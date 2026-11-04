using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private sealed record RouteCountryProbeRequest(string CountryCode, string[] RouteTags);

    private async Task ToggleVpnAsync()
    {
        if (_vpnActionInProgress || _vpnCancelInProgress || _routeProbeInProgress)
            return;

        var wasConnected = IsVpnTunnelActive(_vpnSnapshot.State);
        var operationVersion = ++_vpnOperationVersion;
        _vpnCancelRequested = false;
        _vpnActionInProgress = true;
        RenderActiveTabPreservingScroll();
        try
        {
            if (IsVpnTunnelActive(_vpnSnapshot.State))
            {
                _vpnSnapshot = await _tunnelClient.DisconnectAsync();
                _vpnServiceAvailable = true;
                RenderActiveTabPreservingScroll();
                return;
            }

            if (_keysSnapshot?.ProfileJson is not { Length: > 0 } profile ||
                _routes.FirstOrDefault(route => route.Id == _selectedRoute) is not { } route)
            {
                _vpnSnapshot = new WindowsTunnelSnapshot("error",
                    Copy("Сначала загрузите подписку и выберите маршрут.",
                        "Load a subscription and choose a route first."));
                RenderActiveTabPreservingScroll();
                return;
            }

            _vpnSnapshot = new WindowsTunnelSnapshot("starting",
                Copy("Подготавливаем профиль VPN…", "Preparing VPN profile…"), route.Tag);
            RenderActiveTabPreservingScroll();
            var awgConfig = GetAwgConfig(route);
            if (IsAwgProtocol(route.Protocol) && awgConfig is null)
            {
                _vpnSnapshot = new WindowsTunnelSnapshot("error",
                    Copy("Профиль AmneziaWG не загружен. Обновите подписку.",
                        "The AmneziaWG profile is unavailable. Refresh the subscription."), route.Tag);
                RenderActiveTabPreservingScroll();
                return;
            }
            var splitConfig = WindowsSplitTunnelStore.Load();
            _vpnConnectTask = _tunnelClient.ConnectAsync(profile, route.Tag, awgConfig,
                splitConfig.CustomDomains, splitConfig.CustomProcesses);
            var result = await _vpnConnectTask;
            if (operationVersion == _vpnOperationVersion && !_vpnCancelRequested)
            {
                _vpnSnapshot = result;
                _vpnServiceAvailable = true;
                RenderActiveTabPreservingScroll();
            }
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            if (operationVersion == _vpnOperationVersion && !_vpnCancelRequested)
            {
                _vpnSnapshot = VpnServiceUnavailableSnapshot();
                _vpnServiceAvailable = false;
                RenderActiveTabPreservingScroll();
            }
        }
        finally
        {
            if (operationVersion == _vpnOperationVersion)
            {
                _vpnActionInProgress = false;
                _vpnConnectTask = null;
            }
            if (operationVersion == _vpnOperationVersion && !_vpnCancelRequested)
                UpdateMapLocationAfterVpnAction(wasConnected);
            RenderActiveTabPreservingScroll();
        }

        if (operationVersion == _vpnOperationVersion && _vpnSnapshot.State is ("starting" or "checking"))
            _ = MonitorVpnConnectionAsync();
    }

    private async Task CancelVpnConnectionAsync()
    {
        if (_vpnCancelInProgress || _vpnSnapshot.State is not ("starting" or "checking"))
            return;

        _vpnCancelRequested = true;
        _vpnCancelInProgress = true;
        ++_vpnOperationVersion;
        var pendingConnect = _vpnConnectTask;
        _vpnSnapshot = new WindowsTunnelSnapshot("stopping",
            Copy("Останавливаем подключение…", "Stopping the connection…"));
        RenderActiveTabPreservingScroll();
        try
        {
            // The service serializes connect and disconnect. Wait for a pending connect
            // before stopping so an earlier disconnect cannot be overtaken by connect.
            if (pendingConnect is not null)
            {
                try
                {
                    await pendingConnect;
                }
                catch (Exception)
                {
                    // A failed connect still needs a final disconnect.
                }
            }
            _vpnSnapshot = await _tunnelClient.DisconnectAsync();
            _vpnServiceAvailable = true;
            UpdateMapLocationAfterVpnAction(wasConnected: false);
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            _vpnSnapshot = VpnServiceUnavailableSnapshot();
            _vpnServiceAvailable = false;
        }
        finally
        {
            _vpnCancelInProgress = false;
            _vpnActionInProgress = false;
            _vpnConnectTask = null;
            RenderActiveTabPreservingScroll();
        }
    }

    private async Task MonitorVpnConnectionAsync()
    {
        for (var attempt = 0; attempt < 45; attempt++)
        {
            await Task.Delay(TimeSpan.FromSeconds(1));
            if (_vpnSnapshot.State is not ("starting" or "checking"))
                return;
            await RefreshVpnStatusAsync();
        }
    }

    private bool HasProbeSpeedToken() =>
        _sessionToken is { Length: >= 32 and <= 256 } token &&
        token.All(character => character is >= '\x21' and <= '\x7e');

    private string? RouteCountryProbeStatus(string countryCode)
    {
        if (!_routeCountryProbeStates.TryGetValue(countryCode, out var state))
            return null;

        return state switch
        {
            RouteCountryProbeState.Queued => Copy("Диагностика в очереди…", "Route checks queued…"),
            RouteCountryProbeState.Running => HasProbeSpeedToken()
                ? Copy("Пинг проверяется · скорость в очереди", "Checking latency · speed queued")
                : Copy("Проверяем пинг · скорость после входа в Telegram",
                    "Checking latency · sign in to measure speed"),
            RouteCountryProbeState.Cancelled => Copy("Замеры остановлены · можно повторить",
                "Measurements stopped · retry when ready"),
            RouteCountryProbeState.NeedsDisconnect => Copy("Отключите VPN для проверки маршрутов",
                "Disconnect the VPN to check routes"),
            RouteCountryProbeState.ServiceUnavailable => Copy("Служба VPN недоступна · проверьте настройки",
                "VPN service unavailable · check settings"),
            RouteCountryProbeState.Failed => Copy("Не удалось проверить · можно повторить замеры",
                "Check failed · retry the measurements"),
            _ => null,
        };
    }

    private void QueueRouteCountryProbe(string countryCode, string[] routeTags)
    {
        if (routeTags.Length == 0)
            return;

        if (_activeRouteProbeCountry == countryCode)
        {
            if (!_routeProbeCancelRequested)
                return;
        }
        else if (_pendingRouteProbeNodes.ContainsKey(countryCode))
        {
            return;
        }

        if (_keysSnapshot?.ProfileJson is not { Length: > 0 } || _routes.Count == 0)
        {
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Failed;
            RenderActiveTabPreservingScroll();
            return;
        }
        if (!_vpnServiceAvailable)
        {
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.ServiceUnavailable;
            RenderActiveTabPreservingScroll();
            return;
        }
        if (_vpnActionInProgress || WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State))
        {
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.NeedsDisconnect;
            RenderActiveTabPreservingScroll();
            return;
        }

        var availableTags = _routes.Select(route => route.Tag).ToHashSet(StringComparer.Ordinal);
        var request = new RouteCountryProbeRequest(countryCode,
            routeTags.Where(availableTags.Contains).Distinct(StringComparer.Ordinal).ToArray());
        if (request.RouteTags.Length == 0)
            return;

        // A new expansion is a new measurement. Old numbers must not look live
        // while this country's routes wait in the queue.
        var freshResults = new Dictionary<string, WindowsRouteProbeResult>(_routeProbeResults,
            StringComparer.Ordinal);
        foreach (var routeTag in request.RouteTags)
            freshResults.Remove(routeTag);
        _routeProbeResults = freshResults;
        var node = _pendingRouteProbeQueue.AddLast(request);
        _pendingRouteProbeNodes[countryCode] = node;
        _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Queued;
        RenderActiveTabPreservingScroll();
        StartNextCountryProbe();
    }

    private void CollapseRouteCountryProbe(string countryCode)
    {
        var wasQueued = false;
        if (_pendingRouteProbeNodes.Remove(countryCode, out var node))
        {
            _pendingRouteProbeQueue.Remove(node);
            wasQueued = true;
        }

        if (_activeRouteProbeCountry == countryCode && _routeProbeInProgress)
        {
            _routeCountryProbeGeneration++;
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Cancelled;
            if (!_routeProbeCancelRequested)
                _ = CancelRouteProbeAsync();
        }
        else if (wasQueued)
        {
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Cancelled;
        }
        else
        {
            _routeCountryProbeStates.Remove(countryCode);
        }
    }

    private void SuspendRouteCountryProbes()
    {
        _routeCountryProbeGeneration++;
        _pendingRouteProbeQueue.Clear();
        _pendingRouteProbeNodes.Clear();
        _expandedRouteCountries.Clear();
        _routeCountryProbeStates.Clear();
        if (_activeRouteProbeCountry is not null && _routeProbeInProgress &&
            !_routeProbeCancelRequested)
            _ = CancelRouteProbeAsync();
    }

    private void StartNextCountryProbe()
    {
        if (_activeTab != MainTab.Routes || _routeProbeInProgress || _vpnActionInProgress ||
            _activeRouteProbeCountry is not null ||
            _pendingRouteProbeQueue.Count == 0)
            return;

        if (!_vpnServiceAvailable || WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State))
        {
            var blockedState = !_vpnServiceAvailable
                ? RouteCountryProbeState.ServiceUnavailable
                : RouteCountryProbeState.NeedsDisconnect;
            foreach (var request in _pendingRouteProbeQueue)
                _routeCountryProbeStates[request.CountryCode] = blockedState;
            _pendingRouteProbeQueue.Clear();
            _pendingRouteProbeNodes.Clear();
            RenderActiveTabPreservingScroll();
            return;
        }

        while (_pendingRouteProbeQueue.First is { } node)
        {
            _pendingRouteProbeQueue.RemoveFirst();
            _pendingRouteProbeNodes.Remove(node.Value.CountryCode);
            if (!_expandedRouteCountries.Contains(node.Value.CountryCode))
            {
                _routeCountryProbeStates[node.Value.CountryCode] = RouteCountryProbeState.Cancelled;
                continue;
            }

            _activeRouteProbeCountry = node.Value.CountryCode;
            _routeCountryProbeStates[node.Value.CountryCode] = RouteCountryProbeState.Running;
            _ = ProbeRoutesAsync(node.Value.RouteTags, node.Value.CountryCode);
            return;
        }
    }

    private async Task ProbeRoutesAsync(IReadOnlyCollection<string>? requestedRouteTags = null,
        string? countryCode = null)
    {
        var countryProbeGeneration = _routeCountryProbeGeneration;
        if (_routeProbeInProgress || _vpnActionInProgress ||
            WindowsTunnelHealth.IsTunnelActive(_vpnSnapshot.State))
            return;
        if (_keysSnapshot?.ProfileJson is not { Length: > 0 } profile || _routes.Count == 0)
            return;
        if (!_vpnServiceAvailable)
        {
            if (countryCode is null)
                ShowTab(MainTab.Settings);
            else
                _routeCountryProbeStates[countryCode] = RouteCountryProbeState.ServiceUnavailable;
            return;
        }

        _routeProbeInProgress = true;
        _routeProbeCancelRequested = false;
        RenderProbeProgressPreservingFocus();
        string[]? activeRouteTags = null;
        var previousRouteResults = new Dictionary<string, WindowsRouteProbeResult>(StringComparer.Ordinal);
        try
        {
            var availableTags = _routes.Select(route => route.Tag).ToHashSet(StringComparer.Ordinal);
            var routeTags = requestedRouteTags is { Count: > 0 }
                ? requestedRouteTags.Where(availableTags.Contains).Distinct(StringComparer.Ordinal).ToArray()
                : availableTags.ToArray();
            if (routeTags.Length == 0)
                return;
            activeRouteTags = routeTags;
            _activeRouteProbeTags = new HashSet<string>(routeTags, StringComparer.Ordinal);
            foreach (var routeTag in routeTags)
            {
                if (_routeProbeResults.TryGetValue(routeTag, out var previous))
                    previousRouteResults[routeTag] = previous;
            }
            ApplyProbeProgress(routeTags.Select(routeTag => new WindowsRouteProbeResult(
                routeTag, null, null, null, "latency", 1)).ToArray(), routeTags);
            var awgProfiles = (_keysSnapshot?.AwgProfiles ?? [])
                .ToDictionary(item => item.RouteId, item => item.Config, StringComparer.Ordinal);
            var speedToken = HasProbeSpeedToken() ? _sessionToken! : string.Empty;
            var probeTask = _tunnelClient.ProbeAsync(profile, routeTags, _probeMethod, speedToken,
                awgProfiles);
            while (!probeTask.IsCompleted)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(400));
                if (probeTask.IsCompleted)
                    break;
                try
                {
                    var progress = await _tunnelClient.GetProbeProgressAsync();
                    if (progress.State == "probe_progress" && progress.ProbeResults is { } current &&
                        (countryCode is null || countryProbeGeneration == _routeCountryProbeGeneration))
                        ApplyProbeProgress(current, routeTags);
                }
                catch (Exception error) when (IsTunnelTransportError(error))
                {
                    // The long-running probe result remains authoritative if a progress poll is missed.
                }
            }
            var result = await probeTask;
            if (countryCode is not null &&
                (countryProbeGeneration != _routeCountryProbeGeneration || _activeTab != MainTab.Routes))
            {
                RestoreProbeResults(routeTags, previousRouteResults);
                return;
            }
            if (result.State == "probe_complete" && result.ProbeResults is { } probes)
            {
                var updatedResults = requestedRouteTags is null
                    ? new Dictionary<string, WindowsRouteProbeResult>(StringComparer.Ordinal)
                    : new Dictionary<string, WindowsRouteProbeResult>(_routeProbeResults, StringComparer.Ordinal);
                foreach (var probe in probes)
                    updatedResults[probe.RouteTag] = probe;
                foreach (var routeTag in routeTags)
                    if (!updatedResults.ContainsKey(routeTag))
                        updatedResults[routeTag] = new WindowsRouteProbeResult(routeTag, null, null,
                            Copy("Служба не вернула результат. Повторите замер.",
                                "The service returned no result. Retry the measurement."), "error");
                _routeProbeResults = updatedResults;
            }
            else if (result.State == "probe_cancelled")
            {
                RestoreProbeResults(routeTags, previousRouteResults);
            }
            else
            {
                MarkRouteProbeFailure(routeTags, result.Detail);
                if (countryCode is null)
                    ShowInfoDialog(Copy("Не удалось проверить маршруты", "Could not check routes"),
                        result.Detail);
                else
                    _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Failed;
            }
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
            if (countryCode is not null &&
                (countryProbeGeneration != _routeCountryProbeGeneration || _activeTab != MainTab.Routes))
            {
                RestoreProbeResults(activeRouteTags ?? requestedRouteTags ?? [], previousRouteResults);
                return;
            }
            if (activeRouteTags is not null)
                MarkRouteProbeFailure(activeRouteTags,
                    Copy("Служба VPN не ответила. Повторите замер.",
                        "The VPN service did not respond. Retry the measurement."));
            if (countryCode is null)
                ShowInfoDialog(Copy("Не удалось проверить маршруты", "Could not check routes"),
                    Copy("Служба VPN не ответила. Проверьте её установку и повторите попытку.",
                        "The VPN service did not respond. Check its installation and try again."));
            else
                _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Failed;
        }
        finally
        {
            _activeRouteProbeTags.Clear();
            _routeProbeInProgress = false;
            _routeProbeCancelRequested = false;
            if (countryCode is not null && _activeRouteProbeCountry == countryCode)
            {
                _activeRouteProbeCountry = null;
                if (_routeCountryProbeStates.GetValueOrDefault(countryCode) == RouteCountryProbeState.Running)
                    _routeCountryProbeStates.Remove(countryCode);
            }
            RenderProbeProgressPreservingFocus();
            StartNextCountryProbe();
        }
    }

    private async Task CancelRouteProbeAsync()
    {
        if (!_routeProbeInProgress || _routeProbeCancelRequested)
            return;
        if (_activeRouteProbeCountry is { } countryCode)
            _routeCountryProbeStates[countryCode] = RouteCountryProbeState.Cancelled;
        MarkProbeCancellationRequested();
        try
        {
            await _tunnelClient.CancelProbeAsync();
        }
        catch (Exception error) when (IsTunnelTransportError(error))
        {
        }
    }

    internal void MarkProbeCancellationRequested()
    {
        if (!_routeProbeInProgress || _routeProbeCancelRequested)
            return;
        _routeProbeCancelRequested = true;
        RenderProbeProgressPreservingFocus();
    }

    internal bool ApplyProbeProgress(IReadOnlyList<WindowsRouteProbeResult> progress,
        IReadOnlyCollection<string> requestedRouteTags)
    {
        var updated = new Dictionary<string, WindowsRouteProbeResult>(_routeProbeResults, StringComparer.Ordinal);
        var changed = false;
        foreach (var item in progress)
        {
            if (!requestedRouteTags.Contains(item.RouteTag, StringComparer.Ordinal))
                continue;
            if (updated.TryGetValue(item.RouteTag, out var previous) && previous == item)
                continue;
            updated[item.RouteTag] = item;
            changed = true;
        }

        if (!changed)
            return false;
        _routeProbeResults = updated;
        RenderProbeProgressPreservingFocus();
        return true;
    }

    private void RestoreProbeResults(IEnumerable<string> routeTags,
        IReadOnlyDictionary<string, WindowsRouteProbeResult> previousResults)
    {
        var updated = new Dictionary<string, WindowsRouteProbeResult>(_routeProbeResults, StringComparer.Ordinal);
        foreach (var routeTag in routeTags)
            updated.Remove(routeTag);
        foreach (var (routeTag, result) in previousResults)
            updated[routeTag] = result;
        _routeProbeResults = updated;
    }

    private void MarkRouteProbeFailure(IEnumerable<string> routeTags, string? detail)
    {
        var message = string.IsNullOrWhiteSpace(detail)
            ? Copy("Не удалось проверить маршрут. Повторите замер.",
                "Could not check the route. Retry the measurement.")
            : detail;
        var updated = new Dictionary<string, WindowsRouteProbeResult>(_routeProbeResults,
            StringComparer.Ordinal);
        foreach (var routeTag in routeTags)
            updated[routeTag] = new WindowsRouteProbeResult(routeTag, null, null, message, "error");
        _routeProbeResults = updated;
    }

    private void RenderProbeProgressPreservingFocus()
    {
        var focused = FocusManager?.GetFocusedElement() as Control;
        var focusableButtons = PageHost.GetVisualDescendants().OfType<Button>()
            .Where(button => button.Focusable && button.IsEffectivelyVisible).ToArray();
        var focusIndex = focused is Button focusedButton
            ? Array.IndexOf(focusableButtons, focusedButton)
            : -1;
        var focusAutomationId = focused is Button
            ? AutomationProperties.GetAutomationId(focused)
            : null;
        var offset = PageScroll.Offset;
        ShowTab(_activeTab);
        PageScroll.Offset = offset;
        if (focusIndex < 0)
            return;
        var renderedTab = _activeTab;
        var renderedPage = PageHost.Children.OfType<Control>().SingleOrDefault();

        Dispatcher.UIThread.Post(() =>
        {
            if (_activeTab != renderedTab || renderedPage is null || PageHost.Children.Count != 1 ||
                !ReferenceEquals(PageHost.Children[0], renderedPage))
                return;

            var buttons = PageHost.GetVisualDescendants().OfType<Button>()
                .Where(button => button.Focusable && button.IsEffectivelyVisible).ToArray();
            var stableTargetIndex = string.IsNullOrEmpty(focusAutomationId)
                ? -1
                : Array.FindIndex(buttons, button => AutomationProperties.GetAutomationId(button) == focusAutomationId);
            var targetIndex = stableTargetIndex >= 0 ? stableTargetIndex : focusIndex;
            if (targetIndex < 0 || buttons.Length == 0)
                return;
            targetIndex = Math.Clamp(targetIndex, 0, buttons.Length - 1);
            var focusTarget = buttons[targetIndex];
            if (!focusTarget.IsEffectivelyEnabled)
                focusTarget = buttons.Select((button, index) => (button, index))
                    .Where(item => item.button.IsEffectivelyEnabled)
                    .OrderBy(item => Math.Abs(item.index - targetIndex))
                    .Select(item => item.button)
                    .FirstOrDefault()!;
            focusTarget?.Focus();
        }, DispatcherPriority.Input);
    }
}
