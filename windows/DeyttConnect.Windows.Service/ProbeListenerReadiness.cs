using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace DeyttConnect.Windows.Service;

internal static class ProbeListenerReadiness
{
    internal static async Task WaitAsync(IReadOnlyList<int> ports, Func<bool> engineExited,
        CancellationToken cancellationToken, TimeSpan? timeout = null)
    {
        var remaining = ports.ToHashSet();
        var started = Stopwatch.GetTimestamp();
        var deadline = timeout ?? TimeSpan.FromSeconds(10);
        while (remaining.Count > 0)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (engineExited())
                throw new InvalidOperationException("VPN engine exited before diagnostic listeners were ready.");
            if (Stopwatch.GetElapsedTime(started) >= deadline)
                throw new TimeoutException("Diagnostic proxy listeners did not become ready.");

            foreach (var port in remaining.ToArray())
            {
                using var attempt = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                attempt.CancelAfter(TimeSpan.FromMilliseconds(250));
                using var socket = new TcpClient(AddressFamily.InterNetwork);
                try
                {
                    await socket.ConnectAsync(IPAddress.Loopback, port, attempt.Token).ConfigureAwait(false);
                    remaining.Remove(port);
                }
                catch (SocketException) { }
                catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { }
            }
            if (remaining.Count > 0)
                await Task.Delay(100, cancellationToken).ConfigureAwait(false);
        }
    }
}
