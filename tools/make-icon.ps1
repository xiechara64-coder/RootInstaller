# ===========================================================================
#  生成应用图标：安卓机器人（经典绿色）
#
#  为什么用脚本画，而不是直接塞一张 png 进仓库？
#    · 仓库里只留可读的代码，不出现二进制文件；
#    · 想改颜色/尺寸改几个数字重跑即可。
#
#  用法：  powershell -ExecutionPolicy Bypass -File tools\make-icon.ps1
#
#  输出：  app\src\main\res\drawable\ic_launcher.png
# ===========================================================================

param(
    [int]$Size = 144,
    [string]$Output = ""
)

Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $Output) {
    $Output = Join-Path $root "app\src\main\res\drawable\ic_launcher.png"
}

# 安卓机器人经典的绿色
$green      = [System.Drawing.Color]::FromArgb(255, 164, 198, 57)   # #A4C639
$greenDark  = [System.Drawing.Color]::FromArgb(255, 140, 172, 45)
$eyeColor   = [System.Drawing.Color]::FromArgb(255, 255, 255, 255)

$bmp = New-Object System.Drawing.Bitmap($Size, $Size)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.Clear([System.Drawing.Color]::Transparent)

$headBrush = New-Object System.Drawing.SolidBrush($green)
$darkPen   = New-Object System.Drawing.Pen($greenDark, [float]($Size * 0.028))
$antPen    = New-Object System.Drawing.Pen($green, [float]($Size * 0.045))
$antPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$antPen.EndCap   = [System.Drawing.Drawing2D.LineCap]::Round
$eyeBrush  = New-Object System.Drawing.SolidBrush($eyeColor)

# ---- 头部：圆角矩形，占据中央偏下的区域 ----
$x = [float]($Size * 0.18)
$y = [float]($Size * 0.34)
$w = [float]($Size * 0.64)
$h = [float]($Size * 0.48)
$r = [float]($Size * 0.20)          # 圆角半径，做大的弧形头顶

$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddArc($x, $y, $r, $r, 180, 90)
$path.AddArc($x + $w - $r, $y, $r, $r, 270, 90)
$path.AddArc($x + $w - $r, $y + $h - $r, $r, $r, 0, 90)
$path.AddArc($x, $y + $h - $r, $r, $r, 90, 90)
$path.CloseFigure()
$g.FillPath($headBrush, $path)
$g.DrawPath($darkPen, $path)

# ---- 眼睛：两个白色圆点 ----
$eyeD = [float]($Size * 0.105)
$eyeY = [float]($Size * 0.50)
$g.FillEllipse($eyeBrush, [float]($Size * 0.33), $eyeY, $eyeD, $eyeD)
$g.FillEllipse($eyeBrush, [float]($Size * 0.565), $eyeY, $eyeD, $eyeD)

# ---- 触角：头顶两根斜线，末端加个小圆更可爱 ----
$headTop = [float]($Size * 0.36)
$g.DrawLine($antPen, [float]($Size * 0.36), $headTop, [float]($Size * 0.27), [float]($Size * 0.20))
$g.DrawLine($antPen, [float]($Size * 0.64), $headTop, [float]($Size * 0.73), [float]($Size * 0.20))
$antD = [float]($Size * 0.048)
$g.FillEllipse($headBrush, [float]($Size * 0.27) - $antD/2, [float]($Size * 0.20) - $antD/2, $antD, $antD)
$g.FillEllipse($headBrush, [float]($Size * 0.73) - $antD/2, [float]($Size * 0.20) - $antD/2, $antD, $antD)

# ---- 两侧的小耳朵（让轮廓更像机器人）----
$earW = [float]($Size * 0.045)
$earH = [float]($Size * 0.18)
$earY = [float]($Size * 0.50)
$g.FillRectangle($headBrush, [float]($Size * 0.135), $earY, $earW, $earH)
$g.FillRectangle($headBrush, [float]($Size * 0.82),  $earY, $earW, $earH)

$g.Dispose()

# 保存为 PNG
$dir = Split-Path -Parent $Output
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$bmp.Save($Output, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

foreach ($b in @($headBrush,$eyeBrush)) { $b.Dispose() }
foreach ($p in @($darkPen,$antPen)) { $p.Dispose() }
$path.Dispose()

$fi = Get-Item $Output
Write-Host "图标已生成: $($fi.FullName)"
Write-Host "尺寸: $($Size)x$Size  大小: $([math]::Round($fi.Length/1KB,2)) KB"

# ---------------------------------------------------------------------------
# 顺便生成 APK 占位图标（列表里图标还没解析出来时显示的那个）
# 画成一个「带折角的文件 + 中间一个向下的箭头」，一看就是安装包。
# ---------------------------------------------------------------------------
$apkOut = Join-Path $root "app\src\main\res\drawable\ic_apk.png"

$paper      = [System.Drawing.Color]::FromArgb(255, 236, 239, 241)
$paperEdge  = [System.Drawing.Color]::FromArgb(255, 144, 164, 174)
$fold       = [System.Drawing.Color]::FromArgb(255, 207, 216, 220)
$arrow      = [System.Drawing.Color]::FromArgb(255, 56, 142, 60)     # 绿色向下箭头

$b2 = New-Object System.Drawing.Bitmap($Size, $Size)
$g2 = [System.Drawing.Graphics]::FromImage($b2)
$g2.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g2.Clear([System.Drawing.Color]::Transparent)

$paperBrush = New-Object System.Drawing.SolidBrush($paper)
$foldBrush  = New-Object System.Drawing.SolidBrush($fold)
$edgePen    = New-Object System.Drawing.Pen($paperEdge, [float]($Size * 0.025))
$arrowPen   = New-Object System.Drawing.Pen($arrow, [float]($Size * 0.075))
$arrowPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$arrowPen.EndCap   = [System.Drawing.Drawing2D.LineCap]::Round

$px = [float]($Size * 0.24)
$py = [float]($Size * 0.12)
$pw = [float]($Size * 0.52)
$ph = [float]($Size * 0.76)
$foldSize = [float]($Size * 0.20)

# 纸张主体（右下角留出折角）
$body = New-Object System.Drawing.Drawing2D.GraphicsPath
$body.AddLine($px, $py, $px + $pw - $foldSize, $py)
$body.AddLine($px + $pw - $foldSize, $py, $px + $pw, $py + $foldSize)
$body.AddLine($px + $pw, $py + $foldSize, $px + $pw, $py + $ph)
$body.AddLine($px + $pw, $py + $ph, $px, $py + $ph)
$body.CloseFigure()
$g2.FillPath($paperBrush, $body)
$g2.DrawPath($edgePen, $body)

# 折角
$tri = New-Object System.Drawing.Drawing2D.GraphicsPath
$tri.AddLine($px + $pw - $foldSize, $py, $px + $pw - $foldSize, $py + $foldSize)
$tri.AddLine($px + $pw - $foldSize, $py + $foldSize, $px + $pw, $py + $foldSize)
$tri.CloseFigure()
$g2.FillPath($foldBrush, $tri)
$g2.DrawPath($edgePen, $tri)

# 中间向下的安装箭头
$cx = [float]($Size * 0.50)
$g2.DrawLine($arrowPen, $cx, [float]($Size * 0.34), $cx, [float]($Size * 0.62))
$g2.DrawLine($arrowPen, [float]($Size * 0.38), [float]($Size * 0.51), $cx, [float]($Size * 0.63))
$g2.DrawLine($arrowPen, [float]($Size * 0.62), [float]($Size * 0.51), $cx, [float]($Size * 0.63))
# 底部横线，表示安装到设备
$g2.DrawLine($arrowPen, [float]($Size * 0.36), [float]($Size * 0.73), [float]($Size * 0.64), [float]($Size * 0.73))

$g2.Dispose()
$b2.Save($apkOut, [System.Drawing.Imaging.ImageFormat]::Png)
$b2.Dispose()
foreach ($o in @($paperBrush,$foldBrush,$edgePen,$arrowPen)) { $o.Dispose() }
$body.Dispose(); $tri.Dispose()

$fi2 = Get-Item $apkOut
Write-Host "占位图标已生成: $($fi2.FullName)"
Write-Host "大小: $([math]::Round($fi2.Length/1KB,2)) KB"

# ---------------------------------------------------------------------------
# 「三个竖点」的 ActionBar 溢出菜单图标。
#
# 为什么不用文字「⋮」：实测在目标 ROM（华为 C8816D / 4.3）上，
# 这个 Unicode 字形虽然进了无障碍节点树，但屏幕上画不出来 —— 用户完全看不到。
# 换成自己画的点阵就稳了，不依赖任何字体。
#
# 关于点径：这个图标在 ActionBar 里以 22~24dp 显示，而位图是 96 像素，会被缩小。
# 所以「点」在画布上的比例要按最终显示尺寸来定 ——
# 第一版半径取画布的 15.5%，缩到 24dp 后看着偏粗，用户要求减半，现在是 7.75%。
# ---------------------------------------------------------------------------
$dotsOut = Join-Path $root "app\src\main\res\drawable\ic_overflow.png"

$dotSize = 96
$dotRadiusRatio = 0.0775
$dotSpacing = @(0.26, 0.5, 0.74)
# 纯白。标题栏背景是 Theme.Holo.Light.DarkActionBar 的深灰（约 #3F3F3F），
# 深色点在上面几乎看不清，必须用白色。
$dotColor = [System.Drawing.Color]::FromArgb(255, 255, 255, 255)

$b3 = New-Object System.Drawing.Bitmap($dotSize, $dotSize)
$g3 = [System.Drawing.Graphics]::FromImage($b3)
$g3.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g3.Clear([System.Drawing.Color]::Transparent)

$dotBrush = New-Object System.Drawing.SolidBrush($dotColor)
$r = [float]($dotSize * $dotRadiusRatio)
$cx = [float]($dotSize / 2)
foreach ($f in $dotSpacing) {
    $cy = [float]($dotSize * $f)
    $g3.FillEllipse($dotBrush, [float]($cx - $r), [float]($cy - $r),
        [float]($r * 2), [float]($r * 2))
}
$g3.Dispose()
$b3.Save($dotsOut, [System.Drawing.Imaging.ImageFormat]::Png)
$b3.Dispose()
$dotBrush.Dispose()

$fi3 = Get-Item $dotsOut
Write-Host "溢出菜单图标已生成: $($fi3.FullName)"
Write-Host ("点半径 {0}px（画布的 {1:P2}），直径 {2}px" -f [math]::Round($r,1), $dotRadiusRatio, [math]::Round($r*2,1))
Write-Host "大小: $([math]::Round($fi3.Length/1KB,2)) KB"