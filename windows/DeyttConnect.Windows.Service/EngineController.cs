using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Net.NetworkInformation;
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
    private CancellationTokenSource? _verification;
    private CancellationTokenSource? _probeCancellation;
    private TunnelHealthMonitorLifetime? _healthMonitor;
    private string? _profilePath;
    private string? _routeTag;
    private ServiceSnapshot _snapshot = new("disconnected", "VPN выключен");
    private readonly object _probeProgressLock = new();
    private readonly Dictionary<string, ServiceProbeResult> _probeProgress = new(StringComparer.Ordinal);
    private int _consecutiveHealthFailures;

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
                if (check.ExitCode != 0)
                {
                    var diagnostic = await checkError.ConfigureAwait(false);
                    StopCore();
                    return SetError($"Ядро: {SummarizeEngineDiagnostic(diagnostic)}");
                }
            }

            var runtimeDiagnostic = new EngineRuntimeDiagnostic();
            _engine = StartEngine(enginePath, "run", _profilePath, out _,
                runtimeDiagnostic: runtimeDiagnostic);
            _routeTag = routeTag;
            var process = _engine;
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
        try
        {
            if (Snapshot.State is "connected" or "starting" or "checking" ||
                _engine is { HasExited: false })
                return ProbeError("Сначала отключите VPN для проверки маршрутов.");
            var awgProfilesBytes = awgProfiles.Sum(item => System.Text.Encoding.UTF8.GetByteCount(item.Value));
            if (System.Text.Encoding.UTF8.GetByteCount(profile) is <= 0 or > MaximumProfileBytes ||
                routeTags.Count is < 1 or > WindowsPipeProtocol.MaximumProbeRouteCount ||
                awgProfiles.Count > WindowsPipeProtocol.MaximumAwgProfiles || method is not ("HEAD" or "GET") ||
                awgProfilesBytes > MaximumAwgProfilesTotalBytes ||
                awgProfiles.Any(item => !routeTags.Contains(item.Key, StringComparer.Ordinal) ||
                                        System.Text.Encoding.UTF8.GetByteCount(item.Value) > MaximumAwgProfileBytes))
                return ProbeError("Список маршрутов не прошёл проверку.");

            using var probeCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            Interlocked.Exchange(ref _probeCancellation, probeCancellation)?.Cancel();
            var probeToken = probeCancellation.Token;

            var endpoints = CreateProbeEndpoints(routeTags, awgProfiles);
            lock (_probeProgressLock)
            {
                _probeProgress.Clear();
                foreach (var endpoint in endpoints)
                    _probeProgress[endpoint.RouteTag] = new ServiceProbeResult(endpoint.RouteTag, null, null, null,
                        "latency", 1);
            }
            var probeProfile = TunnelProfileBuilder.BuildProbeProfile(profile, endpoints,
                FindActiveHappTunnel());
            _profilePath = WritePrivateProfile(probeProfile);
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
                if (check.ExitCode != 0)
                {
                    var diagnostic = await checkError.ConfigureAwait(false);
                    return ProbeError($"Ядро: {SummarizeEngineDiagnostic(diagnostic)}");
                }
            }

            _engine = StartEngine(enginePath, "run", _profilePath, out _);
            var process = _engine;
            await Task.Delay(TimeSpan.FromMilliseconds(350), probeToken).ConfigureAwait(false);
            if (process.HasExited)
                return ProbeError("Ядро VPN остановилось до начала диагностики.");

            var results = await MeasureRoutesAsync(endpoints, method, token, probeToken, PublishProbeProgress)
                .ConfigureAwait(false);
            lock (_probeProgressLock)
                _probeProgress.Clear();
            return new ServiceSnapshot("probe_complete", "Проверка маршрутов завершена.",
                ProbeResults: results);
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
            lock (_probeProgressLock)
                _probeProgress.Clear();
            return new ServiceSnapshot("probe_cancelled", "Проверка маршрутов остановлена.", ProbeResults: []);
        }
        catch
        {
            return ProbeError("Не удалось проверить маршруты.");
        }
        finally
        {
            lock (_probeProgressLock)
                _probeProgress.Clear();
            Interlocked.Exchange(ref _probeCancellation, null);
            StopCore();
            SetSnapshot(new ServiceSnapshot("disconnected", "VPN выключен"));
            _gate.Release();
        }
    }

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
            StopCore();
            var exitCode = unchecked((uint)process.ExitCode);
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

    private static async Task<IReadOnlyList<ServiceProbeResult>> MeasureRoutesAsync(
        IReadOnlyList<ProbeRouteEndpoint> endpoints, string method, string token,
        CancellationToken cancellationToken, Action<ServiceProbeResult> publish)
    {
        async Task<ServiceProbeResult> MeasureEndpointAsync(ProbeRouteEndpoint endpoint)
        {
            var latency = await MeasureLatencyAsync(endpoint, method, cancellationToken,
                (attempt, value) => publish(new ServiceProbeResult(endpoint.RouteTag, value, null, null,
                    attempt == 1 ? "latency" : "retry", attempt))).ConfigureAwait(false);
            long? speed = null;
            var error = latency is null ? "Проверочный HTTPS-сервер не ответил." : null;
            if (latency is not null && token.Length is >= 32 and <= 256 &&
                token.All(character => character is >= '\x21' and <= '\x7e'))
            {
                for (var attempt = 1; attempt <= 2; attempt++)
                {
                    publish(new ServiceProbeResult(endpoint.RouteTag, latency, null, null, "waiting_speed", attempt));
                    try
                    {
                        var download = await MeasureDownloadAsync(endpoint, token, cancellationToken,
                            (received, total, liveSpeed) => publish(new ServiceProbeResult(endpoint.RouteTag,
                                latency, liveSpeed, null, "download", attempt, received, total)))
                            .ConfigureAwait(false);
                        speed = download.BytesPerSecond;
                        error = download.Error;
                        if (speed is not null || !download.Retryable)
                            break;
                    }
                    catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                    {
                        throw;
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
            }
            else if (latency is not null)
            {
                error = "Скорость недоступна для этой сессии.";
            }

            var result = new ServiceProbeResult(endpoint.RouteTag, latency, speed, error,
                error is null ? "complete" : "error", null);
            publish(result);
            return result;
        }

        return await Task.WhenAll(endpoints.Select(MeasureEndpointAsync)).ConfigureAwait(false);
    }

    private static async Task<long?> MeasureLatencyAsync(ProbeRouteEndpoint endpoint, string method,
        CancellationToken cancellationToken, Action<int, long?> reportAttempt)
    {
        var targets = new[]
        {
            (Method: method, Url: "https://cp.cloudflare.com/generate_204"),
            (Method: "GET", Url: "https://www.cloudflare.com/cdn-cgi/trace"),
        };

        using var sampleCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        async Task<long?> MeasureTargetAsync((string Method, string Url) target)
        {
            try
            {
                using var handler = CreateProxyHandler(endpoint);
                using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(3) };
                using var request = new HttpRequestMessage(new HttpMethod(target.Method), target.Url);
                request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
                request.Headers.ConnectionClose = true;
                var started = Stopwatch.GetTimestamp();
                using var response = await client.SendAsync(request,
                    HttpCompletionOption.ResponseHeadersRead, sampleCancellation.Token).ConfigureAwait(false);
                if (!response.IsSuccessStatusCode)
                    return null;
                return (long)Math.Max(1, Stopwatch.GetElapsedTime(started).TotalMilliseconds);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (OperationCanceledException)
            {
                return null;
            }
            catch (Exception error) when (error is HttpRequestException or IOException)
            {
                return null;
            }
        }

        var requests = targets.Select(MeasureTargetAsync).ToList();

        long? latency = null;
        while (requests.Count > 0)
        {
            var request = await Task.WhenAny(requests).ConfigureAwait(false);
            requests.Remove(request);
            latency = await request.ConfigureAwait(false);
            if (latency is not null)
                break;
        }

        sampleCancellation.Cancel();
        try
        {
            await Task.WhenAll(requests).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            // The first valid HTTPS response won; cancel and drain the slower fallback.
        }

        reportAttempt(1, latency);
        return latency;
    }

    private readonly record struct DownloadMeasurement(long? BytesPerSecond, string? Error, bool Retryable);

    private static async Task<DownloadMeasurement> MeasureDownloadAsync(ProbeRouteEndpoint endpoint, string token,
        CancellationToken cancellationToken, Action<long, long, long?> reportProgress)
    {
        using var handler = CreateProxyHandler(endpoint);
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
        using var sampleTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        sampleTimeout.CancelAfter(TimeSpan.FromSeconds(5));
        var buffer = new byte[32 * 1024];
        long received = 0;
        long firstByte = 0;
        long lastProgress = 0;
        while (received < 32L * 1024 * 1024)
        {
            int count;
            try
            {
                count = await body.ReadAsync(buffer.AsMemory(), sampleTimeout.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested && received > 0)
            {
                break;
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                return new DownloadMeasurement(null, "Сервер скорости не передал данные за пять секунд.", true);
            }
            if (count <= 0)
                break;
            if (firstByte == 0)
                firstByte = Stopwatch.GetTimestamp();
            received += count;
            var elapsedMs = Stopwatch.GetElapsedTime(firstByte).TotalMilliseconds;
            if (lastProgress == 0 || Stopwatch.GetElapsedTime(lastProgress).TotalMilliseconds >= 200)
            {
                reportProgress(received, Math.Min(response.Content.Headers.ContentLength ?? 32L * 1024 * 1024,
                    32L * 1024 * 1024), elapsedMs >= 200 ? (long)(received * 1000d / elapsedMs) : null);
                lastProgress = Stopwatch.GetTimestamp();
            }
        }

        if (received == 0 || firstByte == 0)
            return new DownloadMeasurement(null, "Сервер скорости вернул пустой ответ.", true);
        var elapsed = Math.Max(1, Stopwatch.GetElapsedTime(firstByte).TotalMilliseconds);
        return new DownloadMeasurement((long)(received * 1000d / elapsed), null, false);
    }

    private void PublishProbeProgress(ServiceProbeResult result)
    {
        if (result.RouteTag.Length is < 1 or > 64 || result.RouteTag.Any(character =>
                !(char.IsAsciiLetterOrDigit(character) || character is '.' or '_' or '-')))
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
            if (_probeProgress.Count < 32 || _probeProgress.ContainsKey(safe.RouteTag))
                _probeProgress[safe.RouteTag] = safe;
    }

    private static HttpClientHandler CreateProxyHandler(ProbeRouteEndpoint endpoint) => new()
    {
        UseProxy = true,
        Proxy = new WebProxy($"http://127.0.0.1:{endpoint.Port}")
        {
            Credentials = new NetworkCredential(endpoint.Username, endpoint.Password),
        },
        AllowAutoRedirect = false,
    };

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
