using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using DeyttConnect.Windows.Controls;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private Control BuildHomeRouteStage()
    {
        var selected = _routes.FirstOrDefault(route => route.Id == _selectedRoute);
        var content = new StackPanel { Spacing = 0 };
        var heading = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var headingCopy = new StackPanel { Spacing = 5 };
        headingCopy.Children.Add(DeyttTheme.TextBlock(Copy("ВЫБРАННЫЙ МАРШРУТ", "SELECTED ROUTE"),
            10, DeyttTheme.Sky, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        var routeName = DeyttTheme.TextBlock(RouteTitle(), _compactLayout ? 23 : 30,
            DeyttTheme.Text, FontWeight.Bold, DeyttTheme.InterTight, wrap: false);
        routeName.TextTrimming = TextTrimming.CharacterEllipsis;
        headingCopy.Children.Add(routeName);
        heading.Children.Add(headingCopy);

        var changeRoute = DeyttTheme.Action(DeyttTheme.TextBlock(
            Copy("Сменить ↗", "Change ↗"), 13, DeyttTheme.Sky, FontWeight.SemiBold, wrap: false),
            () => ShowTab(MainTab.Routes));
        changeRoute.VerticalAlignment = VerticalAlignment.Center;
        changeRoute.Margin = new Thickness(14, 0, 0, 0);
        Grid.SetColumn(changeRoute, 1);
        heading.Children.Add(changeRoute);
        content.Children.Add(heading);

        content.Children.Add(new RoutePathVisual
        {
            IsActive = IsVpnDisplayConnected(),
            Height = _compactLayout ? 125 : 175,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Margin = new Thickness(-20, 9),
        });

        var endpoints = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*") };
        endpoints.Children.Add(RouteEndpoint(Copy("ВАША СЕТЬ", "YOUR NETWORK"),
            GetMapOriginLabel(), GetMapOriginHint(), HorizontalAlignment.Left));
        var exitLabel = IsVpnDisplayConnected() && _mapEgressLocation is { } egress &&
                        !string.IsNullOrWhiteSpace(egress.PlaceLabel)
            ? egress.PlaceLabel
            : SelectedExitPlaceLabel(selected);
        var exit = RouteEndpoint(Copy("ВЫБРАННЫЙ ВЫХОД", "SELECTED EXIT"),
            exitLabel, IsVpnDisplayConnected()
                ? GetMapEgressHint()
                : Copy("применится при подключении", "used when connecting"),
            HorizontalAlignment.Right);
        Grid.SetColumn(exit, 1);
        endpoints.Children.Add(exit);
        content.Children.Add(endpoints);

        content.Children.Add(new Border
        {
            Height = 1,
            Background = DeyttTheme.Brush(DeyttTheme.Line),
            Margin = new Thickness(0, 18, 0, 12),
        });
        content.Children.Add(BuildHomeRouteDiagnostic(selected?.Tag));

        return new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.MapSurface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(22),
            Padding = new Thickness(_compactLayout ? 20 : 28, _compactLayout ? 18 : 24),
            Child = content,
        };
    }

    private static StackPanel RouteEndpoint(string eyebrow, string location, string detail,
        HorizontalAlignment alignment)
    {
        var endpoint = new StackPanel { Spacing = 4, HorizontalAlignment = alignment };
        endpoint.Children.Add(DeyttTheme.TextBlock(eyebrow, 10, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        endpoint.Children.Add(DeyttTheme.TextBlock(location, 16, DeyttTheme.Text,
            FontWeight.SemiBold, wrap: false));
        var detailText = DeyttTheme.TextBlock(detail, 10, DeyttTheme.Muted, wrap: false);
        detailText.TextTrimming = TextTrimming.CharacterEllipsis;
        endpoint.Children.Add(detailText);
        return endpoint;
    }

    private Control BuildHomeRouteDiagnostic(string? routeTag)
    {
        if (routeTag is not null && _routeProbeResults.TryGetValue(routeTag, out var quality) &&
            (quality.LatencyMilliseconds is not null || quality.BytesPerSecond is not null))
        {
            var measurements = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 22 };
            if (quality.LatencyMilliseconds is { } latency)
                measurements.Children.Add(DeyttTheme.TextBlock(
                    $"{Copy("Пинг", "Latency")} {latency} ms", 12, DeyttTheme.Muted));
            if (quality.BytesPerSecond is { } bytesPerSecond)
                measurements.Children.Add(DeyttTheme.TextBlock(
                    $"{Copy("Скорость", "Speed")} {bytesPerSecond * 8d / 1_000_000d:0.#} Mbps",
                    12, DeyttTheme.Muted));
            return measurements;
        }

        return DeyttTheme.Action(DeyttTheme.TextBlock(
            Copy("Проверить маршрут в диагностике ↗", "Check route diagnostics ↗"),
            12, DeyttTheme.Sky, FontWeight.SemiBold), () => ShowTab(MainTab.Routes));
    }
}
