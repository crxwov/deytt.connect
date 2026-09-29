using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
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
    private bool _mapRegionEnabled;
    private bool _mapRegionConsentGranted;
    private bool _mapRegionConsentAsked;
    private bool _reduceMotion;
    private WindowsNetworkLocation? _mapOriginLocation;
    private WindowsNetworkLocation? _mapEgressLocation;
    private CancellationTokenSource? _mapLocationCancellation;
    private bool _mapLocationStateInitialized;
    private bool _mapOriginLookupAttempted;
    private bool _mapEgressLookupAttempted;
    private bool _mapLocationRequestIsEgress;
    private string? _mapLocationIssue;
    private readonly TelegramApiClient _telegramApi = new();
    private readonly WindowsTunnelClient _tunnelClient = new();
    private readonly DispatcherTimer _vpnStatusTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private string? _sessionToken;
    private TelegramAccount? _account;
    private TelegramKeysSnapshot? _keysSnapshot;
    private IReadOnlyList<TelegramAppSession> _sessions = [];
    private IReadOnlyList<WindowsRoute> _routes = [];
    private readonly HashSet<string> _expandedRouteCountries = new(StringComparer.Ordinal);
    private string? _profileLoadIssue;
    private string? _sessionLoadIssue;
    private string? _preferencesIssue;
    private string? _profileActionStatus;
    private string? _pendingPaymentId;
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
            _mapRegionConsentGranted = preferences.MapRegionConsentGranted;
            _mapRegionConsentAsked = preferences.MapRegionConsentAsked;
            _mapRegionEnabled = preferences.MapRegionEnabled && _mapRegionConsentGranted;
            _reduceMotion = preferences.ReduceMotion;
        }
        ApplyVisualPreferences();
        ConfigureNavigation();
        ShowTab(MainTab.Home);
        Opened += (_, _) => _ = RestoreSessionAsync();
        Opened += (_, _) => _ = RefreshVpnStatusAsync();
        Opened += (_, _) => _ = ShowInitialMapConsentIfNeededAsync();
        _vpnStatusTimer.Tick += (_, _) => _ = RefreshVpnStatusAsync();
        Closed += (_, _) =>
        {
            _vpnStatusTimer.Stop();
            CancelMapLocationLookup();
        };
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
        UpdateShellStatus();
        PageScroll.Offset = new Vector(0, 0);
    }

    private void SetNavigation(Button button, string icon, string label, bool selected)
    {
        var content = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        var glyph = DeyttTheme.TextBlock(icon, 19, selected ? DeyttTheme.Sky : DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.InterTight, wrap: false);
        glyph.Width = 25;
        glyph.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        content.Children.Add(glyph);
        var title = DeyttTheme.TextBlock(label, 14,
            selected ? DeyttTheme.Text : DeyttTheme.Muted,
            selected ? FontWeight.SemiBold : FontWeight.Medium, wrap: false);
        title.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(title, 1);
        content.Children.Add(title);

        button.Content = new Border
        {
            Background = DeyttTheme.Brush(selected ? DeyttTheme.Selected : Colors.Transparent),
            BorderBrush = DeyttTheme.Brush(selected ? DeyttTheme.SelectedLine : Colors.Transparent),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(13, 11),
            Child = content,
        };
    }

    private void UpdateShellStatus()
    {
        var connected = _vpnSnapshot.State == "connected";
        var starting = _vpnSnapshot.State is "starting" or "checking" || _vpnActionInProgress;
        var failed = _vpnSnapshot.State == "error";
        var tint = connected ? DeyttTheme.Mint : starting ? DeyttTheme.Amber :
            failed ? DeyttTheme.Coral : DeyttTheme.Muted;
        var state = connected
            ? Copy("VPN подключён", "VPN connected")
            : starting
                ? Copy("Подключаемся…", "Connecting…")
                : failed
                    ? Copy("Ошибка VPN", "VPN error")
                    : Copy("Не подключено", "Not connected");
        HeaderStatusText.Text = state;
        HeaderStatusText.Foreground = DeyttTheme.Brush(tint);
        HeaderStatusDot.Fill = DeyttTheme.Brush(tint);
        SidebarVpnText.Text = state;
        SidebarStatusDot.Fill = DeyttTheme.Brush(tint);
        SidebarAccountText.Text = _sessionToken is null
            ? Copy("Telegram не подключён", "Telegram not connected")
            : string.IsNullOrWhiteSpace(_account?.Username)
                ? Copy("Аккаунт подключён", "Account connected")
                : $"@{_account.Username}";
        WorkspaceTitle.Text = _activeTab switch
        {
            MainTab.Home => Copy("Ваше подключение", "Your connection"),
            MainTab.Routes => Copy("Маршруты", "Routes"),
            MainTab.Profile => Copy("Аккаунт и подписка", "Account and subscription"),
            MainTab.Settings => Copy("Настройки приложения", "App settings"),
            _ => Copy("Ваше подключение", "Your connection"),
        };
        NavSectionTitle.Text = Copy("РАЗДЕЛЫ", "WORKSPACE");
    }

    private void ApplyVisualPreferences()
    {
        AppStarfield.Opacity = _reduceMotion ? 0 : 0.24;
    }

    private Control BuildHomePage()
    {
        var page = NewPage(Copy("DEYTT CONNECT · ЧАСТНАЯ СЕТЬ", "DEYTT CONNECT · PRIVATE NETWORK"),
            Copy("Подключение", "Connection"));
        page.Children.Add(DeyttTheme.TextBlock(
            Copy("Выберите маршрут и управляйте защищённым подключением.",
                "Choose a route and manage your private connection."),
            14, DeyttTheme.Muted));
        page.Children.Add(DeyttTheme.Spacer(24));

        var overview = new Grid { ColumnDefinitions = new ColumnDefinitions("1.12*,0.88*") };
        var mapColumn = new StackPanel { Spacing = 16, Margin = new Thickness(0, 0, 10, 0) };
        mapColumn.Children.Add(BuildMapCard());
        mapColumn.Children.Add(BuildRouteFlow());
        var connectionColumn = new StackPanel { Spacing = 16, Margin = new Thickness(10, 0, 0, 0) };
        connectionColumn.Children.Add(BuildConnectionCard());
        connectionColumn.Children.Add(BuildQualityStrip());
        connectionColumn.Children.Add(DeyttTheme.Card(new StackPanel
        {
            Spacing = 7,
            Children =
            {
                DeyttTheme.TextBlock(Copy("ТЕКУЩИЙ МАРШРУТ", "CURRENT ROUTE"), 10,
                    DeyttTheme.Muted, FontWeight.SemiBold, DeyttTheme.JetBrainsMono),
                DeyttTheme.Action(
                    DeyttTheme.TextBlock(RouteTitle(), 18, DeyttTheme.Text, FontWeight.SemiBold),
                    () => ShowTab(MainTab.Routes)),
                DeyttTheme.TextBlock(Copy("Нажмите, чтобы выбрать другой выход.",
                    "Select to choose another exit."), 12, DeyttTheme.Muted),
            },
        }, DeyttTheme.Surface, DeyttTheme.Line, 19, new Thickness(17, 14)));
        Grid.SetColumn(connectionColumn, 1);
        overview.Children.Add(mapColumn);
        overview.Children.Add(connectionColumn);
        page.Children.Add(overview);
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
        var map = new RouteMapIllustration
        {
            MinHeight = 230,
            OriginCoordinate = _mapRegionEnabled && _mapOriginLocation is { } origin
                ? new MapCoordinate(origin.Latitude, origin.Longitude)
                : null,
            ExitCoordinate = GetMapExitCoordinate(),
        };
        Grid.SetRow(map, 1);
        contents.Children.Add(map);
        var endpoints = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,*"),
            ColumnSpacing = 14,
            Margin = new Thickness(0, 8, 0, 0),
        };
        var sourceEndpoint = MapEndpoint(
            Copy("ВАША СЕТЬ", "YOUR NETWORK"),
            GetMapOriginLabel(),
            GetMapOriginHint());
        endpoints.Children.Add(sourceEndpoint);
        var exitEndpoint = MapEndpoint(
            Copy("ВЫХОД VPN", "VPN EXIT"),
            RouteTitle(),
            GetMapEgressHint());
        Grid.SetColumn(exitEndpoint, 1);
        endpoints.Children.Add(exitEndpoint);
        Grid.SetRow(endpoints, 2);
        contents.Children.Add(endpoints);

        return new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.MapSurface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(25),
            ClipToBounds = true,
            Padding = new Thickness(18, 16),
            Child = contents,
        };
    }

    private Control MapEndpoint(string title, string value, string hint)
    {
        var stack = new StackPanel { Spacing = 3 };
        stack.Children.Add(DeyttTheme.TextBlock(title, 8, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        stack.Children.Add(DeyttTheme.TextBlock(value, 13, DeyttTheme.Text,
            FontWeight.SemiBold, wrap: false));
        stack.Children.Add(DeyttTheme.TextBlock(hint, 10, DeyttTheme.Muted));
        return stack;
    }

    private string GetMapOriginLabel()
    {
        if (!_mapRegionEnabled)
            return Copy("Место скрыто", "Location hidden");
        if (_mapOriginLocation is { } origin)
            return string.IsNullOrWhiteSpace(origin.PlaceLabel)
                ? Copy("Регион определён", "Region detected")
                : origin.PlaceLabel;
        if (_mapLocationCancellation is not null && !_mapLocationRequestIsEgress)
            return Copy("Определяем регион…", "Looking up region…");
        if (_vpnSnapshot.State == "connected")
            return Copy("Исходная сеть скрыта", "Origin network hidden");
        if (_mapLocationIssue is not null)
            return Copy("Регион недоступен", "Region unavailable");
        return Copy("Ожидает определения", "Waiting for lookup");
    }

    private string GetMapOriginHint()
    {
        if (!_mapRegionEnabled)
            return Copy("отключено в настройках", "disabled in settings");
        if (_mapOriginLocation is not null)
            return Copy("примерно по IP · только в памяти", "approx. by IP · memory only");
        if (_vpnSnapshot.State == "connected")
            return Copy("не GPS · доступно после отключения VPN", "not GPS · available after disconnecting VPN");
        return _mapLocationIssue is not null
            ? Copy("проверьте соединение и включите снова", "check connection and toggle on again")
            : Copy("примерно по IP", "approx. by IP");
    }

    private string GetMapEgressHint()
    {
        if (!_mapRegionEnabled || _vpnSnapshot.State != "connected")
            return Copy("выбранный выход", "selected route");
        if (_mapEgressLocation is { } egress && !string.IsNullOrWhiteSpace(egress.PlaceLabel))
            return $"{Copy("выход по IP", "IP egress")}: {egress.PlaceLabel}";
        if (_mapLocationCancellation is not null && _mapLocationRequestIsEgress)
            return Copy("Определяем выход по IP…", "Looking up IP egress…");
        return _mapLocationIssue is not null
            ? Copy("регион выхода недоступен", "egress region unavailable")
            : Copy("место определяется после подключения", "location resolves after connecting");
    }

    private MapCoordinate? GetMapExitCoordinate()
    {
        if (_vpnSnapshot.State == "connected" && _mapEgressLocation is { } egress)
            return new MapCoordinate(egress.Latitude, egress.Longitude);
        var route = _routes.FirstOrDefault(value => value.Id == _selectedRoute);
        var country = route?.CountryCode ?? (_selectedRoute == "ru-de" ? "RU-DE" : string.Empty);
        return country switch
        {
            "NL" => new MapCoordinate(52.37, 4.90),
            "DE" or "RU-DE" => new MapCoordinate(52.52, 13.40),
            "FI" => new MapCoordinate(60.17, 24.94),
            "RU" => new MapCoordinate(55.75, 37.62),
            _ => null,
        };
    }

    private Control BuildRouteFlow()
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto,*,Auto,*") };
        var originHint = !_mapRegionEnabled
            ? Copy("место скрыто", "location hidden")
            : _mapOriginLocation is not null
                ? Copy("примерно по IP", "approx. by IP")
                : Copy("регион не определён", "region not resolved");
        grid.Children.Add(RouteNode("⌂", Copy("Устройство", "Device"), originHint));
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
        var exitHint = _vpnSnapshot.State == "connected"
            ? _mapEgressLocation?.CountryCode ?? Copy("туннель активен", "VPN active")
            : Copy("выбранный выход", "selected exit");
        var destination = RouteNode("◎", RouteTitle(), exitHint);
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
        page.Children.Add(DeyttTheme.Spacer(24));

        var showDiagnostics = _sessionToken is not null &&
                              _keysSnapshot?.ProfileJson is { Length: > 0 } && _routes.Count > 0;
        var columns = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions(showDiagnostics ? "1.28*,0.72*" : "*"),
        };
        var routeColumn = new StackPanel
        {
            Spacing = 0,
            Margin = showDiagnostics ? new Thickness(0, 0, 10, 0) : new Thickness(0),
        };
        var toolsColumn = new StackPanel
        {
            Spacing = 0,
            Margin = new Thickness(10, 0, 0, 0),
        };

        AddSection(routeColumn, Copy("БЫСТРЫЙ ВЫБОР", "QUICK SELECT"));
        var quick = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*") };
        var autoRoute = _routes.FirstOrDefault(route => route.Id == "auto");
        Control automatic;
        if (autoRoute is not null)
            automatic = RouteOption(autoRoute, Copy("Выберем доступный узел", "Choose an available node"));
        else
            automatic = QuickOption("✦", Copy("Автоподбор", "Auto-select"),
                Copy("Выберем доступный узел", "Choose an available node"), "auto");
        automatic.Margin = new Thickness(3, 3, 8, 3);
        quick.Children.Add(automatic);
        var chainRoute = _routes.FirstOrDefault(route => route.Id == "ru-de");
        Control chain;
        if (chainRoute is not null)
            chain = RouteOption(chainRoute, Copy("Россия → Германия · двойной маршрут", "Russia → Germany · double route"));
        else
            chain = QuickOption("🇷🇺🇩🇪", Copy("LTE + белые списки", "LTE + whitelist"),
                Copy("Россия → Германия · двойной маршрут", "Russia → Germany · double route"), "ru-de");
        chain.Margin = new Thickness(8, 3, 3, 3);
        Grid.SetColumn(chain, 1);
        quick.Children.Add(chain);
        routeColumn.Children.Add(DeyttTheme.Card(quick, DeyttTheme.Surface, DeyttTheme.Line, 20,
            new Thickness(8)));
        AddSection(routeColumn, Copy("СТРАНЫ И ПРОТОКОЛЫ", "COUNTRIES AND PROTOCOLS"));

        if (_sessionToken is null)
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
            routeColumn.Children.Add(DeyttTheme.Card(new StackPanel
            {
                Spacing = 10,
                Children =
                {
                    DeyttTheme.TextBlock(_profileLoadIssue ?? Copy("Активная подписка не найдена", "No active subscription found"),
                        18, DeyttTheme.Text, FontWeight.SemiBold),
                    DeyttTheme.TextBlock(Copy("Обнови профиль после подключения тарифа.", "Refresh your profile after activating a plan."),
                        13, DeyttTheme.Muted),
                    DeyttTheme.PrimaryButton(Copy("Обновить профиль", "Refresh profile"),
                        () => _ = RefreshSignedInAccountAsync()),
                },
            }, DeyttTheme.Surface2, DeyttTheme.Line, 20, new Thickness(18)));
        }
        else
        {
            var countryColumns = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*") };
            var firstCountryColumn = new StackPanel { Spacing = 12, Margin = new Thickness(0, 0, 8, 0) };
            var secondCountryColumn = new StackPanel { Spacing = 12, Margin = new Thickness(8, 0, 0, 0) };
            var countries = _routes.Where(route => route.CountryCode is "NL" or "RU" or "DE" or "FI" or "AWG_UNKNOWN")
                .GroupBy(route => route.CountryCode).ToArray();
            for (var index = 0; index < countries.Length; index++)
            {
                var country = countries[index];
                var first = country.First();
                var expanded = _expandedRouteCountries.Contains(country.Key);
                var selectedCountryRoute = country.FirstOrDefault(route => route.Id == _selectedRoute);
                var summary = selectedCountryRoute is not null
                    ? $"{selectedCountryRoute.ProtocolName} · {Copy("выбран", "selected")}"
                    : Copy("доступны протоколы", "protocols available");
                var headerLabels = new StackPanel { Spacing = 3 };
                headerLabels.Children.Add(DeyttTheme.TextBlock(
                    $"{first.Flag}   {RouteCountryName(first)}", 16,
                    DeyttTheme.Text, FontWeight.SemiBold));
                headerLabels.Children.Add(DeyttTheme.TextBlock(summary, 11, DeyttTheme.Muted));
                var header = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
                header.Children.Add(headerLabels);
                var disclosure = DeyttTheme.TextBlock(expanded ? "⌃" : "⌄", 16, DeyttTheme.Sky,
                    FontWeight.SemiBold, wrap: false);
                disclosure.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                Grid.SetColumn(disclosure, 1);
                header.Children.Add(disclosure);
                var headerButton = DeyttTheme.Action(header, () =>
                {
                    if (!_expandedRouteCountries.Add(country.Key))
                        _expandedRouteCountries.Remove(country.Key);
                    ShowTab(MainTab.Routes);
                });
                headerButton.Padding = new Thickness(0);
                var countryContents = new StackPanel { Spacing = 8, Children = { headerButton } };
                if (expanded)
                {
                    var options = new StackPanel { Spacing = 2 };
                    foreach (var route in country)
                        options.Children.Add(RouteOption(route, null));
                    countryContents.Children.Add(options);
                }
                var countryCard = DeyttTheme.Card(
                    countryContents, DeyttTheme.Surface, DeyttTheme.Line, 18, new Thickness(12, 11));
                (index % 2 == 0 ? firstCountryColumn : secondCountryColumn).Children.Add(countryCard);
            }
            countryColumns.Children.Add(firstCountryColumn);
            Grid.SetColumn(secondCountryColumn, 1);
            countryColumns.Children.Add(secondCountryColumn);
            routeColumn.Children.Add(countryColumns);
        }

        if (showDiagnostics)
        {
            AddSection(toolsColumn, Copy("ДИАГНОСТИКА", "DIAGNOSTICS"));
            var diagnostic = new StackPanel { Spacing = 11 };
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
                            : Copy("Каждый выход проверяется отдельно. Результаты появятся рядом с маршрутами.",
                                "Each exit is checked separately. Results appear beside the routes."),
                13, DeyttTheme.Muted));
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
            toolsColumn.Children.Add(DeyttTheme.Card(diagnostic, DeyttTheme.Surface2,
                DeyttTheme.Line, 20, new Thickness(18)));
        }

        Grid.SetColumn(toolsColumn, 1);
        columns.Children.Add(routeColumn);
        if (showDiagnostics)
            columns.Children.Add(toolsColumn);
        page.Children.Add(columns);
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
        var columns = new Grid { ColumnDefinitions = new ColumnDefinitions("1.02*,0.98*") };
        var accountColumn = new StackPanel { Spacing = 0, Margin = new Thickness(0, 0, 10, 0) };
        var managementColumn = new StackPanel { Spacing = 0, Margin = new Thickness(10, 0, 0, 0) };
        var account = _account;
        var signedIn = _sessionToken is not null;
        AddSection(accountColumn, Copy("АККАУНТ", "ACCOUNT"));
        var accountName = account is null
            ? (signedIn ? Copy("Telegram подключён", "Telegram connected") : Copy("Telegram не подключён", "Telegram not connected"))
            : string.IsNullOrWhiteSpace(account.FirstName) ? $"@{account.Username}" : account.FirstName;
        var accountNote = account is null
            ? (signedIn ? Copy("Не удалось обновить профиль", "Could not refresh profile") : Copy("Подписка и устройства", "Subscription and devices"))
            : $"@{account.Username}";
        accountColumn.Children.Add(SettingsEntry("D", accountName, accountNote,
            signedIn ? Copy("выйти", "sign out") : "›", () =>
            {
                if (signedIn)
                    SignOut();
                else
                    ShowSignInDialog();
            }));
        AddSection(accountColumn, Copy("ПОДПИСКА", "SUBSCRIPTION"));
        var subscription = account?.Subscription;
        var hasSubscription = subscription?.Active == true;
        var subscriptionTitle = hasSubscription
            ? subscription!.TariffName ?? Copy("Подписка активна", "Subscription active")
            : Copy("Нет активной подписки", "No active subscription");
        var subscriptionDetail = signedIn
            ? _profileLoadIssue ?? SubscriptionSummary(subscription)
            : Copy("Войдите через Telegram, чтобы загрузить тариф и данные об использовании.",
                "Sign in with Telegram to load your plan and usage.");
        accountColumn.Children.Add(DeyttTheme.Card(new StackPanel
        {
            Spacing = 5,
            Children =
            {
                DeyttTheme.TextBlock(subscriptionTitle, 18,
                    DeyttTheme.Text, FontWeight.SemiBold),
                DeyttTheme.TextBlock(subscriptionDetail, 13, DeyttTheme.Muted),
            },
        }, DeyttTheme.Surface, DeyttTheme.Line, 22, new Thickness(18)));
        accountColumn.Children.Add(DeyttTheme.Spacer(8));
        accountColumn.Children.Add(SettingsEntry("₽", Copy("Тарифы и оплата", "Plans and payment"),
            Copy("Стоимость подтвердит сервер до оформления", "The server confirms the total before checkout"),
            Copy("выбрать", "choose"), () => _ = ShowPlanPickerAsync()));
        if (_pendingPaymentId is not null)
        {
            accountColumn.Children.Add(DeyttTheme.Spacer(6));
            accountColumn.Children.Add(SettingsEntry("↻", Copy("Проверить платёж", "Check payment"),
                Copy("Счёт ожидает подтверждения", "Payment is awaiting confirmation"),
                Copy("проверить", "check"), () => _ = CheckPaymentStatusAsync()));
        }
        if (!string.IsNullOrWhiteSpace(_profileActionStatus))
        {
            accountColumn.Children.Add(DeyttTheme.Spacer(8));
            accountColumn.Children.Add(DeyttTheme.Card(
                DeyttTheme.TextBlock(_profileActionStatus, 12, DeyttTheme.Muted),
                DeyttTheme.Surface2, DeyttTheme.Line, 14, new Thickness(13, 10)));
        }

        AddSection(managementColumn, Copy("УСТРОЙСТВА И СЕАНСЫ", "DEVICES AND SESSIONS"));
        var devices = _keysSnapshot?.HappDevices ?? [];
        var deviceNote = signedIn
            ? $"{devices.Count} {Copy("устройств Happ", "Happ devices")} · {_keysSnapshot?.AmneziaClients ?? 0} {Copy("клиентов AWG", "AWG clients")}"
            : Copy("Подключения и доступ", "Connections and access");
        var deviceList = new StackPanel { Spacing = 3 };
        deviceList.Children.Add(DeyttTheme.TextBlock(deviceNote, 12, DeyttTheme.Muted));
        deviceList.Children.Add(DeyttTheme.Spacer(6));
        if (signedIn && devices.Count == 0)
            deviceList.Children.Add(DeyttTheme.TextBlock(Copy("Активных устройств Happ пока нет.",
                "There are no active Happ devices."), 13, DeyttTheme.Muted));
        foreach (var device in devices)
        {
            var deviceName = string.Join(" · ", new[] { device.Model, device.Os }.Where(value => !string.IsNullOrWhiteSpace(value)));
            if (deviceName.Length == 0)
                deviceName = Copy("Устройство Happ", "Happ device");
            var state = device.Blocked ? Copy("заблокировано", "blocked") : Copy("активно", "active");
            deviceList.Children.Add(SettingsEntry("H", deviceName, $"{state} · {FormatLastSeen(device.LastSeen)}",
                device.Blocked ? Copy("вернуть", "restore") : Copy("отозвать", "revoke"),
                () => _ = ToggleHappDeviceAsync(device)));
        }
        managementColumn.Children.Add(DeyttTheme.Card(deviceList, DeyttTheme.Surface,
            DeyttTheme.Line, 19, new Thickness(15, 13)));
        managementColumn.Children.Add(DeyttTheme.Spacer(8));
        var sessionList = new StackPanel { Spacing = 2 };
        sessionList.Children.Add(DeyttTheme.TextBlock(Copy("СЕАНСЫ ПРИЛОЖЕНИЯ", "APP SESSIONS"),
            9, DeyttTheme.Muted, FontWeight.SemiBold, DeyttTheme.JetBrainsMono));
        if (!signedIn)
        {
            sessionList.Children.Add(SettingsEntry("↗", Copy("Войди, чтобы управлять сеансами", "Sign in to manage sessions"),
                Copy("Можно завершить старые входы на других устройствах", "End old app sessions on other devices"),
                "›", ShowSignInDialog));
        }
        else if (_sessionLoadIssue is not null)
        {
            sessionList.Children.Add(SettingsEntry("↻", Copy("Сеансы не загружены", "Sessions unavailable"),
                _sessionLoadIssue, Copy("повторить", "retry"), () => _ = RefreshSignedInAccountAsync()));
        }
        else if (_sessions.Count == 0)
        {
            sessionList.Children.Add(DeyttTheme.TextBlock(Copy("Активных сеансов приложения нет.",
                "There are no active app sessions."), 12, DeyttTheme.Muted));
        }
        foreach (var session in _sessions)
        {
            sessionList.Children.Add(SettingsEntry("D", session.Label,
                session.Current
                    ? Copy("Это устройство · текущий сеанс", "This device · current session")
                    : $"{Copy("Вход", "Signed in")}: {FormatLastSeen(session.CreatedAt)}",
                session.Current ? Copy("текущий", "current") : Copy("управлять", "manage"),
                () => _ = ManageSessionAsync(session)));
        }
        managementColumn.Children.Add(DeyttTheme.Card(sessionList, DeyttTheme.Surface,
            DeyttTheme.Line, 19, new Thickness(15, 13)));
        managementColumn.Children.Add(DeyttTheme.Spacer(8));
        managementColumn.Children.Add(SettingsEntry("+", Copy("Обновить список", "Refresh list"),
            Copy("Заново загрузить устройства и доступ", "Reload devices and access"), "↻", () =>
            {
                if (signedIn)
                    _ = RefreshSignedInAccountAsync();
                else
                    ShowSignInDialog();
            }));
        AddSection(managementColumn, Copy("ДОСТУП", "ACCESS"));
        managementColumn.Children.Add(SettingsEntry("↻", Copy("Сбросить ключи", "Reset keys"),
            Copy("Отозвать текущие ключи выбранного типа", "Revoke current keys by type"),
            Copy("управлять", "manage"), () => _ = ShowResetChoicesAsync()));
        managementColumn.Children.Add(DeyttTheme.Spacer(6));
        managementColumn.Children.Add(SettingsEntry("?", Copy("Поддержка и документы", "Support and documents"),
            Copy("Открыть центр помощи DEYTT", "Open the DEYTT help center"), "↗",
            () => OpenExternal("https://deytt.space/info")));

        columns.Children.Add(accountColumn);
        Grid.SetColumn(managementColumn, 1);
        columns.Children.Add(managementColumn);
        page.Children.Add(columns);
        return page;
    }

    private Control BuildSettingsPage()
    {
        var page = NewPage(Copy("ПОД ВАС", "MAKE IT YOURS"), Copy("Настройки", "Settings"));
        var columns = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*") };
        var generalColumn = new StackPanel { Spacing = 0, Margin = new Thickness(0, 0, 10, 0) };
        var privacyColumn = new StackPanel { Spacing = 0, Margin = new Thickness(10, 0, 0, 0) };
        AddSection(generalColumn, Copy("ЯЗЫК И АККАУНТ", "LANGUAGE AND ACCOUNT"));
        generalColumn.Children.Add(SettingsEntry("Aa", Copy("Язык приложения", "App language"),
            _language == "ru" ? "Русский" : "English", "›", ToggleLanguage));
        generalColumn.Children.Add(DeyttTheme.Spacer(6));
        var accountDetail = _sessionToken is null
            ? Copy("Подписка и устройства", "Subscription and devices")
            : string.IsNullOrWhiteSpace(_account?.Username)
                ? Copy("Аккаунт подключён", "Account connected")
                : $"@{_account!.Username}";
        generalColumn.Children.Add(SettingsEntry("✓", Copy("Войти через Telegram", "Sign in with Telegram"),
            accountDetail,
            "›", () =>
            {
                if (_sessionToken is null)
                    ShowSignInDialog();
                else
                    ShowTab(MainTab.Profile);
            }, true));

        AddSection(generalColumn, Copy("ПОДКЛЮЧЕНИЕ", "CONNECTION"));
        generalColumn.Children.Add(SettingsEntry("↗", Copy("Раздельное туннелирование", "Split tunneling"),
            Copy("Сайты из подписки · все протоколы", "Subscription sites · all protocols"), "›",
            () => ShowInfoDialog(Copy("Без VPN", "Without VPN"),
                Copy("Список сайтов загрузится вместе с подпиской.", "The bypass list loads with your subscription."))));
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↻", Copy("Проверка маршрута", "Route check"),
            $"HTTP {_probeMethod} · {Copy("быстрая проверка, загрузка 1 с", "fast check, 1 s download")}", "›", ToggleProbeMethod));
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↗", Copy("Служба VPN", "VPN service"),
            _vpnServiceAvailable
                ? Copy("Служба установлена · нажмите для проверки", "Service installed · click to repair")
                : Copy("Требуются права администратора", "Administrator approval required"), "›",
            () => _ = InstallVpnServiceAsync()));
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↗", Copy("Бот DEYTT", "DEYTT bot"),
            Copy("Другие действия в Telegram", "More actions in Telegram"), "›", () => OpenExternal("https://t.me/deyttbot")));

        AddSection(privacyColumn, Copy("ВИД И ПРИВАТНОСТЬ", "APPEARANCE AND PRIVACY"));
        privacyColumn.Children.Add(ToggleEntry(Copy("Регион на карте", "Region on map"),
            Copy("IP · ipinfo.io · не GPS · только в памяти",
                "IP via ipinfo.io · no GPS · memory only"),
            _mapRegionEnabled, UpdateMapRegionEnabledAsync));
        privacyColumn.Children.Add(DeyttTheme.Spacer(8));
        privacyColumn.Children.Add(ToggleEntry(Copy("Упростить фон", "Reduce background effects"),
            Copy("Скрывает декоративный звёздный фон", "Hides the decorative starfield"),
            _reduceMotion, value =>
            {
                _reduceMotion = value;
                return Task.CompletedTask;
            }));

        AddSection(privacyColumn, Copy("ОБНОВЛЕНИЯ", "UPDATES"));
        privacyColumn.Children.Add(SettingsEntry("↻", Copy("Проверить обновления", "Check for updates"),
            Copy("Текущая версия приложения", "Current application version"), "›",
            () => OpenExternal("https://github.com/crxwov/deytt.connect/releases/latest")));
        if (_preferencesIssue is not null)
        {
            privacyColumn.Children.Add(DeyttTheme.Spacer(10));
            privacyColumn.Children.Add(DeyttTheme.TextBlock(_preferencesIssue, 12, DeyttTheme.Coral));
        }
        columns.Children.Add(generalColumn);
        Grid.SetColumn(privacyColumn, 1);
        columns.Children.Add(privacyColumn);
        page.Children.Add(columns);
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

    private Control ToggleEntry(string title, string subtitle, bool value, Func<bool, Task> update)
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
        var button = DeyttTheme.Action(grid, () => _ = ApplyToggleAsync(!value, update));
        return DeyttTheme.Card(button, DeyttTheme.Surface, DeyttTheme.Line, 22, new Thickness(18, 8));
    }

    private async Task ApplyToggleAsync(bool value, Func<bool, Task> update)
    {
        await update(value);
        ApplyVisualPreferences();
        SavePreferences();
        ShowTab(MainTab.Settings);
    }

    private async Task UpdateMapRegionEnabledAsync(bool enabled)
    {
        if (enabled && !_mapRegionConsentGranted)
        {
            var accepted = await ConfirmMapRegionConsentAsync();
            _mapRegionConsentAsked = true;
            if (!accepted)
                return;
            _mapRegionConsentGranted = true;
        }

        _mapRegionEnabled = enabled;
        _mapLocationIssue = null;
        if (!enabled)
        {
            CancelMapLocationLookup();
            _mapOriginLocation = null;
            _mapEgressLocation = null;
            _mapOriginLookupAttempted = false;
            _mapEgressLookupAttempted = false;
            _mapLocationStateInitialized = false;
            return;
        }

        _mapOriginLookupAttempted = false;
        _mapEgressLookupAttempted = false;
        _mapLocationStateInitialized = true;
        StartMapLocationLookup(_vpnSnapshot.State == "connected");
    }

    private async Task ShowInitialMapConsentIfNeededAsync()
    {
        if (!OperatingSystem.IsWindows() || _mapRegionConsentAsked)
            return;

        var accepted = await ConfirmMapRegionConsentAsync();
        _mapRegionConsentAsked = true;
        _mapRegionConsentGranted = accepted;
        _mapRegionEnabled = accepted;
        _mapLocationStateInitialized = false;
        SavePreferences();
        ShowTab(_activeTab);
        if (accepted)
            _ = RefreshVpnStatusAsync();
    }

    private Task<bool> ConfirmMapRegionConsentAsync() => ConfirmDialogAsync(
        Copy("Показывать ваш примерный регион?", "Show your approximate region?"),
        Copy("Точка определяется по публичному IP через ipinfo.io — это не GPS и на выбор маршрута не влияет. Запрос видит внешний сервис; приложение не сохраняет IP или координаты, а держит точку только в памяти, пока открыто.",
            "The point is estimated from your public IP through ipinfo.io. This is not GPS and does not affect route selection. The external service receives the request; the app does not save your IP or coordinates and keeps the point in memory only while open."),
        Copy("Показывать на карте", "Show on map"),
        Copy("Не сейчас", "Not now"));

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

        var wasConnected = _vpnSnapshot.State == "connected";
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
            UpdateMapLocationAfterVpnAction(wasConnected);
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

        var wasConnected = _vpnSnapshot.State == "connected";
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
            UpdateMapLocationAfterVpnAction(wasConnected);
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
            UpdateMapLocationForVpnState(previousSnapshot.State, _vpnSnapshot.State);
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

    private void UpdateMapLocationForVpnState(string previousState, string currentState)
    {
        if (!_mapRegionEnabled || !_mapRegionConsentGranted || currentState is "starting" or "checking")
            return;

        var previousConnected = previousState == "connected";
        var currentConnected = currentState == "connected";
        if (!_mapLocationStateInitialized)
        {
            _mapLocationStateInitialized = true;
            StartMapLocationLookup(currentConnected);
            return;
        }
        if (previousConnected == currentConnected)
            return;

        CancelMapLocationLookup();
        _mapLocationIssue = null;
        _mapEgressLocation = null;
        _mapEgressLookupAttempted = false;
        if (currentConnected)
        {
            StartMapLocationLookup(useTunnel: true);
            return;
        }
        if (_mapOriginLocation is null)
        {
            _mapOriginLookupAttempted = false;
            StartMapLocationLookup(useTunnel: false);
        }
    }

    private void UpdateMapLocationAfterVpnAction(bool wasConnected)
    {
        if (!_mapRegionEnabled || !_mapRegionConsentGranted ||
            _vpnSnapshot.State is "starting" or "checking")
            return;
        var isConnected = _vpnSnapshot.State == "connected";
        if (wasConnected && isConnected)
        {
            CancelMapLocationLookup();
            _mapEgressLocation = null;
            _mapEgressLookupAttempted = false;
            _mapLocationIssue = null;
            _mapLocationStateInitialized = true;
            StartMapLocationLookup(useTunnel: true);
            return;
        }
        UpdateMapLocationForVpnState(wasConnected ? "connected" : "disconnected",
            isConnected ? "connected" : "disconnected");
    }

    private void StartMapLocationLookup(bool useTunnel)
    {
        if (!_mapRegionEnabled || !_mapRegionConsentGranted || _mapLocationCancellation is not null)
            return;
        if (useTunnel ? _mapEgressLookupAttempted : _mapOriginLookupAttempted)
            return;

        if (useTunnel)
            _mapEgressLookupAttempted = true;
        else
            _mapOriginLookupAttempted = true;
        _mapLocationRequestIsEgress = useTunnel;
        _mapLocationIssue = null;
        var cancellation = new CancellationTokenSource();
        _mapLocationCancellation = cancellation;
        _ = LoadMapLocationAsync(useTunnel, cancellation);
        RenderActiveTabPreservingScroll();
    }

    private async Task LoadMapLocationAsync(bool useTunnel, CancellationTokenSource cancellation)
    {
        try
        {
            var location = await IpNetworkLocationClient.FetchAsync(cancellation.Token);
            if (!cancellation.IsCancellationRequested && _mapRegionEnabled && _mapRegionConsentGranted &&
                (useTunnel == (_vpnSnapshot.State == "connected")))
            {
                if (useTunnel)
                    _mapEgressLocation = location;
                else
                    _mapOriginLocation = location;
            }
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
        }
        catch (Exception error) when (error is HttpRequestException or IOException or JsonException or OperationCanceledException)
        {
            if (!cancellation.IsCancellationRequested)
                _mapLocationIssue = Copy("Не удалось определить регион по IP.", "Could not resolve the IP region.");
        }
        finally
        {
            if (ReferenceEquals(_mapLocationCancellation, cancellation))
            {
                _mapLocationCancellation = null;
                _mapLocationRequestIsEgress = false;
                RenderActiveTabPreservingScroll();
            }
            cancellation.Dispose();
        }
    }

    private void CancelMapLocationLookup()
    {
        var cancellation = _mapLocationCancellation;
        _mapLocationCancellation = null;
        _mapLocationRequestIsEgress = false;
        cancellation?.Cancel();
    }

    private bool _vpnStatusRefreshRunning;

    private async Task InstallVpnServiceAsync()
    {
        if (!OperatingSystem.IsWindows())
        {
            ShowInfoDialog(Copy("Только для Windows", "Windows only"),
                Copy("Установка VPN-службы доступна после запуска полного Windows-пакета.",
                    "VPN service setup is available in the full Windows package."));
            return;
        }
        if (_vpnActionInProgress)
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
            try
            {
                _sessions = await _telegramApi.GetSessionsAsync(token);
                _sessionLoadIssue = null;
            }
            catch (TelegramApiException error) when (error.IsUnauthorized)
            {
                throw;
            }
            catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
            {
                _sessions = [];
                _sessionLoadIssue = Copy("Проверь интернет и повтори обновление.",
                    "Check your connection and refresh again.");
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

    private async Task ShowPlanPickerAsync()
    {
        var token = _sessionToken;
        if (token is null)
        {
            ShowSignInDialog();
            return;
        }

        try
        {
            _profileActionStatus = Copy("Загружаем планы…", "Loading plans…");
            ShowTab(MainTab.Profile);
            var account = await _telegramApi.GetAccountAsync(token);
            if (_sessionToken != token)
                return;
            _account = account;
            _profileActionStatus = null;
            ShowTab(MainTab.Profile);

            var subscription = account.Subscription;
            if (subscription?.PaidActive == true && subscription.UnlimitedTime)
            {
                var contact = await ChooseOptionAsync(
                    Copy("Бессрочная подписка", "Lifetime subscription"),
                    Copy("Продлевать её не нужно. Если хотите изменить условия, напишите в поддержку.",
                        "No renewal is needed. Contact support if you want to change your plan."),
                    [(Copy("Открыть поддержку", "Open support"), Copy("Telegram", "Telegram"), "support")]);
                if (contact is not null)
                    OpenExternal("https://t.me/deyttbot");
                return;
            }

            if (subscription?.PaidActive == true && subscription.DeviceLimit is null)
            {
                var contact = await ChooseOptionAsync(
                    Copy("План без лимита устройств", "Unlimited device plan"),
                    Copy("Поддержка поможет продлить его с сохранением индивидуальных условий.",
                        "Support can renew it while preserving your custom terms."),
                    [(Copy("Открыть поддержку", "Open support"), Copy("Telegram", "Telegram"), "support")]);
                if (contact is not null)
                    OpenExternal("https://t.me/deyttbot");
                return;
            }

            if (subscription?.PaidActive == true)
            {
                var change = await ChooseOptionAsync(
                    Copy("Управление подпиской", "Manage subscription"),
                    Copy("Выберите, что хотите изменить.", "Choose what you want to change."),
                    [
                        (Copy("Продлить срок", "Extend subscription"), Copy("Добавить месяцы к текущему плану", "Add months to your plan"), "add_time"),
                        (Copy("Добавить устройства", "Add devices"), Copy("Увеличить лимит подключений", "Increase your device limit"), "add_devices"),
                    ]);
                if (change == "add_time")
                    await ChooseSubscriptionDurationAsync(token);
                else if (change == "add_devices")
                {
                    var currentLimit = Math.Max(1, subscription.DeviceLimit ?? 1);
                    var maximum = Math.Max(0, 10 - currentLimit);
                    if (maximum == 0)
                    {
                        ShowInfoDialog(Copy("Лимит устройств", "Device limit"),
                            Copy("Для текущего плана уже достигнут доступный лимит. Изменить условия поможет поддержка.",
                                "Your plan has reached the available device limit. Contact support to change it."));
                        return;
                    }
                    var extra = await ShowNumberDialogAsync(Copy("Сколько добавить?", "How many to add?"),
                        Copy("Количество дополнительных устройств", "Additional device slots"), 1, maximum);
                    if (extra is not null)
                        await CreateQuoteAndCheckoutAsync(token, "add_devices", null, null, null, extra);
                }
                return;
            }

            var tariffs = await _telegramApi.GetTariffsAsync();
            if (_sessionToken != token)
                return;
            if (tariffs.Count == 0)
            {
                ShowInfoDialog(Copy("Планы временно недоступны", "Plans are unavailable"),
                    Copy("Попробуйте снова позже или напишите в поддержку.",
                        "Try again later or contact support."));
                return;
            }

            var choices = tariffs.Select(tariff =>
                ($"{tariff.Name} · {tariff.Devices} {Copy("устр.", "devices")} · {tariff.Months} {Copy("мес.", "months")} · {tariff.Rubles} ₽",
                    Copy("Точную сумму подтвердит сервер", "The server confirms the final total"),
                    tariff.Code)).ToList();
            choices.Add((Copy("Свой план", "Custom plan"),
                Copy("Выберите число устройств и срок", "Choose the number of devices and duration"), "custom"));
            var selectedPlan = await ChooseOptionAsync(Copy("Выберите план", "Choose a plan"),
                Copy("Сначала получите расчёт. Оплата начнётся только после подтверждения суммы.",
                    "Review a server quote first. Payment starts only after you confirm the total."), choices);
            if (selectedPlan is null)
                return;
            if (selectedPlan == "custom")
            {
                var devices = await ShowNumberDialogAsync(Copy("Количество устройств", "Number of devices"),
                    Copy("Можно выбрать от 1 до 10 устройств.", "Choose 1 to 10 devices."), 1, 10);
                if (devices is null)
                    return;
                var months = await ShowNumberDialogAsync(Copy("Срок подписки", "Subscription duration"),
                    Copy("Количество месяцев от 1 до 36.", "Choose from 1 to 36 months."), 1, 36);
                if (months is not null)
                    await CreateQuoteAndCheckoutAsync(token, "custom", null, months, devices, null);
                return;
            }
            await CreateQuoteAndCheckoutAsync(token, "preset", selectedPlan, null, null, null);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            _profileActionStatus = null;
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Не удалось загрузить планы", "Could not load plans"),
                Copy("Проверьте интернет и повторите попытку. Сервер не подтвердил запрос.",
                    "Check your connection and retry. The server did not confirm the request."));
        }
    }

    private async Task ChooseSubscriptionDurationAsync(string token)
    {
        var choices = new List<(string Title, string Detail, string Value)>
        {
            (Copy("1 месяц", "1 month"), Copy("Добавить к подписке", "Add to your subscription"), "1"),
            (Copy("3 месяца", "3 months"), Copy("Добавить к подписке", "Add to your subscription"), "3"),
            (Copy("6 месяцев", "6 months"), Copy("Добавить к подписке", "Add to your subscription"), "6"),
            (Copy("12 месяцев", "12 months"), Copy("Добавить к подписке", "Add to your subscription"), "12"),
            (Copy("Другой срок", "Custom duration"), Copy("От 1 до 36 месяцев", "From 1 to 36 months"), "custom"),
        };
        var selected = await ChooseOptionAsync(Copy("Срок подписки", "Subscription duration"),
            Copy("Выберите срок, затем проверьте расчёт перед оплатой.",
                "Choose a duration and review the quote before paying."), choices);
        if (selected is null)
            return;
        int? months = selected == "custom"
            ? await ShowNumberDialogAsync(Copy("Количество месяцев", "Number of months"),
                Copy("Можно выбрать от 1 до 36 месяцев.", "Choose from 1 to 36 months."), 1, 36)
            : int.Parse(selected, System.Globalization.CultureInfo.InvariantCulture);
        if (months is not null && _sessionToken == token)
            await CreateQuoteAndCheckoutAsync(token, "add_time", null, months, null, null);
    }

    private async Task CreateQuoteAndCheckoutAsync(
        string token,
        string kind,
        string? plan,
        int? months,
        int? devices,
        int? extra)
    {
        try
        {
            _profileActionStatus = Copy("Рассчитываем стоимость…", "Calculating the total…");
            ShowTab(MainTab.Profile);
            var quote = await _telegramApi.GetQuoteAsync(token, kind, plan, months, devices, extra);
            if (_sessionToken != token)
                return;
            _profileActionStatus = null;
            ShowTab(MainTab.Profile);

            var duration = kind == "add_devices"
                ? Copy("до конца текущей подписки", "until the current subscription ends")
                : Copy($"{quote.Months} мес.", $"{quote.Months} months");
            var amount = $"{quote.Rubles} ₽ · ⭐ {quote.Stars}";
            var detail = $"{quote.Name}\n{quote.Devices} {Copy("устройств", "devices")} · {duration}\n{amount}";
            var method = await ChooseOptionAsync(Copy("Подтвердите сумму", "Confirm the total"), detail,
                [
                    (Copy("Оплатить картой", "Pay by card"),
                        Copy($"Защищённая страница · {quote.Rubles} ₽", $"Secure checkout · {quote.Rubles} ₽"), "platega"),
                    (Copy("Telegram Stars", "Telegram Stars"),
                        Copy($"Оплата через Telegram · ⭐ {quote.Stars}", $"Pay through Telegram · ⭐ {quote.Stars}"), "stars"),
                ]);
            if (method is null)
                return;

            _profileActionStatus = Copy("Готовим защищённый счёт…", "Preparing secure checkout…");
            ShowTab(MainTab.Profile);
            var checkout = await _telegramApi.CreateCheckoutAsync(token, kind, method, plan, months, devices, extra);
            if (_sessionToken != token)
                return;
            _pendingPaymentId = checkout.ExternalId;
            _profileActionStatus = checkout.ExternalId is null
                ? Copy("Счёт создан. Откройте страницу оплаты, чтобы продолжить.",
                    "Checkout is ready. Open the payment page to continue.")
                : Copy("Счёт создан. Статус можно проверить в профиле.",
                    "Checkout is ready. You can check its status from Profile.");
            ShowTab(MainTab.Profile);

            var nextStep = await ChooseOptionAsync(Copy("Счёт готов", "Checkout is ready"),
                Copy("Откройте защищённую страницу оплаты. Приложение не запрашивает данные карты.",
                    "Open the secure payment page. The app never asks for card details."),
                [
                    (Copy("Открыть оплату", "Open payment"), Copy("Перейти в браузер", "Continue in your browser"), "open"),
                    .. (checkout.ExternalId is null
                        ? Array.Empty<(string Title, string Detail, string Value)>()
                        : new[] { (Copy("Проверить статус", "Check status"), Copy("Запросить подтверждение у сервера", "Ask the server for confirmation"), "check") }),
                ]);
            if (nextStep == "open")
                OpenExternal(checkout.PaymentUrl);
            else if (nextStep == "check")
                await CheckPaymentStatusAsync();
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            _profileActionStatus = null;
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Не удалось подготовить оплату", "Could not prepare payment"),
                Copy("Сервер не подтвердил действие. Проверьте интернет и попробуйте снова.",
                    "The server did not confirm the request. Check your connection and try again."));
        }
    }

    private async Task CheckPaymentStatusAsync()
    {
        var token = _sessionToken;
        var externalId = _pendingPaymentId;
        if (token is null || externalId is null)
        {
            ShowInfoDialog(Copy("Нет ожидающего платежа", "No pending payment"),
                Copy("Создайте счёт в разделе тарифов и оплаты.", "Create a checkout from Plans and payment."));
            return;
        }

        _profileActionStatus = Copy("Проверяем платёж…", "Checking payment…");
        ShowTab(MainTab.Profile);
        try
        {
            var status = await _telegramApi.GetPaymentStatusAsync(token, externalId);
            if (_sessionToken != token)
                return;
            if (status == "paid")
            {
                _pendingPaymentId = null;
                _profileActionStatus = Copy("Оплата подтверждена", "Payment confirmed");
                await RefreshAccountDataAsync(token);
                ShowTab(MainTab.Profile);
                ShowInfoDialog(Copy("Оплата подтверждена", "Payment confirmed"),
                    Copy("Данные подписки обновлены.", "Your subscription details have been refreshed."));
            }
            else
            {
                _profileActionStatus = Copy("Платёж ещё не подтверждён. Проверьте позже.",
                    "Payment is not confirmed yet. Check again later.");
                ShowTab(MainTab.Profile);
            }
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            _profileActionStatus = Copy("Не удалось проверить платёж. Повторите попытку позже.",
                "Could not check the payment. Try again later.");
            ShowTab(MainTab.Profile);
        }
    }

    private async Task ShowResetChoicesAsync()
    {
        var token = _sessionToken;
        if (token is null)
        {
            ShowSignInDialog();
            return;
        }

        var selected = await ChooseOptionAsync(Copy("Что сбросить?", "What should be reset?"),
            Copy("Сброс отзовёт ключи и может отключить активные устройства.",
                "Resetting keys revokes access and may disconnect active devices."),
            [
                (Copy("Все ключи", "All keys"), Copy("Happ и AmneziaWG", "Happ and AmneziaWG"), "all"),
                ("AmneziaWG", Copy("Ключи этого протокола", "Keys for this protocol"), "awg"),
                ("Happ", Copy("Ключи этого приложения", "Keys for this app"), "happ"),
            ]);
        if (selected is null)
            return;
        var scopeLabel = selected == "all" ? Copy("все ключи", "all keys") : selected == "awg" ? "AmneziaWG" : "Happ";
        if (!await ConfirmDialogAsync(Copy($"Сбросить {scopeLabel}?", $"Reset {scopeLabel}?"),
                Copy("Текущие ключи будут отозваны. Это может временно отключить устройства.",
                    "Current keys will be revoked. This can temporarily disconnect devices."),
                Copy("Сбросить", "Reset")))
            return;

        _profileActionStatus = Copy("Сбрасываем ключи…", "Resetting keys…");
        ShowTab(MainTab.Profile);
        try
        {
            await _telegramApi.ResetKeysAsync(token, selected);
            if (_sessionToken != token)
                return;
            _profileActionStatus = Copy("Ключи сброшены. Обновляем аккаунт…", "Keys reset. Refreshing account…");
            await RefreshAccountDataAsync(token);
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Ключи сброшены", "Keys reset"),
                Copy("Доступ обновлён. При необходимости переподключите устройства.",
                    "Access has been refreshed. Reconnect your devices if needed."));
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            _profileActionStatus = null;
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Не удалось сбросить ключи", "Could not reset keys"),
                Copy("Сервер не подтвердил сброс. Обновите профиль и попробуйте снова.",
                    "The server did not confirm the reset. Refresh your profile and retry."));
        }
    }

    private async Task<string?> ChooseOptionAsync(
        string title,
        string detail,
        IReadOnlyList<(string Title, string Detail, string Value)> options)
    {
        string? selected = null;
        Window? dialog = null;
        var choices = new StackPanel { Spacing = 7 };
        foreach (var option in options)
        {
            var labels = new StackPanel { Spacing = 3 };
            labels.Children.Add(DeyttTheme.TextBlock(option.Title, 15, DeyttTheme.Text, FontWeight.SemiBold));
            if (!string.IsNullOrWhiteSpace(option.Detail))
                labels.Children.Add(DeyttTheme.TextBlock(option.Detail, 12, DeyttTheme.Muted));
            var button = DeyttTheme.Action(labels, () =>
            {
                selected = option.Value;
                dialog?.Close();
            });
            button.Padding = new Thickness(13, 10);
            choices.Children.Add(new Border
            {
                Background = DeyttTheme.Brush(DeyttTheme.Surface),
                BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(13),
                Child = button,
            });
        }
        var cancel = DeyttTheme.Action(DeyttTheme.TextBlock(Copy("Отмена", "Cancel"), 13,
            DeyttTheme.Muted, FontWeight.SemiBold), () => dialog?.Close());
        cancel.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        cancel.Margin = new Thickness(0, 3, 0, 0);
        choices.Children.Add(cancel);
        dialog = new Window
        {
            Title = title,
            Width = 520,
            MinWidth = 380,
            MaxWidth = 560,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
            Content = DeyttTheme.Card(new StackPanel
            {
                Spacing = 12,
                Children =
                {
                    DeyttTheme.TextBlock(title, 21, DeyttTheme.Text, FontWeight.Bold),
                    DeyttTheme.TextBlock(detail, 13, DeyttTheme.Muted),
                    choices,
                },
            }, DeyttTheme.Surface2, DeyttTheme.Line, 19, new Thickness(20)),
        };
        await dialog.ShowDialog(this);
        return selected;
    }

    private async Task<int?> ShowNumberDialogAsync(string title, string detail, int minimum, int maximum)
    {
        int? selected = null;
        Window? dialog = null;
        var value = new NumericUpDown
        {
            Minimum = minimum,
            Maximum = maximum,
            Increment = 1,
            Value = minimum,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
        };
        var cancel = DeyttTheme.Action(DeyttTheme.TextBlock(Copy("Отмена", "Cancel"), 13,
            DeyttTheme.Muted, FontWeight.SemiBold), () => dialog?.Close());
        cancel.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        var confirm = DeyttTheme.PrimaryButton(Copy("Продолжить", "Continue"), () => { });
        if (confirm.Child is Button confirmButton)
            confirmButton.Click += (_, _) =>
            {
                if (value.Value is { } number)
                    selected = decimal.ToInt32(number);
                dialog?.Close();
            };
        dialog = new Window
        {
            Title = title,
            Width = 420,
            MinWidth = 340,
            MaxWidth = 460,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
            Content = DeyttTheme.Card(new StackPanel
            {
                Spacing = 13,
                Children =
                {
                    DeyttTheme.TextBlock(title, 21, DeyttTheme.Text, FontWeight.Bold),
                    DeyttTheme.TextBlock(detail, 13, DeyttTheme.Muted),
                    value,
                    cancel,
                    confirm,
                },
            }, DeyttTheme.Surface2, DeyttTheme.Line, 19, new Thickness(20)),
        };
        await dialog.ShowDialog(this);
        return selected;
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

    private async Task<bool> ConfirmDialogAsync(string title, string message, string confirmLabel,
        string? cancelLabel = null)
    {
        var confirmed = false;
        var cancel = DeyttTheme.Action(DeyttTheme.TextBlock(cancelLabel ?? Copy("Отмена", "Cancel"), 14,
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

    private async Task ManageSessionAsync(TelegramAppSession session)
    {
        var detail = session.Current
            ? Copy("Это текущий сеанс на этом устройстве.", "This is the current session on this device.")
            : Copy("Этот сеанс можно завершить. VPN-ключи останутся действительными.",
                "You can end this session. VPN keys will remain valid.");
        detail += $"\n{Copy("Вход", "Signed in")}: {FormatLastSeen(session.CreatedAt)}";
        if (!string.IsNullOrWhiteSpace(session.ExpiresAt))
            detail += $"\n{Copy("Действует до", "Valid until")}: {FormatLastSeen(session.ExpiresAt)}";

        if (session.Current)
        {
            ShowInfoDialog(session.Label, detail);
            return;
        }
        if (!await ConfirmDialogAsync(Copy("Завершить сеанс?", "End this session?"), detail,
                Copy("Завершить", "End session")))
            return;

        var token = _sessionToken;
        if (token is null)
        {
            ShowSignInDialog();
            return;
        }
        try
        {
            await _telegramApi.RevokeSessionAsync(token, session.Id);
            if (_sessionToken != token)
                return;
            await RefreshAccountDataAsync(token);
            ShowTab(MainTab.Profile);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            ShowInfoDialog(Copy("Не удалось завершить сеанс", "Could not end session"),
                Copy("Сервер не подтвердил действие. Обнови список и повтори попытку.",
                    "The server did not confirm the action. Refresh the list and try again."));
        }
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
        _sessions = [];
        _routes = [];
        _selectedRoute = "auto";
        _pendingPaymentId = null;
        _profileActionStatus = null;
        _sessionLoadIssue = null;
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
                _language, _selectedRoute, _probeMethod, _mapRegionEnabled, _reduceMotion,
                _mapRegionConsentGranted, _mapRegionConsentAsked));
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
            MinWidth = 320,
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
