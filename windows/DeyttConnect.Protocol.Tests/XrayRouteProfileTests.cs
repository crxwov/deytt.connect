using System.Net;
using System.Text.Json.Nodes;
using DeyttConnect.Protocol;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class XrayRouteProfileTests
{
    private const string Profile = """
        {
          "outbounds": [
            {"tag":"автоподбор","type":"urltest","outbounds":["route:DE:VLESS"]},
            {"tag":"route:DE:VLESS","type":"vless"},
            {"tag":"route:RU:VLESS","type":"vless"},
            {"tag":"route:IT:VLESS","type":"vless"}
          ],
          "deytt":{"xray_routes":{"version":1,"routes":{
             "route:DE:VLESS":{"protocol":"vless","transport":"xhttp","host":"ru.deytt.space",
                "port":18465,"sni":"de.deytt.space","uuid":"12345678-1234-1234-1234-123456789abc",
                "path":"/stream","mode":"auto","alpn":["h2"],"fingerprint":"firefox"},
             "route:RU:VLESS":{"protocol":"vless","transport":"ws","host":"ru.deytt.space",
                "port":443,"sni":"auto.deytt.space","uuid":"12345678-1234-1234-1234-123456789abc",
                "path":"/stream","alpn":["http/1.1"],"fingerprint":"firefox"}
          }}}
        }
        """;

    [Fact]
    public async Task KeepsRouteTagsWhenTheSubscriptionChangesTransport()
    {
        var runtime = await XrayRouteProfile.PrepareAsync(Profile,
            (_, _) => Task.FromResult(new[] { IPAddress.Parse("192.0.2.2") }), CancellationToken.None);
        Assert.NotNull(runtime);

        var profile = JsonNode.Parse(runtime.Profile)!["outbounds"]!.AsArray();
        Assert.Equal("route:DE:VLESS", Assert.Single(profile[0]!["outbounds"]!.AsArray())!.GetValue<string>());
        Assert.Equal("socks", profile[1]!["type"]!.GetValue<string>());
        Assert.Equal("vless", profile[3]!["type"]!.GetValue<string>());
        Assert.NotEqual(runtime.Ports[0], runtime.Ports[1]);

        var xray = JsonNode.Parse(runtime.Xray)!;
        Assert.Equal("xhttp", xray["outbounds"]![0]!["streamSettings"]!["network"]!.GetValue<string>());
        Assert.Equal("ws", xray["outbounds"]![1]!["streamSettings"]!["network"]!.GetValue<string>());
        Assert.Equal(2, xray["routing"]!["rules"]!.AsArray().Count);
        Assert.DoesNotContain("allowInsecure", runtime.Xray);
    }

    [Fact]
    public async Task RejectsThirdPartySniAndAcceptsLegacyProfiles()
    {
        Assert.Throws<InvalidDataException>(() =>
            XrayRouteProfile.Validate(Profile.Replace("de.deytt.space", "untrusted.example")));
        var runtime = await XrayRouteProfile.PrepareAsync("{}",
            (_, _) => throw new InvalidOperationException("Legacy profiles must not start Xray."),
            CancellationToken.None);
        Assert.Null(runtime);
    }
}

