using Avalonia;
using Avalonia.Media.Fonts;
using Avalonia.Threading;
using System;
using System.IO.Pipes;
using System.Text;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Views;

[assembly: System.Runtime.CompilerServices.InternalsVisibleTo("DeyttConnect.Windows.QA")]
[assembly: System.Runtime.CompilerServices.InternalsVisibleTo("DeyttConnect.Windows.HeadlessTests")]
[assembly: System.Runtime.CompilerServices.InternalsVisibleTo("DeyttConnect.Windows.Tests")]

namespace DeyttConnect.Windows;

internal sealed record QaHomeFixture(
    bool SignedIn,
    string Language,
    string SelectedRoute,
    string Tab,
    TelegramAccount? Account,
    TelegramKeysSnapshot? Subscription,
    IReadOnlyList<WindowsRoute> Routes,
    WindowsTunnelSnapshot Tunnel,
    WindowsRouteProbeResult? ProbeResult = null,
    bool RouteProbeInProgress = false,
    bool RouteProbeCancelRequested = false,
    string? ExpandedRouteCountry = null,
    bool DisableNativeMapInitialization = false,
    string? ThemePreference = null);

sealed class Program
{
    private const string SingleInstanceMutexName = @"Local\DEYTT.Connect.Windows";
    private const string ActivationPipeName = "DEYTT.Connect.Windows.Activate";
    private static CancellationTokenSource? _activationCancellation;

    // Initialization code. Don't use any Avalonia, third-party APIs or any
    // SynchronizationContext-reliant code before AppMain is called: things aren't initialized
    // yet and stuff might break.
    [STAThread]
    public static void Main(string[] args)
    {
        var appArguments = args.ToList();
        string? importUrl = null;
        for (var index = appArguments.Count - 1; index >= 0; index--)
        {
            if (!SetupWindow.TryGetSubscriptionUrl(appArguments[index], out var subscriptionUrl))
                continue;
            importUrl = subscriptionUrl;
            appArguments.RemoveAt(index);
        }

        Mutex? instanceMutex = null;
        if (OperatingSystem.IsWindows())
        {
            instanceMutex = new Mutex(initiallyOwned: true, SingleInstanceMutexName, out var isPrimaryInstance);
            if (!isPrimaryInstance)
            {
                instanceMutex.Dispose();
                SignalPrimaryInstance(importUrl);
                return;
            }
        }

        App.InitialImportUrl = importUrl;

        try
        {
            BuildAvaloniaApp().StartWithClassicDesktopLifetime(appArguments.ToArray());
        }
        finally
        {
            StopActivationListener();
            instanceMutex?.Dispose();
        }
    }

    internal static void StartActivationListener(MainWindow mainWindow)
    {
        _activationCancellation = new CancellationTokenSource();
        _ = ListenForActivationAsync(mainWindow, _activationCancellation.Token);
    }

    internal static void StopActivationListener()
    {
        _activationCancellation?.Cancel();
        _activationCancellation?.Dispose();
        _activationCancellation = null;
    }

    private static void SignalPrimaryInstance(string? importUrl)
    {
        for (var attempt = 0; attempt < 10; attempt++)
        {
            try
            {
                using var client = new NamedPipeClientStream(".", ActivationPipeName,
                    PipeDirection.Out, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                client.Connect(timeout: 150);
                using var writer = new StreamWriter(client, new UTF8Encoding(false), leaveOpen: true);
                writer.WriteLine(string.IsNullOrWhiteSpace(importUrl)
                    ? string.Empty
                    : Convert.ToBase64String(Encoding.UTF8.GetBytes(importUrl)));
                writer.Flush();
                return;
            }
            catch (TimeoutException) when (attempt < 9)
            {
                Thread.Sleep(50);
            }
            catch (TimeoutException)
            {
                return;
            }
            catch (IOException) when (attempt < 9)
            {
                Thread.Sleep(50);
            }
            catch (IOException)
            {
                return;
            }
        }
    }

    private static async Task ListenForActivationAsync(MainWindow mainWindow, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await using var server = new NamedPipeServerStream(ActivationPipeName, PipeDirection.In,
                    maxNumberOfServerInstances: 1, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(cancellationToken).ConfigureAwait(false);
                using var reader = new StreamReader(server, Encoding.UTF8, detectEncodingFromByteOrderMarks: false,
                    leaveOpen: true);
                var importUrl = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
                string? decodedImportUrl = null;
                if (!string.IsNullOrWhiteSpace(importUrl))
                {
                    try
                    {
                        var decoded = Encoding.UTF8.GetString(Convert.FromBase64String(importUrl));
                        if (SetupWindow.TryGetSubscriptionUrl(decoded, out var normalized))
                            decodedImportUrl = normalized;
                    }
                    catch (FormatException)
                    {
                        // Ignore malformed activation payloads and still raise the existing window.
                    }
                }
                Dispatcher.UIThread.Post(() => mainWindow.ActivateFromExternalLaunch(decodedImportUrl));
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }
            catch (IOException) when (!cancellationToken.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(100, cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    return;
                }
            }
        }
    }

    // Avalonia configuration, don't remove; also used by visual designer.
    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .ConfigureFonts(fontManager => fontManager.AddFontCollection(new DeyttFontCollection()))
            .LogToTrace();
}

sealed class DeyttFontCollection : EmbeddedFontCollection
{
    public DeyttFontCollection() : base(
        new Uri("fonts:DEYTT", UriKind.Absolute),
        new Uri("avares://DeyttConnect.Windows/Assets/Fonts", UriKind.Absolute))
    {
    }
}
