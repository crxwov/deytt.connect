using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    // These forwarding properties keep the existing command/controller code small
    // while making each page's presentation state owned by its own view model.
    private string _language { get => _shellViewModel.Language; set => _shellViewModel.Language = value; }
    private bool _systemDarkTheme { get => _shellViewModel.SystemDarkTheme; set => _shellViewModel.SystemDarkTheme = value; }
    private bool _useSystemWindowChrome { get => _shellViewModel.UseSystemWindowChrome; set => _shellViewModel.UseSystemWindowChrome = value; }
    private bool _compactLayout { get => _shellViewModel.CompactLayout; set => _shellViewModel.CompactLayout = value; }
    private bool _mediumLayout { get => _shellViewModel.MediumLayout; set => _shellViewModel.MediumLayout = value; }
    private bool _shortCompactLayout { get => _shellViewModel.ShortCompactLayout; set => _shellViewModel.ShortCompactLayout = value; }

    private WindowsTunnelSnapshot _vpnSnapshot { get => _homeViewModel.Tunnel; set => _homeViewModel.Tunnel = value; }
    private bool _vpnServiceAvailable { get => _homeViewModel.VpnServiceAvailable; set => _homeViewModel.VpnServiceAvailable = value; }
    private bool _vpnActionInProgress { get => _homeViewModel.VpnActionInProgress; set => _homeViewModel.VpnActionInProgress = value; }
    private WindowsNetworkLocation? _mapOriginLocation { get => _homeViewModel.OriginLocation; set => _homeViewModel.OriginLocation = value; }
    private WindowsNetworkLocation? _mapEgressLocation { get => _homeViewModel.EgressLocation; set => _homeViewModel.EgressLocation = value; }
    private string? _mapLocationIssue { get => _homeViewModel.LocationIssue; set => _homeViewModel.LocationIssue = value; }

    private string _selectedRoute { get => _routesViewModel.SelectedRoute; set => _routesViewModel.SelectedRoute = value; }
    private IReadOnlyList<WindowsRoute> _routes { get => _routesViewModel.Routes; set => _routesViewModel.Routes = value; }
    private HashSet<string> _expandedRouteCountries { get => _routesViewModel.ExpandedCountries; set => _routesViewModel.ExpandedCountries = value; }
    private bool _routeExpansionInitialized { get => _routesViewModel.ExpansionInitialized; set => _routesViewModel.ExpansionInitialized = value; }
    private IReadOnlyDictionary<string, WindowsRouteProbeResult> _routeProbeResults { get => _routesViewModel.ProbeResults; set => _routesViewModel.ProbeResults = value; }
    private bool _routeProbeInProgress { get => _routesViewModel.ProbeInProgress; set => _routesViewModel.ProbeInProgress = value; }
    private bool _routeProbeCancelRequested { get => _routesViewModel.ProbeCancelRequested; set => _routesViewModel.ProbeCancelRequested = value; }
    private HashSet<string> _activeRouteProbeTags { get => _routesViewModel.ActiveProbeTags; set => _routesViewModel.ActiveProbeTags = value; }

    private TelegramAccount? _account { get => _profileViewModel.Account; set => _profileViewModel.Account = value; }
    private TelegramKeysSnapshot? _keysSnapshot { get => _profileViewModel.KeysSnapshot; set => _profileViewModel.KeysSnapshot = value; }
    private IReadOnlyList<TelegramAppSession> _sessions { get => _profileViewModel.Sessions; set => _profileViewModel.Sessions = value; }
    private string? _profileLoadIssue { get => _profileViewModel.ProfileLoadIssue; set => _profileViewModel.ProfileLoadIssue = value; }
    private string? _sessionLoadIssue { get => _profileViewModel.SessionLoadIssue; set => _profileViewModel.SessionLoadIssue = value; }
    private string? _profileActionStatus { get => _profileViewModel.ActionStatus; set => _profileViewModel.ActionStatus = value; }
    private Func<Task>? _profileActionRetry { get => _profileViewModel.ActionRetry; set => _profileViewModel.ActionRetry = value; }
    private bool _profileRefreshInProgress { get => _profileViewModel.RefreshInProgress; set => _profileViewModel.RefreshInProgress = value; }
    private bool _profileDevicesExpanded { get => _profileViewModel.DevicesExpanded; set => _profileViewModel.DevicesExpanded = value; }
    private bool _profileHelpExpanded { get => _profileViewModel.HelpExpanded; set => _profileViewModel.HelpExpanded = value; }
    private WindowsPaymentFlowState _paymentFlow => _profileViewModel.PaymentFlow;

    private string _probeMethod { get => _settingsViewModel.ProbeMethod; set => _settingsViewModel.ProbeMethod = value; }
    private bool _mapRegionEnabled { get => _settingsViewModel.RegionEnabled; set => _settingsViewModel.RegionEnabled = value; }
    private bool _mapRegionConsentGranted { get => _settingsViewModel.RegionConsentGranted; set => _settingsViewModel.RegionConsentGranted = value; }
    private bool _mapRegionConsentAsked { get => _settingsViewModel.RegionConsentAsked; set => _settingsViewModel.RegionConsentAsked = value; }
    private bool _reduceMotion { get => _settingsViewModel.ReduceMotion; set => _settingsViewModel.ReduceMotion = value; }
    private string _themePreference { get => _settingsViewModel.ThemePreference; set => _settingsViewModel.ThemePreference = value; }
    private string _windowChromePreference { get => _settingsViewModel.WindowChromePreference; set => _settingsViewModel.WindowChromePreference = value; }
    private string? _preferencesIssue { get => _settingsViewModel.PreferencesIssue; set => _settingsViewModel.PreferencesIssue = value; }
    private bool _settingsAppearanceExpanded { get => _settingsViewModel.AppearanceExpanded; set => _settingsViewModel.AppearanceExpanded = value; }
    private bool _settingsConnectionExpanded { get => _settingsViewModel.ConnectionExpanded; set => _settingsViewModel.ConnectionExpanded = value; }
    private bool _settingsPrivacyExpanded { get => _settingsViewModel.PrivacyExpanded; set => _settingsViewModel.PrivacyExpanded = value; }
    private bool _settingsSubscriptionExpanded { get => _settingsViewModel.SubscriptionExpanded; set => _settingsViewModel.SubscriptionExpanded = value; }
}
