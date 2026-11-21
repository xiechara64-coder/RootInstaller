# 造两个「同包名、minSdk 不同」的 APK，用来端到端验证
# 「升级时最低系统要求提高了」这条提示。
#
# 为什么要专门造：
#   · 已装版本的 minSdk 只能反射读（ApplicationInfo.minSdkVersion 是 API 24 才公开的），
#     这段反射逻辑离线测不了，必须在真机上跑。
#   · 设备上现成的第三方应用都是随意挑的，minSdk 不可控，凑不出「新包要求更高」的组合。
#
# 产物：
#   min18.apk  minSdk=18（假装是「已装版本」）
#   min23.apk  minSdk=23（假装是「新版本」，要求提高了）
param([string]$AndroidSdk = "", [string]$BuildTools = "37.0.0")

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $PSScriptRoot "samples"

function Fail($m) { Write-Host "失败：$m" -ForegroundColor Red; exit 1 }

# 必须用项目自己的调试密钥库签名，不能另造一个。
#
# 这份样本的用法是「装 min18、再拿 min23 去覆盖安装」，而覆盖安装要求
# **两边的签名一致**。用别的密钥签就会失败在
# INSTALL_FAILED_UPDATE_INCOMPATIBLE 上，而且报错完全看不出是密钥的原因。
# 所以先确认它存在，给一句能直接照做的提示，而不是等 apksigner 抛个难懂的错。
$ksPath = Join-Path $ProjectRoot "debug.keystore"
if (-not (Test-Path $ksPath)) {
    Fail "找不到 $ksPath。请先跑一次 .\build.ps1 生成它（构建脚本会在首次构建时创建）。"
}

if (-not $AndroidSdk) {
    foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk", "D:\Android\Sdk", "C:\Android\Sdk")) {
        if ($c -and (Test-Path (Join-Path $c "build-tools"))) { $AndroidSdk = $c; break }
    }
}
if (-not $AndroidSdk) { Fail "找不到 SDK" }
$bt = Join-Path $AndroidSdk "build-tools\$BuildTools"
$aapt = Join-Path $bt "aapt.exe"
$apksigner = Join-Path $bt "apksigner.bat"
$androidJar = Get-ChildItem (Join-Path $AndroidSdk "platforms") -Directory |
    Sort-Object Name | ForEach-Object { Join-Path $_.FullName "android.jar" } |
    Where-Object { Test-Path $_ } | Select-Object -First 1

$javac = Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName "bin\javac.exe" } |
    Where-Object { Test-Path $_ } | Select-Object -First 1

$work = Join-Path $OutDir "_minwork"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $work | Out-Null
New-Item -ItemType Directory -Path (Join-Path $work "assets") | Out-Null
[System.IO.File]::WriteAllText((Join-Path $work "assets\payload.txt"), "x`n",
    (New-Object System.Text.UTF8Encoding($false)))

# 一个能跑起来的空白 Activity，让包看起来正常
$pkgDir = Join-Path $work "src\com\example\sdkdemo"
New-Item -ItemType Directory -Path $pkgDir -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $pkgDir "BlankActivity.java"), @'
package com.example.sdkdemo;
import android.app.Activity;
import android.os.Bundle;
public class BlankActivity extends Activity {
    @Override protected void onCreate(Bundle b) { super.onCreate(b); }
}
'@, (New-Object System.Text.UTF8Encoding($false)))

$classesDir = Join-Path $work "classes"
New-Item -ItemType Directory -Path $classesDir | Out-Null
& cmd.exe /c "`"$javac`" -source 8 -target 8 -bootclasspath `"$androidJar`" -d `"$classesDir`" `"$pkgDir\BlankActivity.java`" > `"$work\javac.log`" 2>&1"
if ($LASTEXITCODE -ne 0) { Get-Content "$work\javac.log" | ForEach-Object { Write-Host $_ }; Fail "javac" }

# 必须塞进 classes.dex，否则 pm install 会报 INSTALL_FAILED_DEXOPT。
# （第一版就漏了这一步，装的时候才发现 —— 空壳 APK 对「读基本信息」够用，
#   但要真的装上就必须有 dex。）
$d8 = Join-Path $bt "d8.bat"
$jar = Join-Path (Split-Path -Parent $javac) "jar.exe"
$classesJar = Join-Path $work "classes.jar"
$dexDir = Join-Path $work "dex"
New-Item -ItemType Directory -Path $dexDir | Out-Null
& cmd.exe /c "`"$jar`" cf `"$classesJar`" -C `"$classesDir`" . > `"$work\jar.log`" 2>&1"
& cmd.exe /c "`"$d8`" --min-api 15 --output `"$dexDir`" `"$classesJar`" > `"$work\d8.log`" 2>&1"
if ($LASTEXITCODE -ne 0) { Get-Content "$work\d8.log" | ForEach-Object { Write-Host $_ }; Fail "d8" }
if (-not (Test-Path (Join-Path $dexDir "classes.dex"))) { Fail "没有生成 classes.dex" }
Write-Host "  classes.dex 已生成"

# 每个 minSdk 一个目录 —— aapt 要求文件名必须就叫 AndroidManifest.xml
foreach ($pair in @(@("min18", 18), @("min23", 23))) {
    $name = $pair[0]; $minSdk = $pair[1]
    $d = Join-Path $work $name
    New-Item -ItemType Directory -Path $d -Force | Out-Null
    $manifest = @"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.sdkdemo"
    android:versionCode="1"
    android:versionName="1.0">
    <uses-sdk android:minSdkVersion="$minSdk" android:targetSdkVersion="18" />
    <application android:label="SdkDemo">
        <activity android:name=".BlankActivity" />
    </application>
</manifest>
"@
    [System.IO.File]::WriteAllText((Join-Path $d "AndroidManifest.xml"), $manifest,
        (New-Object System.Text.UTF8Encoding($false)))

    $apk = Join-Path $OutDir "$name.apk"
    & cmd.exe /c "`"$aapt`" package -f -M `"$d\AndroidManifest.xml`" -A `"$work\assets`" -I `"$androidJar`" -F `"$apk`" > `"$work\aapt.log`" 2>&1"
    if ($LASTEXITCODE -ne 0) { Get-Content "$work\aapt.log" | ForEach-Object { Write-Host $_ }; Fail "aapt $name" }

    # 把 dex 塞进去 —— 必须在**签名之前**。
    # 签名之后任何重建 zip 的操作都会把 v2 的 Signing Block 抹掉（这个坑本项目踩过两次）。
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($apk, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.CreateEntry("classes.dex")
        $stream = $entry.Open()
        try {
            $dex = [System.IO.File]::ReadAllBytes((Join-Path $dexDir "classes.dex"))
            $stream.Write($dex, 0, $dex.Length)
        } finally { $stream.Dispose() }
    } finally { $zip.Dispose() }

    & cmd.exe /c "`"$apksigner`" sign --ks `"$ProjectRoot\debug.keystore`" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out `"$apk`" `"$apk`" > `"$work\sign.log`" 2>&1"
    if ($LASTEXITCODE -ne 0) { Get-Content "$work\sign.log" | ForEach-Object { Write-Host $_ }; Fail "签名 $name" }

    $badging = (& cmd.exe /c "`"$aapt`" dump badging `"$apk`" 2>&1") -join ' '
    $got = [regex]::Match($badging, "sdkVersion:'(\d+)'").Groups[1].Value
    Write-Host ("  {0}.apk  minSdk={1}（期望 {2}）" -f $name, $got, $minSdk)
    if ($got -ne "$minSdk") { Fail "$name 的 minSdk 不对" }
}

Write-Host ""
Write-Host "产物在 $OutDir" -ForegroundColor Green
