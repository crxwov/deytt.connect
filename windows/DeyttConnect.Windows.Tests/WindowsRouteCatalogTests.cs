using System.Text.Json;
using System.Text.Json.Nodes;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsRouteCatalogTests
{
    [Fact]
    public void PartialRouteCatalogLoadsLikeAndroid()
    {
        const string source = """
            {
              "inbounds": [{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"]}],
              "outbounds": [
                {"type":"urltest","tag":"🇪🇺 автоподбор","outbounds":["route:DE:VLESS"]},
                {"type":"vless","tag":"route:DE:VLESS"},
                {"type":"vless","tag":"route:IT:VLESS"},
                {"type":"direct","tag":"direct"}
              ],
              "route": {"final":"🇪🇺 автоподбор","rules":[{"protocol":"dns","action":"hijack-dns"}]}
            }
            """;

        using var profile = JsonDocument.Parse(source);
        var routes = WindowsRouteCatalog.Parse(profile.RootElement);

        Assert.Equal(new[] { "auto", "route:DE:VLESS", "route:IT:VLESS" }, routes.Select(route => route.Id));
        var italy = Assert.Single(routes, route => route.CountryCode == "IT");
        Assert.Equal("италия", italy.CountryName);
        Assert.Equal("🇮🇹", italy.Flag);
    }

    [Fact]
    public void DefaultRouteMayTargetANetworkEndpoint()
    {
        var outbounds = new JsonArray
        {
            new JsonObject { ["type"] = "urltest", ["tag"] = "🇪🇺 автоподбор" },
            new JsonObject { ["type"] = "selector", ["tag"] = "route:RU-DE:CHAIN" },
        };
        foreach (var country in new[] { "NL", "RU", "DE", "FI", "IT" })
        foreach (var protocol in new[] { "VLESS", "TROJAN", "HYSTERIA2" })
            outbounds.Add(new JsonObject { ["type"] = "selector", ["tag"] = $"route:{country}:{protocol}" });

        var source = new JsonObject
        {
            ["inbounds"] = new JsonArray(new JsonObject
            {
                ["type"] = "tun",
                ["tag"] = "tun-in",
                ["address"] = new JsonArray("172.19.0.1/30"),
            }),
            ["outbounds"] = outbounds,
            ["endpoints"] = new JsonArray(new JsonObject { ["type"] = "wireguard", ["tag"] = "exit-endpoint" }),
            ["route"] = new JsonObject
            {
                ["final"] = "exit-endpoint",
                ["rules"] = new JsonArray(new JsonObject { ["protocol"] = "dns", ["action"] = "hijack-dns" }),
            },
        }.ToJsonString();

        using var profile = JsonDocument.Parse(source);
        var routes = WindowsRouteCatalog.Parse(profile.RootElement);

        Assert.Equal(17, routes.Count);
    }

    [Fact]
    public void DuplicateTagsAcrossOutboundsAndEndpointsAreRejected()
    {
        const string source = """
            {
              "inbounds": [{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"]}],
              "outbounds": [
                {"type":"urltest","tag":"shared","outbounds":[]},
                {"type":"vless","tag":"network"}
              ],
              "endpoints": [{"type":"wireguard","tag":"shared"}],
              "route": {"final":"shared","rules":[{"protocol":"dns","action":"hijack-dns"}]}
            }
            """;

        using var profile = JsonDocument.Parse(source);
        var error = Assert.Throws<InvalidDataException>(() => WindowsRouteCatalog.Parse(profile.RootElement));

        Assert.Equal("The subscription has invalid or duplicate profile tags.", error.Message);
    }

    [Fact]
    public void AutomaticRouteRemainsRequiredEvenWhenOtherRoutesExist()
    {
        const string source = """
            {
              "inbounds": [{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"]}],
              "outbounds": [
                {"type":"vless","tag":"route:DE:VLESS"},
                {"type":"direct","tag":"direct"}
              ],
              "route": {"final":"direct","rules":[{"protocol":"dns","action":"hijack-dns"}]}
            }
            """;

        using var profile = JsonDocument.Parse(source);
        var error = Assert.Throws<InvalidDataException>(() => WindowsRouteCatalog.Parse(profile.RootElement));

        Assert.Equal("The subscription is missing the automatic route.", error.Message);
    }

    [Fact]
    public void AwgCatalogExcludesLegacyGenerationEvenWhenCachedProfileIsProvided()
    {
        const string source = """
            {
              "inbounds": [{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"]}],
              "outbounds": [
                {"type":"urltest","tag":"🇪🇺 автоподбор","outbounds":["route:IT:VLESS"]},
                {"type":"vless","tag":"route:IT:VLESS"},
                {"type":"direct","tag":"direct"}
              ],
              "route": {"final":"🇪🇺 автоподбор","rules":[{"protocol":"dns","action":"hijack-dns"}]}
            }
            """;
        using var profile = JsonDocument.Parse(source);
        const string config = """
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.8.0.2/32
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 15
            S2 = 20

            [Peer]
            PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
            Endpoint = it.example:51820
            AllowedIPs = 0.0.0.0/0
            """;
        var awgProfiles = new[]
        {
            new WindowsAwgProfile("awg15:aXQ", "it", "италия 1.5", "IT", config, "15"),
            new WindowsAwgProfile("awg31:aXQ", "it", "италия 3.1", "IT", config, "31"),
        };

        var routes = WindowsRouteCatalog.Parse(profile.RootElement, awgProfiles);

        Assert.DoesNotContain(routes, route => route.Id == "awg15:aXQ" || route.Protocol == "AWG15");
        var current = Assert.Single(routes, route => route.Id == "awg31:aXQ");
        Assert.Equal(("AWG31", "amneziawg 3.1", "IT"), (current.Protocol, current.ProtocolName, current.CountryCode));
        Assert.Equal("италия 3.1", current.ProfileName);
    }
}

