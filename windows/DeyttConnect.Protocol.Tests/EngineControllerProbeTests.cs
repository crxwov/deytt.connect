using DeyttConnect.Windows.Service;
using System.Diagnostics;
using System.Reflection;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class EngineControllerProbeTests
{
    [Fact]
    public void ProbeValidationAllowsAwgProfilesForOtherCountries()
    {
        var routeTags = new[] { "nl-vless" };
        IReadOnlyDictionary<string, string> awgProfiles = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["awg31"] = "nl profile",
            ["awg15:Zmk"] = "fi profile",
            ["awg31:cnU"] = "ru profile",
        };

        Assert.True(EngineController.IsProbeRequestValid("{}", routeTags, awgProfiles, "HEAD"));
    }

    [Fact]
    public void ProbeValidationStillRejectsDuplicateRouteTags()
    {
        var routeTags = new[] { "nl-vless", "nl-vless" };

        Assert.False(EngineController.IsProbeRequestValid("{}", routeTags,
            new Dictionary<string, string>(StringComparer.Ordinal), "HEAD"));
    }

    [Fact]
    public void ProbeValidationKeepsAwgIdAndProfileSizeLimitsForUnrequestedRoutes()
    {
        var routeTags = new[] { "nl-vless" };
        var malformedId = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["not-an-awg-id"] = "profile",
        };
        var oversizedProfile = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["awg31:Zmk"] = new string('x', 512 * 1024 + 1),
        };

        Assert.False(EngineController.IsProbeRequestValid("{}", routeTags, malformedId, "HEAD"));
        Assert.False(EngineController.IsProbeRequestValid("{}", routeTags, oversizedProfile, "HEAD"));
    }

    [Fact]
    public async Task ProbeRejectedWhileConnectedDoesNotStopTheExistingTunnelProcess()
    {
        if (!OperatingSystem.IsWindows())
            return;

        using var process = Process.Start(new ProcessStartInfo("cmd.exe")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            ArgumentList = { "/c", "timeout", "/t", "30", "/nobreak" },
        })!;
        try
        {
            var controller = new EngineController();
            typeof(EngineController).GetField("_engine", BindingFlags.Instance | BindingFlags.NonPublic)!
                .SetValue(controller, process);
            typeof(EngineController).GetField("_snapshot", BindingFlags.Instance | BindingFlags.NonPublic)!
                .SetValue(controller, new ServiceSnapshot("connected", "Tunnel active"));

            var result = await controller.ProbeAsync("{}", ["nl-vless"],
                new Dictionary<string, string>(StringComparer.Ordinal), "HEAD", "", CancellationToken.None);

            Assert.Equal("probe_error", result.State);
            Assert.Equal("connected", controller.Snapshot.State);
            Assert.False(process.HasExited);
        }
        finally
        {
            if (!process.HasExited)
                process.Kill(entireProcessTree: true);
        }
    }

    [Fact]
    public void ProgressAcceptsSeededCountryAndAwgTagsButRejectsUnknownTags()
    {
        var controller = new EngineController();
        var progress = (Dictionary<string, ServiceProbeResult>)typeof(EngineController)
            .GetField("_probeProgress", BindingFlags.Instance | BindingFlags.NonPublic)!
            .GetValue(controller)!;
        progress["route:NL:VLESS"] = new ServiceProbeResult("route:NL:VLESS", null, null, null, "latency", 1);
        progress["awg31:cnA"] = new ServiceProbeResult("awg31:cnA", null, null, null, "latency", 1);

        var publish = typeof(EngineController).GetMethod("PublishProbeProgress", BindingFlags.Instance | BindingFlags.NonPublic)!;
        publish.Invoke(controller, [new ServiceProbeResult("route:NL:VLESS", 24, null, null, "latency", 1)]);
        publish.Invoke(controller, [new ServiceProbeResult("awg31:cnA", 38, null, null, "latency", 1)]);
        publish.Invoke(controller, [new ServiceProbeResult("route:FI:VLESS", 12, null, null, "latency", 1)]);

        var actual = controller.GetProbeProgress().ProbeResults!.ToDictionary(item => item.RouteTag);
        Assert.Equal(24, actual["route:NL:VLESS"].LatencyMilliseconds);
        Assert.Equal(38, actual["awg31:cnA"].LatencyMilliseconds);
        Assert.DoesNotContain(actual.Keys, key => key == "route:FI:VLESS");
    }

    [Fact]
    public void ProbeStartupFailureIncludesExitCodeAndSanitizesEngineDiagnostic()
    {
        var format = typeof(EngineController).GetMethod("FormatProbeStartupFailure",
            BindingFlags.Static | BindingFlags.NonPublic)!;

        var message = (string)format.Invoke(null,
            [1, "FATAL[0000] dial 203.0.113.5:51820 password=synthetic-secret"]
        )!;

        Assert.StartsWith("Ядро VPN остановилось до начала диагностики (код 1): ", message);
        Assert.Contains("[IP скрыт]", message);
        Assert.Contains("[скрыто]", message);
        Assert.DoesNotContain("synthetic-secret", message);
        Assert.DoesNotContain("203.0.113.5", message);
    }

    [Fact]
    public async Task AwgResolutionCachesHostnamesPrefersIpv4AndFailsOnlyTheUnresolvableEndpoint()
    {
        var calls = new ConcurrentDictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        const string profile = """
            {"endpoints":[
              {"type":"awg","tag":"awg-good","peers":[{"address":"same.test","port":9},{"address":"127.0.0.1","port":9}]},
              {"type":"awg","tag":"awg-bad","peers":[{"address":"missing.test","port":9}]},
              {"type":"awg","tag":"awg-good-again","peers":[{"address":"SAME.TEST","port":10}]}
            ]}
            """;

        var result = await EngineController.ResolveAwgPeerHostsAsync(profile, (host, _) =>
        {
            calls.AddOrUpdate(host, 1, (_, count) => count + 1);
            if (host == "missing.test")
                throw new SocketException((int)SocketError.HostNotFound);
            return Task.FromResult(new[] { IPAddress.IPv6Loopback, IPAddress.Loopback });
        }, CancellationToken.None);

        Assert.Equal(1, calls["same.test"]);
        Assert.Equal(1, calls["missing.test"]);
        Assert.Equal(new HashSet<string>(["awg-bad"], StringComparer.Ordinal), result.FailedEndpointTags);
        var endpoints = JsonNode.Parse(result.Profile)!["endpoints"]!.AsArray();
        Assert.Equal("127.0.0.1", endpoints[0]!["peers"]![0]!["address"]!.GetValue<string>());
        Assert.Equal("127.0.0.1", endpoints[2]!["peers"]![0]!["address"]!.GetValue<string>());
    }

    [Fact]
    public async Task AwgResolutionDeadlineIsEndpointLocalButCallerCancellationPropagates()
    {
        const string profile = """
            {"endpoints":[{"type":"awg","tag":"awg-one","peers":[{"address":"slow.test","port":9}]}]}
            """;
        var timedOut = await EngineController.ResolveAwgPeerHostsAsync(profile,
            async (_, cancellationToken) =>
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                return [];
            }, CancellationToken.None, TimeSpan.FromMilliseconds(30));
        Assert.Contains("awg-one", timedOut.FailedEndpointTags);

        using var callerCancellation = new CancellationTokenSource();
        var operation = EngineController.ResolveAwgPeerHostsAsync(profile,
            async (_, cancellationToken) =>
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                return [];
            }, callerCancellation.Token, TimeSpan.FromSeconds(5));
        callerCancellation.CancelAfter(30);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => operation);
    }

    [Fact]
    public async Task FailedFirstAwgProbeIsRemovedAndFinalRouteKeepsRemainingProxyCredentials()
    {
        const string awgConfig = """
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = missing.test:9
            """;
        const string subscription = """
            {"inbounds":[{"type":"tun","tag":"tun","address":["172.19.0.1/30"]}],
             "outbounds":[{"type":"urltest","tag":"🇪🇺 автоподбор"},
                          {"type":"vless","tag":"route:FI:VLESS","server":"127.0.0.1","server_port":9}],
             "route":{"final":"🇪🇺 автоподбор","rules":[]},"dns":{"servers":[]}}
            """;
        var requested = new[]
        {
            ProbeEndpoint("awg31:ZmI") with { AwgConfig = awgConfig, InboundTag = "probe-awg", Port = 12345 },
            ProbeEndpoint("route:FI:VLESS") with { InboundTag = "probe-fi", Username = "fi-user", Password = "fi-pass", Port = 12346 },
        };
        var built = TunnelProfileBuilder.BuildProbeProfile(subscription, requested);
        var resolved = await EngineController.ResolveProbeAwgHostsAsync(built, requested,
            (_, _) => Task.FromException<IPAddress[]>(new SocketException((int)SocketError.HostNotFound)),
            CancellationToken.None);

        Assert.Equal(new[] { "route:FI:VLESS" }, resolved.Endpoints.Select(endpoint => endpoint.RouteTag));
        Assert.Equal("Не удалось разрешить адрес узла AmneziaWG.", Assert.Single(resolved.FailedResults).Error);
        var root = JsonNode.Parse(resolved.Profile)!;
        Assert.Equal("route:FI:VLESS", root["route"]!["final"]!.GetValue<string>());
        Assert.DoesNotContain(root["inbounds"]!.AsArray(), inbound => inbound?["tag"]?.GetValue<string>() == "probe-awg");
        var viableInbound = Assert.Single(root["inbounds"]!.AsArray());
        Assert.Equal("probe-fi", viableInbound!["tag"]!.GetValue<string>());
        Assert.Equal("fi-user", viableInbound["users"]![0]!["username"]!.GetValue<string>());
        Assert.Equal("fi-pass", viableInbound["users"]![0]!["password"]!.GetValue<string>());
    }

    [Fact]
    public async Task FailedOnlyAwgProbeReturnsRouteErrorWithoutAStaleFinalOrInbound()
    {
        const string awgConfig = """
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = missing.test:9
            """;
        const string subscription = """
            {"inbounds":[{"type":"tun","tag":"tun","address":["172.19.0.1/30"]}],
             "outbounds":[{"type":"urltest","tag":"🇪🇺 автоподбор"}],
             "route":{"final":"🇪🇺 автоподбор","rules":[]},"dns":{"servers":[]}}
            """;
        var requested = new[]
        {
            ProbeEndpoint("awg31:ZmI") with { AwgConfig = awgConfig, InboundTag = "probe-awg", Port = 12345 },
        };
        var built = TunnelProfileBuilder.BuildProbeProfile(subscription, requested);
        var resolved = await EngineController.ResolveProbeAwgHostsAsync(built, requested,
            (_, _) => Task.FromException<IPAddress[]>(new SocketException((int)SocketError.HostNotFound)),
            CancellationToken.None);

        Assert.Empty(resolved.Endpoints);
        Assert.Equal("awg31:ZmI", Assert.Single(resolved.FailedResults).RouteTag);
        Assert.Equal("Не удалось разрешить адрес узла AmneziaWG.", resolved.FailedResults[0].Error);
        var root = JsonNode.Parse(resolved.Profile)!;
        Assert.Empty(root["endpoints"]!.AsArray());
        Assert.Empty(root["inbounds"]!.AsArray());
        Assert.Null(root["route"]!["final"]);
    }

    [Fact]
    public async Task RouteMeasurementFinishesAllLatenciesBeforeSequentialSpeedChecks()
    {
        var endpoints = new[] { ProbeEndpoint("route:NL:VLESS"), ProbeEndpoint("awg31:cnA") };
        var latencyFinished = new ConcurrentDictionary<string, bool>(StringComparer.Ordinal);
        var activeSpeedChecks = 0;
        var maximumSpeedChecks = 0;

        var results = await EngineController.MeasureRoutesAsync(endpoints, "HEAD", new string('t', 32),
            CancellationToken.None, _ => { },
            async (endpoint, _, _, _) =>
            {
                await Task.Delay(10);
                latencyFinished[endpoint.RouteTag] = true;
                return 20;
            },
            async (endpoint, _, _, report) =>
            {
                Assert.Equal(endpoints.Length, latencyFinished.Count);
                var active = Interlocked.Increment(ref activeSpeedChecks);
                maximumSpeedChecks = Math.Max(maximumSpeedChecks, active);
                report(1000, 1000, 500);
                await Task.Delay(10);
                Interlocked.Decrement(ref activeSpeedChecks);
                return new EngineController.DownloadMeasurement(500, null, false);
            });

        Assert.Equal(1, maximumSpeedChecks);
        Assert.All(results, result => Assert.Equal("complete", result.Stage));
        Assert.All(results, result => Assert.NotNull(result.LatencyMilliseconds));
    }

    [Fact]
    public async Task RouteMeasurementCapsLatencyConcurrencyAtFourAndPublishesEveryRoute()
    {
        var endpoints = Enumerable.Range(0, 9)
            .Select(index => ProbeEndpoint($"route:{index}:VLESS"))
            .ToArray();
        var latencyFinished = new ConcurrentDictionary<string, bool>(StringComparer.Ordinal);
        var published = new ConcurrentBag<ServiceProbeResult>();
        var fourLatenciesStarted = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var latencyStarted = 0;
        var activeLatencies = 0;
        var maximumLatencies = 0;
        var activeSpeedChecks = 0;
        var maximumSpeedChecks = 0;
        using var cancellation = new CancellationTokenSource(TimeSpan.FromSeconds(3));

        var results = await EngineController.MeasureRoutesAsync(endpoints, "HEAD", new string('t', 32),
            cancellation.Token, published.Add,
            async (endpoint, _, token, report) =>
            {
                var active = Interlocked.Increment(ref activeLatencies);
                UpdateMaximum(ref maximumLatencies, active);
                try
                {
                    if (Interlocked.Increment(ref latencyStarted) == 4)
                        fourLatenciesStarted.TrySetResult(true);
                    await fourLatenciesStarted.Task.WaitAsync(token);
                    report(1, 40);
                    await Task.Delay(20, token);
                    latencyFinished[endpoint.RouteTag] = true;
                    return 40;
                }
                finally
                {
                    Interlocked.Decrement(ref activeLatencies);
                }
            },
            async (_, _, token, report) =>
            {
                Assert.Equal(endpoints.Length, latencyFinished.Count);
                var active = Interlocked.Increment(ref activeSpeedChecks);
                UpdateMaximum(ref maximumSpeedChecks, active);
                try
                {
                    report(1024, 1024, 500);
                    await Task.Delay(5, token);
                    return new EngineController.DownloadMeasurement(500, null, false);
                }
                finally
                {
                    Interlocked.Decrement(ref activeSpeedChecks);
                }
            });

        Assert.Equal(4, maximumLatencies);
        Assert.Equal(1, maximumSpeedChecks);
        Assert.All(results, result => Assert.Equal("complete", result.Stage));
        Assert.All(results, result => Assert.Equal(40, result.LatencyMilliseconds));
        Assert.Equal(endpoints.Length, published
            .Where(result => result.Stage == "latency")
            .Select(result => result.RouteTag)
            .Distinct(StringComparer.Ordinal)
            .Count());
    }

    [Fact]
    public async Task FailedDownloadDoesNotRetainProvisionalSpeed()
    {
        var endpoint = ProbeEndpoint("route:NL:VLESS");
        var published = new ConcurrentBag<ServiceProbeResult>();

        var results = await EngineController.MeasureRoutesAsync([endpoint], "HEAD", new string('t', 32),
            CancellationToken.None, published.Add,
            (_, _, _, _) => Task.FromResult<long?>(25),
            (_, _, _, report) =>
            {
                report(1024, 2048, 700);
                return Task.FromResult(new EngineController.DownloadMeasurement(null, "download failed", false));
            });

        var result = Assert.Single(results);
        Assert.Equal("error", result.Stage);
        Assert.Null(result.BytesPerSecond);
        Assert.Equal("download failed", result.Error);
        Assert.Equal(700, Assert.Single(published, progress => progress.Stage == "download").BytesPerSecond);
        Assert.Null(Assert.Single(published, progress => progress.Stage == "error").BytesPerSecond);
    }

    [Fact]
    public async Task CancelledDownloadDoesNotRetainProvisionalSpeed()
    {
        var endpoint = ProbeEndpoint("route:NL:VLESS");
        var published = new ConcurrentBag<ServiceProbeResult>();
        using var cancellation = new CancellationTokenSource();

        var results = await EngineController.MeasureRoutesAsync([endpoint], "HEAD", new string('t', 32),
            cancellation.Token, published.Add,
            (_, _, _, _) => Task.FromResult<long?>(25),
            (_, _, _, report) =>
            {
                report(1024, 2048, 700);
                cancellation.Cancel();
                return Task.FromException<EngineController.DownloadMeasurement>(
                    new OperationCanceledException(cancellation.Token));
            });

        var result = Assert.Single(results);
        Assert.Equal("cancelled", result.Stage);
        Assert.Null(result.BytesPerSecond);
        Assert.Null(result.Error);
        Assert.Equal(700, Assert.Single(published, progress => progress.Stage == "download").BytesPerSecond);
        Assert.Null(Assert.Single(published, progress => progress.Stage == "cancelled").BytesPerSecond);
    }

    [Fact]
    public async Task RouteMeasurementCancellationKeepsCompletedSpeedAndOtherRouteLatency()
    {
        using var cancellation = new CancellationTokenSource();
        var endpoints = new[] { ProbeEndpoint("route:NL:VLESS"), ProbeEndpoint("route:FI:VLESS") };

        var results = await EngineController.MeasureRoutesAsync(endpoints, "HEAD", new string('t', 32),
            cancellation.Token, _ => { },
            (endpoint, _, _, _) => Task.FromResult<long?>(endpoint.RouteTag == "route:NL:VLESS" ? 18 : 42),
            (endpoint, _, token, report) =>
            {
                if (endpoint.RouteTag == "route:NL:VLESS")
                {
                    report(2000, 2000, 400);
                    return Task.FromResult(new EngineController.DownloadMeasurement(400, null, false));
                }
                cancellation.Cancel();
                return Task.FromException<EngineController.DownloadMeasurement>(new OperationCanceledException(token));
            });

        Assert.Equal("complete", results[0].Stage);
        Assert.Equal(400, results[0].BytesPerSecond);
        Assert.Equal(18, results[0].LatencyMilliseconds);
        Assert.Equal("cancelled", results[1].Stage);
        Assert.Equal(42, results[1].LatencyMilliseconds);
    }

    [Fact]
    public async Task DownloadRateIncludesDelayBeforeFirstBodyChunk()
    {
        await using var body = new DelayedFirstChunkStream(1024 * 1024, TimeSpan.FromMilliseconds(180));

        var result = await EngineController.ReadDownloadSampleAsync(body, 1024 * 1024,
            CancellationToken.None, (_, _, _) => { }, TimeSpan.FromSeconds(2));

        Assert.NotNull(result.BytesPerSecond);
        Assert.InRange(result.BytesPerSecond!.Value, 1, 8 * 1024 * 1024);
    }

    [Fact]
    public async Task DownloadRateRejectsPrematureEofAgainstKnownLength()
    {
        await using var body = new MemoryStream(new byte[128 * 1024]);

        var result = await EngineController.ReadDownloadSampleAsync(body, 32L * 1024 * 1024,
            CancellationToken.None, (_, _, _) => { }, TimeSpan.FromSeconds(2));

        Assert.Null(result.BytesPerSecond);
        Assert.True(result.Retryable);
        Assert.Contains("неполный", result.Error, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task DownloadRateRejectsTinyCompletedResponses()
    {
        await using var body = new MemoryStream(new byte[128]);

        var result = await EngineController.ReadDownloadSampleAsync(body, 128,
            CancellationToken.None, (_, _, _) => { }, TimeSpan.FromSeconds(2));

        Assert.Null(result.BytesPerSecond);
        Assert.True(result.Retryable);
    }

    [Fact]
    public async Task DownloadRateAcceptsPartialBytesWhenSampleWindowTimesOut()
    {
        await using var body = new DataThenBlockedStream(64 * 1024);

        var result = await EngineController.ReadDownloadSampleAsync(body, 32L * 1024 * 1024,
            CancellationToken.None, (_, _, _) => { }, TimeSpan.FromMilliseconds(120));

        Assert.NotNull(result.BytesPerSecond);
        Assert.False(result.Retryable);
    }

    private static ProbeRouteEndpoint ProbeEndpoint(string routeTag) =>
        new(routeTag, "probe", 12345, "user", "secret", null);

    private static void UpdateMaximum(ref int maximum, int candidate)
    {
        var current = Volatile.Read(ref maximum);
        while (candidate > current)
        {
            var previous = Interlocked.CompareExchange(ref maximum, candidate, current);
            if (previous == current)
                return;
            current = previous;
        }
    }

    private sealed class DelayedFirstChunkStream(int size, TimeSpan firstReadDelay) : MemoryStream(new byte[size])
    {
        private bool _delayed;

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            if (!_delayed)
            {
                _delayed = true;
                await Task.Delay(firstReadDelay, cancellationToken);
            }
            return await base.ReadAsync(buffer, cancellationToken);
        }
    }

    private sealed class DataThenBlockedStream(int size) : Stream
    {
        private int _sent;

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            if (_sent == 0)
            {
                var count = Math.Min(size, buffer.Length);
                buffer.Span[..count].Fill(0x64);
                _sent = count;
                return count;
            }
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            return 0;
        }
    }
}
