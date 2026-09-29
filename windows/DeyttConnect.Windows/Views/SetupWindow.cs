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
        try
        {
            var paired = await _api.VerifyPairingAsync(_challenge, code, _lifetime.Token);
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
            System.Diagnostics.Trace.TraceError("Telegram pairing flow failed ({0}).", error.GetType().Name);
            Render(ViewState.Code, Copy(
                "Не удалось завершить вход. Эта ошибка не означает, что код неверный. Попробуй запросить новый код.",
                "Could not finish sign-in. This does not mean the code is wrong. Try requesting a new code."), DeyttTheme.Coral,
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
            await Task.WhenAll(accountTask, subscriptionTask);
            _account = await accountTask;
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
                "Достигнут лимит подключённых приложений DEYTT. Попроси поддержку перенести приложение на это устройство.",
                "The DEYTT app limit has been reached. Ask support to transfer the app to this device."),
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
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or IOException)
        {
            Render(ViewState.RefreshError, _options.DeferSessionSaveUntilImport
                ? Copy("Не удалось загрузить подписку. Текущий аккаунт не изменён; проверь интернет и повтори.",
                    "Could not load the subscription. Your current account is unchanged; check your connection and retry.")
                : Copy("Не удалось обновить подписку. Проверь интернет и повтори; сохранённый сеанс останется на устройстве.",
                    "Could not refresh the subscription. Check your connection and retry; the saved session will stay on this device."),
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
        if ((_busy && !automatic) || _completed || _payload is null || _selectedRoute is null)
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
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
                                      InvalidDataException or InvalidOperationException or CryptographicException)
        {
            var message = Copy("Не удалось сохранить подписку в приложении. Повтори импорт.",
                "The app could not save the subscription. Retry the import.");
            if (automatic)
                Render(ViewState.Success, message, DeyttTheme.Coral);
            else
                SetStatus(message, DeyttTheme.Coral);
        }
        finally
        {
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
        _body.Children.Add(DeyttTheme.TextBlock(Copy(
            "Повтори запрос. Если соединение снова не установится, проверь сеть и попробуй позже.",
            "Retry the request. If it still fails, check your connection and try again later."),
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
        _body.Children.Add(DeyttTheme.SectionLabel(Copy("01 / ЛИМИТ ПРИЛОЖЕНИЙ", "01 / APP LIMIT")));
        _body.Children.Add(DeyttTheme.TextBlock(Copy("Достигнут лимит приложений DEYTT.",
            "The DEYTT app limit has been reached."), 15,
            DeyttTheme.Text, FontWeight.SemiBold));
        if (_sessionToken is { Length: > 0 })
        {
            _body.Children.Add(DeyttTheme.TextBlock(Copy(
                "Попроси поддержку перенести deytt.connect на это устройство. После переноса повтори проверку. Приложение само не отключает устройства.",
                "Ask support to transfer deytt.connect to this device. Retry after the transfer. The app does not disconnect devices."),
                13, DeyttTheme.Muted));
            _body.Children.Add(BuildPrimary(
                _transferRequestSent
                    ? Copy("Повторить проверку", "Retry the check")
                    : Copy("Обратиться в поддержку", "Contact support"),
                () =>
                {
                    if (_transferRequestSent && _sessionToken is { } token)
                        _ = RefreshSubscriptionAsync(token, completeAfterVerification: _completeAfterPairing);
                    else
                        _ = RequestDeviceTransferAsync();
                }));
        }
        else
        {
            _body.Children.Add(DeyttTheme.TextBlock(Copy(
                "Чтобы попросить поддержку перенести приложение, сначала подключи аккаунт Telegram. Можно также освободить старый сеанс DEYTT в Telegram.",
                "Sign in to Telegram before asking support to transfer the app. You can also remove an old DEYTT session in Telegram."),
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
        const string message = "Здравствуйте! Не получается подключить deytt.connect: сервер отвечает HTTP 409 app_device_limit_reached (download/http_409). Прошу проверить и перенести приложение на это устройство.";
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
                    "Запрос отправлен. После переноса слота поддержкой нажми «Повторить проверку».",
                    "Request sent. After support transfers the app slot, select “Retry the check”."), DeyttTheme.Mint);
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
        var title = Copy("Перенос приложения", "Transfer the app");
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
                Copy("Отправить в поддержку обращение с просьбой перенести deytt.connect на это устройство?",
                    "Send support a request to transfer deytt.connect to this device?"),
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
        brand.Children.Add(DeyttTheme.TextBlock("DEYTT CONNECT", 10, DeyttTheme.Muted,
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
            ViewState.DeviceConflict => (Copy("Нужен свободный сеанс", "An app slot is needed"),
                Copy("Обратись в поддержку за переносом приложения на это устройство.",
                    "Ask support to transfer the app to this device.")),
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
                Copy("Выберите стартовый выход и передайте профиль в DEYTT Connect.",
                    "Choose your starting route and import the profile into DEYTT Connect.")),
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
        "app_device_limit_reached" or "device_limit_reached" => Copy("Достигнут лимит подключённых приложений.",
            "The connected app limit has been reached."),
        _ => Copy("Сервер не подтвердил вход. Повтори попытку позже.",
            "The server could not confirm sign-in. Please try again later."),
    };

    private static bool IsDeviceSlotConflict(TelegramApiException error) =>
        error.Code is "app_device_limit_reached" or "device_limit_reached" ||
        error.StatusCode == HttpStatusCode.Conflict && error.Code.Contains("device", StringComparison.OrdinalIgnoreCase);

    private static Color ErrorColor(TelegramApiException error) =>
        error.Code is "rate_limited" or "pairing_rate_limited" ? DeyttTheme.Amber : DeyttTheme.Coral;

    private string Copy(string russian, string english) => _language == "en" ? english : russian;

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
