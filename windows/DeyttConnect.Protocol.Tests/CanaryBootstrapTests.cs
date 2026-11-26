using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class CanaryBootstrapTests
{
    [Fact]
    public async Task FirstSuccessfulTunnelCanaryDoesNotWaitForSlowPeer()
    {
        var slowStarted = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var slowCancelled = false;

        async Task<long?> Slow(CancellationToken token)
        {
            slowStarted.TrySetResult(true);
            try
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, token);
                return null;
            }
            catch (OperationCanceledException)
            {
                slowCancelled = true;
                throw;
            }
        }

        async Task<long?> Fast(CancellationToken token)
        {
            await slowStarted.Task.WaitAsync(token);
            return 45;
        }

        var latency = await EngineController.FirstSuccessfulCanaryAsync([Slow, Fast], CancellationToken.None);

        Assert.Equal(45L, latency);
        Assert.True(slowCancelled);
    }

    [Fact]
    public async Task ColdDnsFailureRetriesUntilOneCanaryRetainsBothAddressFamilies()
    {
        var attempts = new ConcurrentDictionary<string, int>();
        var addresses = new[] { IPAddress.Parse("192.0.2.5"), IPAddress.Parse("2001:db8::5") };
        var result = await EngineController.ResolveCanaryAddressesAsync((host, _) =>
        {
            if (host == "www.gstatic.com")
                throw new SocketException((int)SocketError.HostNotFound);
            if (attempts.AddOrUpdate(host, 1, (_, count) => count + 1) == 1)
                throw new SocketException((int)SocketError.TryAgain);
            return Task.FromResult(addresses);
        }, CancellationToken.None);

        Assert.Single(result);
        Assert.Equal(addresses, result["www.cloudflare.com"]);
        Assert.Equal(2, attempts["www.cloudflare.com"]);
    }

    [Fact]
    public async Task MissingCanaryDoesNotPreventAnotherHostFromPassing()
    {
        var result = await EngineController.ResolveCanaryAddressesAsync((host, _) =>
            host == "www.cloudflare.com" ? throw new SocketException((int)SocketError.HostNotFound) :
                Task.FromResult(new[] { IPAddress.Parse("192.0.2.5") }), CancellationToken.None);

        Assert.Single(result);
        Assert.True(result.ContainsKey("www.gstatic.com"));
    }

    [Fact]
    public async Task SuccessfulCanaryDoesNotWaitForAnotherSlowDnsLookup()
    {
        var slowLookupStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseSlowLookup = new TaskCompletionSource<IPAddress[]>(TaskCreationOptions.RunContinuationsAsynchronously);
        async Task<IPAddress[]> Resolve(string host, CancellationToken token)
        {
            if (host == "www.cloudflare.com")
                return [IPAddress.Parse("192.0.2.5")];
            slowLookupStarted.TrySetResult();
            return await releaseSlowLookup.Task.WaitAsync(token);
        }

        var bootstrap = EngineController.ResolveCanaryAddressesAsync(Resolve, CancellationToken.None);
        await slowLookupStarted.Task.WaitAsync(TimeSpan.FromSeconds(1));
        var result = await bootstrap.WaitAsync(TimeSpan.FromSeconds(1));

        Assert.Single(result);
        Assert.Contains("www.cloudflare.com", result.Keys);
        releaseSlowLookup.SetResult([IPAddress.Parse("2001:db8::5")]);
        Assert.True(SpinWait.SpinUntil(() => result.Count == 2, TimeSpan.FromSeconds(1)));
    }

    [Fact]
    public async Task BootstrapLookupAfterTunNeverFallsBackToSystemDns()
    {
        var expected = new[] { IPAddress.Parse("192.0.2.5") };
        IReadOnlyDictionary<string, IPAddress[]> bootstrap = new Dictionary<string, IPAddress[]>
        {
            ["www.cloudflare.com"] = expected,
        };
        Task<IPAddress[]> ForbiddenResolver(string _, CancellationToken token) =>
            throw new InvalidOperationException("System DNS must not run after TUN bootstrap.");

        Assert.Same(expected, await EngineController.ResolveCanaryDestinationAsync("www.cloudflare.com",
            bootstrap, ForbiddenResolver, CancellationToken.None));
        Assert.Empty(await EngineController.ResolveCanaryDestinationAsync("www.gstatic.com",
            bootstrap, ForbiddenResolver, CancellationToken.None));
    }

    [Fact]
    public async Task BootstrapDeadlineBoundsUncooperativeResolver()
    {
        var result = await EngineController.ResolveCanaryAddressesAsync((_, _) =>
            new TaskCompletionSource<IPAddress[]>().Task, CancellationToken.None, TimeSpan.FromMilliseconds(25));

        Assert.Empty(result);
    }

    [Fact]
    public async Task CallerCancellationRemainsCancellation()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => EngineController.ResolveCanaryAddressesAsync(
            (_, token) => Task.FromCanceled<IPAddress[]>(token), cancellation.Token));
    }
}
