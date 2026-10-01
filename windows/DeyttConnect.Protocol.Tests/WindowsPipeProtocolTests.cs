using System.Text;
using System.Text.Json;
using System.Net;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class WindowsPipeProtocolTests
{
    [Theory]
    [InlineData(WindowsPipeCommand.Status, "{\"command\":\"status\"}")]
    [InlineData(WindowsPipeCommand.CancelProbe, "{\"command\":\"cancel-probe\"}")]
    [InlineData(WindowsPipeCommand.ProbeProgress, "{\"command\":\"probe-progress\"}")]
    public void Request_UsesExistingCommandWireNames(WindowsPipeCommand command, string expected)
    {
        var json = Encoding.UTF8.GetString(WindowsPipeProtocol.SerializeRequest(new WindowsPipeRequest
        {
            Command = command,
        }));

        Assert.Equal(expected, json);
        Assert.Equal(command, WindowsPipeProtocol.DeserializeRequest(Encoding.UTF8.GetBytes(json)).Command);
    }

    [Fact]
    public void Request_ConnectAndProbeFieldsRoundTrip()
    {
        var connect = new WindowsPipeRequest
        {
            Command = WindowsPipeCommand.Connect,
            Profile = "vless://example",
            RouteTag = "nl-vless",
            AwgConfig = "[Interface]",
        };
        var restoredConnect = WindowsPipeProtocol.DeserializeRequest(WindowsPipeProtocol.SerializeRequest(connect));
        Assert.Equal(connect, restoredConnect);

        var probe = new WindowsPipeRequest
        {
            Command = WindowsPipeCommand.Probe,
            Profile = "profile",
            RouteTags = ["nl", "fi"],
            Method = "GET",
            Token = "test-token",
            AwgProfiles = new Dictionary<string, string> { ["fi-awg"] = "config" },
        };
        var restoredProbe = WindowsPipeProtocol.DeserializeRequest(WindowsPipeProtocol.SerializeRequest(probe));
        Assert.Equal(probe.Command, restoredProbe.Command);
        Assert.Equal(probe.Profile, restoredProbe.Profile);
        Assert.Equal(probe.RouteTags, restoredProbe.RouteTags);
        Assert.Equal(probe.Method, restoredProbe.Method);
        Assert.Equal(probe.Token, restoredProbe.Token);
        Assert.Equal(probe.AwgProfiles, restoredProbe.AwgProfiles);
    }

    [Fact]
    public void Snapshot_RoundTripsAndOmitsNullOptionalFields()
    {
        var snapshot = new WindowsTunnelSnapshot("connected", "Подключено", "nl-vless",
            DateTimeOffset.Parse("2026-09-30T12:00:00+00:00"),
            [new WindowsRouteProbeResult("nl", 12, 2048, null)]);

        var json = Encoding.UTF8.GetString(WindowsPipeProtocol.SerializeResponse(snapshot));
        Assert.DoesNotContain("\"error\":null", json, StringComparison.Ordinal);
        var restored = WindowsPipeProtocol.DeserializeResponse(Encoding.UTF8.GetBytes(json));
        Assert.Equal(snapshot.State, restored.State);
        Assert.Equal(snapshot.Detail, restored.Detail);
        Assert.Equal(snapshot.ProbeResults![0], restored.ProbeResults![0]);
    }

    [Fact]
    public void Snapshot_RoundTripsOptionalProbeProgressFields()
    {
        var snapshot = new WindowsTunnelSnapshot("probe_progress", "Working", ProbeResults:
        [new WindowsRouteProbeResult("nl-vless", null, null, null, "download", 1,
            1024 * 1024, 32 * 1024 * 1024)]);

        var restored = WindowsPipeProtocol.DeserializeResponse(WindowsPipeProtocol.SerializeResponse(snapshot));

        Assert.Equal(snapshot.ProbeResults, restored.ProbeResults);
    }

    [Fact]
    public void Snapshot_HealthTimestampIsOptionalAndRequiredForFreshGreenStatus()
    {
        var now = DateTimeOffset.Parse("2026-09-30T12:00:00+00:00");
        var legacy = WindowsPipeProtocol.DeserializeResponse(
            Encoding.UTF8.GetBytes("{\"state\":\"connected\",\"detail\":\"Connected\"}"));
        Assert.Null(legacy.HealthCheckedAt);
        Assert.False(WindowsTunnelHealth.IsFreshlyConnected(legacy, now));

        var initialSuccess = WindowsTunnelHealth.ApplyCanaryResult(true, 0);
        Assert.Equal("unknown", initialSuccess.State);
        var tunnelBoundSuccess = WindowsTunnelHealth.ApplyCanaryResult(true, 0, tunnelRouteProven: true);
        Assert.Equal("connected", tunnelBoundSuccess.State);
        var fresh = new WindowsTunnelSnapshot(tunnelBoundSuccess.State, "Connected", HealthCheckedAt: now);
        Assert.True(WindowsTunnelHealth.IsFreshlyConnected(fresh, now));
        Assert.False(WindowsTunnelHealth.IsFreshlyConnected(
            new WindowsTunnelSnapshot(initialSuccess.State, "Host HTTPS only", HealthCheckedAt: now), now));
        Assert.False(WindowsTunnelHealth.IsFreshlyConnected(
            fresh with { HealthCheckedAt = now - WindowsTunnelHealth.MaximumFreshness - TimeSpan.FromSeconds(1) }, now));
        Assert.False(WindowsTunnelHealth.IsFreshlyConnected(fresh with { State = "degraded" }, now));
    }

    [Fact]
    public void CanaryFailureRequiresTwoMissesAndSuccessResetsHealthState()
    {
        var transient = WindowsTunnelHealth.ApplyCanaryResult(false, 0);
        Assert.Equal("health_checking", transient.State);
        Assert.Equal(1, transient.ConsecutiveFailures);

        var repeated = WindowsTunnelHealth.ApplyCanaryResult(false, transient.ConsecutiveFailures);
        Assert.Equal("degraded", repeated.State);
        Assert.Equal(2, repeated.ConsecutiveFailures);

        var recovered = WindowsTunnelHealth.ApplyCanaryResult(true, repeated.ConsecutiveFailures,
            tunnelRouteProven: true);
        Assert.Equal("connected", recovered.State);
        Assert.Equal(0, recovered.ConsecutiveFailures);
    }

    [Fact]
    public void CanaryLossAndRecoveryNeverReportFreshConnectedUntilTunnelBoundSuccess()
    {
        var now = DateTimeOffset.Parse("2026-10-01T12:00:00+00:00");
        var connectedAt = now - TimeSpan.FromMinutes(5);
        var failures = 0;

        var initial = WindowsTunnelHealth.ApplyCanaryResult(true, failures, tunnelRouteProven: true);
        failures = initial.ConsecutiveFailures;
        var initialSnapshot = new WindowsTunnelSnapshot(initial.State, "Connected", "nl-vless",
            connectedAt, HealthCheckedAt: now);
        Assert.True(WindowsTunnelHealth.IsFreshlyConnected(initialSnapshot, now));

        var firstMiss = WindowsTunnelHealth.ApplyCanaryResult(false, failures);
        failures = firstMiss.ConsecutiveFailures;
        var checking = new WindowsTunnelSnapshot(firstMiss.State, "Retrying HTTPS", "nl-vless",
            connectedAt, HealthCheckedAt: now + TimeSpan.FromSeconds(60));
        Assert.Equal("health_checking", checking.State);
        Assert.False(WindowsTunnelHealth.IsFreshlyConnected(checking, now + TimeSpan.FromSeconds(60)));

        var secondMiss = WindowsTunnelHealth.ApplyCanaryResult(false, failures);
        failures = secondMiss.ConsecutiveFailures;
        var degraded = new WindowsTunnelSnapshot(secondMiss.State, "HTTPS failed", "nl-vless",
            connectedAt, HealthCheckedAt: now + TimeSpan.FromSeconds(120));
        Assert.Equal("degraded", degraded.State);
        Assert.False(WindowsTunnelHealth.IsFreshlyConnected(degraded, now + TimeSpan.FromSeconds(120)));

        var recovery = WindowsTunnelHealth.ApplyCanaryResult(true, failures, tunnelRouteProven: true);
        var recovered = new WindowsTunnelSnapshot(recovery.State, "HTTPS verified through TUN", "nl-vless",
            connectedAt, HealthCheckedAt: now + TimeSpan.FromSeconds(180));
        Assert.Equal(0, recovery.ConsecutiveFailures);
        Assert.True(WindowsTunnelHealth.IsFreshlyConnected(recovered, now + TimeSpan.FromSeconds(180)));

        var roundTrip = WindowsPipeProtocol.DeserializeResponse(WindowsPipeProtocol.SerializeResponse(degraded));
        Assert.Equal(degraded, roundTrip);
    }

    [Fact]
    public void CanarySourceSelectionRequiresConfiguredAddressOnAnActiveInterface()
    {
        var configured = new[] { "172.19.0.1/30", "fd00::1/126" };
        var activeAddresses = new[]
        {
            new WindowsTunnelCanaryInterface(IPAddress.Parse("192.0.2.10"), 7),
            new WindowsTunnelCanaryInterface(IPAddress.Parse("172.19.0.1"), 42),
        };

        Assert.Equal(new WindowsTunnelCanaryInterface(IPAddress.Parse("172.19.0.1"), 42),
            WindowsTunnelCanaryRoute.SelectInterface(configured, activeAddresses));
        Assert.Null(WindowsTunnelCanaryRoute.SelectInterface(configured,
            [new WindowsTunnelCanaryInterface(IPAddress.Parse("192.0.2.10"), 7)]));
    }

    [Fact]
    public void CanarySourceSelectionSkipsInvalidAndUnspecifiedAddresses()
    {
        var selected = WindowsTunnelCanaryRoute.SelectInterface(
            ["invalid", "0.0.0.0/0", "127.0.0.1", "fd00::1/126"],
            [new WindowsTunnelCanaryInterface(IPAddress.Loopback, 1),
                new WindowsTunnelCanaryInterface(IPAddress.Parse("fd00::1"), 9)]);

        Assert.Equal(new WindowsTunnelCanaryInterface(IPAddress.Parse("fd00::1"), 9), selected);
    }

    [Fact]
    public void CanaryInterfaceIndexEncodingMatchesWinsockByteOrder()
    {
        Assert.Equal(new byte[] { 0, 0, 0, 42 }, WindowsTunnelCanaryRoute.EncodeInterfaceIndex(
            IPAddress.Parse("172.19.0.1"), 42));
        Assert.Equal(new byte[] { 42, 0, 0, 0 }, WindowsTunnelCanaryRoute.EncodeInterfaceIndex(
            IPAddress.Parse("fd00::1"), 42));
        Assert.Throws<ArgumentOutOfRangeException>(() => WindowsTunnelCanaryRoute.EncodeInterfaceIndex(
            IPAddress.Parse("172.19.0.1"), 0));
    }

    [Theory]
    [InlineData("connected")]
    [InlineData("health_checking")]
    [InlineData("degraded")]
    [InlineData("unknown")]
    [InlineData("starting")]
    [InlineData("checking")]
    public void TunnelStatesWithAnActiveOrUnverifiedEngineBlockRouteActions(string state) =>
        Assert.True(WindowsTunnelHealth.IsTunnelActive(state));

    [Theory]
    [InlineData("disconnected")]
    [InlineData("error")]
    [InlineData("probe_complete")]
    public void InactiveTunnelStatesAllowRouteActions(string state) =>
        Assert.False(WindowsTunnelHealth.IsTunnelActive(state));

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task StoppingHealthMonitorLifetimeCancelsItsBackgroundWork(bool dispose)
    {
        using var lifetime = new DeyttConnect.Windows.Service.TunnelHealthMonitorLifetime();
        var backgroundWork = Task.Delay(Timeout.InfiniteTimeSpan, lifetime.Token);

        if (dispose)
            lifetime.Dispose();
        else
            lifetime.Stop();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => backgroundWork);
    }

    [Fact]
    public async Task ReplacingHealthMonitorLifetimeCancelsPreviousMonitorOnly()
    {
        using var previous = new DeyttConnect.Windows.Service.TunnelHealthMonitorLifetime();
        var previousWork = Task.Delay(Timeout.InfiniteTimeSpan, previous.Token);
        previous.Stop();

        using var replacement = new DeyttConnect.Windows.Service.TunnelHealthMonitorLifetime();
        Assert.True(previous.Token.IsCancellationRequested);
        Assert.False(replacement.Token.IsCancellationRequested);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => previousWork);
    }

    [Fact]
    public async Task NetworkWakeDrainsIntervalWaitBeforeReturning()
    {
        var networkChanged = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var intervalDrained = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        var wait = TunnelHealthMonitorWait.WaitAsync(
            token => WaitForIntervalUntilCancelledAsync(token, intervalDrained),
            token => networkChanged.Task.WaitAsync(token), CancellationToken.None);
        networkChanged.SetResult();

        Assert.Equal(TunnelHealthWake.NetworkChanged, await wait);
        Assert.True(intervalDrained.Task.IsCompleted);
    }

    [Fact]
    public async Task IntervalWakeDrainsNetworkWaitBeforeReturning()
    {
        var intervalElapsed = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var networkDrained = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        var wait = TunnelHealthMonitorWait.WaitAsync(
            _ => intervalElapsed.Task,
            token => WaitUntilCancelledAsync(token, networkDrained), CancellationToken.None);
        intervalElapsed.SetResult(true);

        Assert.Equal(TunnelHealthWake.Interval, await wait);
        Assert.True(networkDrained.Task.IsCompleted);
    }

    [Fact]
    public async Task CancelledMonitorWaitCannotCancelOrPublishThroughReplacementWait()
    {
        using var previous = new DeyttConnect.Windows.Service.TunnelHealthMonitorLifetime();
        var previousIntervalDrained = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var previousNetworkDrained = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var stalePublished = false;
        var previousWait = TunnelHealthMonitorWait.WaitAsync(
            token => WaitForIntervalUntilCancelledAsync(token, previousIntervalDrained),
            token => WaitUntilCancelledAsync(token, previousNetworkDrained), previous.Token);
        var previousMonitor = PublishWhenReadyAsync(previousWait, () => stalePublished = true);

        previous.Stop();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => previousMonitor);
        Assert.False(stalePublished);
        Assert.True(previousIntervalDrained.Task.IsCompleted);
        Assert.True(previousNetworkDrained.Task.IsCompleted);

        using var replacement = new DeyttConnect.Windows.Service.TunnelHealthMonitorLifetime();
        var replacementTick = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var replacementNetworkDrained = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var replacementWait = TunnelHealthMonitorWait.WaitAsync(
            _ => replacementTick.Task,
            token => WaitUntilCancelledAsync(token, replacementNetworkDrained), replacement.Token);
        replacementTick.SetResult(true);

        Assert.Equal(TunnelHealthWake.Interval, await replacementWait);
        Assert.True(replacementNetworkDrained.Task.IsCompleted);
        Assert.False(replacement.Token.IsCancellationRequested);

        static async Task PublishWhenReadyAsync(Task<TunnelHealthWake?> wait, Action publish)
        {
            await wait;
            publish();
        }
    }

    private static async Task WaitUntilCancelledAsync(CancellationToken cancellationToken,
        TaskCompletionSource drained)
    {
        try
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
        }
        finally
        {
            drained.TrySetResult();
        }
    }

    private static async Task<bool> WaitForIntervalUntilCancelledAsync(CancellationToken cancellationToken,
        TaskCompletionSource drained)
    {
        try
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            return false;
        }
        finally
        {
            drained.TrySetResult();
        }
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    [InlineData(17)]
    public async Task ReadFrame_RejectsInvalidLengths(int length)
    {
        var frame = new byte[sizeof(int)];
        BitConverter.GetBytes(length).CopyTo(frame, 0);
        await Assert.ThrowsAsync<InvalidDataException>(() =>
            WindowsPipeProtocol.ReadFrameAsync(new MemoryStream(frame), maximumBytes: 16));
    }

    [Fact]
    public async Task Frame_RoundTripsExactPayload()
    {
        byte[] expected = [1, 2, 3, 4];
        using var stream = new MemoryStream();

        await WindowsPipeProtocol.WriteFrameAsync(stream, expected, maximumBytes: 16);
        stream.Position = 0;

        Assert.Equal(expected, await WindowsPipeProtocol.ReadFrameAsync(stream, maximumBytes: 16));
    }

    [Fact]
    public void Request_RejectsUnknownCommand()
    {
        Assert.Throws<JsonException>(() =>
            WindowsPipeProtocol.DeserializeRequest(Encoding.UTF8.GetBytes("{\"command\":\"unknown\"}")));
    }
}
