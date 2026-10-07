using System.Diagnostics;
using System.Net.NetworkInformation;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private async Task ShowStartupExternalVpnNoticeAsync()
    {
        IReadOnlyList<WindowsExternalVpnObservation> detected;
        try
        {
            detected = await WindowsExternalVpnDetector.DetectAsync();
        }
        catch (Exception error) when (error is NetworkInformationException or InvalidOperationException)
        {
            return;
        }

        if (!IsVisible || detected.Count == 0)
            return;

        var stoppableServices = detected.SelectMany(item => item.RunningServiceNames)
            .Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        var happDetected = detected.Any(item => item.Name == "Happ");
        var content = new StackPanel { Spacing = 14 };
        content.Children.Add(DeyttTheme.TextBlock(
            Copy("В Windows уже работают VPN-службы или сетевые туннели. Они могут менять внешний IP и мешать проверке маршрутов DEYTT.",
                "Windows already has VPN services or tunnel adapters active. They can change your public IP and interfere with DEYTT route checks."),
            14, DeyttTheme.Muted));

        var services = new StackPanel { Spacing = 8 };
        foreach (var item in detected)
        {
            var detail = string.Join(" · ", item.Signals);
            var row = new Grid { ColumnDefinitions = new ColumnDefinitions("12,*"), ColumnSpacing = 11 };
            row.Children.Add(new Border
            {
                Width = 8,
                Height = 8,
                CornerRadius = new CornerRadius(4),
                Background = DeyttTheme.Brush(DeyttTheme.Amber),
                VerticalAlignment = VerticalAlignment.Center,
                HorizontalAlignment = HorizontalAlignment.Center,
            });
            var text = new StackPanel { Spacing = 3 };
            text.Children.Add(DeyttTheme.TextBlock(item.Name, 14, DeyttTheme.Text, FontWeight.SemiBold));
            text.Children.Add(DeyttTheme.TextBlock(detail, 11, DeyttTheme.Muted));
            Grid.SetColumn(text, 1);
            row.Children.Add(text);
            services.Children.Add(DeyttTheme.Card(row, DeyttTheme.Surface, DeyttTheme.Line, 13,
                new Thickness(13, 10)));
        }
        content.Children.Add(services);
        content.Children.Add(DeyttTheme.TextBlock(
            Copy("DEYTT ничего не отключает без вашего выбора. Закрытие остановит распознанную VPN-службу с подтверждением Windows и завершит приложение Happ. Подключение может смениться; регион на карте включается отдельно.",
                "DEYTT changes nothing without your choice. Closing stops the recognized VPN service with Windows confirmation and exits Happ. Your connection may change; map location is enabled separately."),
            12, DeyttTheme.Muted));

        var continueButton = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("Продолжить с текущим подключением", "Continue with current connection"),
                13, DeyttTheme.Sky, FontWeight.SemiBold), static () => { });
        continueButton.Padding = new Thickness(14, 11);
        continueButton.HorizontalContentAlignment = HorizontalAlignment.Center;
        var actions = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            Spacing = 9,
            Children = { continueButton },
        };

        Border? stopButton = null;
        if (stoppableServices.Length > 0 || happDetected)
        {
            stopButton = DeyttTheme.PrimaryButton(happDetected
                ? Copy("Закрыть Happ", "Close Happ")
                : Copy("Закрыть VPN-приложения", "Close VPN apps"), static () => { });
            stopButton.Margin = new Thickness(0);
            actions.Children.Add(stopButton);
        }
        if (!happDetected &&
            (stoppableServices.Length == 0 || detected.Any(item => item.RunningServiceNames.Count == 0)))
        {
            var openVpnSettings = DeyttTheme.Action(
                DeyttTheme.TextBlock(Copy("Параметры VPN Windows", "Windows VPN settings"),
                    13, DeyttTheme.Sky, FontWeight.SemiBold), static () => { });
            openVpnSettings.Padding = new Thickness(14, 11);
            openVpnSettings.Click += (_, _) =>
            {
                try { Process.Start(new ProcessStartInfo("ms-settings:network-vpn") { UseShellExecute = true }); }
                catch { }
            };
            actions.Children.Add(openVpnSettings);
        }

        content.Children.Add(actions);
        var result = await ShowContentInShellAsync(Copy("Активные VPN-службы", "Active VPN services"),
            content, 620, modal =>
            {
                continueButton.Click += (_, _) => modal.Close(false);
                if (stopButton?.Child is Button nestedStopButton)
                    nestedStopButton.Click += (_, _) => modal.Close(true);
            });

        if (result is not true || !IsVisible)
            return;

        var stopResult = stoppableServices.Length == 0
            ? WindowsExternalVpnStopResult.Stopped
            : await WindowsExternalVpnDetector.StopDetectedServicesAsync(stoppableServices);
        if (stopResult is WindowsExternalVpnStopResult.Cancelled)
        {
            await ShowInfoInShellAsync(Copy("VPN остался подключён", "VPN remains connected"),
                Copy("Запрос Windows был отменён. Никакие обнаруженные службы не были остановлены.",
                    "The Windows request was cancelled. No detected services were stopped."));
            return;
        }
        if (stopResult is WindowsExternalVpnStopResult.Failed)
        {
            await ShowInfoInShellAsync(Copy("Не удалось закрыть VPN-службы", "Could not close VPN services"),
                Copy("Служба не остановилась. Подключение осталось без изменений; проверьте права администратора и состояние VPN.",
                    "The service did not stop. Your connection was left unchanged; check administrator permission and VPN status."));
            return;
        }

        var happClosed = !happDetected || await WindowsExternalVpnDetector.CloseHappAppAsync();
        await Task.Delay(TimeSpan.FromMilliseconds(700));
        var currentIp = await WindowsExternalVpnDetector.GetCurrentPublicIpAsync();
        var remaining = await WindowsExternalVpnDetector.DetectAsync();
        var resultLines = new List<string>
        {
            Copy("Распознанные VPN-службы остановлены.", "Recognized VPN services have stopped."),
        };
        if (happDetected && !happClosed)
            resultLines.Add(Copy("Служба Happ остановлена, но его окно осталось открытым. Закройте Happ через его меню.",
                "The Happ service stopped, but its window remains open. Close Happ from its menu."));
        if (remaining.Count > 0)
            resultLines.Add(Copy("Остались активны: ", "Still active: ") +
                            string.Join(", ", remaining.Select(item => item.Name).Distinct(StringComparer.Ordinal)) + ".");
        resultLines.Add(currentIp is null
            ? Copy("Не удалось получить текущий внешний IP. Проверьте подключение и повторите позже.",
                "Could not retrieve the current public IP. Check the connection and try again later.")
            : Copy($"Текущий внешний IP: {currentIp}", $"Current public IP: {currentIp}"));
        await ShowInfoInShellAsync(Copy("Текущее подключение", "Current connection"),
            string.Join(Environment.NewLine + Environment.NewLine, resultLines));
        await ShowInitialMapConsentIfNeededAsync();
    }
}
