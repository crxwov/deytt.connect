using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private bool _vpnStatusRefreshRunning;

    private async Task RefreshVpnStatusAsync()
    {
        if (!OperatingSystem.IsWindows() || _vpnActionInProgress || _vpnCancelInProgress || _vpnStatusRefreshRunning)
            return;

        _vpnStatusRefreshRunning = true;
        try
        {
            var previousSnapshot = _vpnSnapshot;
            var previousDisplayConnected = IsVpnDisplayConnected();
            var wasAvailable = _vpnServiceAvailable;
            var currentSnapshot = await _tunnelClient.GetStatusAsync();
            if (_vpnActionInProgress || _vpnCancelInProgress)
                return;
            _vpnSnapshot = currentSnapshot;
            _vpnServiceAvailable = true;
            UpdateMapLocationForVpnState(previousSnapshot.State, _vpnSnapshot.State);
            if (previousSnapshot != _vpnSnapshot || wasAvailable != _vpnServiceAvailable ||
                previousDisplayConnected != IsVpnDisplayConnected())
                RenderActiveTabPreservingScroll();
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            if (_vpnActionInProgress || _vpnCancelInProgress)
                return;
            var wasAvailable = _vpnServiceAvailable;
            var previousSnapshot = _vpnSnapshot;
            _vpnServiceAvailable = false;
            _vpnSnapshot = VpnServiceUnavailableSnapshot();
            if (wasAvailable || previousSnapshot != _vpnSnapshot)
                RenderActiveTabPreservingScroll();
        }
        finally
        {
            _vpnStatusRefreshRunning = false;
        }
    }

    private WindowsTunnelSnapshot VpnServiceUnavailableSnapshot() => new("error",
        Copy("Не удалось узнать состояние VPN: служба не отвечает. Проверьте её в настройках и состояние VPN в Windows.",
            "VPN status is unknown: the service is not responding. Check it in settings and verify VPN status in Windows."));

    private async Task InstallVpnServiceAsync()
    {
        if (!OperatingSystem.IsWindows())
        {
            ShowInfoDialog(Copy("Только для Windows", "Windows only"),
                Copy("Установка VPN-службы доступна после запуска полного Windows-пакета.",
                    "VPN service setup is available in the full Windows package."));
            return;
        }
        if (_vpnActionInProgress)
            return;

        var installerPath = System.IO.Path.Combine(AppContext.BaseDirectory, "Install-VpnService.ps1");
        if (!File.Exists(installerPath))
        {
            ShowInfoDialog(Copy("Установка недоступна", "Setup unavailable"),
                Copy("Установите приложение из полного Windows-пакета.",
                    "Install the app from the complete Windows package."));
            return;
        }

        _vpnActionInProgress = true;
        RenderActiveTabPreservingScroll();
        try
        {
            using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
            var sid = identity.User?.Value;
            if (string.IsNullOrWhiteSpace(sid))
                throw new InvalidOperationException("The current Windows user SID is unavailable.");

            var powerShellPath = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),
                "WindowsPowerShell", "v1.0", "powershell.exe");
            var bundleRoot = WindowsPortableLayout.ResolveBundleRoot(AppContext.BaseDirectory)
                .TrimEnd(System.IO.Path.DirectorySeparatorChar, System.IO.Path.AltDirectorySeparatorChar);
            var start = new ProcessStartInfo
            {
                FileName = powerShellPath,
                Arguments = $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"{installerPath}\" -AllowedUserSid \"{sid}\" -BundleRoot \"{bundleRoot}\"",
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            using var installer = Process.Start(start)
                                  ?? throw new InvalidOperationException("The VPN installer did not start.");
            await installer.WaitForExitAsync();
            if (installer.ExitCode != 0)
            {
                ShowInfoDialog(Copy("Служба VPN не установлена", "VPN service was not installed"),
                    Copy("Закройте сообщение установщика и попробуйте ещё раз.",
                        "Close the installer message and try again."));
                return;
            }

            _vpnSnapshot = await _tunnelClient.GetStatusAsync();
            _vpnServiceAvailable = true;
            ShowInfoDialog(Copy("Служба VPN готова", "VPN service is ready"),
                Copy("Теперь можно подключиться с главного экрана.",
                    "You can connect from the Home screen now."));
        }
        catch (System.ComponentModel.Win32Exception error) when (error.NativeErrorCode == 1223)
        {
            // The user canceled the Windows elevation prompt.
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
                                      InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            ShowInfoDialog(Copy("Не удалось настроить службу VPN", "Could not configure the VPN service"),
                Copy("Запустите установку ещё раз и подтвердите запрос Windows.",
                    "Run setup again and approve the Windows prompt."));
        }
        finally
        {
            _vpnActionInProgress = false;
            RenderActiveTabPreservingScroll();
        }
    }

    private static bool IsTunnelTransportError(Exception error) =>
        error is IOException or UnauthorizedAccessException or TimeoutException or
            OperationCanceledException or System.ComponentModel.Win32Exception or
            System.Text.Json.JsonException;
}
