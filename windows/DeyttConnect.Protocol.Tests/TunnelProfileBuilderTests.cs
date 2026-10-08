using System.Text.Json.Nodes;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class TunnelProfileBuilderTests
{
    private const string AutoTag = "🌍 автоподбор";
    private const string LegacyAwgProfile = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 10.0.0.2/32
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 1
        S2 = 2
        S3 = 3
        S4 = 4
        H1 = 5
        H2 = 6
        H3 = 7
        H4 = 8
        [Peer]
        PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 192.0.2.1:51820
        """;

    private static string Profile(params string[] routeTags)
    {
        var outbounds = new JsonArray
        {
            new JsonObject { ["type"] = "urltest", ["tag"] = AutoTag },
        };
        foreach (var tag in routeTags)
            outbounds.Add(new JsonObject { ["type"] = "vless", ["tag"] = tag });

        return new JsonObject
        {
            ["inbounds"] = new JsonArray
            {
                new JsonObject { ["type"] = "mixed", ["tag"] = "subscription-proxy", ["listen_port"] = 2080 },
                new JsonObject
                {
                    ["type"] = "tun",
                    ["tag"] = "subscription-tun",
                    ["address"] = new JsonArray("172.19.0.1/30"),
                    ["dns_mode"] = "prefer_ipv4",
                },
            },
            ["outbounds"] = outbounds,
            ["route"] = new JsonObject { ["final"] = AutoTag, ["rules"] = new JsonArray() },
            ["dns"] = new JsonObject { ["servers"] = new JsonArray() },
        }.ToJsonString();
    }

    [Theory]
    [InlineData("on", "off", true, false)]
    [InlineData("off", "on", false, true)]
    [InlineData("ON", "OFF", true, false)]
    [InlineData("true", "false", true, false)]
    [InlineData("True", "False", true, false)]
    [InlineData("1", "0", true, false)]
    [InlineData("0", "1", false, true)]
    public void BuildPreservesAwg31BooleanValues(string trailers, string cookies,
        bool expectedTrailers, bool expectedCookies)
    {
        var config = LegacyAwgProfile.Replace("[Peer]",
            $"HeaderProtectionKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nRandomTrailers = {trailers}\nDisableCookies = {cookies}\n[Peer]");

        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(Profile(), "awg31", config))!;
        var endpoint = Assert.Single(runtime["endpoints"]!.AsArray());

        Assert.Equal(expectedTrailers, endpoint!["random_trailers"]!.GetValue<bool>());
        Assert.Equal(expectedCookies, endpoint["disable_cookies"]!.GetValue<bool>());
    }

    [Theory]
    [InlineData("enabled")]
    [InlineData("2")]
    [InlineData("true false")]
    public void BuildRejectsInvalidAwgBooleanValues(string value)
    {
        var config = LegacyAwgProfile.Replace("[Peer]", $"RandomTrailers = {value}\n[Peer]");

        Assert.Throws<InvalidDataException>(() => TunnelProfileBuilder.Build(Profile(), "awg31", config));
    }

    [Theory]
    [InlineData("10.0.0.2/32", "ipv4_only")]
    [InlineData("fd00::2/128", "ipv6_only")]
    [InlineData("10.0.0.2/32, fd00::2/128", "prefer_ipv4")]
    public void AwgResolvesDomainsBeforeEnteringNetstack(string addresses, string strategy)
    {
        var config = LegacyAwgProfile.Replace("10.0.0.2/32", addresses);
        var connection = JsonNode.Parse(TunnelProfileBuilder.Build(Profile(), "awg31", config))!;
        var connectionRules = connection["route"]!["rules"]!.AsArray();
        Assert.Equal("resolve", connectionRules.Last()!["action"]!.GetValue<string>());
        Assert.Equal(strategy, connectionRules.Last()!["strategy"]!.GetValue<string>());

        var probes = new[] { new ProbeRouteEndpoint("awg31", "awg-probe", 32101, "user", "pass", config) };
        var probe = JsonNode.Parse(TunnelProfileBuilder.BuildProbeProfile(Profile(), probes))!;
        var rules = probe["route"]!["rules"]!.AsArray();
        Assert.Equal(2, rules.Count);
        Assert.Equal("resolve", rules[0]!["action"]!.GetValue<string>());
        Assert.Equal(strategy, rules[0]!["strategy"]!.GetValue<string>());
        Assert.Equal("awg-probe", rules[0]!["inbound"]![0]!.GetValue<string>());
        Assert.Equal("route", rules[1]!["action"]!.GetValue<string>());
    }

    [Fact]
    public void BuildKeepsOnlyTunInboundAndRemovesAndroidOnlyDnsMode()
    {
        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(Profile("route:DE:VLESS"), "route:DE:VLESS"))!;
        var inbounds = runtime["inbounds"]!.AsArray();

        var tun = Assert.Single(inbounds);
        Assert.Equal("tun", tun!["type"]!.GetValue<string>());
        Assert.Equal("subscription-tun", tun["tag"]!.GetValue<string>());
        Assert.False(tun.AsObject().ContainsKey("dns_mode"));
    }

    [Fact]
    public void BuildAcceptsPartialCountryCatalogWithoutInventingUnavailableRoutes()
    {
        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(Profile("route:DE:VLESS"), "route:DE:VLESS"))!;

        Assert.Equal("route:DE:VLESS", runtime["route"]!["final"]!.GetValue<string>());
        Assert.DoesNotContain(runtime["outbounds"]!.AsArray(), outbound =>
            outbound?["tag"]?.GetValue<string>() == "route:IT:VLESS");
    }

    [Fact]
    public void BuildAcceptsItalianRouteWhenAdvertisedBySubscription()
    {
        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(Profile("route:IT:VLESS"), "route:IT:VLESS"))!;

        Assert.Equal("route:IT:VLESS", runtime["route"]!["final"]!.GetValue<string>());
    }

    [Fact]
    public void BuildProbeProfilePrunesUnselectedOutboundsEndpointsAndDnsServers()
    {
        const string profile = """
            {
              "inbounds": [
                {"type":"tun","tag":"subscription-tun","address":["172.19.0.1/30"],"dns_mode":"prefer_ipv4"}
              ],
              "outbounds": [
                {"type":"urltest","tag":"🇪🇺 автоподбор","outbounds":["route:FI:VLESS","route:FI:TROJAN","route:FI:HYSTERIA2"],"url":"https://probe.invalid/"},
                {"type":"selector","tag":"route:FI","outbounds":["route:FI:VLESS","route:FI:TROJAN","route:FI:HYSTERIA2"],"default":"route:FI:VLESS"},
                {"type":"vless","tag":"route:FI:VLESS","server":"127.0.0.1","server_port":9,"uuid":"00000000-0000-0000-0000-000000000001","detour":"probe-hop"},
                {"type":"trojan","tag":"route:FI:TROJAN","server":"127.0.0.1","server_port":9,"password":"test"},
                {"type":"hysteria2","tag":"route:FI:HYSTERIA2","server":"127.0.0.1","server_port":9,"password":"test","tls":{"enabled":true}},
                {"type":"selector","tag":"probe-hop","outbounds":["hop-direct","hop-block"],"default":"hop-direct"},
                {"type":"direct","tag":"hop-direct"},
                {"type":"block","tag":"hop-block"},
                {"type":"vless","tag":"unused-route","server":"127.0.0.1","server_port":9,"uuid":"00000000-0000-0000-0000-000000000002"}
              ],
              "endpoints": [
                {"type":"wireguard","tag":"unused-classic-wg","address":["10.8.0.2/32"],"peers":[]}
              ],
              "dns": {
                "servers": [
                  {"type":"https","tag":"remote-dns","server":"127.0.0.1","server_port":9,"detour":"route:FI:VLESS"},
                  {"type":"https","tag":"unused-google-dns","server":"127.0.0.1","server_port":9},
                  {"type":"local","tag":"local-dns"}
                ],
                "rules":[{"domain_suffix":["ru"],"action":"route","server":"local-dns"}],
                "final":"remote-dns"
              },
              "route": {
                "rules":[{"domain_suffix":["example.invalid"],"outbound":"direct"}],
                "final":"🇪🇺 автоподбор",
                "default_domain_resolver":"local-dns"
              }
            }
            """;
        var probes = new[]
        {
            new ProbeRouteEndpoint("route:FI:VLESS", "probe-vless", 32101, "user", "pass", null),
            new ProbeRouteEndpoint("route:FI:TROJAN", "probe-trojan", 32102, "user", "pass", null),
            new ProbeRouteEndpoint("awg31:ZmI", "probe-awg", 32103, "user", "pass",
                LegacyAwgProfile.Replace("10.0.0.2/32", "10.8.0.2/32")
                    .Replace("192.0.2.1:51820", "127.0.0.1:9")),
        };

        var runtime = JsonNode.Parse(TunnelProfileBuilder.BuildProbeProfile(profile, probes))!;

        var outboundTags = runtime["outbounds"]!.AsArray()
            .Select(node => node!["tag"]!.GetValue<string>()).ToHashSet(StringComparer.Ordinal);
        Assert.Equal(new HashSet<string>(["route:FI:VLESS", "route:FI:TROJAN", "probe-hop", "hop-direct", "hop-block"],
            StringComparer.Ordinal), outboundTags);
        var awgEndpoint = Assert.Single(runtime["endpoints"]!.AsArray());
        Assert.StartsWith("deytt-awg-", awgEndpoint!["tag"]!.GetValue<string>());
        Assert.Equal("10.8.0.2/32", awgEndpoint["address"]![0]!.GetValue<string>());
        Assert.Equal(new HashSet<string>(["remote-dns", "local-dns"], StringComparer.Ordinal),
            runtime["dns"]!["servers"]!.AsArray()
                .Select(node => node!["tag"]!.GetValue<string>()).ToHashSet(StringComparer.Ordinal));
        Assert.All(runtime["dns"]!["servers"]!.AsArray(), server =>
            Assert.False(server!.AsObject().ContainsKey("detour")));
        Assert.Equal(4, runtime["route"]!["rules"]!.AsArray().Count);
        Assert.All(runtime["route"]!["rules"]!.AsArray(), rule =>
            Assert.True(rule!.AsObject().ContainsKey("inbound")));
    }

    [Theory]
    [InlineData("awg15")]
    [InlineData("awg15:ZXhhbXBsZQ")]
    [InlineData("awg31")]
    [InlineData("awg31:ZXhhbXBsZQ")]
    public void BuildParsesLegacyConfigForAwg15AndAwg31RouteTags(string routeTag)
    {
        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(Profile(routeTag), routeTag, LegacyAwgProfile))!;
        var endpoint = Assert.Single(runtime["endpoints"]!.AsArray())!;

        Assert.Equal("awg", endpoint["type"]!.GetValue<string>());
        Assert.Equal("deytt-awg-", endpoint["tag"]!.GetValue<string>()[..10]);
        Assert.Equal(4, endpoint["jc"]!.GetValue<int>());
        Assert.Equal(endpoint["tag"]!.GetValue<string>(), runtime["route"]!["final"]!.GetValue<string>());
        Assert.Null(endpoint["header_protection_key"]);
    }

    [Theory]
    [InlineData("awg15:")]
    [InlineData("awg31:")]
    [InlineData("awg15x:id")]
    [InlineData("awg31x:id")]
    [InlineData("awg15:id:evil")]
    [InlineData("awg31:id:evil")]
    [InlineData("awg15:bad/id")]
    [InlineData("awg31:bad/id")]
    [InlineData("awg32:id")]
    public void BuildRejectsInvalidAwgRouteIds(string routeTag)
    {
        Assert.Throws<InvalidDataException>(() =>
            TunnelProfileBuilder.Build(Profile(routeTag), routeTag, "not a profile"));
    }
}
