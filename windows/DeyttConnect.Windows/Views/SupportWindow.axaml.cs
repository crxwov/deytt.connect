using System.Net;
using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Controls.Shapes;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class SupportView : UserControl, IDisposable
{
    private enum SupportPage { Thread, Terms, Privacy }

    private const int MaximumMessageLength = 4000;
    private static readonly TimeSpan RefreshInterval = TimeSpan.FromSeconds(30);
    private readonly IWindowsSupportClient _client;
    private readonly DispatcherTimer _refreshTimer = new() { Interval = RefreshInterval };
    private readonly CancellationTokenSource _lifetime = new();
    private readonly Func<Task<string?>> _onSignInRequested;
    private readonly Func<string, string, string, Task<bool>> _confirmAsync;
    private CancellationTokenSource? _activationLifetime;
    private CancellationTokenSource? _documentLoad;
    private string? _accessToken;
    private string _language;
    private SupportTicket? _ticket;
    private bool _busy;
    private bool _isActive;
    private bool _disposed;
    private bool _retrySend;
    private bool _retryNeedsConfirmation;
    private long? _accountId;
    private long? _reauthAccountId;
    private string? _reauthDraft;
    private string? _reauthUncertainText;
    private string? _uncertainSendText;
    private int? _uncertainTicketId;
    private int _uncertainBaselineCount;
    private bool _uncertainExistingTicket;
    private IReadOnlyList<SupportMessage> _threadMessages = [];
    private int _activationGeneration;
    private SupportPage _page = SupportPage.Thread;

    public SupportView()
        : this(null,
            static () => Task.FromResult<string?>(null),
            static (_, _, _) => Task.FromResult(false))
    {
    }

    public SupportView(
        string? accessToken,
        Func<Task<string?>> onSignInRequested,
        Func<string, string, string, Task<bool>> confirmAsync,
        string language = "ru",
        IWindowsSupportClient? client = null)
    {
        InitializeComponent();
        _client = client ?? new WindowsSupportClient();
        _accessToken = accessToken;
        _onSignInRequested = onSignInRequested ?? throw new ArgumentNullException(nameof(onSignInRequested));
        _confirmAsync = confirmAsync ?? throw new ArgumentNullException(nameof(confirmAsync));
        _language = language is "en" ? "en" : "ru";
        SetCopy();

        SupportNavButton.Click += (_, _) => ShowThread();
        TermsNavButton.Click += async (_, _) => await ShowDocumentAsync(SupportDocumentKind.Terms);
        PrivacyNavButton.Click += async (_, _) => await ShowDocumentAsync(SupportDocumentKind.Privacy);
        TelegramNavButton.Click += (_, _) => OpenTelegram();
        RefreshButton.Click += async (_, _) => await RefreshCurrentPageAsync();
        RetryButton.Click += async (_, _) =>
        {
            if (_retryNeedsConfirmation)
                await ConfirmAmbiguousRetryAsync();
            else if (_retrySend)
                await SendMessageAsync();
            else
                await RefreshThreadAsync();
        };
        DocumentRetryButton.Click += async (_, _) => await LoadDocumentAsync(CurrentDocumentKind());
        SendButton.Click += async (_, _) => await SendMessageAsync();
        CloseTicketButton.Click += async (_, _) => await ConfirmAndCloseTicketAsync();
        SignInButton.Click += async (_, _) => await SignInAsync();
        BackButton.Click += (_, _) => BackRequested?.Invoke(this, EventArgs.Empty);
        Composer.PropertyChanged += (_, args) =>
        {
            if (args.Property == TextBox.TextProperty)
                CharacterCount.Text = $"{Composer.Text?.Length ?? 0} / {MaximumMessageLength}";
        };
        _refreshTimer.Tick += async (_, _) => await RefreshThreadAsync();
        KeyDown += OnKeyDown;
        ShowSignedOutState();
    }

    public event EventHandler? BackRequested;

    private bool IsRussian => _language == "ru";

    private bool IsActive => _isActive && !_disposed &&
        _activationLifetime is { IsCancellationRequested: false };

    private CancellationToken ActiveToken => IsActive
        ? _activationLifetime!.Token
        : new CancellationToken(canceled: true);

    private bool IsCurrentActivation(int generation) =>
        IsActive && _activationGeneration == generation;

    private bool IsCurrentOperation(int generation, string? accessToken) =>
        IsCurrentActivation(generation) &&
        string.Equals(_accessToken, accessToken, StringComparison.Ordinal);

    public void Activate(string? accessToken)
    {
        if (_disposed)
            return;

        if (_isActive && string.Equals(_accessToken, accessToken, StringComparison.Ordinal))
            return;

        if (_isActive)
            Deactivate();

        var accountChanged = !string.Equals(_accessToken, accessToken, StringComparison.Ordinal);
        _accessToken = accessToken;
        if (accountChanged)
            ResetAccountState();

        _activationLifetime = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        _activationGeneration++;
        _isActive = true;
        _busy = false;
        SetBusy(false);

        if (_page == SupportPage.Thread)
        {
            if (_accessToken is null)
            {
                ShowSignedOutState();
                SignInButton.Focus();
            }
            else
            {
                MessageScroll.IsVisible = true;
                SignedOutPanel.IsVisible = false;
                ComposerCard.IsVisible = true;
                RetryButton.IsVisible = true;
                _ = LoadAccountIdentityAsync(_accessToken, _activationGeneration);
                _ = RefreshThreadAsync();
                _refreshTimer.Start();
            }
        }
        else
        {
            _ = LoadDocumentAsync(CurrentDocumentKind());
        }
    }

    public void Deactivate()
    {
        if (!_isActive)
            return;

        _isActive = false;
        _activationGeneration++;
        _refreshTimer.Stop();
        _documentLoad?.Cancel();
        _documentLoad = null;
        _activationLifetime?.Cancel();
        _activationLifetime?.Dispose();
        _activationLifetime = null;
        _busy = false;
        SetBusy(false);

        if (_page != SupportPage.Thread)
        {
            DocumentStatus.Text = T("Загрузка приостановлена до возвращения на экран.", "Loading paused until you return to this screen.");
            DocumentStatus.Foreground = DeyttTheme.Brush(DeyttTheme.Muted);
            DocumentRetryButton.IsVisible = true;
            DocumentRetryButton.IsEnabled = true;
        }
    }

    public async Task<bool> ConfirmDiscardDraftAsync()
    {
        var draft = Composer.Text?.Trim();
        if (string.IsNullOrEmpty(draft))
            return true;

        return await ConfirmAsync(
            T("Закрыть приложение?", "Close the app?"),
            T("Черновик сообщения будет удалён.", "Your unsent message draft will be discarded."),
            T("Закрыть", "Close"));
    }

    public void Dispose()
    {
        if (_disposed)
            return;

        Deactivate();
        _disposed = true;
        _lifetime.Cancel();
        _lifetime.Dispose();
    }

    private void SetCopy()
    {
        SupportNavButton.Content = NavContent("◉", T("Поддержка", "Support"), _page == SupportPage.Thread);
        TermsNavButton.Content = NavContent("§", T("Условия", "Terms"), _page == SupportPage.Terms);
        PrivacyNavButton.Content = NavContent("◇", T("Приватность", "Privacy"), _page == SupportPage.Privacy);
        TelegramNavButton.Content = NavContent("↗", T("Открыть Telegram", "Open Telegram"), false);
        HeaderKicker.Text = _page == SupportPage.Thread ? "SUPPORT" : "DOCUMENTS";
        HeaderTitle.Text = _page switch
        {
            SupportPage.Terms => T("Условия использования", "Terms of use"),
            SupportPage.Privacy => T("Конфиденциальность", "Privacy"),
            _ => T("Поддержка", "Support"),
        };
        RefreshButton.Content = T("Обновить", "Refresh");
        BackButton.Content = T("Назад", "Back");
        if (_uncertainSendText is not null)
            SetUncertainRetryAction();
        else
            SetRetryAction(_retrySend);
        DocumentRetryButton.Content = T("Повторить", "Retry");
        ComposerLabel.Text = T("СООБЩЕНИЕ КОМАНДЕ", "MESSAGE THE TEAM");
        Composer.PlaceholderText = T("Опишите вопрос минимум в пяти символах", "Describe the issue in at least five characters");
        CloseTicketButton.Content = T("Закрыть обращение", "Close ticket");
        SendButton.Content = T("Отправить · Ctrl+Enter", "Send · Ctrl+Enter");
        SignInButton.Content = T("Подключить Telegram", "Connect Telegram");
        SignInDescription.Text = T(
            "Подключите Telegram, чтобы написать команде и посмотреть историю обращений.",
            "Connect Telegram to message support and view your ticket history.");
        CharacterCount.Text = $"{Composer.Text?.Length ?? 0} / {MaximumMessageLength}";
    }

    private void ResetAccountState()
    {
        _accountId = null;
        _reauthAccountId = null;
        _reauthDraft = null;
        _reauthUncertainText = null;
        _uncertainSendText = null;
        _retryNeedsConfirmation = false;
        _threadMessages = [];
        _ticket = null;
        MessageStack.Children.Clear();
        CloseTicketButton.IsVisible = false;
        Composer.Text = string.Empty;
        CharacterCount.Text = $"0 / {MaximumMessageLength}";
    }

    private void OpenTelegram()
    {
        try
        {
            Process.Start(new ProcessStartInfo("https://t.me/deyttbot") { UseShellExecute = true });
        }
        catch (Exception error)
        {
            SetStatus(ErrorText(error), isError: true);
        }
    }

    private static Control NavContent(string icon, string label, bool selected)
    {
        var content = new Grid { ColumnDefinitions = new ColumnDefinitions("30,*") };
        content.Children.Add(new TextBlock
        {
            Text = icon,
            FontFamily = DeyttTheme.JetBrainsMono,
            FontSize = 15,
            FontWeight = FontWeight.SemiBold,
            Foreground = DeyttTheme.Brush(selected ? DeyttTheme.Sky : DeyttTheme.Muted),
            VerticalAlignment = VerticalAlignment.Center,
        });
        var labelBlock = DeyttTheme.TextBlock(label, 13, selected ? DeyttTheme.Text : DeyttTheme.Muted,
            selected ? FontWeight.SemiBold : FontWeight.Normal);
        content.Children.Add(labelBlock);
        Grid.SetColumn(labelBlock, 1);

        var button = new Border
        {
            Background = DeyttTheme.Brush(selected ? DeyttTheme.Selected : Colors.Transparent),
            BorderBrush = DeyttTheme.Brush(selected ? DeyttTheme.SelectedLine : Colors.Transparent),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(13),
            Padding = new Thickness(12, 10),
            Child = content,
        };
        return button;
    }

    private async Task RefreshCurrentPageAsync()
    {
        if (_page == SupportPage.Thread)
            await RefreshThreadAsync();
        else
            await LoadDocumentAsync(CurrentDocumentKind());
    }

    private async Task RefreshThreadAsync()
    {
        if (_busy || !IsActive || _accessToken is null || _page != SupportPage.Thread)
            return;
        SetRetryAction(retrySend: false);
        var generation = _activationGeneration;
        var accessToken = _accessToken;
        var cancellationToken = ActiveToken;
        _busy = true;
        SetStatus(T("Загружаем переписку…", "Loading conversation…"));
        SetBusy(true);
        try
        {
            var snapshot = await _client.GetThreadAsync(accessToken, cancellationToken);
            if (!IsCurrentOperation(generation, accessToken))
                return;
            _ticket = snapshot.Ticket;
            _threadMessages = snapshot.Messages;
            RenderMessages(snapshot.Messages);
            CloseTicketButton.IsVisible = _ticket?.IsOpen == true;
            if (_uncertainSendText is not null)
            {
                if (SnapshotConfirmsUncertainSend(snapshot))
                {
                    if (string.Equals(Composer.Text?.Trim(), _uncertainSendText, StringComparison.Ordinal))
                        Composer.Text = string.Empty;
                    _uncertainSendText = null;
                    SetRetryAction(retrySend: false);
                    SetStatus(T("Сообщение отправлено.", "Message sent."));
                }
                else
                {
                    SetUncertainRetryAction();
                    SetStatus(T(
                        "Не удалось подтвердить отправку. Сообщение могло дойти; повтор может создать копию. Проверьте историю и подтвердите повтор отдельно.",
                        "Could not confirm delivery. The message may have arrived; retrying could create a duplicate. Check history and confirm a separate retry."), isError: true);
                }
                return;
            }
            var status = _ticket switch
            {
                null => T("Новое обращение создастся после первого сообщения.", "A new ticket will be created with your first message."),
                { IsOpen: true } => T($"Обращение №{_ticket.Id} · открыто", $"Ticket #{_ticket.Id} · open"),
                _ => T("Обращение закрыто. Новое сообщение откроет новое обращение.", "This ticket is closed. A new message will create a new ticket."),
            };
            SetStatus(status);
        }
        catch (OperationCanceledException) when (!IsCurrentOperation(generation, accessToken))
        {
        }
        catch (Exception error)
        {
            if (!IsCurrentOperation(generation, accessToken))
                return;
            if (!ShowReauthenticationIfNeeded(error))
                SetStatus(ErrorText(error) + " · " + T("нажмите, чтобы повторить", "click to retry"), isError: true);
        }
        finally
        {
            if (IsCurrentActivation(generation))
            {
                _busy = false;
                SetBusy(false);
            }
        }
    }

    private void RenderMessages(IReadOnlyList<SupportMessage> messages)
    {
        MessageStack.Children.Clear();
        if (messages.Count == 0)
        {
            MessageStack.Children.Add(new Border
            {
                Background = DeyttTheme.Brush(DeyttTheme.Surface),
                BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(15),
                Padding = new Thickness(15, 12),
                Child = DeyttTheme.TextBlock(
                    T("История переписки появится здесь.", "Your conversation will appear here."),
                    13, DeyttTheme.Muted),
            });
            return;
        }

        foreach (var message in messages)
        {
            var fromSupport = message.IsFromSupport;
            var bubbleContent = new StackPanel { Spacing = 5 };
            bubbleContent.Children.Add(DeyttTheme.TextBlock(
                fromSupport ? "DEYTT" : T("ВЫ", "YOU"),
                9, DeyttTheme.Muted, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false));
            bubbleContent.Children.Add(DeyttTheme.TextBlock(message.Text, 13, DeyttTheme.Text));
            var bubble = new Border
            {
                Background = DeyttTheme.Brush(fromSupport ? DeyttTheme.Surface2 : DeyttTheme.Surface),
                BorderBrush = DeyttTheme.Brush(fromSupport ? DeyttTheme.Line : DeyttTheme.SelectedLine),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(16),
                Padding = new Thickness(13, 10),
                MaxWidth = 760,
                HorizontalAlignment = fromSupport ? HorizontalAlignment.Left : HorizontalAlignment.Right,
                Child = bubbleContent,
            };
            MessageStack.Children.Add(bubble);
        }

        Dispatcher.UIThread.Post(() => MessageScroll.ScrollToEnd());
    }

    private async Task SendMessageAsync()
    {
        if (_uncertainSendText is not null)
        {
            await ConfirmAmbiguousRetryAsync();
            return;
        }
        var text = Composer.Text?.Trim() ?? string.Empty;
        if (text.Length < 5)
        {
            SetStatus(T("Напишите сообщение минимум из пяти символов.", "Write at least five characters."), isError: true);
            Composer.Focus();
            return;
        }
        if (text.Length > MaximumMessageLength || _busy || !IsActive || _accessToken is null)
            return;

        var generation = _activationGeneration;
        var accessToken = _accessToken;
        var cancellationToken = ActiveToken;
        _busy = true;
        SetBusy(true);
        SetRetryAction(retrySend: false);
        SetStatus(T("Отправляем сообщение…", "Sending message…"));
        var ticket = _ticket;
        var sendsToExistingTicket = ticket?.IsOpen == true;
        var baselineTicketId = ticket?.Id;
        var baselineCount = CountMatchingUserMessages(_threadMessages, text);
        var postStarted = false;
        long? operationAccountId = _accountId;
        try
        {
            if (_accountId is null)
            {
                try { _accountId = await _client.GetAccountIdAsync(accessToken, cancellationToken); }
                catch (Exception identityError) when (identityError is not OperationCanceledException)
                {
                    if (ShowReauthenticationIfNeeded(identityError))
                        return;
                }
            }
            operationAccountId = _accountId;
            if (!IsCurrentOperation(generation, accessToken))
                return;
            postStarted = true;
            if (ticket?.IsOpen == true)
                await _client.SendMessageAsync(accessToken, ticket.Id, text, cancellationToken);
            else
                await _client.CreateTicketAsync(accessToken, text, cancellationToken);
            if (!IsCurrentOperation(generation, accessToken))
            {
                await HandleLatePostOutcomeAsync(operationAccountId, accessToken, text,
                    sendsToExistingTicket, baselineTicketId, baselineCount);
                return;
            }
            Composer.Text = string.Empty;
            await RefreshThreadAsyncWhileBusy(generation, accessToken, cancellationToken);
        }
        catch (OperationCanceledException) when (!IsCurrentOperation(generation, accessToken))
        {
            if (postStarted && IsSameAccountForLateOperation(operationAccountId, accessToken))
            {
                MarkUncertainSend(text, sendsToExistingTicket, baselineTicketId, baselineCount);
                await ReconcileLatePostIfActiveAsync(operationAccountId, accessToken, text,
                    sendsToExistingTicket, baselineTicketId, baselineCount);
            }
        }
        catch (Exception error)
        {
            if (!IsCurrentOperation(generation, accessToken))
                return;
            if (ShowReauthenticationIfNeeded(error))
                return;
            if (postStarted && IsAmbiguousSendOutcome(error))
            {
                MarkUncertainSend(text, sendsToExistingTicket, baselineTicketId, baselineCount);
                await ReconcileAmbiguousSendAsync(generation, accessToken, cancellationToken,
                    text, sendsToExistingTicket, baselineTicketId, baselineCount);
                return;
            }
            SetRetryAction(retrySend: true);
            SetStatus(ErrorText(error) + " · " + T("черновик сохранён — можно отправить ещё раз", "draft kept — you can retry sending"), isError: true);
        }
        finally
        {
            if (IsCurrentActivation(generation))
            {
                _busy = false;
                SetBusy(false);
            }
        }
    }

    private async Task RefreshThreadAsyncWhileBusy(
        int generation,
        string accessToken,
        CancellationToken cancellationToken)
    {
        if (!IsCurrentOperation(generation, accessToken) || cancellationToken.IsCancellationRequested)
            return;
        try
        {
            var snapshot = await _client.GetThreadAsync(accessToken, cancellationToken);
            if (!IsCurrentOperation(generation, accessToken))
                return;
            _ticket = snapshot.Ticket;
            _threadMessages = snapshot.Messages;
            RenderMessages(snapshot.Messages);
            CloseTicketButton.IsVisible = _ticket?.IsOpen == true;
            SetStatus(_ticket switch
            {
                null => T("Новое обращение создастся после первого сообщения.", "A new ticket will be created with your first message."),
                { IsOpen: true } => T($"Обращение №{_ticket.Id} · открыто", $"Ticket #{_ticket.Id} · open"),
                _ => T("Обращение закрыто. Новое сообщение откроет новое обращение.", "This ticket is closed. A new message will create a new ticket."),
            });
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            if (!IsCurrentOperation(generation, accessToken))
                return;
            var reauthenticationRequired = ShowReauthenticationIfNeeded(error);
            if (IsCurrentActivation(generation))
            {
                SetRetryAction(retrySend: false);
                SetStatus(reauthenticationRequired
                    ? T("Сообщение отправлено, но историю не удалось обновить. Подключите Telegram повторно.",
                        "Message sent, but conversation could not be refreshed. Reconnect Telegram to continue.")
                    : T("Сообщение отправлено, историю обновить не удалось. Нажмите «Обновить».",
                        "Message sent, but conversation could not be refreshed. Press Refresh."),
                    isError: true);
            }
        }
    }

    private async Task LoadAccountIdentityAsync(string accessToken, int generation)
    {
        try
        {
            var accountId = await _client.GetAccountIdAsync(accessToken, ActiveToken);
            if (IsCurrentOperation(generation, accessToken))
                _accountId = accountId;
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            if (IsCurrentOperation(generation, accessToken) && ShowReauthenticationIfNeeded(error))
                return;
        }
    }

    private async Task ReconcileAmbiguousSendAsync(
        int generation,
        string accessToken,
        CancellationToken cancellationToken,
        string text,
        bool sendsToExistingTicket,
        int? baselineTicketId,
        int baselineCount)
    {
        try
        {
            var snapshot = await _client.GetThreadAsync(accessToken, cancellationToken);
            if (!IsCurrentOperation(generation, accessToken))
                return;
            var matchingCount = CountMatchingUserMessages(snapshot.Messages, text);
            var confirmed = matchingCount > 0 && (sendsToExistingTicket
                ? snapshot.Ticket?.Id == baselineTicketId && matchingCount > baselineCount
                : snapshot.Ticket?.Id != baselineTicketId);
            _ticket = snapshot.Ticket;
            _threadMessages = snapshot.Messages;
            RenderMessages(snapshot.Messages);
            CloseTicketButton.IsVisible = _ticket?.IsOpen == true;
            if (confirmed)
            {
                if (string.Equals(Composer.Text?.Trim(), text, StringComparison.Ordinal))
                    Composer.Text = string.Empty;
                _uncertainSendText = null;
                _retryNeedsConfirmation = false;
                SetRetryAction(retrySend: false);
                SetStatus(T("Сообщение отправлено.", "Message sent."));
                return;
            }

            SetUncertainRetryAction();
            SetStatus(T(
                "Не удалось подтвердить отправку. Сообщение могло дойти; повтор может создать копию. Проверьте историю и подтвердите повтор отдельно.",
                "Could not confirm delivery. The message may have arrived; retrying could create a duplicate. Check history and confirm a separate retry."), isError: true);
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            if (!IsCurrentOperation(generation, accessToken))
                return;
            if (ShowReauthenticationIfNeeded(error))
                return;
            SetUncertainRetryAction();
            SetStatus(T(
                "Не удалось узнать, отправлено ли сообщение. Оно могло дойти; проверьте подключение и историю перед подтверждённым повтором.",
                "Could not determine whether the message was sent. It may have arrived; check the connection and history before confirming a retry."), isError: true);
        }
    }

    private void MarkUncertainSend(string text, bool existingTicket, int? ticketId, int baselineCount)
    {
        _uncertainSendText = text;
        _uncertainExistingTicket = existingTicket;
        _uncertainTicketId = ticketId;
        _uncertainBaselineCount = baselineCount;
        if (_reauthAccountId.HasValue && _reauthAccountId == _accountId)
        {
            _reauthDraft ??= text;
            _reauthUncertainText = text;
        }
    }

    private bool IsSameAccountForLateOperation(long? operationAccountId, string operationToken) =>
        operationAccountId.HasValue
            ? _accountId == operationAccountId || _reauthAccountId == operationAccountId
            : string.Equals(_accessToken, operationToken, StringComparison.Ordinal);

    private async Task HandleLatePostOutcomeAsync(
        long? operationAccountId,
        string operationToken,
        string text,
        bool existingTicket,
        int? ticketId,
        int baselineCount)
    {
        if (!IsSameAccountForLateOperation(operationAccountId, operationToken))
            return;
        MarkUncertainSend(text, existingTicket, ticketId, baselineCount);
        await ReconcileLatePostIfActiveAsync(operationAccountId, operationToken, text,
            existingTicket, ticketId, baselineCount);
    }

    private async Task ReconcileLatePostIfActiveAsync(
        long? operationAccountId,
        string operationToken,
        string text,
        bool existingTicket,
        int? ticketId,
        int baselineCount)
    {
        if (!IsActive || !IsSameAccountForLateOperation(operationAccountId, operationToken) || _accessToken is null)
            return;
        await ReconcileAmbiguousSendAsync(_activationGeneration, _accessToken, ActiveToken,
            text, existingTicket, ticketId, baselineCount);
    }

    private async Task ConfirmAmbiguousRetryAsync()
    {
        var text = _uncertainSendText;
        if (text is null || _busy || !IsActive || _accessToken is null)
            return;
        var confirmed = await ConfirmAsync(
            T("Повторить отправку?", "Retry sending?"),
            T("Предыдущая попытка могла быть принята. Повтор может создать дубликат.",
                "The previous attempt may have been accepted. Retrying may create a duplicate."),
            T("Всё равно отправить", "Send anyway"));
        if (!confirmed || !IsActive || _accessToken is null || _uncertainSendText != text)
            return;
        Composer.Text = text;
        _uncertainSendText = null;
        _retryNeedsConfirmation = false;
        await SendMessageAsync();
    }

    private void SetUncertainRetryAction()
    {
        _retrySend = false;
        _retryNeedsConfirmation = true;
        RetryButton.Content = T("Подтвердить повтор…", "Confirm retry…");
    }

    private static int CountMatchingUserMessages(IReadOnlyList<SupportMessage> messages, string text) =>
        messages.Count(message => !message.IsFromSupport &&
                                  string.Equals(message.Text.Trim(), text, StringComparison.Ordinal));

    private bool SnapshotConfirmsUncertainSend(SupportThreadSnapshot snapshot)
    {
        if (_uncertainSendText is null)
            return false;
        var count = CountMatchingUserMessages(snapshot.Messages, _uncertainSendText);
        return count > 0 && (_uncertainExistingTicket
            ? snapshot.Ticket?.Id == _uncertainTicketId && count > _uncertainBaselineCount
            : snapshot.Ticket?.Id != _uncertainTicketId);
    }

    private static bool IsAmbiguousSendOutcome(Exception error) =>
        error is not WindowsSupportException supportError ||
        (int)supportError.StatusCode >= 500;

    private async Task ConfirmAndCloseTicketAsync()
    {
        if (_ticket is not { IsOpen: true } ticket || _accessToken is null || _busy || !IsActive)
            return;
        var generation = _activationGeneration;
        var accessToken = _accessToken;
        var confirmed = await ConfirmAsync(
            T("Закрыть обращение?", "Close this ticket?"),
            T("Новые ответы не будут приниматься. При необходимости вы сможете создать новое обращение.",
                "New replies will no longer be accepted. You can create another ticket when needed."),
            T("Закрыть обращение", "Close ticket"));
        if (!confirmed || !IsCurrentOperation(generation, accessToken))
            return;

        var cancellationToken = ActiveToken;
        _busy = true;
        SetBusy(true);
        SetStatus(T("Закрываем обращение…", "Closing ticket…"));
        try
        {
            await _client.CloseTicketAsync(accessToken, ticket.Id, cancellationToken);
            if (!IsCurrentOperation(generation, accessToken))
                return;
            await RefreshThreadAsyncWhileBusy(generation, accessToken, cancellationToken);
        }
        catch (OperationCanceledException) when (!IsCurrentOperation(generation, accessToken))
        {
        }
        catch (Exception error)
        {
            if (!IsCurrentOperation(generation, accessToken))
                return;
            if (!ShowReauthenticationIfNeeded(error))
                SetStatus(ErrorText(error) + " · " + T("нажмите, чтобы повторить", "click to retry"), isError: true);
        }
        finally
        {
            if (IsCurrentActivation(generation))
            {
                _busy = false;
                SetBusy(false);
            }
        }
    }

    private async Task SignInAsync()
    {
        if (_busy || !IsActive)
            return;
        var generation = _activationGeneration;
        _busy = true;
        SetBusy(true);
        try
        {
            var accessToken = await _onSignInRequested();
            if (!IsCurrentActivation(generation))
                return;
            if (accessToken is null)
            {
                SetStatus(T("Подключите Telegram, чтобы открыть поддержку.", "Connect Telegram to use support."));
                return;
            }

            var previousAccountId = _reauthAccountId;
            var draftToRestore = _reauthDraft;
            var uncertainTextToRestore = _reauthUncertainText;
            long? newAccountId = null;
            try { newAccountId = await _client.GetAccountIdAsync(accessToken, ActiveToken); }
            catch (Exception identityError) when (identityError is not OperationCanceledException) { }
            if (!IsCurrentActivation(generation))
                return;
            var sameAccount = previousAccountId.HasValue && newAccountId == previousAccountId;
            if (previousAccountId.HasValue || !string.Equals(_accessToken, accessToken, StringComparison.Ordinal))
                ResetAccountState();
            _accountId = newAccountId;
            _accessToken = accessToken;
            if (sameAccount && draftToRestore is not null)
            {
                Composer.Text = draftToRestore;
                _uncertainSendText = uncertainTextToRestore;
                if (_uncertainSendText is not null)
                    SetUncertainRetryAction();
            }
            _reauthAccountId = null;
            _reauthDraft = null;
            _reauthUncertainText = null;
            _busy = false;
            SetBusy(false);
            ShowThread();
            Composer.Focus();
        }
        catch (Exception error)
        {
            if (IsCurrentActivation(generation))
                SetStatus(ErrorText(error), isError: true);
        }
        finally
        {
            if (IsCurrentActivation(generation))
            {
                _busy = false;
                SetBusy(false);
            }
        }
    }

    private void ShowSignedOutState(Exception? sessionError = null)
    {
        _refreshTimer.Stop();
        MessageScroll.IsVisible = false;
        SignedOutPanel.IsVisible = true;
        ComposerCard.IsVisible = false;
        RetryButton.IsVisible = false;
        RefreshButton.IsEnabled = false;
        SignInButton.IsVisible = true;
        var message = sessionError is null
            ? T("Подключите Telegram, чтобы открыть историю обращений.", "Connect Telegram to open your ticket history.")
            : ErrorText(sessionError);
        SignInDescription.Text = message;
        SetStatus(message, isError: sessionError is not null);
    }

    private bool ShowReauthenticationIfNeeded(Exception error)
    {
        if (error is not WindowsSupportException supportError ||
            (supportError.StatusCode != HttpStatusCode.Unauthorized &&
             supportError.Code is not ("session_invalid" or "unauthorized" or "invalid_session" or
                 "session_expired" or "auth_required")))
            return false;

        if (_accountId.HasValue && !string.IsNullOrWhiteSpace(Composer.Text))
        {
            _reauthAccountId = _accountId;
            _reauthDraft = Composer.Text;
            _reauthUncertainText = _uncertainSendText;
        }
        else
        {
            _reauthAccountId = null;
            _reauthDraft = null;
            _reauthUncertainText = null;
        }
        Composer.Text = string.Empty;
        _accessToken = null;
        _ticket = null;
        MessageStack.Children.Clear();
        CloseTicketButton.IsVisible = false;
        _refreshTimer.Stop();
        if (_page == SupportPage.Thread)
            ShowSignedOutState(error);
        return true;
    }

    private void ShowThread()
    {
        _documentLoad?.Cancel();
        _page = SupportPage.Thread;
        SupportPane.IsVisible = true;
        DocumentPane.IsVisible = false;
        SetCopy();
        if (_accessToken is null)
            ShowSignedOutState();
        else
        {
            MessageScroll.IsVisible = true;
            SignedOutPanel.IsVisible = false;
            ComposerCard.IsVisible = true;
            RetryButton.IsVisible = true;
            if (IsActive)
            {
                RefreshButton.IsEnabled = true;
                _ = RefreshThreadAsync();
                _refreshTimer.Start();
            }
        }
    }

    private async Task ShowDocumentAsync(SupportDocumentKind kind)
    {
        _page = kind == SupportDocumentKind.Terms ? SupportPage.Terms : SupportPage.Privacy;
        SupportPane.IsVisible = false;
        DocumentPane.IsVisible = true;
        RefreshButton.IsEnabled = true;
        _refreshTimer.Stop();
        SetCopy();
        DocumentTitle.Text = kind == SupportDocumentKind.Terms
            ? T("Условия использования", "Terms of use")
            : T("Конфиденциальность", "Privacy");
        if (IsActive)
            await LoadDocumentAsync(kind);
    }

    private async Task LoadDocumentAsync(SupportDocumentKind kind)
    {
        if (!IsActive || _page == SupportPage.Thread)
            return;

        var request = CancellationTokenSource.CreateLinkedTokenSource(ActiveToken);
        _documentLoad?.Cancel();
        _documentLoad = request;
        DocumentStack.Children.Clear();
        DocumentRetryButton.IsEnabled = false;
        DocumentRetryButton.IsVisible = false;
        DocumentStatus.Text = T("Загружаем актуальный документ с deytt.space…", "Loading the current document from deytt.space…");
        DocumentStatus.Foreground = DeyttTheme.Brush(DeyttTheme.Muted);
        try
        {
            var document = await _client.GetDocumentAsync(kind, request.Token);
            if (!IsCurrentDocumentLoad(request, kind))
                return;

            var flowDocument = new FlowDocument
            {
                FontSize = 14,
                FontFamily = DeyttTheme.InterTight,
                Foreground = DeyttTheme.Brush(DeyttTheme.Text),
                Background = DeyttTheme.Brush(DeyttTheme.Surface),
                PagePadding = new Thickness(0),
            };
            foreach (var block in document.Blocks)
            {
                var paragraph = new Paragraph
                {
                    FontSize = block.IsHeading ? 18 : 14,
                    FontWeight = block.IsHeading ? FontWeight.SemiBold : FontWeight.Normal,
                    Foreground = DeyttTheme.Brush(block.IsHeading ? DeyttTheme.Sky : DeyttTheme.Text),
                    Margin = new Thickness(0, 0, 0, 10),
                };
                foreach (var inline in block.Inlines)
                {
                    if (inline.NavigateUri is { } uri)
                    {
                        var hyperlink = new RichHyperlink(new RichRun(inline.Text))
                        {
                            NavigateUri = uri,
                            Foreground = DeyttTheme.Brush(DeyttTheme.Sky),
                        };
                        hyperlink.RequestNavigate += HandleDocumentLinkRequest;
                        paragraph.Inlines.Add(hyperlink);
                    }
                    else
                    {
                        paragraph.Inlines.Add(new RichRun(inline.Text));
                    }
                }
                flowDocument.Blocks.Add(paragraph);
            }
            if (document.Blocks.Count == 0)
                throw new WindowsSupportException("document_empty", HttpStatusCode.BadGateway);
            DocumentStack.Children.Add(new FlowDocumentScrollViewer
            {
                Document = flowDocument,
                Background = DeyttTheme.Brush(DeyttTheme.Surface),
                HorizontalAlignment = HorizontalAlignment.Stretch,
            });
            DocumentStatus.Text = T("Официальный текст · источник: deytt.space/info/", "Official text · source: deytt.space/info/");
            DocumentRetryButton.IsVisible = false;
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception error)
        {
            if (!IsCurrentDocumentLoad(request, kind))
                return;
            DocumentStack.Children.Clear();
            DocumentStatus.Text = ErrorText(error) + " · " + T("нажмите, чтобы повторить", "click to retry");
            DocumentStatus.Foreground = DeyttTheme.Brush(DeyttTheme.Coral);
            DocumentRetryButton.IsVisible = true;
        }
        finally
        {
            if (ReferenceEquals(_documentLoad, request))
            {
                _documentLoad = null;
                DocumentRetryButton.IsEnabled = true;
            }
            request.Dispose();
        }
    }

    private void HandleDocumentLinkRequest(object? sender, RequestNavigateEventArgs args)
    {
        args.Handled = true;
        var uri = args.Uri;
        if (uri is null ||
            !uri.IsAbsoluteUri ||
            !uri.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase) ||
            !string.IsNullOrEmpty(uri.UserInfo))
        {
            return;
        }

        try
        {
            Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
        }
        catch (Exception)
        {
            DocumentStatus.Text = T("Не удалось открыть ссылку.", "Could not open the link.");
            DocumentStatus.Foreground = DeyttTheme.Brush(DeyttTheme.Coral);
        }
    }

    private bool IsCurrentDocumentLoad(CancellationTokenSource request, SupportDocumentKind kind) =>
        IsActive &&
        ReferenceEquals(_documentLoad, request) &&
        !request.IsCancellationRequested &&
        _page == (kind == SupportDocumentKind.Terms ? SupportPage.Terms : SupportPage.Privacy);

    private SupportDocumentKind CurrentDocumentKind() =>
        _page == SupportPage.Privacy ? SupportDocumentKind.Privacy : SupportDocumentKind.Terms;

    private void SetStatus(string text, bool isError = false)
    {
        StatusText.Text = text;
        StatusText.Foreground = DeyttTheme.Brush(isError ? DeyttTheme.Coral : DeyttTheme.Muted);
        StatusDot.Fill = DeyttTheme.Brush(isError ? DeyttTheme.Coral : _busy ? DeyttTheme.Amber : DeyttTheme.Mint);
    }

    private void SetBusy(bool busy)
    {
        SendButton.IsEnabled = !busy && _accessToken is not null;
        CloseTicketButton.IsEnabled = !busy;
        RetryButton.IsEnabled = !busy;
        RefreshButton.IsEnabled = !busy && (_page != SupportPage.Thread || _accessToken is not null);
        SignInButton.IsEnabled = !busy;
        DocumentRetryButton.IsEnabled = !busy;
        Composer.IsEnabled = !busy && _accessToken is not null;
        StatusDot.Fill = DeyttTheme.Brush(busy ? DeyttTheme.Amber : DeyttTheme.Muted);
    }

    private void SetRetryAction(bool retrySend)
    {
        _retrySend = retrySend;
        _retryNeedsConfirmation = false;
        RetryButton.Content = retrySend
            ? T("Отправить ещё раз", "Retry sending")
            : T("Повторить", "Retry");
    }

    private async Task<bool> ConfirmAsync(string title, string message, string confirmLabel)
    {
        return await _confirmAsync(title, message, confirmLabel);
    }

    private void OnKeyDown(object? sender, KeyEventArgs args)
    {
        if (args.Key == Key.Escape)
        {
            BackRequested?.Invoke(this, EventArgs.Empty);
            args.Handled = true;
        }
        else if (args.Key == Key.Enter && args.KeyModifiers.HasFlag(KeyModifiers.Control) &&
                 _page == SupportPage.Thread && _accessToken is not null)
        {
            _ = SendMessageAsync();
            args.Handled = true;
        }
    }

    private string ErrorText(Exception error)
    {
        if (error is WindowsSupportException { StatusCode: HttpStatusCode.Unauthorized })
            return T("Сессия Telegram истекла. Подключите Telegram повторно.",
                "Your Telegram session expired. Connect Telegram again.");

        var code = (error as WindowsSupportException)?.Code;
        return code switch
        {
            "session_invalid" or "unauthorized" or "invalid_session" or "session_expired" or "auth_required" =>
                T("Сессия Telegram истекла. Подключите Telegram повторно.", "Your Telegram session expired. Connect Telegram again."),
            "ticket_not_open" => T("Обращение уже закрыто. Обновите переписку.", "This ticket is closed. Refresh the conversation."),
            "rate_limited" => T("Слишком много запросов. Попробуйте позже.", "Too many requests. Try again later."),
            "message_length_invalid" => T("Сообщение должно содержать от 5 до 4000 символов.", "Messages must contain 5 to 4,000 characters."),
            "document_missing" or "document_empty" or "document_unavailable" or "invalid_document" =>
                T("Не удалось загрузить официальный документ.", "Could not load the official document."),
            _ => T("Не удалось выполнить запрос. Проверьте подключение и повторите попытку.",
                "The request failed. Check your connection and try again."),
        };
    }

    private string T(string russian, string english) => IsRussian ? russian : english;
}
