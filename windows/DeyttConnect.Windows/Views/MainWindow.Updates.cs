using System.Text.Json;
using DeyttConnect.Windows.Services;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private bool _startupUpdateCheckStarted;

    /// <summary>Offers each newer stable package matching this installation on every app launch.</summary>
    internal async Task CheckForStartupUpdateAsync()
    {
        if (!OperatingSystem.IsWindows() || _qaFixture || !IsVisible || _startupUpdateCheckStarted)
            return;

        _startupUpdateCheckStarted = true;
        var kind = WindowsUpdateClient.GetCurrentInstallationKind(AppContext.BaseDirectory);
        using var cancellation = new CancellationTokenSource();
        EventHandler closed = (_, _) => cancellation.Cancel();
        Closed += closed;
        try
        {
            var release = await WindowsUpdateClient.FindLatestWindowsReleaseAsync(
                cancellation.Token, kind);
            if (release is null || !release.CanDownloadVerifiedPackage ||
                !WindowsUpdateClient.IsReleaseForInstallation(release, kind))
                return;

            var accepted = await ConfirmInShellAsync(
                Copy("Доступно обновление DEYTT Connect", "DEYTT Connect update available"),
                kind == WindowsUpdatePackageKind.Msi
                    ? Copy($"Установлена версия {WindowsUpdateClient.GetInstalledVersion()?.ToString(3)}. Доступна версия {release.Tag}. Скачать и запустить установщик?",
                        $"Version {WindowsUpdateClient.GetInstalledVersion()?.ToString(3)} is installed. Version {release.Tag} is available. Download and launch the installer?")
                    : Copy($"Установлена версия {WindowsUpdateClient.GetInstalledVersion()?.ToString(3)}. Доступна версия {release.Tag}. Скачать проверенный ZIP?",
                        $"Version {WindowsUpdateClient.GetInstalledVersion()?.ToString(3)} is installed. Version {release.Tag} is available. Download the verified ZIP?"),
                Copy("Обновить", "Update"));
            if (!accepted || cancellation.IsCancellationRequested)
                return;

            var package = await DownloadAndLaunchUpdateAsync(release, null, cancellation.Token);
            if (kind == WindowsUpdatePackageKind.PortableZip && IsVisible)
                await ShowInfoInShellAsync(Copy("Архив обновления проверен", "Update archive verified"),
                    Copy($"Проводник выделил {Path.GetFileName(package.FilePath)}. Распакуйте его в новую папку, затем запустите новую версию.",
                        $"File Explorer selected {Path.GetFileName(package.FilePath)}. Extract it to a new folder, then run the new version."));
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
        }
        catch (System.ComponentModel.Win32Exception error) when (error.NativeErrorCode == 1223)
        {
        }
        catch (OperationCanceledException)
        {
            if (IsVisible)
                await ShowInfoInShellAsync(Copy("Не удалось проверить обновления", "Could not check for updates"),
                    Copy("Проверка заняла слишком много времени. Попробуйте вручную в настройках.",
                        "The update check timed out. Try again manually in Settings."));
        }
        catch (Exception error) when (error is HttpRequestException or IOException or InvalidDataException or
                                      UnauthorizedAccessException or System.Security.SecurityException or
                                      System.ComponentModel.Win32Exception or JsonException or InvalidOperationException)
        {
            if (IsVisible)
                await ShowInfoInShellAsync(Copy("Не удалось обновить приложение", "Could not update the app"),
                    Copy("Проверьте подключение и повторите проверку в настройках.",
                        "Check your connection and try again from Settings."));
        }
        finally
        {
            Closed -= closed;
        }
    }

    /// <summary>Returns the latest verified-asset candidate matching this installation type.</summary>
    internal async Task<WindowsUpdateRelease?> FindManualUpdateAsync(CancellationToken cancellationToken)
    {
        var kind = WindowsUpdateClient.GetCurrentInstallationKind(AppContext.BaseDirectory);
        var release = await WindowsUpdateClient.FindLatestWindowsReleaseAsync(cancellationToken, kind);
        return release is not null && WindowsUpdateClient.IsReleaseForInstallation(release, kind)
            ? release
            : null;
    }

    /// <summary>Downloads, verifies, then starts the MSI or reveals the portable archive.</summary>
    internal async Task<VerifiedWindowsUpdatePackage> DownloadAndLaunchUpdateAsync(
        WindowsUpdateRelease release,
        Action<long, long>? onProgress,
        CancellationToken cancellationToken)
    {
        var kind = WindowsUpdateClient.GetCurrentInstallationKind(AppContext.BaseDirectory);
        if (!WindowsUpdateClient.IsReleaseForInstallation(release, kind) || !release.CanDownloadVerifiedPackage)
            throw new InvalidDataException("The release is not a newer verified package for this installation.");

        var package = await WindowsUpdateClient.DownloadAndVerifyAsync(release, onProgress, cancellationToken);
        if (kind == WindowsUpdatePackageKind.Msi)
        {
            var exited = await ExitForVerifiedUpdateAsync(
                () => WindowsUpdateClient.LaunchVerifiedMsiInstallerAsync(package, cancellationToken));
            if (!exited)
                throw new InvalidOperationException("VPN could not stop for update.");
        }
        else
            await WindowsUpdateClient.OpenVerifiedPackageInExplorerAsync(package, cancellationToken);
        return package;
    }
}
