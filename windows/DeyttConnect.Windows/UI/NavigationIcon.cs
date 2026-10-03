using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Media;

namespace DeyttConnect.Windows.UI;

internal enum NavigationIconKind
{
    Home,
    Routes,
    Profile,
    Settings,
}

internal sealed class NavigationIcon : Control
{
    private static readonly StreamGeometry Home = CreateHome();
    private static readonly StreamGeometry Routes = CreateRoutes();
    private static readonly StreamGeometry Profile = CreateProfile();
    private static readonly StreamGeometry Settings = CreateSettings();

    public NavigationIcon(NavigationIconKind kind, bool selected)
    {
        Kind = kind;
        Selected = selected;
        Width = 24;
        Height = 24;
        HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        IsHitTestVisible = false;
        AutomationProperties.SetName(this, kind.ToString());
    }

    public NavigationIconKind Kind { get; }

    public bool Selected { get; }

    public override void Render(DrawingContext context)
    {
        base.Render(context);
        var color = Selected ? DeyttTheme.Sky : DeyttTheme.Muted;
        var pen = new Pen(DeyttTheme.Brush(color), Selected ? 1.9 : 1.7,
            lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round);
        var geometry = Kind switch
        {
            NavigationIconKind.Home => Home,
            NavigationIconKind.Routes => Routes,
            NavigationIconKind.Profile => Profile,
            _ => Settings,
        };
        context.DrawGeometry(null, pen, geometry);

        if (Kind == NavigationIconKind.Profile)
        {
            context.DrawEllipse(null, pen, new Point(12, 7.2), 3.25, 3.25);
        }
        else if (Kind == NavigationIconKind.Routes)
        {
            foreach (var point in new[] { new Point(5, 17), new Point(12, 7), new Point(19, 15) })
                context.DrawEllipse(DeyttTheme.Brush(DeyttTheme.Background), pen, point, 2.15, 2.15);
        }
        else if (Kind == NavigationIconKind.Settings)
        {
            context.DrawEllipse(DeyttTheme.Brush(DeyttTheme.Background), pen, new Point(12, 12), 3.2, 3.2);
        }
    }

    private static StreamGeometry CreateHome()
    {
        var geometry = new StreamGeometry();
        using var path = geometry.Open();
        path.BeginFigure(new Point(3.5, 10.6), false);
        path.LineTo(new Point(12, 3.8));
        path.LineTo(new Point(20.5, 10.6));
        path.LineTo(new Point(19, 10.6));
        path.LineTo(new Point(19, 20));
        path.LineTo(new Point(13.7, 20));
        path.LineTo(new Point(13.7, 14.6));
        path.LineTo(new Point(10.3, 14.6));
        path.LineTo(new Point(10.3, 20));
        path.LineTo(new Point(5, 20));
        path.LineTo(new Point(5, 10.6));
        path.EndFigure(true);
        return geometry;
    }

    private static StreamGeometry CreateRoutes()
    {
        var geometry = new StreamGeometry();
        using var path = geometry.Open();
        path.BeginFigure(new Point(5, 17), false);
        path.LineTo(new Point(12, 7));
        path.LineTo(new Point(19, 15));
        path.EndFigure(false);
        return geometry;
    }

    private static StreamGeometry CreateProfile()
    {
        var geometry = new StreamGeometry();
        using var path = geometry.Open();
        path.BeginFigure(new Point(4, 20), false);
        path.CubicBezierTo(new Point(4.5, 15.8), new Point(7.2, 13.5), new Point(12, 13.5));
        path.CubicBezierTo(new Point(16.8, 13.5), new Point(19.5, 15.8), new Point(20, 20));
        path.EndFigure(false);
        return geometry;
    }

    private static StreamGeometry CreateSettings()
    {
        const int teeth = 8;
        var geometry = new StreamGeometry();
        using var path = geometry.Open();
        for (var index = 0; index < teeth * 2; index++)
        {
            var angle = -Math.PI / 2 + index * Math.PI / teeth;
            var radius = index % 2 == 0 ? 9.5 : 8;
            var point = new Point(12 + Math.Cos(angle) * radius, 12 + Math.Sin(angle) * radius);
            if (index == 0)
                path.BeginFigure(point, false);
            else
                path.LineTo(point);
        }
        path.EndFigure(true);
        return geometry;
    }
}
