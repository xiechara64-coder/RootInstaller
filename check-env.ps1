# ===========================================================================
#  环境自检脚本
#
#  装完 Android Studio 后运行这个，它会告诉你还差什么。
#
#  用法：
#     双击本文件，或在 cmd 里执行：  check-env.cmd
# ===========================================================================

$ErrorActionPreference = "Continue"

function Line { Write-Host ("-" * 62) -ForegroundColor DarkGray }
function Ok   ($m) { Write-Host "  [就绪]  $m" -ForegroundColor Green }
function Bad  ($m) { Write-Host "  [缺失]  $m" -ForegroundColor Red }
function Warn ($m) { Write-Host "  [注意]  $m" -ForegroundColor Yellow }

Write-Host ""
Write-Host "RootInstaller 构建环境自检" -ForegroundColor Cyan
Write-Host "PowerShell $($PSVersionTable.PSVersion)，系统 $([Environment]::OSVersion.VersionString)"
Line

# ---------------------------------------------------------------------------
Write-Host "1. JDK（编译 Java 源码）"
$jdkOk = $true
$javac = Get-Command javac -ErrorAction SilentlyContinue
if ($javac) { Ok "javac  $($javac.Source)" } else { Bad "javac 不在 PATH"; $jdkOk = $false }

# keytool 不查 PATH，按 build.ps1 同样的逻辑去找
$keytool = $null
foreach ($root in @("C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium",
                    "C:\Program Files\Android\Android Studio\jbr",
                    "C:\Program Files\Android\Android Studio\jre")) {
    if (-not (Test-Path $root)) { continue }
    $d = Join-Path $root "bin\keytool.exe"
    if (Test-Path $d) { $keytool = $d; break }
    foreach ($s in (Get-ChildItem $root -Directory -ErrorAction SilentlyContinue)) {
        $p = Join-Path $s.FullName "bin\keytool.exe"
        if (Test-Path $p) { $keytool = $p; break }
    }
    if ($keytool) { break }
}
if ($keytool) { Ok "keytool  $keytool" }
else { Warn "没找到 keytool（装 Android Studio 后它自带的 jbr 里也有）" }

if ($jdkOk) {
    $jv = (& javac -version 2>&1 | Out-String).Trim()
    Ok "版本  $jv"
    if ($jv -match 'javac\s+(\d+)') {
        if ([int]$Matches[1] -ge 21) {
            Write-Host "          （JDK 21+ 已移除 -source 7，脚本会自动用 -source 8）" -ForegroundColor DarkGray
        }
    }
}
Line

# ---------------------------------------------------------------------------
Write-Host "2. Android SDK（编译所需平台与工具）"
$sdkCandidates = @()
if ($env:ANDROID_HOME)      { $sdkCandidates += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT)  { $sdkCandidates += $env:ANDROID_SDK_ROOT }
$sdkCandidates += "$env:LOCALAPPDATA\Android\Sdk"
$sdkCandidates += (Join-Path $PSScriptRoot ".android-sdk")

$sdk = $sdkCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $sdk) {
    Bad "没有找到 SDK"
    Write-Host "         已检查这些位置：" -ForegroundColor DarkGray
    $sdkCandidates | Where-Object { $_ } | ForEach-Object {
        Write-Host "           $_" -ForegroundColor DarkGray
    }
    Write-Host ""
    Write-Host "         -> 打开 Android Studio，走完首次设置向导即可。" -ForegroundColor Yellow
} else {
    Ok "SDK 目录  $sdk"

    # platforms
    $pd = Join-Path $sdk "platforms"
    $plats = @()
    if (Test-Path $pd) {
        $plats = @(Get-ChildItem $pd -Directory -ErrorAction SilentlyContinue |
                   Where-Object { Test-Path (Join-Path $_.FullName "android.jar") })
    }
    if ($plats.Count -eq 0) {
        Bad "platforms 里没有可用平台"
        Write-Host "         -> SDK Manager 里勾选：SDK Platforms -> Android 4.4 (API 19)" -ForegroundColor Yellow
    } else {
        $names = ($plats | ForEach-Object { $_.Name }) -join ", "
        Ok "平台      $names"
        $has19 = $plats | Where-Object { $_.Name -eq "android-19" }
        if (-not $has19) {
            Warn "没有 android-19；脚本会用其他平台，但建议补装 API 19（最贴近 Android 4.3 的公开 API）"
        }
    }

    # build-tools
    $bd = Join-Path $sdk "build-tools"
    $bts = @()
    if (Test-Path $bd) {
        $bts = @(Get-ChildItem $bd -Directory -ErrorAction SilentlyContinue |
                 Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
                 Sort-Object { [version]$_.Name } -Descending)
    }
    if ($bts.Count -eq 0) {
        Bad "build-tools 里没有可用版本"
        Write-Host "         -> SDK Manager 里勾选：SDK Tools -> Android SDK Build-Tools" -ForegroundColor Yellow
    } else {
        $bt = $bts[0].FullName
        Ok "build-tools  $($bts[0].Name)"
        foreach ($t in @("aapt.exe", "d8.bat", "zipalign.exe", "apksigner.bat")) {
            $p = Join-Path $bt $t
            if (Test-Path $p) { Ok "   $t" } else { Warn "   缺 $t（某些步骤会退化）" }
        }
    }
}
Line

# ---------------------------------------------------------------------------
Write-Host "3. adb（把 APK 装到手机用，构建本身不需要）"
$adb = Get-Command adb -ErrorAction SilentlyContinue
if ($adb) {
    Ok "adb  $($adb.Source)"
    $devs = (& adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match '\S' })
    if ($devs) {
        Write-Host "         已连接设备：" -ForegroundColor DarkGray
        $devs | ForEach-Object { Write-Host "           $_" -ForegroundColor DarkGray }
    } else {
        Write-Host "         未检测到设备（手机需开启 USB 调试）" -ForegroundColor DarkGray
    }
} else {
    Warn "adb 不在 PATH"
    Write-Host "         -> Android Studio 自带，位于 <SDK>\platform-tools\adb.exe" -ForegroundColor Yellow
    Write-Host "            可以把这个目录加到 PATH，或者直接用它的完整路径。" -ForegroundColor Yellow
}
Line

# ---------------------------------------------------------------------------
Write-Host "4. 结论"
$ready = $jdkOk -and $sdk
if ($ready) {
    Write-Host "  环境齐了，开始构建：" -ForegroundColor Green
    Write-Host "      build.cmd" -ForegroundColor White
    Write-Host ""
    Write-Host "  或指定 SDK 路径：" -ForegroundColor Green
    Write-Host "      build.cmd -AndroidSdk `"$sdk`"" -ForegroundColor White
} else {
    Write-Host "  还差点东西，见上面的 [缺失] 项。" -ForegroundColor Yellow
    Write-Host "  装完 Android Studio 后重新运行本脚本即可。" -ForegroundColor Yellow
}
Write-Host ""
