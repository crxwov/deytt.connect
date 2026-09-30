using Avalonia.Controls;
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

    private static QaHomeFixture Fixture(string tab = "home")
    {
        var routes = new[]
        {
            new WindowsRoute("auto", "qa:auto", "AUTO", "Автоподбор", "✦", "AUTO", "Автоподбор"),
            new WindowsRoute("nl-vless", "qa:nl-vless", "NL", "Нидерланды", "🇳🇱", "VLESS", "VLESS"),
        };
        return new QaHomeFixture(false, "ru", "auto", tab, null, null, routes,
            new WindowsTunnelSnapshot("disconnected", "Synthetic only"));
    }

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
