# ===========================================================================
#  API 兼容性审查
#
#  用两个真实的 android.jar 对比，找出「代码用到的、但在高版本被移除」的 API。
#  这类问题编译期发现不了（我们是对着 android-19 编的），只有到真机上才崩。
#
#  用法：  powershell -ExecutionPolicy Bypass -File tools\api-audit.ps1
# ===========================================================================

$ErrorActionPreference = "Continue"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { "$env:LOCALAPPDATA\Android\Sdk" }

$jOld = Join-Path $sdk "platforms\android-19\android.jar"
$jNew = Join-Path $sdk "platforms\android-37.0\android.jar"
$javap = $null
foreach ($c in @(
    "C:\Program Files\Java\jdk-26.0.2\bin\javap.exe",
    "$env:JAVA_HOME\bin\javap.exe"
)) { if (Test-Path $c) { $javap = $c; break } }
if (-not $javap) {
    $cmd = Get-Command javap -ErrorAction SilentlyContinue
    if ($cmd) { $javap = $cmd.Source }
}

Write-Host "javap   : $javap"
Write-Host "老平台  : $jOld"
Write-Host "新平台  : $jNew"
if (-not (Test-Path $jOld) -or -not (Test-Path $jNew) -or -not $javap) {
    Write-Host "缺少必要组件，无法审查" -ForegroundColor Red
    exit 1
}

# 代码里实际用到的、值得核对版本状态的成员
# 格式：类名 | 要查找的方法/字段片段 | 说明
$targets = @(
    @{c='android.app.Notification';      m='setLatestEventInfo';         d='API 23 已移除；代码里用了，高版本会 NoSuchMethodError'},
    @{c='android.app.Notification';      m='Notification\(\)';           d='API 23 废弃但仍在；低版本必须用这个构造器'},
    @{c='android.app.Service';           m='startForeground';            d='API 1+；API 26+ 需通知渠道(targetSdk<26 可免)'},
    @{c='android.app.Service';           m='stopForeground';             d='API 1+'},
    @{c='android.app.PendingIntent';     m='getActivity';                d='API 1+'},
    @{c='android.app.ActionBar';         m='setDisplayHomeAsUpEnabled';  d='API 14+（minSdk 15 安全）'},
    @{c='android.app.ActionBar';         m='setSubtitle';                d='API 14+'},
    @{c='android.app.ListActivity';      m='getListView';                d='API 1+'},
    @{c='android.app.AlertDialog';       m='setItems';                   d='API 1+'},
    @{c='android.app.AlertDialog';       m='setSingleChoiceItems';       d='API 1+'},
    @{c='android.content.ClipboardManager'; m='setPrimaryClip';          d='API 11+'},
    @{c='android.content.ClipData';      m='newPlainText';               d='API 11+'},
    @{c='android.content.Context';       m='getExternalFilesDirs';       d='API 19+；代码里用反射+版本判断调用'},
    @{c='android.content.Context';       m='getCacheDir';                d='API 1+'},
    @{c='android.os.Environment';        m='getExternalStorageDirectory';d='API 1+，API 29 废弃'},
    @{c='android.content.pm.PackageManager'; m='getPackageArchiveInfo';  d='API 1+；API 24+ 才支持读外部存储的包'},
    @{c='android.content.pm.PackageManager'; m='getApplicationIcon';     d='API 1+'},
    @{c='android.content.pm.PackageManager'; m='getApplicationLabel';    d='API 1+'},
    @{c='android.content.pm.PackageManager'; m='getInstalledPackages';   d='API 1+'},
    @{c='android.content.pm.PermissionInfo'; m='PROTECTION_DANGEROUS';   d='API 1+'},
    @{c='android.content.pm.ApplicationInfo'; m='FLAG_SYSTEM';           d='API 1+'},
    @{c='android.content.res.AssetManager'; m='addAssetPath';            d='隐藏API；代码里用反射，取不到就跳过'},
    @{c='android.os.Build';              m='CPU_ABI2';                   d='API 8+'},
    @{c='android.os.Build';              m='SUPPORTED_ABIS';             d='API 21+；代码里没用（曾误用被编译期拦住）'},
    @{c='android.view.ViewConfiguration';m='hasPermanentMenuKey';        d='API 14+'},
    @{c='android.widget.Toast';          m='makeText';                   d='API 1+'},
    @{c='android.text.style.StyleSpan';  m='StyleSpan';                  d='API 1+'},
    @{c='android.graphics.Typeface';     m='BOLD';                       d='API 1+'},
    @{c='android.widget.PopupMenu';      m='PopupMenu';                  d='API 11+'},
    @{c='android.widget.ProgressBar';    m='setIndeterminate';           d='API 1+'},
    @{c='android.content.SharedPreferences'; m='apply';                  d='API 9+'}
)

function Get-Members($jar, $class) {
    $out = & $javap -classpath $jar $class 2>&1
    return ($out | ForEach-Object { $_.ToString() })
}

$removed = @()
$kept = @()

Write-Host ""
Write-Host ("{0,-40} {1,-26} {2}" -f "类", "成员", "API19 / API37")
Write-Host ("-" * 100)

foreach ($t in $targets) {
    $old = Get-Members $jOld $t.c
    $new = Get-Members $jNew $t.c
    # 用正则匹配成员名
    $inOld = [bool]($old | Select-String -Pattern $t.m -Quiet)
    $inNew = [bool]($new | Select-String -Pattern $t.m -Quiet)
    $oldTxt = if ($old.Count -le 2) { "读不到" } elseif ($inOld) { "有" } else { "无" }
    $newTxt = if ($new.Count -le 2) { "读不到" } elseif ($inNew) { "有" } else { "无" }
    $flag = ""
    if ($inOld -and -not $inNew) { $flag = "  <== 高版本移除!"; $removed += $t }
    elseif ($inOld) { $kept += $t }
    Write-Host ("{0,-40} {1,-26} {2,-12}{3}" -f `
        ($t.c -replace 'android\.',''), $t.m, "$oldTxt / $newTxt", $flag)
}

Write-Host ""
Write-Host "==== 结论 ====" -ForegroundColor Cyan
Write-Host "高版本被移除的 API: $($removed.Count) 个"
foreach ($r in $removed) {
    Write-Host "  · $($r.c).$($r.m)" -ForegroundColor Red
    Write-Host "    $($r.d)" -ForegroundColor DarkGray
}
Write-Host ""
Write-Host "其余 $($kept.Count) 个成员在新版本仍然存在" -ForegroundColor Green
