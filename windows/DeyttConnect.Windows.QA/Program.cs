using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Fonts;
using Avalonia.Markup.Xaml;
using System.Diagnostics;
using System.Text.Json;
using System.Threading;
using DeyttConnect.Protocol;
using DeyttConnect.Windows;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Views;

namespace DeyttConnect.Windows.QA;

internal sealed class QaApp : Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var options = QaOptions.Parse(desktop.Args ?? []);
            desktop.MainWindow = BuildWindow(options);
        }

        base.OnFrameworkInitializationCompleted();
    }

    private static MainWindow BuildWindow(QaOptions options)
    {
        var routes = new[]
        {
            new WindowsRoute("auto", "qa:auto", "AUTO", "Автоподбор", "✦", "AUTO", "Автоподбор"),
            new WindowsRoute("ru-de", "qa:ru-de", "RU-DE", "Россия → Германия", "🇷🇺→🇩🇪", "CHAIN", "RU → DE"),
            new WindowsRoute("nl-vless", "qa:nl-vless", "NL", "Нидерланды", "🇳🇱", "VLESS", "VLESS"),
        };
        var account = options.SignedIn
            ? new TelegramAccount("qa_fixture", "QA Demo", false,
                new TelegramSubscription(true, true, "qa", "QA Demo", "2099-12-31T00:00:00Z",
                    false, 3, 1, null, null))
            : null;
        var subscription = options.SignedIn
            ? new TelegramKeysSnapshot(true, false, 0,
                [new TelegramHappDevice(0, false, "QA fixture", "Synthetic desktop", "now")],
                "{\"qa_fixture\":true}", routes, [])
            : null;
        var tunnel = options.State switch
        {
            "connected" => new WindowsTunnelSnapshot("connected", "QA fixture · synthetic state", "qa:nl-vless",
                HealthCheckedAt: DateTimeOffset.UtcNow),
            "connecting" => new WindowsTunnelSnapshot("starting", "QA fixture · synthetic state", "qa:nl-vless"),
            "error" => new WindowsTunnelSnapshot("error", "QA fixture · synthetic error"),
            _ => new WindowsTunnelSnapshot("disconnected", "QA fixture · synthetic state"),
        };
        var probeResult = options.ProbeStage is { } stage
            ? new WindowsRouteProbeResult("qa:nl-vless",
                stage is "waiting_speed" or "download" or "complete" ? 42 : null,
                stage == "complete" ? 4_000_000 : null,
                stage == "error" ? "Synthetic route check error." : null, stage,
                stage is "latency" or "retry" ? 2 : null,
                stage == "download" ? 5 * 1024 * 1024 : null,
                stage == "download" ? 32 * 1024 * 1024 : null)
            : null;
        var fixture = new QaHomeFixture(options.SignedIn, "ru", "nl-vless", options.Tab,
            account, subscription, routes, tunnel, probeResult);
        var window = new MainWindow(fixture)
        {
            Title = $"deytt./connect · QA fixture ({(options.SignedIn ? "signed-in" : "signed-out")}, {options.State})",
            Width = options.Width,
            Height = options.Height,
        };

        var content = (Control)window.Content!;
        window.Content = null;
        var shell = new Grid { RowDefinitions = new RowDefinitions("Auto,*") };
        shell.Children.Add(new Border
        {
            Background = new SolidColorBrush(Color.Parse("#63391D")),
            Padding = new Thickness(16, 8),
            Child = new TextBlock
            {
                Text = $"QA FIXTURE · VPN: {options.State.ToUpperInvariant()} · SYNTHETIC · NO LIVE SESSION",
                FontFamily = "fonts:DEYTT#JetBrains Mono",
                FontSize = 12,
                FontWeight = FontWeight.SemiBold,
                Foreground = Brushes.White,
                HorizontalAlignment = HorizontalAlignment.Center,
            },
        });
        Grid.SetRow(content, 1);
        shell.Children.Add(content);
        window.Content = shell;
        return window;
    }

    private sealed record QaOptions(bool SignedIn, string State, string Tab, string? ProbeStage,
        int Width, int Height)
    {
        public static QaOptions Parse(string[] args)
        {
            var signedIn = args.Contains("--signed-in", StringComparer.Ordinal);
            var state = Value(args, "--state") ?? "disconnected";
            if (state is not ("disconnected" or "connected" or "connecting" or "error"))
                throw new ArgumentException("--state must be disconnected, connected, connecting, or error.");
            var tab = Value(args, "--tab") ?? "home";
            if (tab is not ("home" or "routes" or "profile" or "settings"))
                throw new ArgumentException("--tab must be home, routes, profile, or settings.");
            var probeStage = Value(args, "--probe-stage");
            if (probeStage is not (null or "latency" or "retry" or "waiting_speed" or "download" or "error" or "cancelled" or "complete"))
                throw new ArgumentException("--probe-stage must be latency, retry, waiting_speed, download, error, cancelled, or complete.");
            var width = Dimension(args, "--width", 1240, 520);
            var height = Dimension(args, "--height", 820, 420);
            return new QaOptions(signedIn, state, tab, probeStage, width, height);
        }

        private static int Dimension(string[] args, string key, int defaultValue, int minimum)
        {
            var value = Value(args, key);
            if (value is null)
                return defaultValue;
            if (!int.TryParse(value, out var dimension) || dimension < minimum)
                throw new ArgumentException($"{key} must be an integer of at least {minimum}.");
            return dimension;
        }

        private static string? Value(string[] args, string key)
        {
            var index = Array.IndexOf(args, key);
            return index >= 0 && index + 1 < args.Length ? args[index + 1] : null;
        }
    }
}

internal static class Program
{
    private static int _crashReportWritten;

    [STAThread]
    public static void Main(string[] args)
    {
        RegisterSanitizedCrashDiagnostics();
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
    }

    private static void RegisterSanitizedCrashDiagnostics()
    {
        var path = Environment.GetEnvironmentVariable("DEYTT_QA_CRASH_REPORT");
        if (string.IsNullOrWhiteSpace(path) ||
            !string.Equals(Path.GetFileName(path), "fixture-crash.json", StringComparison.Ordinal))
            return;

        AppDomain.CurrentDomain.UnhandledException += (_, eventArgs) =>
        {
            if (eventArgs.ExceptionObject is Exception exception)
                WriteSanitizedCrashReport(path, exception);
        };
        TaskScheduler.UnobservedTaskException += (_, eventArgs) =>
            WriteSanitizedCrashReport(path, eventArgs.Exception);
    }

    private static void WriteSanitizedCrashReport(string path, Exception exception)
    {
        if (Interlocked.Exchange(ref _crashReportWritten, 1) != 0)
            return;

        try
        {
            var methods = new StackTrace(exception, false).GetFrames() ?? [];
            var report = new
            {
                schema = 1,
                exception_type = exception.GetType().Name,
                stack_methods = methods.Take(16).Select(frame =>
                {
                    var method = frame.GetMethod();
                    return string.Join(".", new[] { method?.DeclaringType?.Name, method?.Name }
                        .Where(name => !string.IsNullOrWhiteSpace(name)));
                }).ToArray(),
            };
            var temporary = path + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(report));
            File.Move(temporary, path);
        }
        catch
        {
            // Crash diagnostics are optional; never replace the original failure.
        }
    }

    private static AppBuilder BuildAvaloniaApp() => AppBuilder.Configure<QaApp>()
        .UsePlatformDetect()
        .ConfigureFonts(fontManager => fontManager.AddFontCollection(new DeyttConnect.Windows.DeyttFontCollection()))
        .LogToTrace();
}
