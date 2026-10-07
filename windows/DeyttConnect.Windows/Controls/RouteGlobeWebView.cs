using System.Text;
using System.Text.Json;
using System.Threading;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Platform;
using Avalonia.Threading;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;
using Microsoft.Web.WebView2.Core;

namespace DeyttConnect.Windows.Controls;

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
    private bool _reducedMotion;
    private bool _atlasFailed;
    private string? _atlasErrorReason;
    private bool _attachedToVisualTree;
    private bool _pageReady;
    private bool _statePushQueued;
    private bool _mapRegionUpdateQueued;
    private IntPtr _nativeMapWindowHandle;
    private int _appliedMapRegionWidth;
    private int _appliedMapRegionHeight;
    private uint _appliedMapRegionDpi;
    private int _stateRevision;
    private int _appliedRevision;
    private int _automaticRetries;
    private int _webViewGeneration;
    private bool _retryPending;
    private CancellationTokenSource? _atlasReadyTimeout;

    public RouteGlobeWebView() : this(false)
    {
    }

    internal RouteGlobeWebView(bool disableNativeInitialization)
    {
        _disableNativeInitialization = disableNativeInitialization;
        MinHeight = 230;
        ClipToBounds = true;
        Background = Brushes.Transparent;
        SizeChanged += OnMapSizeChanged;
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
            StopAtlasReadyTimeout();
        };
        AppDomain.CurrentDomain.ProcessExit += OnProcessExit;
        if (!_disableNativeInitialization)
            TryInitializeWebView();
    }

    /// <summary>Raised when the atlas reports a selectable node code.</summary>
    public event Action<string>? NodeTapped;

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

                webView2UserDataFolder = Path.Combine(localAppData, "DEYTT", "Connect", "WebView2");
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
                QueueRoundedMapRegionUpdate();
                SetTransparentWebViewBackground(handle.CoreWebView2Controller);
                var coreWebView2 = CoreWebView2.CreateFromComICoreWebView2(handle.CoreWebView2);
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
        if (_atlasFailed || !ReferenceEquals(sender, _webView) || body is null || body.Length > 128)
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
        if (!TapKeys.Contains(node))
            return;

        Dispatcher.UIThread.Post(() =>
        {
            if (!_atlasFailed)
                NodeTapped?.Invoke(node);
        });
    }

    private void QueueStateUpdate()
    {
        _stateRevision++;
        if (_atlasFailed || !_attachedToVisualTree || !_pageReady || _webView is null || _statePushQueued)
            return;

        _statePushQueued = true;
        Dispatcher.UIThread.Post(async () =>
        {
            try
            {
                var webView = _webView;
                while (webView is not null && !_atlasFailed && _attachedToVisualTree && _pageReady)
                {
                    var revision = _stateRevision;
                    await webView.InvokeScript(BuildStateScript());
                    _appliedRevision = revision;
                    if (revision == _stateRevision)
                        break;
                }
            }
            catch (Exception exception)
            {
                if (_attachedToVisualTree && _pageReady)
                {
                    System.Diagnostics.Trace.TraceError("Atlas state update failed: {0}", exception.GetType().Name);
                    HandleAtlasFailure("load");
                }
            }
            finally
            {
                _statePushQueued = false;
                if (_attachedToVisualTree && _pageReady && _appliedRevision != _stateRevision)
                    QueueStateUpdate();
            }
        });
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
        _pageReady = false;
    }

    private string BuildStateScript()
    {
        var script = new StringBuilder();
        if (OperatingSystem.IsWindows())
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
        script.Append("window.deyttSetMapReducedMotion && window.deyttSetMapReducedMotion(")
            .Append(_reducedMotion ? "true" : "false").AppendLine(");");
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
            Foreground = Brushes.White,
            Background = new SolidColorBrush(Avalonia.Media.Color.Parse("#24354B")),
            BorderBrush = new SolidColorBrush(Avalonia.Media.Color.Parse("#49627E")),
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
            Text = english ? "DEYTT NETWORK ATLAS" : "АТЛАС СЕТИ DEYTT",
            FontSize = 10,
            FontFamily = DeyttTheme.JetBrainsMono,
            Foreground = DeyttTheme.Brush(DeyttTheme.Sky),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        });
        caption.Children.Add(new TextBlock
        {
            Text = summary,
            TextWrapping = TextWrapping.Wrap,
            FontWeight = FontWeight.SemiBold,
            Foreground = Brushes.White,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        });
        caption.Children.Add(new TextBlock
        {
            Text = english ? "Your route remains available." : "Маршрут остаётся доступен.",
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
        caption.Children.Add(retry);
        Content = new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.MapSurface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Avalonia.Thickness(1),
            CornerRadius = new Avalonia.CornerRadius(18),
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
