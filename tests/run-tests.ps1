# 跑全部离线用例。
#
# 这些用例验证的是「不该依赖 Android API 的那部分逻辑」：
# 排序、权限差异、权限能力描述、签名解析。
# 它们能用普通 JVM 跑起来，是因为对应的类刻意没有引用 Android API —— 
# 这本身就是一条设计约束，一旦有人往这些类里加了 Android 调用，这个脚本会立刻编不过。
#
# 用法：powershell -ExecutionPolicy Bypass -File tests\run-tests.ps1

param(
    [string]$AndroidSdk = "",
    [switch]$SkipSignatureSamples
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $ProjectRoot "build\tests"
$SrcDir = Join-Path $ProjectRoot "app\src\main\java\com\dsh\rootinstaller"
$TestsDir = $PSScriptRoot

function Write-Step($m) { Write-Host ""; Write-Host "=== $m ===" -ForegroundColor Cyan }
function Fail($m) { Write-Host ""; Write-Host "失败：$m" -ForegroundColor Red; exit 1 }

# --- 找 javac ---
$javac = $null
$java = $null
foreach ($root in @("C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium",
                    "C:\Program Files\Microsoft", "C:\Program Files\Android\Android Studio\jbr")) {
    if (-not (Test-Path $root)) { continue }
    $found = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
             ForEach-Object { Join-Path $_.FullName "bin\javac.exe" } |
             Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($found) {
        $javac = $found
        $java = Join-Path (Split-Path -Parent $found) "java.exe"
        break
    }
}
if (-not $javac) {
    $c = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($c) { $javac = $c.Source }
    $c2 = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($c2) { $java = $c2.Source }
}
if (-not $javac) { Fail "找不到 javac" }
if (-not $java) { Fail "找不到 java" }

# --- 找 android.jar（只有权限/排序那几组用例需要它）---
if (-not $AndroidSdk) {
    foreach ($c in @((Join-Path $ProjectRoot ".android-sdk"), "$env:LOCALAPPDATA\Android\Sdk",
                     "D:\Android\Sdk", "C:\Android\Sdk", "$env:ANDROID_HOME")) {
        if ($c -and (Test-Path (Join-Path $c "platforms"))) { $AndroidSdk = $c; break }
    }
}
$androidJar = $null
if ($AndroidSdk) {
    $androidJar = Get-ChildItem (Join-Path $AndroidSdk "platforms") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name | ForEach-Object { Join-Path $_.FullName "android.jar" } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}

Write-Host "javac : $javac"
Write-Host "android.jar: $(if ($androidJar) { $androidJar } else { '(未找到)' })"

# 让 JVM 的输出固定成 UTF-8。
# 本机控制台代码页是 936(GBK)，JVM 默认按它编码中文，打出来全是一堆乱码 ——
# 判定本身有效，但一失败就没法读，等于把排查的路堵死了。
$env:JAVA_TOOL_OPTIONS = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

function Invoke-External {
    param([string]$Exe, [string[]]$Arguments, [string]$Label = "")
    if ($Label) { Write-Host "  $Label" }
    $quoted = @()
    foreach ($a in $Arguments) { $quoted += "`"$([string]$a -replace '"', '\"')`"" }
    $log = Join-Path $OutDir "tool.log"
    & cmd.exe /c "`"$Exe`" $($quoted -join ' ') > `"$log`" 2>&1"
    $code = $LASTEXITCODE
    $out = ""
    if (Test-Path $log) { $out = [System.IO.File]::ReadAllText($log, [System.Text.Encoding]::UTF8) }
    return @{ Code = $code; Out = $out }
}

$script:failed = 0
$script:passed = 0

# ---------------------------------------------------------------------------
# 第一组：纯 JVM 用例（对应类一律不引用 Android API）
# ---------------------------------------------------------------------------
Write-Step "第一组：不依赖 Android 的用例"

$pureTests = @(
    @{ Name = "ApkSignaturesCheck"; Sources = @("ApkSignatures.java"); Arg = "samples" }
    @{ Name = "SigCompatCheck";      Sources = @("SigCompat.java", "ApiNames.java"); Arg = "" }
    @{ Name = "SdkChangeCheck";      Sources = @("SdkChange.java", "ApiNames.java"); Arg = "" }
    @{ Name = "IconCacheCheck";      Sources = @("IconCacheKey.java", "IconCachePolicy.java"); Arg = "" }
)
foreach ($t in $pureTests) {
    $sources = @()
    foreach ($s in $t.Sources) { $sources += (Join-Path $SrcDir $s) }
    $sources += (Join-Path $TestsDir "$($t.Name).java")
    $classDir = Join-Path $OutDir $t.Name
    New-Item -ItemType Directory -Path $classDir -Force | Out-Null

    $r = Invoke-External $javac (@("-encoding", "UTF-8", "-nowarn", "-d", $classDir) + $sources) "编译 $($t.Name)"
    if ($r.Code -ne 0) { Write-Host $r.Out; Fail "$($t.Name) 编译失败" }

    $javaArgs = @("-cp", $classDir, $t.Name)
    if ($t.Arg) { $javaArgs += (Join-Path $TestsDir $t.Arg) }
    $r = Invoke-External $java $javaArgs "运行 $($t.Name)"
    Write-Host $r.Out
    if ($r.Code -eq 0) { $script:passed++ } else { $script:failed++ }
}

# 折行清单回归：用一份真实系统应用确认不会误报「被改动过」。
#
# 这份 APK 有 25MB，不进仓库，放在 tests\fixtures\ 下。
# 注意**不能**放 build\ 里 —— 每次构建都会清空 build，样本会被删掉，
# 用例就变成静默跳过（第一版就踩了这个坑）。
# 取法：从真机复制 /storage/sdcard1/apk/HwDLNA.apk（见 README）。
# 取不到时用例会自己跳过并返回 0，不算失败 —— 免得换台机器就跑不通。
$hwApk = Join-Path $TestsDir "fixtures\hw-system-app.apk"
$classDir = Join-Path $OutDir "ApkSignaturesHuaweiCheck"
New-Item -ItemType Directory -Path $classDir -Force | Out-Null
$src = @((Join-Path $SrcDir "ApkSignatures.java"),
         (Join-Path $TestsDir "ApkSignaturesHuaweiCheck.java"))
$r = Invoke-External $javac (@("-encoding", "UTF-8", "-nowarn", "-d", $classDir) + $src) "编译 ApkSignaturesHuaweiCheck"
if ($r.Code -ne 0) {
    Write-Host $r.Out
    Write-Host "  ApkSignaturesHuaweiCheck 编译失败" -ForegroundColor Red
    $script:failed++
} else {
    $r = Invoke-External $java @("-cp", $classDir, "ApkSignaturesHuaweiCheck", $hwApk) "运行 ApkSignaturesHuaweiCheck"
    Write-Host $r.Out
    if ($r.Code -eq 0) { $script:passed++ } else { $script:failed++ }
}

# ---------------------------------------------------------------------------
# 第二组：需要 android.jar 的用例（对应类引用少量 Android API）
# ---------------------------------------------------------------------------
Write-Step "第二组：需要 android.jar 的用例"

if (-not $androidJar) {
    Write-Host "未找到 android.jar，跳过第二组" -ForegroundColor Yellow
} else {
    $androidTests = @("PermSortCheck", "PermAbilitiesCheck", "PermRiskCheck",
                      "PermDiffCheck", "ApkSorterCheck", "ParseCacheCheck")
    $common = @()
    # ApkCard 里用了 ApkSignatures 的状态常量和 SdkChange 的判定，一起编进来
    foreach ($s in @("ApkCard.java", "ApkSorter.java", "Settings.java", "Log.java",
                     "Perms.java", "PermSorter.java", "PermDiff.java", "PermAbilities.java",
                     "ApkSignatures.java", "SigCompat.java", "SdkChange.java", "ApiNames.java",
                     "ParseCacheData.java", "ParseCacheCodec.java", "ParseCache.java",
                     "IconCacheKey.java", "IconCachePolicy.java", "CacheSweep.java")) {
        $common += (Join-Path $SrcDir $s)
    }
    foreach ($name in $androidTests) {
        $classDir = Join-Path $OutDir $name
        New-Item -ItemType Directory -Path $classDir -Force | Out-Null
        # 注意：JDK 9 之后 -bootclasspath 被移除了，JDK 26 会直接报
        # 「目标 26 不再支持选项 --boot-class-path」。所以改用
        # -source/-target 8 加 -cp android.jar：Android 那几个类只用于编译期，
        # 运行时不会真的碰到（运行时会碰到的用例本来就不该放进这一组）。
        $r = Invoke-External $javac (@("-encoding", "UTF-8", "-nowarn",
                "-source", "8", "-target", "8",
                "-cp", $androidJar, "-d", $classDir) + $common +
                @((Join-Path $TestsDir "$name.java"))) "编译 $name"
        if ($r.Code -ne 0) {
            Write-Host $r.Out
            Write-Host "  $name 编译失败" -ForegroundColor Red
            $script:failed++
            continue
        }
        $r = Invoke-External $java @("-cp", $classDir, $name) "运行 $name"
        Write-Host $r.Out
        if ($r.Code -eq 0) { $script:passed++ } else { $script:failed++ }
    }
}

# ---------------------------------------------------------------------------
Write-Step "汇总"
Write-Host ("通过 {0} 组，失败 {1} 组" -f $script:passed, $script:failed)
if ($script:failed -gt 0) {
    Write-Host "有用例未通过" -ForegroundColor Red
    exit 1
}
Write-Host "全部用例通过" -ForegroundColor Green
exit 0
