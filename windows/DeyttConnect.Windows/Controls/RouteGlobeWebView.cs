using System.Text;
using System.Text.Json;
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
    private static readonly string[] RequiredAtlasAssets =
        ["index.html", "network-atlas.css", "network-atlas.js", "atlas-init.js", "world-land.json"];
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

    private NativeWebView? _webView;
    private AtlasLoopbackAssetHost? _linuxAssetHost;
    private Uri _atlasUri = AtlasUri;
    private string _selectedRoute = "auto";
    private string _language = "ru";
    private IReadOnlyCollection<string> _availableLocations = ["nl", "de", "fi", "ru"];
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
    private int _stateRevision;
    private int _appliedRevision;

    public RouteGlobeWebView()
    {
        MinHeight = 230;
        ClipToBounds = true;
        Background = Brushes.Transparent;
        AttachedToVisualTree += (_, _) =>
        {
            _attachedToVisualTree = true;
            QueueStateUpdate();
        };
        DetachedFromVisualTree += (_, _) =>
        {
            _attachedToVisualTree = false;
            _pageReady = false;
        };
        AppDomain.CurrentDomain.ProcessExit += OnProcessExit;
        TryInitializeWebView();
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

    public void ZoomIn() => ExecuteMapCommand("window.deyttZoomMap && window.deyttZoomMap(1.45);");

    public void ZoomOut() => ExecuteMapCommand("window.deyttZoomMap && window.deyttZoomMap(0.69);");

    public void ResetView() => ExecuteMapCommand("window.deyttResetMapView && window.deyttResetMapView();");

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

    private void TryInitializeWebView()
    {
        if (!Directory.Exists(AtlasAssetDirectory) ||
            RequiredAtlasAssets.Any(name => !File.Exists(Path.Combine(AtlasAssetDirectory, name))))
        {
            ShowAtlasError("assets");
            return;
        }

        try
        {
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
            else if (!OperatingSystem.IsWindows())
            {
                ShowAtlasError("platform");
                return;
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
                    // The Home page embeds the globe in a scrollable surface beside
                    // shell controls; offscreen composition prevents WebView2 airspace
                    // from covering navigation and keeps clipping aligned with layout.
                    webView2.ExperimentalOffscreen = true;
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
            ShowAtlasError("runtime");
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
                    ShowAtlasError("runtime");
                    return;
                }

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
            ShowAtlasError("runtime");
        }
    }

    private void OnAdapterDestroyed(object? sender, WebViewAdapterEventArgs args)
    {
        if (ReferenceEquals(sender, _webView))
            _pageReady = false;
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
            _pageReady = false;
    }

    private void OnNavigationCompleted(object? sender, WebViewNavigationCompletedEventArgs args)
    {
        if (_atlasFailed || !ReferenceEquals(sender, _webView))
            return;

        if (!args.IsSuccess && args.Request == _atlasUri)
        {
            ShowAtlasError("load");
            return;
        }

        if (args.IsSuccess && args.Request == _atlasUri)
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
                _pageReady = true;
                // NavigationCompleted may have pushed state before atlas-init finished
                // creating the renderer. Replay the latest values after its handshake.
                QueueStateUpdate();
            });
            return;
        }
        if (node == "atlas-error")
        {
            Dispatcher.UIThread.Post(() => ShowAtlasError("load"));
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
                    ShowAtlasError("load");
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

    private void ExecuteMapCommand(string script)
    {
        if (_atlasFailed || !_attachedToVisualTree || !_pageReady || _webView is null)
            return;

        var webView = _webView;
        Dispatcher.UIThread.Post(async () =>
        {
            if (_atlasFailed || !_attachedToVisualTree || !_pageReady || !ReferenceEquals(webView, _webView))
                return;
            try
            {
                await webView.InvokeScript(script);
            }
            catch (Exception exception)
            {
                System.Diagnostics.Trace.TraceWarning("Atlas camera command failed: {0}", exception.GetType().Name);
            }
        });
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
        _pageReady = false;

        if (_webView is { } webView)
        {
            webView.AdapterCreated -= OnAdapterCreated;
            webView.AdapterDestroyed -= OnAdapterDestroyed;
            webView.NavigationStarted -= OnNavigationStarted;
            webView.NavigationCompleted -= OnNavigationCompleted;
            webView.NewWindowRequested -= OnNewWindowRequested;
            webView.WebMessageReceived -= OnWebMessageReceived;
            _webView = null;
        }

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
        retry.Click += (_, _) => TryInitializeWebView();
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
        return normalized is "NL" or "DE" or "FI" or "RU" ? normalized : null;
    }
}
