using System.Globalization;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Platform;

namespace DeyttConnect.Windows.UI;

/// <summary>Small, local vector marks for the protocols listed by the route catalog.</summary>
internal sealed class ProtocolLogo(string protocol) : Control
{
    private static readonly XNamespace SvgNamespace = "http://www.w3.org/2000/svg";
    private static readonly Lazy<Geometry> AmneziaGeometry = new(() => LoadAmneziaGeometry());
    private static readonly Lazy<IReadOnlyList<(Geometry Geometry, bool Accent)>> HysteriaGeometries =
        new(() => LoadHysteriaGeometries());
    private readonly string _protocol = protocol.ToUpperInvariant();

    public override void Render(DrawingContext context)
    {
        base.Render(context);
        using (context.PushClip(new Rect(0, 0, Bounds.Width, Bounds.Height)))
        {
            var color = _protocol switch
            {
                "VLESS" => DeyttTheme.Blue,
                "TROJAN" => DeyttTheme.Text,
                "HYSTERIA2" => DeyttTheme.Mint,
                "AWG31" => DeyttTheme.Amber,
                _ => DeyttTheme.Muted,
            };
            var brush = DeyttTheme.Brush(color);
            var pen = new Pen(brush, 2.4, null, PenLineCap.Round, PenLineJoin.Round);
            switch (_protocol)
            {
                case "VLESS":
                {
                    using (PushSource40Transform(context))
                    {
                        context.DrawGeometry(brush, null, Polygon(
                            new(20, 1), new(16.5, 16.5), new(20, 18.4), new(23.5, 16.5),
                            new(20, 1)));
                        context.DrawGeometry(brush, null, Polygon(
                            new(39, 20), new(23.5, 16.5), new(21.6, 20), new(23.5, 23.5),
                            new(39, 20)));
                        context.DrawGeometry(brush, null, Polygon(
                            new(20, 39), new(23.5, 23.5), new(20, 21.6), new(16.5, 23.5),
                            new(20, 39)));
                        context.DrawGeometry(brush, null, Polygon(
                            new(1, 20), new(16.5, 23.5), new(18.4, 20), new(16.5, 16.5),
                            new(1, 20)));
                    }
                    break;
                }
                case "TROJAN":
                {
                    using (PushSource40Transform(context))
                    {
                        context.DrawGeometry(brush, null, Polygon(
                            new(8, 32), new(10.5, 27.5), new(10, 23), new(7.5, 19), new(10.5, 15),
                            new(12, 8), new(16, 10), new(19, 6), new(21, 11), new(26, 10),
                            new(29, 13), new(34, 13), new(33, 17), new(29, 19), new(28, 24),
                            new(24, 27), new(23, 34), new(19, 34), new(17, 28), new(14, 28),
                            new(12, 34), new(8, 34), new(8, 32)));
                        context.DrawLine(new Pen(DeyttTheme.Brush(DeyttTheme.Background), 1.5),
                            new(30, 16), new(32.5, 15.5));
                    }
                    break;
                }
                case "HYSTERIA2":
                    DrawHysteriaMark(context);
                    break;
                case "AWG31":
                    DrawAmneziaMark(context);
                    break;
            }
        }
    }

    private void DrawHysteriaMark(DrawingContext context)
    {
        const double sourceWidth = 442.19;
        const double sourceHeight = 323.31;
        var scale = Math.Min(Bounds.Width / sourceWidth, Bounds.Height / sourceHeight);
        var offsetX = (Bounds.Width - sourceWidth * scale) / 2;
        var offsetY = (Bounds.Height - sourceHeight * scale) / 2;
        foreach (var (geometry, accent) in HysteriaGeometries.Value)
        {
            var color = accent ? DeyttTheme.Amber : DeyttTheme.Text;
            // The upstream symbol is a five-part mark; the gold polygon remains
            // the distinctive accent while the rest follows the active theme.
            DrawScaledGeometry(context, geometry, sourceWidth, sourceHeight, scale,
                offsetX, offsetY, DeyttTheme.Brush(color));
        }
    }

    private void DrawAmneziaMark(DrawingContext context)
    {
        var geometry = AmneziaGeometry.Value;
        var bounds = geometry.Bounds;
        if (bounds.Width <= 0 || bounds.Height <= 0)
            return;
        var scale = Math.Min(Bounds.Width / bounds.Width, Bounds.Height / bounds.Height);
        var offsetX = (Bounds.Width - bounds.Width * scale) / 2 - bounds.X * scale;
        var offsetY = (Bounds.Height - bounds.Height * scale) / 2 - bounds.Y * scale;
        DrawScaledGeometry(context, geometry, bounds.Width, bounds.Height, scale,
            offsetX, offsetY, DeyttTheme.Brush(DeyttTheme.Text));
    }

    private static void DrawScaledGeometry(DrawingContext context, Geometry geometry,
        double sourceWidth, double sourceHeight, double scale, double offsetX, double offsetY, IBrush brush)
    {
        if (sourceWidth <= 0 || sourceHeight <= 0)
            return;
        using (context.PushTransform(new Matrix(scale, 0, 0, scale, offsetX, offsetY)))
            context.DrawGeometry(brush, null, geometry);
    }

    private IDisposable PushSource40Transform(DrawingContext context)
    {
        const double sourceSize = 40;
        var scale = Math.Min(Bounds.Width / sourceSize, Bounds.Height / sourceSize);
        var offsetX = (Bounds.Width - sourceSize * scale) / 2;
        var offsetY = (Bounds.Height - sourceSize * scale) / 2;
        return context.PushTransform(new Matrix(scale, 0, 0, scale, offsetX, offsetY));
    }

    private static Geometry LoadAmneziaGeometry()
    {
        using var stream = OpenMark("amnezia-mark.svg");
        var document = XDocument.Load(stream);
        var path = document.Descendants(SvgNamespace + "path").FirstOrDefault();
        var data = (string?)path?.Attribute("d");
        if (string.IsNullOrWhiteSpace(data))
            throw new InvalidDataException("The Amnezia vector mark has no path data.");
        return Geometry.Parse(data);
    }

    private static IReadOnlyList<(Geometry Geometry, bool Accent)> LoadHysteriaGeometries()
    {
        using var stream = OpenMark("hysteria-symbol.svg");
        var document = XDocument.Load(stream);
        var polygons = document.Descendants(SvgNamespace + "polygon")
            .Select(polygon =>
            {
                var values = ((string?)polygon.Attribute("points") ?? "")
                    .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)
                    .SelectMany(pair => pair.Split(',', StringSplitOptions.RemoveEmptyEntries))
                    .Select(value => double.Parse(value, CultureInfo.InvariantCulture))
                    .ToArray();
                if (values.Length < 6 || values.Length % 2 != 0)
                    throw new InvalidDataException("The Hysteria vector mark contains an invalid polygon.");
                var points = Enumerable.Range(0, values.Length / 2)
                    .Select(index => new Point(values[index * 2], values[index * 2 + 1]))
                    .ToArray();
                return ((Geometry)Polygon(points),
                    string.Equals((string?)polygon.Attribute("class"), "cls-1", StringComparison.Ordinal));
            })
            .ToArray();
        if (polygons.Length == 0)
            throw new InvalidDataException("The Hysteria vector mark has no polygons.");
        return polygons;
    }

    private static Stream OpenMark(string fileName)
    {
        var assemblyName = typeof(ProtocolLogo).Assembly.GetName().Name;
        return AssetLoader.Open(new Uri($"avares://{assemblyName}/Assets/ProtocolMarks/{fileName}"), null);
    }

    private static StreamGeometry Polygon(params Point[] points)
    {
        var geometry = new StreamGeometry();
        using var context = geometry.Open();
        context.BeginFigure(points[0], isFilled: true);
        foreach (var point in points.Skip(1))
            context.LineTo(point);
        context.EndFigure(isClosed: true);
        return geometry;
    }

    private static StreamGeometry Polyline(params Point[] points)
    {
        var geometry = new StreamGeometry();
        using var context = geometry.Open();
        context.BeginFigure(points[0], isFilled: false);
        foreach (var point in points.Skip(1))
            context.LineTo(point);
        context.EndFigure(isClosed: false);
        return geometry;
    }
}
