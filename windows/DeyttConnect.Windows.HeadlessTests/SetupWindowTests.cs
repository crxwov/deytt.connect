using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Views;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class SetupWindowTests
{
    [AvaloniaFact]
    public void FirstRunStartsWithUsernameAndSubscriptionLinkWithoutBackEscape()
    {
        var view = new SetupWindow(new SetupWindowOptions
        {
            AllowCancel = false,
            RestoreExistingSession = false,
            ImportUrlAsync = (_, _, _) => throw new InvalidOperationException("Unexpected import"),
        });
        var host = new Window { Content = view };
        var cancellations = 0;
        view.BackRequested += (_, _) => cancellations++;
        host.Show();
        try
        {
            var text = view.GetVisualDescendants().OfType<TextBlock>().Select(block => block.Text).ToArray();
            Assert.Contains("подключите ./connect", text);
            Assert.Contains("у меня есть ссылка подписки", text);
            Assert.DoesNotContain("← назад", text);
            var input = Assert.Single(view.GetVisualDescendants().OfType<TextBox>());
            Assert.Equal("@username", input.PlaceholderText);
            Assert.NotNull(Assert.Single(view.GetVisualDescendants().OfType<Image>()).Source);
            view.Cancel();
            view.RaiseEvent(new KeyEventArgs { RoutedEvent = InputElement.KeyDownEvent, Key = Key.Escape });
            Assert.Equal(0, cancellations);
            Assert.Null(view.Result);
            view.Abort();
            Assert.Equal(1, cancellations);
        }
        finally { host.Close(); }
    }

    [AvaloniaFact]
    public void ExistingAccountMayCancelReauthenticationOnce()
    {
        var view = new SetupWindow(new SetupWindowOptions { AllowCancel = true });
        var cancellations = 0;
        view.BackRequested += (_, _) => cancellations++;
        view.Cancel();
        view.Cancel();
        Assert.Equal(1, cancellations);
    }

    [AvaloniaFact]
    public void InvalidUsernameKeepsUserOnFormAndShowsUsefulValidation()
    {
        var view = new SetupWindow(new SetupWindowOptions { AllowCancel = false });
        var host = new Window { Content = view };
        host.Show();
        try
        {
            var input = Assert.Single(view.GetVisualDescendants().OfType<TextBox>());
            input.Text = "@@alice";
            var button = view.GetVisualDescendants().OfType<Button>().Single(candidate =>
                candidate.GetVisualDescendants().OfType<TextBlock>().Any(text => text.Text == "получить код"));
            button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            Assert.Contains(view.GetVisualDescendants().OfType<TextBlock>(), text =>
                text.Text?.Contains("от 5 до 32 латинских букв", StringComparison.Ordinal) == true);
            Assert.Equal("@@alice", input.Text);
        }
        finally { view.Abort(); host.Close(); }
    }

    [AvaloniaFact]
    public async Task FirstRunLinkImportCompletesOnlyAfterSuccessfulCommit()
    {
        const string link = "https://deytt.space/sub/token/abcdefghijklmnopqrstuvwxyz123456";
        var commits = 0;
        var completions = 0;
        var snapshot = new TelegramKeysSnapshot(true, false, 0, [], "{}",
            [new WindowsRoute("nl", "fixture:nl", "NL", "Нидерланды", "🇳🇱", "VLESS", "VLESS")], []);
        var view = new SetupWindow(new SetupWindowOptions
        {
            AllowCancel = false,
            RestoreExistingSession = false,
            InitialImportUrl = link,
            ImportUrlAsync = (_, _, _) => Task.FromResult(new SetupSubscriptionPayload(null, null, snapshot)),
            CommitImportAsync = (_, _) =>
            {
                commits++;
                if (commits == 1)
                    throw new IOException("fixture commit failure");
                return Task.CompletedTask;
            },
        });
        view.Completed += (_, _) => completions++;
        var host = new Window { Content = view };
        host.Show();
        try
        {
            await view.ActivateAsync();
            Dispatcher.UIThread.RunJobs();
            host.UpdateLayout();
            Assert.Null(view.Result);
            var button = view.GetVisualDescendants().OfType<Button>().Single(candidate =>
                candidate.GetVisualDescendants().OfType<TextBlock>().Any(text => text.Text == "импортировать подписку"));
            button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            Assert.Equal(1, commits);
            Assert.Equal(0, completions);
            Assert.Null(view.Result);
            button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            Assert.Equal(2, commits);
            Assert.Equal(1, completions);
            Assert.Equal(link, view.Result!.Payload.SubscriptionUrl);
        }
        finally { view.Abort(); host.Close(); }
    }

    [Theory]
    [InlineData("https://deytt.space/sub/token/abcdefghijklmnopqrstuvwxyz123456", true)]
    [InlineData("deytt.connect://import?url=https%3A%2F%2Fdeytt.space%2Fsub%2Ftoken%2Fabcdefghijklmnopqrstuvwxyz123456", true)]
    [InlineData("https://deytt.space.attacker.test/sub/token/abcdefghijklmnopqrstuvwxyz123456", false)]
    [InlineData("https://user@deytt.space/sub/token/abcdefghijklmnopqrstuvwxyz123456", false)]
    public void SubscriptionImportStillAcceptsOnlyFirstPartyLinks(string input, bool accepted)
    {
        Assert.Equal(accepted, SetupWindow.TryGetSubscriptionUrl(input, out _));
    }
}
