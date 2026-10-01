using Avalonia;
using Avalonia.Media.Fonts;
using System;
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
    bool RouteProbeCancelRequested = false);

sealed class Program
{
    // Initialization code. Don't use any Avalonia, third-party APIs or any
    // SynchronizationContext-reliant code before AppMain is called: things aren't initialized
    // yet and stuff might break.
    [STAThread]
    public static void Main(string[] args)
    {
        var appArguments = args.ToList();
        for (var index = appArguments.Count - 1; index >= 0; index--)
        {
            if (!SetupWindow.TryGetSubscriptionUrl(appArguments[index], out var subscriptionUrl))
                continue;
            App.InitialImportUrl = subscriptionUrl;
            appArguments.RemoveAt(index);
        }

        BuildAvaloniaApp().StartWithClassicDesktopLifetime(appArguments.ToArray());
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
