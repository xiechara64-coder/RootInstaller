# 生成签名状态检测用的样本 APK。
#
# 为什么要造样本：签名判断的坑全在格式细节上（Signing Block 偏移、证书在
# signed-data 里的位置、MANIFEST.MF 摘要链），而这些在真机上完全没法调试。
# 用 apksigner 造出「各种签名组合」的包，就能在 PC 上离线验证判断逻辑。
#
# 产物（落在 tests/samples/）：
#   base-unsigned.apk   完全不签名
#   signed-v1.apk       只签 v1
#   signed-v2.apk       只签 v2          <-- 最关键：不能被误判成「未签名」
#   signed-v2v3.apk     只签 v2+v3
#   signed-v1v2v3.apk   v1+v2+v3（现代发布包的典型形态）
#   tampered-v1.apk     在 v1 签名后改动包内容（模拟「改了包没重签名」）

param(
    [string]$AndroidSdk = "",
    [string]$BuildTools = "36.0.0"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $PSScriptRoot "samples"
$Work = Join-Path $OutDir "_work"

function Write-Step($m) { Write-Host ""; Write-Host "=== $m ===" -ForegroundColor Cyan }
function Fail($m) { Write-Host ""; Write-Host "造样本失败：$m" -ForegroundColor Red; exit 1 }

# --- 找 SDK（和 build.ps1 保持一致的搜索顺序）---
if (-not $AndroidSdk) {
    $candidates = @(
        (Join-Path $ProjectRoot ".android-sdk"),
        "$env:LOCALAPPDATA\Android\Sdk",
        "D:\Android\Sdk", "C:\Android\Sdk",
        "$env:ANDROID_HOME", "$env:ANDROID_SDK_ROOT"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c "build-tools"))) { $AndroidSdk = $c; break }
    }
}
if (-not $AndroidSdk) { Fail "找不到 Android SDK，请用 -AndroidSdk 指定" }

$BtDir = Join-Path $AndroidSdk "build-tools\$BuildTools"
$aapt = Join-Path $BtDir "aapt.exe"
$zipalign = Join-Path $BtDir "zipalign.exe"
$apksigner = Join-Path $BtDir "apksigner.bat"
foreach ($t in @($aapt, $zipalign, $apksigner)) {
    if (-not (Test-Path $t)) { Fail "缺少工具：$t" }
}

$Platforms = Join-Path $AndroidSdk "platforms"
$androidJar = Get-ChildItem $Platforms -Directory | Sort-Object Name | ForEach-Object {
    Join-Path $_.FullName "android.jar"
} | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $androidJar) { Fail "找不到 android.jar（$Platforms）" }

# --- javac：优先用 JDK 目录里的，PATH 上的往往是 JRE 没编译器 ---
$javac = $null
$jdkRoots = @("C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium",
              "C:\Program Files\Microsoft", "C:\Program Files\Android\Android Studio\jbr")
foreach ($root in $jdkRoots) {
    if (-not (Test-Path $root)) { continue }
    $found = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
             ForEach-Object { Join-Path $_.FullName "bin\javac.exe" } |
             Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($found) { $javac = $found; break }
}
if (-not $javac) {
    $c = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($c) { $javac = $c.Source }
}
if (-not $javac) { Fail "找不到 javac" }

Write-Host "SDK        : $AndroidSdk"
Write-Host "build-tools: $BuildTools"
Write-Host "android.jar: $androidJar"
Write-Host "javac      : $javac"

# --- 调用外部工具：输出重定向到文件再读回，避免 stderr 被包成 ErrorRecord ---
$script:ToolLog = Join-Path $OutDir "tool.log"
function Invoke-Tool {
    param([string]$Exe, [string[]]$Arguments, [string]$Label = "")
    if ($Label) { Write-Host "  $Label" }
    $quoted = @()
    foreach ($a in $Arguments) {
        $s = [string]$a -replace '"', '\"'
        $quoted += "`"$s`""
    }
    $cmdLine = "`"$Exe`" $($quoted -join ' ') > `"$script:ToolLog`" 2>&1"
    & cmd.exe /c $cmdLine
    $code = $LASTEXITCODE
    $out = ""
    if (Test-Path $script:ToolLog) {
        $out = [System.IO.File]::ReadAllText($script:ToolLog, [System.Text.Encoding]::UTF8)
    }
    return @{ Code = $code; Out = $out }
}

if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir | Out-Null

# ---------------------------------------------------------------------------
# 1. 造一个最小的 base.apk
# ---------------------------------------------------------------------------
Write-Step "生成最小 APK"

$srcDir = Join-Path $Work "src"
$pkgDir = Join-Path $srcDir "com\example\sigcheck"
New-Item -ItemType Directory -Path $pkgDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Work "assets") -Force | Out-Null

# 一个能跑起来的空 Activity，保证 aapt 有东西可打包
$javaSrc = @'
package com.example.sigcheck;

import android.app.Activity;
import android.os.Bundle;

public class BlankActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
    }
}
'@
[System.IO.File]::WriteAllText((Join-Path $pkgDir "BlankActivity.java"), $javaSrc,
    (New-Object System.Text.UTF8Encoding($false)))

# 一个填充用的资产文件：让包有内容，改包测试才有东西可改
[System.IO.File]::WriteAllText((Join-Path $Work "assets\payload.txt"),
    ("payload for tamper test " + ("x" * 500) + "`n"),
    (New-Object System.Text.UTF8Encoding($false)))

$manifest = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.sigcheck"
    android:versionCode="1"
    android:versionName="1.0">
    <uses-sdk android:minSdkVersion="15" android:targetSdkVersion="18" />
    <application android:label="SigCheck">
        <activity android:name=".BlankActivity" />
    </application>
</manifest>
'@
$manifestPath = Join-Path $Work "AndroidManifest.xml"
[System.IO.File]::WriteAllText($manifestPath, $manifest, (New-Object System.Text.UTF8Encoding($false)))

# 编译 class
$classesDir = Join-Path $Work "classes"
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
$r = Invoke-Tool $javac @("-source", "8", "-target", "8",
        "-bootclasspath", $androidJar,
        "-d", $classesDir,
        (Join-Path $pkgDir "BlankActivity.java")) "编译空白 Activity"
if ($r.Code -ne 0) { Write-Host $r.Out; Fail "javac 失败" }

# d8 转成 dex —— 签名检测本身不需要 dex，但带 dex 的包才算个像样的 APK
$d8 = Join-Path $BtDir "d8.bat"
if (-not (Test-Path $d8)) { $d8 = Join-Path $BtDir "d8" }
$classesJar = Join-Path $Work "classes.jar"
# 注意：$env:JAVA_HOME 在本机是空的，必须先判空再拼路径
$jar = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\jar.exe"
    if (Test-Path $candidate) { $jar = $candidate }
}
if (-not $jar) {
    # javac 同目录下必定有 jar.exe，顺着 javac 的位置找最可靠
    $candidate = Join-Path (Split-Path -Parent $javac) "jar.exe"
    if (Test-Path $candidate) { $jar = $candidate }
}
if (-not $jar) {
    $jar = Get-ChildItem "C:\Program Files\Java" -Recurse -Filter jar.exe -ErrorAction SilentlyContinue |
           Select-Object -First 1 -ExpandProperty FullName
}
if ($jar) {
    Remove-Item $classesJar -ErrorAction SilentlyContinue
    $r = Invoke-Tool $jar @("cf", $classesJar, "-C", $classesDir, ".") "打包 classes.jar"
    $dexDir = Join-Path $Work "dex"
    New-Item -ItemType Directory -Path $dexDir -Force | Out-Null
    $r = Invoke-Tool $d8 @("--min-api", "15", "--output", $dexDir, $classesJar) "d8 生成 dex"
    if ($r.Code -ne 0) { Write-Host $r.Out; Fail "d8 失败" }
}

$baseApk = Join-Path $OutDir "base-unsigned.apk"
$stageDir = Join-Path $Work "stage"
New-Item -ItemType Directory -Path $stageDir -Force | Out-Null
if (Test-Path (Join-Path $Work "dex\classes.dex")) {
    Copy-Item (Join-Path $Work "dex\classes.dex") (Join-Path $stageDir "classes.dex")
}
Copy-Item (Join-Path $Work "assets\payload.txt") (Join-Path $stageDir "payload.txt")

$r = Invoke-Tool $aapt @("package", "-f", "-M", $manifestPath,
        "-A", (Join-Path $Work "assets"),
        "-I", $androidJar, "-F", $baseApk) "aapt 打包"
if ($r.Code -ne 0) { Write-Host $r.Out; Fail "aapt 打包失败" }

# 补进 dex。
#
# 两个必须注意的点：
#  1. 顺序上必须在**签名之前**完成。apksigner 签完 v2/v3 之后，Signing Block 是
#     压在 zip 尾部中央目录前面的；此时任何再动这个 zip 的操作（aapt add 也算）
#     都会重建压缩包，把 Signing Block 整个抹掉 —— 那样得到的「v2 样本」其实
#     根本没有签名，用例就白测了。（第一版就是这么翻车的。）
#  2. 用 ZipArchive 而不是 aapt add：aapt add 的条目名要相对当前目录，
#     而 ZipArchive 用 "classes.dex" 这种正斜杠名字最稳，跨 Windows 也没问题。
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path (Join-Path $Work "dex\classes.dex")) {
    $zip = [System.IO.Compression.ZipFile]::Open($baseApk, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.CreateEntry("classes.dex")
        $stream = $entry.Open()
        try {
            $dexBytes = [System.IO.File]::ReadAllBytes((Join-Path $Work "dex\classes.dex"))
            $stream.Write($dexBytes, 0, $dexBytes.Length)
        } finally {
            $stream.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
    Write-Host "  已加入 classes.dex"
}

# ---------------------------------------------------------------------------
# 1b. 再造一份 targetSdk = 29 的包
#
# 为什么要单独造：用来验证「只有 v1 签名 + targetSdk 29」这个组合。
# 它是老包的常态 —— 到处都能装，不该被误拦。
# 而「只有 v1 + targetSdk ≥ 30」才会被 Android 11+ 拒装，
# 两者的分界线正是 targetSdk 是否达到 30，必须有真实样本才能钉住。
# ---------------------------------------------------------------------------
Write-Step "生成 targetSdk=29 的包（用于签名 × 系统版本判定）"

$manifest29 = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.sigcheck"
    android:versionCode="1"
    android:versionName="1.0">
    <uses-sdk android:minSdkVersion="15" android:targetSdkVersion="29" />
    <application android:label="SigCheck">
        <activity android:name=".BlankActivity" />
    </application>
</manifest>
'@
$manifestPath29 = Join-Path $Work "AndroidManifest29.xml"
[System.IO.File]::WriteAllText($manifestPath29, $manifest29, (New-Object System.Text.UTF8Encoding($false)))
# 但 aapt 要求 -M 指的文件**名字就叫 AndroidManifest.xml**：
# 传别的文件名它会直接报「ERROR: No AndroidManifest.xml file found.」——
# 它是在找这个固定文件名，不是在读你给的路径。所以另放一个目录。
$dir29 = Join-Path $Work "m29"
New-Item -ItemType Directory -Path $dir29 -Force | Out-Null
$manifestPath29 = Join-Path $dir29 "AndroidManifest.xml"
[System.IO.File]::WriteAllText($manifestPath29, $manifest29, (New-Object System.Text.UTF8Encoding($false)))

$baseApk29 = Join-Path $OutDir "base-unsigned-target29.apk"
$r = Invoke-Tool $aapt @("package", "-f", "-M", $manifestPath29,
        "-A", (Join-Path $Work "assets"),
        "-I", $androidJar, "-F", $baseApk29) "aapt 打包（targetSdk 29）"
if ($r.Code -ne 0) { Write-Host $r.Out; Fail "aapt 打包失败（targetSdk 29）" }

if (Test-Path (Join-Path $Work "dex\classes.dex")) {
    $zip = [System.IO.Compression.ZipFile]::Open($baseApk29, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.CreateEntry("classes.dex")
        $stream = $entry.Open()
        try {
            $dexBytes = [System.IO.File]::ReadAllBytes((Join-Path $Work "dex\classes.dex"))
            $stream.Write($dexBytes, 0, $dexBytes.Length)
        } finally {
            $stream.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
    Write-Host "  已加入 classes.dex"
}

# ---------------------------------------------------------------------------
# 2. 造密钥库
# ---------------------------------------------------------------------------
Write-Step "生成签名密钥库"

$ks = Join-Path $Work "test.keystore"
$ksPass = "android"
$keytool = Get-ChildItem "C:\Program Files\Java" -Recurse -Filter keytool.exe -ErrorAction SilentlyContinue |
           Select-Object -First 1 -ExpandProperty FullName
if (-not $keytool) {
    $c = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($c) { $keytool = $c.Source }
}
if (-not $keytool) { Fail "找不到 keytool" }

$r = Invoke-Tool $keytool @("-genkeypair", "-keystore", $ks, "-storepass", $ksPass,
        "-keypass", $ksPass, "-alias", "testkey", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000", "-dname", "CN=Test,O=DSH,C=CN") "keytool 生成密钥"
if ($r.Code -ne 0) { Write-Host $r.Out; Fail "keytool 失败" }

# ---------------------------------------------------------------------------
# 3. 用不同签名方案签名
# ---------------------------------------------------------------------------
Write-Step "生成各种签名组合的样本"

function New-Signed {
    param([string]$Name, [string]$Schemes, [string]$Base = "")
    $out = Join-Path $OutDir "$Name.apk"
    # 不指定就用默认那份（targetSdk 18）；指定时用别的 base，
    # 例如 targetSdk 29 那份，用来验证 targetSdk 与签名方案的组合判定。
    $src = if ($Base) { $Base } else { $baseApk }
    # 注意：PowerShell 不允许把 if 表达式直接内联进数组字面量，必须先算好
    $v1 = if ($Schemes -match "1") { "true" } else { "false" }
    $v2 = if ($Schemes -match "2") { "true" } else { "false" }
    $v3 = if ($Schemes -match "3") { "true" } else { "false" }
    $args = @("sign", "--ks", $ks, "--ks-pass", "pass:$ksPass",
              "--key-pass", "pass:$ksPass", "--ks-key-alias", "testkey",
              "--v1-signing-enabled", $v1,
              "--v2-signing-enabled", $v2,
              "--v3-signing-enabled", $v3,
              "--out", $out, $src)
    $r = Invoke-Tool $apksigner $args "$Name.apk  (签名方案 $Schemes)"
    if ($r.Code -ne 0) { Write-Host $r.Out; Fail "$Name 签名失败" }
    return $out
}

$v1Apk = New-Signed "signed-v1" "1"
$v1v2v3Apk = New-Signed "signed-v1v2v3" "123"
# targetSdk 29 + 只有 v1：老包常态，任何系统都该判「能装」。
# 它同时钉住「targetSdk 门槛是 30，不是有 v1 就一定被新系统拒」。
New-Signed "signed-v1-target29" "1" $baseApk29 | Out-Null

# 「只有新式签名、没有 v1」的两份样本 —— 本组用例里最关键的
#
# 这正是本功能要解决的误报场景：一个完全正常的包只用了 v2/v3，
# 而旧实现只翻 META-INF/*.RSA|DSA|EC，取不到证书就报「可能是未签名的包」，
# 白白冤枉一个正常包。
#
# apksigner 在关掉 v1 时会把 META-INF 整个去掉（不留 MANIFEST.MF），
# 于是它自带的 verify 会报 "Missing META-INF/MANIFEST.MF" ——
# 那是 apksigner 自己的要求，不代表包有问题。真实校验请看
# tests/ApkSignaturesCheck.java：它直接从 Signing Block 里读证书，
# 指纹与 apksigner 对同一把密钥报出来的值一致，这才是有意义的证据。
New-Signed "signed-v2" "2" | Out-Null
New-Signed "signed-v2v3" "23" | Out-Null

# 完全不签名的一份（apksigner 三个方案全关）
New-Signed "signed-none" "0" | Out-Null
# 上面那份名字不合适，重命名为「未签名」更贴切
Move-Item (Join-Path $OutDir "signed-none.apk") (Join-Path $OutDir "unsigned.apk") -Force

# 改包样本：v1 签名之后往包里塞一个新文件，签名摘要就对不上了
#
# 这里刻意用原始 ZIP 追加，而不是 aapt add：
# aapt add 会顺手重写 META-INF/MANIFEST.MF，那样测的就不只是「摘要对不上」，
# 还把清单一起改了，模拟得不够干净。直接往 zip 里塞条目才等价于
# 「别人拿到包、改了内容、没重新签名」。
Write-Step "生成改包样本（改了内容但没重签名）"
$tampered = Join-Path $OutDir "tampered-v1.apk"
Copy-Item $v1Apk $tampered -Force
# ZipArchiveMode 定义在 System.IO.Compression 里，ZipFile 在 Compression.FileSystem 里，
# 两个都要加载，少一个就会报「Unable to find type ... ZipArchiveMode」
# （这两个程序集在脚本前面已经加载过，这里重复加载是无害的，留着以防前面分支没走到）
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($tampered, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $entry = $zip.CreateEntry("injected.txt")
    $stream = $entry.Open()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes("injected after signing`n")
        $stream.Write($bytes, 0, $bytes.Length)
    } finally {
        $stream.Dispose()
    }
} finally {
    $zip.Dispose()
}
if (-not (Test-Path $tampered)) { Fail "改包失败" }
Write-Host "  已写入 injected.txt（未重新签名）"

# ---------------------------------------------------------------------------
# 4. 汇总
# ---------------------------------------------------------------------------
Write-Step "校验每个样本的真实签名方案（用 apksigner verify）"

# 顺手把残留的 v4 签名旁路文件清掉。
# apksigner 有时会生成 xxx.apk.idsig，留在目录里会让检测器把样本报成含 v4，
# 干扰对照（不影响检测本身，但那不是我们要测的东西）。
Get-ChildItem $OutDir -Filter "*.idsig" -ErrorAction SilentlyContinue | Remove-Item -Force

$magicBytes = [System.Text.Encoding]::ASCII.GetBytes("APK Sig Block 42")

# 独立于 apksigner，直接看一眼有没有 APK Signing Block。
# 全文件搜魔数，而不是只查文件末尾 —— 魔数后面还要跟 8 字节块长和 EOCD，
# 位置不固定，包都很小，全搜的开销可以忽略。
function Test-SigningBlock {
    param([string]$ApkPath)
    $bytes = [System.IO.File]::ReadAllBytes($ApkPath)
    for ($i = $bytes.Length - 16; $i -ge 0; $i--) {
        $ok = $true
        for ($j = 0; $j -lt 16; $j++) {
            if ($bytes[$i + $j] -ne $magicBytes[$j]) { $ok = $false; break }
        }
        if ($ok) { return $true }
    }
    return $false
}

$result = @()
$referenceCertSha1 = $null

foreach ($f in Get-ChildItem $OutDir -Filter *.apk | Sort-Object Name) {
    $r = Invoke-Tool $apksigner @("verify", "--print-certs", "-v", $f.FullName) "verify $($f.Name)"

    # 注意：apksigner verify 对「没有 MANIFEST.MF 的 v2/v3-only 包」一律报
    # DOES NOT VERIFY（它要求清单存在），所以它的方案判定在那种包上是不可信的，
    # 这里记录的 Schemes 只作参考，真正判定以 ApkSignaturesCheck 为准。
    $v1 = if ($r.Out -match "Verified using v1 scheme.*?:\s*true") { "v1" } else { "" }
    $v2 = if ($r.Out -match "Verified using v2 scheme.*?:\s*true") { "v2" } else { "" }
    $v3 = if ($r.Out -match "Verified using v3 scheme.*?:\s*true") { "v3" } else { "" }
    $doNotVerify = if ($r.Out -match "DOES NOT VERIFY") { "  <-- 校验不通过" } else { "" }
    $schemes = (@($v1, $v2, $v3) | Where-Object { $_ }) -join "+"
    if (-not $schemes) { $schemes = "(无)" }
    $block = Test-SigningBlock $f.FullName
    Write-Host ("  {0,-22} {1,7:n0} B   方案={2,-10} SigningBlock={3,-5}{4}" -f `
        $f.Name, $f.Length, $schemes, $block, $doNotVerify)

    # 证书指纹：优先取 signed-v1v2v3（apksigner 能完整校验，报出来的值最可信）
    if ($f.Name -eq "signed-v1v2v3.apk") {
        $m = [regex]::Match($r.Out, "Signer #1 certificate SHA-1 digest:\s*([0-9a-fA-F]{40})")
        if ($m.Success) { $referenceCertSha1 = $m.Groups[1].Value.ToLowerInvariant() }
    }

    $result += [pscustomobject]@{
        Name = $f.Name
        Size = $f.Length
        Schemes = $schemes
        SigningBlock = $block
        DoesNotVerify = [bool]($r.Out -match "DOES NOT VERIFY")
    }
}

if (-not $referenceCertSha1) { Fail "没能从 apksigner 输出里取到参考证书指纹" }
Write-Host ""
Write-Host "参考证书指纹（由 apksigner 对 signed-v1v2v3.apk 报出）：$referenceCertSha1" -ForegroundColor Cyan
Write-Host "注：每次生成都会新建密钥库，所以这个值每次都不一样 —— 用例是从本文件读的，" -ForegroundColor DarkGray
Write-Host "    不需要手工同步任何常量。" -ForegroundColor DarkGray

$expected = [pscustomobject]@{
    CertSha1 = $referenceCertSha1
    Samples = $result
}
$expected | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutDir "expected.json") -Encoding UTF8

Write-Host ""
Write-Host "样本已生成到 $OutDir" -ForegroundColor Green
Write-Host "对照表写入 samples\expected.json" -ForegroundColor Green

# 必须显式 exit 0。
#
# 原因：本脚本最后几次外部调用是 `apksigner verify`，而我们**故意**拿未签名 /
# v2-only / 被改过的包去校验 —— 它们按设计就会返回非 0。那个退出码会留在
# $LASTEXITCODE 里，而 PowerShell 脚本结束时如果不显式 exit，外层（CI 的 pwsh 步骤）
# 会把它当成脚本自身的退出码，于是**样本明明全都生成好了，作业却判定失败**。
# 本地一直没暴露，是因为样本文件早就存在、没人重跑过这个脚本。
exit 0
