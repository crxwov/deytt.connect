using Avalonia;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;
using Avalonia.Markup.Xaml;
using Avalonia.Media.Fonts;

[assembly: AvaloniaTestApplication(typeof(DeyttConnect.Windows.HeadlessTests.TestAppBuilder))]

namespace DeyttConnect.Windows.HeadlessTests;

internal sealed class TestApplication : Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);
}

internal static class TestAppBuilder
{
    public static AppBuilder BuildAvaloniaApp() => AppBuilder.Configure<TestApplication>()
        .ConfigureFonts(fontManager => fontManager.AddFontCollection(new DeyttConnect.Windows.DeyttFontCollection()))
        .UseHeadless(new AvaloniaHeadlessPlatformOptions());
}
