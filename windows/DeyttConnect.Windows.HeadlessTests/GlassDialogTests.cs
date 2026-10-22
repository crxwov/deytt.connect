using System.Reflection;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;
using DeyttConnect.Windows.Views;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class GlassDialogTests
{
    [AvaloniaFact]
    public void Setup_keeps_its_backdrop_and_input_during_host_refresh()
    {
        var window = new MainWindow(new QaHomeFixture(false, "ru", "auto", "home", null, null, [],
            new WindowsTunnelSnapshot("disconnected", "synthetic"), DisableNativeMapInitialization: true));
        using var preview = new WriteableBitmap(new PixelSize(8, 8), new Vector(96, 96), PixelFormat.Bgra8888, AlphaFormat.Premul);
        var setup = new SetupWindow(new SetupWindowOptions { RestoreExistingSession = false });
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        try
        {
            typeof(MainWindow).GetField("_activeSetupView", flags)!.SetValue(window, setup);
            typeof(MainWindow).GetField("_setupPagePreview", flags)!.SetValue(window, preview);
            var build = typeof(MainWindow).GetMethod("BuildSetupPage", flags)!;
            var page = Assert.IsType<Grid>(build.Invoke(window, null));
            var input = Assert.IsType<TextBox>(typeof(SetupWindow).GetField("_usernameInput", flags)!.GetValue(setup));
            input.Text = "@still_typing";
            Assert.Same(page, build.Invoke(window, null));
            Assert.Same(page, setup.Parent);
            Assert.Equal("@still_typing", input.Text);
        }
        finally { setup.Abort(); window.Close(); }
    }

    [AvaloniaTheory]
    [InlineData(Key.Enter)]
    [InlineData(Key.Space)]
    public void Glass_choice_activates_once_from_keyboard(Key key)
    {
        var activations = 0;
        var choice = DeyttTheme.DialogChoice(DeyttTheme.TextBlock("Маршрут"), false, () => activations++);
        var window = new Window { Content = choice, Width = 420, Height = 200 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            choice.Focus();
            var physicalKey = key == Key.Enter ? PhysicalKey.Enter : PhysicalKey.Space;
            window.KeyPress(key, RawInputModifiers.None, physicalKey, null);
            window.KeyRelease(key, RawInputModifiers.None, physicalKey, null);
            Assert.Equal(1, activations);
        }
        finally { window.Close(); }
    }

    [AvaloniaTheory]
    [InlineData(1d)]
    [InlineData(1.25d)]
    [InlineData(1.5d)]
    [InlineData(2d)]
    public async Task Dialog_keeps_page_unshaded_and_actions_reachable_after_resize(double scale)
    {
        var window = new MainWindow(new QaHomeFixture(false, "ru", "auto", "home", null, null, [],
            new WindowsTunnelSnapshot("disconnected", "synthetic"), DisableNativeMapInitialization: true))
            { Width = 1240, Height = 820 };
        try
        {
            window.Show();
            window.SetRenderScaling(scale);
            Dispatcher.UIThread.RunJobs();
            var done = DeyttTheme.DialogPrimaryButton(DeyttTheme.TextBlock("Готово"), () => { });
            var show = typeof(MainWindow).GetMethod("ShowContentInShellAsync", BindingFlags.Instance | BindingFlags.NonPublic)!;
            var task = (Task<object?>)show.Invoke(window, ["Раздельное туннелирование", new Border { Height = 1200 }, 720d, null, done])!;
            Dispatcher.UIThread.RunJobs();
            window.UpdateLayout();
            var host = window.FindControl<Grid>("ModalOverlayHost")!;
            var overlay = Assert.IsType<Grid>(Assert.Single(host.Children));
            Assert.Equal(0, Assert.IsAssignableFrom<ISolidColorBrush>(overlay.Background).Color.A);
            var card = Assert.Single(overlay.Children.OfType<Border>());
            Assert.Equal("GlassDialogCard", AutomationProperties.GetAutomationId(card));

            window.Width = 520;
            window.Height = 420;
            Dispatcher.UIThread.RunJobs();
            window.UpdateLayout();
            Assert.True(card.Bounds.Right <= host.Bounds.Width && card.Bounds.Bottom <= host.Bounds.Height);
            Assert.True(card.Bounds.Width <= 476);
            var footerOrigin = done.TranslatePoint(default, overlay)!.Value;
            Assert.InRange(footerOrigin.Y + done.Bounds.Height, 1, overlay.Bounds.Height);
            var label = Assert.Single(done.GetVisualDescendants().OfType<TextBlock>());
            var labelOrigin = label.TranslatePoint(default, done)!.Value;
            Assert.InRange(Math.Abs(labelOrigin.X + label.Bounds.Width / 2 - done.Bounds.Width / 2), 0, 2);

            overlay.RaiseEvent(new KeyEventArgs { RoutedEvent = InputElement.KeyDownEvent, Key = Key.Escape });
            await task.WaitAsync(TimeSpan.FromSeconds(3));
            Assert.False(host.IsVisible);
        }
        finally { window.Close(); }
    }
}
