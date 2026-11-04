using System.Net;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class ConnectDnsPrerequisiteResolverTests
{
    private sealed record AwgResolution(bool IsReady);

    [Fact]
    public async Task ResolvesAwgAndCanariesConcurrently()
    {
        var awgStarted = NewSignal();
        var canaryStarted = NewSignal();

        async Task<AwgResolution> ResolveAwg(CancellationToken cancellationToken)
        {
            awgStarted.TrySetResult();
            await canaryStarted.Task.WaitAsync(cancellationToken);
            return new AwgResolution(true);
        }

        async Task<IReadOnlyDictionary<string, IPAddress[]>> ResolveCanaries(
            CancellationToken cancellationToken)
        {
            canaryStarted.TrySetResult();
            await awgStarted.Task.WaitAsync(cancellationToken);
            return new Dictionary<string, IPAddress[]> { ["canary.invalid"] = [IPAddress.Loopback] };
        }

        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var result = await ConnectDnsPrerequisiteResolver.ResolveAsync(
            ResolveAwg, ResolveCanaries, awg => awg.IsReady, canaries => canaries.Count > 0, timeout.Token);

        Assert.True(awgStarted.Task.IsCompletedSuccessfully);
        Assert.True(canaryStarted.Task.IsCompletedSuccessfully);
        Assert.True(result.First?.IsReady == true);
        Assert.NotNull(result.Second);
    }

    [Fact]
    public async Task AwgFailureCancelsCanaryResolutionAndReturnsWithoutWaitingForItsDeadline()
    {
        var canaryStarted = NewSignal();
        var canaryCancelled = NewSignal();

        async Task<IReadOnlyDictionary<string, IPAddress[]>> ResolveCanaries(
            CancellationToken cancellationToken)
        {
            canaryStarted.TrySetResult();
            try
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                return new Dictionary<string, IPAddress[]>();
            }
            catch (OperationCanceledException)
            {
                canaryCancelled.TrySetResult();
                throw;
            }
        }

        var result = await ConnectDnsPrerequisiteResolver.ResolveAsync(
                _ => Task.FromResult(new AwgResolution(false)), ResolveCanaries,
                awg => awg.IsReady, canaries => canaries.Count > 0, CancellationToken.None)
            .WaitAsync(TimeSpan.FromSeconds(2));

        Assert.False(result.First?.IsReady);
        Assert.Null(result.Second);
        Assert.True(canaryStarted.Task.IsCompletedSuccessfully);
        Assert.True(canaryCancelled.Task.IsCompletedSuccessfully);
    }

    [Fact]
    public async Task CanaryFailureCancelsAwgResolutionAndReturnsImmediately()
    {
        var awgCancelled = NewSignal();

        async Task<AwgResolution> ResolveAwg(CancellationToken cancellationToken)
        {
            try
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                return new AwgResolution(true);
            }
            catch (OperationCanceledException)
            {
                awgCancelled.TrySetResult();
                throw;
            }
        }

        var result = await ConnectDnsPrerequisiteResolver.ResolveAsync(
                ResolveAwg,
                _ => Task.FromResult<IReadOnlyDictionary<string, IPAddress[]>>(
                    new Dictionary<string, IPAddress[]>()),
                awg => awg.IsReady, canaries => canaries.Count > 0, CancellationToken.None)
            .WaitAsync(TimeSpan.FromSeconds(2));

        Assert.Null(result.First);
        Assert.Empty(result.Second!);
        Assert.True(awgCancelled.Task.IsCompletedSuccessfully);
    }

    [Fact]
    public async Task CallerCancellationCancelsBothPrerequisitesAndPropagates()
    {
        var awgCancelled = NewSignal();
        var canaryCancelled = NewSignal();

        async Task<AwgResolution> ResolveAwg(CancellationToken cancellationToken)
        {
            try
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                return new AwgResolution(true);
            }
            catch (OperationCanceledException)
            {
                awgCancelled.TrySetResult();
                throw;
            }
        }

        async Task<IReadOnlyDictionary<string, IPAddress[]>> ResolveCanaries(CancellationToken cancellationToken)
        {
            try
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                return new Dictionary<string, IPAddress[]>();
            }
            catch (OperationCanceledException)
            {
                canaryCancelled.TrySetResult();
                throw;
            }
        }

        using var cancellation = new CancellationTokenSource();
        var pending = ConnectDnsPrerequisiteResolver.ResolveAsync(
            ResolveAwg, ResolveCanaries, awg => awg.IsReady, canaries => canaries.Count > 0,
            cancellation.Token);
        cancellation.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => pending);
        await Task.WhenAll(awgCancelled.Task, canaryCancelled.Task).WaitAsync(TimeSpan.FromSeconds(2));
        Assert.True(awgCancelled.Task.IsCompletedSuccessfully);
        Assert.True(canaryCancelled.Task.IsCompletedSuccessfully);
    }

    private static TaskCompletionSource NewSignal() =>
        new(TaskCreationOptions.RunContinuationsAsynchronously);
}
