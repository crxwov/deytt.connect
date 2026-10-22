using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Views;
using System.Reflection;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class ManualUpdateDialogTests
{
    [AvaloniaFact]
    public async Task Manual_update_download_starts_only_after_explicit_button_click()
    {
        var release = new WindowsUpdateRelease(
            "v0.11.42",
            "Test release",
            "",
            "deytt-connect-0.11.42-portable.zip",
            "https://github.com/crxwov/deytt.connect/releases/download/v0.11.42/deytt-connect-0.11.42-portable.zip",
            10,
            $"sha256:{new string('a', 64)}",
            WindowsUpdatePackageKind.PortableZip);
        Assert.True(release.CanDownloadVerifiedPackage);
        var downloads = 0;
        var fixture = new QaHomeFixture(
            SignedIn: false,
            Language: "ru",
            SelectedRoute: "auto",
            Tab: "settings",
            Account: null,
            Subscription: null,
            Routes: [],
            Tunnel: new WindowsTunnelSnapshot("disconnected", "Synthetic only"),
            ManualUpdateLookup: _ => Task.FromResult<WindowsUpdateRelease?>(release),
            ManualUpdateDownload: (_, _, _) =>
            {
                downloads++;
                return Task.FromException<VerifiedWindowsUpdatePackage>(new InvalidOperationException("Offline test"));
            });
        var window = new MainWindow(fixture) { Width = 900, Height = 700 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var show = typeof(MainWindow).GetMethod("ShowUpdateDialogAsync",
                BindingFlags.Instance | BindingFlags.NonPublic)!;
            var dialogFlow = (Task)show.Invoke(window, null)!;
            var host = window.FindControl<Grid>("ModalOverlayHost")!;
            Button? downloadButton = null;
            await WaitUntilAsync(() =>
            {
                Dispatcher.UIThread.RunJobs();
                downloadButton = Descendants(host).OfType<Button>().FirstOrDefault(button =>
                    AutomationProperties.GetAutomationId(button) == "ManualUpdateDownloadButton");
                return downloadButton?.IsVisible == true;
            });

            Assert.Equal(0, downloads);
            downloadButton!.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            await WaitUntilAsync(() =>
            {
                Dispatcher.UIThread.RunJobs();
                return downloads == 1;
            });
            Assert.Equal(1, downloads);

            Descendants(host).OfType<Button>().Single(button =>
                    AutomationProperties.GetAutomationId(button) == "DialogCloseButton")
                .RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            await dialogFlow.WaitAsync(TimeSpan.FromSeconds(3));
        }
        finally
        {
            window.Close();
        }
    }

    private static async Task WaitUntilAsync(Func<bool> predicate)
    {
        for (var attempt = 0; attempt < 100; attempt++)
        {
            if (predicate())
                return;
            await Task.Delay(10);
        }
        Assert.Fail("Timed out waiting for the update dialog state.");
    }

    private static IEnumerable<Control> Descendants(Control root)
    {
        foreach (var child in root.GetVisualChildren().OfType<Control>())
        {
            yield return child;
            foreach (var descendant in Descendants(child))
                yield return descendant;
        }
    }
}
