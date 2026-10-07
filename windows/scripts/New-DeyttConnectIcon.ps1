[CmdletBinding()]
param(
    [string] $FontPath = (Join-Path $PSScriptRoot '..\DeyttConnect.Windows\Assets\Fonts\InterTight-SemiBold.ttf'),
    [string] $OutputPath = (Join-Path $PSScriptRoot '..\DeyttConnect.Windows\Assets\deyttconnect.ico')
)

$ErrorActionPreference = 'Stop'
$resolvedFont = (Resolve-Path -LiteralPath $FontPath).Path
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$compilerPath = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compilerPath -PathType Leaf)) {
    throw 'The Windows .NET Framework C# compiler is required to generate the icon.'
}

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('deyttconnect-icon-' + [Guid]::NewGuid().ToString('N'))
$sourcePath = Join-Path $temporaryRoot 'IconBuilder.cs'
$compilerOutput = Join-Path $temporaryRoot 'IconBuilder.exe'
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
$source = @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.IO;

internal static class IconBuilder
{
    private static GraphicsPath RoundedRectangle(RectangleF bounds, float radius)
    {
        var path = new GraphicsPath();
        var diameter = radius * 2;
        path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
        path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
        path.AddArc(bounds.Right - diameter, bounds.Bottom - diameter, diameter, diameter, 0, 90);
        path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90);
        path.CloseFigure();
        return path;
    }

    private static byte[] RenderPng(FontFamily family, int size)
    {
        using (var frame = new Bitmap(size, size, PixelFormat.Format32bppArgb))
        using (var graphics = Graphics.FromImage(frame))
        using (var stream = new MemoryStream())
        {
            graphics.Clear(Color.Transparent);
            graphics.CompositingMode = CompositingMode.SourceOver;
            graphics.CompositingQuality = CompositingQuality.HighQuality;
            graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
            graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            graphics.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;

            var inset = size * 0.035f;
            var bounds = new RectangleF(inset, inset, size - inset * 2, size - inset * 2);
            using (var tile = RoundedRectangle(bounds, size * 0.21f))
            using (var background = new SolidBrush(Color.FromArgb(255, 25, 35, 49)))
            using (var border = new Pen(Color.FromArgb(255, 52, 68, 91), Math.Max(1, size / 128f)))
            using (var white = new SolidBrush(Color.FromArgb(255, 242, 245, 252)))
            using (var format = new StringFormat(StringFormat.GenericTypographic))
            using (var mark = new Font(family, size * 0.43f, FontStyle.Regular, GraphicsUnit.Pixel))
            {
                graphics.FillPath(background, tile);
                graphics.DrawPath(border, tile);
                format.Alignment = StringAlignment.Center;
                format.LineAlignment = StringAlignment.Center;
                format.FormatFlags |= StringFormatFlags.NoWrap;
                graphics.DrawString("./c", mark, white,
                    new RectangleF(size * 0.075f, size * 0.075f, size * 0.85f, size * 0.85f), format);
            }
            frame.Save(stream, ImageFormat.Png);
            return stream.ToArray();
        }
    }

    private static int Main(string[] args)
    {
        if (args.Length != 2)
            return 2;
        var sizes = new[] { 16, 24, 32, 48, 64, 128, 256 };
        var frames = new byte[sizes.Length][];
        using (var fonts = new PrivateFontCollection())
        {
            fonts.AddFontFile(args[0]);
            var family = fonts.Families[0];
            for (var i = 0; i < sizes.Length; i++)
                frames[i] = RenderPng(family, sizes[i]);
        }

        using (var file = File.Create(args[1]))
        using (var writer = new BinaryWriter(file))
        {
            writer.Write((ushort)0);
            writer.Write((ushort)1);
            writer.Write((ushort)sizes.Length);
            var offset = 6 + sizes.Length * 16;
            for (var i = 0; i < sizes.Length; i++)
            {
                var size = sizes[i];
                writer.Write((byte)(size == 256 ? 0 : size));
                writer.Write((byte)(size == 256 ? 0 : size));
                writer.Write((byte)0);
                writer.Write((byte)0);
                writer.Write((ushort)1);
                writer.Write((ushort)32);
                writer.Write(frames[i].Length);
                writer.Write(offset);
                offset += frames[i].Length;
            }
            foreach (var frame in frames)
                writer.Write(frame);
        }
        return 0;
    }
}
'@

try {
    Set-Content -LiteralPath $sourcePath -Value $source -Encoding UTF8
    & $compilerPath /nologo /target:exe /platform:x64 /reference:System.Drawing.dll "/out:$compilerOutput" $sourcePath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $compilerOutput -PathType Leaf)) {
        throw 'Could not compile the icon converter.'
    }
    & $compilerOutput $resolvedFont $resolvedOutput
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $resolvedOutput -PathType Leaf)) {
        throw 'Could not write the DEYTT Connect icon.'
    }
    Write-Output "Created Windows ./c wordmark using the mobile Inter Tight font: $resolvedOutput"
}
finally {
    $temporaryFullPath = [IO.Path]::GetFullPath($temporaryRoot)
    $temporaryPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($temporaryFullPath.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        foreach ($temporaryFile in @($sourcePath, $compilerOutput)) {
            if (Test-Path -LiteralPath $temporaryFile -PathType Leaf) {
                Remove-Item -LiteralPath $temporaryFile -Force
            }
        }
        if (Test-Path -LiteralPath $temporaryFullPath -PathType Container) {
            Remove-Item -LiteralPath $temporaryFullPath -Force
        }
    }
}
