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
        var routeName = DeyttTheme.TextBlock(RouteTitle(), _shortCompactLayout ? 20 : _compactLayout ? 23 : 30,
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

        var availableLocations = _routes
            .SelectMany(route => route.CountryCode.ToUpperInvariant() switch
            {
                "NL" => new[] { "nl" },
                "DE" => new[] { "de" },
                "FI" => new[] { "fi" },
                "RU" => new[] { "ru" },
                "RU-DE" => new[] { "ru", "de" },
                _ => Array.Empty<string>(),
            })
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        var globe = _routeGlobeMap ??= CreateRouteGlobe();
        globe.SelectedRoute = _selectedRoute;
        globe.Language = _language;
        globe.ReducedMotion = _reduceMotion;
        globe.AvailableLocations = availableLocations;
        globe.OriginConsentGranted = _mapRegionEnabled && _mapRegionConsentGranted;
        globe.OriginLocation = _mapOriginLocation;
        globe.ExitCoordinate = GetMapExitCoordinate();
        globe.EgressCountryCode = IsVpnDisplayConnected() ? _mapEgressLocation?.CountryCode : null;
        globe.ActiveAutoRouteKey = null;
        // Connection state is separately verified by the tunnel service. Traffic counters are not exposed.
        globe.TrafficActive = false;

        if (_routeGlobeHost?.Children.Contains(globe) == true)
            _routeGlobeHost.Children.Remove(globe);
        var globeHost = new Grid
        {
            ClipToBounds = true,
            Margin = _shortCompactLayout
                ? new Thickness(-18, 0, -18, 1)
                : new Thickness(-(_compactLayout ? 18 : 24), _compactLayout ? 5 : 8,
                    -(_compactLayout ? 18 : 24), _compactLayout ? 5 : 10),
        };
        globeHost.Children.Add(globe);
        _routeGlobeHost = globeHost;
        content.Children.Add(globeHost);

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
            Margin = new Thickness(0, _shortCompactLayout ? 3 : _compactLayout ? 6 : 18, 0,
                _shortCompactLayout ? 2 : _compactLayout ? 4 : 12),
        });
        content.Children.Add(BuildHomeRouteDiagnostic(selected?.Tag));

        return new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.MapSurface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(22),
            ClipToBounds = true,
            Padding = new Thickness(_compactLayout ? 18 : 28,
                _shortCompactLayout ? 8 : _compactLayout ? 10 : 24),
            Child = content,
        };
    }

    private RouteGlobeWebView CreateRouteGlobe()
    {
        var globe = new RouteGlobeWebView
        {
            MinHeight = _shortCompactLayout ? 180 : _compactLayout ? 270 : 320,
            Height = _shortCompactLayout ? 195 : _compactLayout ? 330 : 390,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
        };
        globe.SizeChanged += (_, _) => UpdateRouteGlobeHeight();
        globe.NodeTapped += node => _ = ShowMapRoutePickerAsync(node);
        return globe;
    }

    private void UpdateRouteGlobeHeight()
    {
        if (_routeGlobeMap is null || _routeGlobeMap.Bounds.Width <= 0)
            return;

        var compact = _compactLayout;
        _routeGlobeMap.MinHeight = _shortCompactLayout ? 180 : compact ? 270 : 320;
        var height = _shortCompactLayout
            ? Math.Clamp(_routeGlobeMap.Bounds.Width * 0.24, 180, 205)
            : compact
            ? Math.Clamp(_routeGlobeMap.Bounds.Width * 0.36, 260, 315)
            : Math.Clamp(_routeGlobeMap.Bounds.Width * 0.39, 360, 460);
        if (Math.Abs(_routeGlobeMap.Height - height) > 1)
            _routeGlobeMap.Height = height;
    }

    private MapCoordinate? GetMapExitCoordinate()
    {
        if (IsVpnDisplayConnected() && _mapEgressLocation is { } egress)
            return new MapCoordinate(egress.Latitude, egress.Longitude);

        var route = _routes.FirstOrDefault(value => value.Id == _selectedRoute);
        return (route?.CountryCode ?? _selectedRoute).ToUpperInvariant() switch
        {
            "NL" => new MapCoordinate(52.3676, 4.9041),
            "DE" or "RU-DE" => new MapCoordinate(50.1109, 8.6821),
            "FI" => new MapCoordinate(60.1699, 24.9384),
            "RU" => new MapCoordinate(59.9343, 30.3351),
            _ => null,
        };
    }

    private static StackPanel RouteEndpoint(string eyebrow, string location, string detail,
        HorizontalAlignment alignment)
    {
        var endpoint = new StackPanel { Spacing = 4, HorizontalAlignment = alignment };
        endpoint.Children.Add(DeyttTheme.TextBlock(eyebrow, 10, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        var locationText = DeyttTheme.TextBlock(location, 15, DeyttTheme.Text,
            FontWeight.SemiBold, wrap: false);
        locationText.TextTrimming = TextTrimming.CharacterEllipsis;
        endpoint.Children.Add(locationText);
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
