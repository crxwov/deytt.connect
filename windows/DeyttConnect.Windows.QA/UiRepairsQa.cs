using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.VisualTree;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Controls;
using DeyttConnect.Windows.Views;
using Microsoft.Web.WebView2.Core;

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
        var routeGlobeField = typeof(MainWindow).GetField("_routeGlobe", flags)!;
        var setupPagePreviewField = typeof(MainWindow).GetField("_setupPagePreview", flags)!;
        var showTabMethod = typeof(MainWindow).GetMethod("ShowTab", flags)!;
        var tabType = showTabMethod.GetParameters()[0].ParameterType;
        void Navigate(string tab) => showTabMethod.Invoke(window, [Enum.Parse(tabType, tab), true]);
        Bitmap? syntheticSetupPreview = null;
        try
        {
            Navigate("Home");
            await WaitForAtlasStageAsync(window, routeGlobeField, "home");
            foreach (var (name, method) in new[] { ("language", "ShowLanguagePickerAsync"), ("probe", "ShowProbeMethodPickerAsync"), ("split", "ShowSplitTunnelingInfo") })
            {
                Navigate("Settings");
                await WaitForAtlasStageAsync(window, routeGlobeField, "settings");
                var operation = typeof(MainWindow).GetMethod(method, flags)!.Invoke(window, null) as Task;
                for (var attempt = 0; attempt < 100 && modalField.GetValue(window) is null; attempt++) await Task.Delay(40);
                if (modalField.GetValue(window) is null) throw new InvalidOperationException(name + " dialog did not open");
                await Task.Delay(400);
                var host = window.FindControl<Panel>("ModalOverlayHost")!;
                var focused = window.FocusManager?.GetFocusedElement() as Control;
                if (focused is null || !host.GetVisualDescendants().Contains(focused)) throw new InvalidOperationException(name + " focus escaped dialog");
                var card = host.GetVisualDescendants().OfType<Border>()
                    .SingleOrDefault(border => AutomationProperties.GetAutomationId(border) == "GlassDialogCard")
                    ?? throw new InvalidOperationException(name + " dialog card automation id is missing");
                if (card.Bounds.X < 0 || card.Bounds.Y < 0 || card.Bounds.Right > host.Bounds.Width + 1 || card.Bounds.Bottom > host.Bounds.Height + 1)
                    throw new InvalidOperationException(name + " card is outside the window");
                ValidateDialogBackdrop(host, name, output);
                SaveNativeWindow(window, Path.Combine(output, name + ".png"));
                if (name == "split")
                {
                    var splitFileBefore = GetSplitTunnelFileFingerprint();
                    T FindByAutomationId<T>(string id) where T : Control => host.GetVisualDescendants().OfType<T>()
                        .SingleOrDefault(control => AutomationProperties.GetAutomationId(control) == id)
                        ?? throw new InvalidOperationException($"Split control '{id}' is missing");
                    static void Click(Control control) => control.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                    static bool HasSelectionMarker(Button button)
                    {
                        var name = AutomationProperties.GetName(button) ?? string.Empty;
                        return name.Contains("selected", StringComparison.OrdinalIgnoreCase) ||
                               name.Contains("выбрано", StringComparison.OrdinalIgnoreCase);
                    }

                    // Exercise both modes and assert that the selected-state marker follows the active mode.
                    var vpnOnlyMode = FindByAutomationId<Button>("SplitModeVpnOnly");
                    Click(vpnOnlyMode);
                    await Task.Delay(80);
                    if (!HasSelectionMarker(vpnOnlyMode) || HasSelectionMarker(FindByAutomationId<Button>("SplitModeBypass")))
                        throw new InvalidOperationException("VPN-only mode selection marker did not update");
                    var bypassMode = FindByAutomationId<Button>("SplitModeBypass");
                    Click(bypassMode);
                    await Task.Delay(80);
                    if (!HasSelectionMarker(bypassMode) || HasSelectionMarker(FindByAutomationId<Button>("SplitModeVpnOnly")))
                        throw new InvalidOperationException("Bypass mode selection marker did not update");

                    // Add and remove synthetic rules in the in-memory QA fixture only.
                    const string syntheticDomain = "qa-fixture.example.invalid";
                    var domainInput = FindByAutomationId<TextBox>("SplitDomainInput");
                    domainInput.Text = syntheticDomain;
                    Click(FindByAutomationId<Button>("SplitDomainAdd"));
                    await Task.Delay(80);
                    var removeDomainId = "SplitDomainRemove_" + syntheticDomain;
                    var removeDomain = FindByAutomationId<Button>(removeDomainId);
                    removeDomain.BringIntoView();
                    Click(removeDomain);
                    await Task.Delay(80);
                    if (host.GetVisualDescendants().OfType<Button>().Any(button => AutomationProperties.GetAutomationId(button) == removeDomainId))
                        throw new InvalidOperationException("Synthetic domain rule remained after removal");

                    const string syntheticProcess = "qa-fixture-profile.exe";
                    var processInput = FindByAutomationId<TextBox>("SplitProcessInput");
                    processInput.Text = syntheticProcess;
                    Click(FindByAutomationId<Button>("SplitProcessAdd"));
                    await Task.Delay(80);
                    var removeProcessId = "SplitProcessRemove_" + syntheticProcess;
                    var removeProcess = FindByAutomationId<Button>(removeProcessId);
                    removeProcess.BringIntoView();
                    Click(removeProcess);
                    await Task.Delay(80);
                    if (host.GetVisualDescendants().OfType<Button>().Any(button => AutomationProperties.GetAutomationId(button) == removeProcessId))
                        throw new InvalidOperationException("Synthetic process rule remained after removal");

                    // Create a throwaway preset, switch between it and an existing preset, then delete it.
                    var initialPresetButtons = host.GetVisualDescendants().OfType<Button>()
                        .Where(button => AutomationProperties.GetAutomationId(button)?.StartsWith("SplitPresetSelect_", StringComparison.Ordinal) == true)
                        .ToArray();
                    if (initialPresetButtons.Length == 0) throw new InvalidOperationException("Split settings have no selectable preset");
                    var initialPresetId = AutomationProperties.GetAutomationId(initialPresetButtons[0])!;
                    var addPreset = FindByAutomationId<Button>("SplitPresetAdd");
                    addPreset.BringIntoView();
                    await Task.Delay(150);
                    Click(addPreset);
                    var presetName = FindByAutomationId<TextBox>("SplitPresetName");
                    if (!presetName.IsEffectivelyVisible) throw new InvalidOperationException("Preset editor did not expand");
                    presetName.BringIntoView();
                    await Task.Delay(150);
                    presetName.Text = "QA temporary preset";
                    var focusedSurface = presetName.GetVisualDescendants().OfType<Border>().Single(border => border.Name == "PART_BorderElement");
                    if (!ReferenceEquals(focusedSurface.Background, presetName.Background))
                        throw new InvalidOperationException("Focused preset field lost its glass background");
                    ValidateDialogBackdrop(host, "split-preset", output);
                    SaveNativeWindow(window, Path.Combine(output, "split-preset.png"));
                    Click(FindByAutomationId<Button>("SplitPresetCreate"));
                    await Task.Delay(100);
                    var createdPreset = host.GetVisualDescendants().OfType<Button>()
                        .SingleOrDefault(button => AutomationProperties.GetAutomationId(button)?.StartsWith("SplitPresetSelect_", StringComparison.Ordinal) == true &&
                                                   AutomationProperties.GetAutomationId(button) != initialPresetId)
                        ?? throw new InvalidOperationException("Synthetic preset was not created");
                    var createdPresetSelectId = AutomationProperties.GetAutomationId(createdPreset)!;
                    var createdPresetId = createdPresetSelectId["SplitPresetSelect_".Length..];
                    if (!HasSelectionMarker(createdPreset)) throw new InvalidOperationException("New preset was not selected after creation");
                    Click(FindByAutomationId<Button>(initialPresetId));
                    await Task.Delay(80);
                    if (!HasSelectionMarker(FindByAutomationId<Button>(initialPresetId)))
                        throw new InvalidOperationException("Existing preset did not become selected");
                    Click(FindByAutomationId<Button>(createdPresetSelectId));
                    await Task.Delay(80);
                    if (!HasSelectionMarker(FindByAutomationId<Button>(createdPresetSelectId)))
                        throw new InvalidOperationException("Synthetic preset could not be selected again");
                    var deletePreset = FindByAutomationId<Button>("SplitPresetDelete_" + createdPresetId);
                    deletePreset.BringIntoView();
                    Click(deletePreset);
                    await Task.Delay(100);
                    if (host.GetVisualDescendants().OfType<Button>().Any(button => AutomationProperties.GetAutomationId(button) == createdPresetSelectId))
                        throw new InvalidOperationException("Synthetic preset remained after deletion");
                    if (host.GetVisualDescendants().OfType<Button>().Count(button => AutomationProperties.GetAutomationId(button)?.StartsWith("SplitPresetSelect_", StringComparison.Ordinal) == true) != initialPresetButtons.Length)
                        throw new InvalidOperationException("Preset count did not return to its original value after deleting the synthetic preset");

                    var splitFileAfter = GetSplitTunnelFileFingerprint();
                    if (splitFileBefore != splitFileAfter)
                        throw new InvalidOperationException("QA split interactions changed the user's split_tunnel.json");
                    SaveNativeWindow(window, Path.Combine(output, "split-interactions.png"));

                    var running = FindByAutomationId<Button>("SplitRunningPickerToggle");
                    running.BringIntoView();
                    Click(running);
                    var runningSearch = FindByAutomationId<TextBox>("SplitRunningSearch");
                    runningSearch.BringIntoView();
                    for (var attempt = 0; attempt < 20 && !runningSearch.IsEffectivelyVisible; attempt++) await Task.Delay(50);
                    if (!runningSearch.IsEffectivelyVisible) throw new InvalidOperationException("Running apps picker did not become visible");
                    await Task.Delay(200);
                    ValidateDialogBackdrop(host, "split-running", output);
                    SaveNativeWindow(window, Path.Combine(output, "split-running.png"));
                    if (GetSplitTunnelFileFingerprint() != splitFileBefore)
                        throw new InvalidOperationException("Opening the running-app picker changed the user's split_tunnel.json");
                }
                ((Control)host.Children[0]).RaiseEvent(new KeyEventArgs { RoutedEvent = InputElement.KeyDownEvent, Key = Key.Escape });
                if (operation is not null) await operation.WaitAsync(TimeSpan.FromSeconds(8));
                for (var attempt = 0; attempt < 100 && modalField.GetValue(window) is not null; attempt++) await Task.Delay(40);
                if (modalField.GetValue(window) is not null || host.IsVisible) throw new InvalidOperationException(name + " did not close and restore shell");
                var restored = await WaitForAtlasStageAsync(window, routeGlobeField, "settings");
                results.Add(new { name, focusInDialog = true, fitsWindow = true, escapeCloses = true,
                    pagePreview = true, transparentOverlay = true, noFullscreenScrim = true,
                    restoredStage = restored.Snapshot.SelectedTab });
                await Task.Delay(200);
            }
            Navigate("Home");
            var homeStage = await WaitForAtlasStageAsync(window, routeGlobeField, "home");
            syntheticSetupPreview = await homeStage.Globe.CapturePreviewAsync()
                ?? throw new InvalidOperationException("Setup background capture returned no bitmap");
            setupPagePreviewField.SetValue(window, syntheticSetupPreview);
            SaveBitmap(syntheticSetupPreview, Path.Combine(output, "setup-backdrop.png"));
            var setup = new SetupWindow(new SetupWindowOptions { Language = options.Language, RestoreExistingSession = false, AllowCancel = false,
                ImportUrlAsync = (_, _, _) => throw new InvalidOperationException("Remote imports disabled in QA") });
            typeof(MainWindow).GetField("_activeSetupView", flags)!.SetValue(window, setup);
            var showTab = typeof(MainWindow).GetMethod("ShowTab", flags)!;
            showTab.Invoke(window, [Enum.Parse(showTab.GetParameters()[0].ParameterType, "Setup"), false]);
            typeof(MainWindow).GetMethod("RenderActiveTabPreservingScroll", flags)!.Invoke(window, [true]);
            await Task.Delay(200);
            if (window.FindControl<ScrollViewer>("PageScroll")!.VerticalScrollBarVisibility != Avalonia.Controls.Primitives.ScrollBarVisibility.Auto)
                throw new InvalidOperationException("Setup actions cannot scroll into view in a compact window");
            if (!window.GetVisualDescendants().OfType<Image>().Any(image => ReferenceEquals(image.Source, syntheticSetupPreview)))
                throw new InvalidOperationException("Setup page did not render the captured WebView backdrop");
            SaveNativeWindow(window, Path.Combine(output, "setup-username.png"));
            var render = typeof(SetupWindow).GetMethod("Render", flags)!;
            var setupViewStateType = render.GetParameters()[0].ParameterType;
            async Task RenderSetupStateAsync(string state, string file)
            {
                render.Invoke(setup, [Enum.Parse(setupViewStateType, state), "QA synthetic state", null, false]);
                await Task.Delay(100);
                SaveNativeWindow(window, Path.Combine(output, file));
            }
            render.Invoke(setup, [Enum.Parse(render.GetParameters()[0].ParameterType, "Code"), null, null, false]);
            await Task.Delay(100);
            SaveNativeWindow(window, Path.Combine(output, "setup-code.png"));
            await RenderSetupStateAsync("Refreshing", "setup-loading.png");
            await RenderSetupStateAsync("RefreshError", "setup-error.png");
            await RenderSetupStateAsync("NoSubscription", "setup-no-subscription.png");
            await RenderSetupStateAsync("DeviceConflict", "setup-conflict.png");
            await RenderSetupStateAsync("ImportUrl", "setup-url.png");
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
            results.Add(new { name = "setup", states = new[] { "username", "code", "loading", "error", "no-subscription", "conflict", "url", "route" }, pageBackdrop = true, remoteRequests = 0 });
            File.WriteAllText(Path.Combine(output, "results.json"), JsonSerializer.Serialize(new { passed = true, options.Language, options.Theme, options.Width, options.Height, results }, new JsonSerializerOptions { WriteIndented = true }));
            Console.WriteLine("UI repair QA passed: " + output);
        }
        catch (Exception error)
        {
            Program.QaExitCode = 1;
            Console.Error.WriteLine(error);
            SaveNativeWindow(window, Path.Combine(output, "failure.png"));
        }
        finally
        {
            window.Close();
            if (syntheticSetupPreview is not null)
            {
                if (ReferenceEquals(setupPagePreviewField.GetValue(window), syntheticSetupPreview))
                    setupPagePreviewField.SetValue(window, null);
                syntheticSetupPreview.Dispose();
            }
        }
    }

    private static async Task<(RouteGlobeWebView Globe, CoreWebView2 Core, AtlasSnapshot Snapshot)> WaitForAtlasStageAsync(
        MainWindow window, FieldInfo routeGlobeField, string expectedStage)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        var pageReadyField = typeof(RouteGlobeWebView).GetField("_pageReady", flags)
            ?? throw new MissingFieldException(typeof(RouteGlobeWebView).FullName, "_pageReady");
        var deadline = DateTimeOffset.UtcNow.AddSeconds(30);
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (routeGlobeField.GetValue(window) is RouteGlobeWebView globe &&
                typeof(RouteGlobeWebView).GetField("_coreWebView2", flags)?.GetValue(globe) is CoreWebView2 core &&
                pageReadyField.GetValue(globe) is true)
            {
                try
                {
                    var snapshot = await ReadAtlasSnapshotAsync(core);
                    if (snapshot.SelectedTab == expectedStage && snapshot.ActiveStageVisible is true && snapshot.HostActive is true)
                        return (globe, core, snapshot);
                }
                catch (InvalidOperationException)
                {
                    // Detaching the WebView for a dialog may replace its native adapter.
                }
            }
            await Task.Delay(100);
        }
        throw new TimeoutException($"The actual WebView {expectedStage} stage did not become ready within 30 seconds.");
    }

    private static void ValidateDialogBackdrop(Panel host, string name, string output)
    {
        var overlay = host.Children.OfType<Grid>().SingleOrDefault()
            ?? throw new InvalidOperationException(name + " dialog overlay grid is missing");
        if (overlay.Background is not ISolidColorBrush overlayBrush || overlayBrush.Color.A != 0)
            throw new InvalidOperationException(name + " dialog overlay is not transparent");
        var preview = overlay.GetVisualDescendants().OfType<Image>()
            .SingleOrDefault(image => image.Name == "DialogPagePreview");
        if (preview?.Source is not Bitmap backdrop)
            throw new InvalidOperationException(name + " dialog page preview bitmap is missing");
        if (preview.Bounds.Height <= 0 || Math.Abs(preview.Bounds.Width / preview.Bounds.Height -
                backdrop.PixelSize.Width / (double)backdrop.PixelSize.Height) > .01)
            throw new InvalidOperationException(name + " page preview is stretched or outside its coordinate space");
        var solidFullscreenBorder = overlay.GetVisualDescendants().OfType<Border>().Any(border =>
        {
            if (AutomationProperties.GetAutomationId(border) == "GlassDialogCard" ||
                border.Bounds.Width < overlay.Bounds.Width - 1 || border.Bounds.Height < overlay.Bounds.Height - 1)
                return false;
            return border.Background is ISolidColorBrush brush && brush.Color.A > 0;
        });
        if (solidFullscreenBorder)
            throw new InvalidOperationException(name + " dialog contains an opaque fullscreen border scrim");

        SaveBitmap(backdrop, Path.Combine(output, name + "-backdrop.png"));
    }

    private static void SaveBitmap(Bitmap bitmap, string path)
    {
        using var stream = File.Create(path);
        bitmap.Save(stream, PngBitmapEncoderOptions.Default);
    }

    private static void SaveNativeWindow(MainWindow window, string path)
    {
        var content = (Control)window.Content!;
        using var bitmap = new RenderTargetBitmap(new PixelSize((int)Math.Ceiling(content.Bounds.Width), (int)Math.Ceiling(content.Bounds.Height)), new Vector(96, 96));
        bitmap.Render(content);
        using var stream = File.Create(path);
        bitmap.Save(stream, PngBitmapEncoderOptions.Default);
    }

    private static string? GetSplitTunnelFileFingerprint()
    {
        const BindingFlags flags = BindingFlags.Static | BindingFlags.NonPublic;
        var path = typeof(WindowsSplitTunnelStore).GetProperty("SplitTunnelPath", flags)?.GetValue(null) as string;
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path)) return null;
        return Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path)));
    }
}
