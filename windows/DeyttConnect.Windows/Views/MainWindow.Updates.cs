using System;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Media;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private async Task ShowUpdateDialogAsync()
    {
        var status = DeyttTheme.TextBlock(Copy("Проверяем релизы Windows…", "Checking Windows releases…"),
            13, DeyttTheme.Muted);
        var openRelease = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("Открыть все релизы", "Open all releases"), 13,
                DeyttTheme.Sky, FontWeight.SemiBold),
            () => OpenExternal("https://github.com/crxwov/deytt.connect/releases"));
        var packageButton = DeyttTheme.PrimaryButton(Copy("Скачать и проверить ZIP", "Download and verify ZIP"),
            () => { });
        packageButton.IsVisible = false;
        var download = (Button)packageButton.Child!;
        var openPackageButton = DeyttTheme.PrimaryButton(
            Copy("Открыть проверенный архив", "Open verified package"), () => { });
        openPackageButton.IsVisible = false;
        var openPackage = (Button)openPackageButton.Child!;
        var body = new StackPanel
        {
            Spacing = 13,
            Children =
            {
                DeyttTheme.TextBlock(Copy("КАНАЛ WINDOWS", "WINDOWS CHANNEL"), 9,
                    DeyttTheme.Sky, FontWeight.SemiBold, DeyttTheme.JetBrainsMono),
                DeyttTheme.TextBlock(Copy("Обновления приложения", "Application updates"),
                    22, DeyttTheme.Text, FontWeight.Bold),
                status,
                packageButton,
                openPackageButton,
                openRelease,
            },
        };
        using var cancellation = new CancellationTokenSource();

        WindowsUpdateRelease? release = null;
        VerifiedWindowsUpdatePackage? verifiedPackage = null;
        var downloadInFlight = false;
        var dialogIsOpen = false;

        openPackage.Click += async (_, _) =>
        {
            if (verifiedPackage is null)
                return;

            openPackage.IsEnabled = false;
            try
            {
                await WindowsUpdateClient.OpenVerifiedPackageInExplorerAsync(verifiedPackage, cancellation.Token);
            }
            catch (OperationCanceledException)
            {
                // Closing the dialog cancels the final package verification.
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or
                                          System.ComponentModel.Win32Exception or PlatformNotSupportedException or
                                          InvalidDataException or System.Security.SecurityException)
            {
                if (dialogIsOpen)
                    status.Text = Copy("Архив изменился после проверки или не открылся в Проводнике.",
                        "The package changed after verification or File Explorer could not open it.");
            }
            finally
            {
                if (dialogIsOpen)
                    openPackage.IsEnabled = true;
            }
        };

        download.Click += async (_, _) =>
        {
            if (release is null || !release.CanDownloadVerifiedPackage || downloadInFlight)
                return;

            downloadInFlight = true;
            download.IsEnabled = false;
            status.Text = Copy("Загружаем пакет и сверяем SHA-256…", "Downloading package and checking SHA-256…");
            try
            {
                var package = await WindowsUpdateClient.DownloadAndVerifyAsync(release,
                    (received, total) => Avalonia.Threading.Dispatcher.UIThread.Post(() =>
                    {
                        if (dialogIsOpen)
                        {
                            var percent = total > 0 ? Math.Clamp(received * 100 / total, 0, 100) : 0;
                            status.Text = Copy($"Загрузка и проверка · {percent}%",
                                $"Downloading and verifying · {percent}%");
                        }
                    }), cancellation.Token);

                if (!dialogIsOpen)
                    return;

                verifiedPackage = package;
                packageButton.IsVisible = false;
                openPackageButton.IsVisible = true;
                var megabytes = package.Size / (1024d * 1024d);
                status.Text = Copy(
                    $"Архив проверен · {megabytes:0.0} МБ · SHA-256 {package.Sha256}\nПроводник выделит ZIP. Распаковка и установка остаются под вашим контролем.",
                    $"Package verified · {megabytes:0.0} MB · SHA-256 {package.Sha256}\nFile Explorer will select the ZIP. Extraction and installation remain under your control.");
            }
            catch (OperationCanceledException)
            {
                if (dialogIsOpen)
                    status.Text = Copy("Загрузка отменена. Можно попробовать ещё раз.",
                        "Download cancelled. You can try again.");
            }
            catch (Exception error) when (error is HttpRequestException or IOException or InvalidDataException or
                                          UnauthorizedAccessException or JsonException)
            {
                if (dialogIsOpen)
                    status.Text = Copy("Не удалось загрузить или проверить ZIP. Файл не будет открыт.",
                        "Could not download or verify the ZIP. The file will not be opened.");
            }
            finally
            {
                if (dialogIsOpen)
                {
                    download.IsEnabled = true;
                    downloadInFlight = false;
                }
            }
        };

        async Task LoadLatestReleaseAsync()
        {
            try
            {
                release = await WindowsUpdateClient.FindLatestWindowsReleaseAsync(cancellation.Token);
                if (!dialogIsOpen)
                    return;
                if (release is null)
                {
                    status.Text = Copy(
                        "В списке GitHub пока нет пакета Windows. Новые версии Android могут выходить отдельно.",
                        "GitHub does not have a Windows package yet. Android releases may appear separately.");
                }
                else
                {
                    var releaseDetails = $"{release.Tag}\n{release.Name}\n{release.Notes}";
                    if (release.CanDownloadVerifiedPackage)
                    {
                        var megabytes = release.AssetSize!.Value / (1024d * 1024d);
                        status.Text = $"{releaseDetails}\n\n{release.AssetName} · {megabytes:0.0} MB";
                        packageButton.IsVisible = true;
                    }
                    else
                    {
                        status.Text = releaseDetails + "\n\n" + Copy(
                            "Для этого ZIP нет корректных данных GitHub о SHA-256 и размере. Проверить загрузку не удаётся.",
                            "GitHub has not provided valid SHA-256 and size metadata for this ZIP. Its download cannot be verified.");
                    }
                }
            }
            catch (OperationCanceledException)
            {
                // Dismissing the shell modal cancels the outstanding release request.
            }
            catch (Exception error) when (error is HttpRequestException or JsonException or IOException)
            {
                if (dialogIsOpen)
                    status.Text = Copy("Не удалось проверить релизы. Проверьте подключение и повторите.",
                        "Could not check releases. Check the connection and try again.");
            }
        }

        var releaseLookup = Task.CompletedTask;
        await ShowContentInShellAsync(Copy("Обновления", "Updates"), body, 540, modal =>
        {
            dialogIsOpen = true;
            modal.Closed += () =>
            {
                dialogIsOpen = false;
                cancellation.Cancel();
            };
            releaseLookup = LoadLatestReleaseAsync();
        });
        await releaseLookup;
    }
}
