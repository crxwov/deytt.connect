using System.Reflection;
using Avalonia.Headless.XUnit;
using Avalonia.Threading;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Controls;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Views;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class UserFlowHomeTests
{
    [AvaloniaFact]
    public void Shell_navigation_cannot_escape_mandatory_first_run()
    {
        const BindingFlags flags = BindingFlags.NonPublic | BindingFlags.Instance;
        var window = new MainWindow(new QaHomeFixture(false, "ru", "auto", "home", null, null, [], new WindowsTunnelSnapshot("disconnected", "test"), DisableNativeMapInitialization: true));
        var setup = new SetupWindow(new SetupWindowOptions { AllowCancel = false, RestoreExistingSession = false });
        var cancelled = false;
        setup.BackRequested += (_, _) => cancelled = true;
        try
        {
            window.Show(); Dispatcher.UIThread.RunJobs();
            typeof(MainWindow).GetField("_activeSetupView", flags)!.SetValue(window, setup);
            var showTab = typeof(MainWindow).GetMethod("ShowTab", flags)!;
            var enumType = showTab.GetParameters()[0].ParameterType;
            showTab.Invoke(window, [Enum.Parse(enumType, "Setup"), false]);
            typeof(MainWindow).GetMethod("RequestNavigation", flags)!.Invoke(window, [Enum.Parse(enumType, "Profile")]);
            Assert.False(cancelled);
        }
        finally { window.Close(); }
    }

    [AvaloniaTheory]
    [InlineData(null, "", "уточняем выбранный выход…", "")]
    [InlineData("unknown", "", "уточняем выбранный выход…", "")]
    [InlineData("route:FI:VLESS", "FI", "хельсинки · финляндия", "./vless+ws")]
    public void Automatic_connection_uses_confirmed_engine_exit_without_guessing(string? activeTag, string countryCode, string city, string protocol)
    {
        var routes = new WindowsRoute[]
        {
            new("auto", "auto-tag", "AUTO", "Автоподбор", "✦", "AUTO", "Автоподбор"),
            new("route:FI:VLESS", "route:FI:VLESS", "FI", "Финляндия", "🇫🇮", "VLESS", "VLESS"),
        };
        var fixture = new QaHomeFixture(true, "ru", "auto", "home", null, null, routes,
            new WindowsTunnelSnapshot("connected", "test", "auto-tag", HealthCheckedAt: DateTimeOffset.UtcNow, ActiveRouteTag: activeTag),
            DisableNativeMapInitialization: true);
        var window = new MainWindow(fixture);
        try
        {
            window.Show(); Dispatcher.UIThread.RunJobs();
            var presentation = (HomePresentationSnapshot)typeof(MainWindow).GetMethod("BuildHomePresentation", BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(window, null)!;
            Assert.Equal("автоподбор", presentation.Country);
            Assert.Equal(countryCode, presentation.CountryCode);
            Assert.Equal(city, presentation.City);
            Assert.Equal(protocol, presentation.Protocol);
            Assert.Equal("отключить", presentation.ActionLabel);
            var key = typeof(MainWindow).GetMethod("ConfirmedHomeAutoRouteKey", BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(window, null);
            Assert.Equal(countryCode == "FI" ? "fi" : null, key);
        }
        finally { window.Close(); }
    }
}
