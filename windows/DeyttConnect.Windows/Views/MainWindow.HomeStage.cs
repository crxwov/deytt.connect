using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using DeyttConnect.Windows.Controls;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private Grid? _homeOverviewGrid;
    private Control? _homeMapPanel;

    private Control BuildHomePage()
    {
        var overview = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,*"),
            ColumnSpacing = 24,
            RowDefinitions = new RowDefinitions("*"),
        };
        _homeOverviewGrid = overview;
        _homeMapPanel = BuildMapPanel();
        overview.Children.Add(_homeMapPanel);

        var connectionPanel = BuildConnectionCard();
        Grid.SetColumn(connectionPanel, 1);
        overview.Children.Add(connectionPanel);
        UpdateHomeColumns();
        return overview;
    }

    private Control BuildMapPanel()
    {
        var map = _routeGlobe ??= CreateRouteGlobe();
        var availableLocations = _routes
            .SelectMany(route => route.CountryCode.ToUpperInvariant() switch
            {
                "NL" => new[] { "nl" },
                "DE" => new[] { "de" },
                "FI" => new[] { "fi" },
                "RU" => new[] { "ru" },
                "IT" => new[] { "it" },
                "RU-DE" => new[] { "ru", "de" },
                _ => Array.Empty<string>(),
            })
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        map.SelectedRoute = _selectedRoute;
        map.Language = _language;
        map.AvailableLocations = availableLocations;
        map.OriginConsentGranted = _mapRegionEnabled && _mapRegionConsentGranted;
        map.OriginLocation = _mapOriginLocation;
        map.TrafficActive = IsVpnDisplayConnected();
        map.ReducedMotion = _reduceMotion;
        var actualEgress = IsVpnDisplayConnected() ? _mapEgressLocation : null;
        map.EgressCountryCode = actualEgress?.CountryCode;
        map.ActiveAutoRouteKey = _selectedRoute == "auto" && IsVpnDisplayConnected()
            ? RouteGlobeWebView.RouteKeyFor(_vpnSnapshot.RouteTag)
            : null;

        var layout = new Grid();
        if (map.Parent is Panel oldHost)
            oldHost.Children.Remove(map);
        layout.Children.Add(map);
        return new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.MapSurface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(24),
            ClipToBounds = true,
            Child = layout,
        };
    }

    private Control BuildHomeRouteDetails()
    {
        var selected = _routes.FirstOrDefault(route => route.Id == _selectedRoute);
        var displayedRoute = IsVpnDisplayConnected()
            ? _routes.FirstOrDefault(route => route.Tag == _vpnSnapshot.RouteTag) ?? selected
            : selected;
        var details = new Grid
        {
            RowDefinitions = new RowDefinitions("Auto,Auto,Auto"),
            RowSpacing = _compactLayout ? 12 : 16,
        };

        var heading = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var titleStack = new StackPanel { Spacing = 5 };
        titleStack.Children.Add(DeyttTheme.TextBlock(ProtocolPath(displayedRoute),
            _compactLayout ? 20 : 22, DeyttTheme.Text, FontWeight.SemiBold,
            DeyttTheme.JetBrainsMono, wrap: false));
        var routeName = displayedRoute?.CountryCode switch
        {
            "RU-DE" => Copy("LTE · RU+DE", "LTE · RU+DE"),
            "AUTO" => Copy("Автоподбор", "Automatic route"),
            _ => displayedRoute?.CountryName ?? RouteTitle(),
        };
        var routeContext = DeyttTheme.TextBlock(
            $"{routeName} · {ProtocolDescriptor(displayedRoute)}", 12,
            DeyttTheme.Muted, FontWeight.Medium, wrap: false);
        routeContext.TextTrimming = TextTrimming.CharacterEllipsis;
        titleStack.Children.Add(routeContext);
        heading.Children.Add(titleStack);

        var changeRoute = DeyttTheme.Action(DeyttTheme.TextBlock(
            Copy("Маршруты ↗", "Routes ↗"), 12, DeyttTheme.Sky, FontWeight.SemiBold, wrap: false),
            () => ShowTab(MainTab.Routes));
        changeRoute.VerticalAlignment = VerticalAlignment.Center;
        changeRoute.Margin = new Thickness(12, 0, 0, 0);
        Grid.SetColumn(changeRoute, 1);
        heading.Children.Add(changeRoute);
        details.Children.Add(heading);

        var trafficPath = BuildHomeTrafficPath(displayedRoute);
        Grid.SetRow(trafficPath, 1);
        details.Children.Add(trafficPath);

        var diagnostics = BuildHomeRouteDiagnostic(displayedRoute?.Tag);
        Grid.SetRow(diagnostics, 2);
        details.Children.Add(diagnostics);
        return details;
    }

    private Control BuildHomeTrafficPath(WindowsRoute? route)
    {
        var origin = GetHomeOriginParts();
        var destination = route?.CountryCode == "AUTO"
            ? ("AUTO", Copy("Выход", "Exit"), Copy("выберется автоматически", "selected automatically"))
            : GetHomeDestinationParts(route);
        var points = route?.CountryCode == "RU-DE"
            ? new[]
            {
                (Copy("СЕТЬ", "NETWORK"), origin.Code, origin.Name, origin.Hint),
                (Copy("УЗЕЛ", "HOP"), "RU", Copy("Россия", "Russia"), Copy("узел маршрута", "route node")),
                (Copy("ВЫХОД", "EXIT"), destination.Item1, destination.Item2, destination.Item3),
            }
            : new[]
            {
                (Copy("СЕТЬ", "NETWORK"), origin.Code, origin.Name, origin.Hint),
                (Copy("ВЫХОД", "EXIT"), destination.Item1, destination.Item2, destination.Item3),
            };
        var columns = new List<string>();
        for (var index = 0; index < points.Length; index++)
        {
            columns.Add("*");
            if (index < points.Length - 1)
                columns.Add("Auto");
        }
        var path = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions(string.Join(",", columns)),
            ColumnSpacing = 8,
            VerticalAlignment = VerticalAlignment.Center,
        };
        for (var index = 0; index < points.Length; index++)
        {
            var point = points[index];
            var node = RoutePathNode(point.Item1, point.Item2, point.Item3, point.Item4);
            Grid.SetColumn(node, index * 2);
            path.Children.Add(node);
            if (index >= points.Length - 1)
                continue;
            var arrow = DeyttTheme.TextBlock("→", 19, DeyttTheme.Sky, FontWeight.Medium, wrap: false);
            arrow.HorizontalAlignment = HorizontalAlignment.Center;
            arrow.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(arrow, index * 2 + 1);
            path.Children.Add(arrow);
        }

        var card = DeyttTheme.Card(path, DeyttTheme.Surface, DeyttTheme.Line,
            16, new Thickness(13, 11));
        AutomationProperties.SetAutomationId(card, "HomeTrafficPath");
        return card;
    }

    private string ProtocolDescriptor(WindowsRoute? route) => route?.Protocol switch
    {
        "VLESS" or "TROJAN" => "WebSocket · TLS",
        "HYSTERIA2" => Copy("QUIC · UDP", "QUIC · UDP"),
        "AWG31" => "AmneziaWG 3.1",
        _ => route?.ProtocolName ?? Copy("Автоматически", "Automatic"),
    };

    private (string Code, string Name, string Hint) GetHomeOriginParts()
    {
        if (!_mapRegionEnabled || !_mapRegionConsentGranted)
            return ("—", Copy("Регион скрыт", "Region hidden"), Copy("по настройкам приватности", "privacy setting"));
        if (_mapOriginLocation is { } origin)
        {
            var city = !string.IsNullOrWhiteSpace(origin.City)
                ? origin.City
                : !string.IsNullOrWhiteSpace(origin.Region)
                    ? origin.Region
                    : Copy("Ваша сеть", "Your network");
            return (string.IsNullOrWhiteSpace(origin.CountryCode) ? "IP" : origin.CountryCode,
                city, Copy("примерно по IP · не GPS", "approx. by IP · not GPS"));
        }
        if (_mapLocationCancellation is not null && !_mapLocationRequestIsEgress)
            return ("IP", Copy("Определяем…", "Looking up…"), Copy("данные не сохраняются", "not stored"));
        if (_mapLocationIssue is not null)
            return ("—", Copy("Регион недоступен", "Region unavailable"), Copy("проверьте соединение", "check connection"));
        return ("IP", Copy("Текущая сеть", "Current network"), Copy("примерное определение", "approximate lookup"));
    }

    private (string Code, string Name, string Hint) GetHomeDestinationParts(WindowsRoute? route)
    {
        var actualEgress = IsVpnDisplayConnected() ? _mapEgressLocation : null;
        if (actualEgress is { } egress)
        {
            var city = !string.IsNullOrWhiteSpace(egress.City) ? egress.City : egress.Region;
            if (string.IsNullOrWhiteSpace(city))
                city = egress.CountryCode;
            return (string.IsNullOrWhiteSpace(egress.CountryCode) ? "VPN" : egress.CountryCode,
                city, Copy("выход проверен по IP", "egress verified by IP"));
        }

        var code = route?.CountryCode switch
        {
            "RU-DE" => "DE",
            "NL" => "NL",
            "DE" => "DE",
            "FI" => "FI",
            "RU" => "RU",
            _ => "AUTO",
        };
        var name = SelectedExitPlaceLabel(route);
        var hint = route is null || route.CountryCode == "AUTO"
            ? Copy("узел выберется автоматически", "node selected automatically")
            : Copy("выбранный выход", "selected exit");
        return (code, name, hint);
    }

    private static string ProtocolPath(WindowsRoute? route) => route?.Protocol.ToUpperInvariant() switch
    {
        "VLESS" => "./vless+ws",
        "TROJAN" => "./trojan+ws",
        "HYSTERIA2" => "./hysteria2",
        "AWG31" => "./amnezia3.1",
        "CHAIN" => "./lte ru+de",
        _ => "./auto",
    };

    private static Control RoutePathNode(string eyebrow, string code, string name, string hint)
    {
        var endpoint = new StackPanel { Spacing = 7 };
        endpoint.Children.Add(DeyttTheme.TextBlock(eyebrow, 8, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        var hasLocationMarker = code is "NL" or "RU" or "DE" or "FI";
        var location = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions(hasLocationMarker ? "Auto,*" : "*"),
            ColumnSpacing = 6,
        };
        if (hasLocationMarker)
            location.Children.Add(RouteFlagVisual.Create(code, 23, 15));
        var locationText = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        var locationName = DeyttTheme.TextBlock(name, 13, DeyttTheme.Text,
            FontWeight.SemiBold, DeyttTheme.InterTight, wrap: false);
        locationName.TextTrimming = TextTrimming.CharacterEllipsis;
        locationText.Children.Add(locationName);
        var detail = DeyttTheme.TextBlock(hint, 9, DeyttTheme.Muted, wrap: false);
        detail.TextTrimming = TextTrimming.CharacterEllipsis;
        locationText.Children.Add(detail);
        Grid.SetColumn(locationText, hasLocationMarker ? 1 : 0);
        location.Children.Add(locationText);
        endpoint.Children.Add(location);
        return endpoint;
    }

    private RouteGlobeWebView CreateRouteGlobe()
    {
        var map = new RouteGlobeWebView
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
            Language = _language,
            ReducedMotion = _reduceMotion,
        };
        map.NodeTapped += node => _ = ShowMapRoutePickerAsync(node);
        return map;
    }

    private void UpdateHomeColumns()
    {
        UpdateHomeConnectionLayout();
        if (_homeOverviewGrid is null || _homeMapPanel is null || _homeConnectionCard is null)
            return;

        var availableWidth = PageHost.Width > 0 ? PageHost.Width : PageScroll.Bounds.Width;
        var availableHeight = PageScroll.Bounds.Height - PageHost.Margin.Top - PageHost.Margin.Bottom;
        var split = availableWidth >= 800;
        _homeOverviewGrid.ColumnDefinitions = new ColumnDefinitions(split ? "*,*" : "*");
        _homeOverviewGrid.RowDefinitions = new RowDefinitions(split ? "*" : "Auto,Auto");
        _homeOverviewGrid.ColumnSpacing = split ? 24 : 0;
        _homeOverviewGrid.RowSpacing = split ? 0 : 16;
        _homeOverviewGrid.Height = split ? Math.Clamp(availableHeight, 480, 520) : double.NaN;

        Grid.SetColumn(_homeMapPanel, 0);
        Grid.SetRow(_homeMapPanel, 0);
        Grid.SetColumn(_homeConnectionCard, split ? 1 : 0);
        Grid.SetRow(_homeConnectionCard, split ? 0 : 1);
        _homeMapPanel.Margin = split ? new Thickness(0, 0, 12, 0) : new Thickness(0);
        _homeConnectionCard.Margin = split ? new Thickness(12, 0, 0, 0) : new Thickness(0);

        if (_routeGlobe is not null)
            _routeGlobe.Height = split ? double.NaN : Math.Clamp(availableWidth * 0.68, 320, 480);
    }

    private Control BuildHomeRouteDiagnostic(string? routeTag)
    {
        var result = routeTag is not null ? _routeProbeResults.GetValueOrDefault(routeTag) : null;
        var latency = result?.LatencyMilliseconds is { } ping ? $"{ping} ms" : "— ms";
        var speed = result?.BytesPerSecond is { } bytesPerSecond && bytesPerSecond > 0
            ? $"{bytesPerSecond * 8d / 1_000_000d:0.#} Mbps"
            : "— Mbps";
        var measurements = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,*"),
            ColumnSpacing = 16,
        };
        measurements.Children.Add(HomeMeasurement(Copy("ПИНГ", "PING"), latency));
        var speedMeasurement = HomeMeasurement(Copy("СКОРОСТЬ", "SPEED"), speed);
        Grid.SetColumn(speedMeasurement, 1);
        measurements.Children.Add(speedMeasurement);

        var details = new StackPanel { Spacing = 8 };
        var measurementCard = DeyttTheme.Card(measurements, DeyttTheme.Surface, DeyttTheme.Line,
            14, new Thickness(12, 9));
        AutomationProperties.SetAutomationId(measurementCard, "HomeRouteMeasurements");
        details.Children.Add(measurementCard);
        details.Children.Add(DeyttTheme.Action(DeyttTheme.TextBlock(
            Copy("Все замеры и диагностика ↗", "All measurements and diagnostics ↗"),
            11, DeyttTheme.Sky, FontWeight.SemiBold), () => ShowTab(MainTab.Routes)));
        return details;
    }

    private static StackPanel HomeMeasurement(string label, string value)
    {
        var cell = new StackPanel { Spacing = 4 };
        cell.Children.Add(DeyttTheme.TextBlock(label, 9, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        cell.Children.Add(DeyttTheme.TextBlock(value, 14, DeyttTheme.Text,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        return cell;
    }
}
