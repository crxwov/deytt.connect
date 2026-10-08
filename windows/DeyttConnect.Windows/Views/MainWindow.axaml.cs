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
    private sealed record RouteCountryProbeRequest(string CountryCode, string[] RouteTags);

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

    private void ConfigureNavigation()
    {
        HomeNav.Click += (_, _) => RequestNavigation(MainTab.Home);
        RoutesNav.Click += (_, _) => RequestNavigation(MainTab.Routes);
        ProfileNav.Click += (_, _) => RequestNavigation(MainTab.Profile);
        SettingsNav.Click += (_, _) => RequestNavigation(MainTab.Settings);
        BottomHomeNav.Click += (_, _) => RequestNavigation(MainTab.Home);
        BottomRoutesNav.Click += (_, _) => RequestNavigation(MainTab.Routes);
        BottomProfileNav.Click += (_, _) => RequestNavigation(MainTab.Profile);
        BottomSettingsNav.Click += (_, _) => RequestNavigation(MainTab.Settings);
    }

    private void RequestNavigation(MainTab destination)
    {
        if (_activeTab == MainTab.Setup)
        {
            _activeSetupView?.Cancel();
            return;
        }

        ShowTab(destination);
    }

    private void ShowTab(MainTab tab)
    {
        var previousTab = _activeTab;
        if (previousTab == MainTab.Routes && tab != MainTab.Routes)
            SuspendRouteCountryProbes();
        if (previousTab == MainTab.Support && tab != MainTab.Support)
        {
            _supportView?.Deactivate();
            _supportViewActivated = false;
        }

        _activeTab = tab;
        _profileAvatarImage = null;
        PageHost.Children.Clear();
        PageHost.Children.Add(tab switch
        {
            MainTab.Home => BuildHomePage(),
            MainTab.Routes => BuildRoutesPage(),
            MainTab.Profile => BuildProfilePage(),
            MainTab.Settings => BuildSettingsPage(),
            MainTab.Support => BuildSupportPage(),
            MainTab.Setup => BuildSetupPage(),
            _ => BuildHomePage(),
        });
        SetNavigation(HomeNav, NavigationIconKind.Home, Copy("Главная", "Home"), tab == MainTab.Home);
        SetNavigation(RoutesNav, NavigationIconKind.Routes, Copy("Маршруты", "Routes"), tab == MainTab.Routes);
        SetNavigation(ProfileNav, NavigationIconKind.Profile, Copy("Профиль", "Profile"), tab is MainTab.Profile or MainTab.Support);
        SetNavigation(SettingsNav, NavigationIconKind.Settings, Copy("Настройки", "Settings"), tab == MainTab.Settings);
        UpdateBottomNavigation();
        UpdateShellStatus();
        if (tab == MainTab.Support && _supportView is not null &&
            (!_supportViewActivated || !string.Equals(_supportActivatedSessionToken, _sessionToken,
                StringComparison.Ordinal)))
        {
            _supportView?.Activate(_sessionToken);
            _supportViewActivated = true;
            _supportActivatedSessionToken = _sessionToken;
        }
        UpdateResponsiveLayout();
        PageScroll.Offset = new Vector(0, 0);
    }

    private void SetNavigation(Button button, NavigationIconKind icon, string label, bool selected)
    {
        ToolTip.SetTip(button, label);
        Avalonia.Automation.AutomationProperties.SetName(button, label);
        var content = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions(_compactLayout ? "*" : "Auto,*"),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
        };
        var glyph = new NavigationIcon(icon, selected);
        glyph.HorizontalAlignment = _compactLayout
            ? Avalonia.Layout.HorizontalAlignment.Center
            : Avalonia.Layout.HorizontalAlignment.Left;
        glyph.Margin = new Thickness(0, 0, _compactLayout ? 0 : 12, 0);
        content.Children.Add(glyph);
        if (!_compactLayout)
        {
            var title = DeyttTheme.TextBlock(label, 14,
                selected ? DeyttTheme.Text : DeyttTheme.Muted,
                selected ? FontWeight.SemiBold : FontWeight.Medium, wrap: false);
            title.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(title, 1);
            content.Children.Add(title);
        }

        button.MinWidth = _compactLayout ? 52 : 0;
        button.MinHeight = _compactLayout ? 52 : 0;
        var navigationSurface = new Border
        {
            Background = DeyttTheme.Brush(selected ? DeyttTheme.Selected : Colors.Transparent),
            BorderBrush = DeyttTheme.Brush(selected ? DeyttTheme.SelectedLine : Colors.Transparent),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12),
            Padding = _compactLayout ? new Thickness(0, 8) : new Thickness(13, 11),
            Child = content,
        };
        navigationSurface.Classes.Add("nav-surface");
        button.Content = navigationSurface;
    }

    private void UpdateBottomNavigation()
    {
        SetBottomNavigation(BottomHomeNav, NavigationIconKind.Home, Copy("Главная", "Home"), _activeTab == MainTab.Home);
        SetBottomNavigation(BottomRoutesNav, NavigationIconKind.Routes, Copy("Маршруты", "Routes"), _activeTab == MainTab.Routes);
        SetBottomNavigation(BottomProfileNav, NavigationIconKind.Profile, Copy("Профиль", "Profile"),
            _activeTab is MainTab.Profile or MainTab.Support);
        SetBottomNavigation(BottomSettingsNav, NavigationIconKind.Settings, Copy("Настройки", "Settings"),
            _activeTab == MainTab.Settings);
    }

    private static void SetBottomNavigation(Button button, NavigationIconKind icon, string label, bool selected)
    {
        button.CornerRadius = new CornerRadius(15);
        ToolTip.SetTip(button, label);
        var content = new StackPanel
        {
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            Spacing = 2,
        };
        var glyph = new NavigationIcon(icon, selected);
        content.Children.Add(glyph);
        var title = DeyttTheme.TextBlock(label, 11,
            selected ? DeyttTheme.Text : DeyttTheme.Muted,
            selected ? FontWeight.SemiBold : FontWeight.Medium, wrap: false);
        title.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        content.Children.Add(title);
        button.MinHeight = 58;
        Avalonia.Automation.AutomationProperties.SetName(button, label);
        ToolTip.SetTip(button, label);
        var navigationSurface = new Border
        {
            Background = DeyttTheme.Brush(selected ? DeyttTheme.Selected : Colors.Transparent),
            BorderBrush = DeyttTheme.Brush(selected ? DeyttTheme.SelectedLine : Colors.Transparent),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(15),
            Padding = new Thickness(8, 4),
            Child = content,
        };
        navigationSurface.Classes.Add("nav-surface");
        button.Content = navigationSurface;
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
        if (IsVpnDisplayConnected())
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
        if (IsVpnDisplayConnected())
            return Copy("не GPS · доступно после отключения VPN", "not GPS · available after disconnecting VPN");
        return _mapLocationIssue is not null
            ? Copy("проверьте соединение и включите снова", "check connection and toggle on again")
            : Copy("примерно по IP", "approx. by IP");
    }

    private string GetMapEgressHint()
    {
        if (!_mapRegionEnabled)
            return Copy("VPN-трафик проверен · регион скрыт", "VPN traffic verified · region hidden");
        if (_mapEgressLocation is { } egress && !string.IsNullOrWhiteSpace(egress.PlaceLabel))
            return $"{Copy("выход по IP", "IP egress")}: {egress.PlaceLabel}";
        if (_mapLocationCancellation is not null && _mapLocationRequestIsEgress)
            return Copy("Определяем выход по IP…", "Looking up IP egress…");
        return _mapLocationIssue is not null
            ? Copy("регион выхода недоступен", "egress region unavailable")
            : Copy("место определяется после подключения", "location resolves after connecting");
    }

    private string SelectedExitPlaceLabel(WindowsRoute? route)
    {
        if (route is null)
            return RouteTitle();

        if (route.CountryCode == "IT")
        {
            if ((route.Protocol is "AWG31") &&
                !string.IsNullOrWhiteSpace(route.ProfileName))
                return route.ProfileName.Trim();

            return Copy("Италия", "Italy");
        }

        return route.CountryCode switch
        {
            "NL" => Copy("Амстердам", "Amsterdam"),
            "DE" or "RU-DE" => Copy("Франкфурт", "Frankfurt"),
            "FI" => Copy("Хельсинки", "Helsinki"),
            "RU" => Copy("Санкт-Петербург", "Saint Petersburg"),
            _ => RouteTitle(),
        };
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

        var actionIsPrimary = !connected && !starting && !isError;
        var actionForeground = connected || actionIsPrimary ? DeyttTheme.Background : DeyttTheme.Text;
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

        var actionTile = new Border
        {
            IsVisible = !restoringSession,
            Height = _compactLayout ? 76 : 84,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Bottom,
            BorderThickness = new Thickness(1),
            BorderBrush = DeyttTheme.Brush(connected ? DeyttTheme.Mint : isError ? DeyttTheme.Coral :
                starting ? DeyttTheme.Line : DeyttTheme.Sky),
            CornerRadius = new CornerRadius(20),
            Background = connected
                ? DeyttTheme.Brush(DeyttTheme.Mint)
                : actionIsPrimary
                    ? new LinearGradientBrush
                    {
                        StartPoint = new RelativePoint(0, 0.5, RelativeUnit.Relative),
                        EndPoint = new RelativePoint(1, 0.5, RelativeUnit.Relative),
                        GradientStops =
                        {
                            new GradientStop(DeyttTheme.Sky, 0),
                            new GradientStop(DeyttTheme.Mint, 1),
                        },
                    }
                    : DeyttTheme.Brush(DeyttTheme.Surface2),
            Child = primary,
        };
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

    private Control BuildRoutesPage()
    {
        var page = NewPage(Copy("ВЫХОДЫ · ВЫБОР · ДИАГНОСТИКА", "EXITS · SELECTION · DIAGNOSTICS"), Copy("Маршруты", "Routes"));
        page.Children.Add(DeyttTheme.TextBlock(
            HasProbeSpeedToken()
                ? Copy("Выберите выход. Проверка покажет пинг и скорость каждого маршрута.",
                    "Choose an exit. Diagnostics show each route's latency and speed.")
                : Copy("Выберите выход. Войдите через Telegram для проверки скорости.",
                    "Choose an exit. Sign in with Telegram to measure speed."),
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

        var hasAmneziaRoute = _routes.Any(route => IsAwgProtocol(route.Protocol));
        var hasSession = _sessionToken is not null;
        var hasActiveSubscription = _account?.Subscription?.Active == true;
        {
            var keys = _keysSnapshot;
            var message = hasAmneziaRoute
                ? Copy("Профиль AmneziaWG доступен в списке маршрутов ниже.",
                    "The AmneziaWG profile is available in the route list below.")
                : _profileRefreshInProgress
                    ? Copy("Проверяю доступность профилей AmneziaWG…", "Checking AmneziaWG profile availability…")
                : !hasSession
                    ? Copy("Войдите через Telegram, чтобы получить профиль AmneziaWG.",
                        "Sign in with Telegram to get an AmneziaWG profile.")
                    : keys is null
                    ? Copy("Не удалось проверить доступность профиля AmneziaWG. Обновите профиль.",
                        "Could not check AmneziaWG profile availability. Refresh the profile.")
                    : keys.AmneziaActive && keys.AmneziaClients > 0
                        ? Copy($"На аккаунте есть активные ключи: {keys.AmneziaClients}. Профиль маршрута не загрузился.",
                            $"The account has {keys.AmneziaClients} active keys, but the AmneziaWG route profile did not load.")
                        : keys.AmneziaActive
                        ? Copy("Подписка отмечена активной, но сервер не сообщил активные ключи AmneziaWG.",
                            "The subscription is marked active, but the server reports no active AmneziaWG keys.")
                        : !hasActiveSubscription
                            ? Copy("Активируйте подписку, чтобы получить профиль AmneziaWG.",
                                "Activate a subscription to get an AmneziaWG profile.")
                        : !keys.HappAvailable
                            ? Copy("Подписка активна, но сервер пока не сообщил доступный профиль AmneziaWG.",
                                "The subscription is active, but the server has not reported an AmneziaWG profile yet.")
                        : Copy("Сервер пока не сообщил об активной выдаче AmneziaWG для аккаунта.",
                            "The server has not reported active AmneziaWG provisioning for this account yet.");
            var refreshAmnezia = DeyttTheme.PrimaryButton(
                Copy("Обновить профиль", "Refresh profile"),
                () => _ = RefreshSignedInAccountAsync());
            refreshAmnezia.IsEnabled = hasSession && !_profileRefreshInProgress;
            if (refreshAmnezia.Child is Button refreshButton)
                AutomationProperties.SetAutomationId(refreshButton, "AmneziaRefreshProfile");
            routeColumn.Children.Add(DeyttTheme.Card(new StackPanel
            {
                Spacing = 9,
                Children =
                {
                    DeyttTheme.TextBlock("AmneziaWG", 17, DeyttTheme.Text, FontWeight.SemiBold),
                    DeyttTheme.TextBlock(message, 13, DeyttTheme.Muted),
                    refreshAmnezia,
                },
            }, DeyttTheme.Surface2, DeyttTheme.Line, 18, new Thickness(16)));
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
            var countryList = new StackPanel { Spacing = 10 };
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
                headerLabels.Children.Add(DeyttTheme.TextBlock(summary, 11,
                    DeyttTheme.Muted));
                if (!expanded && RouteCountryProbeStatus(country.Key) is { } probeStatus)
                    headerLabels.Children.Add(DeyttTheme.TextBlock(probeStatus, 10,
                        _routeCountryProbeStates.GetValueOrDefault(country.Key) == RouteCountryProbeState.Failed
                            ? DeyttTheme.Coral : DeyttTheme.Muted));
                var header = new Grid
                {
                    ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"),
                    ColumnSpacing = 12,
                    MinHeight = 54,
                };
                var flag = RouteFlagVisual.Create(country.Key, 42, 28);
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
                headerButton.Padding = new Thickness(2, 3);
                AutomationProperties.SetAutomationId(headerButton, $"RouteCountry-{country.Key}");
                var countryContents = new StackPanel { Spacing = 0, Children = { headerButton } };
                if (expanded)
                {
                    countryContents.Children.Add(DeyttTheme.Hairline());
                    var options = new StackPanel { Spacing = 4, Margin = new Thickness(0, 9, 0, 2) };
                    foreach (var route in countryRoutes)
                        options.Children.Add(RouteOption(route, quality));
                    countryContents.Children.Add(options);

                    if (RouteCountryProbeStatus(country.Key) is { } expandedProbeStatus)
                    {
                        var statusColor = _routeCountryProbeStates.GetValueOrDefault(country.Key) ==
                            RouteCountryProbeState.Failed ? DeyttTheme.Coral : DeyttTheme.Muted;
                    {
                        var expandedStatus = DeyttTheme.TextBlock(expandedProbeStatus, 11, statusColor);
                        expandedStatus.Margin = new Thickness(4, 5, 4, 0);
                        countryContents.Children.Add(expandedStatus);
                    }
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
                countryList.Children.Add(DeyttTheme.Card(countryContents,
                    selectedCountry ? DeyttTheme.Surface2 : DeyttTheme.Surface,
                    selectedCountry ? DeyttTheme.SelectedLine : DeyttTheme.Line,
                    18, new Thickness(12, 8)));
            }
            routeColumn.Children.Add(countryList);
        }

        columns.Children.Add(routeColumn);
        page.Children.Add(columns);
        return page;
    }

    private static int RouteProtocolSortOrder(WindowsRoute route) => route.Protocol switch
    {
        "VLESS" => 0,
        "TROJAN" => 1,
        "HYSTERIA2" => 2,
        "AWG31" => 3,
        _ => 4,
    };

    private enum RouteQualityGrade { Unrated, Good, Medium, Poor }
    private sealed record RouteCountryQuality(string? LatencySummary,
        IReadOnlyDictionary<string, RouteQualityGrade> Grades, string? BestTag);

    private RouteCountryQuality CountryRouteQuality(IReadOnlyList<WindowsRoute> routes)
    {
        var measured = routes
            .Select(route => _routeProbeResults.GetValueOrDefault(route.Tag))
            .Where(sample => sample?.LatencyMilliseconds is >= 0 and <= 8_000)
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
            ColumnDefinitions = new ColumnDefinitions("3,2*,1,*,1,*,Auto"),
            ColumnSpacing = 11,
            MinHeight = 56,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
        };
        var protocolColor = route.Protocol switch
        {
            "VLESS" => DeyttTheme.Sky,
            "TROJAN" => DeyttTheme.Blue,
            "HYSTERIA2" => DeyttTheme.Mint,
            "AWG31" => DeyttTheme.Amber,
            _ => DeyttTheme.Muted,
        };
        var protocolMark = new Border
        {
            Width = 3,
            Height = 40,
            CornerRadius = new CornerRadius(2),
            Background = DeyttTheme.Brush(protocolColor),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
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
            "AWG31" => AwgProtocolTitle(route.Protocol),
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
            "complete" when measured.LatencyMilliseconds is null => measured.Error ?? Copy("Нет ответа", "No response"),
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

        var latencyText = measured?.LatencyMilliseconds is { } probeLatency ? $"{probeLatency} ms" : "— ms";
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

        var latencyMetric = RouteMeasurement(Copy("ОТКЛИК", "RESPONSE"), latencyText,
            quality.Grades.GetValueOrDefault(route.Tag) switch
            {
                RouteQualityGrade.Good => DeyttTheme.Mint,
                RouteQualityGrade.Medium => DeyttTheme.Amber,
                RouteQualityGrade.Poor => DeyttTheme.Coral,
                _ => DeyttTheme.Text,
            }, measured?.Stage is "latency" or "retry", _reduceMotion);
        var latencyHelpText = Copy(
            "Время до первого ответа по HTTPS через выбранный маршрут. Это не ICMP-пинг.",
            "Time to the first HTTPS response through the selected route. This is not an ICMP ping.");
        var latencyMetricLabel = latencyMetric.Children.OfType<TextBlock>().First();
        ToolTip.SetTip(latencyMetricLabel, latencyHelpText);
        AutomationProperties.SetHelpText(latencyMetricLabel, latencyHelpText);
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
        if (route.CountryCode == "RU-DE")
        {
            icon = RouteFlagVisual.Create("RU-DE", 48, 30);
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

    private Control BuildProfilePage()
    {
        var page = NewPage(Copy("ВАШ АККАУНТ", "YOUR ACCOUNT"), Copy("Профиль", "Profile"));
        var signedIn = _sessionToken is not null;
        var account = _account;
        var loading = signedIn && (_profileRefreshInProgress ||
            (_keysSnapshot is null && _profileLoadIssue is null));
        var accountError = signedIn && !loading &&
            (account is null || (_keysSnapshot is null && _profileLoadIssue is not null));
        var compact = _compactLayout;
        var columns = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions(compact ? "*" : "1.02*,0.98*"),
            RowDefinitions = new RowDefinitions(compact ? "Auto,Auto" : "Auto"),
        };
        var accountColumn = new StackPanel
        {
            Spacing = 0,
            Margin = compact ? new Thickness(0) : new Thickness(0, 0, 10, 0),
        };
        var managementColumn = new StackPanel
        {
            Spacing = 10,
              Margin = compact ? new Thickness(0, 8, 0, 0) : new Thickness(10, 0, 0, 0),
        };

        accountColumn.Children.Add(BuildProfileIdentityCard(account, signedIn, loading));
        var planPaymentEntry = SettingsEntry("₽", Copy("Тарифы и оплата", "Plans and payment"),
            Copy("Стоимость подтвердит сервер до оформления", "The server confirms the total before checkout"),
            Copy("открыть", "open"), () => _ = ShowPlanPickerAsync(), emphasis: compact && signedIn);
        if (compact && signedIn)
        {
            // Keep the account's main billing action beside its identity on short windows.
            accountColumn.Children.Add(DeyttTheme.Spacer(8));
            accountColumn.Children.Add(planPaymentEntry);
        }
        AddSection(accountColumn, Copy("ПОДПИСКА", "SUBSCRIPTION"));
        var subscription = account?.Subscription;
        var subscriptionTitle = !signedIn
            ? Copy("Подключите Telegram", "Connect Telegram")
            : loading
                ? Copy("Загружаем данные подписки…", "Loading subscription…")
                : accountError
                    ? Copy("Не удалось загрузить данные аккаунта.", "Could not load account data.")
                    : subscription is null
                        ? Copy("Данные подписки недоступны", "Subscription details unavailable")
                        : subscription.Active
                            ? subscription.TariffName ?? Copy("Подписка активна", "Subscription active")
                            : Copy("Нет активной подписки", "No active subscription");
        var subscriptionDetail = !signedIn
            ? Copy("Войдите, чтобы увидеть тариф и срок действия.",
                "Sign in to see your plan and expiry date.")
            : loading
                ? Copy("Проверяем тариф и доступ…", "Checking your plan and access…")
                : accountError
                    ? _profileLoadIssue ?? Copy("Проверьте соединение и повторите загрузку.",
                        "Check your connection and retry.")
                    : SubscriptionSummary(subscription);
        var subscriptionCard = new StackPanel { Spacing = 7 };
        subscriptionCard.Children.Add(DeyttTheme.TextBlock(subscriptionTitle, 18,
            accountError ? DeyttTheme.Coral : DeyttTheme.Text, FontWeight.SemiBold));
        subscriptionCard.Children.Add(DeyttTheme.TextBlock(subscriptionDetail, 13, DeyttTheme.Muted));
        if (signedIn && !loading && (accountError || subscription is null))
        {
            var retry = DeyttTheme.Action(
                DeyttTheme.TextBlock(Copy("Повторить загрузку", "Retry loading"), 13,
                    DeyttTheme.Coral, FontWeight.SemiBold),
                () => _ = RefreshSignedInAccountAsync());
            retry.Margin = new Thickness(0, 10, 0, 0);
            retry.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left;
            subscriptionCard.Children.Add(retry);
        }
        else if (signedIn && !loading && _profileLoadIssue is not null)
        {
            subscriptionCard.Children.Add(DeyttTheme.TextBlock(_profileLoadIssue, 12, DeyttTheme.Amber));
        }
        accountColumn.Children.Add(DeyttTheme.Card(subscriptionCard,
            DeyttTheme.Surface, DeyttTheme.Line, 22, new Thickness(18)));
        if (!compact || !signedIn)
        {
            accountColumn.Children.Add(DeyttTheme.Spacer(8));
            accountColumn.Children.Add(planPaymentEntry);
        }
        if (_paymentFlow.PendingPaymentId is not null)
        {
            accountColumn.Children.Add(DeyttTheme.Spacer(6));
            accountColumn.Children.Add(SettingsEntry("↻", Copy("Проверить платёж", "Check payment"),
                Copy("Счёт ожидает подтверждения", "Payment is awaiting confirmation"),
                Copy("проверить", "check"), () => _ = CheckPaymentStatusAsync()));
        }
        if (!string.IsNullOrWhiteSpace(_profileActionStatus))
        {
            accountColumn.Children.Add(DeyttTheme.Spacer(8));
            var actionStatus = new StackPanel { Spacing = 8 };
            actionStatus.Children.Add(DeyttTheme.TextBlock(_profileActionStatus, 12, DeyttTheme.Muted));
            if (_profileActionRetry is { } retry)
            {
                var retryButton = DeyttTheme.Action(
                    DeyttTheme.TextBlock(
                        _paymentFlow.RetryUsesConfirmedQuote
                            ? Copy("Повторить создание счёта", "Retry checkout")
                            : Copy("Повторить расчёт", "Retry quote"), 13,
                        DeyttTheme.Sky, FontWeight.SemiBold),
                    () => _ = retry());
                retryButton.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left;
                actionStatus.Children.Add(retryButton);
            }
            accountColumn.Children.Add(DeyttTheme.Card(actionStatus,
                DeyttTheme.Surface2, DeyttTheme.Line, 14, new Thickness(13, 10)));
        }

        managementColumn.Children.Add(BuildProfileDisclosure("▣",
            Copy("Устройства и сессии", "Devices and sessions"),
            Copy("Подключения и управление доступом", "Connections and access"),
            _profileDevicesExpanded,
            () => { _profileDevicesExpanded = !_profileDevicesExpanded; RenderActiveTabPreservingScroll(); },
            _profileDevicesExpanded ? BuildProfileDevicesContent(signedIn, loading, accountError) : null));
        managementColumn.Children.Add(BuildProfileDisclosure("◇",
            Copy("Помощь и документы", "Help and documents"),
            Copy("Поддержка, условия и конфиденциальность", "Support, terms and privacy"),
            _profileHelpExpanded,
            () => { _profileHelpExpanded = !_profileHelpExpanded; RenderActiveTabPreservingScroll(); },
            _profileHelpExpanded ? SettingsEntry("?", Copy("Центр помощи", "Help center"),
                Copy("Поддержка, условия использования и политика конфиденциальности",
                    "Support, terms of use, and privacy policy"), "›",
                () => _ = ShowHelpAndDocumentsAsync()) : null));

        columns.Children.Add(accountColumn);
        Grid.SetColumn(managementColumn, compact ? 0 : 1);
        Grid.SetRow(managementColumn, compact ? 1 : 0);
        columns.Children.Add(managementColumn);
        page.Children.Add(columns);
        return page;
    }

    private Control BuildProfileIdentityCard(TelegramAccount? account, bool signedIn, bool loading)
    {
        var avatar = new Border
        {
            Width = 56,
            Height = 56,
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            CornerRadius = new CornerRadius(28),
            ClipToBounds = true,
        };
        if (_telegramAvatarBitmap is { } bitmap)
        {
            var image = new Image { Source = bitmap, Stretch = Stretch.UniformToFill };
            _profileAvatarImage = image;
            avatar.Child = image;
        }
        else
        {
            avatar.Child = DeyttTheme.TextBlock("•", 23, DeyttTheme.Text, FontWeight.Bold, wrap: false);
        }
        var labels = new StackPanel { Spacing = 3, Margin = new Thickness(14, 0, 6, 0),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(AccountDisplayName(account, signedIn), 18,
            DeyttTheme.Text, FontWeight.SemiBold));
        var subtitle = !signedIn
            ? Copy("Войти в аккаунт", "Sign in to your account")
            : loading
                ? Copy("Загружаем профиль…", "Loading profile…")
                : account is null
                    ? Copy("Не удалось обновить профиль", "Could not refresh profile")
                    : string.IsNullOrWhiteSpace(account.Username)
                        ? Copy("Telegram подключён", "Telegram connected")
                        : $"@{account.Username}";
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 12, DeyttTheme.Muted));
        var row = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), MinHeight = 60 };
        row.Children.Add(avatar);
        Grid.SetColumn(labels, 1);
        row.Children.Add(labels);
        var arrow = DeyttTheme.TextBlock("›", 22, DeyttTheme.Muted, wrap: false);
        arrow.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(arrow, 2);
        row.Children.Add(arrow);
        var content = new StackPanel { Spacing = 7, Children = { row } };
        if (FormatAccountTenure(account) is { } tenure)
            content.Children.Add(DeyttTheme.TextBlock(tenure, 12, DeyttTheme.Muted));
        var action = DeyttTheme.Action(content, () =>
        {
            if (signedIn) ShowTelegramAccountMenu();
            else ShowSignInDialog();
        });
        action.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        action.Padding = new Thickness(16, 14);
        return DeyttTheme.Card(action, DeyttTheme.Surface, DeyttTheme.Line, 20, new Thickness(0));
    }

    private Control BuildProfileDisclosure(string glyph, string title, string subtitle, bool expanded,
        Action onToggle, Control? details)
    {
        var header = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"),
            ColumnSpacing = 12, MinHeight = 58 };
        var icon = new Border
        {
            Width = 36,
            Height = 36,
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            CornerRadius = new CornerRadius(11),
            Child = DeyttTheme.TextBlock(glyph, 17, DeyttTheme.Sky, FontWeight.SemiBold,
                DeyttTheme.JetBrainsMono, wrap: false),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        header.Children.Add(icon);
        var labels = new StackPanel { Spacing = 4,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(title, 16, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 11, DeyttTheme.Muted));
        Grid.SetColumn(labels, 1);
        header.Children.Add(labels);
        var indicator = DeyttTheme.TextBlock(expanded ? "−" : "+", 20,
            DeyttTheme.Muted, FontWeight.Normal, wrap: false);
        indicator.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(indicator, 2);
        header.Children.Add(indicator);
        var button = DeyttTheme.Action(header, onToggle);
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.Padding = new Thickness(2, 9);
        var group = new StackPanel { Spacing = 6, Children = { button } };
        if (expanded && details is not null)
            group.Children.Add(details);
        return DeyttTheme.Card(group, DeyttTheme.Surface, DeyttTheme.Line, 17,
            new Thickness(12, 8));
    }

    private Control BuildProfileDevicesContent(bool signedIn, bool loading, bool accountError)
    {
        var content = new StackPanel { Spacing = 6 };
        IReadOnlyList<TelegramHappDevice> devices = signedIn ? _keysSnapshot?.HappDevices ?? [] : [];
        var deviceSummary = !signedIn
            ? Copy("Подключите аккаунт, чтобы увидеть устройства.",
                "Connect your account to see devices.")
            : loading
                ? Copy("Загружаем список…", "Loading devices…")
                : accountError
                    ? Copy("Не удалось загрузить устройства. Повторите загрузку профиля.",
                        "Could not load devices. Retry the profile request.")
                    : $"{devices.Count} {Copy("устройств Happ", "Happ devices")} · " +
                      $"{_keysSnapshot?.AmneziaClients ?? 0} {Copy("клиентов AWG", "AWG clients")}";
        content.Children.Add(DeyttTheme.TextBlock(deviceSummary, 12, DeyttTheme.Muted));
        if (signedIn && !loading && !accountError && devices.Count == 0)
            content.Children.Add(DeyttTheme.TextBlock(Copy("Активных устройств Happ пока нет.",
                "There are no active Happ devices."), 13, DeyttTheme.Muted));
        if (signedIn && !loading && !accountError)
        {
            foreach (var device in devices)
            {
                var deviceOs = !string.IsNullOrWhiteSpace(device.OsVersion) ? device.OsVersion : device.Os;
                var deviceName = string.Join(" · ", new[] { device.Model, deviceOs }
                    .Where(value => !string.IsNullOrWhiteSpace(value)));
                if (deviceName.Length == 0)
                    deviceName = Copy("Устройство Happ", "Happ device");
                var state = device.Blocked
                    ? Copy("обновления заблокированы", "updates blocked")
                    : Copy("доступ разрешён", "access allowed");
                content.Children.Add(SettingsEntry("H", deviceName,
                    $"{state} · {FormatLastSeen(device.LastSeen)}", Copy("подробнее", "details"),
                    () => ShowHappDeviceDetails(device)));
            }
        }
        content.Children.Add(DeyttTheme.Spacer(5));
        content.Children.Add(DeyttTheme.TextBlock(Copy("СЕАНСЫ ПРИЛОЖЕНИЯ", "APP SESSIONS"),
            9, DeyttTheme.Muted, FontWeight.SemiBold, DeyttTheme.JetBrainsMono));
        if (!signedIn)
        {
            content.Children.Add(SettingsEntry("↗", Copy("Войти, чтобы управлять сеансами", "Sign in to manage sessions"),
                Copy("Можно завершить старые входы на других устройствах", "End old app sessions on other devices"),
                "›", ShowSignInDialog));
        }
        else if (loading)
        {
            content.Children.Add(DeyttTheme.TextBlock(Copy("Загружаем сеансы…", "Loading sessions…"),
                12, DeyttTheme.Muted));
        }
        else if (_sessionLoadIssue is not null)
        {
            content.Children.Add(SettingsEntry("↻", Copy("Сеансы не загружены", "Sessions unavailable"),
                _sessionLoadIssue, Copy("повторить", "retry"), () => _ = RefreshSignedInAccountAsync()));
        }
        else if (_sessions.Count == 0)
        {
            content.Children.Add(DeyttTheme.TextBlock(Copy("Активных сеансов приложения нет.",
                "There are no active app sessions."), 12, DeyttTheme.Muted));
        }
        else
        {
            foreach (var session in _sessions)
                content.Children.Add(SettingsEntry("D", session.Label,
                    session.Current
                        ? Copy("Это устройство · текущий сеанс", "This device · current session")
                        : $"{Copy("Вход", "Signed in")}: {FormatLastSeen(session.CreatedAt)}",
                    session.Current ? Copy("текущий", "current") : Copy("управлять", "manage"),
                    () => _ = ManageSessionAsync(session)));
        }
        content.Children.Add(SettingsEntry("+", Copy("Обновить список", "Refresh list"),
            Copy("Заново загрузить устройства и доступ", "Reload devices and access"), "↻", () =>
            {
                if (signedIn) _ = RefreshSignedInAccountAsync();
                else ShowSignInDialog();
            }));
        content.Children.Add(SettingsEntry("↻", Copy("Сбросить ключи", "Reset keys"),
            Copy("Отозвать текущие ключи выбранного типа", "Revoke current keys by type"),
            Copy("управлять", "manage"), () => _ = ShowResetChoicesAsync()));
        return DeyttTheme.Card(content, DeyttTheme.Surface2, DeyttTheme.Line,
            19, new Thickness(15, 13));
    }

    private Control BuildSettingsPage()
    {
        var page = NewPage(Copy("ПОД ВАС", "MAKE IT YOURS"), Copy("Настройки", "Settings"));
        var compact = _compactLayout;
        var columns = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions(compact ? "*" : "*,*"),
            RowDefinitions = new RowDefinitions(compact ? "Auto,Auto" : "Auto"),
        };
        var generalColumn = new StackPanel
        {
            Spacing = 0,
            Margin = compact ? new Thickness(0) : new Thickness(0, 0, 10, 0),
        };
        var privacyColumn = new StackPanel
        {
            Spacing = 0,
            Margin = compact ? new Thickness(0, 4, 0, 0) : new Thickness(10, 0, 0, 0),
        };
        AddSection(generalColumn, Copy("ЯЗЫК И АККАУНТ", "LANGUAGE AND ACCOUNT"));
        var accountDetail = _sessionToken is null
            ? Copy("Подписка и устройства", "Subscription and devices")
            : string.IsNullOrWhiteSpace(_account?.Username)
                ? Copy("Аккаунт подключён", "Account connected")
                : $"@{_account!.Username}";
        var accountEntry = SettingsEntry("✓", _sessionToken is null
                ? Copy("Войти через Telegram", "Sign in with Telegram")
                : Copy("Telegram подключён", "Telegram connected"),
            accountDetail,
            "›", () =>
            {
                if (_sessionToken is null)
                    ShowSignInDialog();
                else
                    ShowTab(MainTab.Profile);
            }, emphasis: true);
        var accountEntryShownFirst = compact && _sessionToken is null;
        if (accountEntryShownFirst)
            generalColumn.Children.Add(accountEntry);
        generalColumn.Children.Add(SettingsEntry("Aa", Copy("Язык приложения", "App language"),
            _language == "ru" ? "Русский" : "English", "›", () => _ = ShowLanguagePickerAsync()));
        if (!accountEntryShownFirst)
        {
            generalColumn.Children.Add(DeyttTheme.Spacer(6));
            generalColumn.Children.Add(accountEntry);
        }
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↓", Copy("Импорт подписки", "Import subscription"),
            Copy("Вставить ссылку или подключить аккаунт", "Paste a link or connect your account"), "›",
            () => _ = ShowSetupWindowAsync()));
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↗", Copy("Ссылки подписки", "Subscription links"),
            WindowsImportLinkProtocol.IsRegistered()
                ? Copy("Открывать ссылки DEYTT в этом приложении", "Open DEYTT links in this app")
                : Copy("Назначить приложению ссылки DEYTT", "Open DEYTT links with this app"),
            WindowsImportLinkProtocol.IsRegistered()
                ? Copy("включено", "enabled")
                : Copy("включить", "enable"),
            ToggleImportLinkProtocol));

        AddSection(generalColumn, Copy("ПОДКЛЮЧЕНИЕ", "CONNECTION"));
        generalColumn.Children.Add(SettingsEntry("↗", Copy("Раздельное туннелирование", "Split tunneling"),
            Copy("Сайты из подписки · все протоколы", "Subscription sites · all protocols"), "›",
            ShowSplitTunnelingInfo));
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↻", Copy("Проверка маршрута", "Route check"),
            $"HTTP {_probeMethod} · {Copy("быстрая проверка, загрузка 1 с", "fast check, 1 s download")}", "›",
            () => _ = ShowProbeMethodPickerAsync()));
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
        privacyColumn.Children.Add(ToggleEntry(Copy("Показывать мой регион", "Show my region"),
            Copy("IP · ipinfo.io · не GPS · только в памяти",
                "IP via ipinfo.io · no GPS · memory only"),
            _mapRegionEnabled, UpdateMapRegionEnabledAsync));
        privacyColumn.Children.Add(DeyttTheme.Spacer(8));
        privacyColumn.Children.Add(ToggleEntry(Copy("Уменьшить движение", "Reduce motion"),
            Copy("Скрыть звёздный фон и анимации",
                "Hide the starfield and animations"),
            _reduceMotion, value =>
            {
                _reduceMotion = value;
                return Task.CompletedTask;
            }));

        AddSection(privacyColumn, Copy("ОБНОВЛЕНИЯ", "UPDATES"));
        privacyColumn.Children.Add(SettingsEntry("↻", Copy("Проверить обновления", "Check for updates"),
            Copy("Текущая версия приложения", "Current application version"), "›",
            () => _ = ShowUpdateDialogAsync()));
        if (_preferencesIssue is not null)
        {
            privacyColumn.Children.Add(DeyttTheme.Spacer(10));
            privacyColumn.Children.Add(DeyttTheme.TextBlock(_preferencesIssue, 12, DeyttTheme.Coral));
        }
        columns.Children.Add(generalColumn);
        Grid.SetColumn(privacyColumn, compact ? 0 : 1);
        Grid.SetRow(privacyColumn, compact ? 1 : 0);
        columns.Children.Add(privacyColumn);
        page.Children.Add(columns);
        return page;
    }

    private Control SettingsEntry(string glyph, string title, string subtitle, string trailing,
        Action onClick, bool emphasis = false)
    {
        var content = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), MinHeight = 58 };
        var icon = DeyttTheme.TextBlock(glyph, 19, DeyttTheme.Sky,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false);
        icon.Width = 28;
        icon.TextAlignment = TextAlignment.Center;
        content.Children.Add(icon);
        var labels = new StackPanel { Spacing = 3, Margin = new Thickness(12, 0, 6, 0), VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(title, 16, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 11, DeyttTheme.Muted));
        Grid.SetColumn(labels, 1);
        content.Children.Add(labels);
        var arrow = DeyttTheme.TextBlock(trailing, 13, emphasis ? DeyttTheme.Sky : DeyttTheme.Muted,
            FontWeight.SemiBold, wrap: false);
        arrow.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(arrow, 2);
        content.Children.Add(arrow);
        var button = DeyttTheme.Action(content, onClick);
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.Padding = new Thickness(5, 5);
        button.CornerRadius = new CornerRadius(12);
        var row = new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(4),
            Child = button,
        };
        button.GotFocus += (_, _) => row.BringIntoView();
        return row;
    }

    private Control ToggleEntry(string title, string subtitle, bool value, Func<bool, Task> update)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), MinHeight = 60 };
        var labels = new StackPanel { Spacing = 3, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(title, 16, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 11, DeyttTheme.Muted));
        grid.Children.Add(labels);
        var knob = new Grid
        {
            Width = 46,
            Height = 25,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        knob.Children.Add(new Ellipse
        {
            Width = 19,
            Height = 19,
            Fill = DeyttTheme.Brush(value ? DeyttTheme.Mint : DeyttTheme.Text),
            HorizontalAlignment = value ? Avalonia.Layout.HorizontalAlignment.Right : Avalonia.Layout.HorizontalAlignment.Left,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Margin = new Thickness(3),
        });
        var toggle = new Border
        {
            Child = knob,
            Width = 48,
            Height = 27,
            Background = DeyttTheme.Brush(value ? DeyttTheme.MintSurface : DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(value ? DeyttTheme.Mint : DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(15),
            ClipToBounds = true,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Margin = new Thickness(14, 0, 4, 0),
        };
        Grid.SetColumn(toggle, 1);
        grid.Children.Add(toggle);
        var button = DeyttTheme.Action(grid, () => _ = ApplyToggleAsync(!value, update));
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.Padding = new Thickness(5, 6);
        AutomationProperties.SetName(button, $"{title}: {(value ? Copy("включено", "on") : Copy("выключено", "off"))}");
        return new Border
        {
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(0, 0, 0, 1),
            Child = button,
        };
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
        StartMapLocationLookup(IsVpnDisplayConnected());
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
        Copy("Точка определяется по публичному IP через ipinfo.io — это не GPS и на выбор маршрута не влияет. Запрос видит внешний сервис; приложение не сохраняет адрес или координаты, а держит точку только в памяти до закрытия.",
            "The point is estimated from your public IP through ipinfo.io — this is not GPS and does not affect route selection. An external service receives the request; the app does not save your address or coordinates and keeps the point only in memory until you close it."),
        Copy("Показывать на карте", "Show on map"),
        Copy("не сейчас", "not now"));

    private StackPanel NewPage(string kicker, string title)
    {
        var page = new StackPanel { Spacing = 0 };
        page.Children.Add(DeyttTheme.TextBlock(title, _compactLayout ? 29 : 36, DeyttTheme.Text,
            FontWeight.Bold, DeyttTheme.InterTight));
        page.Children.Add(DeyttTheme.Spacer(_compactLayout ? 14 : 22));
        return page;
    }

    private void AddSection(StackPanel page, string title)
    {
        page.Children.Add(DeyttTheme.Spacer(_compactLayout ? 17 : 24));
        page.Children.Add(DeyttTheme.SectionLabel(title));
        page.Children.Add(DeyttTheme.Spacer(_compactLayout ? 8 : 10));
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
        IsAwgProtocol(route.Protocol) && route.ProfileId is { } profileId
            ? _keysSnapshot?.AwgProfiles.FirstOrDefault(profile => profile.RouteId == profileId)?.Config
            : null;

    private static bool IsAwgProtocol(string? protocol) => protocol is "AWG31";

    private static string AwgProtocolTitle(string? protocol) => protocol switch
    {
        "AWG31" => "amneziawg 3.1",
        _ => "amneziawg",
    };

    private string RouteCountryName(WindowsRoute route) => route.CountryCode switch
    {
        "NL" => Copy("Нидерланды", "Netherlands"),
        "RU" => Copy("Россия", "Russia"),
        "DE" => Copy("Германия", "Germany"),
        "FI" => Copy("Финляндия", "Finland"),
        "IT" => Copy("италия", "italy"),
        "AWG_UNKNOWN" => Copy("Другие регионы", "Other regions"),
        "RU-DE" => Copy("LTE + белые списки", "LTE + whitelist"),
        _ => route.CountryName,
    };

    private void SelectRoute(string routeId)
    {
        if (_vpnActionInProgress)
            return;
        var route = _routes.FirstOrDefault(value => value.Id == routeId);
        if (route is null)
        {
            if (_sessionToken is null)
            {
                ShowSignInDialog();
                return;
            }
            ShowInfoDialog(Copy("Маршрут не загружен", "Route not loaded"),
                Copy("Обнови подписку, чтобы выбрать этот выход.", "Refresh the subscription to select this exit."));
            return;
        }

        if (_selectedRoute == routeId)
            return;

        if (WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State))
        {
            _ = ConfirmAndSelectRouteAsync(route);
            return;
        }

        ApplyRouteSelection(route);
    }

    private async Task ConfirmAndSelectRouteAsync(WindowsRoute route)
    {
        var confirmed = await ConfirmDialogAsync(
            Copy("Изменить точку выхода?", "Change exit node?"),
            Copy("Текущее соединение завершится. После выбора подключитесь снова, чтобы применить новый маршрут.",
                "The current connection will end. Connect again after choosing to apply the new route."),
            Copy("Остановить и сменить", "Stop and change"),
            Copy("Оставить подключение", "Keep connection"));
        if (!confirmed || _vpnActionInProgress)
            return;

        _vpnActionInProgress = true;
        RenderActiveTabPreservingScroll();
        try
        {
            var disconnected = await _tunnelClient.DisconnectAsync();
            if (disconnected.State != "disconnected")
            {
                _vpnSnapshot = disconnected;
                ShowInfoDialog(Copy("Не удалось остановить VPN", "Could not stop the VPN"),
                    disconnected.Detail ?? Copy("Повторите попытку после проверки службы VPN.",
                        "Check the VPN service and try again."));
                return;
            }

            _vpnSnapshot = disconnected;
            ApplyRouteSelection(route);
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            ShowInfoDialog(Copy("Не удалось остановить VPN", "Could not stop the VPN"),
                Copy("Служба VPN не ответила. Проверьте её состояние и повторите попытку.",
                    "The VPN service did not respond. Check its status and try again."));
        }
        finally
        {
            _vpnActionInProgress = false;
            UpdateMapLocationAfterVpnAction(wasConnected: true);
            RenderActiveTabPreservingScroll();
        }
    }

    private void ApplyRouteSelection(WindowsRoute route)
    {
        _selectedRoute = route.Id;
        SavePreferences();
        ShowTab(MainTab.Home);
    }

    private async Task ShowLanguagePickerAsync()
    {
        var choices = new StackPanel { Spacing = 8 };
        var buttons = new List<(string Language, Button Button)>();
        foreach (var (language, title) in new[] { ("ru", "Русский"), ("en", "English") })
        {
            var selected = _language == language;
            var choice = DeyttTheme.Action(
                DeyttTheme.Card(DeyttTheme.TextBlock(
                        $"{(selected ? "✓  " : string.Empty)}{title}", 16,
                        selected ? DeyttTheme.Sky : DeyttTheme.Text, FontWeight.SemiBold),
                    selected ? DeyttTheme.Selected : DeyttTheme.Surface,
                    selected ? DeyttTheme.SelectedLine : DeyttTheme.Line,
                    16, new Thickness(16, 13)),
                () => { });
            choices.Children.Add(choice);
            buttons.Add((language, choice));
        }
        var selectedLanguage = await ShowContentInShellAsync(
            Copy("Язык приложения", "App language"), choices, 390,
            modal =>
            {
                foreach (var (language, button) in buttons)
                    button.Click += (_, _) => modal.Close(language);
            });
        if (selectedLanguage is not string nextLanguage || nextLanguage == _language)
            return;

        _language = nextLanguage;
        SavePreferences();
        ShowTab(MainTab.Settings);
    }

    private async Task ShowProbeMethodPickerAsync()
    {
        var options = new StackPanel { Spacing = 8 };
        var optionButtons = new List<(string Method, Button Button)>();
        foreach (var option in new[]
        {
            (Method: "HEAD", Title: Copy("HEAD · только заголовки", "HEAD · headers only"),
                Detail: Copy("Быстрая проверка доступности с минимальным трафиком.",
                    "A quick availability check with minimal traffic.")),
            (Method: "GET", Title: Copy("GET · заголовки и содержимое", "GET · headers and body"),
                Detail: Copy("Загружает короткий ответ сервера. Подходит, если узел не отвечает на HEAD.",
                    "Downloads the short server response. Use it if a node does not answer HEAD.")),
        })
        {
            var selected = _probeMethod == option.Method;
            var labels = new StackPanel { Spacing = 4 };
            labels.Children.Add(DeyttTheme.TextBlock(
                $"{(selected ? "✓  " : string.Empty)}{option.Title}", 15,
                selected ? DeyttTheme.Sky : DeyttTheme.Text, FontWeight.SemiBold));
            labels.Children.Add(DeyttTheme.TextBlock(option.Detail, 12, DeyttTheme.Muted));
            var choice = DeyttTheme.Action(
                DeyttTheme.Card(labels, selected ? DeyttTheme.Selected : DeyttTheme.Surface,
                    selected ? DeyttTheme.SelectedLine : DeyttTheme.Line, 16, new Thickness(14, 11)), () => { });
            options.Children.Add(choice);
            optionButtons.Add((option.Method, choice));
        }
        var close = DeyttTheme.Action(DeyttTheme.TextBlock(Copy("Отмена", "Cancel"), 14,
            DeyttTheme.Muted, FontWeight.SemiBold), () => { });
        var content = new StackPanel
        {
            Spacing = 13,
            Children =
            {
                DeyttTheme.TextBlock(Copy("Способ проверки", "Check method"), 21,
                    DeyttTheme.Text, FontWeight.Bold),
                DeyttTheme.TextBlock(Copy("Проверка идёт через выбранный VPN-маршрут.",
                    "Checks run through the selected VPN route."), 13, DeyttTheme.Muted),
                options,
                close,
            },
        };
        var selectedMethod = await ShowContentInShellAsync(
            Copy("Проверка маршрута", "Route check"), content, 600,
            modal =>
            {
                foreach (var (method, button) in optionButtons)
                    button.Click += (_, _) => modal.Close(method);
                close.Click += (_, _) => modal.Close();
            });
        if (selectedMethod is not string method)
            return;

        _probeMethod = method;
        SavePreferences();
        ShowTab(MainTab.Settings);
    }

    private async Task ToggleVpnAsync()
    {
        if (_vpnActionInProgress || _vpnCancelInProgress || _routeProbeInProgress)
            return;

        var wasConnected = IsVpnTunnelActive(_vpnSnapshot.State);
        var operationVersion = ++_vpnOperationVersion;
        _vpnCancelRequested = false;
        _vpnActionInProgress = true;
        RenderActiveTabPreservingScroll();
        try
        {
            if (IsVpnTunnelActive(_vpnSnapshot.State))
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
            if (IsAwgProtocol(route.Protocol) && awgConfig is null)
            {
                _vpnSnapshot = new WindowsTunnelSnapshot("error",
                    Copy("Профиль AmneziaWG не загружен. Обновите подписку.",
                        "The AmneziaWG profile is unavailable. Refresh the subscription."), route.Tag);
                RenderActiveTabPreservingScroll();
                return;
            }
            _vpnConnectTask = _tunnelClient.ConnectAsync(profile, route.Tag, awgConfig);
            var result = await _vpnConnectTask;
            if (operationVersion == _vpnOperationVersion && !_vpnCancelRequested)
            {
                _vpnSnapshot = result;
                _vpnServiceAvailable = true;
                RenderActiveTabPreservingScroll();
            }
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            if (operationVersion == _vpnOperationVersion && !_vpnCancelRequested)
            {
                _vpnSnapshot = VpnServiceUnavailableSnapshot();
                _vpnServiceAvailable = false;
                RenderActiveTabPreservingScroll();
            }
        }
        finally
        {
            if (operationVersion == _vpnOperationVersion)
            {
                _vpnActionInProgress = false;
                _vpnConnectTask = null;
            }
            if (operationVersion == _vpnOperationVersion && !_vpnCancelRequested)
                UpdateMapLocationAfterVpnAction(wasConnected);
            RenderActiveTabPreservingScroll();
        }

        if (operationVersion == _vpnOperationVersion && _vpnSnapshot.State is ("starting" or "checking"))
            _ = MonitorVpnConnectionAsync();
    }

    private async Task CancelVpnConnectionAsync()
    {
        if (_vpnCancelInProgress || _vpnSnapshot.State is not ("starting" or "checking"))
            return;

        _vpnCancelRequested = true;
        _vpnCancelInProgress = true;
        ++_vpnOperationVersion;
        var pendingConnect = _vpnConnectTask;
        _vpnSnapshot = new WindowsTunnelSnapshot("stopping",
            Copy("Останавливаем подключение…", "Stopping the connection…"));
        RenderActiveTabPreservingScroll();
        try
        {
            // The service serializes connect and disconnect. Wait for a pending connect
            // before stopping so an earlier disconnect cannot be overtaken by connect.
            if (pendingConnect is not null)
            {
                try
                {
                    await pendingConnect;
                }
                catch (Exception)
                {
                    // A failed connect still needs a final disconnect.
                }
            }
            _vpnSnapshot = await _tunnelClient.DisconnectAsync();
            _vpnServiceAvailable = true;
            UpdateMapLocationAfterVpnAction(wasConnected: false);
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            _vpnSnapshot = VpnServiceUnavailableSnapshot();
            _vpnServiceAvailable = false;
        }
        finally
        {
            _vpnCancelInProgress = false;
            _vpnActionInProgress = false;
            _vpnConnectTask = null;
            RenderActiveTabPreservingScroll();
        }
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

    private bool HasProbeSpeedToken() =>
        _sessionToken is { Length: >= 32 and <= 256 } token &&
        token.All(character => character is >= '\x21' and <= '\x7e');

    private string? RouteCountryProbeStatus(string countryCode)
    {
        if (!_routeCountryProbeStates.TryGetValue(countryCode, out var state))
            return null;

        return state switch
        {
            RouteCountryProbeState.Queued => Copy("Диагностика в очереди…", "Route checks queued…"),
            RouteCountryProbeState.Running => HasProbeSpeedToken()
                ? Copy("Пинг проверяется · скорость в очереди", "Checking latency · speed queued")
                : Copy("Проверяем пинг · скорость после входа в Telegram",
                    "Checking latency · sign in to measure speed"),
            RouteCountryProbeState.Cancelled => Copy("Замеры остановлены · можно повторить",
                "Measurements stopped · retry when ready"),
            RouteCountryProbeState.NeedsDisconnect => Copy("Отключите VPN для проверки маршрутов",
                "Disconnect the VPN to check routes"),
            RouteCountryProbeState.ServiceUnavailable => Copy("Служба VPN недоступна · проверьте настройки",
                "VPN service unavailable · check settings"),
            RouteCountryProbeState.Failed => Copy("Не удалось проверить · можно повторить замеры",
                "Check failed · retry the measurements"),
            _ => null,
        };
    }

    private void QueueRouteCountryProbe(string countryCode, string[] routeTags)
    {
        if (routeTags.Length == 0)
            return;

        if (_activeRouteProbeCountry == countryCode)
        {
            if (!_routeProbeCancelRequested)
                return;
        }
        else if (_pendingRouteProbeNodes.ContainsKey(countryCode))
        {
            return;
        }

        if (_keysSnapshot?.ProfileJson is not { Length: > 0 } || _routes.Count == 0)
        {
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Failed;
            RenderActiveTabPreservingScroll();
            return;
        }
        if (!_vpnServiceAvailable)
        {
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.ServiceUnavailable;
            RenderActiveTabPreservingScroll();
            return;
        }
        if (_vpnActionInProgress || WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State))
        {
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.NeedsDisconnect;
            RenderActiveTabPreservingScroll();
            return;
        }

        var availableTags = _routes.Select(route => route.Tag).ToHashSet(StringComparer.Ordinal);
        var request = new RouteCountryProbeRequest(countryCode,
            routeTags.Where(availableTags.Contains).Distinct(StringComparer.Ordinal).ToArray());
        if (request.RouteTags.Length == 0)
            return;

        // A new expansion is a new measurement. Old numbers must not look live
        // while this country's routes wait in the queue.
        var freshResults = new Dictionary<string, WindowsRouteProbeResult>(_routeProbeResults,
            StringComparer.Ordinal);
        foreach (var routeTag in request.RouteTags)
            freshResults.Remove(routeTag);
        _routeProbeResults = freshResults;
        var node = _pendingRouteProbeQueue.AddLast(request);
        _pendingRouteProbeNodes[countryCode] = node;
        _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Queued;
        RenderActiveTabPreservingScroll();
        StartNextCountryProbe();
    }

    private void CollapseRouteCountryProbe(string countryCode)
    {
        var wasQueued = false;
        if (_pendingRouteProbeNodes.Remove(countryCode, out var node))
        {
            _pendingRouteProbeQueue.Remove(node);
            wasQueued = true;
        }

        if (_activeRouteProbeCountry == countryCode && _routeProbeInProgress)
        {
            _routeCountryProbeGeneration++;
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Cancelled;
            if (!_routeProbeCancelRequested)
                _ = CancelRouteProbeAsync();
        }
        else if (wasQueued)
        {
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Cancelled;
        }
        else
        {
            _routeCountryProbeStates.Remove(countryCode);
        }
    }

    private void SuspendRouteCountryProbes()
    {
        _routeCountryProbeGeneration++;
        _pendingRouteProbeQueue.Clear();
        _pendingRouteProbeNodes.Clear();
        _expandedRouteCountries.Clear();
        _routeCountryProbeStates.Clear();
        if (_activeRouteProbeCountry is not null && _routeProbeInProgress &&
            !_routeProbeCancelRequested)
            _ = CancelRouteProbeAsync();
    }

    private void StartNextCountryProbe()
    {
        if (_activeTab != MainTab.Routes || _routeProbeInProgress || _vpnActionInProgress ||
            _activeRouteProbeCountry is not null ||
            _pendingRouteProbeQueue.Count == 0)
            return;

        if (!_vpnServiceAvailable || WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State))
        {
            var blockedState = !_vpnServiceAvailable
                ? RouteCountryProbeState.ServiceUnavailable
                : RouteCountryProbeState.NeedsDisconnect;
            foreach (var request in _pendingRouteProbeQueue)
                _routeCountryProbeStates[request.CountryCode] = blockedState;
            _pendingRouteProbeQueue.Clear();
            _pendingRouteProbeNodes.Clear();
            RenderActiveTabPreservingScroll();
            return;
        }

        while (_pendingRouteProbeQueue.First is { } node)
        {
            _pendingRouteProbeQueue.RemoveFirst();
            _pendingRouteProbeNodes.Remove(node.Value.CountryCode);
            if (!_expandedRouteCountries.Contains(node.Value.CountryCode))
            {
                _routeCountryProbeStates[node.Value.CountryCode] = RouteCountryProbeState.Cancelled;
                continue;
            }

            _activeRouteProbeCountry = node.Value.CountryCode;
            _routeCountryProbeStates[node.Value.CountryCode] = RouteCountryProbeState.Running;
            _ = ProbeRoutesAsync(node.Value.RouteTags, node.Value.CountryCode);
            return;
        }
    }

    private async Task ProbeRoutesAsync(IReadOnlyCollection<string>? requestedRouteTags = null,
        string? countryCode = null)
    {
        var countryProbeGeneration = _routeCountryProbeGeneration;
        if (_routeProbeInProgress || _vpnActionInProgress ||
            WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State))
            return;
        if (_keysSnapshot?.ProfileJson is not { Length: > 0 } profile || _routes.Count == 0)
            return;
        if (!_vpnServiceAvailable)
        {
            if (countryCode is null)
                ShowTab(MainTab.Settings);
            else
                _routeCountryProbeStates[countryCode] = RouteCountryProbeState.ServiceUnavailable;
            return;
        }

        _routeProbeInProgress = true;
        _routeProbeCancelRequested = false;
        RenderProbeProgressPreservingFocus();
        string[]? activeRouteTags = null;
        var previousRouteResults = new Dictionary<string, WindowsRouteProbeResult>(StringComparer.Ordinal);
        try
        {
            var availableTags = _routes.Select(route => route.Tag).ToHashSet(StringComparer.Ordinal);
            var routeTags = requestedRouteTags is { Count: > 0 }
                ? requestedRouteTags.Where(availableTags.Contains).Distinct(StringComparer.Ordinal).ToArray()
                : availableTags.ToArray();
            if (routeTags.Length == 0)
                return;
            activeRouteTags = routeTags;
            _activeRouteProbeTags = new HashSet<string>(routeTags, StringComparer.Ordinal);
            foreach (var routeTag in routeTags)
            {
                if (_routeProbeResults.TryGetValue(routeTag, out var previous))
                    previousRouteResults[routeTag] = previous;
            }
            ApplyProbeProgress(routeTags.Select(routeTag => new WindowsRouteProbeResult(
                routeTag, null, null, null, "latency", 1)).ToArray(), routeTags);
            var awgProfiles = (_keysSnapshot?.AwgProfiles ?? [])
                .ToDictionary(item => item.RouteId, item => item.Config, StringComparer.Ordinal);
            var speedToken = HasProbeSpeedToken() ? _sessionToken! : string.Empty;
            var probeTask = _tunnelClient.ProbeAsync(profile, routeTags, _probeMethod, speedToken,
                awgProfiles);
            while (!probeTask.IsCompleted)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(400));
                if (probeTask.IsCompleted)
                    break;
                try
                {
                    var progress = await _tunnelClient.GetProbeProgressAsync();
                    if (progress.State == "probe_progress" && progress.ProbeResults is { } current &&
                        (countryCode is null || countryProbeGeneration == _routeCountryProbeGeneration))
                        ApplyProbeProgress(current, routeTags);
                }
                catch (Exception error) when (IsTunnelTransportError(error))
                {
                    // The long-running probe result remains authoritative if a progress poll is missed.
                }
            }
            var result = await probeTask;
            if (countryCode is not null &&
                (countryProbeGeneration != _routeCountryProbeGeneration || _activeTab != MainTab.Routes))
            {
                RestoreProbeResults(routeTags, previousRouteResults);
                return;
            }
            if (result.State == "probe_complete" && result.ProbeResults is { } probes)
            {
                var updatedResults = requestedRouteTags is null
                    ? new Dictionary<string, WindowsRouteProbeResult>(StringComparer.Ordinal)
                    : new Dictionary<string, WindowsRouteProbeResult>(_routeProbeResults, StringComparer.Ordinal);
                foreach (var probe in probes)
                    updatedResults[probe.RouteTag] = probe;
                foreach (var routeTag in routeTags)
                    if (!updatedResults.ContainsKey(routeTag))
                        updatedResults[routeTag] = new WindowsRouteProbeResult(routeTag, null, null,
                            Copy("Служба не вернула результат. Повторите замер.",
                                "The service returned no result. Retry the measurement."), "error");
                _routeProbeResults = updatedResults;
            }
            else if (result.State == "probe_cancelled")
            {
                RestoreProbeResults(routeTags, previousRouteResults);
                if (result.ProbeResults is { } partial)
                    ApplyProbeProgress(partial.Where(item => item.LatencyMilliseconds is not null || item.BytesPerSecond is not null).ToArray(), routeTags);
            }
            else
            {
                MarkRouteProbeFailure(routeTags, result.Detail);
                if (countryCode is null)
                    ShowInfoDialog(Copy("Не удалось проверить маршруты", "Could not check routes"),
                        result.Detail);
                else
                    _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Failed;
            }
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            if (countryCode is not null &&
                (countryProbeGeneration != _routeCountryProbeGeneration || _activeTab != MainTab.Routes))
                return;
            if (activeRouteTags is not null)
                MarkRouteProbeFailure(activeRouteTags,
                    Copy("Служба VPN не ответила. Повторите замер.",
                        "The VPN service did not respond. Retry the measurement."));
            if (countryCode is null)
                ShowInfoDialog(Copy("Не удалось проверить маршруты", "Could not check routes"),
                    Copy("Служба VPN не ответила. Проверьте её установку и повторите попытку.",
                        "The VPN service did not respond. Check its installation and try again."));
            else
                _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Failed;
        }
        finally
        {
            _activeRouteProbeTags.Clear();
            _routeProbeInProgress = false;
            _routeProbeCancelRequested = false;
            if (countryCode is not null && _activeRouteProbeCountry == countryCode)
            {
                _activeRouteProbeCountry = null;
                if (_routeCountryProbeStates.GetValueOrDefault(countryCode) == RouteCountryProbeState.Running)
                    _routeCountryProbeStates.Remove(countryCode);
            }
            RenderProbeProgressPreservingFocus();
            StartNextCountryProbe();
        }
    }

    private async Task CancelRouteProbeAsync()
    {
        if (!_routeProbeInProgress || _routeProbeCancelRequested)
            return;
        if (_activeRouteProbeCountry is { } countryCode)
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Cancelled;
        MarkProbeCancellationRequested();
        try
        {
            await _tunnelClient.CancelProbeAsync();
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
        }
    }

    internal void MarkProbeCancellationRequested()
    {
        if (!_routeProbeInProgress || _routeProbeCancelRequested)
            return;
        _routeProbeCancelRequested = true;
        RenderProbeProgressPreservingFocus();
    }

    internal bool ApplyProbeProgress(IReadOnlyList<WindowsRouteProbeResult> progress,
        IReadOnlyCollection<string> requestedRouteTags)
    {
        var updated = new Dictionary<string, WindowsRouteProbeResult>(_routeProbeResults, StringComparer.Ordinal);
        var changed = false;
        foreach (var item in progress)
        {
            if (!requestedRouteTags.Contains(item.RouteTag, StringComparer.Ordinal))
                continue;
            if (updated.TryGetValue(item.RouteTag, out var previous) && previous == item)
                continue;
            updated[item.RouteTag] = item;
            changed = true;
        }

        if (!changed)
            return false;
        _routeProbeResults = updated;
        RenderProbeProgressPreservingFocus();
        return true;
    }

    private void RestoreProbeResults(IEnumerable<string> routeTags,
        IReadOnlyDictionary<string, WindowsRouteProbeResult> previousResults)
    {
        var updated = new Dictionary<string, WindowsRouteProbeResult>(_routeProbeResults, StringComparer.Ordinal);
        foreach (var routeTag in routeTags)
            updated.Remove(routeTag);
        foreach (var (routeTag, result) in previousResults)
            updated[routeTag] = result;
        _routeProbeResults = updated;
    }

    private void MarkRouteProbeFailure(IEnumerable<string> routeTags, string? detail)
    {
        var message = string.IsNullOrWhiteSpace(detail)
            ? Copy("Не удалось проверить маршрут. Повторите замер.",
                "Could not check the route. Retry the measurement.")
            : detail;
        var updated = new Dictionary<string, WindowsRouteProbeResult>(_routeProbeResults,
            StringComparer.Ordinal);
        foreach (var routeTag in routeTags)
            updated[routeTag] = new WindowsRouteProbeResult(routeTag, null, null, message, "error");
        _routeProbeResults = updated;
    }

    private void RenderProbeProgressPreservingFocus()
    {
        var focused = FocusManager?.GetFocusedElement() as Control;
        var focusableButtons = PageHost.GetVisualDescendants().OfType<Button>()
            .Where(button => button.Focusable && button.IsEffectivelyVisible).ToArray();
        var focusIndex = focused is Button focusedButton
            ? Array.IndexOf(focusableButtons, focusedButton)
            : -1;
        var focusAutomationId = focused is Button
            ? AutomationProperties.GetAutomationId(focused)
            : null;
        var offset = PageScroll.Offset;
        ShowTab(_activeTab);
        PageScroll.Offset = offset;
        if (focusIndex < 0)
            return;
        var renderedTab = _activeTab;
        var renderedPage = PageHost.Children.OfType<Control>().SingleOrDefault();

        Dispatcher.UIThread.Post(() =>
        {
            if (_activeTab != renderedTab || renderedPage is null || PageHost.Children.Count != 1 ||
                !ReferenceEquals(PageHost.Children[0], renderedPage))
                return;

            var buttons = PageHost.GetVisualDescendants().OfType<Button>()
                .Where(button => button.Focusable && button.IsEffectivelyVisible).ToArray();
            var stableTargetIndex = string.IsNullOrEmpty(focusAutomationId)
                ? -1
                : Array.FindIndex(buttons, button => AutomationProperties.GetAutomationId(button) == focusAutomationId);
            var targetIndex = stableTargetIndex >= 0 ? stableTargetIndex : focusIndex;
            if (targetIndex < 0 || buttons.Length == 0)
                return;
            targetIndex = Math.Clamp(targetIndex, 0, buttons.Length - 1);
            var focusTarget = buttons[targetIndex];
            if (!focusTarget.IsEffectivelyEnabled)
                focusTarget = buttons.Select((button, index) => (button, index))
                    .Where(item => item.button.IsEffectivelyEnabled)
                    .OrderBy(item => Math.Abs(item.index - targetIndex))
                    .Select(item => item.button)
                    .FirstOrDefault()!;
            focusTarget?.Focus();
        }, DispatcherPriority.Input);
    }

    private async Task RefreshVpnStatusAsync()
    {
        if (!OperatingSystem.IsWindows() || _vpnActionInProgress || _vpnCancelInProgress || _vpnStatusRefreshRunning)
            return;

        _vpnStatusRefreshRunning = true;
        try
        {
            var previousSnapshot = _vpnSnapshot;
            var previousDisplayConnected = IsVpnDisplayConnected();
            var wasAvailable = _vpnServiceAvailable;
            var currentSnapshot = await _tunnelClient.GetStatusAsync();
            if (_vpnActionInProgress || _vpnCancelInProgress)
                return;
            _vpnSnapshot = currentSnapshot;
            _vpnServiceAvailable = true;
            UpdateMapLocationForVpnState(previousSnapshot.State, _vpnSnapshot.State);
            if (previousSnapshot != _vpnSnapshot || wasAvailable != _vpnServiceAvailable ||
                previousDisplayConnected != IsVpnDisplayConnected())
                RenderActiveTabPreservingScroll();
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            if (_vpnActionInProgress || _vpnCancelInProgress)
                return;
            var wasAvailable = _vpnServiceAvailable;
            var previousSnapshot = _vpnSnapshot;
            _vpnServiceAvailable = false;
            _vpnSnapshot = VpnServiceUnavailableSnapshot();
            if (wasAvailable || previousSnapshot != _vpnSnapshot)
                RenderActiveTabPreservingScroll();
        }
        finally
        {
            _vpnStatusRefreshRunning = false;
        }
    }

    private WindowsTunnelSnapshot VpnServiceUnavailableSnapshot() => new("error",
        Copy("Не удалось узнать состояние VPN: служба не отвечает. Проверьте её в настройках и состояние VPN в Windows.",
            "VPN status is unknown: the service is not responding. Check it in settings and verify VPN status in Windows."));

    private void UpdateMapLocationForVpnState(string previousState, string currentState)
    {
        if (!_mapRegionEnabled || !_mapRegionConsentGranted ||
            currentState is "starting" or "checking" or "health_checking" or "degraded" or "unknown")
            return;

        var previousConnected = IsVpnTunnelActive(previousState);
        var currentConnected = IsVpnTunnelActive(currentState);
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
            (!_vpnServiceAvailable && _vpnSnapshot.State == "error") ||
            _vpnSnapshot.State is "starting" or "checking" or "health_checking" or "degraded" or "unknown")
            return;
        var isConnected = IsVpnTunnelActive(_vpnSnapshot.State);
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
                (useTunnel == IsVpnDisplayConnected()))
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
            var bundleRoot = WindowsPortableLayout.ResolveBundleRoot(AppContext.BaseDirectory);
            var start = new ProcessStartInfo
            {
                FileName = powerShellPath,
                Arguments = $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"{installerPath}\" -AllowedUserSid \"{sid}\" -BundleRoot \"{bundleRoot}\"",
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
            OperationCanceledException or System.ComponentModel.Win32Exception or
            System.Text.Json.JsonException;

    private void RenderActiveTabPreservingScroll()
    {
        var offset = PageScroll.Offset;
        ShowTab(_activeTab);
        PageScroll.Offset = offset;
    }

    private void ShowSignInDialog() => _ = ShowSetupWindowAsync();

    private async Task RestoreSessionAsync(CancellationToken cancellationToken = default)
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
        {
            RestoreImportedSubscription();
            return;
        }

        _sessionToken = token;
        var subscriptionSnapshotLoaded = false;
        try
        {
            await RefreshAccountDataAsync(token, cancellationToken);
            // A successful API response, including an explicit no-subscription
            // result, is authoritative. Do not replace it with the older offline cache.
            subscriptionSnapshotLoaded = _keysSnapshot is not null;
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or OperationCanceledException or IOException)
        {
            _account = null;
            _keysSnapshot = null;
            _routes = [];
            _profileLoadIssue = Copy("Не удалось загрузить данные аккаунта.", "Could not load account data.");
        }

        if (!subscriptionSnapshotLoaded)
            RestoreImportedSubscription();

        if (IsVisible)
            ShowTab(_activeTab);
    }

    private async Task<bool> RefreshAccountDataAsync(
        string token,
        CancellationToken cancellationToken = default,
        TelegramAccount? verifiedAccount = null,
        IProgress<string>? progress = null)
    {
        var accountTask = verifiedAccount is null
            ? _telegramApi.GetAccountAsync(token, cancellationToken)
            : Task.FromResult(verifiedAccount);
        var subscriptionTask = _telegramApi.GetSubscriptionAsync(token, cancellationToken, progress);
        var avatarTask = RefreshTelegramAvatarAsync(token, cancellationToken);
        var sessionsTask = LoadSessionsForRefreshAsync(token, cancellationToken);
        if (verifiedAccount is not null)
            _account = verifiedAccount;

        try
        {
            await Task.WhenAll(accountTask, subscriptionTask, avatarTask, sessionsTask);
            _account = await accountTask;
            _keysSnapshot = await subscriptionTask;
            _sessions = await sessionsTask ?? [];
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
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or OperationCanceledException or IOException)
        {
            if (accountTask.IsCompletedSuccessfully)
                _account = accountTask.Result;
            _keysSnapshot = null;
            _routes = [];
            _profileLoadIssue = error is TelegramApiException { Code: "subscription_invalid_routes_outbounds_empty" or
                "subscription_invalid_routes_outbounds_no_network" }
                ? Copy("Подписка не содержит доступных маршрутов. Проверь её и повтори обновление позже.",
                    "The subscription has no available routes. Check it and refresh again later.")
                : Copy("Не удалось загрузить маршруты. Повтори обновление позже.",
                    "Could not load routes. Try refreshing again later.");
            return false;
        }
    }

    private async Task<IReadOnlyList<TelegramAppSession>?> LoadSessionsForRefreshAsync(
        string token,
        CancellationToken cancellationToken)
    {
        try
        {
            var sessions = await _telegramApi.GetSessionsAsync(token, cancellationToken);
            _sessionLoadIssue = null;
            return sessions;
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            throw;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or OperationCanceledException or IOException)
        {
            _sessionLoadIssue = Copy("Проверь интернет и повтори обновление.",
                "Check your connection and refresh again.");
            return null;
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

        _profileRefreshInProgress = true;
        if (IsVisible)
            RenderActiveTabPreservingScroll();
        try
        {
            await RefreshAccountDataAsync(token);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or OperationCanceledException or IOException)
        {
            ShowInfoDialog(Copy("Не удалось обновить профиль", "Could not refresh profile"),
                Copy("Проверь интернет и повтори попытку.", "Check your connection and try again."));
        }
        finally
        {
            _profileRefreshInProgress = false;
            if (IsVisible)
                RenderActiveTabPreservingScroll();
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

        if (!_paymentFlow.TryEnterBillingFlow())
            return;

        try
        {
            _profileActionRetry = null;
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
                    [
                        (Copy("Открыть поддержку", "Open support"), Copy("В приложении", "In app"), "support"),
                        (Copy("Открыть Telegram", "Open Telegram"), Copy("Бот DEYTT", "DEYTT bot"), "telegram"),
                    ]);
                if (contact == "support")
                    await ShowSupportWindowAsync();
                else if (contact == "telegram")
                    OpenExternal("https://t.me/deyttbot");
                return;
            }

            if (subscription?.PaidActive == true && subscription.DeviceLimit is null)
            {
                var contact = await ChooseOptionAsync(
                    Copy("План без лимита устройств", "Unlimited device plan"),
                    Copy("Поддержка поможет продлить его с сохранением индивидуальных условий.",
                        "Support can renew it while preserving your custom terms."),
                    [
                        (Copy("Открыть поддержку", "Open support"), Copy("В приложении", "In app"), "support"),
                        (Copy("Открыть Telegram", "Open Telegram"), Copy("Бот DEYTT", "DEYTT bot"), "telegram"),
                    ]);
                if (contact == "support")
                    await ShowSupportWindowAsync();
                else if (contact == "telegram")
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
            var selectedTariff = tariffs.FirstOrDefault(tariff => tariff.Code == selectedPlan);
            await CreateQuoteAndCheckoutAsync(token, "preset", selectedPlan, null, null, null,
                selectedTariff?.Name);
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
        finally
        {
            _paymentFlow.ExitBillingFlow();
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
        int? extra,
        string? selectedPlanLabel = null)
    {
        try
        {
            _profileActionRetry = null;
            _profileActionStatus = Copy("Рассчитываем стоимость…", "Calculating the total…");
            ShowTab(MainTab.Profile);
            var checkout = await _paymentFlow.StartAsync(
                () => _telegramApi.GetQuoteAsync(token, kind, plan, months, devices, extra),
                quote => ChooseOptionAsync(Copy("Подтвердите сумму", "Confirm the total"),
                    BuildQuoteDetail(quote, kind),
                    [
                        (Copy("Оплатить картой", "Pay by card"),
                            Copy($"Защищённая страница · {quote.Rubles} ₽", $"Secure checkout · {quote.Rubles} ₽"), "platega"),
                        (Copy("Telegram Stars", "Telegram Stars"),
                            Copy($"Оплата через Telegram · ⭐ {quote.Stars}", $"Pay through Telegram · ⭐ {quote.Stars}"), "stars"),
                    ]),
                method => _telegramApi.CreateCheckoutAsync(token, kind, method, plan, months, devices, extra));
            if (_sessionToken != token)
                return;
            if (checkout is null)
            {
                _profileActionStatus = null;
                ShowTab(MainTab.Profile);
                return;
            }
            await PresentCheckoutAsync(checkout);
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
            if (_sessionToken != token)
                return;
            if (_paymentFlow.CanRetry)
            {
                var selection = selectedPlanLabel ?? plan ??
                    (months is { } duration
                        ? Copy($"План на {duration} мес.", $"{duration}-month plan")
                        : Copy("Выбранный план", "Selected plan"));
                _profileActionStatus = $"{selection} · {BillingError(error)}";
                _profileActionRetry = () => RetryPaymentFlowAsync(token);
                ShowTab(MainTab.Profile);
                return;
            }
            _profileActionStatus = null;
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Не удалось подготовить оплату", "Could not prepare payment"),
                BillingError(error));
        }
    }

    private string BuildQuoteDetail(TelegramQuote quote, string kind)
    {
        var duration = kind == "add_devices"
            ? Copy("до конца текущей подписки", "until the current subscription ends")
            : Copy($"{quote.Months} мес.", $"{quote.Months} months");
        return $"{quote.Name}\n{quote.Devices} {Copy("устройств", "devices")} · {duration}\n{quote.Rubles} ₽ · ⭐ {quote.Stars}";
    }

    private async Task RetryPaymentFlowAsync(string token)
    {
        if (_sessionToken != token)
            return;
        if (!_paymentFlow.TryEnterBillingFlow())
            return;
        try
        {
            _profileActionRetry = null;
            _profileActionStatus = Copy("Повторяем подготовку счёта…", "Retrying checkout preparation…");
            ShowTab(MainTab.Profile);
            var checkout = await _paymentFlow.RetryAsync();
            if (_sessionToken != token)
                return;
            if (checkout is null)
            {
                _profileActionStatus = null;
                ShowTab(MainTab.Profile);
                return;
            }
            await PresentCheckoutAsync(checkout);
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
            if (_sessionToken != token)
                return;
            _profileActionStatus = BillingError(error);
            _profileActionRetry = () => RetryPaymentFlowAsync(token);
            ShowTab(MainTab.Profile);
        }
        finally
        {
            _paymentFlow.ExitBillingFlow();
        }
    }

    private async Task PresentCheckoutAsync(TelegramCheckout checkout)
    {
        var hasPendingId = _paymentFlow.PendingPaymentId is not null;
        _profileActionStatus = !hasPendingId
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
                .. (!hasPendingId
                    ? Array.Empty<(string Title, string Detail, string Value)>()
                    : new[] { (Copy("Проверить статус", "Check status"), Copy("Запросить подтверждение у сервера", "Ask the server for confirmation"), "check") }),
            ]);
        if (nextStep == "open")
            OpenExternal(checkout.PaymentUrl);
        else if (nextStep == "check")
            await CheckPaymentStatusAsync();
    }

    private async Task CheckPaymentStatusAsync()
    {
        var token = _sessionToken;
        var externalId = _paymentFlow.PendingPaymentId;
        if (token is null || externalId is null)
        {
            ShowInfoDialog(Copy("Нет ожидающего платежа", "No pending payment"),
                Copy("Создайте счёт в разделе тарифов и оплаты.", "Create a checkout from Plans and payment."));
            return;
        }

        _profileActionRetry = null;
        _profileActionStatus = Copy("Проверяем платёж…", "Checking payment…");
        ShowTab(MainTab.Profile);
        try
        {
            var status = await _telegramApi.GetPaymentStatusAsync(token, externalId);
            if (_sessionToken != token)
                return;
            if (status == "paid")
            {
                var refreshed = await RefreshAccountDataAsync(token);
                _paymentFlow.ApplyPaymentStatus(status, externalId, refreshed);
                _profileActionStatus = refreshed
                    ? Copy("Оплата подтверждена", "Payment confirmed")
                    : Copy("Оплата подтверждена, но данные подписки не обновились.",
                        "Payment confirmed, but subscription details could not be refreshed.");
                ShowTab(MainTab.Profile);
                ShowInfoDialog(Copy("Оплата подтверждена", "Payment confirmed"),
                    refreshed
                        ? Copy("Данные подписки обновлены.", "Your subscription details have been refreshed.")
                        : Copy("Оплата прошла. Обновите профиль позже, чтобы загрузить данные подписки.",
                            "Payment succeeded. Refresh Profile later to load subscription details."));
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
            _profileActionStatus = BillingError(error);
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
        var choices = new StackPanel { Spacing = 7 };
        var optionButtons = new List<(string Value, Button Button)>();
        foreach (var option in options)
        {
            var labels = new StackPanel { Spacing = 3 };
            labels.Children.Add(DeyttTheme.TextBlock(option.Title, 15, DeyttTheme.Text, FontWeight.SemiBold));
            if (!string.IsNullOrWhiteSpace(option.Detail))
                labels.Children.Add(DeyttTheme.TextBlock(option.Detail, 12, DeyttTheme.Muted));
            var button = DeyttTheme.Action(labels, () => { });
            button.Padding = new Thickness(13, 10);
            optionButtons.Add((option.Value, button));
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
            DeyttTheme.Muted, FontWeight.SemiBold), () => { });
        cancel.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        cancel.Margin = new Thickness(0, 3, 0, 0);
        choices.Children.Add(cancel);
        var content = new StackPanel
        {
            Spacing = 12,
            Children =
            {
                DeyttTheme.TextBlock(detail, 13, DeyttTheme.Muted),
                choices,
            },
        };
        var result = await ShowContentInShellAsync(title, content, 560, modal =>
        {
            foreach (var (value, button) in optionButtons)
                button.Click += (_, _) => modal.Close(value);
            cancel.Click += (_, _) => modal.Close();
        });
        return result as string;
    }

    private async Task<int?> ShowNumberDialogAsync(string title, string detail, int minimum, int maximum)
    {
        var value = new NumericUpDown
        {
            Minimum = minimum,
            Maximum = maximum,
            Increment = 1,
            Value = minimum,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
        };
        var cancel = DeyttTheme.Action(DeyttTheme.TextBlock(Copy("Отмена", "Cancel"), 13,
            DeyttTheme.Muted, FontWeight.SemiBold), () => { });
        cancel.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        var confirm = DeyttTheme.PrimaryButton(Copy("Продолжить", "Continue"), () => { });
        var content = new StackPanel
        {
            Spacing = 13,
            Children =
            {
                DeyttTheme.TextBlock(detail, 13, DeyttTheme.Muted),
                value,
                cancel,
                confirm,
            },
        };
        var result = await ShowContentInShellAsync(title, content, 460, modal =>
        {
            cancel.Click += (_, _) => modal.Close();
            if (confirm.Child is Button confirmButton)
                confirmButton.Click += (_, _) =>
                {
                    if (value.Value is { } number)
                        modal.Close(decimal.ToInt32(number));
                };
        });
        return result is int selected ? selected : null;
    }

    private async Task ToggleHappDeviceAsync(TelegramHappDevice device)
    {
        var token = _sessionToken;
        if (token is null || device.Id <= 0 || _happDeviceChangesInFlight.Contains(device.Id))
            return;
        var nextBlocked = !device.Blocked;
        var title = nextBlocked
            ? Copy("Заблокировать устройство?", "Block this device?")
            : Copy("Вернуть устройство?", "Restore this device?");
        var detail = nextBlocked
            ? Copy("Устройство больше не сможет обновлять подписку. Уже загруженные ключи и активное VPN-подключение продолжат работать. Чтобы отозвать ключи, используйте общий сброс Happ.",
                "This device will no longer be able to refresh its subscription. Downloaded keys and an active VPN connection will keep working. Use the Happ key reset to revoke keys.")
            : Copy("Устройство снова сможет обновлять подписку. Сервер проверит, есть ли свободное место в вашем плане.",
                "This device will be able to refresh its subscription again. The server will check for a free device slot in your plan.");
        if (!await ConfirmDialogAsync(title, detail,
                nextBlocked ? Copy("Заблокировать", "Block") : Copy("Восстановить", "Restore")))
            return;
        if (_sessionToken != token || !_happDeviceChangesInFlight.Add(device.Id))
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
        catch (TelegramApiException error) when (error.Code is
                   "device_limit_reached" or "device_slot_limit_reached" or
                   "device_not_found" or "happ_device_not_found")
        {
            _happDeviceChangesInFlight.Remove(device.Id);
            await ShowHappDeviceActionErrorAsync(error, device, token);
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            ShowInfoDialog(Copy("Не удалось обновить устройство", "Could not update the device"),
                Copy("Сервер не подтвердил изменение. Проверь интернет и обнови список.",
                    "The server did not confirm the change. Check your connection and refresh the list."));
        }
        finally
        {
            _happDeviceChangesInFlight.Remove(device.Id);
        }
    }

    private Task<bool> ConfirmDialogAsync(string title, string message, string confirmLabel,
        string? cancelLabel = null) =>
        ConfirmInShellAsync(title, message, confirmLabel, cancelLabel);

    private async Task ManageSessionAsync(TelegramAppSession session)
    {
        var sessionToken = _sessionToken;
        if (_sessionRevokesInFlight.Contains(session.Id))
            return;
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
        if (string.IsNullOrWhiteSpace(session.Id))
        {
            ShowInfoDialog(session.Label, detail);
            return;
        }
        var confirmation = detail + "\n" + Copy(
            "На другом устройстве потребуется снова войти через Telegram. VPN-ключи останутся действительными.",
            "The other device will need to sign in through Telegram again. VPN keys will remain valid.");
        if (!await ConfirmDialogAsync(Copy("Завершить сеанс?", "End this session?"), confirmation,
                Copy("Завершить", "End session")))
            return;

        var token = _sessionToken;
        if (token is null)
        {
            ShowSignInDialog();
            return;
        }
        if (token != sessionToken)
            return;
        if (!_sessionRevokesInFlight.Add(session.Id))
            return;
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
        finally
        {
            _sessionRevokesInFlight.Remove(session.Id);
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
        ClearTelegramAvatar();
        _keysSnapshot = null;
        _sessions = [];
        _routes = [];
        _selectedRoute = "auto";
        _paymentFlow.Reset();
        _profileActionStatus = null;
        _profileActionRetry = null;
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

        using var pairingCancellation = new CancellationTokenSource();
        ShellContentDialog? pairingDialog = null;
        var content = new StackPanel
        {
            Spacing = 13,
            Children = { title, detail, usernameInput, codeInput, status, openBot, primary },
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
                pairingDialog?.Close();
                return;
            }

            primaryButton.IsEnabled = false;
            try
            {
                if (step == 0)
                {
                    var username = usernameInput.Text?.Trim() ?? "";
                    SetStatus(Copy("Создаю запрос на вход…", "Starting sign-in…"), DeyttTheme.Blue);
                    var pairing = await _telegramApi.StartPairingAsync(username, pairingCancellation.Token);
                    if (pairingCancellation.IsCancellationRequested || pairingDialog?.Completion.IsCompleted == true)
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
                    var pairing = await _telegramApi.VerifyPairingAsync(challenge, code, pairingCancellation.Token);
                    if (pairingCancellation.IsCancellationRequested || pairingDialog?.Completion.IsCompleted == true)
                        return;
                    WindowsSessionStore.Save(pairing.Token);
                    _sessionToken = pairing.Token;
                    _account = pairing.Account;
                    ShowTab(_activeTab);
                    var refreshFailed = false;
                    try
                    {
                        SetStatus(Copy("Код подтверждён. Загружаю подписку и маршруты…",
                            "Code verified. Loading your subscription and routes…"), DeyttTheme.Blue);
                        var progress = new Progress<string>(stage =>
                        {
                            if (pairingCancellation.IsCancellationRequested ||
                                pairingDialog?.Completion.IsCompleted == true)
                                return;
                            var message = stage switch
                            {
                                "metadata" => Copy("Проверяю подписку…", "Checking your subscription…"),
                                "profile" => Copy("Загружаю маршруты…", "Loading routes…"),
                                "optional" => Copy("Проверяю дополнительные маршруты…", "Checking optional routes…"),
                                "ready" => Copy("Маршруты готовы. Завершаю вход…", "Routes are ready. Finishing sign-in…"),
                                _ => Copy("Загружаю данные аккаунта…", "Loading account data…"),
                            };
                            SetStatus(message, DeyttTheme.Blue);
                        });
                        refreshFailed = !await RefreshAccountDataAsync(
                            pairing.Token, pairingCancellation.Token, pairing.Account, progress);
                    }
                    catch (TelegramApiException error) when (error.IsUnauthorized)
                    {
                        ClearLocalSession();
                        throw;
                    }
                    catch (OperationCanceledException) when (pairingCancellation.IsCancellationRequested)
                    {
                        throw;
                    }
                    catch (Exception error) when (error is TelegramApiException or HttpRequestException or OperationCanceledException or IOException)
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
            catch (OperationCanceledException) when (pairingCancellation.IsCancellationRequested ||
                                                       pairingDialog?.Completion.IsCompleted == true)
            {
                // Closing the dialog cancels its in-flight request; it is not a timeout.
            }
            catch (OperationCanceledException)
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
                if (!pairingCancellation.IsCancellationRequested && pairingDialog?.Completion.IsCompleted != true)
                    primaryButton.IsEnabled = true;
            }
        };

        SetStep(0);
        try
        {
            await ShowContentInShellAsync(
                Copy("Вход через Telegram", "Telegram sign-in"), content, 500,
                modal =>
                {
                    pairingDialog = modal;
                    modal.Closed += () => pairingCancellation.Cancel();
                });
        }
        finally
        {
            if (!pairingCancellation.IsCancellationRequested)
                pairingCancellation.Cancel();
        }
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

    private string BillingError(Exception error)
    {
        if (error is not TelegramApiException apiError)
            return Copy("Не удалось связаться с сервером. Проверьте интернет и повторите попытку.",
                "Could not reach the server. Check your connection and retry.");

        return apiError.Code switch
        {
            "rate_limited" => Copy("Слишком частые запросы. Повторите позже.",
                "Too many requests. Try again later."),
            "payment_unavailable" => Copy("Оплата сейчас недоступна. Повторите позже.",
                "Payments are unavailable right now. Try again later."),
            "payment_not_found" => Copy("Сервер не нашёл этот платёж. Проверьте счёт в профиле.",
                "The server could not find this payment. Check the checkout in Profile."),
            "bad_custom_params" or "bad_plan" => Copy("Сервер не смог рассчитать выбранный план. Проверьте параметры.",
                "The server could not price this plan. Check your selection."),
            "device_limit_exceeded" or "invalid_extra" or "devices_max" =>
                Copy("Выберите меньше дополнительных устройств.", "Choose fewer additional devices."),
            "subscription_unlimited" or "unlimited_subscription" =>
                Copy("Бессрочный доступ не требует продления.", "Lifetime access does not need renewal."),
            "unlimited_devices_plan" => Copy("Изменить этот план поможет поддержка.",
                "Contact support to change this plan."),
            var code when code.Length is > 0 and <= 64 &&
                code.All(character => char.IsAsciiLetterOrDigit(character) || character is '_' or '-') =>
                Copy($"Ошибка сервера: {code}", $"Server error: {code}"),
            _ => Copy("Сервер не смог выполнить запрос. Повторите попытку позже.",
                "The server could not complete the request. Try again later."),
        };
    }

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
        if (subscription.TrafficLimitBytes is > 0)
        {
            if (subscription.TrafficTotalBytes is >= 0 and long trafficTotal)
                details.Add($"{Copy("всего передано", "total transferred")}: {FormatBytes(trafficTotal)}");
        }
        else
        {
            var trafficUsed = Math.Max(0, subscription.TrafficUsedBytes ?? 0);
            var trafficTotal = Math.Max(0, subscription.TrafficTotalBytes ?? 0);
            var transferred = trafficTotal > 0 ? trafficTotal : trafficUsed;
            if (transferred > 0)
                details.Add($"{Copy("передано", "transferred")}: {FormatBytes(transferred)} · {Copy("без установленного лимита", "no quota limit")}");
        }
        return details.Count > 0 ? string.Join(" · ", details) : Copy("Подписка активна", "Subscription active");
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024)
            return $"{bytes} b";
        var units = new[] { "kb", "mb", "gb", "tb" };
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

    private async void ShowInfoDialog(string title, string message) =>
        await ShowInfoInShellAsync(title, message);

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

    private string Copy(string russian, string english) => (_language == "ru" ? russian : english).ToLowerInvariant();
}

