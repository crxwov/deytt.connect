using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Controls;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Views;
using System.Reflection;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class NavigationLifecycleTests
{
    [AvaloniaFact]
    public void Home_globe_is_retained_and_host_activity_tracks_repeated_navigation()
    {
        var window = CreateWindow();
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var pageHost = window.FindControl<Grid>("PageHost")!;
            var globe = Assert.Single(pageHost.GetVisualDescendants().OfType<RouteGlobeWebView>());
            var homePage = globe.Parent;
            Assert.True(globe.HostActive);

            for (var cycle = 0; cycle < 5; cycle++)
            {
                ShowTab(window, "Routes");
                Assert.False(globe.HostActive);
                Assert.Same(globe, Assert.Single(pageHost.GetVisualDescendants().OfType<RouteGlobeWebView>()));

                ShowTab(window, "Home");
                Assert.True(globe.HostActive);
                Assert.Same(globe, Assert.Single(pageHost.GetVisualDescendants().OfType<RouteGlobeWebView>()));
                Assert.Same(homePage, globe.Parent);
            }
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public async Task Cancelled_page_entrances_restore_usable_opacity_after_returning_home()
    {
        var window = CreateWindow();
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            ShowTab(window, "Routes");
            var routesPage = Assert.Single(window.FindControl<Grid>("PageHost")!.Children
                .OfType<Grid>().Where(page => page.DataContext is RoutesViewModel));

            await Task.Delay(45);
            ShowTab(window, "Profile");
            var profilePage = Assert.Single(window.FindControl<Grid>("PageHost")!.Children
                .OfType<Grid>().Where(page => page.DataContext is ProfileViewModel));

            await Task.Delay(45);
            ShowTab(window, "Home");
            await Task.Delay(450);
            Dispatcher.UIThread.RunJobs();

            Assert.Equal(1d, routesPage.Opacity, precision: 3);
            Assert.Equal(1d, profilePage.Opacity, precision: 3);
            Assert.True(Assert.Single(window.FindControl<Grid>("PageHost")!
                .GetVisualDescendants().OfType<RouteGlobeWebView>()).HostActive);
        }
        finally
        {
            window.Close();
        }
    }

    private static MainWindow CreateWindow() => new(new QaHomeFixture(
        false,
        "ru",
        "auto",
        "home",
        null,
        null,
        [],
        new WindowsTunnelSnapshot("disconnected", "synthetic fixture"),
        DisableNativeMapInitialization: true));

    private static void ShowTab(MainWindow window, string tab)
    {
        var method = typeof(MainWindow).GetMethod("ShowTab", BindingFlags.Instance | BindingFlags.NonPublic)!;
        method.Invoke(window, [Enum.Parse(method.GetParameters()[0].ParameterType, tab), true]);
        Dispatcher.UIThread.RunJobs();
    }
}
