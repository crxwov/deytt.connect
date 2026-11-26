using System.Text.Json;
using DeyttConnect.Windows.Services;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private bool _startupUpdateCheckStarted;

    /// <summary>Offers each newer stable package matching this installation on every app launch.</summary>
    internal async Task CheckForStartupUpdateAsync()
    {
        if (_qaFixture || !IsVisible || _startupUpdateCheckStarted)
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
            var installed = WindowsUpdateClient.GetInstalledVersion()?.ToString(3) ?? "-";
            var candidateVersion = release is null ? "-" : WindowsUpdateClient.GetDisplayVersion(release);
            var isNewer = release is not null && release.CanDownloadVerifiedPackage &&
                          WindowsUpdateClient.IsReleaseForInstallation(release, kind);
            UiDiagnostics.Log($"startup update check: installed={installed} latest={candidateVersion} newer={isNewer} asset={release?.AssetName ?? "-"}");
            if (!isNewer || !IsVisible || cancellation.IsCancellationRequested)
                return;

            await ShowUpdateDialogCoreAsync(release);
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
        }
        catch (System.ComponentModel.Win32Exception error) when (error.NativeErrorCode == 1223)
        {
        }
        catch (Exception error) when (error is OperationCanceledException or HttpRequestException or IOException or
                                      InvalidDataException or UnauthorizedAccessException or
                                      System.Security.SecurityException or System.ComponentModel.Win32Exception or
                                      JsonException or InvalidOperationException)
        {
            UiDiagnostics.Log($"startup update check skipped: {error.GetType().Name}");
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
        else if (kind == WindowsUpdatePackageKind.PortableZip)
            await WindowsUpdateClient.OpenVerifiedPackageInExplorerAsync(package, cancellationToken);
        return package;
    }
}
