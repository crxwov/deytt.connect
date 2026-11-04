using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Windows.Controls;
using DeyttConnect.Windows.Services;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow : Window
{
    private const double WorkAreaWidthInset = 32;
    private const double WorkAreaHeightInset = 48;

    private enum MainTab { Home, Routes, Profile, Settings, Support, Setup }
    private enum RouteCountryProbeState { Queued, Running, Cancelled, NeedsDisconnect, ServiceUnavailable, Failed }

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
    private Bitmap? _telegramAvatarBitmap;
    private Image? _profileAvatarImage;
    private string? _telegramAvatarSessionToken;
    private TelegramKeysSnapshot? _keysSnapshot;
    private IReadOnlyList<TelegramAppSession> _sessions = [];
    private IReadOnlyList<WindowsRoute> _routes = [];
    private readonly HashSet<string> _expandedRouteCountries = new(StringComparer.Ordinal);
    private readonly LinkedList<RouteCountryProbeRequest> _pendingRouteProbeQueue = new();
    private readonly Dictionary<string, LinkedListNode<RouteCountryProbeRequest>> _pendingRouteProbeNodes =
        new(StringComparer.Ordinal);
    private readonly Dictionary<string, RouteCountryProbeState> _routeCountryProbeStates =
        new(StringComparer.Ordinal);
    private string? _activeRouteProbeCountry;
    private long _routeCountryProbeGeneration;
    private string? _profileLoadIssue;
    private string? _sessionLoadIssue;
    private string? _preferencesIssue;
    private string? _profileActionStatus;
    private Func<Task>? _profileActionRetry;
    private bool _profileRefreshInProgress;
    private bool _profileDevicesExpanded;
    private bool _profileHelpExpanded;
    private readonly WindowsPaymentFlowState _paymentFlow = new();
    private MainTab _activeTab = MainTab.Home;
    private WindowsTunnelSnapshot _vpnSnapshot = new("disconnected", "VPN выключен");
    private IReadOnlyDictionary<string, WindowsRouteProbeResult> _routeProbeResults =
        new Dictionary<string, WindowsRouteProbeResult>(StringComparer.Ordinal);
    private bool _vpnServiceAvailable;
    private bool _vpnActionInProgress;
    private bool _vpnCancelRequested;
    private bool _vpnCancelInProgress;
    private Task<WindowsTunnelSnapshot>? _vpnConnectTask;
    private long _vpnOperationVersion;
    private bool _routeProbeInProgress;
    private HashSet<string> _activeRouteProbeTags = new(StringComparer.Ordinal);
    private bool _routeProbeCancelRequested;
    private readonly string? _initialImportUrl;
    private bool _supportViewActivated;
    private bool _homeMapInitializationAllowed;
    private bool _disableNativeMapInitialization;
    private string? _supportActivatedSessionToken;
    private bool _startupFlowStarted;
    private bool _sessionRestoreComplete;
    private bool _qaFixture;
    private bool _compactLayout;
    private bool _shortCompactLayout;
    private bool _responsiveLayoutInitialized;
    private Border? _homeConnectionCard;
    private Grid? _homeConnectionLayout;
    private Border? _homeConnectionPrimaryAction;
    private RouteGlobeWebView? _routeGlobe;

    public MainWindow() : this((string?)null)
    {
    }

    public MainWindow(string? initialImportUrl)
    {
        InitializeComponent();
        _initialImportUrl = initialImportUrl;
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
        SizeChanged += (_, _) => UpdateResponsiveLayout();
        PageScroll.SizeChanged += (_, _) =>
        {
            UpdatePageHostWidth();
            UpdateHomeColumns();
        };
        Opened += async (_, _) =>
        {
            FitInitialWindowToWorkArea();
            await RunStartupFlowAsync();
        };
        _vpnStatusTimer.Tick += (_, _) => _ = RefreshVpnStatusAsync();
        Closed += (_, _) =>
        {
            _vpnStatusTimer.Stop();
            CancelMapLocationLookup();
            _telegramAvatarBitmap?.Dispose();
            _telegramAvatarBitmap = null;
        };
    }

    internal MainWindow(QaHomeFixture fixture) : this((string?)null)
    {
        _qaFixture = true;
        _startupFlowStarted = true;
        _sessionRestoreComplete = true;
        _language = fixture.Language;
        _selectedRoute = fixture.SelectedRoute;
        // A synthetic fixture must not inherit the real user's IP-region privacy choice.
        _mapRegionEnabled = false;
        _mapRegionConsentGranted = false;
        _mapRegionConsentAsked = false;
        _mapOriginLocation = null;
        _mapEgressLocation = null;
        _mapLocationStateInitialized = false;
        _sessionToken = fixture.SignedIn ? "QA synthetic session marker" : null;
        _account = fixture.Account;
        _keysSnapshot = fixture.Subscription;
        _routes = fixture.Routes;
        if (fixture.ProbeResult is { } probeResult)
            _routeProbeResults = new Dictionary<string, WindowsRouteProbeResult>(StringComparer.Ordinal)
            {
                [probeResult.RouteTag] = probeResult,
            };
        _vpnSnapshot = fixture.Tunnel;
        // The fixture models a local synthetic service so each displayed tunnel state
        // can exercise the same action mapping without contacting the real service.
        _vpnServiceAvailable = true;
        _homeMapInitializationAllowed = true;
        _disableNativeMapInitialization = fixture.DisableNativeMapInitialization;
        _routeProbeInProgress = fixture.RouteProbeInProgress;
        _routeProbeCancelRequested = fixture.RouteProbeCancelRequested;
        if (!string.IsNullOrWhiteSpace(fixture.ExpandedRouteCountry))
        {
            _expandedRouteCountries.Add(fixture.ExpandedRouteCountry);
            if (fixture.RouteProbeInProgress)
                _activeRouteProbeCountry = fixture.ExpandedRouteCountry;
        }
        ShowTab(fixture.Tab switch
        {
            "routes" => MainTab.Routes,
            "profile" => MainTab.Profile,
            "settings" => MainTab.Settings,
            _ => MainTab.Home,
        });
    }

    private void FitInitialWindowToWorkArea()
    {
        var screen = Screens.ScreenFromWindow(this) ?? Screens.Primary;
        if (screen is null || screen.Scaling <= 0 ||
            screen.WorkingArea.Width <= 0 || screen.WorkingArea.Height <= 0)
            return;

        var scale = screen.Scaling;
        var availableWidth = screen.WorkingArea.Width / scale;
        var availableHeight = screen.WorkingArea.Height / scale;
        var width = Math.Min(Width, Math.Max(360, availableWidth - WorkAreaWidthInset));
        var height = Math.Min(Height, Math.Max(320, availableHeight - WorkAreaHeightInset));

        MinWidth = Math.Min(MinWidth, width);
        MinHeight = Math.Min(MinHeight, height);
        Width = width;
        Height = height;

        var pixelWidth = (int)Math.Round(width * scale);
        var pixelHeight = (int)Math.Round(height * scale);
        Position = new PixelPoint(
            screen.WorkingArea.X + Math.Max(0, (screen.WorkingArea.Width - pixelWidth) / 2),
            screen.WorkingArea.Y + Math.Max(0, (screen.WorkingArea.Height - pixelHeight) / 2));
    }

    private async Task RunStartupFlowAsync()
    {
        if (_startupFlowStarted)
            return;
        _startupFlowStarted = true;
        UpdateResponsiveLayout();

        if (OperatingSystem.IsWindows())
            await ShowStartupExternalVpnNoticeAsync();
        if (!IsVisible)
            return;

        using var startupRestoreTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(45));
        try
        {
            await RestoreSessionAsync(startupRestoreTimeout.Token);
        }
        finally
        {
            _sessionRestoreComplete = true;
            if (IsVisible)
                ShowTab(_activeTab);
        }
        if (!IsVisible)
            return;

        if (_initialImportUrl is { Length: > 0 } importUrl)
            await ShowSetupWindowAsync(importUrl);
        else if (_sessionToken is null || _keysSnapshot is { HappAvailable: false })
            await ShowSetupWindowAsync();
        if (!IsVisible)
            return;

        await ShowInitialMapConsentIfNeededAsync();
        if (!IsVisible)
            return;

        await RefreshVpnStatusAsync();
        if (OperatingSystem.IsWindows() && IsVisible)
            _vpnStatusTimer.Start();

        // Resolve the initial account/setup flow before creating the Windows WebView2
        // child HWND. Replacing the Home page during controller creation aborts COM init.
        _homeMapInitializationAllowed = true;
        if (OperatingSystem.IsWindows() && IsVisible && _activeTab == MainTab.Home)
            ShowTab(MainTab.Home);
    }

    private void UpdateShellStatus()
    {
        var connected = IsVpnDisplayConnected();
        var starting = _vpnSnapshot.State is "starting" or "checking" or "degraded" ||
                       (IsVpnTunnelActive(_vpnSnapshot.State) && !connected) || _vpnActionInProgress;
        var failed = _vpnSnapshot.State == "error";
        var tint = connected ? DeyttTheme.Mint : starting ? DeyttTheme.Amber :
            failed ? DeyttTheme.Coral : DeyttTheme.Muted;
        var state = connected
            ? Copy("VPN подключён", "VPN connected")
            : starting
                ? IsVpnTunnelActive(_vpnSnapshot.State)
                    ? Copy("Проверяем VPN-трафик…", "Checking VPN traffic…")
                    : Copy("Подключаемся…", "Connecting…")
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
            MainTab.Routes => Copy("Выбор маршрута", "Route selection"),
            MainTab.Profile => Copy("Аккаунт и подписка", "Account and subscription"),
            MainTab.Settings => Copy("Параметры клиента", "Client settings"),
            MainTab.Support => Copy("Помощь и поддержка", "Help and support"),
            MainTab.Setup => Copy("Настройка подключения", "Connection setup"),
            _ => Copy("Ваше подключение", "Your connection"),
        };
        WorkspaceHeader.IsVisible = false;
        NavSectionTitle.Text = Copy("РАЗДЕЛЫ", "WORKSPACE");
    }

    private bool IsVpnDisplayConnected() =>
        WindowsTunnelHealth.IsFreshlyConnected(_vpnSnapshot, DateTimeOffset.UtcNow);

    private static bool IsVpnTunnelActive(string state) =>
        state is "connected" or "health_checking" or "degraded" or "unknown";

    private void UpdateResponsiveLayout()
    {
        var compact = Bounds.Width < 1040;
        var shortCompact = compact && Bounds.Height < 700;
        if (_responsiveLayoutInitialized && _compactLayout == compact && _shortCompactLayout == shortCompact)
        {
            UpdatePageHostWidth();
            UpdateHomeColumns();
            return;
        }

        var switchedLayout = _responsiveLayoutInitialized;
        var homeLayoutChanged = _responsiveLayoutInitialized && _shortCompactLayout != shortCompact;
        _responsiveLayoutInitialized = true;
        _compactLayout = compact;
        _shortCompactLayout = shortCompact;
        if (ShellGrid.ColumnDefinitions.Count > 0)
            ShellGrid.ColumnDefinitions[0].Width = new GridLength(0);
        SidebarPanel.IsVisible = false;
        BottomNavigationPanel.IsVisible = true;
        SidebarPanel.Padding = compact
            ? new Thickness(11, 14, 11, 12)
            : new Thickness(20, 23, 18, 18);
        SidebarBrandCopy.IsVisible = !compact;
        SidebarBrandMark.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        NavSectionHeader.IsVisible = !compact;
        SidebarNavPanel.Spacing = compact ? 7 : 5;
        SidebarStatusCard.IsVisible = !compact;
        WorkspaceHeader.Padding = compact ? new Thickness(16, 0) : new Thickness(30, 0);
        WorkspaceEyebrow.IsVisible = !compact;
        PageHost.Margin = compact
            ? new Thickness(14, 12, 14, 14)
            : new Thickness(20, 14, 20, 16);

        SetNavigation(HomeNav, NavigationIconKind.Home, Copy("Главная", "Home"), _activeTab == MainTab.Home);
        SetNavigation(RoutesNav, NavigationIconKind.Routes, Copy("Маршруты", "Routes"), _activeTab == MainTab.Routes);
        SetNavigation(ProfileNav, NavigationIconKind.Profile, Copy("Профиль", "Profile"), _activeTab is MainTab.Profile or MainTab.Support);
        SetNavigation(SettingsNav, NavigationIconKind.Settings, Copy("Настройки", "Settings"), _activeTab == MainTab.Settings);
        UpdateBottomNavigation();
        UpdatePageHostWidth();
        UpdateHomeColumns();
        if (switchedLayout && _activeTab is (MainTab.Routes or MainTab.Profile or MainTab.Settings))
            RenderActiveTabPreservingScroll();
        else if (homeLayoutChanged && _activeTab == MainTab.Home)
            RenderActiveTabPreservingScroll();
    }

    private void UpdatePageHostWidth()
    {
        var availableWidth = PageScroll.Bounds.Width - PageHost.Margin.Left - PageHost.Margin.Right;
        if (!double.IsFinite(availableWidth) || availableWidth <= 0)
            return;

        if (double.IsNaN(PageHost.Width) || Math.Abs(PageHost.Width - availableWidth) > 1)
            PageHost.Width = availableWidth;
    }

    private void UpdateHomeConnectionLayout()
    {
        if (_homeConnectionCard is null || _homeConnectionLayout is null ||
            _homeConnectionPrimaryAction is null)
            return;

        _homeConnectionLayout.ColumnDefinitions = new ColumnDefinitions("*");
        _homeConnectionLayout.RowDefinitions = new RowDefinitions("Auto,Auto,Auto");
        _homeConnectionLayout.RowSpacing = _compactLayout ? 10 : 12;
        Grid.SetColumn(_homeConnectionPrimaryAction, 0);
        Grid.SetRow(_homeConnectionPrimaryAction, 2);
        _homeConnectionPrimaryAction.Height = _compactLayout ? 76 : 84;
        _homeConnectionCard.Padding = _compactLayout
            ? new Thickness(18, 18)
            : new Thickness(22, 22);
        _homeConnectionCard.CornerRadius = new CornerRadius(24);
    }

    private void ApplyVisualPreferences()
    {
        AppStarfield.Opacity = _reduceMotion ? 0 : 0.24;
    }

    private Control BuildConnectionCard()
    {
        var signedIn = _sessionToken is not null;
        var restoringSession = !_sessionRestoreComplete && !_qaFixture;
        var subscriptionReady = _keysSnapshot?.HappAvailable == true;
        var profileReady = !string.IsNullOrWhiteSpace(_keysSnapshot?.ProfileJson);
        var connected = IsVpnDisplayConnected();
        var tunnelActive = IsVpnTunnelActive(_vpnSnapshot.State);
        var healthUnknown = tunnelActive && !connected;
        var connecting = _vpnSnapshot.State is "starting" or "checking";
        var stopping = _vpnSnapshot.State == "stopping" || _vpnCancelInProgress;
        var starting = connecting || stopping || _vpnActionInProgress || _routeProbeInProgress || healthUnknown;
        var canCancel = connecting && !_vpnCancelRequested && !_routeProbeInProgress;
        var isError = _vpnSnapshot.State == "error";
        var contents = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*"),
            RowDefinitions = new RowDefinitions("Auto,Auto,Auto"),
            RowSpacing = _compactLayout ? 10 : 12,
        };
        var stateTitle = restoringSession
            ? Copy("Подготавливаем подключение", "Preparing connection")
            : _qaFixture && isError
            ? Copy("Ошибка VPN", "VPN error")
            : !_vpnServiceAvailable && isError
            ? Copy("Статус VPN неизвестен", "VPN status unknown")
            : healthUnknown
            ? Copy("Проверяем VPN-трафик…", "Checking VPN traffic…")
            : connected
            ? Copy("Подключено", "Connected")
            : starting
                ? _routeProbeInProgress
                    ? Copy("Проверяем маршруты…", "Checking routes…")
                    : stopping
                        ? Copy("Останавливаем VPN…", "Stopping VPN…")
                    : healthUnknown || _vpnSnapshot.State is "checking" or "degraded"
                        ? Copy("Проверяем VPN-трафик…", "Verifying VPN traffic…")
                        : Copy("Подключаем VPN…", "Connecting VPN…")
                : Copy("Не подключено", "Not connected");
        var connectionDetail = restoringSession
            ? Copy("Восстанавливаем сохранённую сессию…", "Restoring your saved session…")
            : connected
            ? _qaFixture
                ? Copy("Синтетический статус · туннель не запускался", "Synthetic status · no tunnel was started")
                : $"{RouteTitleForTag(_vpnSnapshot.RouteTag)} · {Copy("проверка трафика пройдена", "traffic verified")}"
            : healthUnknown
                ? _vpnSnapshot.State == "unknown"
                    ? _vpnSnapshot.Detail
                    : Copy("Туннель оставлен включённым; ждём успешную проверку трафика", "Tunnel remains on; waiting for a successful traffic check")
            : starting
                ? _routeProbeInProgress
                    ? Copy("Проверяем HTTPS и скорость каждого выхода", "Measuring HTTPS and speed for each exit")
                    : stopping
                        ? Copy("Завершаем подключение и освобождаем туннель", "Finishing the connection and releasing the tunnel")
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
        var primaryLabel = restoringSession
            ? Copy("Загрузка…", "Loading…")
            : tunnelActive
            ? Copy("Отключить", "Disconnect")
            : starting
                ? canCancel
                    ? Copy("Отменить подключение", "Cancel connection")
                : _routeProbeInProgress
                    ? Copy("Идёт диагностика…", "Diagnostics running…")
                    : stopping
                        ? Copy("Отключаем…", "Disconnecting…")
                        : Copy("Подключение…", "Connecting…")
            : !signedIn
                ? Copy("Войти через Telegram", "Sign in with Telegram")
                : !subscriptionReady || !profileReady
                    ? Copy("Открыть профиль", "Open profile")
                    : !_vpnServiceAvailable
                        ? Copy("Настроить VPN", "Set up VPN")
                        : Copy("Подключиться", "Connect");

        var actionForeground = DeyttTheme.Text;
        var statusDot = new Ellipse
        {
            Width = 8,
            Height = 8,
            Fill = DeyttTheme.Brush(connected ? DeyttTheme.Mint :
                starting ? DeyttTheme.Amber : isError ? DeyttTheme.Coral : DeyttTheme.Muted),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 9, 0),
        };
        var stateCopy = new StackPanel { Spacing = 3 };
        var stateTitleText = DeyttTheme.TextBlock(stateTitle, _compactLayout ? 16 : 18,
            isError ? DeyttTheme.Coral : DeyttTheme.Text,
            FontWeight.SemiBold, wrap: false);
        stateCopy.Children.Add(stateTitleText);
        var connectionDetailText = DeyttTheme.TextBlock(connectionDetail, 11, DeyttTheme.Muted,
            wrap: isError || healthUnknown);
        connectionDetailText.TextTrimming = isError || healthUnknown
            ? TextTrimming.None
            : TextTrimming.CharacterEllipsis;
        ToolTip.SetTip(connectionDetailText, connectionDetail);
        stateCopy.Children.Add(connectionDetailText);
        var status = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        status.Children.Add(statusDot);
        Grid.SetColumn(stateCopy, 1);
        status.Children.Add(stateCopy);

        var actionLabel = DeyttTheme.TextBlock(primaryLabel, _compactLayout ? 19 : 21,
            actionForeground, FontWeight.Bold, DeyttTheme.InterTight, wrap: false);
        actionLabel.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        actionLabel.TextAlignment = TextAlignment.Center;
        AutomationProperties.SetAutomationId(actionLabel, "HomeConnectionActionLabel");

        var primary = DeyttTheme.Action(actionLabel, () =>
        {
            if (_qaFixture || restoringSession)
                return;
            if (canCancel)
            {
                _ = CancelVpnConnectionAsync();
                return;
            }
            if (starting && !healthUnknown)
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
        primary.IsEnabled = !restoringSession && (!starting || canCancel || healthUnknown);
        primary.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        primary.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Stretch;
        primary.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        primary.VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center;
        primary.Padding = new Thickness(16, 12, 16, 12);
        AutomationProperties.SetAutomationId(primary, "HomeConnectionAction");
        status.IsVisible = connected || starting || isError || healthUnknown;
        status.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left;
        status.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        status.Margin = new Thickness(2, 0);
        status.IsHitTestVisible = false;
        AutomationProperties.SetAutomationId(status, "HomeConnectionStatus");
        Grid.SetColumn(status, 0);
        Grid.SetRow(status, 1);
        contents.Children.Add(status);

        var actionPhase = isError ? ConnectionGlassPhase.Error
            : stopping ? ConnectionGlassPhase.Stopping
            : connected ? ConnectionGlassPhase.Connected
            : starting ? ConnectionGlassPhase.Connecting
            : ConnectionGlassPhase.Idle;
        var actionTile = DeyttTheme.ConnectionGlass(primary, actionPhase, _compactLayout ? 76 : 84);
        actionTile.IsVisible = !restoringSession;
        AutomationProperties.SetAutomationId(actionTile, "HomeConnectionActionTile");
        Grid.SetColumn(actionTile, 0);
        Grid.SetRow(actionTile, 2);
        contents.Children.Add(actionTile);
        var routeDetails = BuildHomeRouteDetails();
        Grid.SetColumn(routeDetails, 0);
        Grid.SetRow(routeDetails, 0);
        contents.Children.Add(routeDetails);

        _homeConnectionCard = DeyttTheme.Card(contents, DeyttTheme.Surface2, DeyttTheme.Line, 23,
            new Thickness(19));
        _homeConnectionLayout = contents;
        _homeConnectionPrimaryAction = actionTile;
        UpdateHomeConnectionLayout();
        return _homeConnectionCard;
    }
}
