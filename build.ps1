# 用 SDK 自带命令行工具直接构建（不依赖 Gradle / Android Studio）
#
# 为什么不用 Gradle？因为 Android Gradle Plugin 8.x 要求 compileSdk >= 21，
# 而要正确处理 Android 4.3 的目标清单，用 aapt + javac + d8 这条经典链路最稳。
#
# 用法：
#   .\build.ps1                                  # 自动找 SDK，用 debug.keystore 签名
#   .\build.ps1 -AndroidSdk "D:\android-sdk"     # 手动指定
#   .\build.ps1 -Platform 19 -BuildTools 34.0.0  # 指定平台/构建工具
#
# 出正式发布包（用自己的密钥签，且给清单去掉 debuggable）：
#   $env:KS_PASS = "密钥库密码"
#   .\build.ps1 -Release -Keystore D:\keys\release.keystore -KsAlias release
#
#   参数说明：
#     -Keystore  发布密钥库路径。**不给就用项目根的 debug.keystore**
#     -KsAlias   keystore 里的别名。不给会自动从 keytool 输出里识别
#     -KsPass    密钥库密码。不给则读环境变量 KS_PASS（避免密码留在命令历史里）
#     -KeyPass   私钥密码。不给则读 KEY_PASS，再不给就用 KsPass
#     -Release   把清单里的 debuggable 改成 false（发布包不该可调试）
#
#   发布密钥**绝对不要**提交进仓库：.gitignore 已排除 *.keystore / *.jks。

param(
    [string]$AndroidSdk = "",
    [string]$Platform = "",
    [string]$BuildTools = "",
    [string]$OutApk = "RootInstaller.apk",
    [switch]$Release,
    [string]$Keystore = "",
    [string]$KsAlias = "",
    [string]$KsPass = "",
    [string]$KeyPass = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$AppDir = Join-Path $ProjectRoot "app"
$SrcDir = Join-Path $AppDir "src\main"
$JavaDir = Join-Path $SrcDir "java"
$ResDir = Join-Path $SrcDir "res"
$AssetsDir = Join-Path $SrcDir "assets"
$LibsDir = Join-Path $SrcDir "libs"
$BuildDir = Join-Path $ProjectRoot "build"

function Write-Step($msg) {
    Write-Host ""
    Write-Host "=== $msg ===" -ForegroundColor Cyan
}

function Fail($msg) {
    Write-Host ""
    Write-Host "构建失败：$msg" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# 运行外部工具（aapt / javac / d8 / keytool / zipalign / apksigner / jarsigner）
#
# 为什么不能简单地写  & $exe ... 2>&1 | ForEach-Object { Write-Host $_ }
# 因为 Windows PowerShell 会把外部程序写到 stderr 的**每一行**包成 ErrorRecord；
# 而本脚本开头设了 $ErrorActionPreference = "Stop"，
# 于是一句无害的提示（比如 javac 的 "uses or overrides a deprecated API"）
# 就会直接把整个构建中断掉 —— 命令明明成功了，脚本却报构建失败。
#
# 所以统一改成：把 stderr 重定向进 stdout、一起落在文件里，再读回来显示。
# 这样输出内容一行不丢，退出码也准确，且完全不经过 PowerShell 的错误流。
# ---------------------------------------------------------------------------
$script:ToolLog = Join-Path $BuildDir "tool-output.log"

function Invoke-Tool {
    param(
        [Parameter(Mandatory=$true)][string]$Exe,
        [Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments,
        [string]$Label = "",
        # 这些参数**原样拼上、不加引号**。
        # 目前只有一个用途：把 @argfile 传给 d8。
        # 为什么必须不加引号：cmd 会把引号剥掉再交给 d8，于是 d8 看到的是
        # 一个以 @ 开头的普通参数 —— 而它的规则是「@ 前缀表示这是清单文件」，
        # 结果报出很难懂的：
        #     Error in flag file argument: '@':
        #     java.nio.file.AccessDeniedException:
        # 直接拼上去（路径里没有空格）才是 d8 期望的形式。
        [string[]]$RawArguments = @()
    )
    if ($Label) { Write-Host $Label }

    # 关键：每个参数在交给 cmd 之前都要自己加双引号。
    # 不能简单地写 $Arguments -join ' ' —— 那样含空格或逗号的参数
    # （例如 keytool 的 -dname "CN=Android Debug,O=Android,C=US"）
    # 会被 cmd 重新拆成多个参数，导致工具报错。
    # 参数里原有的双引号要按 cmd 的规则转义成 \"。
    $quoted = @()
    foreach ($a in $Arguments) {
        $s = [string]$a
        $s = $s -replace '"', '\"'
        $quoted += "`"$s`""
    }
    foreach ($a in $RawArguments) {
        if ([string]$a -match '\s') {
            # 加引号会被 cmd 剥掉、破坏 @ 前缀；但路径带空格又不加引号会被拆开。
            # 两种情况都不能正确工作，所以直接说清楚，别留下一个莫名其妙的失败。
            Fail "RawArguments 不能含空格（会破坏 @ 前缀语义）：$a"
        }
        $quoted += [string]$a
    }

    # 2>&1 交给 cmd 处理，输出落在文件里
    $cmdLine = "`"$Exe`" $($quoted -join ' ') > `"$script:ToolLog`" 2>&1"
    & cmd.exe /c $cmdLine
    $code = $LASTEXITCODE

    if (Test-Path $script:ToolLog) {
        $log = [System.IO.File]::ReadAllText($script:ToolLog, [System.Text.Encoding]::UTF8)
        if ($log.Trim().Length -gt 0) {
            $log.TrimEnd() -split "`r?`n" | ForEach-Object { Write-Host "  $_" }
        }
    }
    return $code
}

# ---------------------------------------------------------------------------
# 1. 定位 SDK
# ---------------------------------------------------------------------------
Write-Step "定位 Android SDK"
if (-not $AndroidSdk) {
    # 按优先级找：环境变量 -> Android Studio 默认位置 -> 项目里的 .android-sdk
    if ($env:ANDROID_HOME) { $AndroidSdk = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
    elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk") { $AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk" }
    # 最后看项目目录下有没有手动放的 .android-sdk —— 免去配环境变量的麻烦
    elseif (Test-Path (Join-Path $ProjectRoot ".android-sdk")) {
        $AndroidSdk = Join-Path $ProjectRoot ".android-sdk"
    }
}
if (-not $AndroidSdk -or -not (Test-Path $AndroidSdk)) {
    Fail @"
没有找到 Android SDK。这是目前唯一还缺的东西。

有两条路，任选一条：

【A】装 Android Studio（最简单，能顺带拿到 adb 用于安装调试）
    下载：https://developer.android.com/studio
    装完后本脚本会自动在 %LOCALAPPDATA%\Android\Sdk 找到它。
    记得在 SDK Manager 里勾上：
        · SDK Platforms -> Android 4.4 (API 19)
        · SDK Tools    -> Android SDK Build-Tools

【B】只装命令行工具（体积小，几百 MB）
    下载：https://developer.android.com/studio#command-line-tools-only
    解压到任意目录，比如 D:\android-sdk
    然后用里面的 sdkmanager 装两个包：
        sdkmanager "platforms;android-19" "build-tools;34.0.0"
    最后用参数告诉本脚本 SDK 在哪：
        .\build.ps1 -AndroidSdk "D:\android-sdk"

【偷懒做法】把 SDK 直接解压到本项目的 .android-sdk 目录里，
本脚本会自动发现它，不用配任何环境变量。

（如果下载不动，见 README 的「没有网络怎么办」一节。）
"@
}
$AndroidSdk = (Resolve-Path $AndroidSdk).Path
Write-Host "SDK: $AndroidSdk"

# ---------------------------------------------------------------------------
# 2. 找平台（android.jar）与构建工具
# ---------------------------------------------------------------------------
Write-Step "查找 android.jar 与 build-tools"

$platformsDir = Join-Path $AndroidSdk "platforms"
if (-not (Test-Path $platformsDir)) { Fail "SDK 里没有 platforms 目录，请用 sdkmanager 安装一个平台" }

if ($Platform) {
    $androidJar = Join-Path $platformsDir "android-$Platform\android.jar"
    if (-not (Test-Path $androidJar)) { Fail "找不到 $androidJar" }
} else {
    # 优先挑 API 19（4.4，包含 4.3 全部公开 API，且不会引入 API 21+ 的新类）
    $candidates = @(Get-ChildItem $platformsDir -Directory |
        Where-Object { $_.Name -match '^android-(\d+)$' } |
        Sort-Object { [int]($_.Name -replace 'android-', '') })
    if ($candidates.Count -eq 0) { Fail "platforms 目录里没有 android-XX 平台" }

    $preferred = $candidates | Where-Object { $_.Name -eq 'android-19' } | Select-Object -First 1
    if (-not $preferred) { $preferred = $candidates[0] }
    $androidJar = Join-Path $preferred.FullName "android.jar"
    Write-Host "使用平台: $($preferred.Name)（想换用 -Platform 参数指定）"
}

$btDir = Join-Path $AndroidSdk "build-tools"
if (-not (Test-Path $btDir)) { Fail "SDK 里没有 build-tools 目录" }

if ($BuildTools) {
    $bt = Join-Path $btDir $BuildTools
    if (-not (Test-Path $bt)) { Fail "找不到 build-tools\$BuildTools" }
} else {
    # 版本号形如 34.0.0，按数值降序取最新的
    $btList = @(Get-ChildItem $btDir -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending)
    if ($btList.Count -eq 0) { Fail "build-tools 目录里没有可用版本" }
    $bt = $btList[0].FullName
    Write-Host "使用 build-tools: $($btList[0].Name)"
}

$aaptExe = Join-Path $bt "aapt.exe"
$aapt2Exe = Join-Path $bt "aapt2.exe"
$zipalignExe = Join-Path $bt "zipalign.exe"
$apksignerExe = Join-Path $bt "apksigner.bat"
$d8Bat = Join-Path $bt "d8.bat"

if (-not (Test-Path $aaptExe)) { Fail "找不到 aapt.exe（build-tools 不完整？）" }
if (-not (Test-Path $d8Bat)) { Fail "找不到 d8.bat（build-tools 需要 28+ 才有 d8）" }

# ---------------------------------------------------------------------------
# 3. 找 java / javac / keytool / jarsigner
# ---------------------------------------------------------------------------
Write-Step "查找 JDK 工具"

function Resolve-Tool($name) {
    # 1) 先看 PATH
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    # 2) 再看 JAVA_HOME
    if ($env:JAVA_HOME) {
        $p = Join-Path $env:JAVA_HOME "bin\$name.exe"
        if (Test-Path $p) { return $p }
    }

    # 3) 最后在常见 JDK 安装位置里翻。
    #    这一步很必要：Oracle 的 javapath 垫片目录里只有 java/javac/javaw/jshell，
    #    没有 keytool 和 jarsigner，光靠 PATH 会误判成「JDK 装不完整」。
    $roots = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft\jdk",
        "C:\Program Files\Zulu",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jre",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        # 先看 root 本身就是 JDK 的情况
        $direct = Join-Path $root "bin\$name.exe"
        if (Test-Path $direct) { return $direct }
        # 再看里面的子目录（jdk-26.0.2 这种），取名字最大的通常是新版本
        $subs = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending
        foreach ($s in $subs) {
            $p = Join-Path $s.FullName "bin\$name.exe"
            if (Test-Path $p) { return $p }
        }
    }
    return $null
}

$javacExe = Resolve-Tool "javac"
$keytoolExe = Resolve-Tool "keytool"
$jarsignerExe = Resolve-Tool "jarsigner"

if (-not $javacExe) {
    Fail @"
找不到 javac。请安装 JDK（不是只装 JRE），并满足下面任意一条：
  · 把 JDK 的 bin 目录加入 PATH
  · 设置环境变量 JAVA_HOME 指向 JDK 根目录
"@
}
if (-not $keytoolExe) {
    Fail @"
找不到 keytool。它随 JDK 一起安装，通常在 <JDK>\bin\keytool.exe。
已搜索的位置：PATH、JAVA_HOME、以及 C:\Program Files\Java 等常见目录。
如果 JDK 装在别处，请设置 JAVA_HOME 后重试。
"@
}

Write-Host "javac:    $javacExe"
Write-Host "keytool:  $keytoolExe"
if ($jarsignerExe) { Write-Host "jarsigner: $jarsignerExe" }

# Java 版本与构建工具的兼容性提醒。
# 新版 JDK（21+）已经移除了对 -source/-target 7 的支持，最低只能到 8。
# d8 把 class 文件转 dex 时，class 的目标版本必须能被它识别。
#
# 这里同样通过 cmd 重定向：javac -version 是往 stderr 输出的，
# 直接写 2>&1 | Out-String 会被 PowerShell 变成 ErrorRecord，
# 在 $ErrorActionPreference = "Stop" 下会直接中断脚本。
$javaVerRaw = (& cmd.exe /c "`"$javacExe`" -version 2>&1" | Out-String)
Write-Host "javac 版本: $($javaVerRaw.Trim())"
$javaMajor = 8
if ($javaVerRaw -match 'javac\s+(\d+)') { $javaMajor = [int]$Matches[1] }

if ($javaMajor -ge 21) {
    $javaTarget = "8"
} else {
    $javaTarget = "7"
}
Write-Host "编译目标字节码版本: $javaTarget"

# ---------------------------------------------------------------------------
# 4. 准备构建目录
# ---------------------------------------------------------------------------
Write-Step "准备构建目录"
if (Test-Path $BuildDir) { Remove-Item $BuildDir -Recurse -Force }
$genDir = Join-Path $BuildDir "gen"
$classesDir = Join-Path $BuildDir "classes"
$dexDir = Join-Path $BuildDir "dex"
$apkWorkDir = Join-Path $BuildDir "apk"
foreach ($d in @($genDir, $classesDir, $dexDir, $apkWorkDir)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

# ---------------------------------------------------------------------------
# 5. aapt 生成 R.java
# ---------------------------------------------------------------------------
Write-Step "aapt: 生成 R.java"
$manifest = Join-Path $SrcDir "AndroidManifest.xml"
if (-not (Test-Path $manifest)) { Fail "找不到 AndroidManifest.xml" }

# 关于 debuggable：aapt 没有对应的命令行开关（各 build-tools 版本差异很大，
# 实测 build-tools 37 直接报 Unknown option '--debuggable'）。
# 所以这个标记直接写在 AndroidManifest.xml 里（android:debuggable="true"），
# 好处是崩溃后可以用 `adb shell run-as` 进私有目录取日志。
#
# -Release 时要把它改成 false（发布包不该可调试）。做法是复制一份清单、
# 只替换这一处，再用它去做 -M。
# 注意 **aapt 要求文件名必须就是 AndroidManifest.xml** —— 踩过：
# 改名成 AndroidManifest-release.xml 后 aapt 直接报
# "ERROR: No AndroidManifest.xml file found."，所以复制到独立子目录、保持原名。
if ($Release) {
    $relDir = Join-Path $BuildDir "release-manifest"
    New-Item -ItemType Directory -Path $relDir -Force | Out-Null
    $relManifest = Join-Path $relDir "AndroidManifest.xml"
    $rawManifest = [System.IO.File]::ReadAllText($manifest, [System.Text.Encoding]::UTF8)
    if ($rawManifest -notmatch 'android:debuggable\s*=\s*"true"') {
        Write-Host "清单里没有 debuggable=\"true\"，Release 无需改动" -ForegroundColor Yellow
    } else {
        $rawManifest = $rawManifest -replace 'android:debuggable\s*=\s*"true"', 'android:debuggable="false"'
    }
    [System.IO.File]::WriteAllText($relManifest, $rawManifest,
        (New-Object System.Text.UTF8Encoding($false)))
    $manifest = $relManifest
    Write-Host "构建模式: Release（清单已去掉 debuggable）"
} else {
    Write-Host "构建模式: Debug（清单保持 debuggable=true，便于 run-as 取日志）"
}

$rJavaPath = Join-Path $genDir "com\dsh\rootinstaller\R.java"

$code = Invoke-Tool -Exe $aaptExe -Arguments @(
    "package", "-f", "-m",
    "-J", $genDir,
    "-M", $manifest,
    "-S", $ResDir,
    "-I", $androidJar,
    "--min-sdk-version", "15",
    "--target-sdk-version", "18",
    "--auto-add-overlay"
)
if ($code -ne 0) { Fail "aapt 生成 R.java 失败（退出码 $code）" }
if (-not (Test-Path $rJavaPath)) { Fail "R.java 没有生成到预期位置：$rJavaPath" }
Write-Host "R.java OK"

# ---------------------------------------------------------------------------
# 6. javac 编译
# ---------------------------------------------------------------------------
Write-Step "javac: 编译 Java 源码"

$sourceFiles = @(Get-ChildItem $JavaDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
$sourceFiles += @(Get-ChildItem $genDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
Write-Host "源文件数量: $($sourceFiles.Count)"

# 额外依赖（如果有的话）
$cpExtra = @()
if (Test-Path $LibsDir) {
    $cpExtra = @(Get-ChildItem $LibsDir -Filter *.jar | Select-Object -ExpandProperty FullName)
}
$classpath = (@($androidJar) + $cpExtra) -join ";"

$argFile = Join-Path $BuildDir "sources.txt"

# javac 的 @argfile 必须是「UTF-8 无 BOM」。
#
# 这里刻意不用 Set-Content -Encoding UTF8：在 Windows PowerShell 5.1 下
# 它会写入 UTF-8 BOM，而 javac 不会跳过 BOM，会把 BOM 的三个字节当成
# 文件名的第一个字符，报出这种非常迷惑的错误：
#     错误: 无效文件名: ?D:\...\ApkCard.java
# 所以用 .NET API 显式指定不带 BOM 的 UTF8 编码。
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($argFile, [string[]]$sourceFiles, $utf8NoBom)

Write-Host "源文件清单: $argFile"

$code = Invoke-Tool -Exe $javacExe -Arguments @(
    "-encoding", "UTF-8",
    "-source", $javaTarget, "-target", $javaTarget,
    "-bootclasspath", $androidJar,
    "-classpath", $classpath,
    "-d", $classesDir,
    "-nowarn",
    "@$argFile"
)
if ($code -ne 0) { Fail "javac 编译失败（退出码 $code），请检查上面的报错" }

$classCount = @(Get-ChildItem $classesDir -Recurse -Filter *.class).Count
if ($classCount -eq 0) { Fail "没有产出任何 class 文件" }
Write-Host "编译出 $classCount 个 class 文件"

# ---------------------------------------------------------------------------
# 7. d8 转 dex
# ---------------------------------------------------------------------------
Write-Step "d8: 转换为 dex"

$classFiles = @(Get-ChildItem $classesDir -Recurse -Filter *.class | Select-Object -ExpandProperty FullName)
$dexOut = Join-Path $dexDir "classes.dex"
Write-Host "class 文件数量: $($classFiles.Count)"

# 把 class 文件列表写进 @argfile 交给 d8，而不是直接当命令行参数。
#
# 踩过的坑：原来是把列表直接拼进命令行，注释里写着「约 2000 字符，
# 远低于 Windows 8191 的上限」。后来类数涨到 101 个就真的超了，报：
#     The command line is too long.
# 这类问题会随着工程变大突然出现，跟代码本身对不对毫无关系 ——
# 典型的「昨天还好好的」故障。所以直接用 d8 自己的 @argfile 支持。
#
# 不要用 --file-per-class：那会把每个类拆成独立的 dex 文件，
# 是老平台做 multidex 的路子，和这里「合成一个 classes.dex」的目的相反。
$d8ArgFile = Join-Path $BuildDir "d8-args.txt"
[System.IO.File]::WriteAllLines($d8ArgFile, [string[]]$classFiles, $utf8NoBom)
# @argfile 走 RawArguments（不加引号），否则 @ 前缀会被引号破坏，见 Invoke-Tool 的注释
$code = Invoke-Tool -Exe $d8Bat -Arguments @(
    "--min-api", "15", "--output", $dexDir, "--lib", $androidJar
) -RawArguments @("@" + $d8ArgFile)
if ($code -ne 0) { Fail "d8 转换 dex 失败（退出码 $code）" }
if (-not (Test-Path $dexOut)) { Fail "没有生成 classes.dex" }
Write-Host "classes.dex OK ($([math]::Round((Get-Item $dexOut).Length / 1KB, 1)) KB)"

# ---------------------------------------------------------------------------
# 8. aapt 打包资源
# ---------------------------------------------------------------------------
Write-Step "aapt: 打包资源"
$unsignedApk = Join-Path $apkWorkDir "unsigned.apk"

$aaptArgs = @(
    "package", "-f",
    "-M", $manifest,
    "-S", $ResDir,
    "-I", $androidJar,
    "-F", $unsignedApk,
    "--min-sdk-version", "15",
    "--target-sdk-version", "18"
)
if (Test-Path $AssetsDir) {
    $hasAssets = @(Get-ChildItem $AssetsDir -Recurse -File).Count -gt 0
    if ($hasAssets) { $aaptArgs += @("-A", $AssetsDir) }
}
if (Test-Path $LibsDir) {
    $hasJars = @(Get-ChildItem $LibsDir -Filter *.jar).Count -gt 0
    if ($hasJars) { $aaptArgs += @("-0", ".jar") }
}

$code = Invoke-Tool -Exe $aaptExe -Arguments $aaptArgs
if ($code -ne 0) { Fail "aapt 打包失败（退出码 $code）" }
Write-Host "资源打包 OK"

# 把 classes.dex 塞进 apk（用 zip 的方式，apk 本身就是 zip）
Write-Step "把 classes.dex 加入 APK"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::Open($unsignedApk, 'Update')
try {
    # 如果 aapt 已经放了同名条目，先删掉
    $existing = $zip.Entries | Where-Object { $_.FullName -eq "classes.dex" }
    foreach ($e in $existing) { $e.Delete() }

    $entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $stream = $entry.Open()
    try {
        $bytes = [System.IO.File]::ReadAllBytes($dexOut)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally {
        $stream.Dispose()
    }
} finally {
    $zip.Dispose()
}
Write-Host "classes.dex 已写入"

# ---------------------------------------------------------------------------
# 9. 签名
# ---------------------------------------------------------------------------
Write-Step "签名 APK"

# 签名密钥的两种来源：
#
#  1) 不提供 -Keystore —— 用项目根目录的 debug.keystore。
#     文件名叫 debug，但它其实是**稳定的本机密钥**，只要这个文件还在，
#     覆盖安装就一直正常。
#
#     踩过的坑：原来放在 $BuildDir\debug.keystore，而 build\ 是构建产物目录
#     （tests\run-tests.ps1 会清空它，手工清理也常整个删掉）。密钥一被删就会重新生成
#     —— 于是每次构建用的是不同密钥，覆盖安装必然失败：
#         adb: failed to install ... Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]
#     现象很迷惑：代码没错、APK 也能装到新机器上，就是更新不了旧机器上那一份。
#     所以放到项目根目录（和 build.gradle 同级），并加进 .gitignore。
#
#  2) 提供 -Keystore —— 用你自己的发布密钥，并启用 v2/v3 签名。
#     密码可以走环境变量（KS_PASS / KEY_PASS），避免明文留在命令历史或 CI 日志里。
#     **发布密钥绝不能提交进仓库**（.gitignore 已排除 *.keystore / *.jks）。
$useReleaseKey = [bool]$Keystore
# 调试密钥沿用原来的方案（v1 + v3，v2 关闭）；发布包补齐 v2，
# 因为 v2 覆盖 Android 7.0–8.1，而 v3 只覆盖 9+。
$v2Enabled = $(if ($useReleaseKey) { "true" } else { "false" })

if ($useReleaseKey) {
    $ksPath = [System.IO.Path]::GetFullPath($Keystore)
    if (-not (Test-Path $ksPath)) { Fail "找不到 keystore：$ksPath" }

    $ksPass = $(if ($KsPass) { $KsPass } else { $env:KS_PASS })
    if (-not $ksPass) {
        Fail "使用 -Keystore 时必须提供 -KsPass，或设置环境变量 KS_PASS（不交互提示，方便 CI）"
    }
    $keyPass = $(if ($KeyPass) { $KeyPass } else { $env:KEY_PASS })
    if (-not $keyPass) { $keyPass = $ksPass }

    if ($KsAlias) {
        $ksAlias = $KsAlias
    } else {
        # 自动识别别名。
        #
        # 这里刻意**不解析 "Alias name:" / "别名:"** —— 那是 keytool -v 的格式，
        # 而且会随系统语言变化；更麻烦的是 keytool 按控制台代码页（本机 936/GBK）
        # 输出，而脚本统一按 UTF-8 读日志，中文部分必然变乱码。
        #
        # 所以用**非 verbose** 的输出，它的第一条字段就是别名，且与语言无关：
        #     release, 2026年11月21日, PrivateKeyEntry,
        # 正则只依赖「行首若干字符 + 逗号 + 数字」，ASCII 别名不会受编码影响。
        Write-Host "未指定 -KsAlias，尝试从 keystore 自动识别…"
        $listLog = Join-Path $BuildDir "keystore-list.log"
        & cmd.exe /c "`"$keytoolExe`" -list -keystore `"$ksPath`" -storepass `"$ksPass`" > `"$listLog`" 2>&1"
        $listCode = $LASTEXITCODE
        if ($listCode -ne 0) {
            Fail "读取 keystore 失败（密码错误？退出码 $listCode）。也可以加 -KsAlias 跳过自动识别。"
        }
        $listing = ""
        if (Test-Path $listLog) {
            $listing = [System.IO.File]::ReadAllText($listLog, [System.Text.Encoding]::UTF8)
        }
        $m = [regex]::Match($listing, '(?m)^\s*([^,\r\n]+?)\s*,\s*\d')
        if (-not $m.Success) {
            Fail "无法自动识别 keystore 别名，请用 -KsAlias 指定（可先用 keytool -list -keystore <文件> 查看）"
        }
        $ksAlias = $m.Groups[1].Value.Trim()
        Write-Host "识别到别名：$ksAlias"
    }
    Write-Host "签名密钥: $ksPath"
    Write-Host "别名: $ksAlias（发布签名，v1+v2+v3）"
} else {
    $ksPath = Join-Path $ProjectRoot "debug.keystore"
    $ksPass = "android"
    $keyPass = "android"
    $ksAlias = "androiddebugkey"

    if (-not (Test-Path $ksPath)) {
        Write-Host "生成调试用 keystore…"
        $code = Invoke-Tool -Exe $keytoolExe -Arguments @(
            "-genkeypair", "-v",
            "-keystore", $ksPath,
            "-alias", $ksAlias,
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-storepass", $ksPass, "-keypass", $keyPass,
            "-dname", "CN=Android Debug,O=Android,C=US"
        )
        if ($code -ne 0) { Fail "生成 keystore 失败（退出码 $code）" }
    }
    Write-Host "签名密钥: $ksPath（调试用；要发布请用 -Keystore 指定自己的密钥）"
}

$alignedApk = Join-Path $apkWorkDir "aligned.apk"
$finalApk = Join-Path $ProjectRoot $OutApk

if (Test-Path $zipalignExe) {
    $code = Invoke-Tool -Exe $zipalignExe -Arguments @("-f", "-p", "4", $unsignedApk, $alignedApk)
    if ($code -ne 0) { Fail "zipalign 失败（退出码 $code）" }
    Write-Host "对齐 OK"
} else {
    Copy-Item $unsignedApk $alignedApk -Force
    Write-Host "没有 zipalign，跳过对齐（能装，但效率略差）" -ForegroundColor Yellow
}

$signed = $false
if (Test-Path $apksignerExe) {
    $code = Invoke-Tool -Exe $apksignerExe -Arguments @(
        "sign",
        "--ks", $ksPath, "--ks-pass", "pass:$ksPass", "--key-pass", "pass:$keyPass",
        "--ks-key-alias", $ksAlias,
        "--min-sdk-version", "15",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", $v2Enabled,
        "--v3-signing-enabled", "true",
        "--out", $finalApk, $alignedApk
    )
    if ($code -eq 0 -and (Test-Path $finalApk)) { $signed = $true }
}

if (-not $signed) {
    Write-Host "apksigner 不可用或失败，改用 jarsigner" -ForegroundColor Yellow
    Copy-Item $alignedApk $finalApk -Force
    $code = Invoke-Tool -Exe $jarsignerExe -Arguments @(
        "-keystore", $ksPath, "-storepass", $ksPass, "-keypass", $keyPass,
        "-sigalg", "SHA1withRSA", "-digestalg", "SHA1",
        $finalApk, $ksAlias
    )
    if ($code -ne 0) { Fail "签名失败（退出码 $code）" }
    $signed = $true
}

# 签名结果自检：打印实际生效的签名方案与证书指纹。
# 这一步值得留下 —— 发布时最容易搞混的就是「这个包到底是哪把钥匙签的」。
if (Test-Path $apksignerExe) {
    $verifyLog = Join-Path $BuildDir "verify.log"
    & cmd.exe /c "`"$apksignerExe`" verify --verbose --print-certs `"$finalApk`" > `"$verifyLog`" 2>&1"
    if (Test-Path $verifyLog) {
        Write-Host "签名校验："
        [System.IO.File]::ReadAllText($verifyLog, [System.Text.Encoding]::UTF8) -split "`r?`n" |
            Where-Object { $_ -match '^(Verified using v[123]|Number of signers|Signer #1 certificate SHA-1)' } |
            ForEach-Object { Write-Host ("  " + $_.Trim()) }
    }
}

# ---------------------------------------------------------------------------
# 10. 校验产物
# ---------------------------------------------------------------------------
Write-Step "校验产物"
$null = Invoke-Tool -Exe $aaptExe -Arguments @("dump", "badging", $finalApk)

$size = [math]::Round((Get-Item $finalApk).Length / 1KB, 1)
Write-Host ""
Write-Host "=========================================" -ForegroundColor Green
Write-Host " 构建成功！" -ForegroundColor Green
Write-Host " APK: $finalApk" -ForegroundColor Green
Write-Host " 体积: $size KB" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
Write-Host ""
Write-Host "安装到手机（USB 调试打开后）："
Write-Host "  adb install -r `"$finalApk`""
Write-Host ""
Write-Host "如果手机上的 adb 版本太老装不上，可以先把 apk 拷到 sdcard 再手动点装："
Write-Host "  adb push `"$finalApk`" /sdcard/RootInstaller.apk"
