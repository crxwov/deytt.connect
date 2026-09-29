using System.Net;
using System.Net.Http.Headers;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private static readonly HttpClient SubscriptionImportHttp = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = false,
        UseCookies = false,
        AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
    })
    {
        Timeout = TimeSpan.FromSeconds(45),
    };

    private async Task ShowSetupWindowAsync(string? initialImportUrl = null)
    {
        SetupWindow? setupWindow = null;
        var setup = new SetupWindow(new SetupWindowOptions
        {
            ApiClient = _telegramApi,
            Language = _language,
            InitialImportUrl = initialImportUrl,
            RestoreExistingSession = true,
            ImportUrlAsync = ImportSubscriptionUrlAsync,
            CommitImportAsync = (result, cancellationToken) =>
                CommitSetupImportAsync(setupWindow!, result, cancellationToken),
        });
        setupWindow = setup;

        await setup.ShowDialog(this);
        if (setup.Result is not null)
            ShowTab(_activeTab);
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
        request.Headers.TryAddWithoutValidation("X-Deytt-Client", "deytt-connect");
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

        if (OperatingSystem.IsWindows())
        {
            if (_vpnActionInProgress || _routeProbeInProgress)
                throw new InvalidOperationException("Wait for the current VPN action to finish before importing.");

            _vpnActionInProgress = true;
            try
            {
                await EnsureTunnelStoppedBeforeImportAsync(owner, cancellationToken);
            }
            catch (Exception error) when (IsTunnelTransportError(error))
            {
                _vpnServiceAvailable = false;
                throw new IOException("Could not verify or stop the active VPN tunnel before importing.", error);
            }
            finally
            {
                _vpnActionInProgress = false;
                RenderActiveTabPreservingScroll();
            }
        }

        if (payload.SessionToken is { Length: > 0 } token)
        {
            if (OperatingSystem.IsWindows())
            {
                WindowsSessionStore.Save(token);
                WindowsImportedSubscriptionStore.Clear();
            }
            _sessionToken = token;
        }
        else if (OperatingSystem.IsWindows())
        {
            WindowsImportedSubscriptionStore.Save(profile);
        }

        _account = payload.Account;
        _keysSnapshot = payload.Subscription;
        _routes = payload.Subscription.Routes;
        _selectedRoute = result.SelectedRoute.Id;
        _profileLoadIssue = null;
        _sessionLoadIssue = null;
        SavePreferences();
        ShowTab(_activeTab);
    }

    private async Task EnsureTunnelStoppedBeforeImportAsync(SetupWindow owner,
        CancellationToken cancellationToken)
    {
        var previousSnapshot = _vpnSnapshot;
        _vpnSnapshot = await _tunnelClient.GetStatusAsync(cancellationToken);
        _vpnServiceAvailable = true;
        UpdateMapLocationForVpnState(previousSnapshot.State, _vpnSnapshot.State);

        if (IsInactiveTunnelState(_vpnSnapshot.State))
            return;

        if (!await ConfirmImportTunnelDisconnectAsync(owner))
            throw new InvalidOperationException("The subscription import was canceled because the VPN remains active.");

        previousSnapshot = _vpnSnapshot;
        _vpnSnapshot = await _tunnelClient.DisconnectAsync(cancellationToken);
        if (!IsInactiveTunnelState(_vpnSnapshot.State))
            _vpnSnapshot = await _tunnelClient.GetStatusAsync(cancellationToken);
        _vpnServiceAvailable = true;
        UpdateMapLocationForVpnState(previousSnapshot.State, _vpnSnapshot.State);

        if (!IsInactiveTunnelState(_vpnSnapshot.State))
            throw new IOException("The VPN service did not confirm that the tunnel stopped.");
    }

    private async Task<bool> ConfirmImportTunnelDisconnectAsync(SetupWindow owner)
    {
        var confirmed = false;
        var dialog = new Window
        {
            Title = Copy("Отключить VPN для импорта?", "Disconnect VPN to import?"),
            Width = 420,
            MinWidth = 340,
            MaxWidth = 470,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
        };
        var cancel = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("Отменить импорт", "Cancel import"), 14,
                DeyttTheme.Muted, FontWeight.SemiBold),
            () => dialog.Close());
        var disconnect = DeyttTheme.PrimaryButton(
            Copy("Отключить и продолжить", "Disconnect and continue"),
            () =>
            {
                confirmed = true;
                dialog.Close();
            });
        dialog.Content = DeyttTheme.Card(new StackPanel
        {
            Spacing = 14,
            Children =
            {
                DeyttTheme.TextBlock(Copy("VPN-соединение активно", "VPN connection is active"),
                    21, DeyttTheme.Text, FontWeight.Bold),
                DeyttTheme.TextBlock(Copy(
                    "Чтобы сохранить новую подписку и маршрут, нужно остановить текущий туннель. После импорта VPN останется выключенным.",
                    "The current tunnel must stop before the new subscription and route can be applied. VPN will remain off after import."),
                    13, DeyttTheme.Muted),
                cancel,
                disconnect,
            },
        }, DeyttTheme.Surface, DeyttTheme.Line, 20);

        await dialog.ShowDialog(owner);
        return confirmed;
    }

    private static bool IsInactiveTunnelState(string state) =>
        state is "disconnected" or "idle" or "error";

    private bool RestoreImportedSubscription()
    {
        if (!OperatingSystem.IsWindows())
            return false;

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
            "ru" => "RU",
            _ => null,
        };
        if (countryCode is null)
            return;

        var countryRoutes = _routes.Where(route =>
                route.CountryCode == countryCode &&
                route.Protocol is "VLESS" or "TROJAN" or "HYSTERIA2")
            .OrderBy(route => route.Protocol switch
            {
                "VLESS" => 0,
                "TROJAN" => 1,
                "HYSTERIA2" => 2,
                _ => 3,
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
            _ => Copy("Россия", "Russia"),
        };
        if (choices.Count == 0)
        {
            var content = new StackPanel
            {
                Spacing = 11,
                Children =
                {
                    DeyttTheme.TextBlock(Copy("Для этой точки нет маршрута в подписке.",
                        "Your subscription has no route for this location."), 14, DeyttTheme.Muted),
                    DeyttTheme.PrimaryButton(Copy("Открыть маршруты", "Open routes"),
                        () => ShowTab(MainTab.Routes)),
                },
            };
            ShowSmallDialog(countryName, content, 390);
            return;
        }

        var options = new StackPanel { Spacing = 8 };
        var picker = new Window
        {
            Title = countryName,
            Width = 520,
            MinWidth = 380,
            MaxWidth = 620,
            Height = 600,
            MinHeight = 420,
            MaxHeight = 760,
            CanResize = true,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
        };
        foreach (var route in choices)
        {
            var detail = route.Protocol switch
            {
                "AUTO" => Copy("Выбрать доступный выход автоматически", "Select an available exit automatically"),
                "RU-DE" => Copy("Двойной маршрут · Санкт-Петербург → Франкфурт", "Double route · Saint Petersburg → Frankfurt"),
                "VLESS" or "TROJAN" => Copy("WebSocket + TLS", "WebSocket + TLS"),
                "HYSTERIA2" => Copy("Быстрый QUIC-маршрут", "Fast QUIC route"),
                "AWG31" => route.ProfileName ?? Copy("Отдельный профиль AmneziaWG", "Separate AmneziaWG profile"),
                _ => route.ProfileName ?? route.ProtocolName,
            };
            var selected = route.Id == _selectedRoute;
            var labels = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
            labels.Children.Add(DeyttTheme.TextBlock(
                route.Protocol == "AUTO" ? Copy("Автоподбор", "Auto-select") :
                route.Protocol == "RU-DE" ? Copy("LTE + белые списки · RU → DE", "LTE + whitelist · RU → DE") :
                route.ProtocolName,
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
            {
                picker.Close();
                SelectRoute(route.Id);
            });
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
        if (_vpnSnapshot.State is "connected" or "starting" or "checking")
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
                () =>
                {
                    picker.Close();
                    ShowTab(MainTab.Routes);
                }));
        picker.Content = new ScrollViewer
        {
            Content = DeyttTheme.Card(body, DeyttTheme.Surface, DeyttTheme.Line, 22,
                new Thickness(22)),
            Margin = new Thickness(18),
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
        };
        await picker.ShowDialog(this);
    }

    private void ShowSmallDialog(string title, Control content, double width)
    {
        var dialog = new Window
        {
            Title = title,
            Width = width,
            SizeToContent = SizeToContent.Height,
            MaxHeight = 560,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
            Content = DeyttTheme.Card(content, DeyttTheme.Surface, DeyttTheme.Line, 20,
                new Thickness(22)),
        };
        _ = dialog.ShowDialog(this);
    }

    private Task ShowSupportWindowAsync()
    {
        var support = new SupportWindow(_sessionToken, async () =>
        {
            await ShowTelegramPairingDialogAsync();
            return _sessionToken;
        }, _language);
        support.Show(this);
        return Task.CompletedTask;
    }

    private void ToggleImportLinkProtocol()
    {
        if (!OperatingSystem.IsWindows())
        {
            ShowInfoDialog(Copy("Только для Windows", "Windows only"),
                Copy("Связь ссылок с приложением можно настроить в Windows.",
                    "Subscription link association is configured in Windows."));
            return;
        }

        try
        {
            if (WindowsImportLinkProtocol.IsRegistered())
            {
                WindowsImportLinkProtocol.Unregister();
                ShowInfoDialog(Copy("Ссылки DEYTT отвязаны", "DEYTT links disconnected"),
                    Copy("Windows больше не будет открывать ссылки подписки в этом приложении.",
                        "Windows will no longer open subscription links in this app."));
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
                Copy("Проверьте разрешения текущего пользователя Windows.",
                    "Check permissions for the current Windows user."));
        }
        finally
        {
            ShowTab(MainTab.Settings);
        }
    }

    private async Task ShowUpdateDialogAsync()
    {
        var status = DeyttTheme.TextBlock(Copy("Проверяем релизы Windows…", "Checking Windows releases…"),
            13, DeyttTheme.Muted);
        var openRelease = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("Открыть все релизы", "Open all releases"), 13,
                DeyttTheme.Sky, FontWeight.SemiBold),
            () => OpenExternal("https://github.com/crxwov/deytt.connect/releases"));
        var packageButton = DeyttTheme.PrimaryButton(Copy("Скачать и проверить ZIP", "Download and verify ZIP"),
            () => { });
        packageButton.IsVisible = false;
        var download = (Button)packageButton.Child!;
        var openPackageButton = DeyttTheme.PrimaryButton(
            Copy("Открыть проверенный архив", "Open verified package"), () => { });
        openPackageButton.IsVisible = false;
        var openPackage = (Button)openPackageButton.Child!;
        var dialog = new Window
        {
            Title = Copy("Обновления", "Updates"),
            Width = 460,
            MinWidth = 360,
            MaxWidth = 540,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
        };
        var body = new StackPanel
        {
            Spacing = 13,
            Children =
            {
                DeyttTheme.TextBlock(Copy("КАНАЛ WINDOWS", "WINDOWS CHANNEL"), 9,
                    DeyttTheme.Sky, FontWeight.SemiBold, DeyttTheme.JetBrainsMono),
                DeyttTheme.TextBlock(Copy("Обновления приложения", "Application updates"),
                    22, DeyttTheme.Text, FontWeight.Bold),
                status,
                packageButton,
                openPackageButton,
                openRelease,
            },
        };
        dialog.Content = DeyttTheme.Card(body, DeyttTheme.Surface, DeyttTheme.Line, 20,
            new Thickness(22));
        using var cancellation = new CancellationTokenSource();
        dialog.Closed += (_, _) => cancellation.Cancel();

        WindowsUpdateRelease? release = null;
        VerifiedWindowsUpdatePackage? verifiedPackage = null;
        var downloadInFlight = false;

        openPackage.Click += async (_, _) =>
        {
            if (verifiedPackage is null)
                return;

            openPackage.IsEnabled = false;
            try
            {
                await WindowsUpdateClient.OpenVerifiedPackageInExplorerAsync(verifiedPackage, cancellation.Token);
            }
            catch (OperationCanceledException)
            {
                // Closing the dialog cancels the final package verification.
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or
                                          System.ComponentModel.Win32Exception or PlatformNotSupportedException or
                                          InvalidDataException or System.Security.SecurityException)
            {
                if (dialog.IsVisible)
                    status.Text = Copy("Архив изменился после проверки или не открылся в Проводнике.",
                        "The package changed after verification or File Explorer could not open it.");
            }
            finally
            {
                if (dialog.IsVisible)
                    openPackage.IsEnabled = true;
            }
        };

        download.Click += async (_, _) =>
        {
            if (release is null || !release.CanDownloadVerifiedPackage || downloadInFlight)
                return;

            downloadInFlight = true;
            download.IsEnabled = false;
            status.Text = Copy("Загружаем пакет и сверяем SHA-256…", "Downloading package and checking SHA-256…");
            try
            {
                var package = await WindowsUpdateClient.DownloadAndVerifyAsync(release,
                    (received, total) => Avalonia.Threading.Dispatcher.UIThread.Post(() =>
                    {
                        if (dialog.IsVisible)
                        {
                            var percent = total > 0 ? Math.Clamp(received * 100 / total, 0, 100) : 0;
                            status.Text = Copy($"Загрузка и проверка · {percent}%",
                                $"Downloading and verifying · {percent}%");
                        }
                    }), cancellation.Token);

                if (!dialog.IsVisible)
                    return;

                verifiedPackage = package;
                packageButton.IsVisible = false;
                openPackageButton.IsVisible = true;
                var megabytes = package.Size / (1024d * 1024d);
                status.Text = Copy(
                    $"Архив проверен · {megabytes:0.0} МБ · SHA-256 {package.Sha256}\nПроводник выделит ZIP. Распаковка и установка остаются под вашим контролем.",
                    $"Package verified · {megabytes:0.0} MB · SHA-256 {package.Sha256}\nFile Explorer will select the ZIP. Extraction and installation remain under your control.");
            }
            catch (OperationCanceledException)
            {
                if (dialog.IsVisible)
                    status.Text = Copy("Загрузка отменена. Можно попробовать ещё раз.",
                        "Download cancelled. You can try again.");
            }
            catch (Exception error) when (error is HttpRequestException or IOException or InvalidDataException or
                                          UnauthorizedAccessException or JsonException)
            {
                if (dialog.IsVisible)
                    status.Text = Copy("Не удалось загрузить или проверить ZIP. Файл не будет открыт.",
                        "Could not download or verify the ZIP. The file will not be opened.");
            }
            finally
            {
                if (dialog.IsVisible)
                {
                    download.IsEnabled = true;
                    downloadInFlight = false;
                }
            }
        };

        dialog.Opened += async (_, _) =>
        {
            try
            {
                release = await WindowsUpdateClient.FindLatestWindowsReleaseAsync(cancellation.Token);
                if (!dialog.IsVisible)
                    return;
                if (release is null)
                {
                    status.Text = Copy(
                        "В списке GitHub пока нет пакета Windows. Новые версии Android могут выходить отдельно.",
                        "GitHub does not have a Windows package yet. Android releases may appear separately.");
                }
                else
                {
                    var releaseDetails = $"{release.Tag}\n{release.Name}\n{release.Notes}";
                    if (release.CanDownloadVerifiedPackage)
                    {
                        var megabytes = release.AssetSize!.Value / (1024d * 1024d);
                        status.Text = $"{releaseDetails}\n\n{release.AssetName} · {megabytes:0.0} MB";
                        packageButton.IsVisible = true;
                    }
                    else
                    {
                        status.Text = releaseDetails + "\n\n" + Copy(
                            "Для этого ZIP нет корректных данных GitHub о SHA-256 и размере. Проверить загрузку не удаётся.",
                            "GitHub has not provided valid SHA-256 and size metadata for this ZIP. Its download cannot be verified.");
                    }
                }
            }
            catch (OperationCanceledException)
            {
                // Closing the dialog cancels the outstanding release request.
            }
            catch (Exception error) when (error is HttpRequestException or JsonException or IOException)
            {
                if (dialog.IsVisible)
                    status.Text = Copy("Не удалось проверить релизы. Проверьте подключение и повторите.",
                        "Could not check releases. Check the connection and try again.");
            }
        };
        await dialog.ShowDialog(this);
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
