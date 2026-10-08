using System.Diagnostics;
using System.Net.NetworkInformation;
using Avalonia;
using Avalonia.Automation;
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
        var layout = BuildActiveVpnNoticeContent(detected, stoppableServices.Length > 0 || happDetected,
            !happDetected &&
            (stoppableServices.Length == 0 || detected.Any(item => item.RunningServiceNames.Count == 0)));
        var result = await ShowContentInShellAsync(Copy("Активные VPN-службы", "Active VPN services"),
            layout.Content, 560, modal =>
            {
                modal.InitialFocusTarget = layout.ContinueButton;
                layout.ContinueButton.Click += (_, _) => modal.Close(false);
                if (layout.StopButton is not null)
                    layout.StopButton.Click += (_, _) => modal.Close(true);
            }, layout.Actions);

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

    internal (StackPanel Content, StackPanel Actions, Button ContinueButton, Button? StopButton)
        BuildActiveVpnNoticeContent(
        IReadOnlyList<WindowsExternalVpnObservation> detected,
        bool includeStopButton,
        bool includeWindowsSettingsButton)
    {
        var happDetected = detected.Any(item => item.Name == "Happ");
        var content = new StackPanel { Spacing = 14 };
        content.Children.Add(DeyttTheme.SectionLabel(Copy("СЕТЬ · ДРУГОЙ VPN", "NETWORK · OTHER VPN")));
        content.Children.Add(DeyttTheme.TextBlock(
            Copy("В Windows работают другие VPN-службы или сетевые туннели. Они могут менять внешний IP и мешать проверке маршрутов DEYTT.",
                "Other VPN services or tunnel adapters are active in Windows. They can change your public IP and interfere with DEYTT route checks."),
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
            Copy("DEYTT ничего не отключает без вашего выбора. Windows попросит подтвердить остановку службы; подключение при этом может измениться. Примерный регион на карте включается отдельно.",
                "DEYTT will not disconnect anything without your choice. Windows will ask you to confirm before a service stops; your connection may change. Approximate map location is enabled separately."),
            12, DeyttTheme.Muted));

        var continueSurface = DeyttTheme.PrimaryButton(
            Copy("Продолжить с текущим подключением", "Continue with current connection"), static () => { });
        continueSurface.Height = 48;
        continueSurface.CornerRadius = new CornerRadius(14);
        var continueButton = continueSurface.Child as Button
            ?? throw new InvalidOperationException("The primary button did not contain a Button control.");
        AutomationProperties.SetAutomationId(continueButton, "ExternalVpnContinue");
        AutomationProperties.SetName(continueButton,
            Copy("Продолжить с текущим подключением", "Continue with current connection"));
        var actions = new StackPanel { Spacing = 2, Children = { continueSurface } };

        Button? stopButton = null;
        if (includeStopButton)
        {
            var stopLabel = happDetected
                ? Copy("Закрыть Happ", "Close Happ")
                : Copy("Остановить VPN-службы", "Stop VPN services");
            stopButton = DeyttTheme.Action(
                DeyttTheme.TextBlock(stopLabel, 13, DeyttTheme.Coral, FontWeight.SemiBold), static () => { });
            stopButton.Height = 44;
            stopButton.HorizontalAlignment = HorizontalAlignment.Stretch;
            stopButton.HorizontalContentAlignment = HorizontalAlignment.Center;
            stopButton.Padding = new Thickness(12, 8);
            AutomationProperties.SetAutomationId(stopButton, "ExternalVpnStop");
            AutomationProperties.SetName(stopButton, stopLabel);
            actions.Children.Add(stopButton);
        }
        if (includeWindowsSettingsButton)
        {
            var openVpnSettings = DeyttTheme.Action(
                DeyttTheme.TextBlock(Copy("Параметры VPN Windows", "Windows VPN settings"),
                    13, DeyttTheme.Sky, FontWeight.SemiBold), static () => { });
            openVpnSettings.Height = 44;
            openVpnSettings.HorizontalAlignment = HorizontalAlignment.Stretch;
            openVpnSettings.HorizontalContentAlignment = HorizontalAlignment.Center;
            openVpnSettings.Padding = new Thickness(12, 8);
            AutomationProperties.SetAutomationId(openVpnSettings, "ExternalVpnSettings");
            AutomationProperties.SetName(openVpnSettings, Copy("Параметры VPN Windows", "Windows VPN settings"));
            openVpnSettings.Click += (_, _) =>
            {
                try { Process.Start(new ProcessStartInfo("ms-settings:network-vpn") { UseShellExecute = true }); }
                catch { }
            };
            actions.Children.Add(openVpnSettings);
        }

        return (content, actions, continueButton, stopButton);
    }
}
