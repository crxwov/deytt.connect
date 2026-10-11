using System.ComponentModel;
using System.Runtime.CompilerServices;
using Avalonia;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;

namespace DeyttConnect.Windows.Views;

internal abstract class ObservablePageViewModel : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected bool SetProperty<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
            return false;

        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        return true;
    }
}

internal sealed class ShellViewModel : ObservablePageViewModel
{
    private string _language = "ru";
    private bool _systemDarkTheme = true;
    private bool _useSystemWindowChrome;
    private string _activePage = "Home";
    private bool _compactLayout;
    private bool _mediumLayout;
    private bool _shortCompactLayout;

    public string Language { get => _language; set => SetProperty(ref _language, value); }
    public bool SystemDarkTheme { get => _systemDarkTheme; set => SetProperty(ref _systemDarkTheme, value); }
    public bool UseSystemWindowChrome { get => _useSystemWindowChrome; set => SetProperty(ref _useSystemWindowChrome, value); }
    public string ActivePage { get => _activePage; set => SetProperty(ref _activePage, value); }
    public bool CompactLayout { get => _compactLayout; set => SetProperty(ref _compactLayout, value); }
    public bool MediumLayout { get => _mediumLayout; set => SetProperty(ref _mediumLayout, value); }
    public bool ShortCompactLayout { get => _shortCompactLayout; set => SetProperty(ref _shortCompactLayout, value); }
}

internal sealed class HomeViewModel : ObservablePageViewModel
{
    private WindowsTunnelSnapshot _tunnel = new("disconnected", "VPN выключен");
    private bool _vpnServiceAvailable;
    private bool _vpnActionInProgress;
    private WindowsNetworkLocation? _originLocation;
    private WindowsNetworkLocation? _egressLocation;
    private string? _locationIssue;

    public WindowsTunnelSnapshot Tunnel { get => _tunnel; set => SetProperty(ref _tunnel, value); }
    public bool VpnServiceAvailable { get => _vpnServiceAvailable; set => SetProperty(ref _vpnServiceAvailable, value); }
    public bool VpnActionInProgress { get => _vpnActionInProgress; set => SetProperty(ref _vpnActionInProgress, value); }
    public WindowsNetworkLocation? OriginLocation { get => _originLocation; set => SetProperty(ref _originLocation, value); }
    public WindowsNetworkLocation? EgressLocation { get => _egressLocation; set => SetProperty(ref _egressLocation, value); }
    public string? LocationIssue { get => _locationIssue; set => SetProperty(ref _locationIssue, value); }
}

internal sealed class RoutesViewModel : ObservablePageViewModel
{
    private string _selectedRoute = "auto";
    private IReadOnlyList<WindowsRoute> _routes = [];
    private HashSet<string> _expandedCountries = new(StringComparer.Ordinal);
    private bool _expansionInitialized;
    private IReadOnlyDictionary<string, WindowsRouteProbeResult> _probeResults =
        new Dictionary<string, WindowsRouteProbeResult>(StringComparer.Ordinal);
    private bool _probeInProgress;
    private bool _probeCancelRequested;
    private HashSet<string> _activeProbeTags = new(StringComparer.Ordinal);

    public string SelectedRoute { get => _selectedRoute; set => SetProperty(ref _selectedRoute, value); }
    public IReadOnlyList<WindowsRoute> Routes { get => _routes; set => SetProperty(ref _routes, value); }
    public HashSet<string> ExpandedCountries { get => _expandedCountries; set => SetProperty(ref _expandedCountries, value); }
    public bool ExpansionInitialized { get => _expansionInitialized; set => SetProperty(ref _expansionInitialized, value); }
    public IReadOnlyDictionary<string, WindowsRouteProbeResult> ProbeResults { get => _probeResults; set => SetProperty(ref _probeResults, value); }
    public bool ProbeInProgress { get => _probeInProgress; set => SetProperty(ref _probeInProgress, value); }
    public bool ProbeCancelRequested { get => _probeCancelRequested; set => SetProperty(ref _probeCancelRequested, value); }
    public HashSet<string> ActiveProbeTags { get => _activeProbeTags; set => SetProperty(ref _activeProbeTags, value); }
}

internal sealed class ProfileViewModel : ObservablePageViewModel
{
    private TelegramAccount? _account;
    private TelegramKeysSnapshot? _keysSnapshot;
    private IReadOnlyList<TelegramAppSession> _sessions = [];
    private string? _profileLoadIssue;
    private string? _sessionLoadIssue;
    private string? _actionStatus;
    private Func<Task>? _actionRetry;
    private bool _refreshInProgress;
    private bool _devicesExpanded;
    private bool _helpExpanded;
    private readonly WindowsPaymentFlowState _paymentFlow = new();

    public TelegramAccount? Account { get => _account; set => SetProperty(ref _account, value); }
    public TelegramKeysSnapshot? KeysSnapshot { get => _keysSnapshot; set => SetProperty(ref _keysSnapshot, value); }
    public IReadOnlyList<TelegramAppSession> Sessions { get => _sessions; set => SetProperty(ref _sessions, value); }
    public string? ProfileLoadIssue { get => _profileLoadIssue; set => SetProperty(ref _profileLoadIssue, value); }
    public string? SessionLoadIssue { get => _sessionLoadIssue; set => SetProperty(ref _sessionLoadIssue, value); }
    public string? ActionStatus { get => _actionStatus; set => SetProperty(ref _actionStatus, value); }
    public Func<Task>? ActionRetry { get => _actionRetry; set => SetProperty(ref _actionRetry, value); }
    public bool RefreshInProgress { get => _refreshInProgress; set => SetProperty(ref _refreshInProgress, value); }
    public bool DevicesExpanded { get => _devicesExpanded; set => SetProperty(ref _devicesExpanded, value); }
    public bool HelpExpanded { get => _helpExpanded; set => SetProperty(ref _helpExpanded, value); }
    public WindowsPaymentFlowState PaymentFlow => _paymentFlow;
}

internal sealed class SettingsViewModel : ObservablePageViewModel
{
    private string _probeMethod = "HEAD";
    private bool _regionEnabled;
    private bool _regionConsentGranted;
    private bool _regionConsentAsked;
    private bool _reduceMotion;
    private string _themePreference = "system";
    private string _windowChromePreference = "deytt";
    private string? _preferencesIssue;
    private bool _appearanceExpanded = true;
    private bool _connectionExpanded;
    private bool _privacyExpanded;
    private bool _subscriptionExpanded;

    public string ProbeMethod { get => _probeMethod; set => SetProperty(ref _probeMethod, value); }
    public bool RegionEnabled { get => _regionEnabled; set => SetProperty(ref _regionEnabled, value); }
    public bool RegionConsentGranted { get => _regionConsentGranted; set => SetProperty(ref _regionConsentGranted, value); }
    public bool RegionConsentAsked { get => _regionConsentAsked; set => SetProperty(ref _regionConsentAsked, value); }
    public bool ReduceMotion { get => _reduceMotion; set => SetProperty(ref _reduceMotion, value); }
    public string ThemePreference { get => _themePreference; set => SetProperty(ref _themePreference, value); }
    public string WindowChromePreference { get => _windowChromePreference; set => SetProperty(ref _windowChromePreference, value); }
    public string? PreferencesIssue { get => _preferencesIssue; set => SetProperty(ref _preferencesIssue, value); }
    public bool AppearanceExpanded { get => _appearanceExpanded; set => SetProperty(ref _appearanceExpanded, value); }
    public bool ConnectionExpanded { get => _connectionExpanded; set => SetProperty(ref _connectionExpanded, value); }
    public bool PrivacyExpanded { get => _privacyExpanded; set => SetProperty(ref _privacyExpanded, value); }
    public bool SubscriptionExpanded { get => _subscriptionExpanded; set => SetProperty(ref _subscriptionExpanded, value); }
}
