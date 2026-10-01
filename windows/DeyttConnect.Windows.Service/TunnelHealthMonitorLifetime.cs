namespace DeyttConnect.Windows.Service;

internal sealed class TunnelHealthMonitorLifetime : IDisposable
{
    private readonly CancellationTokenSource _cancellation = new();

    public CancellationToken Token => _cancellation.Token;

    public void Stop()
    {
        if (!_cancellation.IsCancellationRequested)
            _cancellation.Cancel();
    }

    public void Dispose()
    {
        Stop();
        _cancellation.Dispose();
    }
}

internal enum TunnelHealthWake
{
    Interval,
    NetworkChanged,
}

internal static class TunnelHealthMonitorWait
{
    public static async Task<TunnelHealthWake?> WaitAsync(
        Func<CancellationToken, Task<bool>> waitForInterval,
        Func<CancellationToken, Task> waitForNetworkChange,
        CancellationToken cancellationToken)
    {
        using var wait = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var interval = waitForInterval(wait.Token);
        var network = waitForNetworkChange(wait.Token);
        var winner = await Task.WhenAny(interval, network).ConfigureAwait(false);
        wait.Cancel();

        try
        {
            await Task.WhenAll(interval, network).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            // The losing wait is expected to end when this iteration is canceled.
        }

        cancellationToken.ThrowIfCancellationRequested();
        if (winner == interval)
            return await interval.ConfigureAwait(false) ? TunnelHealthWake.Interval : null;

        await network.ConfigureAwait(false);
        return TunnelHealthWake.NetworkChanged;
    }
}
