using System.Text;
using System.Text.Json;
using System.Threading;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;
using Microsoft.Web.WebView2.Core;

namespace DeyttConnect.Windows.Controls;

public sealed record HomePresentationSnapshot(
    string Status,
    string StatusTone,
    string? Message,
    string Country,
    string City,
    string Protocol,
    string CountryCode,
    string ActionLabel,
    string Action,
    bool ActionEnabled,
    string ActionTone,
    string? Latency,
    string? LatencyTone,
    string? Speed,
    string? SpeedTone,
    string? MeasurementLabel,
    bool MeasurementEnabled = true,
    string? MeasurementActionLabel = null);

/// <summary>
/// Hosts the same bundled offline network atlas as Android in a native WebView.
/// </summary>
public sealed class RouteGlobeWebView : ContentControl
{
    private const string AssetHost = "deytt-atlas.example";
    private static readonly Uri AtlasUri = new($"https://{AssetHost}/index.html");
    private static readonly Uri WindowsAtlasUri = new($"https://{AssetHost}/index.html?profile=windows");
    private const int MaximumAutomaticRetries = 2;
    private readonly bool _disableNativeInitialization;
    private static readonly string[] RequiredAtlasAssets =
        ["index.html", "network-atlas.css", "network-atlas.js", "atlas-init.js", "world-land.json"];
    private static readonly HashSet<string> RouteKeys = new(StringComparer.Ordinal)
    {
        "auto", "nl", "de", "fi", "ru", "it", "ru-de",
    };
    private static readonly HashSet<string> LocationKeys = new(StringComparer.Ordinal)
    {
        "nl", "de", "fi", "ru", "it",
    };
    private static readonly HashSet<string> TapKeys = new(StringComparer.Ordinal)
    {
        "nl", "de", "fi", "ru", "it", "user",
    };

    private NativeWebView? _webView;
    private AtlasLoopbackAssetHost? _linuxAssetHost;
    private Uri _atlasUri = AtlasUri;
    private string _selectedRoute = "auto";
    private string _language = "ru";
    private IReadOnlyCollection<string> _availableLocations = [];
    private bool _originConsentGranted;
    private WindowsNetworkLocation? _originLocation;
    private string? _egressCountryCode;
    private string? _activeAutoRouteKey;
    private bool _trafficActive;
    private bool _hostActive = true;
    private bool _reducedMotion;
    private bool _immersiveMode;
    private bool _darkTheme = true;
    private HomePresentationSnapshot? _homePresentation;
    private IReadOnlyList<WindowsRoute>? _routesCatalog;
    private IReadOnlyDictionary<string, WindowsRouteProbeResult>? _probesCatalog;
    private bool _atlasFailed;
    private string? _atlasErrorReason;
    private bool _attachedToVisualTree;
    private bool _pageReady;
    private bool _statePushQueued;
    private bool _mapRegionUpdateQueued;
    private IntPtr _nativeMapWindowHandle;
    private CoreWebView2? _coreWebView2;
    private int _appliedMapRegionWidth;
    private int _appliedMapRegionHeight;
    private uint _appliedMapRegionDpi;
    private int _stateRevision;
    private int _appliedRevision;
    private int _automaticRetries;
    private int _webViewGeneration;
    private bool _retryPending;
    private CancellationTokenSource? _atlasReadyTimeout;
    private CancellationTokenSource? _statePushCancellation;
    private int _homeResumeRevision;
    private int _appliedHomeResumeRevision = -1;

    // Allows lifecycle tests to defer a backend response without blocking Chromium.
    internal Func<string, Task>? StateScriptExecutor { get; set; }

    public RouteGlobeWebView() : this(false)
    {
    }

    internal RouteGlobeWebView(bool disableNativeInitialization)
    {
        _disableNativeInitialization = disableNativeInitialization;
        MinHeight = 230;
        ClipToBounds = true;
        Background = Brushes.Transparent;
        AttachedToVisualTree += (_, _) =>
        {
            _attachedToVisualTree = true;
            if (!_disableNativeInitialization && !_atlasFailed && !_retryPending)
            {
                if (_webView is null)
                    TryInitializeWebView();
                else if (!_pageReady)
                    StartAtlasReadyTimeout(_webView);
            }
            QueueStateUpdate();
        };
        DetachedFromVisualTree += (_, _) =>
        {
            _attachedToVisualTree = false;
            CancelPendingStatePush();
            _homeResumeRevision++;
            StopAtlasReadyTimeout();
        };
        AppDomain.CurrentDomain.ProcessExit += OnProcessExit;
        if (!_disableNativeInitialization)
            TryInitializeWebView();
    }

    /// <summary>Raised when the atlas reports a selectable node code.</summary>
    public event Action<string>? NodeTapped;

    /// <summary>Raised for a small allow-listed set of home-screen actions.</summary>
    public event Action<string>? HomeActionRequested;

    /// <summary>Raised when the atlas asks the host to enter or leave immersive map mode.</summary>
    public event Action<bool>? ImmersiveModeRequested;

    /// <summary>Raised when the user switches tabs inside the web presentation.</summary>
    public event Action<string>? TabChanged;

    /// <summary>Raised when the user selects a route inside the web presentation.</summary>
    public event Action<string>? RouteSelected;

    /// <summary>Raised when the user requests a route probe for a country.</summary>
    public event Action<string>? CountryProbeRequested;

    /// <summary>Raised when the user toggles a client setting in the web presentation.</summary>
    public event Action<string, bool>? SettingToggled;

    /// <summary>Raised when the user clicks check for updates in the web presentation.</summary>
    public event Action? CheckUpdatesRequested;

    internal bool IsNativeInitializationDisabled => _disableNativeInitialization;

    private string _activeTabName = "home";

    public void SetActiveTab(string tab)
    {
        if (string.IsNullOrWhiteSpace(tab)) return;
        _activeTabName = tab.ToLowerInvariant();
        _ = ExecuteScriptAsync($"window.deyttSwitchStage && window.deyttSwitchStage('{_activeTabName}');");
    }

    public void UpdateRoutesAndProbes(IReadOnlyList<WindowsRoute>? routes, IReadOnlyDictionary<string, WindowsRouteProbeResult>? probes, string? selectedRouteId)
    {
        _routesCatalog = routes;
        _probesCatalog = probes;
        if (!string.IsNullOrEmpty(selectedRouteId))
            _selectedRoute = RouteKeyFor(selectedRouteId);
        if (_atlasFailed || !_attachedToVisualTree || !_pageReady || _webView is null || routes is null) return;
        var items = routes.Select(r => new
        {
            id = r.Id,
            tag = r.Tag,
            countryCode = r.CountryCode,
            countryName = r.CountryName,
            protocol = r.Protocol,
            protocolName = r.ProtocolName,
            latency = probes != null && probes.TryGetValue(r.Tag, out var p) ? p.LatencyMilliseconds : null,
            speed = probes != null && probes.TryGetValue(r.Tag, out var p2) && p2.BytesPerSecond > 0 ? (long?)(p2.BytesPerSecond.Value * 8 / 1_000_000.0) : null,
            stage = probes != null && probes.TryGetValue(r.Tag, out var p3) ? p3.Stage : null,
            isSelected = string.Equals(r.Id, selectedRouteId, StringComparison.OrdinalIgnoreCase) ||
                         string.Equals(r.Tag, selectedRouteId, StringComparison.OrdinalIgnoreCase)
        });
        var script = $"window.deyttSetRoutesData && window.deyttSetRoutesData({JsonSerializer.Serialize(items)});";
        _ = ExecuteScriptAsync(script);
    }

    public void PushRouteProbeProgress(WindowsRouteProbeResult probe)
    {
        if (_atlasFailed || !_attachedToVisualTree || !_pageReady || _webView is null) return;
        var speedMbps = probe.BytesPerSecond is > 0 ? (long?)(probe.BytesPerSecond.Value * 8 / 1_000_000.0) : null;
        var script = $"window.deyttUpdateRouteProbe && window.deyttUpdateRouteProbe({JsonSerializer.Serialize(probe.RouteTag)}, {JsonSerializer.Serialize(probe.LatencyMilliseconds)}, {JsonSerializer.Serialize(speedMbps)}, {JsonSerializer.Serialize(probe.Stage)}, {JsonSerializer.Serialize(probe.Error)});";
        _ = ExecuteScriptAsync(script);
    }

    public async Task ExecuteScriptAsync(string script)
    {
        if (_webView is null) return;
        try
        {
            if (_coreWebView2 is not null)
                await _coreWebView2.ExecuteScriptAsync(script);
            else
                await _webView.InvokeScript(script);
        }
        catch
        {
            // ignored
        }
    }

    public async Task<Bitmap?> CapturePreviewAsync()
    {
        if (!OperatingSystem.IsWindows() || !_pageReady || _coreWebView2 is null)
            return null;
        try
        {
            using var stream = new MemoryStream();
            await _coreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
            stream.Position = 0;
            return new Bitmap(stream);
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or COMException)
        {
            System.Diagnostics.Trace.TraceError("Home WebView preview failed: {0}", error.GetType().Name);
            return null;
        }
    }

    public bool DarkTheme
    {
        get => _darkTheme;
        set
        {
            if (_darkTheme == value) return;
            _darkTheme = value;
            QueueStateUpdate();
        }
    }

    /// <summary>Serializable display-only state. Never place credentials or tunnel profiles here.</summary>
    public HomePresentationSnapshot? HomePresentation
    {
        get => _homePresentation;
        set
        {
            if (_homePresentation == value)
                return;
            _homePresentation = value;
            QueueStateUpdate();
            if (_atlasFailed)
                ShowAtlasError(_atlasErrorReason ?? "load");
        }
    }

    /// <summary>Selected atlas route: auto, nl, de, fi, ru, it, or ru-de.</summary>
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
            if (_atlasFailed)
                ShowAtlasError(_atlasErrorReason ?? "load");
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
            if (next is not ("nl" or "de" or "fi" or "ru" or "it" or "ru-de"))
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

    /// <summary>Pauses rendering while the retained home page is hidden.</summary>
    public bool HostActive
    {
        get => _hostActive;
        set
        {
            if (_hostActive == value)
                return;
            CancelPendingStatePush();
            _hostActive = value;
            if (value) _homeResumeRevision++;
            QueueStateUpdate();
        }
    }

    /// <summary>Resumes the visible Home surface, including a lost backend response.</summary>
    public void ResumeHost()
    {
        CancelPendingStatePush();
        _hostActive = true;
        _homeResumeRevision++;
        QueueStateUpdate();
    }

    /// <summary>Stops atlas traffic motion and camera easing while enabled.</summary>
    public bool ReducedMotion
    {
        get => _reducedMotion;
        set
        {
            if (_reducedMotion == value)
                return;
            _reducedMotion = value;
            QueueStateUpdate();
        }
    }

    public bool ImmersiveMode
    {
        get => _immersiveMode;
        set
        {
            if (_immersiveMode == value)
                return;
            _immersiveMode = value;
            QueueStateUpdate();
        }
    }

    /// <summary>
    /// Translates route identifiers such as NL, IT, RU-DE, or a route id containing those
    /// country tokens to the key understood by the shared atlas.
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
        if (tokens.Contains("IT"))
            return "it";
        if (tokens.Contains("RU"))
            return "ru";
        return "auto";
    }

    internal static string RouteKeyFor(string? routeId, IReadOnlyList<WindowsRoute> routes)
    {
        var route = routes.FirstOrDefault(item => item.Id == routeId || item.Tag == routeId);
        // AWG ids encode the server id; geography comes from subscription metadata.
        return RouteKeyFor(route?.CountryCode ?? routeId);
    }
    private static string AtlasAssetDirectory => Path.Combine(AppContext.BaseDirectory, "Assets", "route-map");

    private void TryInitializeWebView()
    {
        if (_disableNativeInitialization)
            return;

        if (!Directory.Exists(AtlasAssetDirectory) ||
            RequiredAtlasAssets.Any(name => !File.Exists(Path.Combine(AtlasAssetDirectory, name))))
        {
            ShowAtlasError("assets");
            return;
        }

        try
        {
            _webViewGeneration++;
            if (OperatingSystem.IsLinux())
            {
                var gtk = WebViewAdapterInfo.GetAdapterInfo(WebViewAdapterType.WebKitGtk);
                var wpe = WebViewAdapterInfo.GetAdapterInfo(WebViewAdapterType.WpeWebKit);
                if (!gtk.IsInstalled && !wpe.IsInstalled)
                {
                    ShowAtlasError("runtime");
                    return;
                }

                _linuxAssetHost ??= AtlasLoopbackAssetHost.Start(AtlasAssetDirectory);
                _atlasUri = _linuxAssetHost.AtlasUri;
            }
            else if (OperatingSystem.IsWindows())
            {
                _atlasUri = WindowsAtlasUri;
            }
            else
            {
                ShowAtlasError("platform");
                return;
            }

            string? webView2UserDataFolder = null;
            if (OperatingSystem.IsWindows())
            {
                var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                if (string.IsNullOrWhiteSpace(localAppData))
                {
                    ShowAtlasError("runtime");
                    return;
                }

#if DEBUG
                var qaUserDataFolder = Environment.GetEnvironmentVariable("DEYTT_QA_WEBVIEW2_USER_DATA_FOLDER");
#else
                string? qaUserDataFolder = null;
#endif
                webView2UserDataFolder = string.IsNullOrWhiteSpace(qaUserDataFolder)
                    ? Path.Combine(localAppData, "DEYTT", "Connect", "WebView2")
                    : Path.GetFullPath(qaUserDataFolder);
                Directory.CreateDirectory(webView2UserDataFolder);
            }

            _atlasFailed = false;
            _atlasErrorReason = null;
            var webView = new NativeWebView
            {
                Background = Brushes.Transparent,
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Stretch,
            };
            webView.EnvironmentRequested += (_, args) =>
            {
                if (args is GtkWebViewEnvironmentRequestedEventArgs gtk)
                {
                    gtk.ExperimentalOffscreen = true;
                    gtk.EphemeralDataManager = true;
                }
                else if (args is WindowsWebView2EnvironmentRequestedEventArgs webView2)
                {
                    webView2.UserDataFolder = webView2UserDataFolder!;
                    // Keep the standard HWND controller on Windows. The experimental
                    // offscreen adapter crashes during initialization on this supported
                    // Windows 10 and WebView2 combination, taking down the whole client.
                    webView2.ExperimentalOffscreen = false;
                }
            };
            webView.AdapterCreated += OnAdapterCreated;
            webView.AdapterDestroyed += OnAdapterDestroyed;
            webView.NavigationStarted += OnNavigationStarted;
            webView.NavigationCompleted += OnNavigationCompleted;
            webView.NewWindowRequested += OnNewWindowRequested;
            webView.WebMessageReceived += OnWebMessageReceived;
            _webView = webView;
            Content = webView;
        }
        catch (Exception exception)
        {
            System.Diagnostics.Trace.TraceError("Atlas WebView initialization failed: {0}", exception.GetType().Name);
            HandleAtlasFailure("runtime");
        }
    }

    private void OnAdapterCreated(object? sender, WebViewAdapterEventArgs args)
    {
        if (_atlasFailed || !ReferenceEquals(sender, _webView))
            return;

        try
        {
            if (OperatingSystem.IsWindows())
            {
                if (args.TryGetPlatformHandle() is not IWindowsWebView2PlatformHandle handle ||
                    handle.CoreWebView2 == IntPtr.Zero)
                {
                    HandleAtlasFailure("runtime");
                    return;
                }

                _nativeMapWindowHandle = handle.Handle;
                _appliedMapRegionWidth = 0;
                _appliedMapRegionHeight = 0;
                _appliedMapRegionDpi = 0;
                SetTransparentWebViewBackground(handle.CoreWebView2Controller);
                var coreWebView2 = CoreWebView2.CreateFromComICoreWebView2(handle.CoreWebView2);
                _coreWebView2 = coreWebView2;
                coreWebView2.SetVirtualHostNameToFolderMapping(
                    AssetHost,
                    AtlasAssetDirectory,
                    CoreWebView2HostResourceAccessKind.DenyCors);
            }

            _webView!.Source = _atlasUri;
        }
        catch (Exception exception)
        {
            System.Diagnostics.Trace.TraceError("Atlas adapter failed: {0}", exception.GetType().Name);
            HandleAtlasFailure("runtime");
        }
    }

    private void OnMapSizeChanged(object? sender, SizeChangedEventArgs args) => QueueRoundedMapRegionUpdate();

    private void QueueRoundedMapRegionUpdate()
    {
        if (!OperatingSystem.IsWindows() || _nativeMapWindowHandle == IntPtr.Zero || _mapRegionUpdateQueued)
            return;

        var expectedHandle = _nativeMapWindowHandle;
        _mapRegionUpdateQueued = true;
        Dispatcher.UIThread.Post(() =>
        {
            _mapRegionUpdateQueued = false;
            if (expectedHandle != _nativeMapWindowHandle)
                return;

            if (OperatingSystem.IsWindows())
                ApplyRoundedMapWindowRegion(expectedHandle);
        }, DispatcherPriority.Render);
    }

    [SupportedOSPlatform("windows")]
    private void ApplyRoundedMapWindowRegion(IntPtr windowHandle)
    {
        const uint RootAncestor = 2;
        if (windowHandle == IntPtr.Zero)
            return;

        // The WebView's platform handle must be its own child HWND. Never round
        // the top-level app window, even if the adapter returns an unexpected handle.
        var rootWindow = GetAncestor(windowHandle, RootAncestor);
        if (rootWindow == IntPtr.Zero || rootWindow == windowHandle)
        {
            System.Diagnostics.Trace.TraceError("Map corner clip skipped: WebView handle is not a child window.");
            return;
        }

        if (!GetWindowRect(windowHandle, out var bounds))
            return;

        var width = bounds.Right - bounds.Left;
        var height = bounds.Bottom - bounds.Top;
        if (width <= 0 || height <= 0)
            return;

        var dpi = GetDpiForWindow(windowHandle);
        if (dpi == 0)
            dpi = 96;
        if (width == _appliedMapRegionWidth && height == _appliedMapRegionHeight && dpi == _appliedMapRegionDpi)
            return;

        var radius = Math.Max(1, (int)Math.Round(24d * dpi / 96d));
        var diameter = Math.Min(radius * 2, Math.Min(width, height));
        var region = CreateRoundRectRgn(0, 0, width, height, diameter, diameter);
        if (region == IntPtr.Zero)
            return;

        if (SetWindowRgn(windowHandle, region, true) == 0)
        {
            DeleteObject(region);
            System.Diagnostics.Trace.TraceError("Map corner clip failed: SetWindowRgn returned zero.");
            return;
        }
        // On success, Windows owns the region handle and deletes it when replaced.
        _appliedMapRegionWidth = width;
        _appliedMapRegionHeight = height;
        _appliedMapRegionDpi = dpi;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr GetAncestor(IntPtr windowHandle, uint flags);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(IntPtr windowHandle, out NativeRect bounds);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint GetDpiForWindow(IntPtr windowHandle);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern int SetWindowRgn(IntPtr windowHandle, IntPtr region, [MarshalAs(UnmanagedType.Bool)] bool redraw);

    [DllImport("gdi32.dll", SetLastError = true)]
    private static extern IntPtr CreateRoundRectRgn(int left, int top, int right, int bottom, int ellipseWidth, int ellipseHeight);

    [DllImport("gdi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeleteObject(IntPtr objectHandle);

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [SupportedOSPlatform("windows")]
    private static void SetTransparentWebViewBackground(IntPtr controllerHandle)
    {
        if (controllerHandle == IntPtr.Zero)
            throw new InvalidOperationException("WebView2 controller handle is unavailable.");

        var controllerObject = Marshal.GetObjectForIUnknown(controllerHandle);
        var controllerType = typeof(CoreWebView2).Assembly.GetType(
            "Microsoft.Web.WebView2.Core.Raw.ICoreWebView2Controller2",
            throwOnError: true)!;
        if (!controllerType.IsInstanceOfType(controllerObject))
            throw new InvalidOperationException("WebView2 controller does not support transparent backgrounds.");

        var colorType = controllerType.Assembly.GetType(
            "Microsoft.Web.WebView2.Core.Raw.COREWEBVIEW2_COLOR",
            throwOnError: true)!;
        var transparentColor = Activator.CreateInstance(colorType)!;
        colorType.GetField("A")!.SetValue(transparentColor, (byte)0);
        colorType.GetField("R")!.SetValue(transparentColor, (byte)0);
        colorType.GetField("G")!.SetValue(transparentColor, (byte)0);
        colorType.GetField("B")!.SetValue(transparentColor, (byte)0);
        controllerType.GetProperty("DefaultBackgroundColor")!.SetValue(controllerObject, transparentColor);
    }

    private void OnAdapterDestroyed(object? sender, WebViewAdapterEventArgs args)
    {
        if (ReferenceEquals(sender, _webView))
        {
            CancelPendingStatePush();
            _nativeMapWindowHandle = IntPtr.Zero;
            _mapRegionUpdateQueued = false;
            _appliedMapRegionWidth = 0;
            _appliedMapRegionHeight = 0;
            _appliedMapRegionDpi = 0;
            _pageReady = false;
            StopAtlasReadyTimeout();
            if (_attachedToVisualTree && !_atlasFailed)
                HandleAtlasFailure("load");
            else if (!_attachedToVisualTree)
                DetachCurrentWebView();
        }
    }

    private void OnNavigationStarted(object? sender, WebViewNavigationStartingEventArgs args)
    {
        var request = args.Request;
        if (request is null)
        {
            args.Cancel = true;
            return;
        }

        var isBlank = request.IsAbsoluteUri &&
                      string.Equals(request.AbsoluteUri, "about:blank", StringComparison.OrdinalIgnoreCase);
        if (!isBlank && request != _atlasUri)
            args.Cancel = true;
        else if (request == _atlasUri)
        {
            CancelPendingStatePush();
            _appliedHomeResumeRevision = -1;
            _pageReady = false;
            StopAtlasReadyTimeout();
        }
    }

    private void OnNavigationCompleted(object? sender, WebViewNavigationCompletedEventArgs args)
    {
        if (_atlasFailed || !ReferenceEquals(sender, _webView))
            return;

        if (!args.IsSuccess)
        {
            HandleAtlasFailure("load");
            return;
        }

        if (args.IsSuccess && args.Request == _atlasUri)
        {
            // Navigation completion only means the HTML loaded. Wait for the
            // atlas-ready bridge after topology parsing and the first frame.
            StartAtlasReadyTimeout(_webView!);
        }
    }

    private static void OnNewWindowRequested(object? sender, WebViewNewWindowRequestedEventArgs args) =>
        args.Handled = true;

    private void OnWebMessageReceived(object? sender, WebMessageReceivedEventArgs args)
    {
        var body = args.Body;
        if (_atlasFailed || !ReferenceEquals(sender, _webView) || body is null || body.Length > 4096)
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
        if (node == "atlas-ready")
        {
            Dispatcher.UIThread.Post(() =>
            {
                if (_atlasFailed || !ReferenceEquals(sender, _webView))
                    return;
                _automaticRetries = 0;
                StopAtlasReadyTimeout();
                _pageReady = true;
                QueueStateUpdate();
            });
            return;
        }
        if (node == "atlas-error")
        {
            Dispatcher.UIThread.Post(() => HandleAtlasFailure("load"));
            return;
        }
        if (node is "deytt:immersive:enter" or "deytt:immersive:exit")
        {
            var enabled = node.EndsWith(":enter", StringComparison.Ordinal);
            Dispatcher.UIThread.Post(() =>
            {
                if (!_atlasFailed && ReferenceEquals(sender, _webView))
                    ImmersiveModeRequested?.Invoke(enabled);
            });
            return;
        }
        if (node is "home-connect" or "home-disconnect" or "home-cancel" or "home-routes" or "home-profile" or "home-settings" or "home-measure")
        {
            Dispatcher.UIThread.Post(() =>
            {
                if (!_atlasFailed && ReferenceEquals(sender, _webView))
                    HomeActionRequested?.Invoke(node);
            });
            return;
        }
        if (node == "window:minimize")
        {
            Dispatcher.UIThread.Post(() =>
            {
                if (VisualRoot is Window window)
                    window.WindowState = WindowState.Minimized;
            });
            return;
        }
        if (node == "window:maximize")
        {
            Dispatcher.UIThread.Post(() =>
            {
                if (VisualRoot is Window window)
                    window.WindowState = window.WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
            });
            return;
        }
        if (node == "window:close")
        {
            Dispatcher.UIThread.Post(() =>
            {
                if (VisualRoot is Window window)
                    window.Close();
            });
            return;
        }
        if (node.StartsWith("tab:", StringComparison.Ordinal))
        {
            var tabName = node["tab:".Length..];
            Dispatcher.UIThread.Post(() =>
            {
                if (!_atlasFailed && ReferenceEquals(sender, _webView))
                    TabChanged?.Invoke(tabName);
            });
            return;
        }
        if (node.StartsWith("route:select:", StringComparison.Ordinal))
        {
            var routeKey = node["route:select:".Length..];
            Dispatcher.UIThread.Post(() =>
            {
                if (!_atlasFailed && ReferenceEquals(sender, _webView))
                    RouteSelected?.Invoke(routeKey);
            });
            return;
        }
        if (node.StartsWith("route:probe:country:", StringComparison.Ordinal))
        {
            var countryCode = node["route:probe:country:".Length..];
            Dispatcher.UIThread.Post(() =>
            {
                if (!_atlasFailed && ReferenceEquals(sender, _webView))
                    CountryProbeRequested?.Invoke(countryCode);
            });
            return;
        }
        if (node == "settings:check_updates")
        {
            Dispatcher.UIThread.Post(() =>
            {
                if (!_atlasFailed && ReferenceEquals(sender, _webView))
                    CheckUpdatesRequested?.Invoke();
            });
            return;
        }
        if (node.StartsWith("settings:set:", StringComparison.Ordinal))
        {
            var payload = node["settings:set:".Length..];
            var colon = payload.IndexOf(':');
            if (colon > 0)
            {
                var key = payload[..colon];
                var val = bool.TryParse(payload[(colon + 1)..], out var b) && b;
                Dispatcher.UIThread.Post(() =>
                {
                    if (!_atlasFailed && ReferenceEquals(sender, _webView))
                        SettingToggled?.Invoke(key, val);
                });
            }
            return;
        }
        if (!TapKeys.Contains(node))
            return;

        Dispatcher.UIThread.Post(() =>
        {
            if (!_atlasFailed && ReferenceEquals(sender, _webView))
                NodeTapped?.Invoke(node);
        });
    }

    private void QueueStateUpdate()
    {
        _stateRevision++;
        if (_atlasFailed || !_attachedToVisualTree || !_pageReady || _webView is null || _statePushQueued)
            return;

        var webView = _webView;
        var core = _coreWebView2;
        var generation = _webViewGeneration;
        var cancellation = new CancellationTokenSource();
        var token = cancellation.Token;
        _statePushCancellation = cancellation;
        _statePushQueued = true;
        bool Current() => ReferenceEquals(_statePushCancellation, cancellation) &&
            ReferenceEquals(_webView, webView) && generation == _webViewGeneration &&
            !_atlasFailed && _attachedToVisualTree && _pageReady;
        Dispatcher.UIThread.Post(async () =>
        {
            try
            {
                while (Current())
                {
                    var revision = _stateRevision;
                    var resumeRevision = _homeResumeRevision;
                    var script = BuildStateScript();
                    // Use the current Windows core directly; a framework bridge
                    // response from a hidden/destroyed adapter must not block Home.
                    Task invocation = StateScriptExecutor is { } executor ? executor(script)
                        : core is not null ? core.ExecuteScriptAsync(script)
                        : webView.InvokeScript(script);
                    await invocation.WaitAsync(TimeSpan.FromSeconds(2), token);
                    if (!Current()) return;
                    _appliedRevision = revision;
                    _appliedHomeResumeRevision = resumeRevision;
                    if (revision == _stateRevision)
                        break;
                }
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested) { }
            catch (Exception exception)
            {
                if (Current())
                {
                    System.Diagnostics.Trace.TraceError("Atlas state update failed: {0}", exception.GetType().Name);
                    HandleAtlasFailure("load");
                }
            }
            finally
            {
                if (ReferenceEquals(_statePushCancellation, cancellation))
                {
                    _statePushCancellation = null;
                    _statePushQueued = false;
                    if (_attachedToVisualTree && _pageReady && _appliedRevision != _stateRevision)
                        QueueStateUpdate();
                }
                cancellation.Dispose();
            }
        });
    }

    private void CancelPendingStatePush()
    {
        var cancellation = _statePushCancellation;
        _statePushCancellation = null;
        _statePushQueued = false;
        cancellation?.Cancel();
    }

    private void StartAtlasReadyTimeout(NativeWebView webView)
    {
        StopAtlasReadyTimeout();
        var source = new CancellationTokenSource();
        _atlasReadyTimeout = source;
        _ = WaitForAtlasReadyAsync(webView, _webViewGeneration, source.Token);
    }

    private async Task WaitForAtlasReadyAsync(NativeWebView webView, int generation, CancellationToken cancellationToken)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(9), cancellationToken);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        Dispatcher.UIThread.Post(() =>
        {
            if (!cancellationToken.IsCancellationRequested && generation == _webViewGeneration &&
                ReferenceEquals(webView, _webView) && !_pageReady && !_atlasFailed)
                HandleAtlasFailure("load");
        });
    }

    private void StopAtlasReadyTimeout()
    {
        _atlasReadyTimeout?.Cancel();
        _atlasReadyTimeout?.Dispose();
        _atlasReadyTimeout = null;
    }

    private async Task RetryAtlasAfterDelayAsync(int generation, int delayMilliseconds)
    {
        await Task.Delay(delayMilliseconds);
        Dispatcher.UIThread.Post(() =>
        {
            _retryPending = false;
            if (!_atlasFailed && generation == _webViewGeneration && _webView is null)
                TryInitializeWebView();
        });
    }

    private void HandleAtlasFailure(string reason)
    {
        if (_atlasFailed)
            return;

        StopAtlasReadyTimeout();
        if (_retryPending)
            return;
        if (_automaticRetries < MaximumAutomaticRetries)
        {
            _automaticRetries++;
            DetachCurrentWebView();
            var generation = _webViewGeneration;
            var delay = _automaticRetries == 1 ? 280 : 760;
            _retryPending = true;
            _ = RetryAtlasAfterDelayAsync(generation, delay);
            return;
        }

        ShowAtlasError(reason);
    }

    private void RetryAtlasManually()
    {
        _automaticRetries = 0;
        _retryPending = false;
        _atlasFailed = false;
        _atlasErrorReason = null;
        DetachCurrentWebView();
        Content = null;
        TryInitializeWebView();
    }

    private void DetachCurrentWebView()
    {
        CancelPendingStatePush();
        _appliedHomeResumeRevision = -1;
        StopAtlasReadyTimeout();
        if (_webView is not { } webView)
            return;

        webView.AdapterCreated -= OnAdapterCreated;
        webView.AdapterDestroyed -= OnAdapterDestroyed;
        webView.NavigationStarted -= OnNavigationStarted;
        webView.NavigationCompleted -= OnNavigationCompleted;
        webView.NewWindowRequested -= OnNewWindowRequested;
        webView.WebMessageReceived -= OnWebMessageReceived;
        if (ReferenceEquals(Content, webView))
            Content = null;
        _webView = null;
        _coreWebView2 = null;
        _pageReady = false;
    }

    private string BuildStateScript()
    {
        var script = new StringBuilder();
        if (!_hostActive)
            return "window.deyttSetMapHostActive && window.deyttSetMapHostActive(false);";
        if (OperatingSystem.IsWindows())
            script.AppendLine("window.invokeCSharpAction = function(node) { if (window.chrome && window.chrome.webview) window.chrome.webview.postMessage(String(node || '')); };");
        script.Append("window.deyttSetMapTheme && window.deyttSetMapTheme(")
            .Append(JsonSerializer.Serialize(_darkTheme ? "dark" : "light")).AppendLine(");");
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
        script.Append("window.deyttSetMapReducedMotion && window.deyttSetMapReducedMotion(")
            .Append(_reducedMotion ? "true" : "false").AppendLine(");");
        script.Append("window.deyttSetMapImmersive && window.deyttSetMapImmersive(")
            .Append(_immersiveMode ? "true" : "false").AppendLine(");");
        script.Append("window.deyttSetHomePresentation && window.deyttSetHomePresentation(")
            .Append(JsonSerializer.Serialize(_homePresentation, new JsonSerializerOptions(JsonSerializerDefaults.Web)))
            .AppendLine(");");
        if (_routesCatalog is { Count: > 0 } catalog)
        {
            var items = catalog.Select(r => new
            {
                id = r.Id,
                tag = r.Tag,
                countryCode = r.CountryCode,
                countryName = r.CountryName,
                protocol = r.Protocol,
                protocolName = r.ProtocolName,
                latency = _probesCatalog != null && _probesCatalog.TryGetValue(r.Tag, out var p) ? p.LatencyMilliseconds : null,
                speed = _probesCatalog != null && _probesCatalog.TryGetValue(r.Tag, out var p2) && p2.BytesPerSecond > 0 ? (long?)(p2.BytesPerSecond.Value * 8 / 1_000_000.0) : null,
                stage = _probesCatalog != null && _probesCatalog.TryGetValue(r.Tag, out var p3) ? p3.Stage : null,
                isSelected = string.Equals(r.Id, _selectedRoute, StringComparison.OrdinalIgnoreCase) ||
                             string.Equals(r.Tag, _selectedRoute, StringComparison.OrdinalIgnoreCase)
            });
            script.Append("window.deyttSetRoutesData && window.deyttSetRoutesData(")
                .Append(JsonSerializer.Serialize(items)).AppendLine(");");
        }
        script.AppendLine("window.deyttSetMapHostActive && window.deyttSetMapHostActive(true);");
        if (_homeResumeRevision != _appliedHomeResumeRevision)
            script.AppendLine("window.deyttResumeMap && window.deyttResumeMap();");
        script.Append("window.deyttSwitchStage && window.deyttSwitchStage(")
            .Append(JsonSerializer.Serialize(_activeTabName)).AppendLine(");");
        return script.ToString();
    }

    private void ShowAtlasError(string reason)
    {
        _atlasFailed = true;
        _atlasErrorReason = reason;
        DetachCurrentWebView();

        var english = _language == "en";
        var (summary, message) = (reason, english) switch
        {
            ("assets", true) => ("Map files are missing", "Re-extract the complete portable folder, including app/"),
            ("assets", false) => ("Не найдены файлы карты", "Распакуйте архив целиком: папка app должна находиться рядом с deyttconnect.exe."),
            ("runtime", true) when OperatingSystem.IsWindows() =>
                ("WebView2 Runtime is required", "Install or repair Microsoft Edge WebView2 Runtime, then retry."),
            ("runtime", false) when OperatingSystem.IsWindows() =>
                ("Не запущен WebView2 Runtime", "Установите или восстановите Microsoft Edge WebView2 Runtime и повторите попытку."),
            ("runtime", true) when OperatingSystem.IsLinux() =>
                ("WebKitGTK is required", "Install WebKitGTK 4.1 or WPE WebKit and retry."),
            ("runtime", false) when OperatingSystem.IsLinux() =>
                ("Не найден WebKitGTK", "Установите WebKitGTK 4.1 или WPE WebKit и повторите попытку."),
            ("platform", true) => ("Embedded map is unavailable", "This platform does not provide a supported WebView."),
            ("platform", false) => ("Карта недоступна на этой системе", "Встроенный браузер не поддерживается этой платформой."),
            (_, true) => ("The DEYTT atlas could not start", "Check the complete app folder and WebView2 Runtime, then retry."),
            _ => ("Не удалось запустить атлас DEYTT", "Проверьте папку приложения и WebView2 Runtime, затем повторите попытку."),
        };
        var retry = new Button
        {
            Content = english ? "Retry" : "Повторить",
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right,
            Padding = new Avalonia.Thickness(13, 7),
            CornerRadius = new Avalonia.CornerRadius(10),
            Foreground = DeyttTheme.Brush(DeyttTheme.Text),
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Avalonia.Thickness(1),
        };
        retry.Click += (_, _) => RetryAtlasManually();
        var caption = new StackPanel
        {
            Spacing = 8,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        caption.Children.Add(new TextBlock
        {
            Text = english ? "deytt. · route globe" : "deytt. · глобус маршрута",
            FontSize = 14,
            FontFamily = DeyttTheme.JetBrainsMono,
            Foreground = DeyttTheme.Brush(DeyttTheme.Muted),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        });
        caption.Children.Add(new TextBlock
        {
            Text = _homePresentation?.Status ?? (english ? "the globe could not be shown" : "не удалось показать глобус"),
            TextWrapping = TextWrapping.Wrap,
            FontWeight = FontWeight.SemiBold,
            Foreground = DeyttTheme.Brush(DeyttTheme.Text),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        });
        caption.Children.Add(new TextBlock
        {
            Text = _homePresentation?.Message ?? summary,
            FontSize = 11,
            TextWrapping = TextWrapping.Wrap,
            Foreground = DeyttTheme.Brush(DeyttTheme.Muted),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        });
        caption.Children.Add(new TextBlock
        {
            Text = message,
            FontSize = 11,
            TextWrapping = TextWrapping.Wrap,
            TextAlignment = Avalonia.Media.TextAlignment.Center,
            Foreground = DeyttTheme.Brush(DeyttTheme.Muted),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        });
        if (_homePresentation is { } home)
        {
            var action = new Button
            {
                Content = home.ActionLabel,
                MinHeight = 48,
                Padding = new Avalonia.Thickness(18, 10),
                CornerRadius = new Avalonia.CornerRadius(12),
                Foreground = Brushes.White,
                Background = DeyttTheme.Brush(home.ActionTone == "error" ? DeyttTheme.Coral : DeyttTheme.BlueDeep),
                BorderThickness = new Avalonia.Thickness(0),
                IsEnabled = home.ActionEnabled,
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            };
            Avalonia.Automation.AutomationProperties.SetName(action, home.ActionLabel);
            action.Click += (_, _) => HomeActionRequested?.Invoke(home.Action);
            caption.Children.Add(action);
        }
        caption.Children.Add(retry);
        Content = new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.Background),
            BorderThickness = new Avalonia.Thickness(0),
            Padding = new Avalonia.Thickness(24),
            Child = caption,
        };
    }

    private void OnProcessExit(object? sender, EventArgs args) => _linuxAssetHost?.Dispose();

    private static bool IsValidLocation(WindowsNetworkLocation? location) =>
        location is not null &&
        double.IsFinite(location.Latitude) && location.Latitude is >= -85 and <= 85 &&
        double.IsFinite(location.Longitude) && location.Longitude is >= -180 and <= 180;

    private static string? NormalizeCountryCode(string? countryCode)
    {
        var normalized = countryCode?.Trim().ToUpperInvariant();
        return normalized is "NL" or "DE" or "FI" or "RU" or "IT" ? normalized : null;
    }
}
