using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsSubscriptionImportCoordinatorTests
{
    [Fact]
    public async Task InactiveLastSnapshot_AllowsSaveWhenServiceIsUnavailable()
    {
        var saves = 0;
        WindowsSubscriptionImportCommitState? changed = null;
        var coordinator = Create(
            _ => Task.FromException<WindowsTunnelSnapshot>(new IOException("service unavailable")),
            _ => throw new InvalidOperationException("disconnect should not run"));

        var result = await coordinator.CommitAsync(
            Snapshot("disconnected"),
            () => Task.FromResult(false),
            _ => { saves++; return Task.CompletedTask; },
            state => changed = state,
            CancellationToken.None);

        Assert.Equal(1, saves);
        Assert.NotNull(changed);
        Assert.False(changed.ServiceAvailable);
        Assert.False(result.ServiceAvailable);
        Assert.Equal("disconnected", result.Snapshot.State);
    }

    [Fact]
    public async Task ActiveLastSnapshot_BlocksSaveWhenServiceIsUnavailable()
    {
        var saves = 0;
        var coordinator = Create(
            _ => Task.FromException<WindowsTunnelSnapshot>(new IOException("service unavailable")),
            _ => throw new InvalidOperationException("disconnect should not run"));

        var error = await Assert.ThrowsAsync<IOException>(() => coordinator.CommitAsync(
            Snapshot("connected"),
            () => Task.FromResult(true),
            _ => { saves++; return Task.CompletedTask; },
            null,
            CancellationToken.None));

        Assert.Contains("Could not verify", error.Message);
        Assert.Equal(0, saves);
    }

    [Fact]
    public async Task DecliningActiveTunnelDisconnect_LeavesSubscriptionUnchanged()
    {
        var saves = 0;
        var coordinator = Create(_ => Task.FromResult(Snapshot("connected")),
            _ => throw new InvalidOperationException("disconnect should not run"));

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() => coordinator.CommitAsync(
            Snapshot("disconnected"),
            () => Task.FromResult(false),
            _ => { saves++; return Task.CompletedTask; },
            null,
            CancellationToken.None));

        Assert.Contains("canceled", error.Message);
        Assert.Equal(0, saves);
    }

    [Fact]
    public async Task CancellationDuringStatusCheck_LeavesSubscriptionUnchanged()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        var saves = 0;
        var coordinator = Create(
            token => Task.FromCanceled<WindowsTunnelSnapshot>(token),
            _ => throw new InvalidOperationException("disconnect should not run"));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => coordinator.CommitAsync(
            Snapshot("disconnected"),
            () => Task.FromResult(false),
            _ => { saves++; return Task.CompletedTask; },
            null,
            cancellation.Token));

        Assert.Equal(0, saves);
    }

    [Fact]
    public async Task ActiveTunnelIsDisconnectedAndVerifiedBeforeSave()
    {
        var calls = new List<string>();
        var statusCalls = 0;
        var coordinator = Create(
            _ =>
            {
                calls.Add("status");
                statusCalls++;
                return Task.FromResult(Snapshot(statusCalls == 1 ? "connected" : "disconnected"));
            },
            _ =>
            {
                calls.Add("disconnect");
                return Task.FromResult(Snapshot("stopping"));
            });

        await coordinator.CommitAsync(
            Snapshot("disconnected"),
            () => { calls.Add("confirm"); return Task.FromResult(true); },
            _ => { calls.Add("persist"); return Task.CompletedTask; },
            null,
            CancellationToken.None);

        Assert.Equal(["status", "confirm", "disconnect", "status", "persist"], calls);
    }

    [Fact]
    public async Task PersistenceFailureIsNotSwallowed()
    {
        var coordinator = Create(_ => Task.FromResult(Snapshot("idle")),
            _ => throw new InvalidOperationException("disconnect should not run"));

        var error = await Assert.ThrowsAsync<UnauthorizedAccessException>(() => coordinator.CommitAsync(
            Snapshot("disconnected"),
            () => Task.FromResult(false),
            _ => Task.FromException(new UnauthorizedAccessException("storage denied")),
            null,
            CancellationToken.None));

        Assert.Equal("storage denied", error.Message);
    }

    private static WindowsSubscriptionImportCoordinator Create(
        Func<CancellationToken, Task<WindowsTunnelSnapshot>> getStatus,
        Func<CancellationToken, Task<WindowsTunnelSnapshot>> disconnect) =>
        new(getStatus, disconnect, error => error is IOException or TimeoutException or
            UnauthorizedAccessException or OperationCanceledException);

    private static WindowsTunnelSnapshot Snapshot(string state) => new(state, state);
}
