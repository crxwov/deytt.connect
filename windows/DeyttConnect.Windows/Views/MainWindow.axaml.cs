using System.Diagnostics;
using System.Security.Cryptography;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Threading;
using DeyttConnect.Windows.Controls;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow : Window
{
    private enum MainTab { Home, Routes, Profile, Settings }

    private string _language = "ru";
    private string _selectedRoute = "auto";
    private string _probeMethod = "HEAD";
    private bool _mapRegionEnabled = true;
    private bool _reduceMotion;
    private readonly TelegramApiClient _telegramApi = new();
    private readonly WindowsTunnelClient _tunnelClient = new();
    private readonly DispatcherTimer _vpnStatusTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private string? _sessionToken;
    private TelegramAccount? _account;
    private TelegramKeysSnapshot? _keysSnapshot;
    private IReadOnlyList<WindowsRoute> _routes = [];
    private string? _profileLoadIssue;
    private string? _preferencesIssue;
    private MainTab _activeTab = MainTab.Home;
    private WindowsTunnelSnapshot _vpnSnapshot = new("disconnected", "VPN выключен");
    private IReadOnlyDictionary<string, WindowsRouteProbeResult> _routeProbeResults =
        new Dictionary<string, WindowsRouteProbeResult>(StringComparer.Ordinal);
    private bool _vpnServiceAvailable;
    private bool _vpnActionInProgress;
    private bool _routeProbeInProgress;
    private bool _routeProbeCancelRequested;

    public MainWindow()
    {
        InitializeComponent();
        if (OperatingSystem.IsWindows())
        {
            var preferences = WindowsPreferencesStore.Load();
            _language = preferences.Language;
            _selectedRoute = preferences.SelectedRoute;
            _probeMethod = preferences.ProbeMethod;
            _mapRegionEnabled = preferences.MapRegionEnabled;
            _reduceMotion = preferences.ReduceMotion;
        }
        ConfigureNavigation();
        ShowTab(MainTab.Home);
        Opened += (_, _) => _ = RestoreSessionAsync();
        Opened += (_, _) => _ = RefreshVpnStatusAsync();
        _vpnStatusTimer.Tick += (_, _) => _ = RefreshVpnStatusAsync();
        Closed += (_, _) => _vpnStatusTimer.Stop();
        if (OperatingSystem.IsWindows())
            _vpnStatusTimer.Start();
    }

    private void ConfigureNavigation()
    {
        HomeNav.Click += (_, _) => ShowTab(MainTab.Home);
        RoutesNav.Click += (_, _) => ShowTab(MainTab.Routes);
        ProfileNav.Click += (_, _) => ShowTab(MainTab.Profile);
        SettingsNav.Click += (_, _) => ShowTab(MainTab.Settings);
    }

    private void ShowTab(MainTab tab)
    {
        _activeTab = tab;
        PageHost.Children.Clear();
        PageHost.Children.Add(tab switch
        {
            MainTab.Home => BuildHomePage(),
            MainTab.Routes => BuildRoutesPage(),
            MainTab.Profile => BuildProfilePage(),
            MainTab.Settings => BuildSettingsPage(),
            _ => BuildHomePage(),
        });
        SetNavigation(HomeNav, "⌂", Copy("Главная", "Home"), tab == MainTab.Home);
        SetNavigation(RoutesNav, "⌖", Copy("Маршруты", "Routes"), tab == MainTab.Routes);
        SetNavigation(ProfileNav, "♙", Copy("Профиль", "Profile"), tab == MainTab.Profile);
        SetNavigation(SettingsNav, "⚙", Copy("Настройки", "Settings"), tab == MainTab.Settings);
        PageScroll.Offset = new Vector(0, 0);
    }

    private void SetNavigation(Button button, string icon, string label, bool selected)
    {
        var stack = new StackPanel
        {
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Spacing = 1,
            Children =
            {
                DeyttTheme.TextBlock(icon, 22, selected ? DeyttTheme.Sky : DeyttTheme.Muted,
                    FontWeight.SemiBold, DeyttTheme.InterTight, wrap: false),
                DeyttTheme.TextBlock(label, 10, selected ? DeyttTheme.Text : DeyttTheme.Muted,
                    selected ? FontWeight.SemiBold : FontWeight.Normal, DeyttTheme.InterTight, wrap: false),
            },
        };

        button.Content = new Border
        {
            Background = DeyttTheme.Brush(selected ? DeyttTheme.Selected : Colors.Transparent),
            BorderBrush = DeyttTheme.Brush(selected ? DeyttTheme.SelectedLine : Colors.Transparent),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(18),
            Padding = new Thickness(12, 5),
            Child = stack,
        };
    }

    private Control BuildHomePage()
    {
        var page = new StackPanel { Spacing = 0 };
        page.Children.Add(BuildBrandHeader());
        page.Children.Add(DeyttTheme.Spacer(14));
        page.Children.Add(BuildMapCard());
        page.Children.Add(DeyttTheme.Spacer(12));
        page.Children.Add(DeyttTheme.Action(
            DeyttTheme.TextBlock(RouteTitle(), 21, DeyttTheme.Text, FontWeight.Bold, wrap: false),
            () => ShowTab(MainTab.Routes)));
        page.Children.Add(DeyttTheme.Spacer(8));
        page.Children.Add(BuildRouteFlow());
        page.Children.Add(DeyttTheme.Spacer(10));
        page.Children.Add(BuildQualityStrip());
        page.Children.Add(DeyttTheme.Spacer(16));
        page.Children.Add(BuildConnectionCard());
        page.Children.Add(DeyttTheme.Spacer(18));
        page.Children.Add(DeyttTheme.TextBlock(
            Copy("Войдите через Telegram, чтобы загрузить подписку и подключиться.",
                 "Sign in with Telegram to load your subscription and connect."),
            12, DeyttTheme.Muted));
        return page;
    }

    private Control BuildBrandHeader()
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        grid.Children.Add(new Border
        {
            Width = 36,
            Height = 36,
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(20),
            Child = DeyttTheme.TextBlock("•", 19, DeyttTheme.Sky, FontWeight.Bold, wrap: false),
        });

        var account = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("Войти через Telegram", "Sign in with Telegram"), 15,
                DeyttTheme.Text, FontWeight.SemiBold, wrap: false),
            () => ShowTab(MainTab.Profile));
        account.Margin = new Thickness(10, 0, 0, 0);
        Grid.SetColumn(account, 1);
        grid.Children.Add(account);

        var brand = DeyttTheme.TextBlock("deytt./connect", 10, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false);
        Grid.SetColumn(brand, 2);
        grid.Children.Add(brand);
        return grid;
    }

    private Control BuildMapCard()
    {
        var contents = new Grid { RowDefinitions = new RowDefinitions("Auto,*,Auto") };
        contents.Children.Add(DeyttTheme.TextBlock(Copy("КАРТА МАРШРУТА", "ROUTE MAP"), 8,
            DeyttTheme.Muted, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        var map = new RouteMapIllustration { MinHeight = 180 };
        Grid.SetRow(map, 1);
        contents.Children.Add(map);
        var hint = DeyttTheme.TextBlock(Copy("Устройство → выбранный узел", "Device → selected node"), 9,
            DeyttTheme.Muted, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false);
        hint.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        Grid.SetRow(hint, 2);
        contents.Children.Add(hint);

        var action = DeyttTheme.Action(contents, () => ShowTab(MainTab.Routes));
        action.Height = 238;
        return new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.MapSurface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(24),
            Padding = new Thickness(14, 12),
            Child = action,
        };
    }

    private Control BuildRouteFlow()
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto,*,Auto,*") };
        grid.Children.Add(RouteNode("⌂", Copy("Устройство", "Device"), Copy("примерно по IP", "approx. by IP")));
        var firstArrow = DeyttTheme.TextBlock("→", 16, DeyttTheme.Sky, FontWeight.SemiBold, wrap: false);
        firstArrow.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        Grid.SetColumn(firstArrow, 1);
        grid.Children.Add(firstArrow);
        var relay = RouteNode("◎", Copy("Маршрут", "Route"), Copy("DEYTT", "DEYTT"));
        Grid.SetColumn(relay, 2);
        grid.Children.Add(relay);
        var secondArrow = DeyttTheme.TextBlock("→", 16, DeyttTheme.Sky, FontWeight.SemiBold, wrap: false);
        secondArrow.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        Grid.SetColumn(secondArrow, 3);
        grid.Children.Add(secondArrow);
        var destination = RouteNode("◎", RouteTitle(), Copy("после входа", "after sign-in"));
        Grid.SetColumn(destination, 4);
        grid.Children.Add(destination);
        return DeyttTheme.Card(grid, DeyttTheme.Surface, DeyttTheme.Line, 19, new Thickness(12, 13));
    }

    private Control RouteNode(string icon, string title, string note)
    {
        var stack = new StackPanel { HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center, Spacing = 3 };
        stack.Children.Add(DeyttTheme.TextBlock(icon, 19, DeyttTheme.Sky, FontWeight.SemiBold,
            DeyttTheme.JetBrainsMono, wrap: false));
        var titleText = DeyttTheme.TextBlock(title, 11, DeyttTheme.Text, FontWeight.Bold, wrap: false);
        titleText.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        stack.Children.Add(titleText);

        var noteText = DeyttTheme.TextBlock(note, 8, DeyttTheme.Muted, FontWeight.Normal, wrap: false);
        noteText.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        stack.Children.Add(noteText);
        return stack;
    }

    private Control BuildQualityStrip()
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto,*") };
        var selectedTag = _routes.FirstOrDefault(route => route.Id == _selectedRoute)?.Tag;
        var quality = selectedTag is not null && _routeProbeResults.TryGetValue(selectedTag, out var sample)
            ? sample
            : null;
        var latency = quality?.LatencyMilliseconds is { } latencyMs ? latencyMs.ToString() : "—";
        grid.Children.Add(Metric(Copy("ПИНГ", "PING"), latency, "ms"));
        var line = new Border { Width = 1, Height = 30, Background = DeyttTheme.Brush(DeyttTheme.Line), VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        Grid.SetColumn(line, 1);
        grid.Children.Add(line);
        var speedValue = quality?.BytesPerSecond is { } bytesPerSecond
            ? (bytesPerSecond * 8d / 1_000_000d).ToString("0.#", System.Globalization.CultureInfo.InvariantCulture)
            : "—";
        var speed = Metric(Copy("СКОРОСТЬ", "SPEED"), speedValue, "Mbps");
        Grid.SetColumn(speed, 2);
        grid.Children.Add(speed);
        return DeyttTheme.Card(grid, DeyttTheme.Surface, DeyttTheme.Line, 16, new Thickness(16, 11));
    }

    private Control Metric(string label, string value, string unit)
    {
        var stack = new StackPanel { Spacing = 3 };
        stack.Children.Add(DeyttTheme.TextBlock(label, 8, DeyttTheme.Muted, FontWeight.SemiBold,
            DeyttTheme.JetBrainsMono, wrap: false));
        stack.Children.Add(DeyttTheme.TextBlock($"{value} {unit}", 14, DeyttTheme.Muted,
            FontWeight.SemiBold, wrap: false));
        return stack;
    }

    private Control BuildConnectionCard()
    {
        var signedIn = _sessionToken is not null;
        var subscriptionReady = _keysSnapshot?.HappAvailable == true;
        var profileReady = !string.IsNullOrWhiteSpace(_keysSnapshot?.ProfileJson);
        var connected = _vpnSnapshot.State == "connected";
        var starting = _vpnSnapshot.State is "starting" or "checking" || _vpnActionInProgress || _routeProbeInProgress;
        var isError = _vpnSnapshot.State == "error";
        var contents = new StackPanel { Spacing = 0 };
        var status = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        var statusColor = connected ? DeyttTheme.Mint : starting ? DeyttTheme.Amber :
            isError ? DeyttTheme.Coral : DeyttTheme.Muted;
        status.Children.Add(new Ellipse
        {
            Width = 9,
            Height = 9,
            Fill = DeyttTheme.Brush(statusColor),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 10, 0),
        });
        var text = new StackPanel { Spacing = 4 };
        var stateTitle = connected
            ? Copy("VPN подключён", "VPN connected")
            : starting
                ? _routeProbeInProgress
                    ? Copy("Проверяем маршруты…", "Checking routes…")
                    : _vpnSnapshot.State == "checking"
                        ? Copy("Проверяем VPN-трафик…", "Verifying VPN traffic…")
                        : Copy("Подключаем VPN…", "Connecting VPN…")
                : isError
                    ? Copy("VPN не подключён", "VPN disconnected")
                    : Copy("VPN выключен", "VPN off");
        text.Children.Add(DeyttTheme.TextBlock(stateTitle, 24,
            connected ? DeyttTheme.Mint : isError ? DeyttTheme.Coral : DeyttTheme.Text,
            FontWeight.SemiBold));
        var connectionDetail = connected
            ? $"{RouteTitleForTag(_vpnSnapshot.RouteTag)} · {Copy("проверка трафика пройдена", "traffic verified")}"
            : starting
                ? _routeProbeInProgress
                    ? Copy("Проверяем HTTPS и скорость каждого выхода", "Measuring HTTPS and speed for each exit")
                    : Copy("Подождите несколько секунд", "This may take a few seconds")
                : isError
                    ? _vpnSnapshot.Detail
                    : signedIn && !_vpnServiceAvailable
                        ? Copy("Установите службу VPN в настройках приложения", "Install the VPN service in app settings")
                        : signedIn && subscriptionReady && profileReady
                            ? Copy("Подписка готова · выберите маршрут и подключитесь", "Subscription ready · choose a route and connect")
                            : signedIn
                                ? _profileLoadIssue ?? Copy("Нет активной подписки", "No active subscription")
                                : Copy("Войдите через Telegram, чтобы загрузить подписку", "Sign in with Telegram to load your subscription");
        text.Children.Add(DeyttTheme.TextBlock(connectionDetail, 12, DeyttTheme.Muted));
        Grid.SetColumn(text, 1);
        status.Children.Add(text);
        contents.Children.Add(status);
        var primaryLabel = connected
            ? Copy("Отключить VPN", "Disconnect VPN")
            : starting
                ? _routeProbeInProgress
                    ? Copy("Идёт диагностика…", "Diagnostics running…")
                    : Copy("Подключение…", "Connecting…")
                : !signedIn
                    ? Copy("Войти через Telegram", "Sign in with Telegram")
                    : !subscriptionReady || !profileReady
                        ? Copy("Открыть профиль", "Open profile")
                        : !_vpnServiceAvailable
                            ? Copy("Настроить VPN", "Set up VPN")
                            : Copy("Подключить VPN", "Connect VPN");
        var primary = DeyttTheme.PrimaryButton(primaryLabel, () =>
        {
            if (starting)
                return;
            if (!signedIn)
                ShowSignInDialog();
            else if (!subscriptionReady || !profileReady)
                ShowTab(MainTab.Profile);
            else if (!_vpnServiceAvailable)
                ShowTab(MainTab.Settings);
            else
                _ = ToggleVpnAsync();
        });
        primary.IsEnabled = !starting;
        primary.Margin = new Thickness(0, 18, 0, 0);
        contents.Children.Add(primary);
        if (signedIn && subscriptionReady && profileReady)
        {
            var routeAction = DeyttTheme.Action(
                DeyttTheme.TextBlock(Copy("Изменить маршрут  ›", "Change route  ›"), 12,
                    DeyttTheme.Sky, FontWeight.SemiBold), () => ShowTab(MainTab.Routes));
            routeAction.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
            routeAction.Margin = new Thickness(0, 12, 0, 0);
            contents.Children.Add(routeAction);
        }
        return DeyttTheme.Card(contents, DeyttTheme.Surface2, DeyttTheme.Line, 23, new Thickness(19));
    }

    private Control BuildRoutesPage()
    {
        var page = NewPage(Copy("ВЫХОДЫ · ВЫБОР · ДИАГНОСТИКА", "EXITS · SELECTION · DIAGNOSTICS"), Copy("Маршруты", "Routes"));
        page.Children.Add(DeyttTheme.TextBlock(
            Copy("Выбери выход из своей подписки или используй автоподбор.",
                 "Choose an exit from your subscription or use auto-select."),
            13, DeyttTheme.Muted));
        if (_sessionToken is not null && _keysSnapshot?.ProfileJson is { Length: > 0 } && _routes.Count > 0)
        {
            AddSection(page, Copy("ДИАГНОСТИКА", "DIAGNOSTICS"));
            var diagnostic = new StackPanel { Spacing = 10 };
            diagnostic.Children.Add(DeyttTheme.TextBlock(
                _routeProbeInProgress
                    ? Copy("Три HTTPS-замера и проверка скорости идут для каждого выхода.",
                        "Three HTTPS samples and a speed check are running for each exit.")
                    : _vpnSnapshot.State == "connected"
                        ? Copy("Отключите VPN перед проверкой всех маршрутов.",
                            "Disconnect the VPN before checking all routes.")
                        : !_vpnServiceAvailable
                            ? Copy("Сначала установите службу VPN в настройках.",
                                "Install the VPN service in settings first.")
                            : Copy("Замеры выполняются отдельно через каждый выход из подписки.",
                                "Measurements run separately through each subscription exit."),
                12, DeyttTheme.Muted));
            var diagnosticAction = DeyttTheme.PrimaryButton(
                _routeProbeInProgress
                    ? _routeProbeCancelRequested
                        ? Copy("Останавливаем…", "Stopping…")
                        : Copy("Остановить проверку", "Stop diagnostics")
                    : !_vpnServiceAvailable
                        ? Copy("Настроить службу VPN", "Set up VPN service")
                        : Copy("Проверить все маршруты", "Check all routes"),
                () =>
                {
                    if (_routeProbeInProgress)
                        _ = CancelRouteProbeAsync();
                    else if (!_vpnServiceAvailable)
                        ShowTab(MainTab.Settings);
                    else
                        _ = ProbeRoutesAsync();
                });
            diagnosticAction.IsEnabled = _routeProbeInProgress
                ? !_routeProbeCancelRequested
                : _vpnSnapshot.State != "connected";
            diagnostic.Children.Add(diagnosticAction);
            page.Children.Add(DeyttTheme.Card(diagnostic, DeyttTheme.Surface2, DeyttTheme.Line, 20,
                new Thickness(16)));
        }
        AddSection(page, Copy("БЫСТРЫЙ ВЫБОР", "QUICK SELECT"));
        var quick = new StackPanel { Spacing = 0 };
        var autoRoute = _routes.FirstOrDefault(route => route.Id == "auto");
        if (autoRoute is not null)
            quick.Children.Add(RouteOption(autoRoute, Copy("Выберем доступный узел", "Choose an available node")));
        else
            quick.Children.Add(QuickOption("✦", Copy("Автоподбор", "Auto-select"),
                Copy("Выберем доступный узел", "Choose an available node"), "auto"));
        quick.Children.Add(DeyttTheme.Hairline(66));
        var chainRoute = _routes.FirstOrDefault(route => route.Id == "ru-de");
        if (chainRoute is not null)
            quick.Children.Add(RouteOption(chainRoute,
                Copy("Россия → Германия · двойной маршрут", "Russia → Germany · double route")));
        else
            quick.Children.Add(QuickOption("🇷🇺🇩🇪", Copy("LTE + белые списки", "LTE + whitelist"),
                Copy("Россия → Германия · двойной маршрут", "Russia → Germany · double route"), "ru-de"));
        page.Children.Add(DeyttTheme.Card(quick, DeyttTheme.Surface, DeyttTheme.Line, 22, new Thickness(4, 5)));
        AddSection(page, Copy("СТРАНЫ", "COUNTRIES"));

        if (_sessionToken is null)
        {
            var signedOut = new StackPanel { Spacing = 10 };
            signedOut.Children.Add(DeyttTheme.TextBlock(
                Copy("Маршруты появятся после входа", "Your routes will appear after sign-in"),
                18, DeyttTheme.Text, FontWeight.SemiBold));
            signedOut.Children.Add(DeyttTheme.TextBlock(
                Copy("Список стран и протоколов загружается из вашей подписки.",
                     "Countries and protocols are loaded from your subscription."),
                13, DeyttTheme.Muted));
            signedOut.Children.Add(DeyttTheme.PrimaryButton(Copy("Подключить Telegram", "Connect Telegram"),
                () => ShowTab(MainTab.Profile)));
            page.Children.Add(DeyttTheme.Card(signedOut, DeyttTheme.Surface2, DeyttTheme.Line, 22, new Thickness(18)));
        }
        else if (_routes.Count == 0)
        {
            var unavailable = new StackPanel { Spacing = 10 };
            unavailable.Children.Add(DeyttTheme.TextBlock(
                _profileLoadIssue ?? Copy("Активная подписка не найдена", "No active subscription found"),
                18, DeyttTheme.Text, FontWeight.SemiBold));
            unavailable.Children.Add(DeyttTheme.TextBlock(
                Copy("Обнови профиль после подключения тарифа.", "Refresh your profile after activating a plan."),
                13, DeyttTheme.Muted));
            unavailable.Children.Add(DeyttTheme.PrimaryButton(Copy("Обновить профиль", "Refresh profile"),
                () => _ = RefreshSignedInAccountAsync()));
            page.Children.Add(DeyttTheme.Card(unavailable, DeyttTheme.Surface2, DeyttTheme.Line, 22, new Thickness(18)));
        }
        else
        {
            foreach (var country in _routes.Where(route => route.CountryCode is "NL" or "RU" or "DE" or "FI" or "AWG_UNKNOWN")
                         .GroupBy(route => route.CountryCode))
            {
                var first = country.First();
                page.Children.Add(DeyttTheme.Spacer(12));
                var options = new StackPanel { Spacing = 2 };
                foreach (var route in country)
                    options.Children.Add(RouteOption(route, null));
                page.Children.Add(DeyttTheme.Card(new StackPanel
                {
                    Spacing = 8,
                    Children =
                    {
                        DeyttTheme.TextBlock($"{first.Flag}   {RouteCountryName(first)}", 17,
                            DeyttTheme.Text, FontWeight.SemiBold),
                        options,
                    },
                }, DeyttTheme.Surface, DeyttTheme.Line, 20, new Thickness(12, 11)));
            }
        }
        return page;
    }

    private Control RouteOption(WindowsRoute route, string? note)
    {
        var subtitle = note ?? route.ProfileName ?? route.ProtocolName;
        var selected = _selectedRoute == route.Id;
        var body = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), MinHeight = 62 };
        body.Children.Add(DeyttTheme.IconTile(route.Protocol == "AUTO" ? "✦" : route.Flag, 46));
        var labels = new StackPanel
        {
            Spacing = 3,
            Margin = new Thickness(12, 0, 8, 0),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        labels.Children.Add(DeyttTheme.TextBlock(route.CountryCode == "AUTO" ? route.CountryName : subtitle,
            15, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(note is null ? $"{route.CountryName} · {route.ProtocolName}" : note,
            11, DeyttTheme.Muted));
        if (_routeProbeResults.TryGetValue(route.Tag, out var probe))
        {
            var probeLabel = probe.LatencyMilliseconds is { } latency
                ? $"{Copy("Пинг", "Ping")}: {latency} ms · {Copy("Скорость", "Speed")}: " +
                  (probe.BytesPerSecond is { } bps
                      ? $"{(bps * 8d / 1_000_000d).ToString("0.#", System.Globalization.CultureInfo.InvariantCulture)} Mbps"
                      : "—")
                : probe.Error ?? Copy("Нет ответа", "No response");
            labels.Children.Add(DeyttTheme.TextBlock(probeLabel, 10,
                probe.LatencyMilliseconds is null ? DeyttTheme.Coral : DeyttTheme.Muted));
        }
        Grid.SetColumn(labels, 1);
        body.Children.Add(labels);
        var action = DeyttTheme.TextBlock(selected ? "✓" : Copy("выбрать", "select"),
            12, selected ? DeyttTheme.Mint : DeyttTheme.Muted, FontWeight.SemiBold, wrap: false);
        Grid.SetColumn(action, 2);
        body.Children.Add(action);
        var button = DeyttTheme.Action(body, () => SelectRoute(route.Id));
        button.Padding = new Thickness(8, 4);
        return button;
    }

    private Control QuickOption(string glyph, string title, string subtitle, string routeId)
    {
        var body = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), MinHeight = 72 };
        body.Children.Add(DeyttTheme.IconTile(glyph, 52));
        var labels = new StackPanel { Spacing = 3, Margin = new Thickness(14, 0, 8, 0), VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(title, 17, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 12, DeyttTheme.Muted));
        Grid.SetColumn(labels, 1);
        body.Children.Add(labels);
        var action = DeyttTheme.TextBlock(Copy("выбрать", "select"), 13, DeyttTheme.Muted,
            FontWeight.SemiBold, wrap: false);
        Grid.SetColumn(action, 2);
        body.Children.Add(action);
        var button = DeyttTheme.Action(body, () => SelectRoute(routeId));
        button.Padding = new Thickness(10, 5);
        return button;
    }

    private Control BuildProfilePage()
    {
        var page = NewPage(Copy("ВАШ АККАУНТ", "YOUR ACCOUNT"), Copy("Профиль", "Profile"));
        AddSection(page, Copy("АККАУНТ", "ACCOUNT"));
        var account = _account;
        var signedIn = _sessionToken is not null;
        var accountName = account is null
            ? (signedIn ? Copy("Telegram подключён", "Telegram connected") : Copy("Telegram не подключён", "Telegram not connected"))
            : string.IsNullOrWhiteSpace(account.FirstName) ? $"@{account.Username}" : account.FirstName;
        var accountNote = account is null
            ? (signedIn ? Copy("Не удалось обновить профиль", "Could not refresh profile") : Copy("Подписка и устройства", "Subscription and devices"))
            : $"@{account.Username}";
        page.Children.Add(SettingsEntry("D", accountName, accountNote,
            signedIn ? Copy("выйти", "sign out") : "›", () =>
            {
                if (signedIn)
                    SignOut();
                else
                    ShowSignInDialog();
            }));
        page.Children.Add(DeyttTheme.Spacer(14));
        AddSection(page, Copy("ПОДПИСКА", "SUBSCRIPTION"));
        var subscription = account?.Subscription;
        var hasSubscription = subscription?.Active == true;
        var subscriptionTitle = hasSubscription
            ? subscription!.TariffName ?? Copy("Подписка активна", "Subscription active")
            : Copy("Нет активной подписки", "No active subscription");
        var subscriptionDetail = signedIn
            ? _profileLoadIssue ?? SubscriptionSummary(subscription)
            : Copy("Войдите через Telegram, чтобы загрузить тариф и данные об использовании.",
                "Sign in with Telegram to load your plan and usage.");
        page.Children.Add(DeyttTheme.Card(new StackPanel
        {
            Spacing = 5,
            Children =
            {
                DeyttTheme.TextBlock(subscriptionTitle, 18,
                    DeyttTheme.Text, FontWeight.SemiBold),
                DeyttTheme.TextBlock(subscriptionDetail, 13, DeyttTheme.Muted),
            },
        }, DeyttTheme.Surface, DeyttTheme.Line, 22, new Thickness(18)));
        page.Children.Add(DeyttTheme.Spacer(10));
        page.Children.Add(SettingsEntry("₽", Copy("Планы и оплата", "Plans and payment"),
            Copy("Сумму подтвердит сервер до оплаты", "The server confirms the amount before checkout"),
            Copy("открыть", "open"), () => ShowInfoDialog(Copy("Планы и оплата", "Plans and payment"),
                signedIn
                    ? Copy("Покупка тарифа пока не перенесена.", "Plan checkout has not been ported yet.")
                    : Copy("Сначала подключите Telegram-аккаунт.", "Connect your Telegram account first."))));
        page.Children.Add(DeyttTheme.Spacer(14));
        var devices = _keysSnapshot?.HappDevices ?? [];
        var deviceNote = signedIn
            ? $"{devices.Count} {Copy("устройств Happ", "Happ devices")} · {_keysSnapshot?.AmneziaClients ?? 0} {Copy("клиентов AWG", "AWG clients")}"
            : Copy("Подключения и доступ", "Connections and access");
        page.Children.Add(SettingsEntry("+", Copy("Устройства и сеансы", "Devices and sessions"),
            deviceNote, signedIn ? "↻" : "+", () =>
            {
                if (signedIn)
                    _ = RefreshSignedInAccountAsync();
                else
                    ShowSignInDialog();
            }));
        foreach (var device in devices)
        {
            var deviceName = string.Join(" · ", new[] { device.Model, device.Os }.Where(value => !string.IsNullOrWhiteSpace(value)));
            if (deviceName.Length == 0)
                deviceName = Copy("Устройство Happ", "Happ device");
            var state = device.Blocked ? Copy("заблокировано", "blocked") : Copy("активно", "active");
            page.Children.Add(DeyttTheme.Spacer(5));
            page.Children.Add(SettingsEntry("H", deviceName, $"{state} · {FormatLastSeen(device.LastSeen)}",
                device.Blocked ? Copy("вернуть", "restore") : Copy("отозвать", "revoke"),
                () => _ = ToggleHappDeviceAsync(device)));
        }
        page.Children.Add(DeyttTheme.Spacer(10));
        page.Children.Add(SettingsEntry("+", Copy("Помощь и документы", "Help and documents"),
            Copy("Поддержка, условия и конфиденциальность", "Support, terms and privacy"), "+",
            () => OpenExternal("https://deytt.space/info")));
        return page;
    }

    private Control BuildSettingsPage()
    {
        var page = NewPage(Copy("ПОД ВАС", "MAKE IT YOURS"), Copy("Настройки", "Settings"));
        AddSection(page, Copy("ЯЗЫК И АККАУНТ", "LANGUAGE AND ACCOUNT"));
        page.Children.Add(SettingsEntry("Aa", Copy("Язык приложения", "App language"),
            _language == "ru" ? "Русский" : "English", "›", ToggleLanguage));
        page.Children.Add(DeyttTheme.Spacer(6));
        var accountDetail = _sessionToken is null
            ? Copy("Подписка и устройства", "Subscription and devices")
            : string.IsNullOrWhiteSpace(_account?.Username)
                ? Copy("Аккаунт подключён", "Account connected")
                : $"@{_account!.Username}";
        page.Children.Add(SettingsEntry("✓", Copy("Войти через Telegram", "Sign in with Telegram"),
            accountDetail,
            "›", () =>
            {
                if (_sessionToken is null)
                    ShowSignInDialog();
                else
                    ShowTab(MainTab.Profile);
            }, true));

        AddSection(page, Copy("ПОДКЛЮЧЕНИЕ", "CONNECTION"));
        page.Children.Add(SettingsEntry("↗", Copy("Раздельное туннелирование", "Split tunneling"),
            Copy("Сайты из подписки · все протоколы", "Subscription sites · all protocols"), "›",
            () => ShowInfoDialog(Copy("Без VPN", "Without VPN"),
                Copy("Список сайтов загрузится вместе с подпиской.", "The bypass list loads with your subscription."))));
        page.Children.Add(DeyttTheme.Spacer(4));
        page.Children.Add(SettingsEntry("↻", Copy("Проверка маршрута", "Route check"),
            $"HTTP {_probeMethod} · {Copy("быстрая проверка, загрузка 1 с", "fast check, 1 s download")}", "›", ToggleProbeMethod));
        page.Children.Add(DeyttTheme.Spacer(4));
        page.Children.Add(SettingsEntry("↗", Copy("Служба VPN", "VPN service"),
            _vpnServiceAvailable
                ? Copy("Служба установлена · нажмите для проверки", "Service installed · click to repair")
                : Copy("Требуются права администратора", "Administrator approval required"), "›",
            () => _ = InstallVpnServiceAsync()));
        page.Children.Add(DeyttTheme.Spacer(4));
        page.Children.Add(SettingsEntry("↗", Copy("Бот DEYTT", "DEYTT bot"),
            Copy("Другие действия в Telegram", "More actions in Telegram"), "›", () => OpenExternal("https://t.me/deyttbot")));

        AddSection(page, Copy("ВИД И ПРИВАТНОСТЬ", "APPEARANCE AND PRIVACY"));
        page.Children.Add(ToggleEntry(Copy("Регион на карте", "Region on the map"),
            Copy("Определение по IP · без GPS и сохранения координат", "IP lookup · no GPS or saved coordinates"),
            _mapRegionEnabled, value => _mapRegionEnabled = value));
        page.Children.Add(ToggleEntry(Copy("Уменьшить движение", "Reduce motion"),
            Copy("Приостановить фоновую анимацию", "Pause background animation"),
            _reduceMotion, value => _reduceMotion = value));

        AddSection(page, Copy("ОБНОВЛЕНИЯ", "UPDATES"));
        page.Children.Add(SettingsEntry("↻", Copy("Проверить обновления", "Check for updates"),
            Copy("Текущая версия приложения", "Current application version"), "›",
            () => OpenExternal("https://github.com/crxwov/deytt.connect/releases/latest")));
        if (_preferencesIssue is not null)
        {
            page.Children.Add(DeyttTheme.Spacer(10));
            page.Children.Add(DeyttTheme.TextBlock(_preferencesIssue, 12, DeyttTheme.Coral));
        }
        return page;
    }

    private Control SettingsEntry(string glyph, string title, string subtitle, string trailing,
        Action onClick, bool emphasis = false)
    {
        var content = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), MinHeight = 84 };
        content.Children.Add(DeyttTheme.IconTile(glyph, 52, emphasis ? DeyttTheme.Sky : DeyttTheme.Sky));
        var labels = new StackPanel { Spacing = 3, Margin = new Thickness(14, 0, 6, 0), VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(title, 17, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 12, DeyttTheme.Muted));
        Grid.SetColumn(labels, 1);
        content.Children.Add(labels);
        var arrow = DeyttTheme.TextBlock(trailing, 13, emphasis ? DeyttTheme.Sky : DeyttTheme.Muted,
            FontWeight.SemiBold, wrap: false);
        arrow.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(arrow, 2);
        content.Children.Add(arrow);
        var button = DeyttTheme.Action(content, onClick);
        button.Padding = new Thickness(5, 5);
        return new Border
        {
            Background = DeyttTheme.Brush(emphasis ? DeyttTheme.Selected : Colors.Transparent),
            BorderBrush = DeyttTheme.Brush(emphasis ? DeyttTheme.SelectedLine : Colors.Transparent),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(19),
            Child = button,
        };
    }

    private Control ToggleEntry(string title, string subtitle, bool value, Action<bool> update)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), MinHeight = 86 };
        var labels = new StackPanel { Spacing = 3, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(title, 17, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 12, DeyttTheme.Muted));
        grid.Children.Add(labels);
        var knob = new Grid
        {
            Width = 48,
            Height = 27,
            Background = DeyttTheme.Brush(value ? DeyttTheme.MintSurface : DeyttTheme.Surface2),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        knob.Children.Add(new Ellipse
        {
            Width = 21,
            Height = 21,
            Fill = DeyttTheme.Brush(value ? DeyttTheme.Mint : DeyttTheme.Muted),
            HorizontalAlignment = value ? Avalonia.Layout.HorizontalAlignment.Right : Avalonia.Layout.HorizontalAlignment.Left,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Margin = new Thickness(3),
        });
        var toggle = new Border { Child = knob, CornerRadius = new CornerRadius(15) };
        Grid.SetColumn(toggle, 1);
        grid.Children.Add(toggle);
        var button = DeyttTheme.Action(grid, () =>
        {
            update(!value);
            SavePreferences();
            ShowTab(MainTab.Settings);
        });
        return DeyttTheme.Card(button, DeyttTheme.Surface, DeyttTheme.Line, 22, new Thickness(18, 8));
    }

    private StackPanel NewPage(string kicker, string title)
    {
        var page = new StackPanel { Spacing = 0 };
        page.Children.Add(DeyttTheme.TextBlock(kicker, 12, DeyttTheme.Sky,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono));
        page.Children.Add(DeyttTheme.Spacer(8));
        page.Children.Add(DeyttTheme.TextBlock(title, 36, DeyttTheme.Text,
            FontWeight.Bold, DeyttTheme.InterTight));
        page.Children.Add(DeyttTheme.Spacer(22));
        return page;
    }

    private static void AddSection(StackPanel page, string title)
    {
        page.Children.Add(DeyttTheme.Spacer(24));
        page.Children.Add(DeyttTheme.SectionLabel(title));
        page.Children.Add(DeyttTheme.Spacer(10));
    }

    private string RouteTitle() => _routes.FirstOrDefault(route => route.Id == _selectedRoute) is { } route
        ? route.CountryCode == "AUTO"
            ? Copy("Автоподбор", "Auto-select")
            : $"{route.Flag} {RouteCountryName(route)} · {route.ProfileName ?? route.ProtocolName}"
        : Copy("Автоподбор", "Auto-select");

    private string RouteTitleForTag(string? tag) =>
        _routes.FirstOrDefault(route => route.Tag == tag) is { } route
            ? route.CountryCode == "AUTO"
                ? Copy("Автоподбор", "Auto-select")
                : $"{route.Flag} {RouteCountryName(route)} · {route.ProfileName ?? route.ProtocolName}"
            : RouteTitle();

    private string? GetAwgConfig(WindowsRoute route) =>
        route.Protocol == "AWG31" && route.ProfileId is { } profileId
            ? _keysSnapshot?.AwgProfiles.FirstOrDefault(profile => profile.RouteId == profileId)?.Config
            : null;

    private string RouteCountryName(WindowsRoute route) => route.CountryCode switch
    {
        "NL" => Copy("Нидерланды", "Netherlands"),
        "RU" => Copy("Россия", "Russia"),
        "DE" => Copy("Германия", "Germany"),
        "FI" => Copy("Финляндия", "Finland"),
        "AWG_UNKNOWN" => Copy("Другие регионы", "Other regions"),
        "RU-DE" => Copy("LTE + белые списки", "LTE + whitelist"),
        _ => route.CountryName,
    };

    private void SelectRoute(string routeId)
    {
        if (_routeProbeInProgress || _vpnActionInProgress)
            return;
        var route = _routes.FirstOrDefault(value => value.Id == routeId);
        if (_sessionToken is null)
        {
            ShowSignInDialog();
            return;
        }
        if (route is null)
        {
            ShowInfoDialog(Copy("Маршрут не загружен", "Route not loaded"),
                Copy("Обнови подписку, чтобы выбрать этот выход.", "Refresh the subscription to select this exit."));
            return;
        }
        _selectedRoute = routeId;
        SavePreferences();
        ShowTab(MainTab.Home);
        if ((_vpnSnapshot.State is "connected" or "starting" or "checking") &&
            _keysSnapshot?.ProfileJson is { Length: > 0 } profile)
            _ = ReconnectSelectedRouteAsync(route, profile);
    }

    private async Task ReconnectSelectedRouteAsync(WindowsRoute route, string profile)
    {
        if (_vpnActionInProgress)
            return;

        _vpnActionInProgress = true;
        _vpnSnapshot = new WindowsTunnelSnapshot("starting",
            Copy("Переключаем маршрут…", "Switching route…"), route.Tag);
        RenderActiveTabPreservingScroll();
        try
        {
            var awgConfig = GetAwgConfig(route);
            if (route.Protocol == "AWG31" && awgConfig is null)
            {
                _vpnSnapshot = new WindowsTunnelSnapshot("error",
                    Copy("Профиль AmneziaWG не загружен. Обновите подписку.",
                        "The AmneziaWG profile is unavailable. Refresh the subscription."), route.Tag);
                RenderActiveTabPreservingScroll();
                return;
            }
            _vpnSnapshot = await _tunnelClient.ConnectAsync(profile, route.Tag, awgConfig);
            _vpnServiceAvailable = true;
            RenderActiveTabPreservingScroll();
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            _vpnSnapshot = new WindowsTunnelSnapshot("error",
                Copy("Не удалось переключить маршрут. Проверьте службу VPN.",
                    "Could not switch route. Check the VPN service."), route.Tag);
            RenderActiveTabPreservingScroll();
        }
        finally
        {
            _vpnActionInProgress = false;
            RenderActiveTabPreservingScroll();
        }

        if (_vpnSnapshot.State is "starting" or "checking")
            _ = MonitorVpnConnectionAsync();
    }

    private void ToggleLanguage()
    {
        _language = _language == "ru" ? "en" : "ru";
        SavePreferences();
        ShowTab(MainTab.Settings);
    }

    private void ToggleProbeMethod()
    {
        _probeMethod = _probeMethod == "HEAD" ? "GET" : "HEAD";
        SavePreferences();
        ShowTab(MainTab.Settings);
    }

    private async Task ToggleVpnAsync()
    {
        if (_vpnActionInProgress || _routeProbeInProgress)
            return;

        _vpnActionInProgress = true;
        RenderActiveTabPreservingScroll();
        try
        {
            if (_vpnSnapshot.State == "connected")
            {
                _vpnSnapshot = await _tunnelClient.DisconnectAsync();
                _vpnServiceAvailable = true;
                RenderActiveTabPreservingScroll();
                return;
            }

            if (_keysSnapshot?.ProfileJson is not { Length: > 0 } profile ||
                _routes.FirstOrDefault(route => route.Id == _selectedRoute) is not { } route)
            {
                _vpnSnapshot = new WindowsTunnelSnapshot("error",
                    Copy("Сначала загрузите подписку и выберите маршрут.",
                        "Load a subscription and choose a route first."));
                RenderActiveTabPreservingScroll();
                return;
            }

            _vpnSnapshot = new WindowsTunnelSnapshot("starting",
                Copy("Подготавливаем профиль VPN…", "Preparing VPN profile…"), route.Tag);
            RenderActiveTabPreservingScroll();
            var awgConfig = GetAwgConfig(route);
            if (route.Protocol == "AWG31" && awgConfig is null)
            {
                _vpnSnapshot = new WindowsTunnelSnapshot("error",
                    Copy("Профиль AmneziaWG не загружен. Обновите подписку.",
                        "The AmneziaWG profile is unavailable. Refresh the subscription."), route.Tag);
                RenderActiveTabPreservingScroll();
                return;
            }
            _vpnSnapshot = await _tunnelClient.ConnectAsync(profile, route.Tag, awgConfig);
            _vpnServiceAvailable = true;
            RenderActiveTabPreservingScroll();
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            _vpnSnapshot = new WindowsTunnelSnapshot("error",
                Copy("Служба VPN не ответила. Откройте настройки и проверьте её установку.",
                    "The VPN service did not respond. Check its installation in settings."));
            RenderActiveTabPreservingScroll();
        }
        finally
        {
            _vpnActionInProgress = false;
            RenderActiveTabPreservingScroll();
        }

        if (_vpnSnapshot.State is "starting" or "checking")
            _ = MonitorVpnConnectionAsync();
    }

    private async Task MonitorVpnConnectionAsync()
    {
        for (var attempt = 0; attempt < 45; attempt++)
        {
            await Task.Delay(TimeSpan.FromSeconds(1));
            if (_vpnSnapshot.State is not ("starting" or "checking"))
                return;
            await RefreshVpnStatusAsync();
        }
    }

    private async Task ProbeRoutesAsync()
    {
        if (_routeProbeInProgress || _vpnActionInProgress || _vpnSnapshot.State == "connected")
            return;
        if (_sessionToken is not { Length: > 0 } token ||
            _keysSnapshot?.ProfileJson is not { Length: > 0 } profile || _routes.Count == 0)
            return;
        if (!_vpnServiceAvailable)
        {
            ShowTab(MainTab.Settings);
            return;
        }

        _routeProbeInProgress = true;
        _routeProbeCancelRequested = false;
        RenderActiveTabPreservingScroll();
        try
        {
            var routeTags = _routes.Select(route => route.Tag).Distinct(StringComparer.Ordinal).ToArray();
            var awgProfiles = (_keysSnapshot?.AwgProfiles ?? [])
                .ToDictionary(item => item.RouteId, item => item.Config, StringComparer.Ordinal);
            var result = await _tunnelClient.ProbeAsync(profile, routeTags, _probeMethod, token, awgProfiles);
            if (result.State == "probe_complete" && result.ProbeResults is { } probes)
            {
                _routeProbeResults = probes
                    .GroupBy(probe => probe.RouteTag, StringComparer.Ordinal)
                    .ToDictionary(group => group.Key, group => group.Last(), StringComparer.Ordinal);
            }
            else if (result.State != "probe_cancelled")
            {
                ShowInfoDialog(Copy("Не удалось проверить маршруты", "Could not check routes"),
                    result.Detail);
            }
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            ShowInfoDialog(Copy("Не удалось проверить маршруты", "Could not check routes"),
                Copy("Служба VPN не ответила. Проверьте её установку и повторите попытку.",
                    "The VPN service did not respond. Check its installation and try again."));
        }
        finally
        {
            _routeProbeInProgress = false;
            _routeProbeCancelRequested = false;
            RenderActiveTabPreservingScroll();
        }
    }

    private async Task CancelRouteProbeAsync()
    {
        if (!_routeProbeInProgress || _routeProbeCancelRequested)
            return;
        _routeProbeCancelRequested = true;
        RenderActiveTabPreservingScroll();
        try
        {
            await _tunnelClient.CancelProbeAsync();
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
        }
    }

    private async Task RefreshVpnStatusAsync()
    {
        if (!OperatingSystem.IsWindows() || _vpnActionInProgress || _vpnStatusRefreshRunning)
            return;

        _vpnStatusRefreshRunning = true;
        try
        {
            var previousSnapshot = _vpnSnapshot;
            var wasAvailable = _vpnServiceAvailable;
            _vpnSnapshot = await _tunnelClient.GetStatusAsync();
            _vpnServiceAvailable = true;
            if (previousSnapshot != _vpnSnapshot || wasAvailable != _vpnServiceAvailable)
                RenderActiveTabPreservingScroll();
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            var wasAvailable = _vpnServiceAvailable;
            _vpnServiceAvailable = false;
            if (wasAvailable)
                RenderActiveTabPreservingScroll();
        }
        finally
        {
            _vpnStatusRefreshRunning = false;
        }
    }

    private bool _vpnStatusRefreshRunning;

    private async Task InstallVpnServiceAsync()
    {
        if (!OperatingSystem.IsWindows() || _vpnActionInProgress)
            return;

        var installerPath = System.IO.Path.Combine(AppContext.BaseDirectory, "Install-VpnService.ps1");
        if (!File.Exists(installerPath))
        {
            ShowInfoDialog(Copy("Установка недоступна", "Setup unavailable"),
                Copy("Установите приложение из полного Windows-пакета.",
                    "Install the app from the complete Windows package."));
            return;
        }

        _vpnActionInProgress = true;
        RenderActiveTabPreservingScroll();
        try
        {
            using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
            var sid = identity.User?.Value;
            if (string.IsNullOrWhiteSpace(sid))
                throw new InvalidOperationException("The current Windows user SID is unavailable.");

            var powerShellPath = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),
                "WindowsPowerShell", "v1.0", "powershell.exe");
            var start = new ProcessStartInfo
            {
                FileName = powerShellPath,
                Arguments = $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"{installerPath}\" -AllowedUserSid \"{sid}\" -BundleRoot \"{AppContext.BaseDirectory.TrimEnd(System.IO.Path.DirectorySeparatorChar)}\"",
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            using var installer = Process.Start(start)
                                  ?? throw new InvalidOperationException("The VPN installer did not start.");
            await installer.WaitForExitAsync();
            if (installer.ExitCode != 0)
            {
                ShowInfoDialog(Copy("Служба VPN не установлена", "VPN service was not installed"),
                    Copy("Закройте сообщение установщика и попробуйте ещё раз.",
                        "Close the installer message and try again."));
                return;
            }

            _vpnSnapshot = await _tunnelClient.GetStatusAsync();
            _vpnServiceAvailable = true;
            ShowInfoDialog(Copy("Служба VPN готова", "VPN service is ready"),
                Copy("Теперь можно подключиться с главного экрана.",
                    "You can connect from the Home screen now."));
        }
        catch (System.ComponentModel.Win32Exception error) when (error.NativeErrorCode == 1223)
        {
            // The user canceled the Windows elevation prompt.
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
                                      InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            ShowInfoDialog(Copy("Не удалось настроить службу VPN", "Could not configure the VPN service"),
                Copy("Запустите установку ещё раз и подтвердите запрос Windows.",
                    "Run setup again and approve the Windows prompt."));
        }
        finally
        {
            _vpnActionInProgress = false;
            RenderActiveTabPreservingScroll();
        }
    }

    private static bool IsTunnelTransportError(Exception error) =>
        error is IOException or UnauthorizedAccessException or TimeoutException or
            OperationCanceledException or System.ComponentModel.Win32Exception;

    private void RenderActiveTabPreservingScroll()
    {
        var offset = PageScroll.Offset;
        ShowTab(_activeTab);
        PageScroll.Offset = offset;
    }

    private void RequireSignIn()
    {
        if (_sessionToken is null)
            ShowSignInDialog();
        else
            ShowInfoDialog(Copy("Аккаунт подключён", "Account connected"),
                Copy("Управление этой функцией появится после переноса устройств и сеансов.",
                    "Device and session management is still being ported."));
    }

    private void ShowSignInDialog() => _ = ShowTelegramPairingDialogAsync();

    private async Task RestoreSessionAsync()
    {
        if (!OperatingSystem.IsWindows())
            return;

        string? token;
        try
        {
            token = WindowsSessionStore.Load();
        }
        catch (Exception error) when (error is CryptographicException or IOException or UnauthorizedAccessException)
        {
            return;
        }

        if (token is null)
            return;

        _sessionToken = token;
        try
        {
            await RefreshAccountDataAsync(token);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            _account = null;
            _keysSnapshot = null;
            _routes = [];
            _profileLoadIssue = Copy("Не удалось загрузить данные аккаунта.", "Could not load account data.");
        }

        if (IsVisible)
            ShowTab(_activeTab);
    }

    private async Task<bool> RefreshAccountDataAsync(string token)
    {
        _account = await _telegramApi.GetAccountAsync(token);
        try
        {
            _keysSnapshot = await _telegramApi.GetSubscriptionAsync(token);
            _routes = _keysSnapshot.Routes;
            _profileLoadIssue = _keysSnapshot.HappAvailable
                ? null
                : Copy("Активная подписка не найдена", "No active subscription found");
            if (!_routes.Any(route => route.Id == _selectedRoute))
            {
                _selectedRoute = "auto";
                SavePreferences();
            }
            return _keysSnapshot.HappAvailable;
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            throw;
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            _keysSnapshot = null;
            _routes = [];
            _profileLoadIssue = Copy("Не удалось загрузить маршруты. Повтори обновление позже.",
                "Could not load routes. Try refreshing again later.");
            return false;
        }
    }

    private async Task RefreshSignedInAccountAsync()
    {
        var token = _sessionToken;
        if (token is null)
        {
            ShowSignInDialog();
            return;
        }

        try
        {
            await RefreshAccountDataAsync(token);
            ShowTab(_activeTab);
            if (_profileLoadIssue is not null)
                ShowInfoDialog(Copy("Профиль обновлён", "Profile refreshed"), _profileLoadIssue);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(_activeTab);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            ShowInfoDialog(Copy("Не удалось обновить профиль", "Could not refresh profile"),
                Copy("Проверь интернет и повтори попытку.", "Check your connection and try again."));
        }
    }

    private async Task ToggleHappDeviceAsync(TelegramHappDevice device)
    {
        var token = _sessionToken;
        if (token is null)
            return;
        var nextBlocked = !device.Blocked;
        var title = nextBlocked
            ? Copy("Отозвать устройство?", "Revoke this device?")
            : Copy("Вернуть устройство?", "Restore this device?");
        var detail = nextBlocked
            ? Copy("Доступ по подписке будет отозван при следующем обновлении Happ. Текущий туннель может не отключиться сразу.",
                "Subscription access is revoked on the next Happ refresh. An active tunnel may not stop immediately.")
            : Copy("Устройство снова займёт место в лимите подключений.",
                "The device will use a slot in your connection limit again.");
        if (!await ConfirmDialogAsync(title, detail,
                nextBlocked ? Copy("Отозвать", "Revoke") : Copy("Вернуть", "Restore")))
            return;

        try
        {
            await _telegramApi.SetHappDeviceBlockedAsync(token, device.Id, nextBlocked);
            try
            {
                await RefreshAccountDataAsync(token);
                ShowTab(_activeTab);
            }
            catch (TelegramApiException error) when (error.IsUnauthorized)
            {
                throw;
            }
            catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
            {
                ShowTab(_activeTab);
                ShowInfoDialog(Copy("Изменение подтверждено", "Change confirmed"),
                    Copy("Устройство обновлено, но список не удалось перезагрузить.",
                        "The device was updated, but the list could not be refreshed."));
            }
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(_activeTab);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            ShowInfoDialog(Copy("Не удалось обновить устройство", "Could not update the device"),
                Copy("Сервер не подтвердил изменение. Проверь интернет и обнови список.",
                    "The server did not confirm the change. Check your connection and refresh the list."));
        }
    }

    private async Task<bool> ConfirmDialogAsync(string title, string message, string confirmLabel)
    {
        var confirmed = false;
        var cancel = DeyttTheme.Action(DeyttTheme.TextBlock(Copy("Отмена", "Cancel"), 14,
            DeyttTheme.Muted, FontWeight.SemiBold), () => { });
        var confirm = DeyttTheme.PrimaryButton(confirmLabel, () => { });
        var dialog = new Window
        {
            Title = title,
            Width = 410,
            MinWidth = 340,
            MaxWidth = 460,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
            Content = DeyttTheme.Card(new StackPanel
            {
                Spacing = 14,
                Children =
                {
                    DeyttTheme.TextBlock(title, 21, DeyttTheme.Text, FontWeight.Bold),
                    DeyttTheme.TextBlock(message, 13, DeyttTheme.Muted),
                    cancel,
                    confirm,
                },
            }, DeyttTheme.Surface2, DeyttTheme.Line, 21, new Thickness(20)),
        };
        if (cancel is Button cancelButton)
            cancelButton.Click += (_, _) => dialog.Close();
        if (confirm.Child is Button confirmButton)
            confirmButton.Click += (_, _) => { confirmed = true; dialog.Close(); };
        await dialog.ShowDialog(this);
        return confirmed;
    }

    private bool ClearLocalSession()
    {
        var cleared = true;
        try
        {
            WindowsSessionStore.Clear();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            _profileLoadIssue = Copy("Не удалось удалить сохранённый сеанс Windows.",
                "Could not remove the saved Windows session.");
            cleared = false;
        }

        _sessionToken = null;
        _account = null;
        _keysSnapshot = null;
        _routes = [];
        _selectedRoute = "auto";
        if (cleared)
            _profileLoadIssue = null;
        SavePreferences();
        return cleared;
    }

    private void SavePreferences()
    {
        if (!OperatingSystem.IsWindows())
            return;
        try
        {
            WindowsPreferencesStore.Save(new WindowsPreferences(
                _language, _selectedRoute, _probeMethod, _mapRegionEnabled, _reduceMotion));
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            _preferencesIssue = Copy("Настройки не сохранились на этом устройстве.",
                "Settings could not be saved on this device.");
        }
    }

    private string FormatLastSeen(string? value)
    {
        if (!DateTimeOffset.TryParse(value, out var lastSeen))
            return Copy("дата неизвестна", "date unknown");
        return lastSeen.ToLocalTime().ToString("dd MMM yyyy HH:mm");
    }

    private async Task ShowTelegramPairingDialogAsync()
    {
        if (!OperatingSystem.IsWindows())
        {
            ShowInfoDialog(Copy("Вход через Telegram", "Sign in with Telegram"),
                Copy("Сохранение сеанса защищено средствами Windows. Завершите вход в версии для Windows.",
                    "Session storage uses Windows protection. Complete sign-in in the Windows build."));
            return;
        }

        var title = DeyttTheme.TextBlock(Copy("Добавить приложение", "Add this app"), 22,
            DeyttTheme.Text, FontWeight.Bold);
        var detail = DeyttTheme.TextBlock(
            Copy("Укажи Telegram username. Бот отправит одноразовый код, чтобы подключить существующую подписку.",
                "Enter your Telegram username. The bot sends a one-time code to link your subscription."),
            13, DeyttTheme.Muted);
        var usernameInput = new TextBox
        {
            PlaceholderText = "@username",
            MaxLength = 33,
            MinHeight = 52,
            FontSize = 15,
            Foreground = DeyttTheme.Brush(DeyttTheme.Text),
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(15, 10),
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        var codeInput = new TextBox
        {
            PlaceholderText = "123456",
            MaxLength = 6,
            MinHeight = 58,
            FontSize = 22,
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Foreground = DeyttTheme.Brush(DeyttTheme.Text),
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(15, 10),
            IsVisible = false,
        };
        var status = DeyttTheme.TextBlock("", 12, DeyttTheme.Muted);
        var openBot = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("Открыть бота в Telegram", "Open the Telegram bot"), 14,
                DeyttTheme.Sky, FontWeight.SemiBold), () => { });
        openBot.IsVisible = false;
        var primary = DeyttTheme.PrimaryButton(Copy("Получить код", "Get code"), () => { });
        var primaryButton = (Button)primary.Child!;
        var primaryLabel = (TextBlock)primaryButton.Content!;

        var content = new StackPanel
        {
            Spacing = 13,
            Children = { title, detail, usernameInput, codeInput, status, openBot, primary },
        };
        var dialog = new Window
        {
            Title = Copy("Вход через Telegram", "Sign in with Telegram"),
            Width = 430,
            MinWidth = 350,
            MaxWidth = 500,
            MinHeight = 300,
            MaxHeight = 560,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
            Content = DeyttTheme.Card(content, DeyttTheme.Surface2, DeyttTheme.Line, 22,
                new Thickness(22)),
        };

        var step = 0;
        var challenge = "";
        var botUrl = "";
        var needsBotStart = false;
        void SetStatus(string message, Color color)
        {
            status.Text = message;
            status.Foreground = DeyttTheme.Brush(color);
        }

        void SetStep(int value)
        {
            step = value;
            usernameInput.IsVisible = step == 0;
            codeInput.IsVisible = step == 1;
            openBot.IsVisible = step == 1 && needsBotStart;
            primaryLabel.Text = step switch
            {
                0 => Copy("Получить код", "Get code"),
                1 => Copy("Подтвердить код", "Verify code"),
                _ => Copy("Готово", "Done"),
            };
            title.Text = step switch
            {
                0 => Copy("Добавить приложение", "Add this app"),
                1 when needsBotStart => Copy("Открой бота", "Open the bot"),
                1 => Copy("Код отправлен", "Code sent"),
                _ => Copy("Аккаунт подключён", "Account connected"),
            };
            detail.Text = step switch
            {
                0 => Copy("Укажи Telegram username. Бот отправит одноразовый код, чтобы подключить существующую подписку.",
                    "Enter your Telegram username. The bot sends a one-time code to link your subscription."),
                1 when needsBotStart => Copy("Этот аккаунт ещё не начинал чат с ботом. Открой его один раз, затем введи код из сообщения.",
                    "This account has not started the bot chat yet. Open it once, then enter the code from its message."),
                1 => Copy("Введи шестизначный код из Telegram. Он действует пять минут.",
                    "Enter the six-digit code from Telegram. It expires in five minutes."),
                _ => Copy("Профиль и сведения о подписке загружены.", "Profile and subscription details are loaded."),
            };
        }

        openBot.Click += (_, _) =>
        {
            if (botUrl.Length > 0)
                OpenExternal(botUrl);
        };
        primaryButton.Click += async (_, _) =>
        {
            if (step == 2)
            {
                dialog.Close();
                return;
            }

            primaryButton.IsEnabled = false;
            try
            {
                if (step == 0)
                {
                    var username = usernameInput.Text?.Trim() ?? "";
                    SetStatus(Copy("Создаю запрос на вход…", "Starting sign-in…"), DeyttTheme.Blue);
                    var pairing = await _telegramApi.StartPairingAsync(username);
                    if (!dialog.IsVisible)
                        return;
                    challenge = pairing.Challenge;
                    botUrl = pairing.BotUrl;
                    needsBotStart = pairing.Delivery != "sent";
                    SetStep(1);
                    SetStatus(pairing.Delivery switch
                    {
                        "sent" => Copy("Код отправлен в Telegram. Переключаться в бота не нужно.",
                            "The code was sent in Telegram. You do not need to open the bot."),
                        "delivery_failed" => Copy("Не удалось доставить код. Открой бота ниже и повтори.",
                            "The code could not be delivered. Open the bot below and try again."),
                        _ => Copy("Открой бота ниже, чтобы получить код для входа.",
                            "Open the bot below to receive your sign-in code."),
                    }, DeyttTheme.Blue);
                }
                else
                {
                    var code = codeInput.Text?.Trim() ?? "";
                    SetStatus(Copy("Проверяю код и загружаю профиль…", "Verifying the code and loading your profile…"),
                        DeyttTheme.Blue);
                    var pairing = await _telegramApi.VerifyPairingAsync(challenge, code);
                    WindowsSessionStore.Save(pairing.Token);
                    _sessionToken = pairing.Token;
                    _account = pairing.Account;
                    ShowTab(_activeTab);
                    var refreshFailed = false;
                    try
                    {
                        refreshFailed = !await RefreshAccountDataAsync(pairing.Token);
                    }
                    catch (TelegramApiException error) when (error.IsUnauthorized)
                    {
                        ClearLocalSession();
                        throw;
                    }
                    catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
                    {
                        refreshFailed = true;
                        _profileLoadIssue = Copy("Аккаунт подключён, но данные профиля не загрузились.",
                            "Account linked, but profile data could not be loaded.");
                    }

                    ShowTab(_activeTab);
                    SetStep(2);
                    SetStatus(refreshFailed
                        ? Copy("Аккаунт подключён, но не удалось обновить данные профиля.",
                            "Account linked, but the profile could not be refreshed.")
                        : Copy("Аккаунт подключён. Подписка загружена.", "Account linked. Subscription loaded."),
                        DeyttTheme.Mint);
                }
            }
            catch (TelegramApiException error)
            {
                SetStatus(PairingError(error), DeyttTheme.Coral);
                primaryLabel.Text = step == 0
                    ? Copy("Получить код", "Get code")
                    : Copy("Подтвердить код", "Verify code");
            }
            catch (HttpRequestException)
            {
                SetStatus(Copy("Не удалось связаться с сервером. Проверь интернет и повтори.",
                    "Could not reach the server. Check your connection and retry."), DeyttTheme.Coral);
                primaryLabel.Text = step == 0
                    ? Copy("Получить код", "Get code")
                    : Copy("Подтвердить код", "Verify code");
            }
            catch (TaskCanceledException)
            {
                SetStatus(Copy("Запрос занял слишком много времени. Повтори попытку.",
                    "The request timed out. Please retry."), DeyttTheme.Coral);
                primaryLabel.Text = step == 0
                    ? Copy("Получить код", "Get code")
                    : Copy("Подтвердить код", "Verify code");
            }
            catch (CryptographicException)
            {
                SetStatus(Copy("Не удалось безопасно сохранить сеанс Windows.",
                    "Could not safely save the Windows session."), DeyttTheme.Coral);
                primaryLabel.Text = Copy("Подтвердить код", "Verify code");
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                SetStatus(Copy("Не удалось сохранить сеанс в папке данных Windows.",
                    "Could not save the session in Windows app data."), DeyttTheme.Coral);
                primaryLabel.Text = Copy("Подтвердить код", "Verify code");
            }
            finally
            {
                if (dialog.IsVisible)
                    primaryButton.IsEnabled = true;
            }
        };

        SetStep(0);
        await dialog.ShowDialog(this);
    }

    private string PairingError(TelegramApiException error) => error.Code switch
    {
        "invalid_username" => Copy("Проверь Telegram username: от 5 до 32 латинских букв, цифр или _.",
            "Enter a Telegram username with 5–32 Latin letters, digits, or underscores."),
        "rate_limited" or "pairing_rate_limited" => Copy("Слишком много попыток. Подожди немного и повтори.",
            "Too many attempts. Wait a little and retry."),
        "pair_code_invalid" => Copy("Код неверный или истёк. Запроси новый код.",
            "The code is invalid or expired. Request a new one."),
        "pair_code_locked" => Copy("Попытки исчерпаны. Запроси новый код.",
            "Too many code attempts. Request a new one."),
        "account_blocked" => Copy("Этот Telegram-аккаунт заблокирован.",
            "This Telegram account is blocked."),
        "not_registered" => Copy("Сначала открой бота DEYTT в Telegram и создай аккаунт.",
            "Start the DEYTT bot in Telegram and create an account first."),
        "pairing_unavailable" => Copy("Вход через Telegram сейчас недоступен.",
            "Telegram sign-in is currently unavailable."),
        "session_expired" or "session_invalid" => Copy("Сеанс истёк. Войди через Telegram ещё раз.",
            "Your session expired. Sign in with Telegram again."),
        _ => Copy("Сервер не подтвердил вход. Повтори попытку позже.",
            "The server could not confirm sign-in. Please retry later."),
    };

    private string SubscriptionSummary(TelegramSubscription? subscription)
    {
        if (subscription is null)
            return Copy("Профиль загружен. Нет данных о подписке.", "Profile loaded. Subscription details are unavailable.");
        if (!subscription.Active)
            return Copy("Подписка не активна", "Subscription is inactive");

        var details = new List<string>();
        if (subscription.UnlimitedTime)
            details.Add(Copy("Без ограничения по времени", "No time limit"));
        else if (DateTimeOffset.TryParse(subscription.ExpiresAt, out var expiresAt))
            details.Add($"{Copy("до", "until")} {expiresAt.ToLocalTime():d MMM yyyy}");
        if (subscription.DeviceLimit is int deviceLimit)
            details.Add($"{Copy("устройства", "devices")}: {subscription.DevicesUsed?.ToString() ?? "0"}/{deviceLimit}");
        if (subscription.TrafficLimitBytes is > 0 and long trafficLimit)
            details.Add($"{FormatBytes(subscription.TrafficUsedBytes ?? 0)} / {FormatBytes(trafficLimit)}");
        return details.Count > 0 ? string.Join(" · ", details) : Copy("Подписка активна", "Subscription active");
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024)
            return $"{bytes} B";
        var units = new[] { "KB", "MB", "GB", "TB" };
        double value = bytes;
        var unit = -1;
        do
        {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.Length - 1);
        return $"{value:0.#} {units[unit]}";
    }

    private async void SignOut()
    {
        var token = _sessionToken;
        if (token is null)
            return;

        try
        {
            await _telegramApi.LogoutAsync(token);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            // The server has already invalidated this session.
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            ShowInfoDialog(Copy("Не удалось завершить сеанс", "Could not end the session"),
                Copy("Проверь интернет и повтори выход.", "Check your connection and try signing out again."));
            return;
        }

        var localStateCleared = ClearLocalSession();
        ShowTab(_activeTab);
        if (!localStateCleared)
            ShowInfoDialog(Copy("Сеанс завершён", "Session signed out"), _profileLoadIssue ?? "");
    }

    private async void ShowInfoDialog(string title, string message)
    {
        var close = DeyttTheme.PrimaryButton(Copy("Понятно", "Got it"), () => { });
        var dialog = new Window
        {
            Title = title,
            Width = 390,
            Height = 250,
            MinWidth = 320,
            MaxWidth = 460,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
            Content = DeyttTheme.Card(new StackPanel
            {
                Spacing = 14,
                Children =
                {
                    DeyttTheme.TextBlock(title, 22, DeyttTheme.Text, FontWeight.Bold),
                    DeyttTheme.TextBlock(message, 14, DeyttTheme.Muted),
                    close,
                },
            }, DeyttTheme.Surface2, DeyttTheme.Line, 21, new Thickness(20)),
        };
        if (close.Child is Button button)
            button.Click += (_, _) => dialog.Close();
        else
            dialog.PointerPressed += (_, _) => dialog.Close();
        await dialog.ShowDialog(this);
    }

    private void OpenExternal(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception error)
        {
            ShowInfoDialog(Copy("Не удалось открыть ссылку", "Could not open link"), error.Message);
        }
    }

    private string Copy(string russian, string english) => _language == "ru" ? russian : english;
}
