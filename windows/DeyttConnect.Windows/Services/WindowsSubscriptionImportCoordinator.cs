using DeyttConnect.Protocol;

namespace DeyttConnect.Windows.Services;

/// <summary>Outcome of preparing and persisting a subscription import.</summary>
public sealed record WindowsSubscriptionImportCommitState(
    WindowsTunnelSnapshot Snapshot,
    bool ServiceAvailable);

/// <summary>
/// Coordinates the safety gate before replacing a saved subscription. Tunnel access and persistence
/// are supplied by the host so the ordering policy can be verified without Windows services or DPAPI.
/// </summary>
public sealed class WindowsSubscriptionImportCoordinator(
    Func<CancellationToken, Task<WindowsTunnelSnapshot>> getStatusAsync,
    Func<CancellationToken, Task<WindowsTunnelSnapshot>> disconnectAsync,
    Func<Exception, bool> isTransportError)
{
    public async Task<WindowsSubscriptionImportCommitState> CommitAsync(
        WindowsTunnelSnapshot lastKnownSnapshot,
        Func<Task<bool>> confirmDisconnectAsync,
        Func<CancellationToken, Task> persistAsync,
        Action<WindowsSubscriptionImportCommitState>? stateChanged,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(lastKnownSnapshot);
        ArgumentNullException.ThrowIfNull(confirmDisconnectAsync);
        ArgumentNullException.ThrowIfNull(persistAsync);
        ArgumentNullException.ThrowIfNull(getStatusAsync);
        ArgumentNullException.ThrowIfNull(disconnectAsync);
        ArgumentNullException.ThrowIfNull(isTransportError);

        var current = new WindowsSubscriptionImportCommitState(lastKnownSnapshot, ServiceAvailable: true);
        try
        {
            var verified = await getStatusAsync(cancellationToken);
            current = new WindowsSubscriptionImportCommitState(verified, ServiceAvailable: true);
            stateChanged?.Invoke(current);

            if (!IsInactive(verified.State))
            {
                if (!await confirmDisconnectAsync())
                    throw new InvalidOperationException(
                        "The subscription import was canceled because the VPN remains active.");

                var disconnected = await disconnectAsync(cancellationToken);
                current = new WindowsSubscriptionImportCommitState(disconnected, ServiceAvailable: true);
                stateChanged?.Invoke(current);
                if (!IsInactive(disconnected.State))
                {
                    var confirmed = await getStatusAsync(cancellationToken);
                    current = new WindowsSubscriptionImportCommitState(confirmed, ServiceAvailable: true);
                    stateChanged?.Invoke(current);
                }

                if (!IsInactive(current.Snapshot.State))
                    throw new IOException("The VPN service did not confirm that the tunnel stopped.");
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception error) when (isTransportError(error))
        {
            current = current with { ServiceAvailable = false };
            stateChanged?.Invoke(current);
            if (!IsInactive(current.Snapshot.State))
                throw new IOException("Could not verify or stop the active VPN tunnel before importing.", error);
        }

        cancellationToken.ThrowIfCancellationRequested();
        await persistAsync(cancellationToken);
        return current;
    }

    private static bool IsInactive(string state) =>
        state is "disconnected" or "idle" or "error";
}
