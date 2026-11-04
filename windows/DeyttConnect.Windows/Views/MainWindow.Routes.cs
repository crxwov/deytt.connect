using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Controls;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private Control BuildRoutesPage()
    {
        var page = NewPage(Copy("ВЫХОДЫ · ВЫБОР · ДИАГНОСТИКА", "EXITS · SELECTION · DIAGNOSTICS"), Copy("Маршруты", "Routes"));
        page.Children.Add(DeyttTheme.TextBlock(
            HasProbeSpeedToken()
                ? Copy("Выберите выход. Проверка покажет пинг и скорость каждого маршрута.",
                    "Choose an exit. Diagnostics show each route's latency and speed.")
                : Copy("Пинг можно проверить без входа; для замера скорости войдите через Telegram.",
                    "Pings are available without sign-in; sign in through Telegram to measure speed."),
            13, DeyttTheme.Muted));
        page.Children.Add(DeyttTheme.Spacer(_compactLayout ? 14 : 24));

        var columns = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*"),
            RowDefinitions = new RowDefinitions("Auto"),
        };
        var routeColumn = new StackPanel
        {
            Spacing = 0,
            Margin = new Thickness(0),
        };

        var autoRoute = _routes.FirstOrDefault(route => route.Id == "auto");
        var chainRoute = _routes.FirstOrDefault(route => route.Id == "ru-de");
        if (autoRoute is not null || chainRoute is not null)
        {
            AddSection(routeColumn, Copy("БЫСТРЫЙ ВЫБОР", "QUICK SELECT"));
            var quick = new StackPanel { Spacing = 0 };
            if (autoRoute is not null)
                quick.Children.Add(QuickOption(autoRoute, "✦", Copy("Автоподбор", "Auto-select"),
                    Copy("Подберём быстрый доступный узел", "Choose a fast available node")));
            if (autoRoute is not null && chainRoute is not null)
                quick.Children.Add(new Border { Height = 1, Margin = new Thickness(68, 0, 10, 0),
                    Background = DeyttTheme.Brush(DeyttTheme.Line) });
            if (chainRoute is not null)
                quick.Children.Add(QuickOption(chainRoute, "🇷🇺🇩🇪",
                    Copy("LTE + белые списки", "LTE + whitelist"),
                    Copy("Россия → Германия · двойной маршрут", "Russia → Germany · double route")));
            routeColumn.Children.Add(quick);
        }
        AddSection(routeColumn, Copy("СТРАНЫ", "COUNTRIES"));

        if (_routes.Count == 0 && _sessionToken is null)
        {
            routeColumn.Children.Add(DeyttTheme.Card(new StackPanel
            {
                Spacing = 10,
                Children =
                {
                    DeyttTheme.TextBlock(Copy("Войди, чтобы увидеть маршруты", "Sign in to see your routes"),
                        18, DeyttTheme.Text, FontWeight.SemiBold),
                    DeyttTheme.TextBlock(Copy("Список стран и протоколов загрузится из подписки.",
                        "Countries and protocols will load from your subscription."), 13, DeyttTheme.Muted),
                    DeyttTheme.PrimaryButton(Copy("Подключить Telegram", "Connect Telegram"), ShowSignInDialog),
                },
            }, DeyttTheme.Surface2, DeyttTheme.Line, 20, new Thickness(18)));
        }
        else if (_routes.Count == 0)
        {
            var loadingRoutes = _profileRefreshInProgress ||
                (_keysSnapshot is null && _profileLoadIssue is null);
            var subscriptionInactive = _account?.Subscription is { Active: false } &&
                _keysSnapshot is not null;
            var routesTitle = loadingRoutes
                ? Copy("Загружаем маршруты…", "Loading routes…")
                : subscriptionInactive
                    ? Copy("Нет активной подписки", "No active subscription")
                    : _keysSnapshot is null && _profileLoadIssue is not null
                        ? Copy("Не удалось загрузить маршруты", "Could not load routes")
                        : Copy("Маршруты пока недоступны", "Routes are unavailable");
            var routesDetail = loadingRoutes
                ? Copy("Проверяем подписку и список выходов.", "Checking your subscription and exits.")
                : subscriptionInactive
                    ? Copy("Выберите тариф, затем обновите профиль.", "Choose a plan, then refresh your profile.")
                    : _keysSnapshot is null && _profileLoadIssue is not null
                        ? _profileLoadIssue
                        : Copy("Повторите загрузку профиля, чтобы получить список выходов.",
                            "Reload your profile to get the list of exits.");
            var refreshRoutes = DeyttTheme.PrimaryButton(Copy("Обновить профиль", "Refresh profile"),
                () => _ = RefreshSignedInAccountAsync());
            refreshRoutes.IsEnabled = !loadingRoutes;
            routeColumn.Children.Add(DeyttTheme.Card(new StackPanel
            {
                Spacing = 10,
                Children =
                {
                    DeyttTheme.TextBlock(routesTitle,
                        18, DeyttTheme.Text, FontWeight.SemiBold),
                    DeyttTheme.TextBlock(routesDetail, 13, DeyttTheme.Muted),
                    refreshRoutes,
                },
            }, DeyttTheme.Surface2, DeyttTheme.Line, 20, new Thickness(18)));
        }
        else
        {
            var countryList = new StackPanel { Spacing = _compactLayout ? 6 : 8 };
            var countryOrder = new[] { "NL", "DE", "RU", "FI", "IT", "AWG_UNKNOWN" };
            var countries = _routes.Where(route => route.CountryCode is "NL" or "RU" or "DE" or "FI" or "IT" or "AWG_UNKNOWN")
                .GroupBy(route => route.CountryCode)
                .OrderBy(country => Array.IndexOf(countryOrder, country.Key));
            foreach (var country in countries)
            {
                var countryRoutes = country.DistinctBy(route => route.Id)
                    .OrderBy(RouteProtocolSortOrder).ToArray();
                var first = countryRoutes[0];
                var quality = CountryRouteQuality(countryRoutes);
                var expanded = _expandedRouteCountries.Contains(country.Key);
                var selectedCountry = countryRoutes.Any(route => route.Id == _selectedRoute);
                var summary = string.Join(" · ", countryRoutes
                    .Select(route => IsAwgProtocol(route.Protocol) ? AwgProtocolTitle(route.Protocol) : route.ProtocolName)
                    .Distinct(StringComparer.Ordinal));
                var headerLabels = new StackPanel { Spacing = 3 };
                headerLabels.Children.Add(DeyttTheme.TextBlock(
                    RouteCountryName(first), 17,
                    selectedCountry ? DeyttTheme.Sky : DeyttTheme.Text, FontWeight.SemiBold));
                var protocolSummary = DeyttTheme.TextBlock(summary, 11, DeyttTheme.Muted, wrap: false);
                protocolSummary.TextTrimming = TextTrimming.CharacterEllipsis;
                ToolTip.SetTip(protocolSummary, summary);
                headerLabels.Children.Add(protocolSummary);
                if (!expanded && RouteCountryProbeStatus(country.Key) is { } probeStatus)
                    headerLabels.Children.Add(DeyttTheme.TextBlock(probeStatus, 10,
                        _routeCountryProbeStates.GetValueOrDefault(country.Key) == RouteCountryProbeState.Failed
                            ? DeyttTheme.Coral : DeyttTheme.Muted));
                var header = new Grid
                {
                    ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"),
                    ColumnSpacing = 12,
                    MinHeight = 50,
                };
                var flag = RouteFlagVisual.Create(country.Key, 36, 24);
                header.Children.Add(flag);
                headerLabels.Margin = new Thickness(0, 0, 8, 0);
                headerLabels.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                Grid.SetColumn(headerLabels, 1);
                header.Children.Add(headerLabels);
                var disclosure = DeyttTheme.TextBlock(expanded ? "⌃" : "⌄", 17, DeyttTheme.Sky,
                    FontWeight.SemiBold, wrap: false);
                disclosure.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                Grid.SetColumn(disclosure, 2);
                header.Children.Add(disclosure);
                var headerButton = DeyttTheme.Action(header, () =>
                {
                    var expandedNow = _expandedRouteCountries.Add(country.Key);
                    if (expandedNow)
                    {
                        var routeTags = countryRoutes.Select(route => route.Tag)
                            .Distinct(StringComparer.Ordinal).ToArray();
                        if (!_qaFixture)
                            QueueRouteCountryProbe(country.Key, routeTags);
                    }
                    else
                    {
                        _expandedRouteCountries.Remove(country.Key);
                        CollapseRouteCountryProbe(country.Key);
                    }
                    RenderActiveTabPreservingScroll();
                });
                headerButton.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
                headerButton.Padding = new Thickness(2, 2);
                AutomationProperties.SetAutomationId(headerButton, $"RouteCountry-{country.Key}");
                var countryContents = new StackPanel { Spacing = 0, Children = { headerButton } };
                if (expanded)
                {
                    countryContents.Children.Add(DeyttTheme.Hairline());
                    var options = new StackPanel { Spacing = 4, Margin = new Thickness(0, 7, 0, 2) };
                    foreach (var route in countryRoutes)
                        options.Children.Add(RouteOption(route, quality));
                    countryContents.Children.Add(options);

                    if (RouteCountryProbeStatus(country.Key) is { } expandedProbeStatus)
                    {
                        var statusColor = _routeCountryProbeStates.GetValueOrDefault(country.Key) ==
                            RouteCountryProbeState.Failed ? DeyttTheme.Coral : DeyttTheme.Muted;
                        var expandedStatus = DeyttTheme.TextBlock(expandedProbeStatus, 11, statusColor);
                        expandedStatus.Margin = new Thickness(4, 5, 4, 0);
                        countryContents.Children.Add(expandedStatus);
                    }

                    var countryRouteTags = countryRoutes.Select(route => route.Tag)
                        .Distinct(StringComparer.Ordinal).ToArray();
                    var countryProbeActive = _routeProbeInProgress && _activeRouteProbeCountry == country.Key;
                    var countryProbeQueued = _pendingRouteProbeNodes.ContainsKey(country.Key);
                    var probeState = _routeCountryProbeStates.GetValueOrDefault(country.Key);
                    var countryProbeLabel = countryProbeActive
                        ? _routeProbeCancelRequested
                            ? Copy("Останавливаем…", "Stopping…")
                            : Copy("Остановить замеры", "Stop measurements")
                        : countryProbeQueued
                            ? Copy("Убрать из очереди", "Remove from queue")
                            : probeState is RouteCountryProbeState.Cancelled or RouteCountryProbeState.Failed
                                ? Copy("Повторить замеры", "Retry measurements")
                                : Copy("Обновить замеры", "Refresh measurements");
                    var countryProbeAction = DeyttTheme.Action(DeyttTheme.TextBlock(countryProbeLabel,
                        12, DeyttTheme.Sky, FontWeight.SemiBold, wrap: false), () =>
                    {
                        if (_qaFixture)
                            return;
                        if (countryProbeActive)
                        {
                            if (!_routeProbeCancelRequested)
                                _ = CancelRouteProbeAsync();
                        }
                        else if (_pendingRouteProbeNodes.Remove(country.Key, out var queuedNode))
                        {
                            _pendingRouteProbeQueue.Remove(queuedNode);
                            _routeCountryProbeStates[country.Key] = RouteCountryProbeState.Cancelled;
                            RenderActiveTabPreservingScroll();
                        }
                        else
                        {
                            _routeCountryProbeStates.Remove(country.Key);
                            QueueRouteCountryProbe(country.Key, countryRouteTags);
                        }
                    });
                    countryProbeAction.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left;
                    countryProbeAction.Padding = new Thickness(4, 8, 10, 5);
                    countryProbeAction.IsEnabled = countryProbeQueued ||
                        !countryProbeActive || !_routeProbeCancelRequested;
                    AutomationProperties.SetAutomationId(countryProbeAction,
                        $"RouteCountryRefresh-{country.Key}");
                    countryContents.Children.Add(countryProbeAction);
                }
                countryList.Children.Add(DeyttTheme.GlassCard(countryContents,
                    selectedCountry ? DeyttTheme.Surface2 : DeyttTheme.Surface,
                    selectedCountry ? DeyttTheme.Sky : DeyttTheme.Line,
                    18, new Thickness(10, 6)));
            }
            routeColumn.Children.Add(countryList);
        }

        columns.Children.Add(routeColumn);
        page.Children.Add(columns);
        return page;
    }

    private static int RouteProtocolSortOrder(WindowsRoute route) => route.Protocol switch
    {
        "LTE" => 0,
        "VLESS" => 1,
        "TROJAN" => 2,
        "HYSTERIA2" => 3,
        "TUIC" => 4,
        "AWG15" or "AWG31" => 5,
        _ => 6,
    };

    private static bool IsValidLatency(long? latencyMilliseconds) =>
        latencyMilliseconds is > 0 and <= 8_000;

    private static Control RouteProtocolVisual(string protocol, Color color)
    {
        if (protocol is "VLESS" or "TROJAN" or "HYSTERIA2" or "TUIC" || IsAwgProtocol(protocol))
            return new ProtocolIcon(protocol);

        var mark = protocol == "LTE" ? "LTE" : protocol.Length <= 3 ? protocol : protocol[..3];
        return DeyttTheme.TextBlock(mark, 8, color, FontWeight.SemiBold,
            DeyttTheme.JetBrainsMono, wrap: false);
    }

    private enum RouteQualityGrade { Unrated, Good, Medium, Poor }
    private sealed record RouteCountryQuality(string? LatencySummary,
        IReadOnlyDictionary<string, RouteQualityGrade> Grades, string? BestTag);

    private RouteCountryQuality CountryRouteQuality(IReadOnlyList<WindowsRoute> routes)
    {
        var measured = routes
            .Select(route => _routeProbeResults.GetValueOrDefault(route.Tag))
            .Where(sample => IsValidLatency(sample?.LatencyMilliseconds))
            .Select(sample => sample!)
            .ToArray();
        var grades = new Dictionary<string, RouteQualityGrade>(StringComparer.Ordinal);
        if (measured.Length == 0)
            return new RouteCountryQuality(null, grades, null);

        var fastest = measured.Min(sample => sample.LatencyMilliseconds!.Value);
        var slowest = measured.Max(sample => sample.LatencyMilliseconds!.Value);
        var firstTag = measured.MinBy(sample => sample.RouteTag, StringComparer.Ordinal)!.RouteTag;
        foreach (var sample in measured)
        {
            var latency = sample.LatencyMilliseconds!.Value;
            grades[sample.RouteTag] = fastest == slowest
                ? sample.RouteTag == firstTag ? RouteQualityGrade.Good : RouteQualityGrade.Medium
                : latency == fastest ? RouteQualityGrade.Good
                : latency == slowest ? RouteQualityGrade.Poor : RouteQualityGrade.Medium;
        }

        var complete = measured.Where(sample => sample.BytesPerSecond is > 0).ToArray();
        string? bestTag = complete.Length == 1 ? complete[0].RouteTag : null;
        if (complete.Length >= 2)
        {
            var minLatency = complete.Min(sample => sample.LatencyMilliseconds!.Value);
            var maxLatency = complete.Max(sample => sample.LatencyMilliseconds!.Value);
            var minSpeed = complete.Min(sample => sample.BytesPerSecond!.Value);
            var maxSpeed = complete.Max(sample => sample.BytesPerSecond!.Value);
            var ranked = complete.Select(sample =>
            {
                var latencyScore = minLatency == maxLatency ? 0.5 :
                    (maxLatency - sample.LatencyMilliseconds!.Value) / (double)(maxLatency - minLatency);
                var speedScore = minSpeed == maxSpeed ? 0.5 :
                    (sample.BytesPerSecond!.Value - minSpeed) / (double)(maxSpeed - minSpeed);
                return (sample.RouteTag, Score: (latencyScore + speedScore) / 2);
            }).OrderByDescending(item => item.Score)
                .ThenBy(item => item.RouteTag, StringComparer.Ordinal).ToArray();
            bestTag = ranked[0].RouteTag;
            for (var index = 0; index < ranked.Length; index++)
                grades[ranked[index].RouteTag] = index == 0 ? RouteQualityGrade.Good
                    : index / (double)ranked.Length >= 2d / 3d
                        ? RouteQualityGrade.Poor : RouteQualityGrade.Medium;
        }

        var average = (long)Math.Round(measured.Average(sample => (double)sample.LatencyMilliseconds!.Value),
            MidpointRounding.AwayFromZero);
        var summary = Copy($"Пинг · лучший {fastest} · средний {average} · худший {slowest} мс",
            $"Ping · best {fastest} · avg {average} · worst {slowest} ms");
        return new RouteCountryQuality(summary, grades, bestTag);
    }

    private Control RouteOption(WindowsRoute route, RouteCountryQuality quality)
    {
        var selected = _selectedRoute == route.Id;
        var body = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("28,2*,1,*,1,*,Auto"),
            ColumnSpacing = 11,
            MinHeight = 56,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
        };
        var protocolColor = route.Protocol switch
        {
            "VLESS" => DeyttTheme.Sky,
            "TROJAN" => DeyttTheme.Blue,
            "HYSTERIA2" => DeyttTheme.Mint,
            "TUIC" => DeyttTheme.Sky,
            "LTE" => DeyttTheme.Mint,
            "AWG15" or "AWG31" => DeyttTheme.Amber,
            _ => DeyttTheme.Muted,
        };
        var protocolMark = RouteProtocolVisual(route.Protocol, protocolColor);
        protocolMark.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        protocolMark.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        body.Children.Add(protocolMark);
        var labels = new StackPanel
        {
            Spacing = 2,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        labels.Children.Add(DeyttTheme.TextBlock(route.ProfileName ??
            (IsAwgProtocol(route.Protocol) ? AwgProtocolTitle(route.Protocol) : route.ProtocolName),
            14, DeyttTheme.Text, FontWeight.SemiBold));
        var detail = route.Protocol switch
        {
            "VLESS" or "TROJAN" => "WebSocket + TLS",
            "HYSTERIA2" => "QUIC · UDP",
            "TUIC" => "QUIC · UDP · BBR",
            "LTE" => Copy("Белые списки LTE · лимит 10 ГБ в месяц", "Whitelist LTE · 10 GB monthly limit"),
            "AWG15" or "AWG31" => AwgProtocolTitle(route.Protocol),
            _ => route.ProtocolName,
        };
        labels.Children.Add(DeyttTheme.TextBlock(detail, 10, DeyttTheme.Muted));
        var measured = _routeProbeResults.GetValueOrDefault(route.Tag);
        var probeStatus = measured is null ? null : measured.Stage switch
        {
            "latency" => Copy("Проверяем пинг", "Measuring latency"),
            "retry" => Copy("Повторяем замер", "Retrying measurement"),
            "waiting_speed" => Copy("Пинг получен · измеряем скорость", "Ping received · measuring speed"),
            "download" => FormatProbeDownloadProgress(measured),
            "error" => measured.Error ?? Copy("Не удалось проверить маршрут", "Could not check this route"),
            "cancelled" => Copy("Замер остановлен", "Measurement stopped"),
            "complete" when !IsValidLatency(measured.LatencyMilliseconds) =>
                measured.Error ?? Copy("Нет ответа", "No response"),
            "complete" when quality.BestTag == route.Tag => Copy("Лучший результат", "Best result"),
            _ => null,
        };
        if (probeStatus is not null)
        {
            var status = DeyttTheme.TextBlock(probeStatus, 9,
                measured?.Stage is "error" or "cancelled" ? DeyttTheme.Coral : DeyttTheme.Muted);
            labels.Children.Add(status);
        }
        Grid.SetColumn(labels, 1);
        body.Children.Add(labels);

        var latencyText = IsValidLatency(measured?.LatencyMilliseconds)
            ? $"{measured!.LatencyMilliseconds} ms" : "— ms";
        var speedText = measured?.BytesPerSecond is { } bytesPerSecond && bytesPerSecond > 0
            ? $"{(bytesPerSecond * 8d / 1_000_000d).ToString("0.#", System.Globalization.CultureInfo.InvariantCulture)} mbps"
            : "— mbps";
        var firstDivider = new Border
        {
            Width = 1,
            Height = 30,
            Background = DeyttTheme.Brush(DeyttTheme.Line),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        Grid.SetColumn(firstDivider, 2);
        body.Children.Add(firstDivider);

        var latencyMetric = RouteMeasurement(Copy("ПИНГ", "PING"), latencyText,
            quality.Grades.GetValueOrDefault(route.Tag) switch
            {
                RouteQualityGrade.Good => DeyttTheme.Mint,
                RouteQualityGrade.Medium => DeyttTheme.Amber,
                RouteQualityGrade.Poor => DeyttTheme.Coral,
                _ => DeyttTheme.Text,
            }, measured?.Stage is "latency" or "retry", _reduceMotion);
        Grid.SetColumn(latencyMetric, 3);
        body.Children.Add(latencyMetric);

        var secondDivider = new Border
        {
            Width = 1,
            Height = 30,
            Background = DeyttTheme.Brush(DeyttTheme.Line),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        Grid.SetColumn(secondDivider, 4);
        body.Children.Add(secondDivider);

        var speedMetric = RouteMeasurement(Copy("СКОРОСТЬ", "SPEED"), speedText,
            quality.BestTag == route.Tag ? DeyttTheme.Mint : DeyttTheme.Text,
              measured?.Stage is "latency" or "retry" or "waiting_speed" or "download", _reduceMotion);
        Grid.SetColumn(speedMetric, 5);
        body.Children.Add(speedMetric);

        Control action = selected
            ? DeyttTheme.TextBlock("✓", 16, DeyttTheme.Mint, FontWeight.SemiBold, wrap: false)
            : DeyttTheme.TextBlock("›", 16, DeyttTheme.Muted, FontWeight.Medium, wrap: false);
        if (selected)
            action = new Border
            {
                Width = 26,
                Height = 26,
                Background = DeyttTheme.Brush(DeyttTheme.MintSurface),
                CornerRadius = new CornerRadius(9),
                Child = action,
            };
        Grid.SetColumn(action, 6);
        action.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        body.Children.Add(action);
        var button = DeyttTheme.Action(body, () =>
        {
            if (IsAwgProtocol(route.Protocol))
                _ = ShowAwgRouteChoicesAsync(route);
            else
                SelectRoute(route.Id);
        });
        button.Padding = new Thickness(0);
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        AutomationProperties.SetAutomationId(button, $"RouteOption-{route.Id}");
        AutomationProperties.SetName(button, $"{route.ProfileName ?? route.ProtocolName} · {route.CountryName}");
        return new Border
        {
            Background = DeyttTheme.Brush(selected ? DeyttTheme.Selected : Colors.Transparent),
            BorderBrush = DeyttTheme.Brush(selected ? DeyttTheme.SelectedLine : Colors.Transparent),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(12, 8),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            Child = button,
        };
    }

    private static StackPanel RouteMeasurement(string label, string value, Color valueColor,
        bool active, bool reducedMotion)
    {
        var cell = new StackPanel
        {
            Spacing = 2,
            MinWidth = 74,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        cell.Children.Add(DeyttTheme.TextBlock(label, 9, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        if (active && value.StartsWith('—'))
        {
            var dots = DeyttTheme.TextBlock(reducedMotion ? "···" : "·", 14,
                DeyttTheme.Sky, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false);
            dots.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left;
            cell.Children.Add(dots);
            if (!reducedMotion)
            {
                var step = 0;
                var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(340) };
                timer.Tick += (_, _) => dots.Text = new string('·', 1 + (++step % 3));
                dots.AttachedToVisualTree += (_, _) => timer.Start();
                dots.DetachedFromVisualTree += (_, _) => timer.Stop();
            }
        }
        else
            cell.Children.Add(DeyttTheme.TextBlock(value, 14, valueColor,
                FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        return cell;
    }

    private async Task ShowAwgRouteChoicesAsync(WindowsRoute route)
    {
        var choice = await ChooseOptionAsync(route.ProfileName ?? AwgProtocolTitle(route.Protocol),
            Copy("Выберите маршрут или проверьте этот выход отдельно.",
                "Select this route or measure this exit separately."),
            [
                (Copy("Выбрать маршрут", "Select route"),
                    Copy("Использовать для подключения", "Use for the next connection"), "select"),
                (Copy("Подключить и проверить", "Connect and measure"),
                    Copy("Временная проверка без смены выбранного маршрута",
                        "Temporary measurement without changing your selected route"), "measure"),
            ]);
        if (choice == "select")
            SelectRoute(route.Id);
        else if (choice == "measure")
        {
            if (_routeProbeInProgress || _vpnActionInProgress ||
                WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State))
            {
                ShowInfoDialog(Copy("Проверка недоступна", "Measurement unavailable"),
                    Copy("Дождитесь окончания текущей проверки или отключите VPN.",
                        "Wait for the current measurement to finish or disconnect the VPN."));
                return;
            }
            await ProbeRoutesAsync([route.Tag]);
        }
    }

    private Control QuickOption(WindowsRoute route, string glyph, string title, string subtitle)
    {
        var selected = _selectedRoute == route.Id;
        var body = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), MinHeight = 60 };
        Control icon;
        if (route.Protocol == "LTE")
        {
            icon = RouteFlagVisual.Create("EU", 48, 30);
        }
        else
        {
            var glyphIcon = DeyttTheme.TextBlock(glyph, 23, DeyttTheme.Sky, wrap: false);
            glyphIcon.Width = 36;
            glyphIcon.TextAlignment = TextAlignment.Center;
            icon = glyphIcon;
        }
        body.Children.Add(icon);
        var labels = new StackPanel { Spacing = 3, Margin = new Thickness(12, 0, 8, 0), VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(title, 16, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 11, DeyttTheme.Muted));
        Grid.SetColumn(labels, 1);
        body.Children.Add(labels);
        var action = DeyttTheme.TextBlock(selected ? Copy("ВЫБРАН", "SELECTED") : Copy("выбрать", "select"),
            12, selected ? DeyttTheme.Mint : DeyttTheme.Muted,
            FontWeight.SemiBold, wrap: false);
        Grid.SetColumn(action, 2);
        body.Children.Add(action);
        var button = DeyttTheme.Action(body, () => SelectRoute(route.Id));
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.Padding = new Thickness(6, 6);
        return button;
    }

    private string FormatProbeDownloadProgress(WindowsRouteProbeResult probe)
    {
        var received = probe.BytesReceived ?? 0;
        var total = probe.TotalBytes ?? 0;
        var progress = total > 0
            ? $"{received / (1024d * 1024d):0.#}/{total / (1024d * 1024d):0.#} mb"
            : $"{received / (1024d * 1024d):0.#} mb";
        return Copy($"Скорость · получено {progress}", $"Speed · received {progress}");
    }
}
