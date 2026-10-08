using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

/// <param name="progress">Reports the stage identifiers download, validate, awg, or save.</param>
public delegate Task<SetupSubscriptionPayload> SetupUrlImportHandler(
    string subscriptionUrl,
    IProgress<string> progress,
    CancellationToken cancellationToken);

public delegate Task SetupImportCommitHandler(
    SetupWindowResult result,
    CancellationToken cancellationToken);

public sealed record SetupWindowOptions
{
    public TelegramApiClient? ApiClient { get; init; }
    /// <summary>UI language: "ru" or "en". Missing or unsupported values use Russian.</summary>
    public string Language { get; init; } = "ru";
    public string? InitialImportUrl { get; init; }
    public bool RestoreExistingSession { get; init; } = true;
    /// <summary>Keep the current account intact until a fresh pairing imports usable routes.</summary>
    public bool DeferSessionSaveUntilImport { get; init; }
    public SetupUrlImportHandler? ImportUrlAsync { get; init; }
    public SetupImportCommitHandler? CommitImportAsync { get; init; }
    /// <summary>Host-owned confirmation callback: title, message, and primary-button label.</summary>
    public Func<string, string, string, Task<bool>>? ConfirmAsync { get; init; }
}

public sealed record SetupSubscriptionPayload(
    string? SessionToken,
    TelegramAccount? Account,
    TelegramKeysSnapshot Subscription,
    string? SubscriptionUrl = null);

public sealed record SetupWindowResult(
    SetupSubscriptionPayload Payload,
    WindowsRoute SelectedRoute);

public sealed class SetupWindowCompletedEventArgs(SetupWindowResult result) : EventArgs
{
    public SetupWindowResult Result { get; } = result;
}

internal sealed class SetupImportCommitGate
{
    private int _inProgress;

    public bool TryEnter() => Interlocked.CompareExchange(ref _inProgress, 1, 0) == 0;

    public void Exit() => Volatile.Write(ref _inProgress, 0);
}

/// <summary>
/// Hostable Telegram sign-in and subscription import flow. The host owns committing
/// the returned profile into its active route/tunnel state through CommitImportAsync.
/// </summary>
public sealed class SetupWindow : UserControl
{
    private enum ViewState
    {
        Username,
        Code,
        Refreshing,
        RefreshError,
        NoSubscription,
        DeviceConflict,
        ImportUrl,
        ImportingUrl,
        Success,
    }

    private static readonly Regex ImportTokenPattern = new("^[A-Za-z0-9_-]{32,128}$", RegexOptions.CultureInvariant);

    private readonly SetupWindowOptions _options;
    private readonly TelegramApiClient _api;
    private readonly WindowsSupportClient _support = new();
    private readonly string _language;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly SetupImportCommitGate _importCommitGate = new();
    private Task? _activationTask;
    private readonly StackPanel _body = new() { Spacing = 15 };
    private readonly TextBlock _headline;
    private readonly TextBlock _intro;
    private readonly Border _statusPanel;
    private readonly TextBlock _statusText;
    private readonly Border[] _stepBars = new Border[3];

    private ViewState _state = ViewState.Username;
    private TextBox? _usernameInput;
    private TextBox? _codeInput;
    private TextBox? _urlInput;
    private Button? _primaryButton;
    private TextBlock? _primaryLabel;
    private string _username = "";
    private string _challenge = "";
    private string _botUrl = "";
    private string? _sessionToken;
    private TelegramAccount? _account;
    private TelegramKeysSnapshot? _subscription;
    private SetupSubscriptionPayload? _payload;
    private WindowsRoute? _selectedRoute;
    private string? _resolvedImportUrl;
    private bool _busy;
    private bool _transferRequestSent;
    private bool _completeAfterPairing;
    private bool _completed;
    private bool _backRequested;

    public SetupWindow(SetupWindowOptions? options = null)
    {
        _options = options ?? new SetupWindowOptions();
        _api = _options.ApiClient ?? new TelegramApiClient();
        _language = string.Equals(_options.Language, "en", StringComparison.OrdinalIgnoreCase) ? "en" : "ru";

        _headline = DeyttTheme.TextBlock(Copy("Подключите DEYTT", "Set up DEYTT"), 25, DeyttTheme.Text, FontWeight.Bold,
            DeyttTheme.Unbounded);
        _intro = DeyttTheme.TextBlock(
            Copy("Войдите через Telegram, чтобы загрузить подписку и маршруты.",
                "Sign in with Telegram to load your subscription and routes."),
            14, DeyttTheme.Muted);
        _statusText = DeyttTheme.TextBlock("", 13, DeyttTheme.Muted);
        _statusPanel = new Border
        {
            Padding = new Thickness(13, 11),
            CornerRadius = new CornerRadius(13),
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            Child = _statusText,
            IsVisible = false,
        };

        var header = BuildHeader();
        var steps = BuildSteps();
        var content = new StackPanel
        {
            Spacing = 20,
            MaxWidth = 620,
            Children =
            {
                header,
                steps,
                _statusPanel,
                _body,
            },
        };
        var card = DeyttTheme.Card(content, DeyttTheme.Surface, DeyttTheme.Line, 25,
            new Thickness(30, 28));
        card.MaxWidth = 680;
        card.HorizontalAlignment = HorizontalAlignment.Center;
        card.VerticalAlignment = VerticalAlignment.Center;
        card.Margin = new Thickness(22);
        card.BoxShadow = new BoxShadows(new BoxShadow
        {
            Blur = 55,
            Spread = 4,
            OffsetY = 12,
            Color = Color.Parse("#59000000"),
        });

        // MainWindow already scrolls PageHost. A nested scroll viewer here measures the
        // centered card against unbounded height and leaves it below the compact viewport.
        Content = card;

        KeyDown += (_, args) =>
        {
            if (args.Key != Key.Escape)
                return;
            Cancel();
            args.Handled = true;
        };

        Render(ViewState.Username);
    }

    /// <summary>Raised when the user asks the host to leave the setup flow.</summary>
    public event EventHandler? BackRequested;

    public event EventHandler<SetupWindowCompletedEventArgs>? Completed;

    /// <summary>The result becomes available after the import callback succeeds.</summary>
    public SetupWindowResult? Result { get; private set; }

    /// <summary>
    /// Starts initial-link processing or saved-session restoration once. Call on the UI thread
    /// whenever the host activates this view; repeated calls return the same task.
    /// </summary>
    public Task ActivateAsync()
    {
        if (!Dispatcher.UIThread.CheckAccess())
            throw new InvalidOperationException("SetupWindow must be activated on the UI thread.");
        if (_backRequested)
            throw new InvalidOperationException("A cancelled setup view cannot be activated again.");

        return _activationTask ??= ActivateCoreAsync();
    }

    /// <summary>Cancels in-flight setup work and asks the host to leave this view once.</summary>
    public void Cancel()
    {
        if (_backRequested || _completed)
            return;

        _backRequested = true;
        _lifetime.Cancel();
        BackRequested?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>
    /// Accepts a first-party subscription URL or an Android-compatible
    /// deytt.connect://import?url=... deep link.
    /// </summary>
    public static bool TryGetSubscriptionUrl(string? rawValue, out string subscriptionUrl)
    {
        subscriptionUrl = "";
        if (string.IsNullOrWhiteSpace(rawValue) || rawValue.Length > 4096)
            return false;

        var candidate = rawValue.Trim();
        if (Uri.TryCreate(candidate, UriKind.Absolute, out var deepLink) &&
            deepLink.Scheme.Equals("deytt.connect", StringComparison.OrdinalIgnoreCase))
        {
            if (!deepLink.Host.Equals("import", StringComparison.OrdinalIgnoreCase))
                return false;
            candidate = ReadDeepLinkParameter(deepLink.Query, "url") ??
                       ReadDeepLinkParameter(deepLink.Query, "subscription") ?? "";
        }

        if (!Uri.TryCreate(candidate, UriKind.Absolute, out var uri))
            return false;

        var host = uri.Host.TrimEnd('.');
        var allowedHost = host.Equals("deytt.space", StringComparison.OrdinalIgnoreCase) ||
                          host.EndsWith(".deytt.space", StringComparison.OrdinalIgnoreCase);
        var path = uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (!uri.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase) ||
            !allowedHost || uri.UserInfo.Length != 0 || uri.Port != 443 || uri.Fragment.Length != 0 ||
            path.Length != 3 || path[0] != "sub" || path[1] != "token" ||
            !ImportTokenPattern.IsMatch(path[2]))
            return false;

        subscriptionUrl = uri.AbsoluteUri;
        return true;
    }

    private async Task ActivateCoreAsync()
    {
        if (_options.InitialImportUrl is { Length: > 0 } initialUrl)
        {
            if (!TryGetSubscriptionUrl(initialUrl, out var parsedUrl))
            {
                Render(ViewState.ImportUrl, Copy("Ссылка подписки недействительна. Проверь её и попробуй снова.",
                    "The subscription link is invalid. Check it and try again."), DeyttTheme.Coral);
                return;
            }
            _resolvedImportUrl = parsedUrl;
            if (_options.ImportUrlAsync is null)
            {
                Render(ViewState.ImportUrl,
                    Copy("Ссылка распознана. Для импорта хосту нужно передать обработчик ImportUrlAsync.",
                        "Link recognized. The host must provide ImportUrlAsync to import it."), DeyttTheme.Amber);
                _urlInput!.Text = parsedUrl;
                return;
            }
            await ImportUrlAsync(parsedUrl);
            return;
        }

        if (_options.RestoreExistingSession && OperatingSystem.IsWindows())
            await RestoreSessionAsync();
        else
            FocusLater(_usernameInput);
    }

    private async Task RestoreSessionAsync()
    {
        string? token;
        try
        {
            token = WindowsSessionStore.Load();
        }
        catch (Exception error) when (error is CryptographicException or IOException or UnauthorizedAccessException)
        {
            Render(ViewState.Username, Copy("Не удалось прочитать сохранённый сеанс. Войди через Telegram ещё раз.",
                "Could not read the saved session. Sign in with Telegram again."), DeyttTheme.Amber);
            return;
        }

        if (token is null)
            return;

        _sessionToken = token;
        await RefreshSubscriptionAsync(token);
    }

    private async Task StartPairingAsync()
    {
        if (_busy)
            return;
        _completeAfterPairing = false;
        _username = _usernameInput?.Text?.Trim() ?? _username;
        if (_username.Length == 0)
        {
            SetStatus(Copy("Введи Telegram username, например @username.",
                "Enter your Telegram username, for example @username."), DeyttTheme.Amber);
            _usernameInput?.Focus();
            return;
        }

        SetBusy(true);
        Render(ViewState.Refreshing, Copy("Создаём защищённый запрос на вход…", "Starting secure sign-in…"), DeyttTheme.Blue);
        try
        {
            var pairing = await _api.StartPairingAsync(_username, _lifetime.Token);
            _challenge = pairing.Challenge;
            _botUrl = pairing.BotUrl;
            _username = _username.Trim().TrimStart('@');
            var needsBotStart = pairing.Delivery != "sent";
            Render(ViewState.Code,
                pairing.Delivery switch
                {
                    "sent" => Copy("Код отправлен в Telegram. Введи его ниже.", "The code was sent in Telegram. Enter it below."),
                    "delivery_failed" => Copy("Не удалось доставить код. Открой бота и запроси код ещё раз.",
                        "The code could not be delivered. Open the bot and request another code."),
                    _ => Copy("Открой бота, чтобы начать чат, затем введи код из сообщения.",
                        "Open the bot to start a chat, then enter the code from its message."),
                },
                pairing.Delivery == "sent" ? DeyttTheme.Blue : DeyttTheme.Amber,
                showBotButton: needsBotStart);
            FocusLater(_codeInput);
        }
        catch (TelegramApiException error)
        {
            if (IsDeviceSlotConflict(error))
                Render(ViewState.DeviceConflict, Copy(
                    "Для аккаунта достигнут лимит приложений. Удали старый сеанс DEYTT в Telegram и затем попробуй снова.",
                    "This account has reached its app limit. Remove an old DEYTT session in Telegram, then try again."),
                    DeyttTheme.Amber);
            else
                Render(ViewState.Username, PairingError(error), ErrorColor(error));
        }
        catch (HttpRequestException)
        {
            Render(ViewState.Username, Copy("Не удалось связаться с сервером. Проверь интернет и повтори.",
                "Could not reach the server. Check your connection and retry."), DeyttTheme.Coral);
        }
        catch (OperationCanceledException) when (!_lifetime.IsCancellationRequested)
        {
            Render(ViewState.Username, Copy("Запрос занял слишком много времени. Повтори попытку.",
                "The request timed out. Please try again."), DeyttTheme.Coral);
        }
        catch (Exception error) when (_lifetime.IsCancellationRequested && IsNonFatal(error))
        {
            // The host has left the setup view, so there is no UI left to update.
        }
        catch (Exception error) when (IsNonFatal(error))
        {
            Render(ViewState.Username, Copy("Не удалось обработать ответ сервера. Попробуй ещё раз.",
                "Could not process the server response. Please try again."), DeyttTheme.Coral);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async Task VerifyPairingAsync()
    {
        if (_busy)
            return;
        var code = _codeInput?.Text?.Trim() ?? "";
        if (code.Length != 6 || code.Any(character => character is < '0' or > '9'))
        {
            SetStatus(Copy("Введи шестизначный код из Telegram.", "Enter the six-digit code from Telegram."), DeyttTheme.Amber);
            _codeInput?.Focus();
            return;
        }

        SetBusy(true);
        Render(ViewState.Refreshing, Copy("Проверяем код…", "Verifying the code…"), DeyttTheme.Blue);
        var pairingStage = "verify";
        try
        {
            var paired = await _api.VerifyPairingAsync(_challenge, code, _lifetime.Token);
            pairingStage = "save_session";
            try
            {
                if (!_options.DeferSessionSaveUntilImport)
                    WindowsSessionStore.Save(paired.Token);
            }
            catch (Exception error) when (error is CryptographicException or IOException or UnauthorizedAccessException)
            {
                Render(ViewState.Code, Copy("Не удалось безопасно сохранить сеанс Windows. Повтори подтверждение.",
                    "Could not securely save the Windows session. Please confirm again."), DeyttTheme.Coral);
                return;
            }

            _sessionToken = paired.Token;
            _account = paired.Account;
            _completeAfterPairing = true;
            pairingStage = "load_subscription";
            await RefreshSubscriptionAsync(paired.Token, paired.Account, completeAfterVerification: true);
        }
        catch (TelegramApiException error)
        {
            if (IsDeviceSlotConflict(error))
            {
                Render(ViewState.DeviceConflict, Copy(
                    "Для аккаунта достигнут лимит приложений. Удали старый сеанс DEYTT в Telegram и затем попробуй снова.",
                    "This account has reached its app limit. Remove an old DEYTT session in Telegram, then try again."),
                    DeyttTheme.Amber);
            }
            else
            {
                Render(ViewState.Code, PairingError(error), ErrorColor(error), showBotButton: _botUrl.Length > 0);
            }
        }
        catch (HttpRequestException)
        {
            Render(ViewState.Code, Copy("Не удалось связаться с сервером. Проверь интернет и повтори.",
                "Could not reach the server. Check your connection and retry."), DeyttTheme.Coral,
                showBotButton: _botUrl.Length > 0);
        }
        catch (OperationCanceledException) when (!_lifetime.IsCancellationRequested)
        {
            Render(ViewState.Code, Copy("Запрос занял слишком много времени. Повтори попытку.",
                "The request timed out. Please try again."), DeyttTheme.Coral,
                showBotButton: _botUrl.Length > 0);
        }
        catch (Exception error) when (_lifetime.IsCancellationRequested && IsNonFatal(error))
        {
            // The host has left the setup view, so there is no UI left to update.
        }
        catch (Exception error) when (IsNonFatal(error))
        {
            var diagnostic = $"{pairingStage}/{error.GetType().Name}/{DiagnosticOrigin(error)}/0x{error.HResult:X8}";
            System.Diagnostics.Trace.TraceError("Telegram pairing flow failed ({0}).", diagnostic);
            Render(ViewState.Code, Copy(
                $"Не удалось завершить вход. Диагностика: {diagnostic}. Это не подтверждает, что код неверный. Запроси новый код.",
                $"Could not finish sign-in. Diagnostic: {diagnostic}. This does not mean the code is wrong. Request a new code."), DeyttTheme.Coral,
                showBotButton: _botUrl.Length > 0);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async Task RefreshSubscriptionAsync(string token, TelegramAccount? verifiedAccount = null,
        bool completeAfterVerification = false)
    {
        SetBusy(true);
        Render(ViewState.Refreshing,
            Copy("Код подтверждён. Загружаю подписку и маршруты…", "Code verified. Loading your subscription and routes…"),
            DeyttTheme.Blue);
        try
        {
            var progress = new Progress<string>(stage =>
            {
                if (_lifetime.IsCancellationRequested || !IsVisible)
                    return;
                var message = stage switch
                {
                    "metadata" => Copy("Получаю данные подписки…", "Fetching subscription details…"),
                    "profile" => Copy("Загружаю маршруты…", "Loading routes…"),
                    "optional" => Copy("Проверяю дополнительные маршруты…", "Checking optional routes…"),
                    "ready" => Copy("Маршруты готовы. Завершаю вход…", "Routes are ready. Finishing sign-in…"),
                    _ => Copy("Загружаю подписку…", "Loading your subscription…"),
                };
                SetStatus(message, DeyttTheme.Blue);
            });
            var accountTask = verifiedAccount is null
                ? _api.GetAccountAsync(token, _lifetime.Token)
                : Task.FromResult(verifiedAccount);
            var subscriptionTask = _api.GetSubscriptionAsync(token, _lifetime.Token, progress);
            try
            {
                await Task.WhenAll(accountTask, subscriptionTask);
            }
            finally
            {
                // Keep the verified account identity even when the separate
                // subscription request fails, so the error state identifies
                // which Telegram account needs attention.
                if (accountTask.IsCompletedSuccessfully)
                    _account = accountTask.Result;
            }
            _subscription = await subscriptionTask;
            if (!_subscription.HappAvailable || _subscription.Routes.Count == 0)
            {
                Render(ViewState.NoSubscription, _options.DeferSessionSaveUntilImport
                    ? Copy("Код подтверждён, но активная подписка не найдена. Текущий аккаунт не изменён.",
                        "The code was verified, but no active subscription was found. Your current account is unchanged.")
                    : Copy("Аккаунт подключён, но активная подписка не найдена. Можно обновить данные или импортировать ссылку подписки.",
                        "The account is connected, but no active subscription was found. Refresh or import a subscription link."),
                    DeyttTheme.Amber);
                return;
            }

            _payload = new SetupSubscriptionPayload(token, _account, _subscription);
            _selectedRoute = ChooseInitialRoute(_subscription.Routes);
            if (completeAfterVerification)
            {
                await CompleteImportAsync(automatic: true);
            }
            else
            {
                Render(ViewState.Success,
                    Copy($"Подписка загружена: {_subscription.Routes.Count} маршрутов готовы к импорту.",
                        $"Subscription loaded: {_subscription.Routes.Count} routes are ready to import."), DeyttTheme.Mint);
            }
        }
        catch (TelegramApiException error) when (IsDeviceSlotConflict(error))
        {
            Render(ViewState.DeviceConflict, Copy(
                "Слот компьютера уже занят другим компьютером. На аккаунт доступно по одному телефону и одному компьютеру; слот телефона и квота Happ отдельные. Если нужно заменить компьютер, обратись в поддержку.",
                "The computer slot is already used by another computer. Each account has one phone slot and one computer slot; the phone slot and Happ allowance are separate. Contact support if you need to replace the computer."),
                DeyttTheme.Amber);
        }
        catch (TelegramApiException error) when (error.Code == "device_blocked")
        {
            Render(ViewState.RefreshError, Copy(
                "Сервер пометил это устройство как заблокированное. Обратись в поддержку DEYTT и попроси проверить доступ.",
                "The server marked this device as blocked. Contact DEYTT support to check its access."),
                DeyttTheme.Amber);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            _sessionToken = null;
            if (!_options.DeferSessionSaveUntilImport)
            {
                try { WindowsSessionStore.Clear(); }
                catch (Exception clearError) when (clearError is IOException or UnauthorizedAccessException) { }
            }
            Render(ViewState.Username, Copy("Сеанс Telegram истёк. Войди ещё раз, чтобы обновить подписку.",
                "Your Telegram session expired. Sign in again to refresh the subscription."), DeyttTheme.Amber);
        }
        catch (OperationCanceledException) when (!_lifetime.IsCancellationRequested)
        {
            Render(ViewState.RefreshError, _options.DeferSessionSaveUntilImport
                ? Copy("Обновление заняло слишком много времени. Текущий аккаунт не изменён; повтори попытку.",
                    "The refresh took too long. Your current account is unchanged; please retry.")
                : Copy("Обновление заняло слишком много времени. Сеанс сохранён; попробуй ещё раз позже.",
                    "The refresh took too long. Your session is saved; please retry later."),
                DeyttTheme.Coral);
        }
        catch (TelegramApiException error) when (error.Code is
                   "subscription_invalid_routes_outbounds_empty" or
                   "subscription_invalid_routes_outbounds_no_network")
        {
            var activeSubscription = _account?.Subscription is { Active: true };
            var profileError = activeSubscription
                ? Copy("Подписка активна, но серверный профиль не содержит подключаемых маршрутов.",
                    "The subscription is active, but the server profile has no connectable routes.")
                : Copy("Серверный профиль не содержит подключаемых маршрутов.",
                    "The server profile has no connectable routes.");
            var accountState = _options.DeferSessionSaveUntilImport
                ? Copy("Текущий аккаунт не изменён; проверь выдачу маршрутов.",
                    "Your current account is unchanged; check route provisioning.")
                : Copy("Сеанс сохранён; проверь выдачу маршрутов.",
                    "Your session is saved; check route provisioning.");
            Render(ViewState.RefreshError, $"{profileError} {accountState}", DeyttTheme.Amber);
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or IOException)
        {
            var diagnostic = SubscriptionRefreshDiagnostic(error);
            Trace.TraceError("Telegram subscription refresh failed ({0}).", diagnostic);
            Render(ViewState.RefreshError, _options.DeferSessionSaveUntilImport
                ? Copy($"Не удалось загрузить подписку. Текущий аккаунт не изменён; проверь интернет и повтори. Код: {diagnostic}.",
                    $"Could not load the subscription. Your current account is unchanged; check your connection and retry. Code: {diagnostic}.")
                : Copy($"Не удалось обновить подписку. Проверь интернет и повтори; сохранённый сеанс останется на устройстве. Код: {diagnostic}.",
                    $"Could not refresh the subscription. Check your connection and retry; the saved session will stay on this device. Code: {diagnostic}."),
                DeyttTheme.Coral);
        }
        catch (Exception error) when (_lifetime.IsCancellationRequested && IsNonFatal(error))
        {
            // Ignore failures raised while the host is leaving this view.
        }
        catch (Exception error) when (IsNonFatal(error))
        {
            Render(ViewState.RefreshError, _options.DeferSessionSaveUntilImport
                ? Copy("Не удалось обработать данные подписки. Текущий аккаунт не изменён; повтори попытку.",
                    "Could not process the subscription. Your current account is unchanged; please retry.")
                : Copy("Не удалось обработать данные подписки. Сеанс сохранён; повтори обновление позже.",
                    "Could not process the subscription data. Your session is saved; retry the refresh later."),
                DeyttTheme.Coral);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private static string SubscriptionRefreshDiagnostic(Exception error) => error switch
    {
        TelegramApiException api => $"API/{(int)api.StatusCode}/{SafeSubscriptionApiCode(api.Code)}",
        HttpRequestException network => $"NET/{network.HttpRequestError}",
        IOException => "IO",
        _ => "UNKNOWN",
    };

    private static string SafeSubscriptionApiCode(string code) => code switch
    {
        "invalid_response" or "subscription_unavailable" or "subscription_invalid" or
            "subscription_invalid_json" or "subscription_invalid_utf8" or "subscription_invalid_normalize" or
            "subscription_invalid_awg" or "subscription_invalid_routes" or
            "subscription_invalid_routes_tun" or "subscription_invalid_routes_outbounds" or
            "subscription_invalid_routes_outbounds_empty" or "subscription_invalid_routes_outbounds_no_network" or
            "subscription_invalid_routes_root" or "subscription_invalid_routes_inbounds" or
            "subscription_invalid_routes_inbounds_empty" or "subscription_invalid_routes_inbounds_proxy" or
            "subscription_invalid_routes_tun_address_missing" or
            "subscription_invalid_routes_tun_address_shape" or
            "subscription_invalid_routes_tags" or "subscription_invalid_routes_automatic" or
            "subscription_invalid_routes_auto_chain" or "subscription_invalid_routes_auto" or
            "subscription_invalid_routes_chain" or
            "subscription_invalid_routes_required" or "subscription_invalid_routes_policy" or
            "response_too_large" or "request_failed" or "session_invalid" => code,
        _ => "server_rejected",
    };

    private async Task ImportUrlAsync(string? candidate)
    {
        if (_busy)
            return;
        if (_options.ImportUrlAsync is null)
        {
            Render(ViewState.ImportUrl, Copy("Импорт ссылки не подключён в этом хосте.",
                "Subscription link import is not connected in this host."), DeyttTheme.Amber);
            return;
        }
        if (!TryGetSubscriptionUrl(candidate, out var url))
        {
            Render(ViewState.ImportUrl, Copy(
                "Поддерживается ссылка вида https://deytt.space/sub/token/… или ссылка DEYTT из Android.",
                "Use a link like https://deytt.space/sub/token/… or a DEYTT link from Android."),
                DeyttTheme.Coral);
            return;
        }

        _resolvedImportUrl = url;
        SetBusy(true);
        Render(ViewState.ImportingUrl, Copy("Подготавливаем импорт подписки…", "Preparing the subscription import…"), DeyttTheme.Blue);
        var progress = new Progress<string>(message =>
        {
            if (!_lifetime.IsCancellationRequested && IsVisible)
                SetStatus(LocalizeImportProgress(message), DeyttTheme.Blue);
        });
        try
        {
            _payload = await _options.ImportUrlAsync(url, progress, _lifetime.Token);
            if (!_payload.Subscription.HappAvailable || _payload.Subscription.Routes.Count == 0)
            {
                Render(ViewState.ImportUrl, Copy("По этой ссылке не найдено ни одного маршрута.",
                    "No routes were found at this link."), DeyttTheme.Coral);
                return;
            }
            _sessionToken = _payload.SessionToken;
            _account = _payload.Account;
            _subscription = _payload.Subscription;
            _selectedRoute = ChooseInitialRoute(_subscription.Routes);
            _payload = _payload with { SubscriptionUrl = url };
            Render(ViewState.Success,
                Copy($"По ссылке загружено {_subscription.Routes.Count} маршрутов.",
                    $"Loaded {_subscription.Routes.Count} routes from the link."), DeyttTheme.Mint);
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException or InvalidDataException)
        {
            Render(ViewState.ImportUrl, Copy("Не удалось импортировать подписку. Проверь ссылку и сеть, затем повтори.",
                "Could not import the subscription. Check the link and your connection, then retry."), DeyttTheme.Coral);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async Task CompleteImportAsync(bool automatic = false)
    {
        if ((_busy && !automatic) || _completed || _payload is null || _selectedRoute is null ||
            !_importCommitGate.TryEnter())
            return;
        var result = new SetupWindowResult(_payload, _selectedRoute);
        if (automatic)
            Render(ViewState.Refreshing,
                Copy("Сохраняем подписку и открываем главную…", "Saving your subscription and opening Home…"),
                DeyttTheme.Blue);
        SetBusy(true);
        SetStatus(Copy("Сохраняем подписку и выбранный маршрут…", "Saving the subscription and selected route…"), DeyttTheme.Blue);
        try
        {
            if (_options.CommitImportAsync is not null)
                await _options.CommitImportAsync(result, _lifetime.Token);
            _completed = true;
            Result = result;
            Completed?.Invoke(this, new SetupWindowCompletedEventArgs(result));
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error) when (IsNonFatal(error))
        {
            var diagnostic = $"{error.GetType().Name}/{DiagnosticOrigin(error.InnerException ?? error)}/0x{error.HResult:X8}";
            System.Diagnostics.Trace.TraceError("Subscription import commit failed ({0}).", diagnostic);
            var message = Copy($"Не удалось сохранить подписку в приложении. Диагностика: {diagnostic}. Повтори импорт.",
                $"The app could not save the subscription. Diagnostic: {diagnostic}. Retry the import.");
            if (automatic)
                Render(ViewState.Success, message, DeyttTheme.Coral);
            else
                SetStatus(message, DeyttTheme.Coral);
        }
        finally
        {
            _importCommitGate.Exit();
            SetBusy(false);
        }
    }

    private void Render(
        ViewState state,
        string? status = null,
        Color? statusColor = null,
        bool showBotButton = false)
    {
        _state = state;
        if (state == ViewState.Username)
            _completeAfterPairing = false;
        _busy = state is ViewState.Refreshing or ViewState.ImportingUrl;
        _body.Children.Clear();
        _usernameInput = null;
        _codeInput = null;
        _urlInput = null;
        _primaryButton = null;
        _primaryLabel = null;

        var step = state switch
        {
            ViewState.Username or ViewState.Code or ViewState.DeviceConflict => 0,
            ViewState.Refreshing or ViewState.RefreshError or ViewState.NoSubscription or ViewState.ImportUrl or ViewState.ImportingUrl => 1,
            ViewState.Success => 2,
            _ => 0,
        };
        UpdateSteps(step);
        SetHeader(state);
        if (status is not null)
            SetStatus(status, statusColor ?? DeyttTheme.Muted);
        else if (state is not ViewState.Refreshing and not ViewState.ImportingUrl)
            SetStatus("", DeyttTheme.Muted);

        switch (state)
        {
            case ViewState.Username:
                BuildUsernameView();
                break;
            case ViewState.Code:
                BuildCodeView(showBotButton);
                break;
            case ViewState.Refreshing:
            case ViewState.ImportingUrl:
                BuildLoadingView(state == ViewState.ImportingUrl);
                break;
            case ViewState.RefreshError:
                BuildRefreshErrorView();
                break;
            case ViewState.NoSubscription:
                BuildNoSubscriptionView();
                break;
            case ViewState.DeviceConflict:
                BuildDeviceConflictView();
                break;
            case ViewState.ImportUrl:
                BuildImportUrlView();
                break;
            case ViewState.Success:
                BuildSuccessView();
                break;
        }

        SetBusy(_busy);
    }

    private void BuildUsernameView()
    {
        _body.Children.Add(DeyttTheme.SectionLabel("01 / TELEGRAM"));
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            "Укажи username аккаунта, в котором оформлена подписка.",
            "Enter the username of the account that has the subscription."), 15, DeyttTheme.Text));

        _usernameInput = CreateInput("@username", 44);
        _usernameInput.MaxLength = 33;
        _usernameInput.Text = _username;
        _usernameInput.KeyDown += (_, args) =>
        {
            if (args.Key == Key.Enter)
                _ = StartPairingAsync();
        };
        _body.Children.Add(_usernameInput);

        _body.Children.Add(BuildPrimary(Copy("Получить код", "Get code"), () => _ = StartPairingAsync()));
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            "Бот пришлёт одноразовый код в Telegram. Оплата для подключения приложения не требуется.",
            "The bot will send a one-time code in Telegram. No payment is needed to link this app."),
            12, DeyttTheme.Muted));
        if (_options.ImportUrlAsync is not null)
            _body.Children.Add(BuildTextButton(Copy("У меня есть ссылка подписки", "I have a subscription link"),
                () => Render(ViewState.ImportUrl)));
    }

    private void BuildCodeView(bool showBotButton)
    {
        _body.Children.Add(DeyttTheme.SectionLabel(Copy("01 / ПОДТВЕРЖДЕНИЕ", "01 / VERIFICATION")));
        _body.Children.Add(DeyttTheme.TextBlock(Copy($"Код для @{_username.TrimStart('@')}",
            $"Code for @{_username.TrimStart('@')}"), 15, DeyttTheme.Text,
            FontWeight.SemiBold));
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            "Введи шестизначный код из Telegram. Если чат с ботом ещё не открыт, открой его один раз.",
            "Enter the six-digit code from Telegram. If you have not started the bot chat, open it once."),
            13, DeyttTheme.Muted));

        _codeInput = CreateInput("123456", 52);
        _codeInput.MaxLength = 6;
        _codeInput.FontFamily = DeyttTheme.JetBrainsMono;
        _codeInput.FontSize = 22;
        _codeInput.HorizontalContentAlignment = HorizontalAlignment.Center;
        _codeInput.KeyDown += (_, args) =>
        {
            if (args.Key == Key.Enter)
                _ = VerifyPairingAsync();
        };
        _body.Children.Add(_codeInput);
        if (showBotButton)
            _body.Children.Add(BuildTextButton(Copy("Открыть бота в Telegram", "Open the Telegram bot"), OpenBot));

        _body.Children.Add(BuildPrimary(Copy("Подтвердить код", "Verify code"), () => _ = VerifyPairingAsync()));
        _body.Children.Add(BuildTextButton(Copy("Запросить новый код", "Request a new code"), () => _ = StartPairingAsync()));
    }

    private void BuildLoadingView(bool isUrlImport)
    {
        _body.Children.Add(DeyttTheme.SectionLabel(isUrlImport
            ? Copy("02 / ИМПОРТ", "02 / IMPORT")
            : Copy("02 / ПОДПИСКА", "02 / SUBSCRIPTION")));
        _body.Children.Add(new ProgressBar
        {
            IsIndeterminate = true,
            Height = 4,
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            Foreground = DeyttTheme.Brush(DeyttTheme.Mint),
        });
        _body.Children.Add(DeyttTheme.TextBlock(isUrlImport
            ? Copy("Проверяем ссылку и загружаем маршруты…", "Checking the link and loading routes…")
            : Copy("Обновляем аккаунт, профиль и список маршрутов…", "Refreshing your account, profile, and routes…"),
            15, DeyttTheme.Text, FontWeight.SemiBold));
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            "Загрузка может занять несколько секунд. Не закрывай приложение.",
            "This may take a few seconds. Keep the app open."), 13, DeyttTheme.Muted));
    }

    private void BuildRefreshErrorView()
    {
        _body.Children.Add(DeyttTheme.SectionLabel(Copy("02 / ОБНОВЛЕНИЕ", "02 / REFRESH")));
        _body.Children.Add(DeyttTheme.TextBlock(_options.DeferSessionSaveUntilImport
            ? Copy("Текущий аккаунт пока не изменён.", "Your current account is unchanged.")
            : Copy("Сеанс сохранён на этом устройстве.", "Your session is saved on this device."), 15,
            DeyttTheme.Text, FontWeight.SemiBold));
        var accountUsername = _account?.Username;
        if (!string.IsNullOrWhiteSpace(accountUsername))
        {
            var username = accountUsername.Trim().TrimStart('@');
            if (username.Length > 0)
                _body.Children.Add(DeyttTheme.TextBlock($"Telegram: @{username}", 13, DeyttTheme.Muted));
        }
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            "Проверь соединение и доступность профиля. Повторный запрос не удалит сохранённый сеанс.",
            "Check your connection and profile availability. Retrying will keep the saved session."),
            13, DeyttTheme.Muted));
        _body.Children.Add(BuildPrimary(Copy("Повторить", "Retry"), () =>
        {
            if (_sessionToken is { } token)
                _ = RefreshSubscriptionAsync(token, completeAfterVerification: _completeAfterPairing);
        }));
        _body.Children.Add(BuildTextButton(Copy("Войти в другой аккаунт", "Sign in to another account"), () =>
        {
            _sessionToken = null;
            Render(ViewState.Username);
        }));
    }

    private void BuildNoSubscriptionView()
    {
        _body.Children.Add(DeyttTheme.SectionLabel(Copy("02 / ПОДПИСКА", "02 / SUBSCRIPTION")));
        var accountName = _account?.FirstName is { Length: > 0 } name ? name : _account?.Username;
        _body.Children.Add(DeyttTheme.TextBlock(_options.DeferSessionSaveUntilImport
            ? Copy("Код подтверждён. Текущий аккаунт пока не изменён.",
                "The code is verified. Your current account is unchanged.")
            : Copy(accountName is { Length: > 0 } ? $"Аккаунт {accountName} подключён." : "Аккаунт подключён.",
                accountName is { Length: > 0 } ? $"Account {accountName} is connected." : "Account is connected."),
            15, DeyttTheme.Text, FontWeight.SemiBold));
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            "Активная подписка не найдена. Обнови аккаунт или импортируй ссылку подписки, если она у тебя есть.",
            "No active subscription was found. Refresh your account or import a subscription link."),
            13, DeyttTheme.Muted));
        _body.Children.Add(BuildPrimary(Copy("Обновить подписку", "Refresh subscription"), () =>
        {
            if (_sessionToken is { } token)
                _ = RefreshSubscriptionAsync(token, completeAfterVerification: _completeAfterPairing);
        }));
        if (_options.ImportUrlAsync is not null)
            _body.Children.Add(BuildTextButton(Copy("Импортировать ссылку", "Import a link"), () => Render(ViewState.ImportUrl)));
        _body.Children.Add(BuildTextButton(Copy("Сменить Telegram-аккаунт", "Switch Telegram account"), () =>
        {
            _sessionToken = null;
            Render(ViewState.Username);
        }));
    }

    private void BuildDeviceConflictView()
    {
        _body.Children.Add(DeyttTheme.SectionLabel(Copy("01 / СЛОТ КОМПЬЮТЕРА", "01 / COMPUTER SLOT")));
        _body.Children.Add(DeyttTheme.TextBlock(Copy("Слот компьютера занят.",
            "The computer slot is in use."), 15,
            DeyttTheme.Text, FontWeight.SemiBold));
        if (_sessionToken is { Length: > 0 })
        {
            _body.Children.Add(DeyttTheme.TextBlock(Copy(
                "Выйди из deytt./connect на прежнем компьютере и повтори проверку. Слоты телефона и Happ отдельные. Если прежний компьютер недоступен, попроси поддержку освободить слот. Приложение не отключает устройства автоматически.",
                "Sign out of deytt./connect on the previous computer, then retry the check. Phone and Happ slots are separate. If the previous computer is unavailable, ask support to free its slot. The app does not disconnect devices automatically."),
                13, DeyttTheme.Muted));
            _body.Children.Add(BuildPrimary(Copy("Повторить проверку", "Retry the check"), () =>
            {
                if (_sessionToken is { } token)
                    _ = RefreshSubscriptionAsync(token, completeAfterVerification: _completeAfterPairing);
            }));
            _body.Children.Add(BuildTextButton(
                _transferRequestSent
                    ? Copy("Обращение отправлено", "Support request sent")
                    : Copy("Обратиться в поддержку", "Contact support"),
                () =>
                {
                    if (!_transferRequestSent)
                        _ = RequestDeviceTransferAsync();
                }));
        }
        else
        {
            _body.Children.Add(DeyttTheme.TextBlock(Copy(
                "Сначала подключи аккаунт Telegram. Если прежний компьютер недоступен, войди в аккаунт и обратись в поддержку, чтобы освободить компьютерный слот.",
                "Sign in to Telegram first. If the previous computer is unavailable, contact support to free the computer slot."),
                13, DeyttTheme.Muted));
            _body.Children.Add(BuildPrimary(Copy("Попробовать снова", "Try again"), () => Render(ViewState.Username)));
        }
        _body.Children.Add(BuildTextButton(Copy("Назад к коду", "Back to code"), () =>
        {
            if (!_busy)
                Render(ViewState.Code);
        }));
    }

    private async Task RequestDeviceTransferAsync()
    {
        if (_busy)
            return;

        var token = _sessionToken;
        if (string.IsNullOrWhiteSpace(token))
        {
            Render(ViewState.Username, Copy(
                "Подключи аккаунт Telegram, чтобы отправить обращение в поддержку.",
                "Sign in to Telegram before sending a support request."), DeyttTheme.Amber);
            return;
        }

        if (!await ConfirmDeviceTransferAsync() || _sessionToken != token || _state != ViewState.DeviceConflict)
            return;

        SetBusy(true);
        SetStatus(Copy("Отправляем запрос в поддержку…", "Sending the support request…"), DeyttTheme.Blue);
        const string message = "здравствуйте! на новом компьютере deytt./connect отвечает http 409 app_device_limit_reached (download/http_409). не получается освободить слот прежнего компьютера самостоятельно. прошу проверить и освободить только компьютерный слот; слот телефона и happ менять не нужно.";
        try
        {
            var thread = await _support.GetThreadAsync(token, _lifetime.Token);
            if (_sessionToken != token || _state != ViewState.DeviceConflict)
                return;

            if (thread.Ticket is { IsOpen: true } ticket)
                await _support.SendMessageAsync(token, ticket.Id, message, _lifetime.Token);
            else
                await _support.CreateTicketAsync(token, message, _lifetime.Token);

            _transferRequestSent = true;
            if (_state == ViewState.DeviceConflict)
                Render(ViewState.DeviceConflict, Copy(
                    "Запрос отправлен. После освобождения слота нажми «Повторить проверку».",
                    "Request sent. After the slot is freed, select “Retry the check”."), DeyttTheme.Mint);
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error) when (error is WindowsSupportException or HttpRequestException or TaskCanceledException or IOException)
        {
            if (_state == ViewState.DeviceConflict)
                Render(ViewState.DeviceConflict, Copy(
                    "Не удалось отправить обращение. Проверь интернет и повтори попытку.",
                    "Could not send the request. Check your connection and try again."), DeyttTheme.Coral);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async Task<bool> ConfirmDeviceTransferAsync()
    {
        var title = Copy("Запрос в поддержку", "Contact support");
        var confirm = _options.ConfirmAsync;
        if (confirm is null)
        {
            SetStatus(Copy("Подтверждение недоступно. Вернись и попробуй ещё раз позже.",
                "Confirmation is unavailable. Return and try again later."), DeyttTheme.Amber);
            return false;
        }

        try
        {
            return await confirm(
                title,
                Copy("Отправить обращение, чтобы освободить занятый слот компьютера? Слот телефона и Happ не изменится.",
                    "Send a request to free the occupied computer slot? Phone and Happ slots will not change."),
                Copy("Отправить обращение", "Send request"));
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
            return false;
        }
        catch (Exception error) when (IsNonFatal(error))
        {
            SetStatus(Copy("Не удалось открыть подтверждение. Попробуй ещё раз.",
                "Could not open the confirmation. Please try again."), DeyttTheme.Coral);
            return false;
        }
    }

    private void BuildImportUrlView()
    {
        _body.Children.Add(DeyttTheme.SectionLabel(Copy("01 / ССЫЛКА ПОДПИСКИ", "01 / SUBSCRIPTION LINK")));
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            "Вставь ссылку DEYTT. Она должна начинаться с https://deytt.space/sub/token/.",
            "Paste a DEYTT link. It must start with https://deytt.space/sub/token/."),
            14, DeyttTheme.Text));
        _urlInput = CreateInput("https://deytt.space/sub/token/…", 48);
        _urlInput.MaxLength = 4096;
        _urlInput.Text = _resolvedImportUrl;
        _urlInput.KeyDown += (_, args) =>
        {
            if (args.Key == Key.Enter)
                _ = ImportUrlAsync(_urlInput.Text);
        };
        _body.Children.Add(_urlInput);
        _body.Children.Add(BuildPrimary(Copy("Загрузить маршруты", "Load routes"), () => _ = ImportUrlAsync(_urlInput.Text)));
        _body.Children.Add(BuildTextButton(Copy("Вернуться ко входу через Telegram", "Back to Telegram sign-in"),
            () => Render(ViewState.Username)));
    }

    private void BuildSuccessView()
    {
        if (_payload is null || _subscription is null || _selectedRoute is null)
            return;

        _body.Children.Add(DeyttTheme.SectionLabel(Copy("03 / МАРШРУТ", "03 / ROUTE")));
        var accountName = _payload.Account?.FirstName is { Length: > 0 } name
            ? name
            : _payload.Account?.Username;
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            accountName is { Length: > 0 } ? $"Готово, {accountName}." : "Подписка готова.",
            accountName is { Length: > 0 } ? $"You're all set, {accountName}." : "Your subscription is ready."),
            18, DeyttTheme.Text, FontWeight.SemiBold));
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            "Выбери первый маршрут. Его и профиль подписки приложение получит при продолжении.",
            "Choose your first route. The app will import it and your subscription profile when you continue."),
            13, DeyttTheme.Muted));

        var routeList = new StackPanel { Spacing = 8 };
        foreach (var route in _subscription.Routes)
            routeList.Children.Add(BuildRouteChoice(route));
        _body.Children.Add(new ScrollViewer
        {
            MaxHeight = 265,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
            Content = routeList,
        });
        _body.Children.Add(BuildPrimary(Copy("Импортировать подписку", "Import subscription"), () => _ = CompleteImportAsync()));
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            "Выбранный маршрут можно изменить позже в разделе маршрутов.",
            "You can change the selected route later in Routes."),
            11, DeyttTheme.Muted));
    }

    private Button BuildRouteChoice(WindowsRoute route)
    {
        var isSelected = _selectedRoute?.Id == route.Id;
        var details = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
        details.Children.Add(DeyttTheme.TextBlock($"{route.Flag}  {GetRouteTitle(route)}", 14,
            DeyttTheme.Text, FontWeight.SemiBold));
        details.Children.Add(DeyttTheme.TextBlock(
            route.Protocol == "AUTO" ? GetRouteProtocol(route) : $"{GetRouteProtocol(route)}  ·  {route.CountryCode}",
            11, DeyttTheme.Muted));

        var row = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            ColumnSpacing = 15,
        };
        Grid.SetColumn(details, 0);
        row.Children.Add(details);
        var marker = new Border
        {
            Width = 20,
            Height = 20,
            CornerRadius = new CornerRadius(10),
            BorderThickness = new Thickness(isSelected ? 6 : 1),
            BorderBrush = DeyttTheme.Brush(isSelected ? DeyttTheme.Mint : DeyttTheme.Line),
            Background = DeyttTheme.Brush(isSelected ? DeyttTheme.MintSurface : DeyttTheme.Surface2),
            VerticalAlignment = VerticalAlignment.Center,
        };
        Grid.SetColumn(marker, 1);
        row.Children.Add(marker);

        var button = new Button
        {
            Content = row,
            Padding = new Thickness(13, 10),
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Background = DeyttTheme.Brush(isSelected ? DeyttTheme.Selected : DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(isSelected ? DeyttTheme.SelectedLine : DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(13),
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        button.Click += (_, _) =>
        {
            _selectedRoute = route;
            Render(ViewState.Success, Copy($"Выбран маршрут «{GetRouteTitle(route)} · {GetRouteProtocol(route)}».",
                $"Selected route: {GetRouteTitle(route)} · {GetRouteProtocol(route)}."), DeyttTheme.Mint);
        };
        return button;
    }

    private Border BuildPrimary(string label, Action action)
    {
        var text = DeyttTheme.TextBlock(label, 15, DeyttTheme.Background, FontWeight.SemiBold, wrap: false);
        text.HorizontalAlignment = HorizontalAlignment.Stretch;
        text.TextAlignment = TextAlignment.Center;
        var button = DeyttTheme.Action(text, action);
        button.HorizontalAlignment = HorizontalAlignment.Stretch;
        button.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        button.MinHeight = 52;
        _primaryButton = button;
        _primaryLabel = text;
        return new Border
        {
            MinHeight = 52,
            Background = new LinearGradientBrush
            {
                StartPoint = new RelativePoint(0, 0.5, RelativeUnit.Relative),
                EndPoint = new RelativePoint(1, 0.5, RelativeUnit.Relative),
                GradientStops = { new GradientStop(DeyttTheme.Sky, 0), new GradientStop(DeyttTheme.Mint, 1) },
            },
            CornerRadius = new CornerRadius(16),
            Child = button,
        };
    }

    private Button BuildTextButton(string label, Action action)
    {
        var button = DeyttTheme.Action(
            DeyttTheme.TextBlock(label, 13, DeyttTheme.Sky, FontWeight.SemiBold), action);
        button.HorizontalAlignment = HorizontalAlignment.Left;
        button.MinHeight = 36;
        return button;
    }

    private static TextBox CreateInput(string placeholder, double minHeight) => new()
    {
        PlaceholderText = placeholder,
        MinHeight = minHeight,
        FontSize = 15,
        Foreground = DeyttTheme.Brush(DeyttTheme.Text),
        Background = DeyttTheme.Brush(DeyttTheme.Surface2),
        BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
        BorderThickness = new Thickness(1),
        Padding = new Thickness(15, 10),
        VerticalContentAlignment = VerticalAlignment.Center,
        HorizontalAlignment = HorizontalAlignment.Stretch,
    };

    private Control BuildHeader()
    {
        var heading = new StackPanel { Spacing = 7 };
        var brandLine = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 11,
            VerticalAlignment = VerticalAlignment.Center,
        };
        brandLine.Children.Add(DeyttTheme.IconTile("◉", 42, DeyttTheme.Mint));
        var brand = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
        brand.Children.Add(DeyttTheme.TextBlock("deytt./connect", 10, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
        brand.Children.Add(DeyttTheme.TextBlock(Copy("НАСТРОЙКА ПОДКЛЮЧЕНИЯ", "CONNECTION SETUP"), 9, DeyttTheme.Muted,
            FontWeight.Medium, DeyttTheme.JetBrainsMono, wrap: false));
        brandLine.Children.Add(brand);
        heading.Children.Add(brandLine);
        heading.Children.Add(_headline);
        heading.Children.Add(_intro);

        var header = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            ColumnSpacing = 14,
        };
        Grid.SetColumn(heading, 0);
        header.Children.Add(heading);

        var backButton = BuildTextButton(Copy("← Назад", "← Back"), Cancel);
        backButton.VerticalAlignment = VerticalAlignment.Top;
        Grid.SetColumn(backButton, 1);
        header.Children.Add(backButton);
        return header;
    }

    private Control BuildSteps()
    {
        var row = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,*,*"),
            ColumnSpacing = 7,
        };
        var labels = new[] { "TELEGRAM", Copy("ПОДПИСКА", "SUBSCRIPTION"), Copy("МАРШРУТ", "ROUTE") };
        for (var index = 0; index < labels.Length; index++)
        {
            var segment = new StackPanel { Spacing = 7 };
            var bar = new Border
            {
                Height = 3,
                CornerRadius = new CornerRadius(2),
                Background = DeyttTheme.Brush(DeyttTheme.Line),
            };
            _stepBars[index] = bar;
            segment.Children.Add(bar);
            segment.Children.Add(DeyttTheme.TextBlock(labels[index], 9, DeyttTheme.Muted,
                FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
            Grid.SetColumn(segment, index);
            row.Children.Add(segment);
        }
        return row;
    }

    private void SetHeader(ViewState state)
    {
        (_headline.Text, _intro.Text) = state switch
        {
            ViewState.Username => (Copy("Подключите DEYTT", "Set up DEYTT"),
                Copy("Войдите через Telegram, чтобы загрузить подписку и выбрать первый маршрут.",
                    "Sign in with Telegram to load your subscription and choose the first route.")),
            ViewState.Code => (Copy("Подтвердите вход", "Confirm sign-in"),
                Copy("Одноразовый код связывает Windows-приложение с вашим Telegram-аккаунтом.",
                    "A one-time code links this Windows app to your Telegram account.")),
            ViewState.DeviceConflict => (Copy("Нужен свободный слот компьютера", "A computer slot is needed"),
                Copy("Освободи слот на прежнем компьютере и повтори проверку. Если он недоступен, обратись в поддержку. Слоты телефона и happ отдельные.",
                    "Free the slot on the previous computer and retry. If it is unavailable, contact support. Phone and happ slots are separate.")),
            ViewState.Refreshing => (Copy("Загружаем данные", "Loading your data"),
                Copy("Сверяем аккаунт и получаем готовые маршруты подписки.",
                    "Checking your account and loading subscription routes.")),
            ViewState.RefreshError => (Copy("Не удалось обновить", "Could not refresh"),
                Copy("Сохранённый сеанс можно использовать для следующей попытки.",
                    "Your saved session is ready for another attempt.")),
            ViewState.NoSubscription => (Copy("Подписка не найдена", "No subscription found"),
                Copy("Подключите аккаунт с подпиской или импортируйте ссылку.",
                    "Sign in to an account with a subscription or import a link.")),
            ViewState.ImportUrl or ViewState.ImportingUrl => (Copy("Импорт подписки", "Import subscription"),
                Copy("Ссылка подготовит профиль для первого маршрута.",
                    "The link will load a profile for your first route.")),
            ViewState.Success => (Copy("Маршруты готовы", "Routes are ready"),
                Copy("Выберите стартовый выход и передайте профиль в deytt./connect.",
                    "Choose your starting route and import the profile into deytt./connect.")),
            _ => (Copy("Подключите DEYTT", "Set up DEYTT"),
                Copy("Войдите через Telegram, чтобы загрузить подписку и выбрать первый маршрут.",
                    "Sign in with Telegram to load your subscription and choose the first route.")),
        };
    }

    private void UpdateSteps(int activeStep)
    {
        for (var index = 0; index < _stepBars.Length; index++)
            _stepBars[index].Background = DeyttTheme.Brush(index <= activeStep ? DeyttTheme.Mint : DeyttTheme.Line);
    }

    private void SetStatus(string message, Color color)
    {
        _statusText.Text = message;
        _statusText.Foreground = DeyttTheme.Brush(color);
        _statusPanel.IsVisible = !string.IsNullOrWhiteSpace(message);
        _statusPanel.BorderBrush = DeyttTheme.Brush(color == DeyttTheme.Coral ? DeyttTheme.Coral : DeyttTheme.Line);
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        if (_primaryButton is not null)
        {
            _primaryButton.IsEnabled = !busy;
            _primaryButton.Opacity = busy ? 0.65 : 1;
        }
        if (_usernameInput is not null) _usernameInput.IsEnabled = !busy;
        if (_codeInput is not null) _codeInput.IsEnabled = !busy;
        if (_urlInput is not null) _urlInput.IsEnabled = !busy;
    }

    private static bool IsNonFatal(Exception error) =>
        error is not (OutOfMemoryException or StackOverflowException or AccessViolationException);

    private static string DiagnosticOrigin(Exception error)
    {
        var methods = new StackTrace(error, false).GetFrames()?
            .Select(frame => frame.GetMethod())
            .OfType<System.Reflection.MethodBase>()
            .Where(candidate => candidate.Name != "MoveNext")
            .ToArray();
        if (methods is null)
            return "unknown";

        var appMethods = methods
            .Where(candidate => candidate.DeclaringType?.Namespace?.StartsWith("DeyttConnect.", StringComparison.Ordinal) == true)
            .Select(FormatDiagnosticMethod)
            .Distinct(StringComparer.Ordinal)
            .Take(2)
            .ToArray();
        if (appMethods.Length > 0)
            return string.Join(">", appMethods);

        var frameworkMethod = methods.FirstOrDefault(candidate =>
            candidate.DeclaringType?.Namespace?.StartsWith("System.Text.Json", StringComparison.Ordinal) == true ||
            candidate.DeclaringType?.Namespace?.StartsWith("System.Net.Http", StringComparison.Ordinal) == true);
        return frameworkMethod is null ? "unknown" : FormatDiagnosticMethod(frameworkMethod);
    }

    private static string FormatDiagnosticMethod(System.Reflection.MethodBase method)
    {
        var name = $"{method.DeclaringType?.Name}.{method.Name}";
        return new string(name.Where(character => char.IsLetterOrDigit(character) || character is '.' or '_' or '+')
            .Take(64)
            .ToArray());
    }

    private void OpenBot()
    {
        if (!Uri.TryCreate(_botUrl, UriKind.Absolute, out var uri) ||
            uri.Scheme != Uri.UriSchemeHttps || !uri.Host.Equals("t.me", StringComparison.OrdinalIgnoreCase))
        {
            SetStatus(Copy("Ссылка Telegram недоступна. Повтори запрос кода.",
                "The Telegram link is unavailable. Request a new code."), DeyttTheme.Coral);
            return;
        }

        try
        {
            Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
        }
        catch (Exception error) when (error is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            SetStatus(Copy("Не удалось открыть Telegram. Открой бота вручную и вернись в приложение.",
                "Could not open Telegram. Open the bot manually, then return to the app."), DeyttTheme.Amber);
        }
    }

    private string PairingError(TelegramApiException error) => error.Code switch
    {
        "invalid_username" => Copy("Проверь username: от 5 до 32 латинских букв, цифр или символов _.",
            "Check the username: use 5–32 Latin letters, digits, or underscores."),
        "rate_limited" or "pairing_rate_limited" => Copy("Слишком много попыток. Подожди немного и повтори.",
            "Too many attempts. Wait a little and try again."),
        "pair_code_invalid" => Copy("Код неверный или истёк. Проверь сообщение или запроси новый код.",
            "The code is invalid or expired. Check the Telegram message or request a new code."),
        "pair_code_locked" => Copy("Попытки исчерпаны. Запроси новый код.",
            "Too many code attempts. Request a new code."),
        "account_blocked" => Copy("Этот Telegram-аккаунт заблокирован.", "This Telegram account is blocked."),
        "not_registered" => Copy("Сначала открой бота DEYTT в Telegram и создай аккаунт.",
            "Start the DEYTT bot in Telegram and create an account first."),
        "pairing_unavailable" => Copy("Вход через Telegram сейчас недоступен.",
            "Telegram sign-in is currently unavailable."),
        "session_expired" or "session_invalid" => Copy("Сеанс истёк. Войди через Telegram ещё раз.",
            "Your session expired. Sign in with Telegram again."),
        "app_device_limit_reached" or "device_limit_reached" => Copy(
            "Слот компьютера уже занят другим компьютером. На аккаунт доступно по одному телефону и одному компьютеру; слот телефона и квота Happ отдельные. Если нужно заменить компьютер, обратись в поддержку.",
            "The computer slot is already used by another computer. Each account has one phone slot and one computer slot; the phone slot and Happ allowance are separate. Contact support if you need to replace the computer."),
        _ => Copy("Сервер не подтвердил вход. Повтори попытку позже.",
            "The server could not confirm sign-in. Please try again later."),
    };

    private static bool IsDeviceSlotConflict(TelegramApiException error) =>
        error.Code is "app_device_limit_reached" or "device_limit_reached" ||
        error.StatusCode == HttpStatusCode.Conflict && error.Code.Contains("device", StringComparison.OrdinalIgnoreCase);

    private static Color ErrorColor(TelegramApiException error) =>
        error.Code is "rate_limited" or "pairing_rate_limited" ? DeyttTheme.Amber : DeyttTheme.Coral;

    private string Copy(string russian, string english) => (_language == "en" ? english : russian).ToLowerInvariant();

    private string LocalizeImportProgress(string stage) => stage.Trim().ToLowerInvariant() switch
    {
        "download" => Copy("Получаем маршруты…", "Downloading routes…"),
        "validate" => Copy("Проверяем подписку…", "Validating the subscription…"),
        "awg" => Copy("Загружаем дополнительные маршруты…", "Loading additional routes…"),
        "save" => Copy("Сохраняем подписку…", "Saving the subscription…"),
        _ => Copy("Получаем маршруты…", "Loading routes…"),
    };

    private string GetRouteTitle(WindowsRoute route)
    {
        if (_language != "en")
            return route.CountryName;

        return route.CountryCode switch
        {
            "AUTO" => "Automatic selection",
            "RU-DE" => "LTE + allowlists",
            "NL" => "Netherlands",
            "RU" => "Russia",
            "DE" => "Germany",
            "FI" => "Finland",
            "IT" => "Italy",
            "AWG_UNKNOWN" => "Region not specified",
            _ => route.CountryName,
        };
    }

    private string GetRouteProtocol(WindowsRoute route) =>
        route.Protocol == "AUTO" ? Copy("Автоподбор", "Automatic") : route.ProtocolName;

    private static WindowsRoute? ChooseInitialRoute(IReadOnlyList<WindowsRoute> routes) =>
        routes.FirstOrDefault(route => route.Id == "auto") ?? routes.FirstOrDefault();

    private static string? ReadDeepLinkParameter(string query, string parameter)
    {
        foreach (var item in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var separator = item.IndexOf('=');
            if (separator < 0)
                continue;
            var name = Uri.UnescapeDataString(item[..separator].Replace('+', ' '));
            if (name.Equals(parameter, StringComparison.OrdinalIgnoreCase))
                return Uri.UnescapeDataString(item[(separator + 1)..].Replace('+', ' '));
        }
        return null;
    }

    private static void FocusLater(Control? control)
    {
        if (control is null)
            return;
        Dispatcher.UIThread.Post(() => control.Focus(), DispatcherPriority.Input);
    }
}

