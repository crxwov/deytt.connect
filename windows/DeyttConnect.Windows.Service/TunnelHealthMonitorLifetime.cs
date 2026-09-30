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
