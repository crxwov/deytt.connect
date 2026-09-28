using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;

namespace DeyttConnect.Windows.Controls;

public sealed class RouteMapIllustration : Control
{
    public override void Render(DrawingContext context)
    {
        base.Render(context);
        var width = Bounds.Width;
        var height = Bounds.Height;
        if (width <= 0 || height <= 0)
            return;

        var center = new Point(width * 0.57, height * 0.51);
        var mapLine = new Pen(new SolidColorBrush(Color.Parse("#202B38")), 1);
        var orbit = new Pen(new SolidColorBrush(Color.Parse("#182636")), 0.8);
        context.DrawEllipse(null, mapLine, center, width * 0.33, height * 0.76);
        context.DrawEllipse(null, orbit, center, width * 0.17, height * 0.76);
        context.DrawEllipse(null, orbit, center, width * 0.33, height * 0.27);
        context.DrawEllipse(null, orbit, center, width * 0.33, height * 0.51);

        DrawPolyline(context, mapLine, width, height,
            (0.30, 0.30), (0.37, 0.22), (0.45, 0.27), (0.49, 0.36),
            (0.56, 0.37), (0.62, 0.46), (0.57, 0.54), (0.62, 0.63),
            (0.55, 0.73), (0.48, 0.67), (0.44, 0.58), (0.37, 0.55), (0.33, 0.45));
        DrawPolyline(context, mapLine, width, height,
            (0.63, 0.32), (0.69, 0.26), (0.78, 0.28), (0.83, 0.36),
            (0.79, 0.44), (0.84, 0.49), (0.79, 0.57), (0.74, 0.52), (0.70, 0.44));

        var routePen = new Pen(new SolidColorBrush(Color.Parse("#6BDDF2")), 2.1, lineCap: PenLineCap.Round);
        var start = new Point(width * 0.13, height * 0.63);
        var relay = new Point(width * 0.48, height * 0.62);
        var exit = new Point(width * 0.76, height * 0.43);
        context.DrawLine(routePen, start, relay);
        context.DrawLine(routePen, relay, exit);
        DrawNode(context, start, Color.Parse("#6BDDF2"), 2.7);
        DrawNode(context, relay, Color.Parse("#63E0B4"), 3.7);
        DrawNode(context, exit, Color.Parse("#8494FF"), 3.4);
        DrawNode(context, new Point(width * 0.64, height * 0.52), Color.Parse("#6BDDF2"), 2.8);
    }

    private static void DrawPolyline(DrawingContext context, Pen pen, double width, double height, params (double X, double Y)[] points)
    {
        for (var index = 1; index < points.Length; index++)
        {
            var previous = new Point(points[index - 1].X * width, points[index - 1].Y * height);
            var current = new Point(points[index].X * width, points[index].Y * height);
            context.DrawLine(pen, previous, current);
        }
    }

    private static void DrawNode(DrawingContext context, Point point, Color color, double radius)
    {
        context.DrawEllipse(new SolidColorBrush(Color.FromArgb(34, color.R, color.G, color.B)), null,
            point, radius * 3.7, radius * 3.7);
        context.DrawEllipse(new SolidColorBrush(color), null, point, radius, radius);
    }
}
