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

    [Fact]
    public void BuildRejectsUnsafeSingBoxPrivilegeEscalationFields()
    {
        var withLogOutput = JsonNode.Parse(Profile("route:DE:VLESS"))!.AsObject();
        withLogOutput["log"] = new JsonObject { ["level"] = "info", ["output"] = @"C:\Windows\System32\evil.log" };
        Assert.Throws<InvalidDataException>(() =>
            TunnelProfileBuilder.Build(withLogOutput.ToJsonString(), "route:DE:VLESS"));

        var withExperimental = JsonNode.Parse(Profile("route:DE:VLESS"))!.AsObject();
        withExperimental["experimental"] = new JsonObject
        {
            ["cache_file"] = new JsonObject { ["enabled"] = true, ["path"] = @"C:\Windows\System32\cache.db" },
        };
        Assert.Throws<InvalidDataException>(() =>
            TunnelProfileBuilder.Build(withExperimental.ToJsonString(), "route:DE:VLESS"));

        var withCertPath = JsonNode.Parse(Profile("route:DE:VLESS"))!.AsObject();
        withCertPath["outbounds"]!.AsArray()[1]!["tls"] = new JsonObject
        {
            ["enabled"] = true,
            ["certificate_path"] = @"C:\Windows\win.ini",
        };
        Assert.Throws<InvalidDataException>(() =>
            TunnelProfileBuilder.Build(withCertPath.ToJsonString(), "route:DE:VLESS"));
    }

    [Fact]
    public void BuildEnforcesIpv6LeakProtectionAndIpv4OnlyDnsStrategy()
    {
        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(Profile("route:DE:VLESS"), "route:DE:VLESS"))!;
        var inbounds = runtime["inbounds"]!.AsArray();
        var tun = Assert.Single(inbounds)!.AsObject();

        var addresses = tun["address"]!.AsArray().Select(node => node!.GetValue<string>()).ToArray();
        Assert.Contains("172.19.0.1/30", addresses);
        Assert.Contains("fdfe:dcba:9876::1/126", addresses);

        Assert.Equal("mixed", tun["stack"]!.GetValue<string>());
        Assert.True(tun["strict_route"]!.GetValue<bool>());

        var dns = runtime["dns"]!.AsObject();
        Assert.Equal("ipv4_only", dns["strategy"]!.GetValue<string>());

        var rules = runtime["route"]!["rules"]!.AsArray();
        var ipv6RejectRule = rules.OfType<JsonObject>().FirstOrDefault(r => r["ip_version"]?.GetValue<int>() == 6);
        Assert.NotNull(ipv6RejectRule);
        Assert.Equal("reject", ipv6RejectRule["action"]!.GetValue<string>());
    }

    [Fact]
    public void GetTunAddressesReturnsBothIpv4AndIpv6Addresses()
    {
        var runtime = TunnelProfileBuilder.Build(Profile("route:DE:VLESS"), "route:DE:VLESS");
        var addresses = TunnelProfileBuilder.GetTunAddresses(runtime);

        Assert.Equal(2, addresses.Count);
        Assert.Equal("172.19.0.1/30", addresses[0]);
        Assert.Equal("fdfe:dcba:9876::1/126", addresses[1]);
    }

    [Fact]
    public void BuildAppliesCustomBypassDomainsToRouteAndDns()
    {
        var customDomains = new[] { "*.my-suffix.org", "exact-domain.com" };
        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(
            Profile("route:DE:VLESS"), "route:DE:VLESS", customBypassDomains: customDomains))!;

        var rules = runtime["route"]!["rules"]!.AsArray();
        var suffixRule = rules.OfType<JsonObject>().FirstOrDefault(r =>
            r.ContainsKey("domain_suffix") && r["outbound"]?.GetValue<string>() == "direct");
        Assert.NotNull(suffixRule);
        var suffixes = suffixRule["domain_suffix"]!.AsArray().Select(s => s!.GetValue<string>()).ToArray();
        Assert.Contains("my-suffix.org", suffixes);
        Assert.Contains("ru", suffixes);

        var exactRule = rules.OfType<JsonObject>().FirstOrDefault(r =>
            r.ContainsKey("domain") && r["outbound"]?.GetValue<string>() == "direct");
        Assert.NotNull(exactRule);
        var exacts = exactRule["domain"]!.AsArray().Select(s => s!.GetValue<string>()).ToArray();
        Assert.Contains("exact-domain.com", exacts);
        Assert.Contains("cp.cloudflare.com", exacts);

        var dnsRules = runtime["dns"]!["rules"]!.AsArray();
        var dnsSuffix = dnsRules.OfType<JsonObject>().FirstOrDefault(r =>
            r.ContainsKey("domain_suffix") && r["server"]?.GetValue<string>() == "local-dns");
        Assert.NotNull(dnsSuffix);
        Assert.Contains("my-suffix.org", dnsSuffix["domain_suffix"]!.AsArray().Select(s => s!.GetValue<string>()));
    }

    [Fact]
    public void BuildAppliesCustomBypassProcessesToRoute()
    {
        var customProcesses = new[] { @"C:\Games\Game.exe", "discord.exe", "  " };
        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(
            Profile("route:DE:VLESS"), "route:DE:VLESS", customBypassProcesses: customProcesses))!;

        var rules = runtime["route"]!["rules"]!.AsArray();
        var procRule = rules.OfType<JsonObject>().FirstOrDefault(r =>
            r.ContainsKey("process_name") && r["outbound"]?.GetValue<string>() == "direct");
        Assert.NotNull(procRule);
        var procs = procRule["process_name"]!.AsArray().Select(s => s!.GetValue<string>()).ToArray();
        Assert.Contains("Game.exe", procs);
        Assert.Contains("discord.exe", procs);
    }
}
