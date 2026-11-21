# 参与贡献

感谢有兴趣改这个项目。它很小，但有几条约束是踩坑踩出来的，先花两分钟看完能省不少时间。

## 这是什么 / 不是什么

- **是**：一个面向 **Android 4.3 (API 18)** 的 APK 安装器，用 root 调 `pm install`，
  并把系统安装器藏起来的信息（签名方案、权限风险、升降级、权限差异）摊开给用户看。
- **不是**：跨版本通用的现代应用。**不要**引入 `androidx.*`、Material Design 组件、
  Kotlin、协程这类依赖最小 SDK ≥ 21 的东西。

支持范围：**minSdk 15（Android 4.0.3）～ 在 4.3 上完整验证**。

## 环境要求

| 需要 | 说明 |
|---|---|
| JDK | 8 以上都可以（CI 用 17）。编译目标固定为 **字节码 8** |
| Android SDK | `platforms;android-19`（用它的 android.jar）+ `build-tools;34.0.0` |
| Gradle | **不需要**。主力构建链路是 `aapt` + `javac` + `d8` + `apksigner` |

Gradle 配置只是给「想用 Android Studio 打开看看」的人准备的，不是发布路径 ——
AGP 8.x 要求 compileSdk ≥ 21，和本项目目标冲突。

## 构建

```powershell
# Windows
.\check-env.ps1        # 先检查环境
.\build.ps1            # 产物在项目根目录 RootInstaller.apk
```

```bash
# Linux / macOS
./check-env.sh         # 可选
./build.sh
```

缺 `debug.keystore` 时构建脚本会自动生成一个（它只用于本地调试，不要提交）。

## 测试

```powershell
# 先造签名样本（首次或不想要旧样本时）
.\tests\make-signed-samples.ps1 -BuildTools 34.0.0

# 跑全部离线用例（当前 9 组）
.\tests\run-tests.ps1
```

这些用例是**纯 JVM** 跑的，不需要设备。它们能编译通过本身就是一条设计约束：
`ApkSignatures` / `SigCompat` / `SdkChange` / `ApiNames` / `Perms` / `PermSorter` /
`PermDiff` / `PermAbilities` / `ApkSorter` 里**不允许出现 Android API 调用** ——
一旦有人加了，这组用例会立刻编不过。

## 改代码前必须知道的几个坑

1. **`.ps1` 文件必须带 UTF-8 BOM**
   PowerShell 5.1 会把无 BOM 文件按 GBK 解码，中文注释直接变乱码、整份脚本解析失败。
   很多编辑器保存时会悄悄去掉 BOM，改完 `.ps1` 一定要检查。

2. **Java 语言级别按 7 写**
   虽然编译目标是 8，但为了在任何 JDK 上都能过，**避免 lambda、方法引用、`stream()`**，
   匿名内部类捕获的局部变量**显式加 `final`**（Java 7 规则，Java 8 才放宽）。

3. **权限「危不危险」只有一个出处**
   判定在 `Perms.resolveJudgment()`。不要在别处再写一份 —— 历史上就是因为
   系统 `protectionLevel` 和本应用的风险模型各判一次，才出现
   「总评说低风险、明细标危险」的自相矛盾。
   判定尺度：**这条权限能不能拿到用户数据，或控制用户会察觉的东西？**
   `INTERNET`、`ACCESS_NETWORK_STATE`、`VIBRATE` 这类**故意不算危险**，别改回去。

4. **改了危险判定，必须同时改测试**
   `tests/PermRiskCheck.java` 把「加重」和「减轻」两个方向都钉住了：
   既验证 `INTERNET` 被压成普通，也验证内置表未覆盖的冷门危险权限**不会**被漏报。
   只改一边的 PR 会被 CI 拒绝。

5. **不要提交**：`.apk` / `.idsig` / `*.keystore` / `*.jks` / `local.properties` /
   任何真实系统应用的样本 APK（那是厂商版权作品）。`.gitignore` 已经挡了，但别 `git add -f`。

## 提交与 PR

- 提交信息说明**改了什么、为什么**。中文或英文都行，别只写「fix bug」。
- 一个 PR 只做一件事。顺手重构请拆成单独的提交。
- PR 会跑 CI（构建 + 9 组用例），**必须全绿**。
- 涉及界面显示的改动，建议附一张真机截图 —— 这个项目的很多问题只有真机才暴露。

## 关于 root 与安全

- 本项目通过 `su` 执行 `pm install`，**会绕过系统安装界面**。这是它的设计目标，不是缺陷。
- 不接受任何「捆绑推广 APK」「静默安装第三方应用」「绕过付费」类的功能请求。
- 权限风险结论是**基于权限推断**的，不代表应用真的做过那些事。改动相关文案时请保留这个边界说明。

## 许可证

本仓库以 [MIT](LICENSE) 发布。提交 PR 即表示你同意你的贡献以同样的 MIT 条款分发。
