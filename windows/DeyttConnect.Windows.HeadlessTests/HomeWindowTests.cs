using Avalonia.Controls;
using Avalonia.Automation;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;
using Avalonia.Input;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Protocol;
using DeyttConnect.Windows;
using DeyttConnect.Windows.Views;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class HomeWindowTests
{
    [AvaloniaTheory]
    [InlineData(720d, true)]
    [InlineData(1360d, false)]
    public void Home_navigation_and_controls_remain_reachable_at_supported_widths(double width, bool compact)
    {
        var window = new MainWindow(Fixture(tab: "settings")) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var sidebar = Required<Control>(window, "SidebarPanel");
            var bottomNavigation = Required<Control>(window, "BottomNavigationPanel");
            Assert.Equal(!compact, sidebar.IsVisible);
            Assert.Equal(compact, bottomNavigation.IsVisible);

            var homeButton = Required<Button>(window, compact ? "BottomHomeNav" : "HomeNav");
            Assert.True(homeButton.IsEffectivelyVisible);
            Assert.True(homeButton.Focus());
            Assert.True(homeButton.IsFocused);

            window.KeyReleaseQwerty(PhysicalKey.Space, RawInputModifiers.None);
            Dispatcher.UIThread.RunJobs();

            Assert.Equal("Ваше подключение", Required<TextBlock>(window, "WorkspaceTitle").Text);
            var pageHost = Required<Grid>(window, "PageHost");
            Assert.Single(pageHost.Children);
            Assert.Contains(Descendants(pageHost).OfType<Button>(), button => button.IsEffectivelyVisible && button.IsEnabled);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d, 156d, 48d)]
    [InlineData(1360d, 0d, 56d)]
    public void Home_connection_action_keeps_a_usable_hit_target(double width, double expectedMinWidth, double expectedHeight)
    {
        var window = new MainWindow(Fixture()) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var action = Assert.Single(Descendants(Required<Grid>(window, "PageHost"))
                .OfType<Button>(), button => button.Content is TextBlock text && text.Text == "Войти через Telegram");
            var hitTarget = Assert.IsType<Border>(action.GetVisualParent());

            Assert.True(action.IsEffectivelyVisible);
            Assert.True(action.IsEnabled);
            Assert.Equal(expectedMinWidth, hitTarget.MinWidth);
            Assert.Equal(expectedHeight, hitTarget.Height);
            Assert.True(action.Focus());
            Assert.True(action.IsFocused);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Route_progress_only_renders_when_content_changes_and_restores_keyboard_focus(double width)
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var pageHost = Required<Grid>(window, "PageHost");
            var focusableButtons = Descendants(pageHost).OfType<Button>()
                .Where(button => button.IsEnabled && button.IsEffectivelyVisible).ToArray();
            Assert.NotEmpty(focusableButtons);
            var focusIndex = Math.Min(2, focusableButtons.Length - 1);
            Assert.True(focusableButtons[focusIndex].Focus());
            Dispatcher.UIThread.RunJobs();

            var snapshot = new WindowsRouteProbeResult("qa:nl-vless", null, null, null, "retry", 2);
            Assert.True(window.ApplyProbeProgress([snapshot], [snapshot.RouteTag]));
            Dispatcher.UIThread.RunJobs();
            var changedPage = Assert.Single(pageHost.Children);
            var focusedAfterChange = Assert.IsType<Button>(window.FocusManager?.GetFocusedElement());
            var buttonsAfterChange = Descendants(pageHost).OfType<Button>()
                .Where(button => button.IsEnabled && button.IsEffectivelyVisible).ToArray();
            Assert.True(buttonsAfterChange[focusIndex].IsFocused);

            Assert.False(window.ApplyProbeProgress([snapshot], [snapshot.RouteTag]));
            Assert.Same(changedPage, Assert.Single(pageHost.Children));
            Assert.Same(focusedAfterChange, window.FocusManager?.GetFocusedElement());
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Stop_button_cancel_transition_moves_focus_to_nearest_enabled_control(double width)
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true, routeProbeInProgress: true))
        {
            Width = width,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var stopButton = Descendants(Required<Grid>(window, "PageHost")).OfType<Button>()
                .Single(button => AutomationProperties.GetAutomationId(button) == "RouteDiagnosticsAction");
            Assert.Equal("Остановить проверку", Assert.IsType<TextBlock>(stopButton.Content).Text);
            Assert.True(stopButton.IsEnabled);
            Assert.True(stopButton.Focus());
            Dispatcher.UIThread.RunJobs();

            window.MarkProbeCancellationRequested();
            Dispatcher.UIThread.RunJobs();

            var stoppingButton = Descendants(Required<Grid>(window, "PageHost")).OfType<Button>()
                .Single(button => AutomationProperties.GetAutomationId(button) == "RouteDiagnosticsAction");
            Assert.Equal("Останавливаем…", Assert.IsType<TextBlock>(stoppingButton.Content).Text);
            Assert.False(stoppingButton.IsEffectivelyEnabled);
            var focused = Assert.IsType<Button>(window.FocusManager?.GetFocusedElement());
            Assert.NotSame(stoppingButton, focused);
            Assert.True(focused.IsEffectivelyEnabled);
            Assert.True(focused.IsEffectivelyVisible);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d, "BottomHomeNav")]
    [InlineData(1360d, "HomeNav")]
    public void Pending_route_focus_restore_does_not_override_navigation(double width, string homeNavigationName)
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var snapshot = new WindowsRouteProbeResult("qa:nl-vless", null, null, null, "retry", 2);
            Assert.True(window.ApplyProbeProgress([snapshot], [snapshot.RouteTag]));

            var homeNavigation = Required<Button>(window, homeNavigationName);
            Assert.True(homeNavigation.Focus());
            window.KeyReleaseQwerty(PhysicalKey.Space, RawInputModifiers.None);
            Dispatcher.UIThread.RunJobs();

            Assert.Equal("Ваше подключение", Required<TextBlock>(window, "WorkspaceTitle").Text);
            Assert.Same(homeNavigation, window.FocusManager?.GetFocusedElement());
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Unlimited_profile_shows_total_transferred_without_quota_usage_or_percentage(double width)
    {
        var subscription = ProfileSubscription(trafficLimit: null, trafficUsed: 64L * 1024 * 1024,
            trafficTotal: 128L * 1024 * 1024);
        var window = new MainWindow(Fixture(tab: "profile", signedIn: true, profileSubscription: subscription))
        {
            Width = width,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var summary = ProfileSummary(window);
            Assert.Contains("передано: 128 MB", summary);
            Assert.Contains("без установленного лимита", summary);
            Assert.DoesNotContain("64 MB /", summary);
            Assert.DoesNotContain('%', summary);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Unlimited_profile_hides_transfer_line_when_usage_is_zero(double width)
    {
        var subscription = ProfileSubscription(trafficLimit: null, trafficUsed: 0, trafficTotal: null);
        var window = new MainWindow(Fixture(tab: "profile", signedIn: true, profileSubscription: subscription))
        {
            Width = width,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var summary = ProfileSummary(window);
            Assert.DoesNotContain("передано:", summary);
            Assert.DoesNotContain("без установленного лимита", summary);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Limited_profile_keeps_used_over_limit_summary(double width)
    {
        var subscription = ProfileSubscription(trafficLimit: 1024L * 1024 * 1024,
            trafficUsed: 512L * 1024 * 1024, trafficTotal: null);
        var window = new MainWindow(Fixture(tab: "profile", signedIn: true, profileSubscription: subscription))
        {
            Width = width,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var summary = ProfileSummary(window);
            Assert.Contains("512 MB / 1 GB", summary);
            Assert.DoesNotContain("без установленного лимита", summary);
            Assert.DoesNotContain('%', summary);
        }
        finally
        {
            window.Close();
        }
    }

    private static QaHomeFixture Fixture(string tab = "home", bool signedIn = false,
        bool routeProbeInProgress = false, TelegramSubscription? profileSubscription = null)
    {
        var routes = new[]
        {
            new WindowsRoute("auto", "qa:auto", "AUTO", "Автоподбор", "✦", "AUTO", "Автоподбор"),
            new WindowsRoute("nl-vless", "qa:nl-vless", "NL", "Нидерланды", "🇳🇱", "VLESS", "VLESS"),
        };
        var account = signedIn
            ? new TelegramAccount("qa_fixture", "QA Demo", false,
                profileSubscription ?? ProfileSubscription(trafficLimit: null, trafficUsed: null, trafficTotal: null))
            : null;
        var subscription = signedIn
            ? new TelegramKeysSnapshot(true, false, 0,
                [new TelegramHappDevice(0, false, "QA fixture", "Synthetic desktop", "now")],
                "{\"qa_fixture\":true}", routes, [])
            : null;
        return new QaHomeFixture(signedIn, "ru", "auto", tab, account, subscription, routes,
            new WindowsTunnelSnapshot("disconnected", "Synthetic only"),
            RouteProbeInProgress: routeProbeInProgress);
    }

    private static TelegramSubscription ProfileSubscription(long? trafficLimit, long? trafficUsed, long? trafficTotal) =>
        new(true, true, "qa", "QA Demo", "2099-12-31T00:00:00Z",
            false, 3, 1, trafficLimit, trafficUsed)
        {
            TrafficTotalBytes = trafficTotal,
        };

    private static string ProfileSummary(MainWindow window) => string.Join(" ",
        Descendants(Required<Grid>(window, "PageHost")).OfType<TextBlock>().Select(text => text.Text));

    private static T Required<T>(Control control, string name) where T : Control =>
        control.FindControl<T>(name) ?? throw new Xunit.Sdk.XunitException($"Missing control: {name}");

    private static IEnumerable<Control> Descendants(Control root)
    {
        foreach (var child in root.GetVisualChildren().OfType<Control>())
        {
            yield return child;
            foreach (var descendant in Descendants(child))
                yield return descendant;
        }
    }
}
