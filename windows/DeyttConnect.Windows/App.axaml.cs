using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using DeyttConnect.Windows.Views;

namespace DeyttConnect.Windows;

public partial class App : Application
{
    public static string? InitialImportUrl { get; set; }

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.MainWindow = new MainWindow(InitialImportUrl);
        }

        base.OnFrameworkInitializationCompleted();
    }
}
