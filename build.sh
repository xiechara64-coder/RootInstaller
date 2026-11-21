#!/usr/bin/env bash
#
# 用 SDK 自带命令行工具直接构建（Linux / macOS）
# 和 build.ps1 等价，逻辑一一对应。
#
# 用法：
#   ./build.sh
#   ANDROID_HOME=/path/to/sdk ./build.sh
#   ./build.sh --platform 19 --build-tools 34.0.0
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$PROJECT_ROOT/app"
SRC_DIR="$APP_DIR/src/main"
JAVA_DIR="$SRC_DIR/java"
RES_DIR="$SRC_DIR/res"
ASSETS_DIR="$SRC_DIR/assets"
LIBS_DIR="$SRC_DIR/libs"
BUILD_DIR="$PROJECT_ROOT/build"
MANIFEST="$SRC_DIR/AndroidManifest.xml"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
PLATFORM=""
BUILD_TOOLS=""
OUT_APK="RootInstaller.apk"

while [ $# -gt 0 ]; do
  case "$1" in
    --sdk)          ANDROID_SDK="$2"; shift 2 ;;
    --platform)     PLATFORM="$2"; shift 2 ;;
    --build-tools)  BUILD_TOOLS="$2"; shift 2 ;;
    --out)          OUT_APK="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

step()  { printf '\n=== %s ===\n' "$1"; }
fail()  { printf '\n构建失败：%s\n' "$1" >&2; exit 1; }

step "定位 Android SDK"
[ -n "$ANDROID_SDK" ] || fail "没有找到 Android SDK。请设置 ANDROID_HOME，或用 --sdk 指定。"
[ -d "$ANDROID_SDK" ] || fail "SDK 目录不存在: $ANDROID_SDK"
ANDROID_SDK="$(cd "$ANDROID_SDK" && pwd)"
echo "SDK: $ANDROID_SDK"

step "查找 android.jar 与 build-tools"
PLATFORMS_DIR="$ANDROID_SDK/platforms"
[ -d "$PLATFORMS_DIR" ] || fail "SDK 里没有 platforms 目录，请用 sdkmanager 安装平台"

if [ -n "$PLATFORM" ]; then
  ANDROID_JAR="$PLATFORMS_DIR/android-$PLATFORM/android.jar"
else
  # 优先 API 19，包含 4.3 的全部公开 API 且不引入 21+ 的新类
  if [ -d "$PLATFORMS_DIR/android-19" ]; then
    ANDROID_JAR="$PLATFORMS_DIR/android-19/android.jar"
  else
    ANDROID_JAR="$(ls -d "$PLATFORMS_DIR"/android-*/android.jar 2>/dev/null | sort -V | head -n1)"
  fi
fi
[ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ] || fail "找不到 android.jar"
echo "使用平台: $ANDROID_JAR"

BT_DIR="$ANDROID_SDK/build-tools"
[ -d "$BT_DIR" ] || fail "SDK 里没有 build-tools 目录"
if [ -n "$BUILD_TOOLS" ]; then
  BT="$BT_DIR/$BUILD_TOOLS"
else
  BT="$(ls -d "$BT_DIR"/*/ 2>/dev/null | grep -E '/[0-9]+\.[0-9]+\.[0-9]+/$' | sort -V | tail -n1)"
  BT="${BT%/}"
fi
[ -d "$BT" ] || fail "找不到可用的 build-tools"
echo "使用 build-tools: $BT"

AAPT="$BT/aapt"
ZIPALIGN="$BT/zipalign"
[ -x "$AAPT" ] || fail "找不到 aapt"
D8="$BT/d8"
[ -f "$D8" ] || fail "找不到 d8（需要 build-tools 28+）"

step "查找 JDK 工具"
command -v javac >/dev/null 2>&1 || fail "找不到 javac，请安装 JDK"
command -v keytool >/dev/null 2>&1 || fail "找不到 keytool"
JAVAC="$(command -v javac)"
KEYTOOL="$(command -v keytool)"

JAVAC_VER="$("$JAVAC" -version 2>&1 | head -n1)"
echo "javac: $JAVAC_VER"
# JDK 21+ 起 -source/-target 7 已被移除，最低只能到 8
if printf '%s' "$JAVAC_VER" | grep -qE 'javac (2[1-9]|[3-9][0-9])'; then
  JAVA_TARGET="8"
else
  JAVA_TARGET="7"
fi
echo "编译目标字节码版本: $JAVA_TARGET"

step "准备构建目录"
rm -rf "$BUILD_DIR"
GEN_DIR="$BUILD_DIR/gen"
CLASSES_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"
APK_WORK_DIR="$BUILD_DIR/apk"
mkdir -p "$GEN_DIR" "$CLASSES_DIR" "$DEX_DIR" "$APK_WORK_DIR"

step "aapt: 生成 R.java"
"$AAPT" package -f -m \
  -J "$GEN_DIR" \
  -M "$MANIFEST" \
  -S "$RES_DIR" \
  -I "$ANDROID_JAR" \
  --min-sdk-version 15 \
  --target-sdk-version 18 \
  --auto-add-overlay
[ -f "$GEN_DIR/com/dsh/rootinstaller/R.java" ] || fail "R.java 没有生成"
echo "R.java OK"

step "javac: 编译 Java 源码"
CP_EXTRA=""
if [ -d "$LIBS_DIR" ]; then
  for j in "$LIBS_DIR"/*.jar; do
    [ -f "$j" ] && CP_EXTRA="$CP_EXTRA:$j"
  done
fi

find "$JAVA_DIR" -name '*.java' > "$BUILD_DIR/sources.txt"
find "$GEN_DIR"  -name '*.java' >> "$BUILD_DIR/sources.txt"
echo "源文件数量: $(wc -l < "$BUILD_DIR/sources.txt")"

"$JAVAC" \
  -encoding UTF-8 \
  -source "$JAVA_TARGET" -target "$JAVA_TARGET" \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR$CP_EXTRA" \
  -d "$CLASSES_DIR" \
  -nowarn \
  @"$BUILD_DIR/sources.txt"

CLASS_COUNT="$(find "$CLASSES_DIR" -name '*.class' | wc -l)"
[ "$CLASS_COUNT" -gt 0 ] || fail "没有产出任何 class 文件"
echo "编译出 $CLASS_COUNT 个 class 文件"

step "d8: 转换为 dex"
find "$CLASSES_DIR" -name '*.class' > "$BUILD_DIR/classes.txt"
# d8 的参数用 @argfile 传入，避免类多了以后命令行过长
"$D8" \
  --min-api 15 \
  --output "$DEX_DIR" \
  --lib "$ANDROID_JAR" \
  @"$BUILD_DIR/classes.txt" \
  || fail "d8 转换 dex 失败"
[ -f "$DEX_DIR/classes.dex" ] || fail "没有生成 classes.dex"
echo "classes.dex OK"

step "aapt: 打包资源"
UNSIGNED_APK="$APK_WORK_DIR/unsigned.apk"
AAPT_ARGS=(package -f -M "$MANIFEST" -S "$RES_DIR" -I "$ANDROID_JAR" -F "$UNSIGNED_APK"
           --min-sdk-version 15 --target-sdk-version 18)
if [ -d "$ASSETS_DIR" ] && [ -n "$(ls -A "$ASSETS_DIR" 2>/dev/null || true)" ]; then
  AAPT_ARGS+=(-A "$ASSETS_DIR")
fi
if [ -d "$LIBS_DIR" ] && ls "$LIBS_DIR"/*.jar >/dev/null 2>&1; then
  AAPT_ARGS+=(-0 .jar)
fi
"$AAPT" "${AAPT_ARGS[@]}" || fail "aapt 打包失败"

step "把 classes.dex 加入 APK"
# apk 就是 zip，把 dex 塞进去即可（放在根目录，且不压缩更接近标准产物）
cd "$DEX_DIR"
zip -q -X "$UNSIGNED_APK" classes.dex
cd "$PROJECT_ROOT"
echo "classes.dex 已写入"

step "签名 APK"
# 调试密钥库**不能**放 build/ 里。
#
# 踩过的坑：原来放在 build/debug.keystore，而本脚本开头就会 rm -rf 掉整个
# build/ —— 于是每次构建密钥库都被重建，**每次用的是不同密钥**，
# 覆盖安装必然失败：
#     Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]
# 现象很有迷惑性：装到新机器没问题，就是更新不了旧机器上那一份。
# 放到项目根目录（已在 .gitignore 的 *.keystore 里），密钥才长期稳定。
KS_PATH="$PROJECT_ROOT/debug.keystore"
KS_PASS="android"
KS_ALIAS="androiddebugkey"
if [ ! -f "$KS_PATH" ]; then
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KS_PATH" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Android Debug,O=Android,C=US"
fi

ALIGNED_APK="$APK_WORK_DIR/aligned.apk"
if [ -x "$ZIPALIGN" ]; then
  "$ZIPALIGN" -f -p 4 "$UNSIGNED_APK" "$ALIGNED_APK"
else
  cp "$UNSIGNED_APK" "$ALIGNED_APK"
  echo "没有 zipalign，跳过对齐"
fi

FINAL_APK="$PROJECT_ROOT/$OUT_APK"
if [ -x "$BT/apksigner" ]; then
  "$BT/apksigner" sign \
    --ks "$KS_PATH" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" \
    --min-sdk-version 15 \
    --v1-signing-enabled true --v2-signing-enabled false \
    --out "$FINAL_APK" "$ALIGNED_APK"
else
  cp "$ALIGNED_APK" "$FINAL_APK"
  jarsigner -keystore "$KS_PATH" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -sigalg SHA1withRSA -digestalg SHA1 \
    "$FINAL_APK" "$KS_ALIAS"
fi

step "校验产物"
"$AAPT" dump badging "$FINAL_APK" | head -n 5 || true

SIZE_KB=$(( $(wc -c < "$FINAL_APK") / 1024 ))
printf '\n=========================================\n'
printf ' 构建成功！\n'
printf ' APK: %s\n' "$FINAL_APK"
printf ' 体积: %s KB\n' "$SIZE_KB"
printf '=========================================\n\n'
echo "安装到手机：  adb install -r \"$FINAL_APK\""
