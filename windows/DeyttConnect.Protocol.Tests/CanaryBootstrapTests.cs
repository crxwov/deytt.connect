using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class CanaryBootstrapTests
{
    [Fact]
    public async Task ColdDnsFailureRetriesAndRetainsBothAddressFamilies()
    {
        var attempts = new ConcurrentDictionary<string, int>();
        var addresses = new[] { IPAddress.Parse("192.0.2.5"), IPAddress.Parse("2001:db8::5") };
        var result = await EngineController.ResolveCanaryAddressesAsync((host, _) =>
        {
            if (attempts.AddOrUpdate(host, 1, (_, count) => count + 1) == 1)
                throw new SocketException((int)SocketError.TryAgain);
            return Task.FromResult(addresses);
        }, CancellationToken.None);

        Assert.Equal(2, result.Count);
        Assert.All(result.Values, value => Assert.Equal(addresses, value));
        Assert.All(attempts.Values, count => Assert.Equal(2, count));
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
