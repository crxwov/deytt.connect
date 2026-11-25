using System.Net;
using System.Net.Http.Headers;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private readonly Func<CancellationToken, Task<WindowsUpdateRelease?>>? _manualUpdateLookup;
    private readonly Func<WindowsUpdateRelease, Action<long, long>?, CancellationToken,
        Task<VerifiedWindowsUpdatePackage>>? _manualUpdateDownload;

    private static readonly HttpClient SubscriptionImportHttp = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = false,
        UseCookies = false,
        AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
    })
    {
        Timeout = TimeSpan.FromSeconds(45),
    };

    private async Task ShowSetupWindowAsync(string? initialImportUrl = null, bool forceFreshPairing = false)
    {
        if (_setupFlowCompletion is not null)
        {
            await _setupFlowCompletion.Task;
            return;
        }

        var returnTab = _activeTab == MainTab.Setup ? _setupReturnTab : _activeTab;
        var completion = new TaskCompletionSource<SetupWindowResult?>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        SetupWindow? setupView = null;
        var setup = new SetupWindow(new SetupWindowOptions
        {
            ApiClient = _telegramApi,
            Language = _language,
            InitialImportUrl = initialImportUrl,
            RestoreExistingSession = !forceFreshPairing,
            AllowCancel = _keysSnapshot?.ProfileJson is { Length: > 0 } && _routes.Count > 0,
            DeferSessionSaveUntilImport = forceFreshPairing,
            ImportUrlAsync = ImportSubscriptionUrlAsync,
            CommitImportAsync = (result, cancellationToken) =>
                CommitSetupImportAsync(setupView!, result, cancellationToken),
            ConfirmAsync = (title, message, label) => ConfirmInShellAsync(title, message, label),
        });
        setupView = setup;
        setup.Completed += (_, args) => completion.TrySetResult(args.Result);
        setup.BackRequested += (_, _) => completion.TrySetResult(null);

        EnsureHostedViewLifecycle();
        _setupReturnTab = returnTab;
        _activeSetupView = setup;
        _setupFlowCompletion = completion;

        SetupWindowResult? completedResult = null;
        try
        {
            if (_routeGlobe is { Parent: not null } globe)
            {
                var capture = globe.CapturePreviewAsync();
                try { _setupPagePreview = await capture.WaitAsync(PagePreviewCaptureTimeout); }
                catch (TimeoutException)
                {
                    _ = capture.ContinueWith(task => task.Result?.Dispose(), CancellationToken.None,
                        TaskContinuationOptions.OnlyOnRanToCompletion, TaskScheduler.Default);
                }
            }
            if (!IsVisible) return;
            ShowTab(MainTab.Setup);
            await setup.ActivateAsync();
            completedResult = await completion.Task;
        }
        finally
        {
            setup.Abort();
            _activeSetupView = null;
            _setupFlowCompletion = null;
            if (IsVisible && _activeTab == MainTab.Setup)
                ShowTab(completedResult is null ? returnTab : MainTab.Home);
            _setupPagePreview?.Dispose();
            _setupPagePreview = null;
            _setupBackdropPage = null;
            if (IsVisible && _pendingExternalImportUrl is { Length: > 0 } queuedImportUrl)
            {
                _pendingExternalImportUrl = null;
                _ = ShowSetupWindowAsync(queuedImportUrl);
            }
        }
    }

    private async Task<SetupSubscriptionPayload> ImportSubscriptionUrlAsync(
        string subscriptionUrl,
        IProgress<string> progress,
        CancellationToken cancellationToken)
    {
        if (!SetupWindow.TryGetSubscriptionUrl(subscriptionUrl, out var normalizedUrl))
            throw new InvalidDataException("The subscription URL is not a first-party URL.");

        var uri = new UriBuilder(normalizedUrl);
        var query = uri.Query.TrimStart('?');
        if (!query.Split('&', StringSplitOptions.RemoveEmptyEntries)
                .Any(value => value.StartsWith("format=", StringComparison.OrdinalIgnoreCase)))
            query = string.IsNullOrEmpty(query) ? "format=singbox" : $"{query}&format=singbox";
        uri.Query = query;

        progress.Report(Copy("Загружаем конфигурацию маршрутов…", "Downloading route configuration…"));
        using var request = new HttpRequestMessage(HttpMethod.Get, uri.Uri);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        request.Headers.UserAgent.ParseAdd("deytt-connect/windows");
        using var response = await SubscriptionImportHttp.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (response.StatusCode != HttpStatusCode.OK)
            throw new HttpRequestException("The subscription server did not return a profile.");
        if (response.Content.Headers.ContentLength is > 2 * 1024 * 1024)
            throw new InvalidDataException("The subscription profile is too large.");

        var profileJson = await ReadBoundedTextAsync(response.Content, 2 * 1024 * 1024, cancellationToken);
        progress.Report(Copy("Проверяем структуру и список маршрутов…", "Checking profile structure and routes…"));
        using var document = JsonDocument.Parse(profileJson);
        var routes = WindowsRouteCatalog.Parse(document.RootElement);
        if (routes.Count == 0)
            throw new InvalidDataException("The subscription has no routes.");

        var snapshot = new TelegramKeysSnapshot(true, false, 0, [], profileJson, routes, []);
        return new SetupSubscriptionPayload(null, null, snapshot, normalizedUrl);
    }

    private async Task CommitSetupImportAsync(SetupWindow owner, SetupWindowResult result,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var payload = result.Payload;
        if (payload.Subscription.ProfileJson is not { Length: > 0 } profile ||
            result.SelectedRoute is null)
            throw new InvalidDataException("The imported subscription is incomplete.");

        if (_vpnActionInProgress || _routeProbeInProgress)
            throw new InvalidOperationException("Wait for the current VPN action to finish before importing.");

        _vpnActionInProgress = true;
        try
        {
            var coordinator = new WindowsSubscriptionImportCoordinator(
                _tunnelClient.GetStatusAsync,
                _tunnelClient.DisconnectAsync,
                IsTunnelTransportError);
            await coordinator.CommitAsync(
                _vpnSnapshot,
                () => ConfirmImportTunnelDisconnectAsync(owner),
                _ =>
                {
                    if (payload.SessionToken is { Length: > 0 } token)
                    {
                        // Clear the previous anonymous import before replacing the account token.
                        // A cleanup failure must leave the currently saved account untouched.
                        WindowsImportedSubscriptionStore.Clear();
                        WindowsSessionStore.Save(token);
                    }
                    else
                    {
                        WindowsImportedSubscriptionStore.Save(profile);
                    }
                    return Task.CompletedTask;
                },
                state =>
                {
                    var previousState = _vpnSnapshot.State;
                    _vpnServiceAvailable = state.ServiceAvailable;
                    _vpnSnapshot = state.ServiceAvailable ? state.Snapshot : VpnServiceUnavailableSnapshot();
                    UpdateMapLocationForVpnState(previousState, _vpnSnapshot.State);
                },
                cancellationToken);
        }
        finally
        {
            _vpnActionInProgress = false;
            RenderActiveTabPreservingScroll();
        }

        if (payload.SessionToken is { Length: > 0 } token)
            _sessionToken = token;

        _account = payload.Account;
        _keysSnapshot = payload.Subscription;
        _routes = payload.Subscription.Routes;
        _selectedRoute = result.SelectedRoute.Id;
        _profileLoadIssue = null;
        SavePreferences();
        ShowTab(_activeTab);
    }

    private async Task<bool> ConfirmImportTunnelDisconnectAsync(SetupWindow owner)
    {
        return await ConfirmInShellAsync(
            Copy("VPN-соединение активно", "VPN connection is active"),
            Copy("Чтобы сохранить новую подписку и маршрут, нужно остановить текущий туннель. После импорта VPN останется выключенным.",
                "The current tunnel must stop before the new subscription and route can be applied. VPN will remain off after import."),
            Copy("Отключить и продолжить", "Disconnect and continue"),
            Copy("Отменить импорт", "Cancel import"));
    }

    private bool RestoreImportedSubscription()
    {
        try
        {
            var profileJson = WindowsImportedSubscriptionStore.Load();
            if (profileJson is null)
                return false;
            using var document = JsonDocument.Parse(profileJson);
            var routes = WindowsRouteCatalog.Parse(document.RootElement);
            if (routes.Count == 0)
                throw new InvalidDataException("The imported subscription has no routes.");

            _keysSnapshot = new TelegramKeysSnapshot(true, false, 0, [], profileJson, routes, []);
            _routes = routes;
            if (!_routes.Any(route => route.Id == _selectedRoute))
                _selectedRoute = "auto";
            _profileLoadIssue = null;
            return true;
        }
        catch (Exception error) when (error is JsonException or InvalidDataException or IOException or
                                      UnauthorizedAccessException or CryptographicException)
        {
            try { WindowsImportedSubscriptionStore.Clear(); }
            catch (Exception clearError) when (clearError is IOException or UnauthorizedAccessException) { }
            _keysSnapshot = null;
            _routes = [];
            _profileLoadIssue = Copy("Импортированный профиль повреждён. Импортируйте подписку снова.",
                "The imported profile is damaged. Import the subscription again.");
            return false;
        }
    }

    private async Task ShowMapRoutePickerAsync(string node)
    {
        if (node == "user")
        {
            var place = _mapRegionEnabled && _mapRegionConsentGranted
                ? _mapOriginLocation?.PlaceLabel
                : null;
            if (string.IsNullOrWhiteSpace(place))
                place = Copy("Точка входа с этого устройства", "This device’s entry point");

            ShowInfoDialog(Copy("Точка входа", "Entry point"),
                Copy($"{place} — примерное место по IP. Оно не является сервером выхода и не влияет на выбор маршрута.",
                    $"{place} is an approximate location based on your IP. It is not an exit server and does not affect route selection."));
            return;
        }

        var countryCode = node.ToLowerInvariant() switch
        {
            "nl" => "NL",
            "de" => "DE",
            "fi" => "FI",
            "it" => "IT",
            "ru" => "RU",
            _ => null,
        };
        if (countryCode is null)
            return;

        var countryRoutes = _routes.Where(route =>
                route.CountryCode == countryCode &&
                route.Protocol is "VLESS" or "TROJAN" or "HYSTERIA2" or "TUIC" or "AWG31")
            .OrderBy(route => route.Protocol switch
            {
                "LTE" => 0,
                "VLESS" => 1,
                "TROJAN" => 2,
                "HYSTERIA2" => 3,
                "TUIC" => 4,
                "AWG31" => 4,
                _ => 4,
            })
            .ToArray();
        var choices = new List<WindowsRoute>();
        if (_routes.FirstOrDefault(route => route.Protocol == "AUTO") is { } automatic)
            choices.Add(automatic);
        choices.AddRange(countryRoutes);
        if ((countryCode is "RU" or "DE") &&
            _routes.FirstOrDefault(route => route.Protocol == "RU-DE") is { } chain)
            choices.Add(chain);

        var countryName = countryRoutes.FirstOrDefault()?.CountryName ?? countryCode switch
        {
            "NL" => Copy("Нидерланды", "Netherlands"),
            "DE" => Copy("Германия", "Germany"),
            "FI" => Copy("Финляндия", "Finland"),
            "IT" => Copy("Италия", "Italy"),
            _ => Copy("Россия", "Russia"),
        };
        if (choices.Count == 0)
        {
            ShellContentDialog? noRoutesDialog = null;
            var content = new StackPanel
            {
                Spacing = 11,
                Children =
                {
                    DeyttTheme.TextBlock(Copy("Для этой точки нет маршрута в подписке.",
                        "Your subscription has no route for this location."), 14, DeyttTheme.Muted),
                    DeyttTheme.PrimaryButton(Copy("Открыть маршруты", "Open routes"),
                        () => noRoutesDialog?.Close(MainTab.Routes)),
                },
            };
            var noRoutesResult = await ShowSmallDialog(countryName, content, 390,
                dialog => noRoutesDialog = dialog);
            if (noRoutesResult is MainTab.Routes)
                ShowTab(MainTab.Routes);
            return;
        }

        var options = new StackPanel { Spacing = 8 };
        ShellContentDialog? pickerDialog = null;
        foreach (var route in choices)
        {
            var detail = WindowsRouteCatalog.FormatRouteDetail(route, _language == "en");
            var selected = route.Id == _selectedRoute;
            var title = WindowsRouteCatalog.FormatRouteTitle(route);
            var labels = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
            labels.Children.Add(DeyttTheme.TextBlock(
                title,
                15, DeyttTheme.Text, FontWeight.SemiBold));
            labels.Children.Add(DeyttTheme.TextBlock(detail, 11, DeyttTheme.Muted));
            var row = new Grid { ColumnDefinitions = new ColumnDefinitions("42,*,Auto"), MinHeight = 66 };
            row.Children.Add(DeyttTheme.TextBlock(route.Flag, 21, DeyttTheme.Text,
                FontWeight.Normal, wrap: false));
            Grid.SetColumn(labels, 1);
            row.Children.Add(labels);
            if (selected)
            {
                var current = DeyttTheme.TextBlock(Copy("ВЫБРАН", "SELECTED"), 9,
                    DeyttTheme.Mint, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false);
                current.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(current, 2);
                row.Children.Add(current);
            }
            var button = DeyttTheme.Action(row, () =>
                pickerDialog?.Close(route.Id));
            button.Padding = new Thickness(12, 8);
            button.Background = DeyttTheme.Brush(selected ? DeyttTheme.Selected : DeyttTheme.Surface2);
            options.Children.Add(button);
        }

        var body = new StackPanel { Spacing = 14 };
        body.Children.Add(DeyttTheme.TextBlock(Copy("ВЫХОД · ", "EXIT · ") + countryName.ToUpperInvariant(),
            9, DeyttTheme.Sky, FontWeight.SemiBold, DeyttTheme.JetBrainsMono));
        body.Children.Add(DeyttTheme.TextBlock(countryName, 26, DeyttTheme.Text, FontWeight.Bold));
        body.Children.Add(DeyttTheme.TextBlock(
            Copy("Выберите маршрут или сравните протоколы.", "Choose a route or compare protocols."),
            13, DeyttTheme.Muted));
        if (WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State))
            body.Children.Add(DeyttTheme.TextBlock(
                Copy("Смена выхода остановит соединение. Новый маршрут нужно запустить отдельно.",
                    "Changing the exit stops the current connection. Start the new route separately."),
                12, DeyttTheme.Amber));
        body.Children.Add(new ScrollViewer
        {
            Content = options,
            MaxHeight = 400,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
        });
        if (countryRoutes.Length > 1)
            body.Children.Add(DeyttTheme.Action(
                DeyttTheme.TextBlock(Copy("Сравнить протоколы", "Compare protocols"), 13,
                    DeyttTheme.Sky, FontWeight.SemiBold),
                () => pickerDialog?.Close(MainTab.Routes)));

        var result = await ShowContentInShellAsync(countryName, body, 620,
            dialog => pickerDialog = dialog);
        if (result is string routeId && choices.Any(route => route.Id == routeId))
            SelectRoute(routeId);
        else if (result is MainTab.Routes)
            ShowTab(MainTab.Routes);
    }

    private Task<object?> ShowSmallDialog(
        string title,
        Control content,
        double maxWidth,
        Action<ShellContentDialog>? configure = null) =>
        ShowContentInShellAsync(title, content, maxWidth, configure);

    private Task ShowSupportWindowAsync()
    {
        _supportReturnTab = _activeTab is MainTab.Support or MainTab.Setup
            ? MainTab.Profile
            : _activeTab;
        ShowTab(MainTab.Support);
        return Task.CompletedTask;
    }

    private Task ShowHelpAndDocumentsAsync()
    {
        _supportReturnTab = _activeTab is MainTab.Support or MainTab.Setup
            ? MainTab.Profile
            : _activeTab;
        ShowTab(MainTab.Support);
        _supportView?.ShowHelpIndex();
        return Task.CompletedTask;
    }

    private void ToggleImportLinkProtocol()
    {
        try
        {
            if (WindowsImportLinkProtocol.IsRegistered())
            {
                WindowsImportLinkProtocol.Unregister();
                ShowInfoDialog(Copy("Ссылки DEYTT отвязаны", "DEYTT links disconnected"),
                    Copy("ссылки подписки больше не будут открываться в этом приложении.",
                        "subscription links will no longer open in this app."));
            }
            else
            {
                WindowsImportLinkProtocol.Register();
                ShowInfoDialog(Copy("Ссылки DEYTT подключены", "DEYTT links connected"),
                    Copy("Ссылки подписки будут открывать окно импорта DEYTT Connect.",
                        "Subscription links will open the DEYTT Connect import window."));
            }
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
                                      System.Security.SecurityException or PlatformNotSupportedException)
        {
            ShowInfoDialog(Copy("Не удалось настроить ссылки", "Could not set up links"),
                Copy("проверьте разрешения текущего пользователя.",
                    "check permissions for the current user."));
        }
        finally
        {
            ShowTab(MainTab.Settings);
        }
    }

    private Task ShowUpdateDialogAsync() => ShowUpdateDialogCoreAsync(null);

    private async Task ShowUpdateDialogCoreAsync(WindowsUpdateRelease? preloadedRelease)
    {
        var linux = OperatingSystem.IsLinux();
        var installedVersion = WindowsUpdateClient.GetInstalledVersion()?.ToString(3) ?? "0.0.0";
        var headline = DeyttTheme.TextBlock(
            preloadedRelease is not null
                ? Copy("доступно обновление ./connect", "./connect update available")
                : Copy("обновления ./connect", "./connect updates"),
            22, DeyttTheme.Text, FontWeight.Bold);
        var status = DeyttTheme.TextBlock(
            Copy("проверяем сервер deytt.space и github…", "checking deytt.space server and github…"),
            13, DeyttTheme.Muted);

        var currentVersionLabel = DeyttTheme.TextBlock(installedVersion, 14, DeyttTheme.Muted, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, false);
        var nextVersionLabel = DeyttTheme.TextBlock("—", 14, DeyttTheme.Mint, FontWeight.Bold, DeyttTheme.JetBrainsMono, false);
        var packageMetaLabel = DeyttTheme.TextBlock("", 12, DeyttTheme.Muted);
        var versionRow = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("Auto,Auto,Auto,*"),
            ColumnSpacing = 10,
        };
        var currentCol = new StackPanel
        {
            Spacing = 2,
            Children = { DeyttTheme.TextBlock(Copy("установлено", "installed"), 11, DeyttTheme.Muted), currentVersionLabel },
        };
        var arrow = DeyttTheme.TextBlock("→", 16, DeyttTheme.Muted, FontWeight.SemiBold, wrap: false);
        arrow.VerticalAlignment = VerticalAlignment.Center;
        var nextCol = new StackPanel
        {
            Spacing = 2,
            Children = { DeyttTheme.TextBlock(Copy("новая версия", "latest"), 11, DeyttTheme.Mint), nextVersionLabel },
        };
        Grid.SetColumn(arrow, 1);
        Grid.SetColumn(nextCol, 2);
        versionRow.Children.Add(currentCol);
        versionRow.Children.Add(arrow);
        versionRow.Children.Add(nextCol);

        var versionPanel = DeyttTheme.DialogPanel(new StackPanel
        {
            Spacing = 6,
            Children = { versionRow, packageMetaLabel },
        }, radius: 14, padding: new Thickness(14, 12));
        versionPanel.IsVisible = false;

        var notesText = DeyttTheme.TextBlock("", 13, DeyttTheme.Text);
        notesText.LineHeight = 19;
        var notesPanel = DeyttTheme.Card(new StackPanel
        {
            Spacing = 8,
            Children =
            {
                DeyttTheme.TextBlock(Copy("что изменилось", "what changed"), 12, DeyttTheme.Muted, FontWeight.SemiBold, DeyttTheme.JetBrainsMono),
                new ScrollViewer
                {
                    MaxHeight = 185,
                    HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
                    VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
                    Content = notesText,
                },
            },
        }, fill: DeyttTheme.Surface, stroke: DeyttTheme.Line, radius: 14, padding: new Thickness(14, 12));
        notesPanel.IsVisible = false;

        var progressBar = new ProgressBar
        {
            Minimum = 0,
            Maximum = 100,
            Value = 0,
            Height = 6,
            CornerRadius = new CornerRadius(3),
            Foreground = DeyttTheme.Brush(DeyttTheme.Mint),
            Background = DeyttTheme.Brush(DeyttTheme.Line),
            IsVisible = false,
        };

        var downloadLabel = DeyttTheme.TextBlock(
            linux ? Copy("скачать пакет", "download package") : Copy("обновить сейчас", "update now"),
            16, Colors.White, FontWeight.SemiBold, wrap: false);
        var packageButton = DeyttTheme.DialogPrimaryButton(downloadLabel, () => { });
        packageButton.IsVisible = false;
        var download = (Button)packageButton.Child!;
        AutomationProperties.SetAutomationId(download, "ManualUpdateDownloadButton");

        ShellContentDialog? activeModal = null;
        var dismissButton = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("позже", "later"), 13, DeyttTheme.Muted, FontWeight.SemiBold, wrap: false),
            () => activeModal?.Close());
        var cancelDownloadButton = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("отменить загрузку", "cancel download"), 13, DeyttTheme.Muted, FontWeight.SemiBold, wrap: false),
            () => { });
        cancelDownloadButton.IsVisible = false;
        var openRelease = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("все релизы", "all releases"), 13, DeyttTheme.Muted, FontWeight.SemiBold, wrap: false),
            () => OpenExternal("https://github.com/crxwov/deytt.connect/releases"));

        var footerBar = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"),
            ColumnSpacing = 12,
        };
        Grid.SetColumn(dismissButton, 0);
        Grid.SetColumn(cancelDownloadButton, 0);
        Grid.SetColumn(openRelease, 2);
        footerBar.Children.Add(dismissButton);
        footerBar.Children.Add(cancelDownloadButton);
        footerBar.Children.Add(openRelease);

        var body = new StackPanel
        {
            Spacing = 12,
            Children =
            {
                DeyttTheme.TextBlock(linux ? "deytt./update · linux" : "deytt./update · windows", 11,
                    DeyttTheme.Mint, FontWeight.SemiBold, DeyttTheme.JetBrainsMono),
                headline,
                status,
                versionPanel,
                notesPanel,
                progressBar,
                packageButton,
                footerBar,
            },
        };
        using var cancellation = new CancellationTokenSource();

        WindowsUpdateRelease? release = null;
        CancellationTokenSource? downloadCancellation = null;
        var downloadInFlight = false;
        var dialogIsOpen = false;

        cancelDownloadButton.Click += (_, _) => downloadCancellation?.Cancel();

        void PresentRelease(WindowsUpdateRelease candidate)
        {
            var nextVer = WindowsUpdateClient.GetDisplayVersion(candidate);
            var sourceHost = Uri.TryCreate(candidate.AssetUrl, UriKind.Absolute, out var uri) &&
                             (uri.Host.Equals("deytt.space", StringComparison.OrdinalIgnoreCase) ||
                              uri.Host.Equals("ru.deytt.space", StringComparison.OrdinalIgnoreCase))
                ? "deytt.space"
                : "github";
            nextVersionLabel.Text = nextVer;
            versionPanel.IsVisible = true;

            var formattedNotes = WindowsUpdateClient.FormatDisplayNotes(candidate.Notes);
            notesText.Text = string.IsNullOrWhiteSpace(formattedNotes)
                ? Copy("улучшения стабильности, интерфейса и скорости работы ./connect.",
                    "stability, interface, and performance improvements for ./connect.")
                : formattedNotes;
            notesPanel.IsVisible = true;

            if (candidate.CanDownloadVerifiedPackage)
            {
                var megabytes = candidate.AssetSize!.Value / (1024d * 1024d);
                packageMetaLabel.Text = $"{candidate.AssetName} · {megabytes:0.0} мб · {sourceHost}";
                status.Text = Copy("готово к загрузке и проверке sha-256.",
                    "ready to download and verify sha-256.");
                downloadLabel.Text = linux
                    ? Copy($"скачать {nextVer}", $"download {nextVer}")
                    : Copy($"обновить до {nextVer}", $"update to {nextVer}");
                packageButton.IsVisible = true;
            }
            else
            {
                packageMetaLabel.Text = $"{candidate.AssetName} · {sourceHost}";
                status.Text = Copy(
                    "для этого пакета не переданы sha-256 и размер; автоматическая проверка невозможна.",
                    "sha-256 and size metadata are missing for this package; verified download is unavailable.");
            }
        }

        async Task BeginDownloadAsync()
        {
            if (release is null || !release.CanDownloadVerifiedPackage || downloadInFlight)
                return;

            downloadInFlight = true;
            download.IsEnabled = false;
            dismissButton.IsVisible = false;
            cancelDownloadButton.IsVisible = true;
            progressBar.Value = 0;
            progressBar.IsVisible = true;
            downloadCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellation.Token);
            status.Text = Copy("загружаем пакет и сверяем sha-256…", "downloading package and checking sha-256…");
            try
            {
                Action<long, long> onProgress = (received, total) => Avalonia.Threading.Dispatcher.UIThread.Post(() =>
                {
                    if (!dialogIsOpen)
                        return;
                    var percent = total > 0 ? Math.Clamp(received * 100.0 / total, 0, 100) : 0;
                    progressBar.Value = percent;
                    var recvMb = received / (1024d * 1024d);
                    var totalMb = total / (1024d * 1024d);
                    status.Text = total > 0
                        ? Copy($"загрузка · {percent:0}% · {recvMb:0.0} / {totalMb:0.0} мб",
                            $"downloading · {percent:0}% · {recvMb:0.0} / {totalMb:0.0} mb")
                        : Copy($"загрузка · {recvMb:0.0} мб", $"downloading · {recvMb:0.0} mb");
                });
                var package = await (_manualUpdateDownload?.Invoke(release, onProgress, downloadCancellation.Token) ??
                                     DownloadAndLaunchUpdateAsync(release, onProgress, downloadCancellation.Token));

                if (!dialogIsOpen)
                    return;

                progressBar.Value = 100;
                var megabytes = package.Size / (1024d * 1024d);
                status.Text = linux
                    ? Copy($"пакет проверен · sha-256 {package.Sha256}\nустановите его менеджером пакетов: {package.FilePath}",
                        $"package verified · sha-256 {package.Sha256}\ninstall it with your package manager: {package.FilePath}")
                    : release.PackageKind == WindowsUpdatePackageKind.Msi
                    ? Copy($"msi проверен · {megabytes:0.0} мб · sha-256 {package.Sha256}\nзапущен установщик windows.",
                        $"msi verified · {megabytes:0.0} mb · sha-256 {package.Sha256}\nwindows installer has started.")
                    : Copy($"zip проверен · {megabytes:0.0} мб · sha-256 {package.Sha256}\nпроводник выделил архив.",
                        $"zip verified · {megabytes:0.0} mb · sha-256 {package.Sha256}\nfile explorer selected the archive.");
                packageButton.IsVisible = false;
            }
            catch (OperationCanceledException) when (downloadCancellation?.IsCancellationRequested == true)
            {
                if (dialogIsOpen)
                {
                    progressBar.IsVisible = false;
                    status.Text = Copy("загрузка отменена. можно попробовать ещё раз.",
                        "download cancelled. you can try again.");
                }
            }
            catch (System.ComponentModel.Win32Exception error) when (error.NativeErrorCode == 1223)
            {
                if (dialogIsOpen)
                {
                    progressBar.IsVisible = false;
                    status.Text = Copy("запуск установки отменён.", "installer launch was cancelled.");
                }
            }
            catch (Exception error) when (error is HttpRequestException or IOException or InvalidDataException or
                                          UnauthorizedAccessException or System.Security.SecurityException or
                                          System.ComponentModel.Win32Exception or PlatformNotSupportedException or
                                          InvalidOperationException)
            {
                if (dialogIsOpen)
                {
                    progressBar.IsVisible = false;
                    status.Text = Copy("не удалось загрузить, проверить или открыть пакет обновления.",
                        "could not download, verify, or open the update package.");
                }
            }
            finally
            {
                downloadCancellation?.Dispose();
                downloadCancellation = null;
                if (dialogIsOpen)
                {
                    downloadInFlight = false;
                    cancelDownloadButton.IsVisible = false;
                    dismissButton.IsVisible = true;
                    download.IsEnabled = true;
                }
            }
        }
        download.Click += async (_, _) => await BeginDownloadAsync();

        async Task LoadLatestReleaseAsync()
        {
            if (preloadedRelease is not null)
            {
                release = preloadedRelease;
                PresentRelease(release);
                return;
            }

            try
            {
                release = await (_manualUpdateLookup?.Invoke(cancellation.Token) ??
                                 FindManualUpdateAsync(cancellation.Token));
                if (!dialogIsOpen)
                    return;
                if (release is null)
                {
                    status.Text = Copy(
                        $"установлена последняя версия ({installedVersion}) для этого формата пакета.",
                        $"you are on the latest version ({installedVersion}) for this package format.");
                }
                else
                {
                    PresentRelease(release);
                }
            }
            catch (OperationCanceledException)
            {
                if (dialogIsOpen && !cancellation.IsCancellationRequested)
                    status.Text = Copy("проверка заняла слишком много времени. попробуйте ещё раз.",
                        "the update check timed out. try again.");
            }
            catch (Exception error) when (error is HttpRequestException or JsonException or IOException or
                                          InvalidDataException)
            {
                if (dialogIsOpen)
                    status.Text = Copy("не удалось проверить релизы. проверьте подключение и повторите.",
                        "could not check releases. check the connection and try again.");
            }
        }

        var releaseLookup = Task.CompletedTask;
        await ShowContentInShellAsync(Copy("обновления", "updates"), body, 560, modal =>
        {
            activeModal = modal;
            dialogIsOpen = true;
            modal.Closed += () =>
            {
                dialogIsOpen = false;
                cancellation.Cancel();
                downloadCancellation?.Cancel();
            };
            releaseLookup = LoadLatestReleaseAsync();
        });
        await releaseLookup;
    }

    private static async Task<string> ReadBoundedTextAsync(
        HttpContent content,
        int maximumBytes,
        CancellationToken cancellationToken)
    {
        await using var stream = await content.ReadAsStreamAsync(cancellationToken);
        using var output = new MemoryStream();
        var buffer = new byte[16 * 1024];
        while (true)
        {
            var read = await stream.ReadAsync(buffer, cancellationToken);
            if (read == 0)
                break;
            if (output.Length + read > maximumBytes)
                throw new InvalidDataException("The server response is too large.");
            output.Write(buffer, 0, read);
        }
        return new System.Text.UTF8Encoding(false, true).GetString(output.ToArray());
    }

}
