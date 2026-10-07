using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;
using Avalonia.Interactivity;
using Avalonia.Input;
using Avalonia.Threading;
using Avalonia.VisualTree;
using System.Net;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Views;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class SupportWindowTests
{
    private const string Draft = "Please help me";

    [AvaloniaFact]
    public void Successful_send_and_refresh_clears_draft_and_shows_thread()
    {
        var client = new FakeSupportClient();
        using var fixture = new Fixture(client);

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, client.SendCount);
        Assert.Equal(2, client.ThreadReadCount);
        Assert.Equal(string.Empty, fixture.Composer.Text);
        Assert.Equal("обращение №42 · открыто", fixture.Status.Text);
    }

    [AvaloniaTheory]
    [InlineData("ru", "сообщение отправлено, историю обновить не удалось. нажмите «обновить».", "повторить", "обращение №42 · открыто")]
    [InlineData("en", "message sent, but conversation could not be refreshed. press refresh.", "retry", "ticket #42 · open")]
    public void Sent_message_with_failed_refresh_is_confirmed_and_retry_refresh_does_not_resend(
        string language, string expectedStatus, string expectedRetryText, string expectedThreadStatus)
    {
        var client = new FakeSupportClient { FailSecondThreadRead = true };
        using var fixture = new Fixture(client, language: language);

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, client.SendCount);
        Assert.Equal(2, client.ThreadReadCount);
        Assert.Equal(string.Empty, fixture.Composer.Text);
        Assert.Equal(expectedStatus, fixture.Status.Text);
        Assert.Equal(expectedRetryText, fixture.RetryButton.Content);

        fixture.RetryButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, client.SendCount);
        Assert.Equal(3, client.ThreadReadCount);
        Assert.Equal(expectedThreadStatus, fixture.Status.Text);
    }

    [AvaloniaFact]
    public void Failed_send_keeps_draft_and_retry_action_resends_it()
    {
        var client = new FakeSupportClient { FailFirstSend = true };
        using var fixture = new Fixture(client);

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, client.SendCount);
        Assert.Equal(Draft, fixture.Composer.Text);
        Assert.Contains("черновик сохранён", fixture.Status.Text);
        Assert.Equal("отправить ещё раз", fixture.RetryButton.Content);

        fixture.RetryButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(2, client.SendCount);
        Assert.Equal(string.Empty, fixture.Composer.Text);
        Assert.Equal(2, client.ThreadReadCount);
        Assert.Equal("обращение №42 · открыто", fixture.Status.Text);
    }

    [AvaloniaFact]
    public void Ambiguous_send_is_reconciled_from_history_without_retry_prompt()
    {
        var client = new FakeSupportClient { AcceptBeforeAmbiguousFailure = true };
        using var fixture = new Fixture(client);

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, client.SendCount);
        Assert.Equal(string.Empty, fixture.Composer.Text);
        Assert.Equal("сообщение отправлено.", fixture.Status.Text);
        Assert.Equal("повторить", fixture.RetryButton.Content);
    }

    [AvaloniaFact]
    public void Ambiguous_send_requires_confirmation_when_history_cannot_confirm_it()
    {
        var client = new FakeSupportClient { FailAmbiguousFirstSend = true };
        var confirmations = 0;
        using var fixture = new Fixture(client, confirm: (_, _, _) =>
        {
            confirmations++;
            return Task.FromResult(false);
        });

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();
        Assert.Equal(Draft, fixture.Composer.Text);
        Assert.Contains("могло дойти", fixture.Status.Text);
        Assert.Equal("подтвердить повтор…", fixture.RetryButton.Content);

        fixture.RetryButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, client.SendCount);
        Assert.Equal(1, confirmations);
    }

    [AvaloniaFact]
    public void Manual_refresh_can_later_resolve_an_uncertain_send_without_resending()
    {
        var client = new FakeSupportClient { FailAmbiguousFirstSend = true, AppearOnThirdThreadRead = true };
        using var fixture = new Fixture(client);

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();
        Assert.Equal(Draft, fixture.Composer.Text);

        fixture.RefreshButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, client.SendCount);
        Assert.Equal(string.Empty, fixture.Composer.Text);
        Assert.Equal("сообщение отправлено.", fixture.Status.Text);
    }

    [AvaloniaTheory]
    [InlineData(742001L, true)]
    [InlineData(742002L, false)]
    public void Reauthentication_restores_draft_only_for_confirmed_same_account(long newAccountId, bool shouldRestore)
    {
        var client = new FakeSupportClient { ExpireFirstSend = true, AccountId = 742001 };
        using var fixture = new Fixture(client, signIn: () =>
        {
            client.AccountId = newAccountId;
            return Task.FromResult<string?>("qa-support-token-2");
        });

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();
        Assert.Equal(string.Empty, fixture.Composer.Text);

        fixture.SignInButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(shouldRestore ? Draft : string.Empty, fixture.Composer.Text);
    }

    [AvaloniaFact]
    public void Unauthorized_reconciliation_keeps_uncertain_marker_through_same_account_reauth()
    {
        var client = new FakeSupportClient { FailAmbiguousFirstSend = true, UnauthorizedSecondThreadRead = true };
        var confirmations = 0;
        using var fixture = new Fixture(client, confirm: (_, _, _) =>
        {
            confirmations++;
            return Task.FromResult(false);
        }, signIn: static () => Task.FromResult<string?>("qa-support-token-2"));

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();
        Assert.Equal(string.Empty, fixture.Composer.Text);

        fixture.SignInButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();
        Assert.Equal(Draft, fixture.Composer.Text);
        Assert.Contains("могло дойти", fixture.Status.Text);

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, client.SendCount);
        Assert.Equal(1, confirmations);
    }

    [AvaloniaFact]
    public void Deactivate_during_post_marks_outcome_uncertain_before_same_account_retry()
    {
        var client = new FakeSupportClient { BlockFirstSendUntilCanceled = true };
        using var fixture = new Fixture(client, confirm: static (_, _, _) => Task.FromResult(false));

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();
        Assert.True(client.SendStarted.Task.IsCompleted);

        fixture.View.Deactivate();
        Dispatcher.UIThread.RunJobs();
        fixture.View.Activate("qa-support-token");
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(Draft, fixture.Composer.Text);
        Assert.Contains("могло дойти", fixture.Status.Text);
        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();
        Assert.Equal(1, client.SendCount);
    }

    [AvaloniaFact]
    public void Late_cancellation_after_account_switch_does_not_leak_uncertainty()
    {
        var client = new FakeSupportClient { DelayFirstSend = true, CancelFirstSendAfterRelease = true };
        var confirmations = 0;
        using var fixture = new Fixture(client, confirm: (_, _, _) =>
        {
            confirmations++;
            return Task.FromResult(false);
        });

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();
        fixture.View.Deactivate();
        client.AccountId = 742002;
        fixture.View.Activate("qa-support-token-account-b");
        Dispatcher.UIThread.RunJobs();

        client.ReleaseDelayedSend.TrySetResult(true);
        Dispatcher.UIThread.RunJobs();
        fixture.Composer.Text = "New account question";
        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(2, client.SendCount);
        Assert.Equal(0, confirmations);
        Assert.Equal(string.Empty, fixture.Composer.Text);
    }

    [AvaloniaFact]
    public void Late_success_after_deactivation_reconciles_before_retry_can_duplicate()
    {
        var client = new FakeSupportClient { DelayFirstSend = true };
        using var fixture = new Fixture(client);

        fixture.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Dispatcher.UIThread.RunJobs();
        fixture.View.Deactivate();
        fixture.View.Activate("qa-support-token");
        Dispatcher.UIThread.RunJobs();

        client.ReleaseDelayedSend.TrySetResult(true);
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, client.SendCount);
        Assert.Equal(string.Empty, fixture.Composer.Text);
        Assert.Equal("сообщение отправлено.", fixture.Status.Text);
        Assert.Equal("повторить", fixture.RetryButton.Content);
    }

    [AvaloniaFact]
    public void Compact_composer_keeps_send_button_reachable_and_keyboard_shortcuts()
    {
        using var fixture = new Fixture(new FakeSupportClient(), width: 720);
        var backRequested = 0;
        fixture.View.BackRequested += (_, _) => backRequested++;

        Assert.True(fixture.SendButton.IsEffectivelyVisible);
        Assert.True(fixture.SendButton.IsEnabled);
        Assert.True(fixture.Composer.Focus());
        fixture.View.RaiseEvent(new KeyEventArgs
        {
            RoutedEvent = InputElement.KeyDownEvent,
            Key = Key.Enter,
            KeyModifiers = KeyModifiers.Control,
        });
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, fixture.Client.SendCount);
        Assert.Equal(string.Empty, fixture.Composer.Text);

        fixture.View.RaiseEvent(new KeyEventArgs
        {
            RoutedEvent = InputElement.KeyDownEvent,
            Key = Key.Escape,
            KeyModifiers = KeyModifiers.None,
        });
        Dispatcher.UIThread.RunJobs();

        Assert.Equal(1, backRequested);
    }

    private sealed class Fixture : IDisposable
    {
        public Fixture(FakeSupportClient client, double width = 720, string language = "ru",
            Func<string, string, string, Task<bool>>? confirm = null,
            Func<Task<string?>>? signIn = null)
        {
            Client = client;
            View = new SupportView(
                "qa-support-token",
                signIn ?? (static () => Task.FromResult<string?>(null)),
                confirm ?? Confirm,
                language: language,
                client: client);
            Window = new Window { Content = View, Width = width, Height = 520 };
            Window.Show();
            View.Activate("qa-support-token");
            Dispatcher.UIThread.RunJobs();

            Composer = Required<TextBox>("Composer");
            SendButton = Required<Button>("SendButton");
            RetryButton = Required<Button>("RetryButton");
            RefreshButton = Required<Button>("RefreshButton");
            SignInButton = Required<Button>("SignInButton");
            Status = Required<TextBlock>("StatusText");
            Composer.Text = Draft;
        }

        public FakeSupportClient Client { get; }
        public SupportView View { get; }
        public Window Window { get; }
        public TextBox Composer { get; }
        public Button SendButton { get; }
        public Button RetryButton { get; }
        public Button RefreshButton { get; }
        public TextBlock Status { get; }
        public Button SignInButton { get; }
        public int ConfirmCount { get; private set; }

        private Task<bool> Confirm(string title, string message, string label)
        {
            ConfirmCount++;
            return Task.FromResult(true);
        }

        private T Required<T>(string name) where T : Control =>
            View.FindControl<T>(name) ?? throw new Xunit.Sdk.XunitException($"Missing support control: {name}");

        public void Dispose()
        {
            View.Dispose();
            Window.Close();
        }
    }

    private sealed class FakeSupportClient : IWindowsSupportClient
    {
        private readonly List<SupportMessage> _messages = [];
        private static readonly SupportTicket Ticket = new(42, IsOpen: true);

        public int ThreadReadCount { get; private set; }
        public int SendCount { get; private set; }
        public bool FailFirstSend { get; init; }
        public bool FailAmbiguousFirstSend { get; init; }
        public bool FailSecondThreadRead { get; init; }
        public bool AcceptBeforeAmbiguousFailure { get; init; }
        public bool AppearOnThirdThreadRead { get; init; }
        public bool ExpireFirstSend { get; init; }
        public bool UnauthorizedSecondThreadRead { get; init; }
        public bool BlockFirstSendUntilCanceled { get; init; }
        public bool DelayFirstSend { get; init; }
        public bool CancelFirstSendAfterRelease { get; init; }
        public TaskCompletionSource SendStarted { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource<bool> ReleaseDelayedSend { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public long AccountId { get; set; } = 742001;

        public Task<long> GetAccountIdAsync(string token, CancellationToken cancellationToken = default) => Task.FromResult(AccountId);

        public Task<SupportThreadSnapshot> GetThreadAsync(string token, CancellationToken cancellationToken = default)
        {
            ThreadReadCount++;
            if (AppearOnThirdThreadRead && ThreadReadCount == 3)
                _messages.Add(new SupportMessage("user", Draft));
            if (UnauthorizedSecondThreadRead && ThreadReadCount == 2)
                return Task.FromException<SupportThreadSnapshot>(
                    new WindowsSupportException("session_expired", HttpStatusCode.Unauthorized));
            if (FailSecondThreadRead && ThreadReadCount == 2)
                return Task.FromException<SupportThreadSnapshot>(new InvalidOperationException("offline read"));
            return Task.FromResult(new SupportThreadSnapshot(Ticket, _messages.ToArray()));
        }

        public Task CreateTicketAsync(string token, string text, CancellationToken cancellationToken = default) =>
            Task.CompletedTask;

        public async Task SendMessageAsync(string token, int ticketId, string text, CancellationToken cancellationToken = default)
        {
            SendCount++;
            if (BlockFirstSendUntilCanceled && SendCount == 1)
            {
                SendStarted.TrySetResult();
                await Task.Delay(Timeout.Infinite, cancellationToken);
            }
            if (DelayFirstSend && SendCount == 1)
            {
                SendStarted.TrySetResult();
                await ReleaseDelayedSend.Task;
                if (CancelFirstSendAfterRelease)
                    throw new OperationCanceledException("delayed cancellation");
            }
            if (ExpireFirstSend && SendCount == 1)
                throw new WindowsSupportException("session_expired", HttpStatusCode.Unauthorized);
            if (FailFirstSend && SendCount == 1)
                throw new WindowsSupportException("rate_limited", HttpStatusCode.TooManyRequests);
            if (FailAmbiguousFirstSend && SendCount == 1)
                throw new InvalidOperationException("offline send");
            if (AcceptBeforeAmbiguousFailure && SendCount == 1)
            {
                _messages.Add(new SupportMessage("user", text));
                throw new InvalidOperationException("response lost after commit");
            }
            _messages.Add(new SupportMessage("user", text));
        }

        public Task CloseTicketAsync(string token, int ticketId, CancellationToken cancellationToken = default) =>
            Task.CompletedTask;

        public Task<SupportDocument> GetDocumentAsync(SupportDocumentKind kind, CancellationToken cancellationToken = default) =>
            Task.FromResult(new SupportDocument(kind, []));
    }
}
