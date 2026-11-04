using System.Collections.Concurrent;
using System.Net;
using System.Text.Json.Nodes;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class ConnectionHostResolutionTests
{
    [Fact]
    public async Task ResolvesOutboundAndAwgHostsConcurrentlyAndMergesBothProfiles()
    {
        const string profile = """
            {"outbounds":[{"type":"vless","tag":"route:DE:VLESS","server":"proxy.example.test","server_port":443,"tls":{"enabled":true}}],
             "endpoints":[{"type":"awg","tag":"awg31:ZGU","peers":[{"address":"peer.example.test","port":51820}]}],
             "route":{"final":"route:DE:VLESS"}}
            """;
        var outboundStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var peerStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseOutbound = new TaskCompletionSource<IPAddress[]>(TaskCreationOptions.RunContinuationsAsynchronously);
        var releasePeer = new TaskCompletionSource<IPAddress[]>(TaskCreationOptions.RunContinuationsAsynchronously);

        Task<IPAddress[]> Resolve(string host, CancellationToken cancellationToken)
        {
            if (host == "proxy.example.test")
            {
                outboundStarted.TrySetResult();
                return releaseOutbound.Task.WaitAsync(cancellationToken);
            }

            if (host == "peer.example.test")
            {
                peerStarted.TrySetResult();
                return releasePeer.Task.WaitAsync(cancellationToken);
            }

            throw new InvalidOperationException($"Unexpected DNS lookup: {host}");
        }

        var operation = EngineController.ResolveConnectionHostsAsync(profile, resolveAwgPeers: true,
            Resolve, CancellationToken.None);
        try
        {
            await Task.WhenAll(outboundStarted.Task, peerStarted.Task).WaitAsync(TimeSpan.FromSeconds(2));
        }
        catch
        {
            releaseOutbound.TrySetResult([IPAddress.Parse("192.0.2.41")]);
            releasePeer.TrySetResult([IPAddress.Parse("198.51.100.78")]);
            await operation;
            throw;
        }

        releaseOutbound.SetResult([IPAddress.Parse("192.0.2.41")]);
        releasePeer.SetResult([IPAddress.Parse("198.51.100.78")]);
        var result = await operation;

        Assert.Empty(result.FailedOutboundTags);
        Assert.Empty(result.FailedEndpointTags);
        var root = JsonNode.Parse(result.Profile)!;
        Assert.Equal("192.0.2.41", root["outbounds"]![0]!["server"]!.GetValue<string>());
        Assert.Equal("proxy.example.test", root["outbounds"]![0]!["tls"]!["server_name"]!.GetValue<string>());
        Assert.Equal("198.51.100.78", root["endpoints"]![0]!["peers"]![0]!["address"]!.GetValue<string>());
        Assert.Equal("route:DE:VLESS", root["route"]!["final"]!.GetValue<string>());
    }

    [Fact]
    public async Task DoesNotResolveAwgPeersWhenTheSelectedRouteIsNotAwg()
    {
        const string profile = """
            {"outbounds":[{"type":"vless","tag":"route:DE:VLESS","server":"proxy.example.test","server_port":443}],
             "endpoints":[{"type":"awg","tag":"awg31:ZGU","peers":[{"address":"peer.example.test","port":51820}]}]}
            """;
        var lookups = new ConcurrentBag<string>();

        var result = await EngineController.ResolveConnectionHostsAsync(profile, resolveAwgPeers: false,
            (host, _) =>
            {
                lookups.Add(host);
                return Task.FromResult(new[] { IPAddress.Parse("192.0.2.41") });
            }, CancellationToken.None);

        Assert.Equal(["proxy.example.test"], lookups);
        Assert.Empty(result.FailedEndpointTags);
        var root = JsonNode.Parse(result.Profile)!;
        Assert.Equal("192.0.2.41", root["outbounds"]![0]!["server"]!.GetValue<string>());
        Assert.Equal("peer.example.test", root["endpoints"]![0]!["peers"]![0]!["address"]!.GetValue<string>());
    }

    [Fact]
    public async Task OutboundDnsFailureDoesNotWaitForAnUnresponsiveAwgPeer()
    {
        const string profile = """
            {"outbounds":[{"type":"vless","tag":"route:DE:VLESS","server":"proxy.example.test","server_port":443}],
             "endpoints":[{"type":"awg","tag":"awg31:ZGU","peers":[{"address":"peer.example.test","port":51820}]}]}
            """;
        using var cancellation = new CancellationTokenSource();
        var peerStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var unresponsivePeer = new TaskCompletionSource<IPAddress[]>(TaskCreationOptions.RunContinuationsAsynchronously);

        Task<IPAddress[]> Resolve(string host, CancellationToken cancellationToken)
        {
            if (host == "proxy.example.test")
                return Task.FromResult(Array.Empty<IPAddress>());
            if (host == "peer.example.test")
            {
                peerStarted.TrySetResult();
                return unresponsivePeer.Task.WaitAsync(cancellationToken);
            }
            throw new InvalidOperationException($"Unexpected DNS lookup: {host}");
        }

        var operation = EngineController.ResolveConnectionHostsAsync(profile, resolveAwgPeers: true,
            Resolve, cancellation.Token);
        try
        {
            await peerStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));
            var result = await operation.WaitAsync(TimeSpan.FromSeconds(4));
            Assert.Contains("route:DE:VLESS", result.FailedOutboundTags);
        }
        finally
        {
            cancellation.Cancel();
            try { await operation; }
            catch (OperationCanceledException) { }
        }
    }
}
