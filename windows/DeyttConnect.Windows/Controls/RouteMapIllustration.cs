using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;

namespace DeyttConnect.Windows.Controls;

public readonly record struct MapCoordinate(double Latitude, double Longitude);

public sealed class RouteMapIllustration : Control
{
    public MapCoordinate? OriginCoordinate { get; set; }
    public MapCoordinate? ExitCoordinate { get; set; }

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
        context.DrawEllipse(null, mapLine, center, width * 0.33, height * 0.38);
        context.DrawEllipse(null, orbit, center, width * 0.17, height * 0.38);
        context.DrawEllipse(null, orbit, center, width * 0.33, height * 0.14);
        context.DrawEllipse(null, orbit, center, width * 0.33, height * 0.25);

        DrawPolyline(context, mapLine, width, height,
            (0.30, 0.30), (0.37, 0.22), (0.45, 0.27), (0.49, 0.36),
            (0.56, 0.37), (0.62, 0.46), (0.57, 0.54), (0.62, 0.63),
            (0.55, 0.73), (0.48, 0.67), (0.44, 0.58), (0.37, 0.55), (0.33, 0.45));
        DrawPolyline(context, mapLine, width, height,
            (0.63, 0.32), (0.69, 0.26), (0.78, 0.28), (0.83, 0.36),
            (0.79, 0.44), (0.84, 0.49), (0.79, 0.57), (0.74, 0.52), (0.70, 0.44));

        var routePen = new Pen(new SolidColorBrush(Color.Parse("#6BDDF2")), 2.1, lineCap: PenLineCap.Round);
        if (OriginCoordinate is { } origin && ExitCoordinate is { } selectedExit)
        {
            var start = Project(origin, width, height);
            var exit = Project(selectedExit, width, height);
            DrawRouteArc(context, routePen, start, exit, height);
            DrawNode(context, start, Color.Parse("#6BDDF2"), 3.2);
            DrawNode(context, exit, Color.Parse("#8494FF"), 3.8);
        }
        else if (ExitCoordinate is { } destination)
        {
            var exit = Project(destination, width, height);
            var start = new Point(width * 0.28, height * 0.61);
            DrawRouteArc(context, routePen, start, exit, height);
            DrawNode(context, exit, Color.Parse("#8494FF"), 3.8);
        }
        else
        {
            DrawPolyline(context, routePen, width, height,
                (0.29, 0.63), (0.43, 0.62), (0.55, 0.53), (0.68, 0.48));
            DrawNode(context, new Point(width * 0.68, height * 0.48), Color.Parse("#8494FF"), 3.4);
        }
    }

    private static Point Project(MapCoordinate coordinate, double width, double height)
    {
        var latitude = Math.Clamp(coordinate.Latitude, -85, 85);
        var longitude = Math.Clamp(coordinate.Longitude, -180, 180);
        var x = 0.23 + ((longitude + 180) / 360 * 0.68);
        var y = 0.14 + ((85 - latitude) / 170 * 0.72);
        return new Point(width * x, height * y);
    }

    private static void DrawRouteArc(DrawingContext context, Pen pen, Point start, Point end, double height)
    {
        var control = new Point((start.X + end.X) / 2, Math.Max(height * 0.15, (start.Y + end.Y) / 2 - height * 0.16));
        var previous = start;
        for (var step = 1; step <= 24; step++)
        {
            var progress = step / 24d;
            var inverse = 1 - progress;
            var current = new Point(
                inverse * inverse * start.X + 2 * inverse * progress * control.X + progress * progress * end.X,
                inverse * inverse * start.Y + 2 * inverse * progress * control.Y + progress * progress * end.Y);
            context.DrawLine(pen, previous, current);
            previous = current;
        }
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
