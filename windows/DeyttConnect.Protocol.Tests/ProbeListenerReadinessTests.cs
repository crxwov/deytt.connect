using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class ProbeListenerReadinessTests
{
    [Fact]
    public async Task WaitsForLoopbackListenerThatStartsAfter350Milliseconds()
    {
        var port = ReserveLoopbackPort();
        using var listener = new TcpListener(IPAddress.Loopback, port);
        var startListener = Task.Run(async () =>
        {
            await Task.Delay(TimeSpan.FromMilliseconds(500));
            listener.Start();
        });
        var startedAt = Stopwatch.GetTimestamp();

        try
        {
            await ProbeListenerReadiness.WaitAsync(
                [port],
                engineExited: () => false,
                cancellationToken: CancellationToken.None,
                timeout: TimeSpan.FromSeconds(3));

            Assert.True(Stopwatch.GetElapsedTime(startedAt) >= TimeSpan.FromMilliseconds(400));
            await startListener;
            Assert.True(listener.Server.IsBound);
        }
        finally
        {
            try
            {
                await startListener;
            }
            finally
            {
                listener.Stop();
            }
        }
    }

    [Fact]
    public async Task PropagatesCallerCancellationWhileWaiting()
    {
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(100));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => ProbeListenerReadiness.WaitAsync(
            [ReserveLoopbackPort()],
            engineExited: () => false,
            cancellationToken: cancellation.Token,
            timeout: TimeSpan.FromSeconds(3)));
    }

    [Fact]
    public async Task StopsWaitingWhenEngineExits()
    {
        var exitChecks = 0;
        var startedAt = Stopwatch.GetTimestamp();

        await Assert.ThrowsAsync<InvalidOperationException>(() => ProbeListenerReadiness.WaitAsync(
            [ReserveLoopbackPort()],
            engineExited: () => Interlocked.Increment(ref exitChecks) >= 2,
            cancellationToken: CancellationToken.None,
            timeout: TimeSpan.FromSeconds(3)));

        Assert.True(exitChecks >= 2);
        Assert.True(Stopwatch.GetElapsedTime(startedAt) < TimeSpan.FromSeconds(2));
    }

    [Fact]
    public async Task StopsWaitingAtDeadline()
    {
        var startedAt = Stopwatch.GetTimestamp();
        var wait = ProbeListenerReadiness.WaitAsync(
            [ReserveLoopbackPort()],
            engineExited: () => false,
            cancellationToken: CancellationToken.None,
            timeout: TimeSpan.FromMilliseconds(250));
        using var watchdog = new CancellationTokenSource();
        var finished = await Task.WhenAny(wait, Task.Delay(TimeSpan.FromSeconds(2), watchdog.Token));
        watchdog.Cancel();

        Assert.Same(wait, finished);
        await Assert.ThrowsAsync<TimeoutException>(() => wait);
        Assert.True(Stopwatch.GetElapsedTime(startedAt) < TimeSpan.FromSeconds(1));
    }

    private static int ReserveLoopbackPort()
    {
        using var reservation = new TcpListener(IPAddress.Loopback, 0);
        reservation.Start();
        return ((IPEndPoint)reservation.LocalEndpoint).Port;
    }
}
