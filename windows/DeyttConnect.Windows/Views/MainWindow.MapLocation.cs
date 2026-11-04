using System;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private string GetMapOriginLabel()
    {
        if (!_mapRegionEnabled)
            return Copy("Место скрыто", "Location hidden");
        if (_mapOriginLocation is { } origin)
            return string.IsNullOrWhiteSpace(origin.PlaceLabel)
                ? Copy("Регион определён", "Region detected")
                : origin.PlaceLabel;
        if (_mapLocationCancellation is not null && !_mapLocationRequestIsEgress)
            return Copy("Определяем регион…", "Looking up region…");
        if (IsVpnDisplayConnected())
            return Copy("Исходная сеть скрыта", "Origin network hidden");
        if (_mapLocationIssue is not null)
            return Copy("Регион недоступен", "Region unavailable");
        return Copy("Ожидает определения", "Waiting for lookup");
    }

    private string GetMapOriginHint()
    {
        if (!_mapRegionEnabled)
            return Copy("отключено в настройках", "disabled in settings");
        if (_mapOriginLocation is not null)
            return Copy("примерно по IP · только в памяти", "approx. by IP · memory only");
        if (IsVpnDisplayConnected())
            return Copy("не GPS · доступно после отключения VPN", "not GPS · available after disconnecting VPN");
        return _mapLocationIssue is not null
            ? Copy("проверьте соединение и включите снова", "check connection and toggle on again")
            : Copy("примерно по IP", "approx. by IP");
    }

    private string GetMapEgressHint()
    {
        if (!_mapRegionEnabled)
            return Copy("VPN-трафик проверен · регион скрыт", "VPN traffic verified · region hidden");
        if (_mapEgressLocation is { } egress && !string.IsNullOrWhiteSpace(egress.PlaceLabel))
            return $"{Copy("выход по IP", "IP egress")}: {egress.PlaceLabel}";
        if (_mapLocationCancellation is not null && _mapLocationRequestIsEgress)
            return Copy("Определяем выход по IP…", "Looking up IP egress…");
        return _mapLocationIssue is not null
            ? Copy("регион выхода недоступен", "egress region unavailable")
            : Copy("место определяется после подключения", "location resolves after connecting");
    }

    private string SelectedExitPlaceLabel(WindowsRoute? route) => route?.CountryCode switch
    {
        "NL" => Copy("Амстердам", "Amsterdam"),
        "DE" or "RU-DE" => Copy("Франкфурт", "Frankfurt"),
        "FI" => Copy("Хельсинки", "Helsinki"),
        "IT" => Copy("милан", "milan"),
        "RU" => Copy("Санкт-Петербург", "Saint Petersburg"),
        _ => RouteTitle(),
    };

    private void UpdateMapLocationForVpnState(string previousState, string currentState)
    {
        if (!_mapRegionEnabled || !_mapRegionConsentGranted ||
            currentState is "starting" or "checking" or "health_checking" or "degraded" or "unknown")
            return;

        var previousConnected = IsVpnTunnelActive(previousState);
        var currentConnected = IsVpnTunnelActive(currentState);
        if (!_mapLocationStateInitialized)
        {
            _mapLocationStateInitialized = true;
            StartMapLocationLookup(currentConnected);
            return;
        }
        if (previousConnected == currentConnected)
            return;

        CancelMapLocationLookup();
        _mapLocationIssue = null;
        _mapEgressLocation = null;
        _mapEgressLookupAttempted = false;
        if (currentConnected)
        {
            StartMapLocationLookup(useTunnel: true);
            return;
        }
        if (_mapOriginLocation is null)
        {
            _mapOriginLookupAttempted = false;
            StartMapLocationLookup(useTunnel: false);
        }
    }

    private void UpdateMapLocationAfterVpnAction(bool wasConnected)
    {
        if (!_mapRegionEnabled || !_mapRegionConsentGranted ||
            (!_vpnServiceAvailable && _vpnSnapshot.State == "error") ||
            _vpnSnapshot.State is "starting" or "checking" or "health_checking" or "degraded" or "unknown")
            return;
        var isConnected = IsVpnTunnelActive(_vpnSnapshot.State);
        if (wasConnected && isConnected)
        {
            CancelMapLocationLookup();
            _mapEgressLocation = null;
            _mapEgressLookupAttempted = false;
            _mapLocationIssue = null;
            _mapLocationStateInitialized = true;
            StartMapLocationLookup(useTunnel: true);
            return;
        }
        UpdateMapLocationForVpnState(wasConnected ? "connected" : "disconnected",
            isConnected ? "connected" : "disconnected");
    }

    private void StartMapLocationLookup(bool useTunnel)
    {
        if (!_mapRegionEnabled || !_mapRegionConsentGranted || _mapLocationCancellation is not null)
            return;
        if (useTunnel ? _mapEgressLookupAttempted : _mapOriginLookupAttempted)
            return;

        if (useTunnel)
            _mapEgressLookupAttempted = true;
        else
            _mapOriginLookupAttempted = true;
        _mapLocationRequestIsEgress = useTunnel;
        _mapLocationIssue = null;
        var cancellation = new CancellationTokenSource();
        _mapLocationCancellation = cancellation;
        _ = LoadMapLocationAsync(useTunnel, cancellation);
        RenderActiveTabPreservingScroll();
    }

    private async Task LoadMapLocationAsync(bool useTunnel, CancellationTokenSource cancellation)
    {
        try
        {
            var location = await IpNetworkLocationClient.FetchAsync(cancellation.Token);
            if (!cancellation.IsCancellationRequested && _mapRegionEnabled && _mapRegionConsentGranted &&
                (useTunnel == IsVpnDisplayConnected()))
            {
                if (useTunnel)
                    _mapEgressLocation = location;
                else
                    _mapOriginLocation = location;
            }
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
        }
        catch (Exception error) when (error is HttpRequestException or IOException or JsonException or OperationCanceledException)
        {
            if (!cancellation.IsCancellationRequested)
                _mapLocationIssue = Copy("Не удалось определить регион по IP.", "Could not resolve the IP region.");
        }
        finally
        {
            if (ReferenceEquals(_mapLocationCancellation, cancellation))
            {
                _mapLocationCancellation = null;
                _mapLocationRequestIsEgress = false;
                RenderActiveTabPreservingScroll();
            }
            cancellation.Dispose();
        }
    }

    private void CancelMapLocationLookup()
    {
        var cancellation = _mapLocationCancellation;
        _mapLocationCancellation = null;
        _mapLocationRequestIsEgress = false;
        cancellation?.Cancel();
    }

    private async Task UpdateMapRegionEnabledAsync(bool enabled)
    {
        if (enabled && !_mapRegionConsentGranted)
        {
            var accepted = await ConfirmMapRegionConsentAsync();
            _mapRegionConsentAsked = true;
            if (!accepted)
                return;
            _mapRegionConsentGranted = true;
        }

        _mapRegionEnabled = enabled;
        _mapLocationIssue = null;
        if (!enabled)
        {
            CancelMapLocationLookup();
            _mapOriginLocation = null;
            _mapEgressLocation = null;
            _mapOriginLookupAttempted = false;
            _mapEgressLookupAttempted = false;
            _mapLocationStateInitialized = false;
            return;
        }

        _mapOriginLookupAttempted = false;
        _mapEgressLookupAttempted = false;
        _mapLocationStateInitialized = true;
        StartMapLocationLookup(IsVpnDisplayConnected());
    }

    private async Task ShowInitialMapConsentIfNeededAsync()
    {
        if (!OperatingSystem.IsWindows() || _mapRegionConsentAsked)
            return;

        var accepted = await ConfirmMapRegionConsentAsync();
        _mapRegionConsentAsked = true;
        _mapRegionConsentGranted = accepted;
        _mapRegionEnabled = accepted;
        _mapLocationStateInitialized = false;
        SavePreferences();
        ShowTab(_activeTab);
        if (accepted)
            _ = RefreshVpnStatusAsync();
    }

    private Task<bool> ConfirmMapRegionConsentAsync() => ConfirmDialogAsync(
        Copy("Показывать ваш примерный регион?", "Show your approximate region?"),
        Copy("Точка определяется по публичному IP через ipinfo.io — это не GPS и на выбор маршрута не влияет. Запрос видит внешний сервис; приложение не сохраняет адрес или координаты, а держит точку только в памяти до закрытия.",
            "The point is estimated from your public IP through ipinfo.io — this is not GPS and does not affect route selection. An external service receives the request; the app does not save your address or coordinates and keeps the point only in memory until you close it."),
        Copy("Показывать на карте", "Show on map"),
        Copy("не сейчас", "not now"));
}
