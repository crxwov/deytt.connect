using Avalonia;
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
        var mapLabel = new StackPanel { Spacing = 4, HorizontalAlignment = HorizontalAlignment.Left };
        mapLabel.Children.Add(DeyttTheme.TextBlock(Copy("КАРТА СЕТИ", "NETWORK MAP"),
            10, DeyttTheme.Sky, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        mapLabel.Children.Add(DeyttTheme.TextBlock(Copy("Встроенная карта · регион только с согласия",
            "Embedded map · region shown with consent"), 11, DeyttTheme.Muted));
        var mapLabelSurface = new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(218, 8, 15, 23)),
            BorderBrush = DeyttTheme.Brush(Color.Parse("#283B49")),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(13, 10),
            Margin = new Thickness(18),
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Top,
            Child = mapLabel,
        };
        mapLabelSurface.ZIndex = 1;
        layout.Children.Add(mapLabelSurface);

        var zoomControls = new StackPanel
        {
            Spacing = 7,
            Margin = new Thickness(16),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
        };
        zoomControls.Children.Add(MapControlButton("+", Copy("Увеличить карту", "Zoom in"), map.ZoomIn));
        zoomControls.Children.Add(MapControlButton("-", Copy("Уменьшить карту", "Zoom out"), map.ZoomOut));
        zoomControls.Children.Add(MapControlButton("1:1", Copy("Сбросить масштаб", "Reset map"), map.ResetView));
        zoomControls.ZIndex = 1;
        layout.Children.Add(zoomControls);

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

    private static Button MapControlButton(string glyph, string label, Action action)
    {
        var button = new Button
        {
            Width = 38,
            Height = 38,
            Padding = new Thickness(0),
            Content = DeyttTheme.TextBlock(glyph, 20, DeyttTheme.Text, FontWeight.Medium,
                DeyttTheme.InterTight, wrap: false),
            Background = DeyttTheme.Brush(Color.Parse("#182530")),
            BorderBrush = DeyttTheme.Brush(Color.Parse("#385261")),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12),
            HorizontalContentAlignment = HorizontalAlignment.Center,
            VerticalContentAlignment = VerticalAlignment.Center,
        };
        ToolTip.SetTip(button, label);
        button.Click += (_, _) => action();
        return button;
    }

    private Control BuildHomeRouteDetails()
    {
        var selected = _routes.FirstOrDefault(route => route.Id == _selectedRoute);
        var details = new StackPanel { Spacing = 0 };

        var heading = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var titleStack = new StackPanel { Spacing = 5 };
        titleStack.Children.Add(DeyttTheme.TextBlock(Copy("ВЫБРАННЫЙ МАРШРУТ", "SELECTED ROUTE"),
            10, DeyttTheme.Sky, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        var routeName = selected?.CountryCode switch
        {
            "RU-DE" => Copy("Россия → Германия", "Russia → Germany"),
            "AUTO" => Copy("Автоподбор маршрута", "Automatic route"),
            _ => selected?.CountryName ?? RouteTitle(),
        };
        var routeTitle = DeyttTheme.TextBlock(routeName, _compactLayout ? 21 : 26,
            DeyttTheme.Text, FontWeight.Bold, DeyttTheme.InterTight, wrap: false);
        routeTitle.TextTrimming = TextTrimming.CharacterEllipsis;
        titleStack.Children.Add(routeTitle);
        heading.Children.Add(titleStack);

        var changeRoute = DeyttTheme.Action(DeyttTheme.TextBlock(
            Copy("Изменить", "Change"), 13, DeyttTheme.Sky, FontWeight.SemiBold, wrap: false),
            () => ShowTab(MainTab.Routes));
        changeRoute.VerticalAlignment = VerticalAlignment.Center;
        changeRoute.Margin = new Thickness(12, 0, 0, 0);
        Grid.SetColumn(changeRoute, 1);
        heading.Children.Add(changeRoute);
        details.Children.Add(heading);
        details.Children.Add(DeyttTheme.Spacer(24));

        var endpoints = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto,*"),
            ColumnSpacing = 12,
        };
        var origin = GetHomeOriginParts();
        endpoints.Children.Add(RouteEndpoint(Copy("ОТКУДА", "FROM"), origin.Code, origin.Name,
            origin.Hint, HorizontalAlignment.Left));
        var arrow = DeyttTheme.TextBlock("→", 28, DeyttTheme.Sky, FontWeight.Medium, wrap: false);
        arrow.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(arrow, 1);
        endpoints.Children.Add(arrow);

        var destination = GetHomeDestinationParts(selected);
        var exit = RouteEndpoint(Copy("КУДА", "TO"), destination.Code, destination.Name,
            destination.Hint, HorizontalAlignment.Right);
        Grid.SetColumn(exit, 2);
        endpoints.Children.Add(exit);
        details.Children.Add(endpoints);
        details.Children.Add(DeyttTheme.Spacer(24));
        details.Children.Add(DeyttTheme.Hairline());
        details.Children.Add(DeyttTheme.Spacer(18));

        var protocol = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var protocolCopy = new StackPanel { Spacing = 5 };
        protocolCopy.Children.Add(DeyttTheme.TextBlock(Copy("ПРОТОКОЛ", "PROTOCOL"),
            10, DeyttTheme.Muted, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        protocolCopy.Children.Add(DeyttTheme.TextBlock(ProtocolPath(selected), 18,
            DeyttTheme.Text, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        protocol.Children.Add(protocolCopy);
        var protocolName = DeyttTheme.TextBlock(selected?.ProtocolName ?? Copy("Автоподбор", "Automatic"),
            12, DeyttTheme.Muted, FontWeight.Medium);
        protocolName.TextAlignment = TextAlignment.Right;
        protocolName.MaxWidth = 130;
        Grid.SetColumn(protocolName, 1);
        protocol.Children.Add(protocolName);
        details.Children.Add(protocol);
        details.Children.Add(DeyttTheme.Spacer(18));
        details.Children.Add(BuildHomeRouteDiagnostic(selected?.Tag));
        return details;
    }

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
        "VLESS" => "./vless",
        "TROJAN" => "./trojan",
        "HYSTERIA2" => "./hysteria2",
        "AWG31" => "./amnezia3.1",
        "CHAIN" => "./ru-de",
        _ => "./auto",
    };

    private static StackPanel RouteEndpoint(string eyebrow, string code, string name, string hint,
        HorizontalAlignment alignment)
    {
        var endpoint = new StackPanel { Spacing = 5, HorizontalAlignment = alignment };
        endpoint.Children.Add(DeyttTheme.TextBlock(eyebrow, 10, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        endpoint.Children.Add(DeyttTheme.TextBlock(code.ToUpperInvariant(), 12, DeyttTheme.Sky,
            FontWeight.Bold, DeyttTheme.JetBrainsMono, wrap: false));
        var location = DeyttTheme.TextBlock(name, 19, DeyttTheme.Text,
            FontWeight.SemiBold, DeyttTheme.InterTight, wrap: false);
        location.TextTrimming = TextTrimming.CharacterEllipsis;
        endpoint.Children.Add(location);
        var detail = DeyttTheme.TextBlock(hint, 10, DeyttTheme.Muted, wrap: false);
        detail.TextTrimming = TextTrimming.CharacterEllipsis;
        endpoint.Children.Add(detail);
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
        _homeOverviewGrid.Height = split ? Math.Max(520, availableHeight) : double.NaN;

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
            Copy("Открыть диагностику ↗", "Open diagnostics ↗"),
            12, DeyttTheme.Sky, FontWeight.SemiBold), () => ShowTab(MainTab.Routes));
    }
}
