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
    private static readonly Lazy<Geometry> VlessGeometry = new(() => LoadProtocolGeometry("vless-xray-symbol.svg"));
    private static readonly Lazy<Geometry> TrojanGeometry = new(() => LoadProtocolGeometry("trojan-horse-symbol.svg"));
    private static readonly Lazy<IReadOnlyList<(Geometry Geometry, bool Accent)>> HysteriaGeometries =
        new(() => LoadHysteriaGeometries());
    private readonly string _protocol = protocol.ToUpperInvariant();

    public override void Render(DrawingContext context)
    {
        base.Render(context);
        using (context.PushClip(new Rect(0, 0, Bounds.Width, Bounds.Height)))
        {
            switch (_protocol)
            {
                case "VLESS": DrawMark(context, VlessGeometry.Value, DeyttTheme.Blue);
                    break;
                case "TROJAN": DrawMark(context, TrojanGeometry.Value, DeyttTheme.Text);
                    break;
                case "HYSTERIA2":
                    DrawHysteriaMark(context);
                    break;
                case "AWG31":
                    DrawMark(context, AmneziaGeometry.Value, DeyttTheme.Text);
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

    private void DrawMark(DrawingContext context, Geometry geometry, Color color)
    {
        var bounds = geometry.Bounds;
        if (bounds.Width <= 0 || bounds.Height <= 0)
            return;
        var scale = Math.Min(Bounds.Width / bounds.Width, Bounds.Height / bounds.Height);
        var offsetX = (Bounds.Width - bounds.Width * scale) / 2 - bounds.X * scale;
        var offsetY = (Bounds.Height - bounds.Height * scale) / 2 - bounds.Y * scale;
        DrawScaledGeometry(context, geometry, bounds.Width, bounds.Height, scale,
            offsetX, offsetY, DeyttTheme.Brush(color));
    }

    private static void DrawScaledGeometry(DrawingContext context, Geometry geometry,
        double sourceWidth, double sourceHeight, double scale, double offsetX, double offsetY, IBrush brush)
    {
        if (sourceWidth <= 0 || sourceHeight <= 0)
            return;
        using (context.PushTransform(new Matrix(scale, 0, 0, scale, offsetX, offsetY)))
            context.DrawGeometry(brush, null, geometry);
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

    private static Geometry LoadProtocolGeometry(string fileName)
    {
        using var stream = OpenMark(fileName);
        var document = XDocument.Load(stream);
        var data = (string?)document.Descendants(SvgNamespace + "path").FirstOrDefault()?.Attribute("d");
        if (string.IsNullOrWhiteSpace(data))
            throw new InvalidDataException($"The protocol mark {fileName} has no path data.");
        return Geometry.Parse(data);
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

}
