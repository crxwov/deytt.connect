
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'Assets')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$bitmap = [Drawing.Bitmap]::new(968, 648, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$resources = [Collections.Generic.List[IDisposable]]::new()

function Ink([string]$hex) {
    $brush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($hex))
    $resources.Add($brush)
    $brush
}

try {
    $graphics.ScaleTransform(2, 2)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.FillRectangle((Ink '#141414'), 0, 0, 484, 324)
    $graphics.FillRectangle((Ink '#0c0c0c'), 0, 0, 484, 69)
    $graphics.FillRectangle((Ink '#2d2d2d'), 0, 68, 484, 1)
    $graphics.FillRectangle((Ink '#0c0c0c'), 0, 275, 484, 49)
    $graphics.FillRectangle((Ink '#2d2d2d'), 0, 274, 484, 1)

    $font = [Drawing.Font]::new('Segoe UI', 23, [Drawing.FontStyle]::Bold, [Drawing.GraphicsUnit]::Pixel)
    $resources.Add($font)
    $typographic = [Drawing.StringFormat]::GenericTypographic
    $resources.Add($typographic)
    $mark = './'
    $graphics.DrawString($mark, $font, (Ink '#87d4e3'), [Drawing.PointF]::new(28, 21), $typographic)
    $markWidth = $graphics.MeasureString($mark, $font, [Drawing.PointF]::Empty, $typographic).Width
    $graphics.DrawString('connect', $font, (Ink '#f5f5f5'), [Drawing.PointF]::new(28 + $markWidth, 21), $typographic)
    $bitmap.Save((Join-Path $OutputDirectory 'SetupPanel.png'), [Drawing.Imaging.ImageFormat]::Png)
} finally {
    foreach ($resource in $resources) { $resource.Dispose() }
    $graphics.Dispose()
    $bitmap.Dispose()
}
