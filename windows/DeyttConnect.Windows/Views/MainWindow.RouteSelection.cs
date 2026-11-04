using DeyttConnect.Windows.Services;
using System;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Media;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private StackPanel NewPage(string kicker, string title)
    {
        var page = new StackPanel { Spacing = 0 };
        page.Children.Add(DeyttTheme.TextBlock(title, _compactLayout ? 29 : 36, DeyttTheme.Text,
            FontWeight.Bold, DeyttTheme.InterTight));
        page.Children.Add(DeyttTheme.Spacer(_compactLayout ? 14 : 22));
        return page;
    }

    private void AddSection(StackPanel page, string title)
    {
        page.Children.Add(DeyttTheme.Spacer(_compactLayout ? 17 : 24));
        page.Children.Add(DeyttTheme.SectionLabel(title));
        page.Children.Add(DeyttTheme.Spacer(_compactLayout ? 8 : 10));
    }

    private string RouteTitle() => _routes.FirstOrDefault(route => route.Id == _selectedRoute) is { } route
        ? route.CountryCode == "AUTO"
            ? Copy("Автоподбор", "Auto-select")
            : $"{route.Flag} {RouteCountryName(route)} · {route.ProfileName ?? route.ProtocolName}"
        : Copy("Автоподбор", "Auto-select");

    private string RouteTitleForTag(string? tag) =>
        _routes.FirstOrDefault(route => route.Tag == tag) is { } route
            ? route.CountryCode == "AUTO"
                ? Copy("Автоподбор", "Auto-select")
                : $"{route.Flag} {RouteCountryName(route)} · {route.ProfileName ?? route.ProtocolName}"
            : RouteTitle();

    private string? GetAwgConfig(WindowsRoute route) =>
        IsAwgProtocol(route.Protocol) && route.ProfileId is { } profileId
            ? _keysSnapshot?.AwgProfiles.FirstOrDefault(profile => profile.RouteId == profileId)?.Config
            : null;

    private static bool IsAwgProtocol(string? protocol) => protocol is "AWG15" or "AWG31";

    private static string AwgProtocolTitle(string? protocol) => protocol switch
    {
        "AWG15" => "amneziawg 1.5",
        "AWG31" => "amneziawg 3.1",
        _ => "amneziawg",
    };

    private string RouteCountryName(WindowsRoute route) => route.CountryCode switch
    {
        "NL" => Copy("Нидерланды", "Netherlands"),
        "RU" => Copy("Россия", "Russia"),
        "DE" => Copy("Германия", "Germany"),
        "FI" => Copy("Финляндия", "Finland"),
        "IT" => Copy("италия", "italy"),
        "AWG_UNKNOWN" => Copy("Другие регионы", "Other regions"),
        _ => route.CountryName,
    };

    private void SelectRoute(string routeId)
    {
        if (_vpnActionInProgress)
            return;
        var route = _routes.FirstOrDefault(value => value.Id == routeId);
        if (route is null)
        {
            if (_sessionToken is null)
            {
                ShowSignInDialog();
                return;
            }
            ShowInfoDialog(Copy("Маршрут не загружен", "Route not loaded"),
                Copy("Обнови подписку, чтобы выбрать этот выход.", "Refresh the subscription to select this exit."));
            return;
        }

        if (_selectedRoute == routeId)
            return;

        if (WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State))
        {
            _ = ConfirmAndSelectRouteAsync(route);
            return;
        }

        ApplyRouteSelection(route);
    }

    private async Task ConfirmAndSelectRouteAsync(WindowsRoute route)
    {
        var confirmed = await ConfirmDialogAsync(
            Copy("Изменить точку выхода?", "Change exit node?"),
            Copy("Текущее соединение завершится. После выбора подключитесь снова, чтобы применить новый маршрут.",
                "The current connection will end. Connect again after choosing to apply the new route."),
            Copy("Остановить и сменить", "Stop and change"),
            Copy("Оставить подключение", "Keep connection"));
        if (!confirmed || _vpnActionInProgress)
            return;

        _vpnActionInProgress = true;
        RenderActiveTabPreservingScroll();
        try
        {
            var disconnected = await _tunnelClient.DisconnectAsync();
            if (disconnected.State != "disconnected")
            {
                _vpnSnapshot = disconnected;
                ShowInfoDialog(Copy("Не удалось остановить VPN", "Could not stop the VPN"),
                    disconnected.Detail ?? Copy("Повторите попытку после проверки службы VPN.",
                        "Check the VPN service and try again."));
                return;
            }

            _vpnSnapshot = disconnected;
            ApplyRouteSelection(route);
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            ShowInfoDialog(Copy("Не удалось остановить VPN", "Could not stop the VPN"),
                Copy("Служба VPN не ответила. Проверьте её состояние и повторите попытку.",
                    "The VPN service did not respond. Check its status and try again."));
        }
        finally
        {
            _vpnActionInProgress = false;
            UpdateMapLocationAfterVpnAction(wasConnected: true);
            RenderActiveTabPreservingScroll();
        }
    }

    private void ApplyRouteSelection(WindowsRoute route)
    {
        _selectedRoute = route.Id;
        SavePreferences();
        ShowTab(MainTab.Home);
    }
}
