using System.Reflection;
using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Avalonia.Threading;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Views;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class TrayLifecycleTests
{
    private const BindingFlags PrivateInstance = BindingFlags.Instance | BindingFlags.NonPublic;
    private static readonly WindowsRoute Route = new("nl", "qa:nl", "NL", "Нидерланды", "🇳🇱", "VLESS", "VLESS");
    private static readonly WindowsRoute Bypass = new("chain", "qa:chain", "RU-DE", "Россия → Германия", "", "CHAIN", "RU → DE");

    private static MainWindow Window(string language = "ru", string state = "disconnected") =>
        new(new QaHomeFixture(false, language, Route.Id, "settings", null, null, [Route, Bypass],
            new WindowsTunnelSnapshot(state, "Synthetic only"), DisableNativeMapInitialization: true));

    private static void Set(MainWindow window, string field, object? value)
    {
        if (typeof(MainWindow).GetField(field, PrivateInstance) is { } member) member.SetValue(window, value);
        else typeof(MainWindow).GetProperty(field, PrivateInstance)!.SetValue(window, value);
    }
    private static T Get<T>(MainWindow window, string field) => (T)(
        typeof(MainWindow).GetField(field, PrivateInstance)?.GetValue(window) ??
        typeof(MainWindow).GetProperty(field, PrivateInstance)!.GetValue(window))!;
    private static object? Invoke(MainWindow window, string method, params object?[] arguments) =>
        typeof(MainWindow).GetMethod(method, PrivateInstance)!.Invoke(window, arguments);
    private static NativeMenu Menu(MainWindow window)
    {
        var menu = new NativeMenu();
        Invoke(window, "PopulateTrayMenu", menu);
        return menu;
    }

    [AvaloniaTheory]
    [InlineData("ru", "disconnected", "подключить vpn", "сменить обход")]
    [InlineData("en", "connected", "disconnect vpn", "change bypass")]
    [InlineData("en", "checking", "connecting vpn…", "change bypass")]
    public void MenuUsesLocalizedActionAndSeparatesBypass(string language, string state,
        string action, string bypass)
    {
        var window = Window(language, state);
        try
        {
            var menu = Menu(window);
            Assert.Equal(5, menu.Items.Count);
            Assert.Equal(action, Assert.IsType<NativeMenuItem>(menu.Items[1]).Header);
            var bypasses = Assert.IsType<NativeMenuItem>(menu.Items[2]);
            Assert.Equal(bypass, bypasses.Header);
            Assert.IsType<NativeMenuItemSeparator>(menu.Items[3]);
            Assert.Equal(language == "ru" ? "выйти" : "exit",
                Assert.IsType<NativeMenuItem>(menu.Items[4]).Header);
            var choices = bypasses.Menu!.Items;
            Assert.Equal(2, choices.Count);
            Assert.Equal("ru + de", Assert.IsType<NativeMenuItem>(choices[0]).Header);
            var country = Assert.IsType<NativeMenuItem>(choices[1]);
            Assert.Equal(language == "ru" ? "нидерланды" : "netherlands", country.Header);
            var protocol = Assert.Single(country.Menu!.Items);
            Assert.Equal("✓ vless", Assert.IsType<NativeMenuItem>(protocol).Header);
        }
        finally { window.Close(); }
    }

    [AvaloniaFact]
    public void QuitAndSessionRestoreDisableTunnelAndRouteActions()
    {
        var window = Window();
        try
        {
            Set(window, "_quitInProgress", true);
            var menu = Menu(window);
            Assert.False(Assert.IsType<NativeMenuItem>(menu.Items[1]).IsEnabled);
            Assert.False(Assert.IsType<NativeMenuItem>(menu.Items[2]).IsEnabled);
            Assert.All(Assert.IsType<NativeMenuItem>(menu.Items[2]).Menu!.Items,
                item => Assert.False(Assert.IsType<NativeMenuItem>(item).IsEnabled));
            Assert.False(Assert.IsType<NativeMenuItem>(menu.Items[4]).IsEnabled);
            Set(window, "_quitInProgress", false);
            Set(window, "_sessionRestoreComplete", false);
            menu = Menu(window);
            Assert.False(Assert.IsType<NativeMenuItem>(menu.Items[1]).IsEnabled);
            Assert.All(Assert.IsType<NativeMenuItem>(menu.Items[2]).Menu!.Items,
                item =>
                {
                    var route = Assert.IsType<NativeMenuItem>(item);
                    Assert.False(route.IsEnabled);
                    if (route.Menu is { } protocols)
                        Assert.All(protocols.Items, protocol => Assert.False(Assert.IsType<NativeMenuItem>(protocol).IsEnabled));
                });
        }
        finally { window.Close(); }
    }

    [AvaloniaTheory]
    [InlineData(true, false, WindowCloseReason.WindowClosing)]
    [InlineData(false, true, WindowCloseReason.WindowClosing)]
    [InlineData(false, false, WindowCloseReason.OSShutdown)]
    [InlineData(false, false, WindowCloseReason.ApplicationShutdown)]
    public void CloseAllowsQaAuthorizedExitAndSystemShutdown(bool qa, bool authorized,
        WindowCloseReason reason)
    {
        var window = Window();
        using var tray = new TrayIcon { IsVisible = false };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            Set(window, "_trayIcon", tray);
            Set(window, "_qaFixture", qa);
            Set(window, "_exitAuthorized", authorized);
            var args = (WindowClosingEventArgs)Activator.CreateInstance(typeof(WindowClosingEventArgs),
                BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null, [reason, false], null)!;
            Invoke(window, "OnTrayWindowClosing", window, args);
            Assert.False(args.Cancel);
            Assert.True(window.IsVisible);
        }
        finally { Set(window, "_qaFixture", true); Set(window, "_trayIcon", null); window.Close(); }
    }

    [AvaloniaFact]
    public void Window_close_stays_visible_while_the_exit_lifecycle_owns_shutdown()
    {
        var window = Window();
        using var tray = new TrayIcon { IsVisible = false };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            Set(window, "_trayIcon", tray);
            Set(window, "_qaFixture", false);
            Set(window, "_quitInProgress", true);
            var args = (WindowClosingEventArgs)Activator.CreateInstance(typeof(WindowClosingEventArgs),
                BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
                [WindowCloseReason.WindowClosing, false], null)!;

            Invoke(window, "OnTrayWindowClosing", window, args);

            Assert.True(args.Cancel);
            Assert.True(window.IsVisible);
            Assert.True(Get<bool>(window, "_quitInProgress"));
        }
        finally { Set(window, "_qaFixture", true); Set(window, "_trayIcon", null); window.Close(); }
    }

    [AvaloniaFact]
    public async Task DeferredConnectedPollCannotReplaceCompletedDisconnect()
    {
        var window = Window(state: "connected");
        var pending = new TaskCompletionSource<WindowsTunnelSnapshot>(TaskCreationOptions.RunContinuationsAsynchronously);
        try
        {
            var polling = window.RefreshVpnStatusAsync(() => pending.Task);
            Assert.False(polling.IsCompleted);
            Set(window, "_vpnOperationVersion", Get<long>(window, "_vpnOperationVersion") + 1);
            var stopped = new WindowsTunnelSnapshot("disconnected", "Latest completed stop");
            Set(window, "_vpnSnapshot", stopped);
            Set(window, "_vpnServiceAvailable", false);
            pending.SetResult(new WindowsTunnelSnapshot("connected", "Obsolete poll"));
            await polling;
            Assert.Same(stopped, Get<WindowsTunnelSnapshot>(window, "_vpnSnapshot"));
            Assert.False(Get<bool>(window, "_vpnServiceAvailable"));
            Assert.False(Get<bool>(window, "_vpnStatusRefreshRunning"));
        }
        finally { pending.TrySetCanceled(); window.Close(); }
    }

    [AvaloniaFact]
    public async Task DeferredStatusFailureCannotEraseNewerConnectionState()
    {
        var window = Window();
        var pending = new TaskCompletionSource<WindowsTunnelSnapshot>(TaskCreationOptions.RunContinuationsAsynchronously);
        try
        {
            var polling = window.RefreshVpnStatusAsync(() => pending.Task);
            Set(window, "_vpnOperationVersion", Get<long>(window, "_vpnOperationVersion") + 1);
            var newer = new WindowsTunnelSnapshot("checking", "Latest connect");
            Set(window, "_vpnSnapshot", newer);
            Set(window, "_vpnServiceAvailable", true);
            pending.SetException(new IOException("Obsolete failed status"));
            await polling;
            Assert.Same(newer, Get<WindowsTunnelSnapshot>(window, "_vpnSnapshot"));
            Assert.True(Get<bool>(window, "_vpnServiceAvailable"));
            Assert.False(Get<bool>(window, "_vpnStatusRefreshRunning"));
        }
        finally { pending.TrySetCanceled(); window.Close(); }
    }

    [AvaloniaFact]
    public async Task InstallerCancellationReleasesQuitAndPendingConnectFlags()
    {
        var window = Window(state: "checking");
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            Set(window, "_vpnConnectTask", Task.FromResult(new WindowsTunnelSnapshot("checking", "Synthetic")));
            Set(window, "_vpnCancelInProgress", true);
            await Assert.ThrowsAsync<OperationCanceledException>(() => window.ExitForVerifiedUpdateAsync(
                () => Task.FromException(new OperationCanceledException("Synthetic MSI launch cancellation"))));
            Assert.False(Get<bool>(window, "_quitInProgress"));
            Assert.False(Get<bool>(window, "_vpnActionInProgress"));
            Assert.False(Get<bool>(window, "_vpnCancelInProgress"));
            Assert.False(Get<bool>(window, "_vpnCancelRequested"));
            Assert.False(Get<bool>(window, "_exitAuthorized"));
            Assert.Null(typeof(MainWindow).GetField("_vpnConnectTask", PrivateInstance)!.GetValue(window));
            Assert.True(window.IsVisible);
        }
        finally { window.Close(); }
    }

    [AvaloniaFact]
    public async Task QuitLockBlocksActionsWhileInstallerLaunchIsPending()
    {
        var window = Window(state: "checking");
        var installer = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var quitting = window.ExitForVerifiedUpdateAsync(() => installer.Task);
            var version = Get<long>(window, "_vpnOperationVersion");
            await (Task)Invoke(window, "ToggleVpnAsync")!;
            await (Task)Invoke(window, "CancelVpnConnectionAsync")!;
            await (Task)Invoke(window, "SwitchTrayRouteAsync", Bypass)!;
            Invoke(window, "SelectRoute", Bypass.Id);
            Assert.Equal(Route.Id, Get<string>(window, "_selectedRoute"));
            Assert.Equal(version, Get<long>(window, "_vpnOperationVersion"));
            Assert.False(quitting.IsCompleted);
            installer.SetException(new OperationCanceledException("Synthetic cancellation"));
            await Assert.ThrowsAsync<OperationCanceledException>(() => quitting);
            Assert.False(Get<bool>(window, "_vpnActionInProgress"));
        }
        finally { installer.TrySetCanceled(); window.Close(); }
    }

    [Fact]
    public async Task ExitStopWaitsForConnectBeforeDisconnectAndAcceptsFailedConnect()
    {
        var pending = new TaskCompletionSource<WindowsTunnelSnapshot>(TaskCreationOptions.RunContinuationsAsynchronously);
        var disconnectCalled = false;
        var stopping = MainWindow.StopVpnBeforeExitAsync(pending.Task, () =>
        {
            disconnectCalled = true;
            return Task.FromResult(new WindowsTunnelSnapshot("disconnected", "Stopped"));
        });
        Assert.False(disconnectCalled);
        pending.SetException(new InvalidOperationException("Failed connect still needs cleanup"));
        Assert.Equal("disconnected", (await stopping).State);
        Assert.True(disconnectCalled);
    }

    [Fact]
    public async Task HungConnectDoesNotAuthorizeExitOrSendAnEarlierDisconnect()
    {
        var pending = new TaskCompletionSource<WindowsTunnelSnapshot>(TaskCreationOptions.RunContinuationsAsynchronously);
        var disconnectCalled = false;
        await Assert.ThrowsAsync<TimeoutException>(() => MainWindow.StopVpnBeforeExitAsync(pending.Task, () =>
        {
            disconnectCalled = true;
            return Task.FromResult(new WindowsTunnelSnapshot("disconnected", "Stopped"));
        }, TimeSpan.FromMilliseconds(20)));
        Assert.False(disconnectCalled);
        pending.SetResult(new WindowsTunnelSnapshot("checking", "Later connect"));
    }

    [Fact]
    public async Task ExitRequiresExplicitDisconnectedAcknowledgement()
    {
        await Assert.ThrowsAsync<IOException>(() => MainWindow.StopVpnBeforeExitAsync(null,
            () => Task.FromResult(new WindowsTunnelSnapshot("checking", "Still running"))));
    }

    [Fact]
    public async Task VerifiedStoppedOrAbsentServiceAllowsExitWithoutUnavailablePipe()
    {
        var stopped = await MainWindow.StopVpnBeforeExitAsync(null,
            () => throw new InvalidOperationException("No pipe can exist for a stopped service"), serviceInactive: () => true);
        Assert.Equal("disconnected", stopped.State);
    }

    [Fact]
    public async Task Service_confirmed_stopped_after_pipe_failure_allows_exit()
    {
        var statusChecks = 0;
        var stopped = await MainWindow.StopVpnBeforeExitAsync(null,
            () => Task.FromException<WindowsTunnelSnapshot>(new IOException("Service stopped during disconnect")),
            serviceInactive: () => ++statusChecks > 1);
        Assert.Equal("disconnected", stopped.State);
        Assert.Equal(2, statusChecks);
    }

    [Fact]
    public async Task UnknownOrRunningServiceStillRequiresDisconnectAcknowledgement()
    {
        await Assert.ThrowsAsync<IOException>(() => MainWindow.StopVpnBeforeExitAsync(null,
            () => Task.FromException<WindowsTunnelSnapshot>(new IOException("Service pipe unavailable")), serviceInactive: () => false));
    }
}
