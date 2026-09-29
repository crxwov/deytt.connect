using System.Text;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Platform;
using Avalonia.Threading;
using DeyttConnect.Windows.Services;
using Microsoft.Web.WebView2.Core;

namespace DeyttConnect.Windows.Controls;

/// <summary>
/// Hosts the bundled offline network atlas in WebView2 on Windows and keeps the
/// native illustration available when WebView2 is unavailable.
/// </summary>
public sealed class RouteGlobeWebView : ContentControl
{
    private const string AssetHost = "deytt-atlas.example";
    private static readonly Uri AtlasUri = new($"https://{AssetHost}/index.html");
    private static readonly HashSet<string> RouteKeys = new(StringComparer.Ordinal)
    {
        "auto", "nl", "de", "fi", "ru", "ru-de",
    };
    private static readonly HashSet<string> LocationKeys = new(StringComparer.Ordinal)
    {
        "nl", "de", "fi", "ru",
    };
    private static readonly HashSet<string> TapKeys = new(StringComparer.Ordinal)
    {
        "nl", "de", "fi", "ru", "user",
    };

    private readonly RouteMapIllustration _fallback = new();
    private NativeWebView? _webView;
    private string _selectedRoute = "auto";
    private string _language = "ru";
    private IReadOnlyCollection<string> _availableLocations = ["nl", "de", "fi", "ru"];
    private bool _originConsentGranted;
    private WindowsNetworkLocation? _originLocation;
    private MapCoordinate? _exitCoordinate;
    private string? _egressCountryCode;
    private string? _activeAutoRouteKey;
    private bool _trafficActive;
    private bool _usingFallback;
    private bool _pageReady;
    private bool _statePushQueued;
    private int _stateRevision;
    private int _appliedRevision;

    public RouteGlobeWebView()
    {
        MinHeight = 230;
        ClipToBounds = true;
        Background = Brushes.Transparent;
        _fallback.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        _fallback.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Stretch;

        if (!OperatingSystem.IsWindows() || !Directory.Exists(AtlasAssetDirectory))
        {
            UseFallback();
            return;
        }

        try
        {
            var webView = new NativeWebView
            {
                Background = Brushes.Transparent,
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Stretch,
            };
            webView.AdapterCreated += OnAdapterCreated;
            webView.NavigationStarted += OnNavigationStarted;
            webView.NavigationCompleted += OnNavigationCompleted;
            webView.NewWindowRequested += OnNewWindowRequested;
            webView.WebMessageReceived += OnWebMessageReceived;
            _webView = webView;
            Content = webView;
        }
        catch
        {
            UseFallback();
        }

        UpdateFallback();
    }

    /// <summary>Raised when the atlas reports a selectable node code.</summary>
    public event Action<string>? NodeTapped;

    /// <summary>Selected atlas route: auto, nl, de, fi, ru, or ru-de.</summary>
    public string SelectedRoute
    {
        get => _selectedRoute;
        set
        {
            var next = RouteKeyFor(value);
            if (_selectedRoute == next)
                return;
            _selectedRoute = next;
            QueueStateUpdate();
        }
    }

    /// <summary>The atlas supports the same Russian and English labels as Android.</summary>
    public string Language
    {
        get => _language;
        set
        {
            var next = string.Equals(value, "en", StringComparison.OrdinalIgnoreCase) ? "en" : "ru";
            if (_language == next)
                return;
            _language = next;
            QueueStateUpdate();
        }
    }

    /// <summary>Available exit-node keys. The synthetic user origin is never an exit.</summary>
    public IReadOnlyCollection<string> AvailableLocations
    {
        get => _availableLocations;
        set
        {
            var next = (value ?? Array.Empty<string>())
                .Select(key => key?.Trim().ToLowerInvariant())
                .Where(key => key is not null && LocationKeys.Contains(key))
                .Cast<string>()
                .Distinct(StringComparer.Ordinal)
                .ToArray();
            if (_availableLocations.SequenceEqual(next, StringComparer.Ordinal))
                return;
            _availableLocations = next;
            QueueStateUpdate();
        }
    }

    /// <summary>
    /// Approximate origin is retained and shown only while explicit consent is active.
    /// Turning consent off clears the location from this control.
    /// </summary>
    public bool OriginConsentGranted
    {
        get => _originConsentGranted;
        set
        {
            if (_originConsentGranted == value)
                return;
            _originConsentGranted = value;
            if (!value)
                _originLocation = null;
            QueueStateUpdate();
        }
    }

    /// <summary>Approximate IP-derived city data; ignored unless consent is already granted.</summary>
    public WindowsNetworkLocation? OriginLocation
    {
        get => _originConsentGranted ? _originLocation : null;
        set
        {
            var next = _originConsentGranted && IsValidLocation(value) ? value : null;
            if (_originLocation == next)
                return;
            _originLocation = next;
            QueueStateUpdate();
        }
    }

    /// <summary>Fallback endpoint coordinate for the currently selected route.</summary>
    public MapCoordinate? ExitCoordinate
    {
        get => _exitCoordinate;
        set
        {
            var next = IsValidCoordinate(value) ? value : null;
            if (_exitCoordinate == next)
                return;
            _exitCoordinate = next;
            QueueStateUpdate();
        }
    }

    /// <summary>Approximate active exit country for an automatic route.</summary>
    public string? EgressCountryCode
    {
        get => _egressCountryCode;
        set
        {
            var next = NormalizeCountryCode(value);
            if (_egressCountryCode == next)
                return;
            _egressCountryCode = next;
            QueueStateUpdate();
        }
    }

    /// <summary>Active automatic route key, including the ru-de double-hop key.</summary>
    public string? ActiveAutoRouteKey
    {
        get => _activeAutoRouteKey;
        set
        {
            var next = value?.Trim().ToLowerInvariant();
            if (next is not ("nl" or "de" or "fi" or "ru" or "ru-de"))
                next = null;
            if (_activeAutoRouteKey == next)
                return;
            _activeAutoRouteKey = next;
            QueueStateUpdate();
        }
    }

    /// <summary>Enables the atlas traffic animation only while the tunnel is active.</summary>
    public bool TrafficActive
    {
        get => _trafficActive;
        set
        {
            if (_trafficActive == value)
                return;
            _trafficActive = value;
            QueueStateUpdate();
        }
    }

    /// <summary>
    /// Translates route identifiers such as NL, RU-DE, or a route id containing those
    /// country tokens to the key understood by the Android atlas.
    /// </summary>
    public static string RouteKeyFor(string? selectedRouteId)
    {
        var value = selectedRouteId?.Trim().ToLowerInvariant();
        if (value is not null && RouteKeys.Contains(value))
            return value;

        var tokens = (selectedRouteId ?? string.Empty)
            .ToUpperInvariant()
            .Split(new[] { ' ', '-', '_', '/', '.', ':', '+', '\\' }, StringSplitOptions.RemoveEmptyEntries)
            .ToHashSet(StringComparer.Ordinal);
        if (tokens.Contains("RU") && tokens.Contains("DE"))
            return "ru-de";
        if (tokens.Contains("NL"))
            return "nl";
        if (tokens.Contains("DE"))
            return "de";
        if (tokens.Contains("FI"))
            return "fi";
        if (tokens.Contains("RU"))
            return "ru";
        return "auto";
    }

    private static string AtlasAssetDirectory => Path.Combine(AppContext.BaseDirectory, "Assets", "route-map");

    private void OnAdapterCreated(object? sender, WebViewAdapterEventArgs args)
    {
        if (_usingFallback || !ReferenceEquals(sender, _webView))
            return;

        try
        {
            if (args.TryGetPlatformHandle() is not IWindowsWebView2PlatformHandle handle ||
                handle.CoreWebView2 == IntPtr.Zero)
            {
                UseFallback();
                return;
            }

            var coreWebView2 = CoreWebView2.CreateFromComICoreWebView2(handle.CoreWebView2);
            coreWebView2.SetVirtualHostNameToFolderMapping(
                AssetHost,
                AtlasAssetDirectory,
                CoreWebView2HostResourceAccessKind.Deny);

            _webView!.Source = AtlasUri;
        }
        catch
        {
            UseFallback();
        }
    }

    private static void OnNavigationStarted(object? sender, WebViewNavigationStartingEventArgs args)
    {
        var request = args.Request;
        if (request is null)
        {
            args.Cancel = true;
            return;
        }

        var isBlank = request.IsAbsoluteUri &&
                      string.Equals(request.AbsoluteUri, "about:blank", StringComparison.OrdinalIgnoreCase);
        if (!isBlank && request != AtlasUri)
            args.Cancel = true;
    }

    private void OnNavigationCompleted(object? sender, WebViewNavigationCompletedEventArgs args)
    {
        if (_usingFallback || !ReferenceEquals(sender, _webView))
            return;

        if (!args.IsSuccess && args.Request == AtlasUri)
        {
            UseFallback();
            return;
        }

        if (args.IsSuccess && args.Request == AtlasUri)
        {
            _pageReady = true;
            QueueStateUpdate();
        }
    }

    private static void OnNewWindowRequested(object? sender, WebViewNewWindowRequestedEventArgs args) =>
        args.Handled = true;

    private void OnWebMessageReceived(object? sender, WebMessageReceivedEventArgs args)
    {
        var body = args.Body;
        if (_usingFallback || !ReferenceEquals(sender, _webView) || body is null || body.Length > 128)
            return;

        var node = body.Trim();
        if (node.StartsWith('"'))
        {
            try
            {
                node = JsonSerializer.Deserialize<string>(node) ?? string.Empty;
            }
            catch (JsonException)
            {
                return;
            }
        }

        node = node.Trim().ToLowerInvariant();
        if (!TapKeys.Contains(node))
            return;

        Dispatcher.UIThread.Post(() =>
        {
            if (!_usingFallback)
                NodeTapped?.Invoke(node);
        });
    }

    private void QueueStateUpdate()
    {
        _stateRevision++;
        UpdateFallback();
        if (_usingFallback || !_pageReady || _webView is null || _statePushQueued)
            return;

        _statePushQueued = true;
        Dispatcher.UIThread.Post(async () =>
        {
            try
            {
                var webView = _webView;
                while (webView is not null && !_usingFallback && _pageReady)
                {
                    var revision = _stateRevision;
                    await webView.InvokeScript(BuildStateScript());
                    _appliedRevision = revision;
                    if (revision == _stateRevision)
                        break;
                }
            }
            catch
            {
                UseFallback();
            }
            finally
            {
                _statePushQueued = false;
                if (_appliedRevision != _stateRevision)
                    QueueStateUpdate();
            }
        });
    }

    private string BuildStateScript()
    {
        var script = new StringBuilder();
        script.AppendLine("window.invokeCSharpAction = function(node) { if (window.chrome && window.chrome.webview) window.chrome.webview.postMessage(String(node || '')); };");
        script.Append("window.deyttSetMapRoute && window.deyttSetMapRoute(")
            .Append(JsonSerializer.Serialize(_selectedRoute)).AppendLine(");");
        script.Append("window.deyttSetMapLanguage && window.deyttSetMapLanguage(")
            .Append(JsonSerializer.Serialize(_language)).AppendLine(");");
        script.Append("window.deyttSetMapLocations && window.deyttSetMapLocations(")
            .Append(JsonSerializer.Serialize(_availableLocations)).AppendLine(");");

        if (_originConsentGranted && _originLocation is { } location)
        {
            var city = new string(location.City.Where(character => !char.IsControl(character)).Take(80).ToArray()).Trim();
            var country = new string(location.CountryCode.Where(char.IsAsciiLetterOrDigit).Take(3).ToArray())
                .ToUpperInvariant();
            var details = JsonSerializer.Serialize(new { city, country });
            script.Append("window.deyttSetMapUserLocation && window.deyttSetMapUserLocation(")
                .Append(JsonSerializer.Serialize(location.Latitude)).Append(',')
                .Append(JsonSerializer.Serialize(location.Longitude)).Append(',')
                .Append(details).AppendLine(");");
        }
        else
        {
            script.AppendLine("window.deyttClearMapUserLocation && window.deyttClearMapUserLocation();");
        }

        script.Append("window.deyttSetMapEgressCountry && window.deyttSetMapEgressCountry(")
            .Append(JsonSerializer.Serialize(_egressCountryCode)).AppendLine(");");
        script.Append("window.deyttSetMapActiveAutoRoute && window.deyttSetMapActiveAutoRoute(")
            .Append(JsonSerializer.Serialize(_activeAutoRouteKey)).AppendLine(");");
        script.Append("window.deyttSetMapTrafficActive && window.deyttSetMapTrafficActive(")
            .Append(_trafficActive ? "true" : "false").AppendLine(");");
        return script.ToString();
    }

    private void UseFallback()
    {
        _usingFallback = true;
        _pageReady = false;

        if (_webView is { } webView)
        {
            webView.AdapterCreated -= OnAdapterCreated;
            webView.NavigationStarted -= OnNavigationStarted;
            webView.NavigationCompleted -= OnNavigationCompleted;
            webView.NewWindowRequested -= OnNewWindowRequested;
            webView.WebMessageReceived -= OnWebMessageReceived;
            _webView = null;
        }

        UpdateFallback();
        Content = _fallback;
    }

    private void UpdateFallback()
    {
        _fallback.OriginCoordinate = _originConsentGranted && _originLocation is { } location
            ? new MapCoordinate(location.Latitude, location.Longitude)
            : null;
        _fallback.ExitCoordinate = _exitCoordinate;
        _fallback.InvalidateVisual();
    }

    private static bool IsValidLocation(WindowsNetworkLocation? location) =>
        location is not null &&
        double.IsFinite(location.Latitude) && location.Latitude is >= -85 and <= 85 &&
        double.IsFinite(location.Longitude) && location.Longitude is >= -180 and <= 180;

    private static bool IsValidCoordinate(MapCoordinate? coordinate) =>
        coordinate is null ||
        (double.IsFinite(coordinate.Value.Latitude) && coordinate.Value.Latitude is >= -85 and <= 85 &&
         double.IsFinite(coordinate.Value.Longitude) && coordinate.Value.Longitude is >= -180 and <= 180);

    private static string? NormalizeCountryCode(string? countryCode)
    {
        var normalized = countryCode?.Trim().ToUpperInvariant();
        return normalized is "NL" or "DE" or "FI" or "RU" ? normalized : null;
    }
}
