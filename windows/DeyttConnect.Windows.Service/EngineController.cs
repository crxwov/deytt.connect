using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Text.Json.Nodes;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
using System.Security.AccessControl;
using System.Security.Principal;
using DeyttConnect.Protocol;

namespace DeyttConnect.Windows.Service;

internal sealed record ServiceSnapshot(string State, string Detail,
    string? RouteTag = null, DateTimeOffset? ConnectedAt = null,
    IReadOnlyList<ServiceProbeResult>? ProbeResults = null,
    DateTimeOffset? HealthCheckedAt = null);

internal sealed class EngineController
{
    private const int MaximumProfileBytes = 2 * 1024 * 1024;
    private const int MaximumAwgProfileBytes = 512 * 1024;
    private const int MaximumAwgProfilesTotalBytes = 4 * 1024 * 1024;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly SemaphoreSlim _healthRecheck = new(0, 1);
    private Process? _engine;
    private EngineProcessLifetime? _engineLifetime;
    private CancellationTokenSource? _verification;
    private CancellationTokenSource? _probeCancellation;
    private TunnelHealthMonitorLifetime? _healthMonitor;
    private string? _profilePath;
    private string? _routeTag;
    private ServiceSnapshot _snapshot = new("disconnected", "VPN выключен");
    private readonly object _probeProgressLock = new();
    private readonly Dictionary<string, ServiceProbeResult> _probeProgress = new(StringComparer.Ordinal);
    private int _consecutiveHealthFailures;

    internal sealed record AwgResolution(string Profile, IReadOnlySet<string> FailedEndpointTags);
    internal sealed record ProbeAwgResolution(string Profile, IReadOnlyList<ProbeRouteEndpoint> Endpoints,
        IReadOnlyList<ServiceProbeResult> FailedResults);

    public ServiceSnapshot Snapshot => Volatile.Read(ref _snapshot);

    public ServiceSnapshot GetProbeProgress()
    {
        lock (_probeProgressLock)
            return new ServiceSnapshot("probe_progress", "Проверка маршрутов выполняется.",
                ProbeResults: _probeProgress.Values.Take(32).ToArray());
    }

    public async Task<ServiceSnapshot> ConnectAsync(string profile, string routeTag, string? awgConfig,
        CancellationToken cancellationToken)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            StopCore();
            if (System.Text.Encoding.UTF8.GetByteCount(profile) is <= 0 or > MaximumProfileBytes)
                return SetError("Профиль подписки недопустимого размера.");
            if (awgConfig is not null && System.Text.Encoding.UTF8.GetByteCount(awgConfig) > MaximumAwgProfileBytes)
                return SetError("Профиль AmneziaWG недопустимого размера.");

            SetSnapshot(new ServiceSnapshot("starting", "Подготовка VPN…", routeTag));

            var upstreamInterface = FindActiveHappTunnel();
            var runtimeProfile = TunnelProfileBuilder.Build(profile, routeTag, awgConfig,
                upstreamInterface);
            if (awgConfig is not null)
            {
                var resolved = await ResolveAwgPeerHostsAsync(runtimeProfile, ResolveDnsAddressesAsync,
                    cancellationToken).ConfigureAwait(false);
                runtimeProfile = resolved.Profile;
                if (resolved.FailedEndpointTags.Count != 0)
                {
                    StopCore();
                    return SetError("Не удалось разрешить адрес узла AmneziaWG. Проверьте соединение и повторите.");
                }
            }
            var tunAddresses = TunnelProfileBuilder.GetTunAddresses(runtimeProfile);
            var enginePath = Path.Combine(AppContext.BaseDirectory, "DeyttVpnEngine.exe");
            if (!File.Exists(enginePath))
                return SetError("Не найдено ядро VPN. Переустановите приложение.");
            _profilePath = WritePrivateProfile(runtimeProfile);

            using (var check = StartEngine(enginePath, "check", _profilePath, out var checkError,
                       captureStandardError: true))
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                timeout.CancelAfter(TimeSpan.FromSeconds(30));
                try
                {
                    await check.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
                {
                    TryKill(check);
                    StopCore();
                    return SetError("Проверка профиля VPN не завершилась вовремя.");
                }
                catch (OperationCanceledException)
                {
                    TryKill(check);
                    throw;
                }
                if (check.ExitCode != 0)
                {
                    var diagnostic = await checkError.ConfigureAwait(false);
                    StopCore();
                    return SetError($"Ядро: {SummarizeEngineDiagnostic(diagnostic)}");
                }
            }

            var runtimeDiagnostic = new EngineRuntimeDiagnostic();
            var process = StartOwnedEngine(enginePath, _profilePath, runtimeDiagnostic);
            _routeTag = routeTag;
            _ = MonitorEngineAsync(process, runtimeDiagnostic);

            _verification?.Cancel();
            _verification?.Dispose();
            _verification = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            var verification = _verification;
            SetSnapshot(new ServiceSnapshot("checking", "Проверяем VPN-трафик…", routeTag));
            _ = VerifyTunnelAsync(process, routeTag, tunAddresses, runtimeDiagnostic,
                verification.Token);
            return Snapshot;
        }
        catch (InvalidDataException error)
        {
            StopCore();
            return SetError($"Профиль подписки не подходит для Windows: {error.Message}");
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            StopCore();
            throw;
        }
        catch
        {
            StopCore();
            return SetError("Не удалось запустить VPN-службу.");
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<ServiceSnapshot> ProbeAsync(string profile, IReadOnlyList<string> routeTags,
        IReadOnlyDictionary<string, string> awgProfiles, string method, string token,
        CancellationToken cancellationToken)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        var probeRequestInitialized = false;
        var probeProfileWritten = false;
        try
        {
            if (Snapshot.State is "connected" or "starting" or "checking" ||
                _engine is { HasExited: false })
                return ProbeError("Сначала отключите VPN для проверки маршрутов.");
            if (!IsProbeRequestValid(profile, routeTags, awgProfiles, method))
                return ProbeError("Список маршрутов не прошёл проверку.");

            using var probeCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            Interlocked.Exchange(ref _probeCancellation, probeCancellation)?.Cancel();
            probeRequestInitialized = true;
            var probeToken = probeCancellation.Token;

            var endpoints = CreateProbeEndpoints(routeTags, awgProfiles);
            var requestedEndpoints = endpoints;
            lock (_probeProgressLock)
            {
                _probeProgress.Clear();
                foreach (var endpoint in endpoints)
                    _probeProgress[endpoint.RouteTag] = new ServiceProbeResult(endpoint.RouteTag, null, null, null,
                        "latency", 1);
            }
            var probeProfile = TunnelProfileBuilder.BuildProbeProfile(profile, endpoints,
                FindActiveHappTunnel());
            var resolvedProbe = await ResolveProbeAwgHostsAsync(probeProfile, endpoints,
                ResolveDnsAddressesAsync, probeToken).ConfigureAwait(false);
            endpoints = resolvedProbe.Endpoints;
            foreach (var failed in resolvedProbe.FailedResults)
                PublishProbeProgress(failed);
            if (endpoints.Count == 0)
            {
                var noRoutes = SnapshotProbeProgress();
                return new ServiceSnapshot("probe_complete", "Проверка маршрутов завершена.",
                    ProbeResults: noRoutes.ProbeResults);
            }
            probeProfile = resolvedProbe.Profile;
            _profilePath = WritePrivateProfile(probeProfile);
            probeProfileWritten = true;
            var enginePath = Path.Combine(AppContext.BaseDirectory, "DeyttVpnEngine.exe");
            if (!File.Exists(enginePath))
                return ProbeError("Не найдено ядро VPN. Переустановите приложение.");

            using (var check = StartEngine(enginePath, "check", _profilePath, out var checkError,
                       captureStandardError: true))
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(probeToken);
                timeout.CancelAfter(TimeSpan.FromSeconds(30));
                try
                {
                    await check.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (!probeToken.IsCancellationRequested)
                {
                    TryKill(check);
                    return ProbeError("Проверка профиля диагностики не завершилась вовремя.");
                }
                catch (OperationCanceledException)
                {
                    TryKill(check);
                    throw;
                }
                if (check.ExitCode != 0)
                {
                    var diagnostic = await checkError.ConfigureAwait(false);
                    return ProbeError($"Ядро: {SummarizeEngineDiagnostic(diagnostic)}");
                }
            }

            var runtimeDiagnostic = new EngineRuntimeDiagnostic();
            var process = StartOwnedEngine(enginePath, _profilePath, runtimeDiagnostic);
            using var measurementCancellation = CancellationTokenSource.CreateLinkedTokenSource(probeToken);
            using var monitorCancellation = new CancellationTokenSource();
            var engineMonitor = WatchProbeEngineAsync(process, measurementCancellation, monitorCancellation.Token);
            IReadOnlyList<ServiceProbeResult> results;
            try
            {
                await ProbeListenerReadiness.WaitAsync(endpoints.Select(endpoint => endpoint.Port).ToArray(),
                    () => process.HasExited, measurementCancellation.Token).ConfigureAwait(false);
                var failures = new System.Collections.Concurrent.ConcurrentDictionary<string, string>(StringComparer.Ordinal);
                async Task<long?> Measure(ProbeRouteEndpoint endpoint, string _, CancellationToken ct,
                    Action<int, long?> report)
                {
                    using var client = new HttpClient(RouteProxyTransport.CreateHandler(endpoint)) { Timeout = Timeout.InfiniteTimeSpan };
                    long? lastSuccessfulSample = null;
                    var result = await RouteLatencyProbe.MeasureAsync(client, ct,
                        observation =>
                        {
                            if (observation.LatencyMilliseconds is { } value)
                                lastSuccessfulSample = value;
                            report(observation.Attempt, lastSuccessfulSample);
                        }).ConfigureAwait(false);
                    if (result.Failure is { } failure)
                        failures[endpoint.RouteTag] = failure;
                    return result.LatencyMilliseconds;
                }
                results = await MeasureRoutesAsync(endpoints, method, token, measurementCancellation.Token,
                    PublishProbeProgress, Measure, MeasureDownloadAsync,
                    endpoint => failures.GetValueOrDefault(endpoint.RouteTag)).ConfigureAwait(false);
            }
            catch (TimeoutException)
            {
                return ProbeError("Ядро VPN не открыло локальные порты диагностики вовремя.");
            }
            catch (OperationCanceledException) when (!probeToken.IsCancellationRequested && process.HasExited)
            {
                return ProbeError(FormatProbeStartupFailure(process.ExitCode, runtimeDiagnostic.LatestError ?? ""));
            }
            catch (InvalidOperationException) when (process.HasExited)
            {
                return ProbeError(FormatProbeStartupFailure(process.ExitCode, runtimeDiagnostic.LatestError ?? ""));
            }
            finally
            {
                monitorCancellation.Cancel();
                await engineMonitor.ConfigureAwait(false);
            }
            if (process.HasExited && !probeToken.IsCancellationRequested)
            {
                var failure = runtimeDiagnostic.LatestError is { } reason
                    ? $"Ядро VPN остановилось: {reason}"
                    : "Ядро VPN остановилось во время диагностики.";
                results = results.Select(result => result.Stage == "complete" ? result : result with
                    { Error = failure, Stage = "error", BytesPerSecond = null }).ToArray();
                foreach (var result in results)
                    PublishProbeProgress(result);
            }
            var allResults = OrderProbeResults(requestedEndpoints,
                results.Concat(resolvedProbe.FailedResults).ToArray());
            lock (_probeProgressLock)
                _probeProgress.Clear();
            if (probeToken.IsCancellationRequested)
                return new ServiceSnapshot("probe_cancelled", "Проверка маршрутов остановлена.",
                    ProbeResults: allResults);
            return new ServiceSnapshot("probe_complete", "Проверка маршрутов завершена.",
                ProbeResults: allResults);
        }
        catch (InvalidDataException error)
        {
            return ProbeError($"Профиль подписки не подходит для проверки маршрутов: {error.Message}");
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (OperationCanceledException)
        {
            return new ServiceSnapshot("probe_cancelled", "Проверка маршрутов остановлена.",
                ProbeResults: SnapshotProbeProgress().ProbeResults);
        }
        catch
        {
            return ProbeError("Не удалось проверить маршруты.");
        }
        finally
        {
            if (probeRequestInitialized)
            {
                lock (_probeProgressLock)
                    _probeProgress.Clear();
                Interlocked.Exchange(ref _probeCancellation, null);
            }
            if (probeProfileWritten)
            {
                StopCore();
                SetSnapshot(new ServiceSnapshot("disconnected", "VPN выключен"));
            }
            _gate.Release();
        }
    }

    internal static bool IsProbeRequestValid(string profile, IReadOnlyList<string> routeTags,
        IReadOnlyDictionary<string, string> awgProfiles, string method)
    {
        if (awgProfiles.Any(item => item.Value is null || !IsValidAwgProfileId(item.Key)))
            return false;

        var awgProfilesBytes = awgProfiles.Sum(item => System.Text.Encoding.UTF8.GetByteCount(item.Value));
        return System.Text.Encoding.UTF8.GetByteCount(profile) is > 0 and <= MaximumProfileBytes &&
               routeTags.Count is >= 1 and <= WindowsPipeProtocol.MaximumProbeRouteCount &&
               routeTags.Distinct(StringComparer.Ordinal).Count() == routeTags.Count &&
               routeTags.All(IsValidProbeRouteTag) &&
               awgProfiles.Count <= WindowsPipeProtocol.MaximumAwgProfiles && (method is "HEAD" or "GET") &&
               awgProfilesBytes <= MaximumAwgProfilesTotalBytes &&
               awgProfiles.All(item => System.Text.Encoding.UTF8.GetByteCount(item.Value) <= MaximumAwgProfileBytes);
    }

    private static bool IsValidAwgProfileId(string routeId)
    {
        var prefix = routeId.StartsWith("awg15:", StringComparison.Ordinal) ? "awg15:" :
            routeId.StartsWith("awg31:", StringComparison.Ordinal) ? "awg31:" : null;
        if (routeId is "awg15" or "awg31")
            return true;
        if (prefix is null)
            return false;

        var id = routeId[prefix.Length..];
        return id.Length is >= 1 and <= 172 && id.All(character =>
            char.IsAsciiLetterOrDigit(character) || character is '-' or '_');
    }

    private static bool IsValidProbeRouteTag(string? routeTag) =>
        routeTag is { Length: > 0 and <= 256 } && routeTag.All(character =>
            char.IsAsciiLetterOrDigit(character) || character is ':' or '.' or '_' or '-');

    public ServiceSnapshot CancelProbe()
    {
        try
        {
            Volatile.Read(ref _probeCancellation)?.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
        return new ServiceSnapshot("probe_cancelling", "Останавливаем проверку маршрутов.");
    }

    public void Stop()
    {
        _gate.Wait();
        try
        {
            StopCore();
            SetSnapshot(new ServiceSnapshot("disconnected", "VPN выключен"));
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task VerifyTunnelAsync(Process process, string routeTag,
        IReadOnlyList<string> tunAddresses, EngineRuntimeDiagnostic runtimeDiagnostic,
        CancellationToken cancellationToken)
    {
        var failureStage = "TUN";
        try
        {
            var tunnelInterface = await WaitForTunInterfaceAsync(process, tunAddresses,
                cancellationToken).ConfigureAwait(false);
            var passed = false;
            Exception? lastError = null;
            for (var attempt = 0; attempt < 3; attempt++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                try
                {
                    if (process.HasExited)
                        throw new InvalidOperationException("VPN engine exited before traffic verification.");

                    failureStage = "HTTPS";
                    await VerifyHttpsThroughTunAsync(tunnelInterface, TimeSpan.FromSeconds(5),
                        cancellationToken).ConfigureAwait(false);
                    passed = true;
                    break;
                }
                catch (Exception error) when (error is not OperationCanceledException && attempt < 2)
                {
                    lastError = error;
                    await Task.Delay(TimeSpan.FromMilliseconds(1_500), cancellationToken).ConfigureAwait(false);
                }
            }

            if (!passed)
                throw new HttpRequestException("Tunnel verification failed.", lastError);

            await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                if (ReferenceEquals(_engine, process) && !process.HasExited)
                {
                    var checkedAt = DateTimeOffset.UtcNow;
                    var transition = WindowsTunnelHealth.ApplyCanaryResult(true, _consecutiveHealthFailures,
                        tunnelRouteProven: true);
                    _consecutiveHealthFailures = transition.ConsecutiveFailures;
                    SetSnapshot(new ServiceSnapshot(transition.State, "VPN подключён; HTTPS проверен через TUN.",
                        routeTag, checkedAt, HealthCheckedAt: checkedAt));
                }
                if (ReferenceEquals(_engine, process) && !process.HasExited)
                {
                    _healthMonitor?.Stop();
                    _healthMonitor?.Dispose();
                    _healthMonitor = new TunnelHealthMonitorLifetime();
                    _ = MonitorTunnelHealthAsync(process, routeTag, tunAddresses, _healthMonitor.Token);
                }
            }
            finally
            {
                _gate.Release();
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
          catch (Exception error)
        {
            await _gate.WaitAsync(CancellationToken.None).ConfigureAwait(false);
            try
            {
                if (ReferenceEquals(_engine, process))
                {
                    StopCore();
                      var coreReason = runtimeDiagnostic.LatestError;
                      SetError(coreReason is null
                          ? SummarizeTunnelFailure(failureStage, error)
                          : $"Ядро: {coreReason}");
                }
            }
            finally
            {
                _gate.Release();
            }
        }
    }

    private async Task MonitorTunnelHealthAsync(Process process, string routeTag,
        IReadOnlyList<string> tunAddresses,
        CancellationToken cancellationToken)
    {
        const int intervalSeconds = 60;
        const int canaryTimeoutSeconds = 5;
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(intervalSeconds));
        void OnNetworkChanged(object? _, EventArgs __)
        {
            try { _healthRecheck.Release(); }
            catch (SemaphoreFullException) { }
        }

        NetworkChange.NetworkAddressChanged += OnNetworkChanged;
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                var wake = await TunnelHealthMonitorWait.WaitAsync(
                    token => timer.WaitForNextTickAsync(token).AsTask(),
                    token => _healthRecheck.WaitAsync(token), cancellationToken).ConfigureAwait(false);
                if (wake is null || process.HasExited)
                    return;
                if (wake == TunnelHealthWake.NetworkChanged)
                    await Task.Delay(TimeSpan.FromSeconds(3), cancellationToken).ConfigureAwait(false);

                await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
                try
                {
                    if (!ReferenceEquals(_engine, process) || process.HasExited)
                        return;
                    SetSnapshot(new ServiceSnapshot("health_checking", "Повторно проверяем VPN-трафик…",
                        routeTag, Snapshot.ConnectedAt, HealthCheckedAt: Snapshot.HealthCheckedAt));
                }
                finally { _gate.Release(); }

                var passed = false;
                try
                {
                    var tunnelInterface = FindTunInterface(tunAddresses)
                        ?? throw new InvalidOperationException("The configured TUN address is unavailable.");
                    await VerifyHttpsThroughTunAsync(tunnelInterface,
                        TimeSpan.FromSeconds(canaryTimeoutSeconds), cancellationToken).ConfigureAwait(false);
                    passed = !process.HasExited;
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { return; }
                catch { }

                await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
                try
                {
                    if (!ReferenceEquals(_engine, process) || process.HasExited)
                        return;
                    var checkedAt = DateTimeOffset.UtcNow;
                    var transition = WindowsTunnelHealth.ApplyCanaryResult(passed, _consecutiveHealthFailures,
                        tunnelRouteProven: passed);
                    _consecutiveHealthFailures = transition.ConsecutiveFailures;
                    var detail = transition.State switch
                    {
                        "connected" => "HTTPS проверен через TUN.",
                        "unknown" => "HTTPS доступен, но маршрут через TUN не подтверждён.",
                        "health_checking" => "Проверка временно не прошла; повторяем проверку VPN-трафика.",
                        _ => "Проверка VPN-трафика не прошла; соединение оставлено включённым.",
                    };
                    SetSnapshot(new ServiceSnapshot(transition.State, detail, routeTag,
                        Snapshot.ConnectedAt, HealthCheckedAt: transition.State == "unknown" ? null : checkedAt));
                }
                finally { _gate.Release(); }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { }
        finally { NetworkChange.NetworkAddressChanged -= OnNetworkChanged; }
    }

    private static async Task<WindowsTunnelCanaryInterface> WaitForTunInterfaceAsync(Process process,
        IReadOnlyList<string> tunAddresses, CancellationToken cancellationToken)
    {
        for (var attempt = 0; attempt < 30; attempt++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (process.HasExited)
                throw new InvalidOperationException("VPN engine exited before the TUN address appeared.");
            var tunnelInterface = FindTunInterface(tunAddresses);
            if (tunnelInterface is not null)
                return tunnelInterface;
            await Task.Delay(TimeSpan.FromSeconds(1), cancellationToken).ConfigureAwait(false);
        }
        throw new InvalidOperationException("The configured TUN address is not assigned to an active interface.");
    }

    private static async Task VerifyHttpsThroughTunAsync(WindowsTunnelCanaryInterface tunnelInterface,
        TimeSpan timeout, CancellationToken cancellationToken)
    {
        // A single successful HTTPS response through a socket pinned to TUN proves
        // traffic reached the tunnel. Public canaries can fail independently.
        (string Url, HttpStatusCode? ExpectedStatus)[] canaries =
        [
            ("https://www.cloudflare.com/cdn-cgi/trace", null),
            ("https://www.gstatic.com/generate_204", HttpStatusCode.NoContent),
        ];
        Exception? lastError = null;
        foreach (var canary in canaries)
        {
            try
            {
                using var http = CreateTunnelHttpClient(tunnelInterface, timeout);
                using var response = await http.GetAsync(canary.Url, cancellationToken).ConfigureAwait(false);
                if (canary.ExpectedStatus is { } expected
                    ? response.StatusCode == expected
                    : response.IsSuccessStatusCode)
                    return;
                lastError = new HttpRequestException("HTTPS canary returned an unexpected status.");
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception error) when (error is HttpRequestException or OperationCanceledException or IOException)
            {
                lastError = error;
            }
        }
        throw new HttpRequestException("No HTTPS canary passed through TUN.", lastError);
    }

    private static string SummarizeTunnelFailure(string stage, Exception error)
    {
        if (error is InvalidOperationException && error.Message.Contains("engine exited", StringComparison.Ordinal))
            return "Ядро VPN остановилось до появления TUN-адреса.";
        if (stage == "core")
            return "Ядро VPN остановилось до проверки трафика.";
        if (stage == "TUN")
            return "VPN запустился, но TUN-адаптер не получил адрес.";

        for (var current = error; current is not null; current = current.InnerException)
        {
            if (current is SocketException socket)
                return $"HTTPS через TUN: ошибка сокета {socket.SocketErrorCode}.";
            if (current is OperationCanceledException)
                return "HTTPS через TUN: время ожидания истекло.";
            if (current is System.Security.Authentication.AuthenticationException)
                return "HTTPS через TUN: ошибка TLS.";
        }
        return "HTTPS через TUN не прошёл; проверь доступность выбранного маршрута.";
    }

    private static WindowsTunnelCanaryInterface? FindTunInterface(IReadOnlyList<string> configuredAddresses)
    {
        var interfaceAddresses = new List<WindowsTunnelCanaryInterface>();
        foreach (var networkInterface in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (networkInterface.OperationalStatus != OperationalStatus.Up)
                continue;
            try
            {
                var properties = networkInterface.GetIPProperties();
                foreach (var unicast in properties.UnicastAddresses)
                {
                    var interfaceIndex = unicast.Address.AddressFamily switch
                    {
                        AddressFamily.InterNetwork => properties.GetIPv4Properties()?.Index ?? 0,
                        AddressFamily.InterNetworkV6 => properties.GetIPv6Properties()?.Index ?? 0,
                        _ => 0,
                    };
                    if (interfaceIndex > 0)
                        interfaceAddresses.Add(new WindowsTunnelCanaryInterface(unicast.Address, interfaceIndex));
                }
            }
            catch (Exception error) when (error is NetworkInformationException or PlatformNotSupportedException)
            {
                // An interface can disappear or lack index data while Windows rebuilds routes.
            }
        }
        return WindowsTunnelCanaryRoute.SelectInterface(configuredAddresses, interfaceAddresses);
    }

    private static string? FindActiveHappTunnel()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .FirstOrDefault(networkInterface =>
                    networkInterface.Name.Equals("happ-tun", StringComparison.OrdinalIgnoreCase) &&
                    networkInterface.OperationalStatus == OperationalStatus.Up &&
                    networkInterface.GetIPProperties().UnicastAddresses.Any(unicast =>
                        unicast.Address.AddressFamily == AddressFamily.InterNetwork &&
                        !IPAddress.IsLoopback(unicast.Address)))?.Name;
        }
        catch (NetworkInformationException)
        {
            return null;
        }
    }

    private static HttpClient CreateTunnelHttpClient(WindowsTunnelCanaryInterface tunnelInterface,
        TimeSpan timeout)
    {
        var handler = new SocketsHttpHandler
        {
            UseProxy = false,
            AllowAutoRedirect = false,
            ConnectCallback = async (context, cancellationToken) =>
            {
                var destinationAddresses = await Dns.GetHostAddressesAsync(
                    context.DnsEndPoint.Host, cancellationToken).ConfigureAwait(false);
                Exception? lastError = null;
                foreach (var destinationAddress in destinationAddresses.Where(address =>
                             address.AddressFamily == tunnelInterface.Address.AddressFamily))
                {
                    var socket = new Socket(tunnelInterface.Address.AddressFamily, SocketType.Stream, ProtocolType.Tcp);
                    try
                    {
                        socket.Bind(new IPEndPoint(tunnelInterface.Address, 0));
                        SetOutgoingInterface(socket, tunnelInterface);
                        await socket.ConnectAsync(new IPEndPoint(destinationAddress, context.DnsEndPoint.Port),
                            cancellationToken).ConfigureAwait(false);
                        return new NetworkStream(socket, ownsSocket: true);
                    }
                    catch (OperationCanceledException)
                    {
                        socket.Dispose();
                        throw;
                    }
                    catch (Exception error)
                    {
                        lastError = error;
                        socket.Dispose();
                    }
                }
                throw new HttpRequestException("No HTTPS destination could be reached through TUN.", lastError);
            },
        };
        return new HttpClient(handler) { Timeout = timeout };
    }

    private static void SetOutgoingInterface(Socket socket, WindowsTunnelCanaryInterface tunnelInterface)
    {
        const int ipProtocol = 0;
        const int ipv6Protocol = 41;
        const int unicastInterfaceOption = 31;
        var addressFamily = tunnelInterface.Address.AddressFamily;
        var optionLevel = addressFamily switch
        {
            AddressFamily.InterNetwork => ipProtocol,
            AddressFamily.InterNetworkV6 => ipv6Protocol,
            _ => throw new SocketException((int)SocketError.AddressFamilyNotSupported),
        };
        var optionValue = WindowsTunnelCanaryRoute.EncodeInterfaceIndex(
            tunnelInterface.Address, tunnelInterface.InterfaceIndex);
        if (SetSocketOptionNative(socket.SafeHandle, optionLevel, unicastInterfaceOption,
                optionValue, optionValue.Length) != 0)
            throw new SocketException(GetSocketErrorNative());
    }

    [DllImport("Ws2_32.dll", EntryPoint = "setsockopt")]
    private static extern int SetSocketOptionNative(SafeSocketHandle socket, int level, int optionName,
        byte[] optionValue, int optionLength);

    [DllImport("Ws2_32.dll", EntryPoint = "WSAGetLastError")]
    private static extern int GetSocketErrorNative();

    private async Task MonitorEngineAsync(Process process, EngineRuntimeDiagnostic runtimeDiagnostic)
    {
        try
        {
            await process.WaitForExitAsync().ConfigureAwait(false);
        }
        catch
        {
            return;
        }

        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (!ReferenceEquals(_engine, process))
                return;
            var exitCode = unchecked((uint)process.ExitCode);
            StopCore();
            var detail = runtimeDiagnostic.LatestError is { } reason
                ? $"Ядро: {reason}"
                : "Причина завершения ядра не получена.";
            SetError($"{detail} Код завершения: 0x{exitCode:X8}.");
        }
        finally
        {
            _gate.Release();
        }
    }

    internal static async Task<AwgResolution> ResolveAwgPeerHostsAsync(string runtimeProfile,
        Func<string, CancellationToken, Task<IPAddress[]>> resolve,
        CancellationToken cancellationToken, TimeSpan? timeout = null)
    {
        var root = JsonNode.Parse(runtimeProfile) as JsonObject
                   ?? throw new InvalidDataException("Windows tunnel profile is invalid.");
        var awgEndpoints = (root["endpoints"] as JsonArray)?.OfType<JsonObject>()
            .Where(endpoint => ReadJsonString(endpoint, "type") == "awg").ToArray() ?? [];
        var hosts = awgEndpoints.SelectMany(endpoint => (endpoint["peers"] as JsonArray)?.OfType<JsonObject>() ?? [])
            .Select(peer => ReadJsonString(peer, "address"))
            .Where(address => !string.IsNullOrWhiteSpace(address))
            .Select(address => address!.Trim('[', ']'))
            .Where(address => !IPAddress.TryParse(address, out _))
            .Distinct(StringComparer.OrdinalIgnoreCase).ToArray();

        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout ?? TimeSpan.FromSeconds(5));
        using var concurrency = new SemaphoreSlim(8, 8);
        var resolvedHosts = new System.Collections.Concurrent.ConcurrentDictionary<string, IPAddress>(
            StringComparer.OrdinalIgnoreCase);

        async Task ResolveOneAsync(string host)
        {
            var acquired = false;
            try
            {
                await concurrency.WaitAsync(deadline.Token).ConfigureAwait(false);
                acquired = true;
                try
                {
                    var addresses = await resolve(host, deadline.Token).WaitAsync(deadline.Token)
                        .ConfigureAwait(false);
                    var selected = addresses.Where(address => address.AddressFamily is
                            AddressFamily.InterNetwork or AddressFamily.InterNetworkV6)
                        .OrderBy(address => address.AddressFamily == AddressFamily.InterNetwork ? 0 : 1)
                        .FirstOrDefault();
                    if (selected is not null)
                        resolvedHosts[host] = selected;
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    throw;
                }
                catch
                {
                    // A DNS failure is local to this AWG endpoint; other routes may still run.
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (OperationCanceledException)
            {
                // The shared DNS deadline expired. Unresolved hosts are marked failed below.
            }
            finally
            {
                if (acquired)
                    concurrency.Release();
            }
        }

        try
        {
            await Task.WhenAll(hosts.Select(ResolveOneAsync)).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }

        var failedTags = new HashSet<string>(StringComparer.Ordinal);
        foreach (var endpoint in awgEndpoints)
        {
            var endpointFailed = false;
            if (endpoint["peers"] is JsonArray peers)
            {
                foreach (var peer in peers.OfType<JsonObject>())
                {
                    var addressNode = peer["address"];
                    if (addressNode is not JsonValue value || !value.TryGetValue<string>(out var rawAddress) ||
                        string.IsNullOrWhiteSpace(rawAddress))
                    {
                        endpointFailed = true;
                        continue;
                    }
                    var address = rawAddress.Trim('[', ']');
                    if (IPAddress.TryParse(address, out var literal))
                    {
                        peer["address"] = literal.ToString();
                        continue;
                    }
                    if (!resolvedHosts.TryGetValue(address, out var resolved))
                    {
                        endpointFailed = true;
                        continue;
                    }
                    peer["address"] = resolved.ToString();
                }
            }
            else
            {
                endpointFailed = true;
            }

            if (endpointFailed && ReadJsonString(endpoint, "tag") is { Length: > 0 } tag)
                failedTags.Add(tag);
        }

        return new AwgResolution(root.ToJsonString(), failedTags);
    }

    internal static async Task<ProbeAwgResolution> ResolveProbeAwgHostsAsync(string runtimeProfile,
        IReadOnlyList<ProbeRouteEndpoint> endpoints,
        Func<string, CancellationToken, Task<IPAddress[]>> resolve, CancellationToken cancellationToken,
        TimeSpan? timeout = null)
    {
        var resolved = await ResolveAwgPeerHostsAsync(runtimeProfile, resolve, cancellationToken, timeout)
            .ConfigureAwait(false);
        if (resolved.FailedEndpointTags.Count == 0)
            return new ProbeAwgResolution(resolved.Profile, endpoints, []);

        var failed = endpoints.Where(endpoint => endpoint.AwgConfig is not null &&
            resolved.FailedEndpointTags.Contains(TunnelProfileBuilder.AwgEndpointTag(endpoint.RouteTag)))
            .ToArray();
        var viable = endpoints.Except(failed).ToArray();
        var root = JsonNode.Parse(resolved.Profile) as JsonObject
                   ?? throw new InvalidDataException("Windows diagnostic profile is invalid.");
        if (root["endpoints"] is JsonArray endpointNodes)
        {
            var failedTags = resolved.FailedEndpointTags;
            root["endpoints"] = new JsonArray(endpointNodes.OfType<JsonObject>()
                .Where(node => ReadJsonString(node, "tag") is not { } tag || !failedTags.Contains(tag))
                .Select(node => (JsonNode?)node.DeepClone()).ToArray());
        }
        var failedInboundTags = new HashSet<string>(failed.Select(endpoint => endpoint.InboundTag),
            StringComparer.Ordinal);
        if (root["inbounds"] is JsonArray inboundNodes)
            root["inbounds"] = new JsonArray(inboundNodes.OfType<JsonObject>()
                .Where(node => ReadJsonString(node, "tag") is not { } tag || !failedInboundTags.Contains(tag))
                .Select(node => (JsonNode?)node.DeepClone()).ToArray());
        if (root["route"] is JsonObject route)
        {
            if (route["rules"] is JsonArray rules)
                route["rules"] = new JsonArray(rules.OfType<JsonObject>()
                    .Where(rule => rule["inbound"] is not JsonArray inboundArray ||
                        !inboundArray.OfType<JsonValue>().Any(value => value.TryGetValue<string>(out var tag) &&
                            failedInboundTags.Contains(tag)))
                    .Select(rule => (JsonNode?)rule.DeepClone()).ToArray());
            if (viable.Length > 0)
                route["final"] = viable[0].AwgConfig is null ? viable[0].RouteTag :
                    TunnelProfileBuilder.AwgEndpointTag(viable[0].RouteTag);
            else
                route.Remove("final");
        }
        if (viable.Length > 0)
            TunnelProfileBuilder.PruneProbeDependencies(root, viable);

        var failedResults = failed.Select(endpoint => new ServiceProbeResult(endpoint.RouteTag, null, null,
            "Не удалось разрешить адрес узла AmneziaWG.", "error")).ToArray();
        return new ProbeAwgResolution(root.ToJsonString(), viable, failedResults);
    }

    private static Task<IPAddress[]> ResolveDnsAddressesAsync(string host, CancellationToken cancellationToken) =>
        Dns.GetHostAddressesAsync(host, cancellationToken);

    private static string? ReadJsonString(JsonObject value, string property) =>
        value[property] is JsonValue jsonValue && jsonValue.TryGetValue<string>(out var text) ? text : null;

    private static IReadOnlyList<ServiceProbeResult> OrderProbeResults(
        IReadOnlyList<ProbeRouteEndpoint> requested, IReadOnlyList<ServiceProbeResult> results)
    {
        var byTag = results.GroupBy(result => result.RouteTag, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.Last(), StringComparer.Ordinal);
        return requested.Where(endpoint => byTag.ContainsKey(endpoint.RouteTag))
            .Select(endpoint => byTag[endpoint.RouteTag]).ToArray();
    }

    private ServiceSnapshot SnapshotProbeProgress()
    {
        lock (_probeProgressLock)
            return new ServiceSnapshot("probe_cancelled", "Проверка маршрутов остановлена.",
                ProbeResults: _probeProgress.Values.Take(32).ToArray());
    }

    private static IReadOnlyList<ProbeRouteEndpoint> CreateProbeEndpoints(IReadOnlyList<string> routeTags,
        IReadOnlyDictionary<string, string> awgProfiles)
    {
        if (routeTags.Distinct(StringComparer.Ordinal).Count() != routeTags.Count)
            throw new InvalidDataException("The diagnostic route list has duplicate entries.");

        var endpoints = new List<ProbeRouteEndpoint>(routeTags.Count);
        for (var index = 0; index < routeTags.Count; index++)
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            listener.Stop();

            var entropy = Guid.NewGuid().ToString("N");
            endpoints.Add(new ProbeRouteEndpoint(routeTags[index], $"deytt-probe-{index}", port,
                $"d{entropy[..14]}", entropy, awgProfiles.GetValueOrDefault(routeTags[index])));
        }
        return endpoints;
    }

    internal static async Task<IReadOnlyList<ServiceProbeResult>> MeasureRoutesAsync(
        IReadOnlyList<ProbeRouteEndpoint> endpoints, string method, string token,
        CancellationToken cancellationToken, Action<ServiceProbeResult> publish,
        Func<ProbeRouteEndpoint, string, CancellationToken, Action<int, long?>, Task<long?>> measureLatency,
        Func<ProbeRouteEndpoint, string, CancellationToken, Action<long, long, long?>,
            Task<DownloadMeasurement>> measureDownload,
        Func<ProbeRouteEndpoint, string?>? latencyFailure = null)
    {
        var canMeasureSpeed = token.Length is >= 32 and <= 256 &&
                              token.All(character => character is >= '\x21' and <= '\x7e');
        var latencyResults = new System.Collections.Concurrent.ConcurrentDictionary<string, ServiceProbeResult>(
            StringComparer.Ordinal);
        using var latencyConcurrency = new SemaphoreSlim(4, 4);

        async Task MeasureLatencyForEndpointAsync(ProbeRouteEndpoint endpoint)
        {
            long? latency;
            var acquired = false;
            try
            {
                await latencyConcurrency.WaitAsync(cancellationToken).ConfigureAwait(false);
                acquired = true;
                latency = await measureLatency(endpoint, method, cancellationToken,
                (attempt, value) => publish(new ServiceProbeResult(endpoint.RouteTag, value, null, null,
                    attempt == 1 ? "latency" : "retry", attempt))).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                var cancelled = new ServiceProbeResult(endpoint.RouteTag, null, null, null, "cancelled");
                latencyResults[endpoint.RouteTag] = cancelled;
                publish(cancelled);
                return;
            }
            finally
            {
                if (acquired)
                    latencyConcurrency.Release();
            }

            var error = latency is null ? latencyFailure?.Invoke(endpoint) ?? "Маршрут не ответил на HTTPS-проверку." :
                canMeasureSpeed ? null : "Скорость недоступна для этой сессии.";
            var result = new ServiceProbeResult(endpoint.RouteTag, latency, null, error,
                latency is null || error is not null ? "error" : "waiting_speed");
            latencyResults[endpoint.RouteTag] = result;
            publish(result);
        }

        await Task.WhenAll(endpoints.Select(MeasureLatencyForEndpointAsync)).ConfigureAwait(false);

        foreach (var endpoint in endpoints)
        {
            var result = latencyResults[endpoint.RouteTag];
            if (result.LatencyMilliseconds is null || !canMeasureSpeed)
                continue;

            long received = 0;
            long totalBytes = 0;
            long? liveSpeed = null;
            string? error = null;
            long? speed = null;
            var cancelled = false;
            for (var attempt = 1; attempt <= 2; attempt++)
            {
                received = 0;
                totalBytes = 0;
                liveSpeed = null;
                publish(result with { BytesPerSecond = liveSpeed, Error = null,
                    Stage = attempt == 1 ? "waiting_speed" : "retry", Attempt = attempt });
                try
                {
                    var download = await measureDownload(endpoint, token, cancellationToken,
                        (currentReceived, currentTotal, currentSpeed) =>
                        {
                            received = currentReceived;
                            totalBytes = currentTotal;
                            liveSpeed = currentSpeed;
                            publish(result with { BytesPerSecond = liveSpeed, Error = null, Stage = "download",
                                Attempt = attempt, BytesReceived = received, TotalBytes = totalBytes });
                        }).ConfigureAwait(false);
                    speed = download.BytesPerSecond;
                    error = download.Error;
                    if (speed is not null || !download.Retryable)
                        break;
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    cancelled = true;
                    break;
                }
                catch (OperationCanceledException)
                {
                    error = "Сервер скорости не ответил вовремя.";
                }
                catch (HttpRequestException)
                {
                    error = "HTTPS-запрос скорости не прошёл через маршрут.";
                }
                catch (IOException)
                {
                    error = "Соединение с сервером скорости прервалось.";
                }
            }

            result = result with
            {
                BytesPerSecond = cancelled || error is not null ? null : speed,
                Error = cancelled ? null : error,
                Stage = cancelled ? "cancelled" : error is null ? "complete" : "error",
                Attempt = null,
                BytesReceived = received == 0 ? null : received,
                TotalBytes = totalBytes == 0 ? null : totalBytes,
            };
            latencyResults[endpoint.RouteTag] = result;
            publish(result);
            if (cancelled)
                break;
        }

        if (cancellationToken.IsCancellationRequested)
        {
            foreach (var endpoint in endpoints)
            {
                var result = latencyResults[endpoint.RouteTag];
                if (result.Stage is "complete" or "error" or "cancelled")
                    continue;
                result = result with { Error = null, Stage = "cancelled" };
                latencyResults[endpoint.RouteTag] = result;
                publish(result);
            }
        }

        return endpoints.Select(endpoint => latencyResults[endpoint.RouteTag]).ToArray();
    }

    internal readonly record struct DownloadMeasurement(long? BytesPerSecond, string? Error, bool Retryable);

    private static async Task<DownloadMeasurement> MeasureDownloadAsync(ProbeRouteEndpoint endpoint, string token,
        CancellationToken cancellationToken, Action<long, long, long?> reportProgress)
    {
        using var handler = RouteProxyTransport.CreateHandler(endpoint);
        // Bound the request-header wait separately from the five-second streaming
        // sample below; an unreachable server must not leave the route row pending
        // for the old twelve-second default before any live speed can be reported.
        using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(6) };
        using var request = new HttpRequestMessage(HttpMethod.Get,
            "https://deytt.space/api/tg/mobile/probe/download");
        request.Headers.TryAddWithoutValidation("X-TG-App-Token", token);
        request.Headers.AcceptEncoding.ParseAdd("identity");
        request.Headers.CacheControl = new CacheControlHeaderValue { NoStore = true };
        using var response = await client.SendAsync(request,
            HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
        if (response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
            return new DownloadMeasurement(null, "Сессия для замера скорости недействительна. Обновите вход.", false);
        if (response.StatusCode != HttpStatusCode.OK)
            return new DownloadMeasurement(null, "Сервер скорости временно недоступен.", true);

        await using var body = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        return await ReadDownloadSampleAsync(body, response.Content.Headers.ContentLength,
            cancellationToken, reportProgress, TimeSpan.FromSeconds(5)).ConfigureAwait(false);
    }

    internal static async Task<DownloadMeasurement> ReadDownloadSampleAsync(Stream body, long? contentLength,
        CancellationToken cancellationToken, Action<long, long, long?> reportProgress, TimeSpan sampleDuration)
    {
        const long maximumSampleBytes = 32L * 1024 * 1024;
        const long minimumReliableBytes = 256L * 1024;
        var expectedBytes = contentLength is > 0 ? Math.Min(contentLength.Value, maximumSampleBytes) : maximumSampleBytes;
        using var sampleTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        sampleTimeout.CancelAfter(sampleDuration);
        var buffer = new byte[32 * 1024];
        long received = 0;
        long lastProgress = 0;
        var sampleStarted = Stopwatch.GetTimestamp();
        var timedOut = false;
        while (received < 32L * 1024 * 1024)
        {
            int count;
            try
            {
                count = await body.ReadAsync(buffer.AsMemory(), sampleTimeout.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested && received > 0)
            {
                timedOut = true;
                break;
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                return new DownloadMeasurement(null, "Сервер скорости не передал данные за пять секунд.", true);
            }
            if (count <= 0)
                break;
            received += count;
            var elapsedMs = Stopwatch.GetElapsedTime(sampleStarted).TotalMilliseconds;
            if (lastProgress == 0 || Stopwatch.GetElapsedTime(lastProgress).TotalMilliseconds >= 200)
            {
                reportProgress(received, expectedBytes, elapsedMs >= 200 ? (long)(received * 1000d / elapsedMs) : null);
                lastProgress = Stopwatch.GetTimestamp();
            }
        }

        if (received == 0)
            return new DownloadMeasurement(null, "Сервер скорости вернул пустой ответ.", true);

        if (!timedOut && contentLength is > 0 && received < expectedBytes)
            return new DownloadMeasurement(null, "Сервер скорости вернул неполный объём данных.", true);

        var elapsed = Stopwatch.GetElapsedTime(sampleStarted).TotalMilliseconds;
        if (elapsed < 100 || (!timedOut && received < minimumReliableBytes))
            return new DownloadMeasurement(null, "Недостаточно данных для точного замера скорости.", true);

        return new DownloadMeasurement((long)(received * 1000d / elapsed), null, false);
    }

    private void PublishProbeProgress(ServiceProbeResult result)
    {
        if (string.IsNullOrEmpty(result.RouteTag) || result.RouteTag.Length > 256)
            return;
        var safe = result with
        {
            Stage = result.Stage is "latency" or "retry" or "waiting_speed" or "download" or "complete" or "error" or "cancelled"
                ? result.Stage : null,
            Attempt = result.Attempt is { } attempt ? Math.Clamp(attempt, 1, 3) : null,
            BytesReceived = result.BytesReceived is { } received ? Math.Clamp(received, 0, 32L * 1024 * 1024) : null,
            TotalBytes = result.TotalBytes is { } total ? Math.Clamp(total, 0, 32L * 1024 * 1024) : null,
            Error = result.Error is null ? null : result.Error.Length <= 120 ? result.Error : result.Error[..120],
        };
        lock (_probeProgressLock)
        {
            if (!_probeProgress.ContainsKey(safe.RouteTag))
                return;
            if (_probeProgress.Count <= WindowsPipeProtocol.MaximumProbeRouteCount)
                _probeProgress[safe.RouteTag] = safe;
        }
    }

    private static ServiceSnapshot ProbeError(string detail) =>
        new("probe_error", detail, ProbeResults: []);

    private static Process StartEngine(string enginePath, string command, string profilePath,
        out Task<string> standardError, bool captureStandardError = false,
        EngineRuntimeDiagnostic? runtimeDiagnostic = null)
    {
        var start = new ProcessStartInfo
        {
            FileName = enginePath,
            WorkingDirectory = AppContext.BaseDirectory,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        start.ArgumentList.Add(command);
        start.ArgumentList.Add("-c");
        start.ArgumentList.Add(profilePath);

        var process = Process.Start(start) ?? throw new InvalidOperationException("Engine did not start.");
        process.OutputDataReceived += static (_, _) => { };
        process.BeginOutputReadLine();
        if (captureStandardError)
        {
            standardError = ReadBoundedStandardErrorAsync(process.StandardError);
        }
        else
        {
            standardError = Task.FromResult(string.Empty);
            process.ErrorDataReceived += (_, eventArgs) =>
            {
                if (eventArgs.Data is { } line)
                    runtimeDiagnostic?.Record(line);
            };
            process.BeginErrorReadLine();
        }
        return process;
    }

    private static async Task<string> ReadBoundedStandardErrorAsync(StreamReader reader)
    {
        const int maximumCharacters = 16_384;
        var output = new System.Text.StringBuilder(maximumCharacters);
        var buffer = new char[2_048];
        while (output.Length < maximumCharacters)
        {
            var count = await reader.ReadAsync(buffer.AsMemory(0,
                Math.Min(buffer.Length, maximumCharacters - output.Length))).ConfigureAwait(false);
            if (count == 0)
                break;
            output.Append(buffer, 0, count);
        }

        if (output.Length == maximumCharacters)
        {
            while (await reader.ReadAsync(buffer.AsMemory()).ConfigureAwait(false) > 0)
            {
                // Drain additional output without retaining it or blocking the engine process.
            }
        }
        return output.ToString();
    }

    private static string SummarizeEngineDiagnostic(string standardError)
    {
        var lines = standardError.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries)
            .Select(line => line.Trim())
            .Where(line => line.Length > 0)
            .ToArray();
        var diagnostic = lines.LastOrDefault(line =>
            System.Text.RegularExpressions.Regex.IsMatch(line,
                @"(?i)\b(fatal|error|failed|invalid|unsupported|unknown|missing|decode|parse)\b"))
            ?? lines.LastOrDefault();
        if (string.IsNullOrWhiteSpace(diagnostic))
            return "Причина ядра не получена.";

        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
            @"\x1B(?:\[[0-?]*[ -/]*[@-~]|\][^\x07]*(?:\x07|\x1B\\))", string.Empty);
        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
            @"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]", string.Empty);
        var configLocation = System.Text.RegularExpressions.Regex.Match(diagnostic,
            @"(?i)\bdecode config at .+?\.json:\s*");
        if (configLocation.Success)
        {
            diagnostic = "decode config: " + diagnostic[(configLocation.Index + configLocation.Length)..].Trim();
        }
        else
        {
            diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
                @"(?i)^\s*(?:FATAL|ERROR)\[\d+\]\s*", string.Empty);
        }

        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
            @"(?i)\b(?:https?|socks5?|vless|trojan|hysteria2?)://[^\s<>]+", "[URL скрыт]");
        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
            @"(?i)(?<prefix>[""']?(?:password|passwd|username|user|token|secret|private[_-]?key|public[_-]?key|preshared[_-]?key|uuid|short[_-]?id|client[_-]?id|psk|alter[_-]?id|authorization)[""']?\s*[:=]\s*[""']?)[^,""'\s;}]+",
            "${prefix}[скрыто]");
        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
            @"(?i)(?<prefix>[""']?(?:server|host|endpoint|peer|address)[""']?\s*[:=]\s*[""']?)[^,""'\s;}]+",
            "${prefix}[адрес скрыт]");
        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
            @"\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?::\d{1,5})?\b",
            "[IP скрыт]");
        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
            @"(?i)(?<![0-9a-f])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?:%[0-9a-z_-]+)?(?![0-9a-f])",
            "[IPv6 скрыт]");
        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
            @"(?i)(?:[a-z]:\\|\\\\)[^\s:]+", "[путь скрыт]");
        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
            @"\b[0-9a-f]{32,}\b", "[идентификатор скрыт]");
        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic,
            @"\b[A-Za-z0-9_+/=-]{64,}\b", "[секрет скрыт]");
        diagnostic = System.Text.RegularExpressions.Regex.Replace(diagnostic, @"\s{2,}", " ").Trim();
        const int maximumLength = 64;
        if (diagnostic.Length > maximumLength)
            diagnostic = diagnostic[..maximumLength] + "…";
        return diagnostic;
    }

    private static string FormatProbeStartupFailure(int exitCode, string standardError) =>
        $"Ядро VPN остановилось до начала диагностики (код {exitCode}): {SummarizeEngineDiagnostic(standardError)}";

    private sealed class EngineRuntimeDiagnostic
    {
        private string? _latestError;

        public string? LatestError => Volatile.Read(ref _latestError);

        public void Record(string line)
        {
            if (System.Text.RegularExpressions.Regex.IsMatch(line,
                    @"(?i)\b(fatal|error|failed|invalid|unsupported|unknown|missing)\b"))
                Volatile.Write(ref _latestError, SummarizeEngineDiagnostic(line));
        }
    }

    private static string WritePrivateProfile(string content)
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException();

        var directoryPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "DEYTT", "Connect", "runtime");
        var directory = new DirectoryInfo(directoryPath);
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None, AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            FileSystemRights.FullControl, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None, AccessControlType.Allow));

        directory.Create(security);
        if ((File.GetAttributes(directoryPath) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("Runtime folder cannot be a reparse point.");
        directory.SetAccessControl(security);

        var path = Path.Combine(directoryPath, $"{Guid.NewGuid():N}.json");
        using (var stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None))
        using (var writer = new StreamWriter(stream, new System.Text.UTF8Encoding(false)))
            writer.Write(content);

        var fileSecurity = new FileSecurity();
        fileSecurity.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        fileSecurity.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl, AccessControlType.Allow));
        fileSecurity.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            FileSystemRights.FullControl, AccessControlType.Allow));
        new FileInfo(path).SetAccessControl(fileSecurity);
        return path;
    }

    private Process StartOwnedEngine(string enginePath, string profilePath, EngineRuntimeDiagnostic diagnostic)
    {
        var process = StartEngine(enginePath, "run", profilePath, out _, runtimeDiagnostic: diagnostic);
        try
        {
            _engineLifetime = EngineProcessLifetime.Attach(process);
            _engine = process;
            return process;
        }
        catch
        {
            TryKill(process);
            process.Dispose();
            throw;
        }
    }

    private static async Task WatchProbeEngineAsync(Process process, CancellationTokenSource measurement,
        CancellationToken cancellationToken)
    {
        try
        {
            await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
            measurement.Cancel();
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { }
    }

    private void StopCore()
    {
        _healthMonitor?.Stop();
        _healthMonitor?.Dispose();
        _healthMonitor = null;
        _verification?.Cancel();
        _verification?.Dispose();
        _verification = null;

        var process = _engine;
        _engine = null;
        _engineLifetime?.Dispose();
        _engineLifetime = null;
        if (process is not null)
        {
            try
            {
                if (!process.HasExited)
                {
                    process.Kill(entireProcessTree: true);
                    process.WaitForExit(5_000);
                }
            }
            catch
            {
                // Continue clearing service state and private profile data.
            }
            process.Dispose();
        }

        if (_profilePath is { } path)
        {
            try { File.Delete(path); }
            catch { }
            _profilePath = null;
        }
        _routeTag = null;
    }

    private static void TryKill(Process process)
    {
        try
        {
            if (!process.HasExited)
                process.Kill(entireProcessTree: true);
        }
        catch
        {
            // The caller reports the validation timeout without leaking process output.
        }
    }

    private ServiceSnapshot SetError(string detail)
    {
        var snapshot = new ServiceSnapshot("error", detail);
        SetSnapshot(snapshot);
        return snapshot;
    }

    private void SetSnapshot(ServiceSnapshot snapshot) => Volatile.Write(ref _snapshot, snapshot);
}
