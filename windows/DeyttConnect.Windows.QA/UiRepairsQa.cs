using System.Reflection;
using System.Text.Json;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media.Imaging;
using Avalonia.VisualTree;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Views;

namespace DeyttConnect.Windows.QA;

internal sealed partial class QaApp
{
    // Synthetic native-window acceptance: no account requests, imports or settings commits.
    private static async Task RunUiRepairsQaAsync(MainWindow window, QaOptions options)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        var output = Path.GetFullPath(options.CaptureHomePreview ?? throw new ArgumentException("Provide --capture-page output directory."));
        Directory.CreateDirectory(output);
        var results = new List<object>();
        var modalField = typeof(MainWindow).GetField("_activeShellContentDialog", flags)!;
        try
        {
            await Task.Delay(900);
            foreach (var (name, method) in new[] { ("language", "ShowLanguagePickerAsync"), ("probe", "ShowProbeMethodPickerAsync"), ("split", "ShowSplitTunnelingInfo") })
            {
                var operation = typeof(MainWindow).GetMethod(method, flags)!.Invoke(window, null) as Task;
                for (var attempt = 0; attempt < 100 && modalField.GetValue(window) is null; attempt++) await Task.Delay(40);
                if (modalField.GetValue(window) is null) throw new InvalidOperationException(name + " dialog did not open");
                await Task.Delay(400);
                var host = window.FindControl<Panel>("ModalOverlayHost")!;
                var focused = window.FocusManager?.GetFocusedElement() as Control;
                if (focused is null || !host.GetVisualDescendants().Contains(focused)) throw new InvalidOperationException(name + " focus escaped dialog");
                var card = ((Panel)host.Children[0]).Children.OfType<Border>().Last();
                if (card.Bounds.X < 0 || card.Bounds.Y < 0 || card.Bounds.Right > host.Bounds.Width + 1 || card.Bounds.Bottom > host.Bounds.Height + 1)
                    throw new InvalidOperationException(name + " card is outside the window");
                SaveNativeWindow(window, Path.Combine(output, name + ".png"));
                if (name == "split")
                {
                    var addPreset = host.GetVisualDescendants().OfType<Button>().First(button => button.Content is string text && (text.Contains("Новый пресет", StringComparison.OrdinalIgnoreCase) || text.Contains("New preset", StringComparison.OrdinalIgnoreCase)));
                    addPreset.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                    await Task.Delay(100);
                    if (!host.GetVisualDescendants().OfType<TextBox>().Any(box => box.IsEffectivelyVisible && (box.PlaceholderText?.Contains("Preset", StringComparison.OrdinalIgnoreCase) == true || box.PlaceholderText?.Contains("Название", StringComparison.OrdinalIgnoreCase) == true)))
                        throw new InvalidOperationException("Preset editor did not expand");
                    SaveNativeWindow(window, Path.Combine(output, "split-preset.png"));
                    var running = host.GetVisualDescendants().OfType<Button>().FirstOrDefault(button => button.GetVisualDescendants().OfType<TextBlock>().Any(text => text.Text?.Contains(options.Language == "en" ? "running apps" : "запущенных") == true));
                    if (running is not null)
                    {
                        running.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                        running.BringIntoView();
                        await Task.Delay(150);
                        SaveNativeWindow(window, Path.Combine(output, "split-running.png"));
                    }
                }
                ((Control)host.Children[0]).RaiseEvent(new KeyEventArgs { RoutedEvent = InputElement.KeyDownEvent, Key = Key.Escape });
                if (operation is not null) await operation.WaitAsync(TimeSpan.FromSeconds(8));
                for (var attempt = 0; attempt < 100 && modalField.GetValue(window) is not null; attempt++) await Task.Delay(40);
                if (modalField.GetValue(window) is not null || host.IsVisible) throw new InvalidOperationException(name + " did not close and restore shell");
                results.Add(new { name, focusInDialog = true, fitsWindow = true, escapeCloses = true });
                await Task.Delay(200);
            }
            var setup = new SetupWindow(new SetupWindowOptions { Language = options.Language, RestoreExistingSession = false, AllowCancel = false,
                ImportUrlAsync = (_, _, _) => throw new InvalidOperationException("Remote imports disabled in QA") });
            typeof(MainWindow).GetField("_activeSetupView", flags)!.SetValue(window, setup);
            var showTab = typeof(MainWindow).GetMethod("ShowTab", flags)!;
            showTab.Invoke(window, [Enum.Parse(showTab.GetParameters()[0].ParameterType, "Setup"), false]);
            await Task.Delay(200);
            SaveNativeWindow(window, Path.Combine(output, "setup-username.png"));
            var render = typeof(SetupWindow).GetMethod("Render", flags)!;
            render.Invoke(setup, [Enum.Parse(render.GetParameters()[0].ParameterType, "Code"), null, null, false]);
            await Task.Delay(100);
            SaveNativeWindow(window, Path.Combine(output, "setup-code.png"));
            var routes = new[] {
                new WindowsRoute("auto", "qa:auto", "AUTO", "Автоподбор", "✦", "AUTO", "automatic"),
                new WindowsRoute("ru-de", "qa:ru-de", "RU-DE", "Россия → Германия", "", "CHAIN", "ru → de"),
                new WindowsRoute("nl-vless", "qa:nl", "NL", "Нидерланды", "", "VLESS", "vless"),
                new WindowsRoute("nl-trojan", "qa:trojan", "NL", "Нидерланды", "", "TROJAN", "trojan") };
            var subscription = new TelegramKeysSnapshot(true, true, 1, [], null, routes, []);
            typeof(SetupWindow).GetField("_subscription", flags)!.SetValue(setup, subscription);
            typeof(SetupWindow).GetField("_selectedRoute", flags)!.SetValue(setup, routes[0]);
            typeof(SetupWindow).GetField("_payload", flags)!.SetValue(setup, new SetupSubscriptionPayload(null, new TelegramAccount("qa", "qa", false, null), subscription));
            render.Invoke(setup, [Enum.Parse(render.GetParameters()[0].ParameterType, "Success"), null, null, false]);
            await Task.Delay(150);
            SaveNativeWindow(window, Path.Combine(output, "setup-route.png"));
            results.Add(new { name = "setup", states = new[] { "username", "code", "route" }, remoteRequests = 0 });
            File.WriteAllText(Path.Combine(output, "results.json"), JsonSerializer.Serialize(new { passed = true, options.Language, options.Theme, options.Width, options.Height, results }, new JsonSerializerOptions { WriteIndented = true }));
            Console.WriteLine("UI repair QA passed: " + output);
        }
        catch (Exception error)
        {
            Program.QaExitCode = 1;
            Console.Error.WriteLine(error);
            SaveNativeWindow(window, Path.Combine(output, "failure.png"));
        }
        finally { window.Close(); }
    }

    private static void SaveNativeWindow(MainWindow window, string path)
    {
        var content = (Control)window.Content!;
        using var bitmap = new RenderTargetBitmap(new PixelSize((int)Math.Ceiling(content.Bounds.Width), (int)Math.Ceiling(content.Bounds.Height)), new Vector(96, 96));
        bitmap.Render(content);
        using var stream = File.Create(path);
        bitmap.Save(stream, PngBitmapEncoderOptions.Default);
    }
}
