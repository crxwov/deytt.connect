using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;

namespace DeyttConnect.Windows.Controls;

/// <summary>A route diagram; its geometry does not claim to show a real geographic path.</summary>
public sealed class RoutePathVisual : Control
{
    public bool IsActive { get; init; }

    public override void Render(DrawingContext context)
    {
        base.Render(context);
        var width = Bounds.Width;
        var height = Bounds.Height;
        if (width < 80 || height < 60)
            return;

        var left = new Point(width * 0.12, height * 0.67);
        var right = new Point(width * 0.88, height * 0.32);
        var control = new Point(width * 0.51, height * 0.03);
        var gridPen = new Pen(new SolidColorBrush(Color.Parse("#172B36")), 1);
        var ghostPen = new Pen(new SolidColorBrush(Color.Parse("#193444")), 1);
        var routePen = new Pen(new SolidColorBrush(IsActive
            ? Color.Parse("#63E0B4") : Color.Parse("#6BDDF2")), 2.6, lineCap: PenLineCap.Round);

        for (var row = 1; row <= 3; row++)
        {
            var y = height * row / 4;
            context.DrawLine(gridPen, new Point(0, y), new Point(width, y));
        }
        for (var column = 1; column <= 8; column++)
        {
            var x = width * column / 9;
            context.DrawLine(gridPen, new Point(x, 0), new Point(x, height));
        }

        DrawCurve(context, ghostPen, left, new Point(width * 0.43, height * 0.89), right, 24, false);
        DrawCurve(context, routePen, left, control, right, 32, !IsActive);
        DrawNode(context, left, Color.Parse("#6BDDF2"));
        DrawNode(context, right, IsActive ? Color.Parse("#63E0B4") : Color.Parse("#8494FF"));

        var midpoint = CurvePoint(left, control, right, 0.52);
        context.DrawEllipse(new SolidColorBrush(Color.Parse("#111D2B")), routePen, midpoint, 5, 5);
    }

    private static void DrawCurve(DrawingContext context, Pen pen, Point start, Point control,
        Point end, int segments, bool dashed)
    {
        var previous = start;
        for (var index = 1; index <= segments; index++)
        {
            var current = CurvePoint(start, control, end, index / (double)segments);
            if (!dashed || index % 3 != 0)
                context.DrawLine(pen, previous, current);
            previous = current;
        }
    }

    private static Point CurvePoint(Point start, Point control, Point end, double t)
    {
        var before = 1 - t;
        return new Point(before * before * start.X + 2 * before * t * control.X + t * t * end.X,
            before * before * start.Y + 2 * before * t * control.Y + t * t * end.Y);
    }

    private static void DrawNode(DrawingContext context, Point center, Color color)
    {
        context.DrawEllipse(new SolidColorBrush(Color.Parse("#142D38")), null, center, 19, 19);
        context.DrawEllipse(new SolidColorBrush(Color.Parse("#0B121C")),
            new Pen(new SolidColorBrush(color), 1.5), center, 11, 11);
        context.DrawEllipse(new SolidColorBrush(color), null, center, 4.5, 4.5);
    }
}
