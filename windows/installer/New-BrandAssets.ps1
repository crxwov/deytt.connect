$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$directory = Join-Path $PSScriptRoot 'Assets'
$bitmap = [Drawing.Bitmap]::new(896,1392)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.ScaleTransform(4,4)
    $background = [Drawing.Drawing2D.LinearGradientBrush]::new(
        [Drawing.RectangleF]::new(0,0,224,348),
        [Drawing.Color]::FromArgb(13,23,43),[Drawing.Color]::FromArgb(8,39,52),35)
    $graphics.FillRectangle($background,0,0,224,348)
    $background.Dispose()
    $bitmap.Save((Join-Path $directory 'Sidebar.bmp'),[Drawing.Imaging.ImageFormat]::Bmp)
    $bitmap.Save((Join-Path $directory 'Sidebar-preview.png'),[Drawing.Imaging.ImageFormat]::Png)
} finally { $graphics.Dispose(); $bitmap.Dispose() }
