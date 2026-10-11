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
    Devices,
    Help,
    ThemeLight,
    ThemeDark,
    ThemeSystem,
    WindowMinimize,
    WindowMaximize,
    WindowRestore,
    WindowClose,
}

internal sealed class NavigationIcon : Control
{
    private static readonly StreamGeometry Home = CreateHome();
    private static readonly StreamGeometry Routes = CreateRoutes();
    private static readonly StreamGeometry Profile = CreateProfile();
    private static readonly StreamGeometry Settings = CreateSettings();
    private static readonly StreamGeometry Devices = CreateDevices();
    private static readonly StreamGeometry Help = CreateHelp();
    private static readonly StreamGeometry Moon = CreateMoon();
    private static readonly StreamGeometry System = CreateSystem();
    private static readonly StreamGeometry Maximize = CreateRectangle(5, 5, 19, 19);
    private static readonly StreamGeometry Restore = CreateRestore();

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
        if (Kind == NavigationIconKind.ThemeLight)
        {
            context.DrawEllipse(null, pen, new Point(12, 12), 3.5, 3.5);
            for (var ray = 0; ray < 8; ray++)
            {
                var angle = ray * Math.PI / 4;
                var inner = new Point(12 + Math.Cos(angle) * 6.5, 12 + Math.Sin(angle) * 6.5);
                var outer = new Point(12 + Math.Cos(angle) * 8.8, 12 + Math.Sin(angle) * 8.8);
                context.DrawLine(pen, inner, outer);
            }
            return;
        }
        if (Kind == NavigationIconKind.ThemeDark)
        {
            context.DrawGeometry(DeyttTheme.Brush(color), null, Moon);
            return;
        }
        if (Kind == NavigationIconKind.WindowMinimize)
        {
            context.DrawLine(pen, new Point(6, 12), new Point(18, 12));
            return;
        }
        if (Kind == NavigationIconKind.WindowClose)
        {
            context.DrawLine(pen, new Point(7, 7), new Point(17, 17));
            context.DrawLine(pen, new Point(17, 7), new Point(7, 17));
            return;
        }
        if (Kind == NavigationIconKind.Help)
        {
            context.DrawEllipse(null, pen, new Point(12, 12), 8, 8);
            context.DrawGeometry(null, pen, Help);
            context.DrawEllipse(DeyttTheme.Brush(color), null, new Point(12, 17.2), 1, 1);
            return;
        }
        var geometry = Kind switch
        {
            NavigationIconKind.Home => Home,
            NavigationIconKind.Routes => Routes,
            NavigationIconKind.Profile => Profile,
            NavigationIconKind.Settings => Settings,
            NavigationIconKind.Devices => Devices,
            NavigationIconKind.Help => Help,
            NavigationIconKind.ThemeSystem => System,
            NavigationIconKind.WindowMaximize => Maximize,
            NavigationIconKind.WindowRestore => Restore,
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

    private static StreamGeometry CreateDevices()
    {
        var geometry = new StreamGeometry();
        using var path = geometry.Open();
        path.BeginFigure(new Point(4.5, 5.2), false);
        path.CubicBezierTo(new Point(4.5, 4.2), new Point(5.2, 3.5), new Point(6.2, 3.5));
        path.LineTo(new Point(12.2, 3.5));
        path.CubicBezierTo(new Point(13.2, 3.5), new Point(13.9, 4.2), new Point(13.9, 5.2));
        path.LineTo(new Point(13.9, 18.8));
        path.CubicBezierTo(new Point(13.9, 19.8), new Point(13.2, 20.5), new Point(12.2, 20.5));
        path.LineTo(new Point(6.2, 20.5));
        path.CubicBezierTo(new Point(5.2, 20.5), new Point(4.5, 19.8), new Point(4.5, 18.8));
        path.EndFigure(true);
        path.BeginFigure(new Point(7, 17.5), false);
        path.LineTo(new Point(11.4, 17.5));
        path.EndFigure(false);
        path.BeginFigure(new Point(15.4, 7), false);
        path.LineTo(new Point(20, 7));
        path.LineTo(new Point(20, 15.2));
        path.EndFigure(false);
        path.BeginFigure(new Point(14.6, 17.8), false);
        path.LineTo(new Point(21, 17.8));
        path.EndFigure(false);
        return geometry;
    }

    private static StreamGeometry CreateHelp()
    {
        var geometry = new StreamGeometry();
        using var path = geometry.Open();
        path.BeginFigure(new Point(9.2, 9), false);
        path.CubicBezierTo(new Point(9.2, 5.7), new Point(15, 5.8), new Point(15, 9));
        path.CubicBezierTo(new Point(15, 10.9), new Point(12, 11), new Point(12, 13));
        path.EndFigure(false);
        return geometry;
    }

    private static StreamGeometry CreateMoon()
    {
        var geometry = new StreamGeometry();
        using var path = geometry.Open();
        path.BeginFigure(new Point(17.85, 17.1), true);
        path.CubicBezierTo(new Point(14.55, 19.3), new Point(10.55, 18.6), new Point(8.45, 15.6));
        path.CubicBezierTo(new Point(6.15, 12.3), new Point(7.05, 8.0), new Point(10.25, 5.9));
        path.CubicBezierTo(new Point(11.95, 4.8), new Point(14.05, 4.7), new Point(15.95, 5.4));
        path.CubicBezierTo(new Point(12.45, 6.7), new Point(10.95, 10.5), new Point(12.25, 13.7));
        path.CubicBezierTo(new Point(13.35, 16.6), new Point(15.65, 17.8), new Point(17.85, 17.1));
        path.EndFigure(true);
        return geometry;
    }

    private static StreamGeometry CreateSystem()
    {
        var geometry = new StreamGeometry();
        using var path = geometry.Open();
        path.BeginFigure(new Point(4.5, 5.5), false);
        path.LineTo(new Point(19.5, 5.5));
        path.LineTo(new Point(19.5, 16.5));
        path.LineTo(new Point(4.5, 16.5));
        path.EndFigure(true);
        path.BeginFigure(new Point(12, 16.8), false);
        path.LineTo(new Point(12, 20));
        path.EndFigure(false);
        path.BeginFigure(new Point(8.5, 20), false);
        path.LineTo(new Point(15.5, 20));
        path.EndFigure(false);
        return geometry;
    }

    private static StreamGeometry CreateRectangle(double left, double top, double right, double bottom)
    {
        var geometry = new StreamGeometry();
        using var path = geometry.Open();
        path.BeginFigure(new Point(left, top), false);
        path.LineTo(new Point(right, top));
        path.LineTo(new Point(right, bottom));
        path.LineTo(new Point(left, bottom));
        path.EndFigure(true);
        return geometry;
    }

    private static StreamGeometry CreateRestore()
    {
        var geometry = new StreamGeometry();
        using var path = geometry.Open();
        path.BeginFigure(new Point(8, 4.5), false);
        path.LineTo(new Point(18.5, 4.5));
        path.LineTo(new Point(18.5, 15));
        path.EndFigure(false);
        path.BeginFigure(new Point(5.5, 8), false);
        path.LineTo(new Point(16, 8));
        path.LineTo(new Point(16, 19.5));
        path.LineTo(new Point(5.5, 19.5));
        path.EndFigure(true);
        return geometry;
    }
}
