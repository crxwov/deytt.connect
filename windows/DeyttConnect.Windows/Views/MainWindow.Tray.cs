using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using System.Runtime.InteropServices;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private TrayIcon? _trayIcon;
    private bool _exitAuthorized;
    private bool _quitInProgress;
    private bool _hideAfterStartup;

    private void InitializeTray()
    {
        if (_qaFixture || !OperatingSystem.IsWindows() || _trayIcon is not null || Application.Current is null)
            return;
        _trayIcon = new TrayIcon { Icon = Icon, ToolTipText = "deytt. connect", IsVisible = true };
        _trayIcon.Clicked += (_, _) => ShowFromTray();
        var menu = new NativeMenu();
        menu.NeedsUpdate += (_, _) => PopulateTrayMenu(menu);
        _trayIcon.Menu = menu;
        PopulateTrayMenu(menu);
        TrayIcon.SetIcons(Application.Current, new TrayIcons { _trayIcon });
    }

    private NativeMenuItem TrayAction(string label, Action action, bool enabled = true)
    {
        var item = new NativeMenuItem { Header = label, IsEnabled = enabled };
        item.Click += (_, _) => action();
        return item;
    }

    private void PopulateTrayMenu(NativeMenu menu)
    {
        menu.Items.Clear();
        menu.Items.Add(TrayAction(CopyExact("Открыть ./Connect", "Open ./Connect"), ShowFromTray));
        var busy = _vpnActionInProgress || _vpnCancelInProgress || _quitInProgress || _routeProbeInProgress;
        var active = IsVpnTunnelActive(_vpnSnapshot.State);
        var connecting = _vpnSnapshot.State is "starting" or "checking";
        var canCancelConnection = connecting && !_vpnCancelRequested && !_vpnCancelInProgress &&
                                  !_routeProbeInProgress && !_quitInProgress && !_exitAuthorized;
        var vpnAction = connecting
            ? CopyExact("Отменить подключение", "Cancel connection")
            : active ? CopyExact("Отключить VPN", "Disconnect VPN") : CopyExact("Подключить VPN", "Connect VPN");
        menu.Items.Add(TrayAction(vpnAction,
            () => { ShowFromTray(); HandleHomePrimaryAction(); },
            _sessionRestoreComplete && !_exitAuthorized && (canCancelConnection || !busy)));

        var servers = new NativeMenu();
        var selectableRoutes = _routes.Where(value => value.CountryCode != "RU-DE").ToArray();
        foreach (var route in selectableRoutes.Where(value =>
                     string.Equals(value.CountryCode, "AUTO", StringComparison.OrdinalIgnoreCase) || value.Id == "auto"))
        {
            var selected = route.Id == _selectedRoute;
            servers.Items.Add(TrayAction((selected ? "✓ " : "") + CopyExact("Автоподбор", "Auto-select"),
                () => _ = SwitchTrayRouteAsync(route), !busy && _sessionRestoreComplete));
        }

        foreach (var country in selectableRoutes
                     .Where(value => !string.Equals(value.CountryCode, "AUTO", StringComparison.OrdinalIgnoreCase) && value.Id != "auto")
                     .GroupBy(value => value.CountryCode, StringComparer.OrdinalIgnoreCase))
        {
            var routes = country.ToArray();
            var protocols = new NativeMenu();
            var repeatedProtocols = routes.GroupBy(value => value.ProtocolName, StringComparer.OrdinalIgnoreCase)
                .Where(group => group.Count() > 1)
                .Select(group => group.Key)
                .ToHashSet(StringComparer.OrdinalIgnoreCase);
            foreach (var route in routes)
            {
                var selected = route.Id == _selectedRoute;
                var profile = repeatedProtocols.Contains(route.ProtocolName)
                    ? " · " + (string.IsNullOrWhiteSpace(route.ProfileName) ? route.Id : route.ProfileName)
                    : "";
                protocols.Items.Add(TrayAction((selected ? "✓ " : "") + route.ProtocolName + profile,
                    () => _ = SwitchTrayRouteAsync(route), !busy && _sessionRestoreComplete));
            }
            var countryName = routes.Length > 0 ? RouteCountryName(routes[0]) : country.Key;
            if (!string.IsNullOrWhiteSpace(countryName))
                countryName = char.ToUpperInvariant(countryName[0]) + countryName[1..];
            servers.Items.Add(new NativeMenuItem { Header = countryName, Menu = protocols,
                IsEnabled = protocols.Items.Count > 0 && !busy && _sessionRestoreComplete });
        }
        menu.Items.Add(new NativeMenuItem { Header = CopyExact("Сменить сервер", "Change server"), Menu = servers,
            IsEnabled = servers.Items.Count > 0 && !busy && _sessionRestoreComplete });
        var bypassRoutes = _routes.Where(value => value.CountryCode == "RU-DE").ToArray();
        var bypass = new NativeMenu();
        foreach (var route in bypassRoutes)
            bypass.Items.Add(TrayAction((route.Id == _selectedRoute ? "✓ " : "") + route.ProtocolName,
                () => _ = SwitchTrayRouteAsync(route), !busy && _sessionRestoreComplete));
        menu.Items.Add(new NativeMenuItem { Header = CopyExact("Обход белых списков", "Allowlist bypass"), Menu = bypass,
            IsEnabled = bypassRoutes.Length > 0 && !busy && _sessionRestoreComplete });
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(TrayAction(CopyExact("Выйти", "Exit"), () => _ = QuitFromTrayAsync(), !_quitInProgress));
        if (_trayIcon is not null)
            _trayIcon.ToolTipText = "./Connect · " + (active ? Copy("VPN работает", "VPN active") : Copy("VPN отключён", "VPN inactive"));
    }

    private void ShowFromTray()
    {
        if (_exitAuthorized) return;
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        if (_quitInProgress) return;
        if (_sessionRestoreComplete)
        {
            _homeMapInitializationAllowed = true;
            ShowTab(_activeTab);
            _vpnStatusTimer.Start();
            _ = CheckForStartupUpdateAsync();
        }
    }

    private void OnTrayWindowClosing(object? sender, WindowClosingEventArgs args)
    {
        if (_qaFixture || _exitAuthorized || _trayIcon is null ||
            args.CloseReason is WindowCloseReason.OSShutdown or WindowCloseReason.ApplicationShutdown)
            return;
        args.Cancel = true;
        if (!_sessionRestoreComplete)
        {
            _hideAfterStartup = true;
            WindowState = WindowState.Minimized;
        }
        else Hide();
    }

    private async Task SwitchTrayRouteAsync(WindowsRoute route)
    {
        if (_vpnActionInProgress || _vpnCancelInProgress || _quitInProgress || _exitAuthorized || !_sessionRestoreComplete || route.Id == _selectedRoute) return;
        var reconnect = WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State);
        var operationVersion = ++_vpnOperationVersion;
        _vpnActionInProgress = true;
        try
        {
            if (reconnect)
            {
                _vpnSnapshot = new WindowsTunnelSnapshot("stopping", Copy("Меняем сервер…", "Switching server…"));
                RenderActiveTabPreservingScroll();
                var stopped = await _tunnelClient.DisconnectAsync();
                if (operationVersion != _vpnOperationVersion || _quitInProgress || _exitAuthorized) return;
                _vpnSnapshot = stopped;
                if (stopped.State != "disconnected") { ShowFromTray(); return; }
            }
            ApplyRouteSelection(route);
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            if (operationVersion != _vpnOperationVersion || _quitInProgress || _exitAuthorized) return;
            _vpnServiceAvailable = false;
            _vpnSnapshot = VpnServiceUnavailableSnapshot();
            ShowFromTray();
            reconnect = false;
        }
        finally
        {
            if (operationVersion == _vpnOperationVersion)
            {
                _vpnActionInProgress = false;
                RenderActiveTabPreservingScroll();
            }
        }
        if (reconnect && operationVersion == _vpnOperationVersion && !_quitInProgress && !_exitAuthorized) await ToggleVpnAsync();
    }

    private async Task QuitFromTrayAsync() => await ExitForVerifiedUpdateAsync(null);

    internal async Task<bool> ExitForVerifiedUpdateAsync(Func<Task>? launchInstaller)
    {
        if (_quitInProgress) return false;
        _quitInProgress = true;
        ++_vpnOperationVersion;
        _vpnCancelRequested = true;
        _vpnActionInProgress = true;
        _vpnStatusTimer.Stop();
        _vpnSnapshot = new WindowsTunnelSnapshot("stopping",
            Copy("Отключаем VPN и закрываем приложение…", "Disconnecting VPN and closing the app…"));
        ShowFromTray();
        RenderActiveTabPreservingScroll();
        var disconnectConfirmed = false;
        try
        {
            _vpnSnapshot = await StopVpnBeforeExitAsync(_vpnConnectTask,
                !_qaFixture && OperatingSystem.IsWindows()
                    ? () => _tunnelClient.DisconnectAsync()
                    : () => Task.FromResult(new WindowsTunnelSnapshot("disconnected", "")),
                serviceInactive: !_qaFixture && OperatingSystem.IsWindows() ? IsVpnServiceConfirmedInactive : null);
            disconnectConfirmed = true;
            if (launchInstaller is not null) await launchInstaller();
            _exitAuthorized = true;
            _trayIcon?.Dispose();
            _trayIcon = null;
            if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
                // Shutdown uses ApplicationShutdown for every owned dialog. Closing the
                // main window first uses WindowClosing and can be vetoed by a setup gate.
                desktop.Shutdown();
            else Close();
            return true;
        }
        catch (Exception error) when (IsTunnelTransportError(error) && !disconnectConfirmed)
        {
            ShowFromTray();
            ShowInfoDialog(Copy("VPN ещё не отключён", "VPN has not stopped"),
                Copy("Служба не подтвердила отключение. Повторите выход после проверки службы в настройках.",
                    "The service did not confirm disconnection. Check the service in settings, then try quitting again."));
            return false;
        }
        finally
        {
            _quitInProgress = false;
            _vpnCancelRequested = false;
            _vpnCancelInProgress = false;
            _vpnActionInProgress = false;
            if (_vpnConnectTask?.IsCompleted != false) _vpnConnectTask = null;
            if (!_exitAuthorized) _vpnStatusTimer.Start();
        }
    }

    internal static async Task<WindowsTunnelSnapshot> StopVpnBeforeExitAsync(
        Task<WindowsTunnelSnapshot>? pendingConnect, Func<Task<WindowsTunnelSnapshot>> disconnect,
        TimeSpan? pendingTimeout = null, Func<bool>? serviceInactive = null)
    {
        // Keep ordering: a connect not yet accepted by the service must not start
        // after our final disconnect. A failed connect still requires that stop.
        if (pendingConnect is not null)
        {
            try { await pendingConnect.WaitAsync(pendingTimeout ?? TimeSpan.FromSeconds(80)); }
            catch (TimeoutException) when (!pendingConnect.IsCompleted) { throw; }
            catch (Exception) { }
        }
        // EngineProcessLifetime uses KillOnJobClose and the service reports
        // STOPPED only after Engine.Stop(). No service-owned tunnel can survive it.
        if (serviceInactive?.Invoke() == true) return new WindowsTunnelSnapshot("disconnected", "");
        var stopped = await disconnect().WaitAsync(TimeSpan.FromSeconds(20));
        if (stopped.State != "disconnected") throw new IOException("VPN did not stop.");
        return stopped;
    }

    private static bool IsVpnServiceConfirmedInactive()
    {
        var manager = OpenSCManager(null, null, 1); // SC_MANAGER_CONNECT only.
        if (manager == IntPtr.Zero) return false;
        try
        {
            var service = OpenService(manager, "DEYTTConnectVpn", 4); // SERVICE_QUERY_STATUS only.
            if (service == IntPtr.Zero) return Marshal.GetLastWin32Error() == 1060; // ERROR_SERVICE_DOES_NOT_EXIST.
            try { return QueryServiceStatus(service, out var status) && status.CurrentState == 1; }
            finally { CloseServiceHandle(service); }
        }
        finally { CloseServiceHandle(manager); }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct VpnServiceStatus
    {
        public uint ServiceType, CurrentState, ControlsAccepted, Win32ExitCode, ServiceSpecificExitCode, CheckPoint, WaitHint;
    }
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr OpenSCManager(string? machine, string? database, uint access);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr OpenService(IntPtr manager, string name, uint access);
    [DllImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QueryServiceStatus(IntPtr service, out VpnServiceStatus status);
    [DllImport("advapi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseServiceHandle(IntPtr handle);
}
