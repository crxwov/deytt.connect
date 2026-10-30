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
        var result = await ShowContentInShellAsync(Copy("обнаружен сторонний vpn", "active vpn services"),
            layout.Content, 520, modal =>
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
        content.Children.Add(DeyttTheme.SectionLabel(Copy("СЕТЬ · СТОРОННИЙ VPN", "NETWORK · OTHER VPN")));
        content.Children.Add(DeyttTheme.TextBlock(
            Copy("В Windows активны другие VPN-службы или туннели. Они могут перехватывать трафик и мешать стабильному подключению к узлам DEYTT.",
                "Other VPN services or tunnel adapters are active in Windows. They can change your public IP and interfere with DEYTT route checks."),
            13, DeyttTheme.Muted));

        var services = new StackPanel { Spacing = 8 };
        foreach (var item in detected)
        {
            var detail = string.Join(" · ", item.Signals);
            var row = new Grid { ColumnDefinitions = new ColumnDefinitions("14,*"), ColumnSpacing = 12 };
            var statusDot = new Border
            {
                Width = 8,
                Height = 8,
                CornerRadius = new CornerRadius(4),
                Background = DeyttTheme.Brush(DeyttTheme.Amber),
                VerticalAlignment = VerticalAlignment.Center,
                HorizontalAlignment = HorizontalAlignment.Center,
                BoxShadow = new BoxShadows(new BoxShadow
                {
                    Color = Color.FromArgb(120, 245, 158, 11),
                    Blur = 8,
                }),
            };
            row.Children.Add(statusDot);
            var text = new StackPanel { Spacing = 2 };
            text.Children.Add(DeyttTheme.TextBlock(item.Name.ToLowerInvariant(), 14, DeyttTheme.Text, FontWeight.SemiBold));
            text.Children.Add(DeyttTheme.TextBlock(detail.ToLowerInvariant(), 12, DeyttTheme.Muted));
            Grid.SetColumn(text, 1);
            row.Children.Add(text);
            services.Children.Add(DeyttTheme.DialogPanel(row, radius: 14, padding: new Thickness(14, 11)));
        }
        content.Children.Add(services);
        content.Children.Add(DeyttTheme.TextBlock(
            Copy("DEYTT не отключает службы без вашего выбора. Вы можете продолжить с текущим подключением или закрыть сторонний VPN.",
                "DEYTT will not disconnect anything without your choice. You can continue or close the conflicting VPN."),
            12, DeyttTheme.Muted));

        var continueSurface = DeyttTheme.DialogPrimaryButton(
            DeyttTheme.TextBlock(Copy("Продолжить с текущим подключением", "Continue with current connection"), 14, Colors.White, FontWeight.SemiBold), static () => { });
        continueSurface.Height = 46;
        var continueButton = continueSurface.Child as Button
            ?? throw new InvalidOperationException("The primary button did not contain a Button control.");
        AutomationProperties.SetAutomationId(continueButton, "ExternalVpnContinue");
        AutomationProperties.SetName(continueButton,
            Copy("Продолжить с текущим подключением", "Continue with current connection"));
        var actions = new StackPanel { Spacing = 10, Children = { continueSurface } };

        Button? stopButton = null;
        if (includeStopButton)
        {
            var stopLabel = happDetected
                ? Copy("Закрыть Happ", "Close Happ")
                : Copy("Остановить сторонние службы", "Stop VPN services");
            var stopText = DeyttTheme.TextBlock(stopLabel, 14, DeyttTheme.Coral, FontWeight.SemiBold);
            stopText.HorizontalAlignment = HorizontalAlignment.Center;
            stopButton = DeyttTheme.Action(stopText, static () => { });
            stopButton.Height = 44;
            stopButton.HorizontalAlignment = HorizontalAlignment.Stretch;
            stopButton.HorizontalContentAlignment = HorizontalAlignment.Center;
            stopButton.Padding = new Thickness(12, 8);
            AutomationProperties.SetAutomationId(stopButton, "ExternalVpnStop");
            AutomationProperties.SetName(stopButton, stopLabel);

            var stopCard = new Border
            {
                Height = 44,
                HorizontalAlignment = HorizontalAlignment.Stretch,
                CornerRadius = new CornerRadius(14),
                Background = DeyttTheme.Brush(Color.FromArgb(20, 255, 107, 107)),
                BorderBrush = DeyttTheme.Brush(Color.FromArgb(50, 255, 107, 107)),
                BorderThickness = new Thickness(1),
                Child = stopButton,
            };
            actions.Children.Add(stopCard);
        }
        if (includeWindowsSettingsButton)
        {
            var settingsText = DeyttTheme.TextBlock(Copy("Параметры VPN Windows", "Windows VPN settings"),
                13, DeyttTheme.Sky, FontWeight.SemiBold);
            settingsText.HorizontalAlignment = HorizontalAlignment.Center;
            var openVpnSettings = DeyttTheme.Action(settingsText, static () => { });
            openVpnSettings.Height = 40;
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
