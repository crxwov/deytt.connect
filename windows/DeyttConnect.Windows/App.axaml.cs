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
#if DEBUG
        this.AttachDeveloperTools();
#endif
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.ShutdownMode = Avalonia.Controls.ShutdownMode.OnExplicitShutdown;
            var mainWindow = new MainWindow(InitialImportUrl);
            desktop.MainWindow = mainWindow;
            if (OperatingSystem.IsWindows())
            {
                Program.StartActivationListener(mainWindow);
                desktop.Exit += (_, _) => Program.StopActivationListener();
            }
        }

        base.OnFrameworkInitializationCompleted();
    }
}
