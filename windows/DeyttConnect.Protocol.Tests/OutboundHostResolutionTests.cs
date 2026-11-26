using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class OutboundHostResolutionTests
{
    [Fact]
    public async Task HostnamesAreCachedPinnedAndPreserveTlsAndTransportHosts()
    {
        const string profile = """
            {"outbounds":[
              {"type":"vless","tag":"ws-existing","server":"Proxy.Example.Test","server_port":443,
               "tls":{"enabled":true},
               "transport":{"type":"ws","path":"/edge","headers":{"host":"ws.example.test","X-Trace":"keep"}}},
              {"type":"trojan","tag":"http-existing","server":"proxy.example.test","server_port":443,
               "tls":{"enabled":true,"server_name":"certificate.example.test"},
               "transport":{"type":"httpupgrade","host":"upgrade.example.test","path":"/connect"}},
              {"type":"vless","tag":"ws-default","server":"PROXY.example.test","server_port":443,
               "tls":{"enabled":true},
               "transport":{"type":"ws","headers":{"X-Trace":"keep"}}},
              {"type":"vless","tag":"http-default","server":"proxy.example.test","server_port":443,
               "tls":{"enabled":true},
               "transport":{"type":"httpupgrade","path":"/connect"}},
              {"type":"vless","tag":"http-transport-default","server":"proxy.example.test","server_port":443,
               "transport":{"type":"http","path":"/connect"}},
              {"type":"vless","tag":"http-transport-existing","server":"proxy.example.test","server_port":443,
               "transport":{"type":"http","host":["front.example.test"],"path":"/connect"}}
            ]}
            """;
        var calls = new ConcurrentDictionary<string, int>(StringComparer.OrdinalIgnoreCase);

        var result = await EngineController.ResolveOutboundServerHostsAsync(profile, (host, _) =>
        {
            calls.AddOrUpdate(host, 1, (_, count) => count + 1);
            return Task.FromResult(new[] { IPAddress.IPv6Loopback, IPAddress.Loopback });
        }, CancellationToken.None);

        var outbounds = JsonNode.Parse(result.Profile)!["outbounds"]!.AsArray();
        Assert.Empty(result.FailedOutboundTags);
        Assert.Single(calls);
        Assert.Equal(1, calls["Proxy.Example.Test"]);
        Assert.All(outbounds, outbound => Assert.Equal("127.0.0.1",
            outbound!["server"]!.GetValue<string>()));
        Assert.Equal("Proxy.Example.Test", outbounds[0]!["tls"]!["server_name"]!.GetValue<string>());
        Assert.Equal("certificate.example.test", outbounds[1]!["tls"]!["server_name"]!.GetValue<string>());
        Assert.Equal("ws.example.test", outbounds[0]!["transport"]!["headers"]!["host"]!.GetValue<string>());
        Assert.Equal(1, outbounds[0]!["transport"]!["headers"]!.AsObject()
            .Count(header => header.Key.Equals("Host", StringComparison.OrdinalIgnoreCase)));
        Assert.Equal("keep", outbounds[0]!["transport"]!["headers"]!["X-Trace"]!.GetValue<string>());
        Assert.Equal("PROXY.example.test", outbounds[2]!["transport"]!["headers"]!["Host"]!.GetValue<string>());
        Assert.Equal("keep", outbounds[2]!["transport"]!["headers"]!["X-Trace"]!.GetValue<string>());
        Assert.Equal("upgrade.example.test", outbounds[1]!["transport"]!["host"]!.GetValue<string>());
        Assert.Equal("proxy.example.test", outbounds[3]!["transport"]!["host"]!.GetValue<string>());
        Assert.Equal("/connect", outbounds[3]!["transport"]!["path"]!.GetValue<string>());
        Assert.True(JsonNode.DeepEquals(new JsonArray("proxy.example.test"),
            outbounds[4]!["transport"]!["host"]));
        Assert.True(JsonNode.DeepEquals(new JsonArray("front.example.test"),
            outbounds[5]!["transport"]!["host"]));
    }

    [Fact]
    public async Task NumericServerAddressesRemainUnchangedAndAreNotResolved()
    {
        const string profile = """
            {"outbounds":[
              {"type":"vless","tag":"ipv4","server":"192.0.2.40","server_port":443},
              {"type":"vless","tag":"ipv6","server":"[2001:db8::40]","server_port":443},
              {"type":"direct","tag":"direct"}
            ]}
            """;
        var input = JsonNode.Parse(profile)!;

        var result = await EngineController.ResolveOutboundServerHostsAsync(profile,
            (_, _) => throw new InvalidOperationException("Numeric addresses must not use DNS."),
            CancellationToken.None);

        Assert.Empty(result.FailedOutboundTags);
        Assert.True(JsonNode.DeepEquals(input, JsonNode.Parse(result.Profile)));

        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            EngineController.ResolveOutboundServerHostsAsync(profile,
                (_, _) => throw new InvalidOperationException("Cancelled numeric profile must not use DNS."),
                cancelled.Token));
    }

    [Fact]
    public async Task OnlyUnresolvableOutboundTagsAreReported()
    {
        const string profile = """
            {"outbounds":[
              {"type":"vless","tag":"route-good","server":"good.example.test","server_port":443},
              {"type":"trojan","tag":"route-bad","server":"missing.example.test","server_port":443}
            ]}
            """;

        var result = await EngineController.ResolveOutboundServerHostsAsync(profile, (host, _) =>
        {
            if (host == "missing.example.test")
                throw new SocketException((int)SocketError.HostNotFound);
            return Task.FromResult(new[] { IPAddress.Loopback });
        }, CancellationToken.None);

        Assert.Equal(new HashSet<string>(["route-bad"], StringComparer.Ordinal), result.FailedOutboundTags);
        var outbounds = JsonNode.Parse(result.Profile)!["outbounds"]!.AsArray();
        Assert.Equal("127.0.0.1", outbounds[0]!["server"]!.GetValue<string>());
        Assert.Equal("missing.example.test", outbounds[1]!["server"]!.GetValue<string>());
    }

    [Fact]
    public async Task ResolutionDeadlineMarksHostFailedAndCallerCancellationPropagates()
    {
        const string profile = """
            {"outbounds":[{"type":"vless","tag":"slow-route","server":"slow.example.test","server_port":443}]}
            """;
        var timedOut = await EngineController.ResolveOutboundServerHostsAsync(profile,
            async (_, cancellationToken) =>
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                return [];
            }, CancellationToken.None, TimeSpan.FromMilliseconds(30));
        Assert.Contains("slow-route", timedOut.FailedOutboundTags);

        using var callerCancellation = new CancellationTokenSource();
        var operation = EngineController.ResolveOutboundServerHostsAsync(profile,
            async (_, cancellationToken) =>
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
                return [];
            }, callerCancellation.Token, TimeSpan.FromSeconds(5));
        callerCancellation.CancelAfter(30);

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => operation);
    }
}
