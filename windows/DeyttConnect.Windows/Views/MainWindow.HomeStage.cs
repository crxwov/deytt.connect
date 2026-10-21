using Avalonia;
using System.Globalization;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using DeyttConnect.Windows.Controls;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private Control? _homeMapPanel;
    private CancellationTokenSource? _homeProbeCancellation;
    private WindowsRouteProbeResult? _homeLiveProbe;
    private DateTimeOffset _homeProbeUpdatedAt;
    private bool _homeSpeedSampled;
    private string? _homeMeasurementSession;

    private void RefreshHomeConnectionMeasurement(bool forceRefresh = false)
    {
        if (_qaFixture) return;
        var routeTag = _vpnSnapshot.RouteTag;
        if (!IsVpnDisplayConnected() || string.IsNullOrEmpty(routeTag))
        {
            _homeProbeCancellation?.Cancel();
            _homeLiveProbe = null;
            _homeSpeedSampled = false;
            _homeMeasurementSession = null;
            return;
        }
        var session = routeTag + "|" + _vpnSnapshot.ConnectedAt?.ToString("O", CultureInfo.InvariantCulture);
        if (_homeMeasurementSession != session)
        {
            _homeProbeCancellation?.Cancel();
            _homeLiveProbe = null;
            _homeSpeedSampled = false;
            _homeMeasurementSession = session;
        }
        if (_homeLiveProbe?.RouteTag == routeTag && _homeProbeCancellation is null)
        {
            _homeLiveProbe = _homeLiveProbe with
            {
                LatencyMilliseconds = _vpnSnapshot.TunnelLatencyMilliseconds,
                Error = _homeLiveProbe.Stage == "latency" ? null : _vpnSnapshot.TunnelLatencyError is null
                    ? _homeLiveProbe.Error
                    : Copy("Не удалось проверить HTTPS через VPN.", "Could not verify HTTPS through the VPN."),
            };
        }
        else if (_homeLiveProbe is null)
        {
            _homeLiveProbe = new(routeTag, _vpnSnapshot.TunnelLatencyMilliseconds, null,
                _vpnSnapshot.TunnelLatencyError is null ? null : Copy("Не удалось проверить HTTPS через VPN.",
                    "Could not verify HTTPS through the VPN."), "complete");
        }
        if (_homeProbeCancellation is not null || !forceRefresh && _homeLiveProbe?.RouteTag == routeTag && DateTimeOffset.UtcNow - _homeProbeUpdatedAt < TimeSpan.FromSeconds(30)) return;
        _ = MeasureHomeConnectionAsync(routeTag, forceRefresh || !_homeSpeedSampled, forceRefresh);
    }

    private async Task MeasureHomeConnectionAsync(string routeTag, bool speed, bool tunnelLatency)
    {
        using var cancellation = new CancellationTokenSource();
        _homeProbeCancellation = cancellation;
        var previousSpeed = _homeLiveProbe?.RouteTag == routeTag ? _homeLiveProbe.BytesPerSecond : null;
        long? latency = tunnelLatency ? null : _vpnSnapshot.TunnelLatencyMilliseconds;
        string? latencyError = null;
        _homeLiveProbe = new(routeTag, latency, previousSpeed, null, tunnelLatency ? "latency" : "complete");
        UpdateHomeGlobePresentation();
        void Apply(WindowsRouteProbeResult sample) => Dispatcher.UIThread.Post(() =>
        {
            if (!ReferenceEquals(_homeProbeCancellation, cancellation) || cancellation.IsCancellationRequested || _vpnSnapshot.RouteTag != routeTag || !IsVpnDisplayConnected()) return;
            _homeLiveProbe = sample with
            {
                LatencyMilliseconds = latency,
                Error = latencyError ?? sample.Error,
                Stage = tunnelLatency && latency is null ? "latency" : sample.Stage,
                BytesPerSecond = sample.BytesPerSecond ?? previousSpeed,
            };
            UpdateHomeGlobePresentation();
        });
        try
        {
            if (tunnelLatency)
            {
                var measuredTunnel = await _tunnelClient.MeasureTunnelLatencyAsync(cancellation.Token);
                if (!cancellation.IsCancellationRequested && _vpnSnapshot.RouteTag == routeTag && IsVpnDisplayConnected())
                {
                    _vpnSnapshot = _vpnSnapshot with
                    {
                        TunnelLatencyMilliseconds = measuredTunnel.TunnelLatencyMilliseconds,
                        TunnelLatencyError = measuredTunnel.TunnelLatencyError,
                        HealthCheckedAt = measuredTunnel.HealthCheckedAt,
                    };
                    latency = measuredTunnel.TunnelLatencyMilliseconds;
                    latencyError = measuredTunnel.TunnelLatencyError is null ? null :
                        Copy("Не удалось проверить HTTPS через VPN.", "Could not verify HTTPS through the VPN.");
                    UpdateHomeGlobePresentation();
                }
            }
            var result = await HomeConnectionProbe.MeasureSpeedAsync(
                routeTag, _sessionToken, speed && latency is not null, Apply, cancellation.Token);
            if (!cancellation.IsCancellationRequested && _vpnSnapshot.RouteTag == routeTag && IsVpnDisplayConnected())
            {
                _homeLiveProbe = result with
                {
                    LatencyMilliseconds = latency,
                    Error = latencyError ?? result.Error,
                    BytesPerSecond = result.BytesPerSecond ?? previousSpeed,
                };
                _homeSpeedSampled |= speed && result.BytesPerSecond is > 0;
            }
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        catch (Exception error) when (error is HttpRequestException or IOException or OperationCanceledException)
        {
            if (!cancellation.IsCancellationRequested && _vpnSnapshot.RouteTag == routeTag && _homeLiveProbe is { } sample)
                _homeLiveProbe = sample with { Stage = "error", Error = Copy("Не удалось измерить подключение. Повторите замер.", "Could not measure the connection. Retry.") };
        }
        finally
        {
            _homeProbeUpdatedAt = DateTimeOffset.UtcNow;
            if (ReferenceEquals(_homeProbeCancellation, cancellation)) _homeProbeCancellation = null;
            UpdateHomeGlobePresentation();
        }
    }

    private Control BuildHomePage()
    {
        if (OperatingSystem.IsWindows() && !_homeMapInitializationAllowed)
            return BuildMapLoadingView();

        var map = _routeGlobe ??= CreateRouteGlobe();
        ConfigureHomeMap(map);
        if (map.Parent is Panel oldHost)
            oldHost.Children.Remove(map);
        _homeMapPanel = map;
        UpdateHomeColumns();
        return map;
    }

    private void RefreshHomePageInPlace()
    {
        if (_routeGlobe is not { } map)
            return;

        ConfigureHomeMap(map);
        _homeMapPanel = map;

        if (_pageViews.TryGetValue(MainTab.Home, out var homePage) &&
            !homePage.View.Children.Contains(map))
        {
            if (map.Parent is Panel oldHost)
                oldHost.Children.Remove(map);

            foreach (var child in homePage.View.Children.OfType<Control>().ToArray())
            {
                if (!ReferenceEquals(child, map))
                    homePage.View.Children.Remove(child);
            }

            homePage.View.Children.Add(map);
        }

        UpdateHomeColumns();
    }

    private void ConfigureHomeMap(RouteGlobeWebView map)
    {
        map.SelectedRoute = RouteGlobeWebView.RouteKeyFor(_selectedRoute, _routes);
        AutomationProperties.SetName(map,
            Copy("Интерактивный глобус маршрута deytt.", "Interactive deytt route globe"));
        AutomationProperties.SetHelpText(map, BuildHomeRouteAccessibilityDescription());
        map.Language = _language;
        map.AvailableLocations = GetAvailableMapLocations();
        map.OriginConsentGranted = _mapRegionEnabled && _mapRegionConsentGranted;
        map.OriginLocation = _mapOriginLocation;
        map.TrafficActive = IsVpnDisplayConnected();
        map.ReducedMotion = _reduceMotion;
        map.DarkTheme = DeyttTheme.IsDark;
        // A normal IP lookup follows the machine's upstream VPN; it cannot prove
        // which exit the DEYTT tunnel selected.
        map.EgressCountryCode = null;
        map.ActiveAutoRouteKey = _selectedRoute == "auto" && IsVpnDisplayConnected()
            ? ConfirmedHomeAutoRouteKey()
            : null;
        var presentation = BuildHomePresentation();
        map.HomePresentation = presentation;
        AutomationProperties.SetHelpText(map, string.Join(" · ", new[]
        {
            presentation.Status,
            presentation.Country,
            presentation.City,
            presentation.Protocol,
        }.Where(value => !string.IsNullOrWhiteSpace(value))));
    }

    private string[] GetAvailableMapLocations() => _routes
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

    private Control BuildMapPanel()
    {
        var layout = new Grid();
        if ((!OperatingSystem.IsWindows() || _homeMapInitializationAllowed) &&
            _activeShellContentDialog is null)
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
            map.SelectedRoute = RouteGlobeWebView.RouteKeyFor(_selectedRoute, _routes);
            map.Language = _language;
            map.AvailableLocations = availableLocations;
            map.OriginConsentGranted = _mapRegionEnabled && _mapRegionConsentGranted;
            map.OriginLocation = _mapOriginLocation;
            map.TrafficActive = IsVpnDisplayConnected();
            map.ReducedMotion = _reduceMotion;
            // A normal IP lookup follows the machine's upstream VPN (for example Happ).
            // It cannot prove which exit the DEYTT tunnel selected.
            map.EgressCountryCode = null;
            map.ActiveAutoRouteKey = _selectedRoute == "auto" && IsVpnDisplayConnected()
                ? ConfirmedHomeAutoRouteKey()
                : null;

            if (map.Parent is Panel oldHost)
                oldHost.Children.Remove(map);
            layout.Children.Add(map);
        }
        else
        {
            layout.Children.Add(BuildMapLoadingView());
        }

        return layout;
    }

    private Control BuildMapLoadingView()
    {
        var orbit = new Canvas { Width = 228, Height = 228 };
        foreach (var (inset, opacity) in new[] { (0d, 0.18d), (28d, 0.24d), (57d, 0.3d) })
        {
            var ring = new Ellipse
            {
                Width = 228 - inset * 2,
                Height = 228 - inset * 2,
                Stroke = DeyttTheme.Brush(DeyttTheme.Sky),
                StrokeThickness = 1,
                Opacity = opacity,
            };
            Canvas.SetLeft(ring, inset);
            Canvas.SetTop(ring, inset);
            orbit.Children.Add(ring);
        }

        var route = new Line
        {
            StartPoint = new Point(46, 159),
            EndPoint = new Point(173, 73),
            Stroke = DeyttTheme.Brush(DeyttTheme.Sky),
            StrokeThickness = 1.5,
            Opacity = 0.46,
        };
        orbit.Children.Add(route);
        foreach (var (x, y, color) in new[]
                 {
                     (46d, 159d, DeyttTheme.Sky),
                     (173d, 73d, DeyttTheme.Mint),
                 })
        {
            var halo = new Ellipse
            {
                Width = 18, Height = 18,
                Fill = DeyttTheme.Brush(color),
                Opacity = 0.15,
            };
            Canvas.SetLeft(halo, x - 9);
            Canvas.SetTop(halo, y - 9);
            orbit.Children.Add(halo);
            var node = new Ellipse
            {
                Width = 6, Height = 6,
                Fill = DeyttTheme.Brush(color),
            };
            Canvas.SetLeft(node, x - 3);
            Canvas.SetTop(node, y - 3);
            orbit.Children.Add(node);
        }

        var label = DeyttTheme.TextBlock(Copy("Готовим атлас маршрутов", "Preparing route atlas"),
            12, DeyttTheme.Muted, FontWeight.Medium, wrap: false);
        label.HorizontalAlignment = HorizontalAlignment.Center;
        var placeholder = new StackPanel
        {
            Spacing = 12,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Children = { orbit, label },
        };
        return placeholder;
    }

    private Control BuildHomeRouteDetails()
    {
        var selected = _routes.FirstOrDefault(route => route.Id == _selectedRoute);
        var displayedRoute = IsVpnDisplayConnected()
            ? _routes.FirstOrDefault(route => route.Tag == _vpnSnapshot.RouteTag) ?? selected
            : selected;
        var details = new StackPanel { Spacing = _compactLayout ? 18 : 24 };
        var routeName = displayedRoute?.CountryCode switch
        {
            "RU-DE" => Copy("lte · белые списки", "lte · allowlist"),
            "AUTO" or null => Copy("автоподбор", "auto-select"),
            _ => RouteCountryName(displayedRoute),
        };
        var destination = GetHomeDestinationParts(displayedRoute);
        var location = new StackPanel { Spacing = 4 };
        location.Children.Add(DeyttTheme.TextBlock(routeName, 18, DeyttTheme.Muted,
            FontWeight.SemiBold));
        location.Children.Add(DeyttTheme.TextBlock(destination.Name, 32, DeyttTheme.Text,
            FontWeight.Bold));
        location.Children.Add(DeyttTheme.TextBlock(ProtocolDescriptor(displayedRoute), 18,
            DeyttTheme.Text, FontWeight.SemiBold));
        details.Children.Add(location);
        details.Children.Add(BuildHomeRouteDiagnostic(displayedRoute?.Tag));
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

    private string ProtocolDescriptor(WindowsRoute? route)
    {
        if (route is null || route.CountryCode == "AUTO")
            return Copy("Автоматически", "Automatic");
        return route.Protocol switch
        {
            "VLESS" => "./vless+ws",
            "TROJAN" => "./trojan",
            "HYSTERIA2" => "./hysteria",
            "AWG31" => "./amnezia3.1",
            _ when !string.IsNullOrWhiteSpace(route.Protocol) => "./" + route.Protocol.ToLowerInvariant(),
            _ => route.ProtocolName ?? Copy("Автоматически", "Automatic"),
        };
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
        var code = route?.CountryCode switch
        {
            "RU-DE" => "DE",
            "NL" => "NL",
            "DE" => "DE",
            "FI" => "FI",
            "IT" => "IT",
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
        var hasLocationMarker = code is "NL" or "RU" or "DE" or "FI" or "IT";
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
        var map = new RouteGlobeWebView(_disableNativeMapInitialization)
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
            Language = _language,
            ReducedMotion = _reduceMotion,
        };
        map.NodeTapped += node => _ = ShowMapRoutePickerAsync(node);
        map.HomeActionRequested += HandleHomeActionRequested;
        map.ImmersiveModeRequested += SetMapImmersive;
        return map;
    }

    private void UpdateHomeColumns()
    {
        if (_routeGlobe is null)
            return;

        var availableWidth = PageHost.Width > 0 ? PageHost.Width : PageScroll.Bounds.Width;
        var availableHeight = PageScroll.Bounds.Height - PageHost.Margin.Top - PageHost.Margin.Bottom;
        if (_mapImmersive)
            _routeGlobe.Height = Math.Max(1, availableHeight);
        else if (availableWidth >= 1180)
            _routeGlobe.Height = Math.Clamp(availableHeight, 520, 1000);
        else
            _routeGlobe.Height = Math.Clamp(Math.Max(availableHeight, availableWidth * .84), 720, 1100);
    }

    private HomePresentationSnapshot BuildHomePresentation()
    {
        var connected = IsVpnDisplayConnected();
        var tunnelActive = IsVpnTunnelActive(_vpnSnapshot.State);
        var healthUnknown = tunnelActive && !connected;
        var restoring = !_sessionRestoreComplete && !_qaFixture;
        var connecting = _vpnSnapshot.State is "starting" or "checking";
        var stopping = _vpnSnapshot.State == "stopping" || _vpnCancelInProgress;
        var routeProbeActive = _routeProbeInProgress;
        var canCancel = connecting && !_vpnCancelRequested && !routeProbeActive;
        var failed = _vpnSnapshot.State == "error";
        var displayedRoute = connected
            ? _routes.FirstOrDefault(route => route.Tag == (_vpnSnapshot.ActiveRouteTag ?? _vpnSnapshot.RouteTag))
            : null;
        displayedRoute ??= _routes.FirstOrDefault(route => route.Id == _selectedRoute);
        var destination = GetHomeDestinationParts(displayedRoute);
        var automatic = _selectedRoute == "auto";
        var exitCode = destination.Code is "NL" or "DE" or "RU" or "FI" or "IT" ? destination.Code.ToLowerInvariant() : "";
        var protoRaw = displayedRoute?.CountryCode is null or "AUTO" ? "" : ProtocolDescriptor(displayedRoute).ToLowerInvariant().Replace("./", "");
        var protocol = string.IsNullOrEmpty(exitCode)
            ? (displayedRoute?.CountryCode is null or "AUTO" ? "" : ProtocolDescriptor(displayedRoute).ToLowerInvariant())
            : $"{exitCode}./{protoRaw}";
        var country = automatic ? Copy("автоподбор", "auto-select") : "";
        var city = displayedRoute?.CountryCode is null or "AUTO"
            ? connected ? Copy("уточняем выбранный выход…", "confirming the selected exit…")
                : connecting || routeProbeActive ? Copy("выбираем выход…", "selecting an exit…")
                : Copy("выход выберется автоматически", "exit selected automatically")
            : displayedRoute?.Protocol == "AWG31" && !string.IsNullOrWhiteSpace(displayedRoute.ProfileName)
                ? destination.Name
                : automatic ? Copy($"{destination.Name} · {RouteCountryName(displayedRoute!)}", $"{destination.Name} · {RouteCountryName(displayedRoute!)}").ToLowerInvariant()
                : destination.Name.ToLowerInvariant();
        var status = restoring
            ? Copy("подключаемся…", "preparing connection…")
            : connected
                ? Copy("подключено", "connected")
                : stopping
                    ? Copy("отключаемся…", "disconnecting…")
                    : healthUnknown
                        ? Copy("проверяем соединение…", "checking connection…")
                        : connecting || _vpnActionInProgress || routeProbeActive
                            ? routeProbeActive
                                ? Copy("проверяем маршруты…", "checking routes…")
                                : Copy("подключаемся…", "connecting…")
                            : failed
                                ? _vpnServiceAvailable
                                    ? Copy("не удалось подключиться", "could not connect")
                                    : Copy("служба vpn недоступна", "vpn service unavailable")
                                : Copy("не подключено", "not connected");
        var message = restoring
            ? Copy("восстанавливаем сохранённую сессию", "restoring the saved session")
            : failed
                ? _vpnServiceAvailable
                    ? string.IsNullOrWhiteSpace(_vpnSnapshot.Detail)
                        ? Copy("проверьте маршрут и повторите подключение", "check the route and retry")
                        : LocalizeHomeDiagnostic(_vpnSnapshot.Detail)
                    : Copy("проверьте службу vpn в настройках", "check the vpn service in settings")
                : healthUnknown
                    ? Copy("туннель включён, выполняется проверка трафика", "the tunnel is on while traffic is being checked")
                    : !connected && _sessionToken is null
                        ? Copy("войдите через telegram, чтобы загрузить подписку", "sign in through telegram to load your subscription")
                        : !connected && (_keysSnapshot?.HappAvailable != true || string.IsNullOrWhiteSpace(_keysSnapshot.ProfileJson))
                            ? Copy("откройте профиль и обновите подписку", "open your profile and refresh the subscription")
                            : null;
        var action = tunnelActive ? "home-disconnect" : canCancel ? "home-cancel" : "home-connect";
        var actionLabel = restoring
            ? CopyExact("подготовка…", "preparing…")
            : stopping
                ? CopyExact("отключение…", "disconnecting…")
                : canCancel
                    ? CopyExact("отменить подключение", "cancel connection")
                    : tunnelActive
                        ? CopyExact("отключить", "disconnect")
                        : connecting || _vpnActionInProgress
                            ? CopyExact("подключаемся…", "connecting…")
                            : routeProbeActive
                                ? CopyExact("подождите…", "please wait…")
                                : _sessionToken is null
                                    ? CopyExact("войти через telegram", "sign in through telegram")
                                    : _keysSnapshot?.HappAvailable != true || string.IsNullOrWhiteSpace(_keysSnapshot.ProfileJson)
                                        ? CopyExact("открыть профиль", "open profile")
                                        : !_vpnServiceAvailable
                                            ? CopyExact("проверить службу", "check service")
                                            : failed ? CopyExact("повторить", "retry") : CopyExact("подключиться", "connect");
        if (!tunnelActive && !canCancel)
        {
            if (_sessionToken is null)
                action = "home-connect";
            else if (_keysSnapshot?.HappAvailable != true || string.IsNullOrWhiteSpace(_keysSnapshot.ProfileJson))
                action = "home-profile";
            else if (!_vpnServiceAvailable)
                action = "home-settings";
            else
                action = "home-connect";
        }
        var actionEnabled = !restoring && (!stopping && (!connecting && !_vpnActionInProgress && !routeProbeActive || canCancel) || healthUnknown || tunnelActive);
        if (failed && action is "home-connect") actionEnabled = true;

        var probe = displayedRoute is null ? null : _routeProbeResults.GetValueOrDefault(displayedRoute.Tag);
        var liveMeasurement = connected && _homeLiveProbe?.RouteTag == _vpnSnapshot.RouteTag;
        if (liveMeasurement)
            probe = _homeLiveProbe! with
            {
                LatencyMilliseconds = _homeLiveProbe.Stage == "latency"
                    ? _homeLiveProbe.LatencyMilliseconds : _vpnSnapshot.TunnelLatencyMilliseconds,
                Error = _vpnSnapshot.TunnelLatencyError is null ? _homeLiveProbe.Error :
                    Copy("Не удалось проверить HTTPS через VPN.", "Could not verify HTTPS through the VPN."),
            };
        var probeQuality = displayedRoute is null ? RouteQualityGrade.Unrated : CountryRouteQuality(
            _routes.Where(route => string.Equals(route.CountryCode, displayedRoute.CountryCode,
                StringComparison.OrdinalIgnoreCase)).ToArray()).Grades.GetValueOrDefault(displayedRoute.Tag);
        var latencyText = probe?.LatencyMilliseconds is { } latency
            ? latency.ToString(UiCulture) : probe?.Stage is "latency" or "retry" ? "…" : null;
        var speedBytes = probe?.BytesPerSecond;
        var speedText = speedBytes is > 0
            ? (speedBytes.Value * 8d / 1_000_000d).ToString("0.#", UiCulture) : probe?.Stage is "waiting_speed" or "download" ? "…" : null;
        var tone = probeQuality switch
        {
            RouteQualityGrade.Good => "good",
            RouteQualityGrade.Poor => "warn",
            _ => "normal",
        };
        var measured = probe is not null && (probe.LatencyMilliseconds is not null || probe.BytesPerSecond is > 0);
        var statusTone = connected ? "good" : failed ? "error" : startingTone(connecting, routeProbeActive, healthUnknown);
        var countryCode = destination.Code is "NL" or "DE" or "RU" or "FI" or "IT" ? destination.Code : "";
          var actionTone = connected ? "connected" : failed ? "error" :
              connecting || stopping || _vpnActionInProgress || routeProbeActive ? "connecting" : "idle";
          return new HomePresentationSnapshot(status, statusTone, message, country, city, protocol,
              countryCode, actionLabel, action, actionEnabled, actionTone,
            latencyText, tone, speedText, tone,
            probe?.Error ?? (liveMeasurement
                ? probe?.Stage is "waiting_speed" or "download" ? Copy("измеряем скорость подключения…", "measuring connection speed…")
                    : probe?.Stage is "latency" ? Copy("проверяем HTTPS через VPN…", "checking HTTPS through the VPN…")
                    : Copy("HTTPS-отклик через VPN", "HTTPS response through VPN")
                : measured ? Copy("последний замер маршрута", "last route measurement") : Copy("запустите замер подключения", "measure the connection")),
            _homeProbeCancellation is null && !_routeProbeInProgress && !_vpnActionInProgress && (!tunnelActive || connected),
            Copy("обновить замер", "refresh measurement"));

        static string startingTone(bool isConnecting, bool isProbing, bool isUnknown) =>
            isConnecting || isProbing || isUnknown ? "warn" : "normal";
    }

    private string BuildHomeRouteAccessibilityDescription()
    {
        var presentation = BuildHomePresentation();
        return string.Join(" · ", new[]
        {
            presentation.Status,
            presentation.Country,
            presentation.City,
            presentation.Protocol,
        }.Where(value => !string.IsNullOrWhiteSpace(value)));
    }

    private void UpdateHomeGlobePresentation()
    {
        var map = _routeGlobe;
        if (map is null)
            return;
        map.SelectedRoute = RouteGlobeWebView.RouteKeyFor(_selectedRoute, _routes);
        map.Language = _language;
        map.AvailableLocations = GetAvailableMapLocations();
        map.OriginConsentGranted = _mapRegionEnabled && _mapRegionConsentGranted;
        map.OriginLocation = _mapOriginLocation;
        map.TrafficActive = IsVpnDisplayConnected();
        map.ReducedMotion = _reduceMotion;
        map.DarkTheme = DeyttTheme.IsDark;
        map.EgressCountryCode = null;
        map.ActiveAutoRouteKey = _selectedRoute == "auto" && IsVpnDisplayConnected()
            ? ConfirmedHomeAutoRouteKey()
            : null;
        map.HomePresentation = BuildHomePresentation();
    }

    private string? ConfirmedHomeAutoRouteKey()
    {
        var activeTag = _vpnSnapshot.ActiveRouteTag;
        if (string.IsNullOrWhiteSpace(activeTag)) return null;
        var route = _routes.FirstOrDefault(candidate => candidate.Tag == activeTag && candidate.CountryCode != "AUTO");
        return route is null ? null : RouteGlobeWebView.RouteKeyFor(route.CountryCode);
    }

    private void HandleHomeActionRequested(string action)
    {
        switch (action)
        {
            case "home-connect":
            case "home-disconnect":
                HandleHomePrimaryAction();
                break;
            case "home-cancel":
                _ = CancelVpnConnectionAsync();
                break;
            case "home-routes":
                ShowTab(MainTab.Routes);
                break;
            case "home-profile":
                ShowTab(MainTab.Profile);
                break;
            case "home-settings":
                ShowTab(MainTab.Settings);
                break;
            case "home-measure":
                if (IsVpnDisplayConnected()) RefreshHomeConnectionMeasurement(true);
                else if (_routes.FirstOrDefault(route => route.Id == _selectedRoute) is { Id: not "auto" } route)
                    _ = ProbeRoutesAsync([route.Tag]);
                else ShowTab(MainTab.Routes);
                break;
        }
    }

    private void HandleHomePrimaryAction()
    {
        if (_quitInProgress || _exitAuthorized) return;
        var restoring = !_sessionRestoreComplete && !_qaFixture;
        var tunnelActive = IsVpnTunnelActive(_vpnSnapshot.State);
        var connected = IsVpnDisplayConnected();
        var healthUnknown = tunnelActive && !connected;
        var connecting = _vpnSnapshot.State is "starting" or "checking";
        var canCancel = connecting && !_vpnCancelRequested && !_routeProbeInProgress;
        if (_qaFixture || restoring)
            return;
        if (canCancel)
        {
            _ = CancelVpnConnectionAsync();
            return;
        }
        if (_vpnActionInProgress || _routeProbeInProgress || _vpnCancelInProgress)
            return;
        if (!tunnelActive && !_sessionRestoreComplete)
            return;
        if (!tunnelActive && _sessionToken is null)
            ShowSignInDialog();
        else if (!tunnelActive && (_keysSnapshot?.HappAvailable != true || string.IsNullOrWhiteSpace(_keysSnapshot.ProfileJson)))
            ShowTab(MainTab.Profile);
        else if (!tunnelActive && !_vpnServiceAvailable)
            ShowTab(MainTab.Settings);
        else
            _ = ToggleVpnAsync();
    }

    private string LocalizeHomeDiagnostic(string detail) => _language == "en"
        ? detail.Replace("Причина завершения ядра не получена.", "The VPN engine exited without a reported reason.", StringComparison.Ordinal)
            .Replace("Код завершения:", "Exit code:", StringComparison.Ordinal)
            .Replace("Ядро:", "Engine:", StringComparison.Ordinal)
        : detail;

    private Control BuildHomeRouteDiagnostic(string? routeTag)
    {
        var result = routeTag is not null ? _routeProbeResults.GetValueOrDefault(routeTag) : null;
        var latency = result?.LatencyMilliseconds is { } ping ? $"{ping} {Copy("мс", "ms")}" : Copy("не измерено", "not measured");
        var speed = result?.BytesPerSecond is { } bytesPerSecond && bytesPerSecond > 0
            ? (bytesPerSecond * 8d / 1_000_000d).ToString("0.#", UiCulture) + " " + LocalizedSpeedUnit(_language)
            : Copy("не измерено", "not measured");
        var routeProbeActive = routeTag is not null && _activeRouteProbeTags.Contains(routeTag) &&
            result?.Stage is "latency" or "retry" or "waiting_speed" or "download";
        var measurements = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,*"),
            ColumnSpacing = 16,
        };
        var latencyMeasurement = HomeMeasurement(Copy("ОТКЛИК", "RESPONSE"), latency,
            routeProbeActive && result?.LatencyMilliseconds is null);
        var latencyHelpText = Copy(
            "Время до первого ответа по HTTPS через выбранный маршрут. Это не ICMP-пинг.",
            "Time to the first HTTPS response through the selected route. This is not an ICMP ping.");
        var latencyMeasurementLabel = latencyMeasurement.Children.OfType<TextBlock>().First();
        ToolTip.SetTip(latencyMeasurementLabel, latencyHelpText);
        AutomationProperties.SetHelpText(latencyMeasurementLabel, latencyHelpText);
        measurements.Children.Add(latencyMeasurement);
        var speedMeasurement = HomeMeasurement(Copy("СКОРОСТЬ", "SPEED"), speed,
            routeProbeActive && result?.BytesPerSecond is not > 0);
        Grid.SetColumn(speedMeasurement, 1);
        measurements.Children.Add(speedMeasurement);

        var details = new StackPanel { Spacing = 8 };
        AutomationProperties.SetAutomationId(measurements, "HomeRouteMeasurements");
        details.Children.Add(measurements);
        if (result is not null && result.Stage is not ("latency" or "retry" or "waiting_speed" or "download"))
            details.Children.Add(DeyttTheme.TextBlock(
                Copy("Последний замер маршрута", "Last route measurement"),
                10, DeyttTheme.Muted));
        return details;
    }

    private StackPanel HomeMeasurement(string label, string value, bool active)
    {
        var cell = new StackPanel { Spacing = 4 };
        cell.Children.Add(DeyttTheme.TextBlock(label, 9, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        if (active && value.StartsWith('—'))
        {
            var dots = DeyttTheme.TextBlock(_reduceMotion ? "···" : "·", 14,
                DeyttTheme.Sky, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false);
            dots.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left;
            cell.Children.Add(dots);
            if (!_reduceMotion)
            {
                var step = 0;
                var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(340) };
                timer.Tick += (_, _) => dots.Text = new string('·', 1 + (++step % 3));
                dots.AttachedToVisualTree += (_, _) => timer.Start();
                dots.DetachedFromVisualTree += (_, _) => timer.Stop();
            }
        }
        else
            cell.Children.Add(DeyttTheme.TextBlock(value, 14, DeyttTheme.Text,
                FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        return cell;
    }
}
