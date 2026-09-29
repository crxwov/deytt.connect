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

public partial class SupportWindow : Window
{
    private enum SupportPage { Thread, Terms, Privacy }

    private const int MaximumMessageLength = 4000;
    private static readonly TimeSpan RefreshInterval = TimeSpan.FromSeconds(30);
    private readonly WindowsSupportClient _client = new();
    private readonly DispatcherTimer _refreshTimer = new() { Interval = RefreshInterval };
    private readonly CancellationTokenSource _lifetime = new();
    private readonly Func<Task<string?>>? _onSignInRequested;
    private CancellationTokenSource? _documentLoad;
    private string? _accessToken;
    private string _language;
    private SupportTicket? _ticket;
    private bool _busy;
    private bool _closingPromptVisible;
    private bool _allowWindowClose;
    private SupportPage _page = SupportPage.Thread;

    public SupportWindow() : this(null)
    {
    }

    public SupportWindow(
        string? accessToken,
        Func<Task<string?>>? onSignInRequested = null,
        string language = "ru")
    {
        InitializeComponent();
        _accessToken = accessToken;
        _onSignInRequested = onSignInRequested;
        _language = language is "en" ? "en" : "ru";
        SetCopy();

        SupportNavButton.Click += (_, _) => ShowThread();
        TermsNavButton.Click += async (_, _) => await ShowDocumentAsync(SupportDocumentKind.Terms);
        PrivacyNavButton.Click += async (_, _) => await ShowDocumentAsync(SupportDocumentKind.Privacy);
        TelegramNavButton.Click += (_, _) => OpenTelegram();
        RefreshButton.Click += async (_, _) => await RefreshCurrentPageAsync();
        RetryButton.Click += async (_, _) => await RefreshThreadAsync();
        DocumentRetryButton.Click += async (_, _) => await LoadDocumentAsync(CurrentDocumentKind());
        SendButton.Click += async (_, _) => await ConfirmAndSendAsync();
        CloseTicketButton.Click += async (_, _) => await ConfirmAndCloseTicketAsync();
        SignInButton.Click += async (_, _) => await SignInAsync();
        WindowCloseButton.Click += (_, _) => Close();
        Composer.PropertyChanged += (_, args) =>
        {
            if (args.Property == TextBox.TextProperty)
                CharacterCount.Text = $"{Composer.Text?.Length ?? 0} / {MaximumMessageLength}";
        };
        Opened += async (_, _) =>
        {
            if (_accessToken is null)
            {
                ShowSignedOutState();
                SignInButton.Focus();
            }
            else
            {
                Composer.Focus();
                _ = RefreshThreadAsync();
                _refreshTimer.Start();
            }
        };
        _refreshTimer.Tick += async (_, _) => await RefreshThreadAsync();
        KeyDown += OnKeyDown;
        Closing += OnClosing;
        Closed += (_, _) =>
        {
            _refreshTimer.Stop();
            _lifetime.Cancel();
            _documentLoad?.Cancel();
            _lifetime.Dispose();
        };
    }

    private bool IsRussian => _language == "ru";

    private void SetCopy()
    {
        Title = T("DEYTT · Помощь и документы", "DEYTT · Help and documents");
        SidebarKicker.Text = T("ЦЕНТР ПОМОЩИ", "HELP CENTER");
        NavSectionTitle.Text = T("ПОМОЩЬ И ДОКУМЕНТЫ", "HELP AND DOCUMENTS");
        SourceText.Text = T("Текущие документы deytt.space", "Current documents from deytt.space");
        SupportNavButton.Content = NavContent("◉", T("Поддержка", "Support"), _page == SupportPage.Thread);
        TermsNavButton.Content = NavContent("§", T("Условия использования", "Terms of use"), _page == SupportPage.Terms);
        PrivacyNavButton.Content = NavContent("◇", T("Конфиденциальность", "Privacy"), _page == SupportPage.Privacy);
        TelegramNavButton.Content = NavContent("↗", T("Открыть Telegram", "Open Telegram"), false);
        HeaderKicker.Text = _page == SupportPage.Thread ? "DEYTT · SUPPORT" : "DEYTT · DOCUMENTS";
        HeaderTitle.Text = _page switch
        {
            SupportPage.Terms => T("Условия использования", "Terms of use"),
            SupportPage.Privacy => T("Конфиденциальность", "Privacy"),
            _ => T("Поддержка", "Support"),
        };
        RefreshButton.Content = T("Обновить", "Refresh");
        WindowCloseButton.Content = T("Закрыть", "Close");
        RetryButton.Content = T("Повторить", "Retry");
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
        if (_busy || _accessToken is null || _page != SupportPage.Thread || _lifetime.IsCancellationRequested)
            return;
        _busy = true;
        SetStatus(T("Загружаем переписку…", "Loading conversation…"));
        SetBusy(true);
        try
        {
            var snapshot = await _client.GetThreadAsync(_accessToken, _lifetime.Token);
            _ticket = snapshot.Ticket;
            RenderMessages(snapshot.Messages);
            CloseTicketButton.IsVisible = _ticket?.IsOpen == true;
            var status = _ticket switch
            {
                null => T("Новое обращение создастся после первого сообщения.", "A new ticket will be created with your first message."),
                { IsOpen: true } => T($"Обращение №{_ticket.Id} · открыто", $"Ticket #{_ticket.Id} · open"),
                _ => T("Обращение закрыто. Новое сообщение откроет новое обращение.", "This ticket is closed. A new message will create a new ticket."),
            };
            SetStatus(status);
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            if (!ShowReauthenticationIfNeeded(error))
                SetStatus(ErrorText(error) + " · " + T("нажмите, чтобы повторить", "click to retry"), isError: true);
        }
        finally
        {
            _busy = false;
            SetBusy(false);
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

    private async Task ConfirmAndSendAsync()
    {
        var text = Composer.Text?.Trim() ?? string.Empty;
        if (text.Length < 5)
        {
            SetStatus(T("Напишите сообщение минимум из пяти символов.", "Write at least five characters."), isError: true);
            Composer.Focus();
            return;
        }
        if (text.Length > MaximumMessageLength || _busy || _accessToken is null)
            return;

        var confirmed = await ConfirmAsync(
            T("Отправить сообщение?", "Send this message?"),
            T("Сообщение будет отправлено команде поддержки.", "This message will be sent to the support team."),
            T("Отправить", "Send"));
        if (!confirmed || _accessToken is null || _lifetime.IsCancellationRequested)
            return;

        _busy = true;
        SetBusy(true);
        SetStatus(T("Отправляем сообщение…", "Sending message…"));
        try
        {
            var ticket = _ticket;
            if (ticket?.IsOpen == true)
                await _client.SendMessageAsync(_accessToken, ticket.Id, text, _lifetime.Token);
            else
                await _client.CreateTicketAsync(_accessToken, text, _lifetime.Token);
            Composer.Text = string.Empty;
            await RefreshThreadAsyncWhileBusy();
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            if (!ShowReauthenticationIfNeeded(error))
                SetStatus(ErrorText(error) + " · " + T("нажмите, чтобы повторить", "click to retry"), isError: true);
        }
        finally
        {
            _busy = false;
            SetBusy(false);
        }
    }

    private async Task RefreshThreadAsyncWhileBusy()
    {
        if (_accessToken is null || _lifetime.IsCancellationRequested)
            return;
        try
        {
            var snapshot = await _client.GetThreadAsync(_accessToken, _lifetime.Token);
            _ticket = snapshot.Ticket;
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
            if (!ShowReauthenticationIfNeeded(error))
                SetStatus(ErrorText(error) + " · " + T("нажмите, чтобы повторить", "click to retry"), isError: true);
        }
    }

    private async Task ConfirmAndCloseTicketAsync()
    {
        if (_ticket is not { IsOpen: true } ticket || _accessToken is null || _busy)
            return;
        var confirmed = await ConfirmAsync(
            T("Закрыть обращение?", "Close this ticket?"),
            T("Новые ответы не будут приниматься. При необходимости вы сможете создать новое обращение.",
                "New replies will no longer be accepted. You can create another ticket when needed."),
            T("Закрыть обращение", "Close ticket"));
        if (!confirmed || _accessToken is null || _lifetime.IsCancellationRequested)
            return;

        _busy = true;
        SetBusy(true);
        SetStatus(T("Закрываем обращение…", "Closing ticket…"));
        try
        {
            await _client.CloseTicketAsync(_accessToken, ticket.Id, _lifetime.Token);
            await RefreshThreadAsyncWhileBusy();
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            if (!ShowReauthenticationIfNeeded(error))
                SetStatus(ErrorText(error) + " · " + T("нажмите, чтобы повторить", "click to retry"), isError: true);
        }
        finally
        {
            _busy = false;
            SetBusy(false);
        }
    }

    private async Task SignInAsync()
    {
        if (_onSignInRequested is null || _busy)
            return;
        _busy = true;
        SetBusy(true);
        try
        {
            _accessToken = await _onSignInRequested();
            if (_accessToken is null)
            {
                SetStatus(T("Подключите Telegram, чтобы открыть поддержку.", "Connect Telegram to use support."));
                return;
            }
            _busy = false;
            SetBusy(false);
            ShowThread();
            Composer.Focus();
            _refreshTimer.Start();
        }
        catch (Exception error)
        {
            SetStatus(ErrorText(error), isError: true);
        }
        finally
        {
            _busy = false;
            SetBusy(false);
        }
    }

    private void ShowSignedOutState(Exception? sessionError = null)
    {
        MessageScroll.IsVisible = false;
        SignedOutPanel.IsVisible = true;
        ComposerCard.IsVisible = false;
        RetryButton.IsVisible = false;
        RefreshButton.IsEnabled = false;
        SignInButton.IsVisible = _onSignInRequested is not null;
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

        _accessToken = null;
        _ticket = null;
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
            RefreshButton.IsEnabled = true;
            _ = RefreshThreadAsync();
            _refreshTimer.Start();
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
        await LoadDocumentAsync(kind);
    }

    private async Task LoadDocumentAsync(SupportDocumentKind kind)
    {
        if (_lifetime.IsCancellationRequested)
            return;

        var request = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
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
        SignInButton.IsEnabled = !busy && _onSignInRequested is not null;
        DocumentRetryButton.IsEnabled = !busy;
        Composer.IsEnabled = !busy && _accessToken is not null;
        StatusDot.Fill = DeyttTheme.Brush(busy ? DeyttTheme.Amber : DeyttTheme.Muted);
    }

    private async Task<bool> ConfirmAsync(string title, string message, string confirmLabel)
    {
        var dialog = new Window
        {
            Title = title,
            Width = 440,
            SizeToContent = SizeToContent.Height,
            MinHeight = 210,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
            RequestedThemeVariant = Avalonia.Styling.ThemeVariant.Dark,
        };
        var cancelButton = new Button
        {
            Content = T("Отмена", "Cancel"),
            MinWidth = 100,
            Padding = new Thickness(13, 9),
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(11),
            IsCancel = true,
        };
        var confirmButton = new Button
        {
            Content = confirmLabel,
            MinWidth = 120,
            Padding = new Thickness(13, 9),
            Background = DeyttTheme.Brush(DeyttTheme.SkySurface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Sky),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(11),
            IsDefault = true,
        };
        cancelButton.Click += (_, _) => dialog.Close(false);
        confirmButton.Click += (_, _) => dialog.Close(true);
        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            Spacing = 9,
            Children = { cancelButton, confirmButton },
        };
        dialog.Content = new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.Surface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(21),
            Padding = new Thickness(24),
            Child = new StackPanel
            {
                Spacing = 17,
                Children =
                {
                    DeyttTheme.TextBlock(title, 19, DeyttTheme.Text, FontWeight.SemiBold),
                    DeyttTheme.TextBlock(message, 14, DeyttTheme.Muted),
                    buttons,
                },
            },
        };
        return await dialog.ShowDialog<bool>(this);
    }

    private void OnKeyDown(object? sender, KeyEventArgs args)
    {
        if (args.Key == Key.Escape)
        {
            Close();
            args.Handled = true;
        }
        else if (args.Key == Key.Enter && args.KeyModifiers.HasFlag(KeyModifiers.Control) &&
                 _page == SupportPage.Thread && _accessToken is not null)
        {
            _ = ConfirmAndSendAsync();
            args.Handled = true;
        }
    }

    private async void OnClosing(object? sender, WindowClosingEventArgs args)
    {
        if (_allowWindowClose)
            return;
        args.Cancel = true;
        if (_closingPromptVisible)
            return;
        _closingPromptVisible = true;
        try
        {
            var draft = Composer.Text?.Trim();
            var canClose = string.IsNullOrEmpty(draft) || await ConfirmAsync(
                T("Закрыть окно?", "Close this window?"),
                T("Черновик сообщения будет удалён.", "Your unsent message draft will be discarded."),
                T("Закрыть окно", "Close window"));
            if (canClose)
            {
                _allowWindowClose = true;
                Close();
            }
        }
        finally
        {
            _closingPromptVisible = false;
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
