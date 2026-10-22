using System.Text.Json.Nodes;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class TunnelProfileBuilderTests
{
    private const string AutoTag = "🌍 автоподбор";

    [Theory]
    [InlineData("urltest")]
    [InlineData("selector")]
    public void AutomaticSelectionUsesOnlyEuropeanSingleCountryServers(string type)
    {
        var profile = JsonNode.Parse(Profile("route:NL:VLESS", "route:DE:TROJAN", "route:FI:HYSTERIA2",
            "route:IT:VLESS", "route:RU:VLESS", "route:RU-DE:CHAIN"))!.AsObject();
        var automatic = profile["outbounds"]![0]!.AsObject();
        automatic["type"] = type;
        automatic["outbounds"] = new JsonArray("route:RU:VLESS", "route:RU-DE:CHAIN", "route:NL:VLESS");
        automatic["default"] = "route:RU:VLESS";
        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(profile.ToJsonString(), AutoTag))!;
        var selector = runtime["outbounds"]!.AsArray().OfType<JsonObject>()
            .Single(outbound => outbound["tag"]!.GetValue<string>() == AutoTag);
        Assert.Equal(new[] { "route:NL:VLESS", "route:DE:TROJAN", "route:FI:HYSTERIA2", "route:IT:VLESS" },
            selector["outbounds"]!.AsArray().Select(tag => tag!.GetValue<string>()));
        Assert.False(selector.ContainsKey("default"));
        Assert.DoesNotContain("route:RU", selector["outbounds"]!.ToJsonString());
    }

    [Fact]
    public void AutomaticSelectionCannotFallBackToRussiaOrRussianDetour()
    {
        var profile = JsonNode.Parse(Profile("route:NL:VLESS", "route:RU:VLESS"))!.AsObject();
        profile["outbounds"]![1]!["detour"] = "route:RU:VLESS";
        Assert.Throws<InvalidDataException>(() => TunnelProfileBuilder.Build(profile.ToJsonString(), AutoTag));
        Assert.Throws<InvalidDataException>(() => TunnelProfileBuilder.Build(Profile("route:RU:VLESS"), AutoTag));
    }

    [Fact]
    public void ExplicitRussianRouteRemainsAvailable()
    {
        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(Profile("route:RU:VLESS"), "route:RU:VLESS"))!;
        Assert.Equal("route:RU:VLESS", runtime["route"]!["final"]!.GetValue<string>());
    }
    [Theory]
    [InlineData(true, "trace")]
    [InlineData(false, "info")]
    public void WindowsRuntimeKeepsErrorsOnConsoleWithoutSubscriptionLogFiles(bool disabled, string level)
    {
        var profile = JsonNode.Parse(Profile("route:NL:VLESS"))!.AsObject();
        profile["log"] = new JsonObject
        {
            ["disabled"] = disabled,
            ["level"] = level,
            ["output"] = "untrusted-subscription-output.log",
        };

        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(profile.ToJsonString(), "route:NL:VLESS"))!;
        Assert.False(runtime["log"]!["disabled"]!.GetValue<bool>());
        Assert.Equal("error", runtime["log"]!["level"]!.GetValue<string>());
        Assert.False(runtime["log"]!.AsObject().ContainsKey("output"));
    }

    [Fact]
    public void WindowsRuntimeStripsPrivilegedExperimentalCertificateAndServicesSections()
    {
        var profile = JsonNode.Parse(Profile("route:NL:VLESS"))!.AsObject();
        profile["experimental"] = new JsonObject
        {
            ["cache_file"] = new JsonObject
            {
                ["path"] = @"C:\Windows\System32\drivers\etc\hosts",
            },
        };
        profile["certificate"] = new JsonObject { ["store"] = "system" };
        profile["services"] = new JsonArray(new JsonObject { ["type"] = "test" });

        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(profile.ToJsonString(), "route:NL:VLESS"))!.AsObject();
        Assert.False(runtime.ContainsKey("experimental"));
        Assert.False(runtime.ContainsKey("certificate"));
        Assert.False(runtime.ContainsKey("services"));

        var (telemetryProfile, _) = ActiveRouteTelemetry.Create(runtime.ToJsonString(), "route:NL:VLESS");
        var telemetryJson = JsonNode.Parse(telemetryProfile)!.AsObject();
        Assert.True(telemetryJson.ContainsKey("experimental"));
        var experimental = telemetryJson["experimental"]!.AsObject();
        Assert.True(experimental.ContainsKey("clash_api"));
        Assert.False(experimental.ContainsKey("cache_file"));
    }

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
        Assert.Equal("deytt-connect", tun["interface_name"]!.GetValue<string>());
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
    public void ConnectionPrunesUnusedWireGuardEndpointsAndTheirSelectors()
    {
        var profile = JsonNode.Parse(Profile("route:NL:VLESS"))!.AsObject();
        profile["endpoints"] = new JsonArray(new JsonObject
        {
            ["type"] = "wireguard",
            ["tag"] = "unused-wg",
            ["address"] = new JsonArray("10.8.0.2/32"),
            ["peers"] = new JsonArray(new JsonObject { ["address"] = "unavailable.invalid" }),
        });
        profile["outbounds"]!.AsArray().Add(new JsonObject
        {
            ["type"] = "selector",
            ["tag"] = "unused-wg-selector",
            ["outbounds"] = new JsonArray("unused-wg"),
        });

        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(profile.ToJsonString(), "route:NL:VLESS"))!.AsObject();

        Assert.False(runtime.ContainsKey("endpoints"));
        Assert.DoesNotContain(runtime["outbounds"]!.AsArray(), outbound =>
            outbound!["tag"]!.GetValue<string>() == "unused-wg-selector");
        Assert.Contains(runtime["outbounds"]!.AsArray(), outbound =>
            outbound!["tag"]!.GetValue<string>() == "route:NL:VLESS");
    }

    [Fact]
    public void ConnectionKeepsDetoursPolicyTargetsAndObjectDnsResolverDependencies()
    {
        var profile = JsonNode.Parse(Profile("route:NL:VLESS"))!.AsObject();
        var outbounds = profile["outbounds"]!.AsArray();
        outbounds[1]!["detour"] = "transport-hop";
        outbounds[1]!["domain_resolver"] = new JsonObject { ["server"] = "bootstrap-dns" };
        outbounds.Add(new JsonObject { ["type"] = "direct", ["tag"] = "transport-hop" });
        outbounds.Add(new JsonObject { ["type"] = "direct", ["tag"] = "policy-hop" });
        profile["route"]!["rules"] = new JsonArray(new JsonObject
        {
            ["ip_cidr"] = new JsonArray("192.0.2.0/24"), ["outbound"] = "policy-hop",
        });
        profile["dns"]!["servers"] = new JsonArray(
            new JsonObject { ["type"] = "local", ["tag"] = "local-dns" },
            new JsonObject { ["type"] = "https", ["tag"] = "remote-dns", ["server"] = "192.0.2.53" },
            new JsonObject
            {
                ["type"] = "https", ["tag"] = "bootstrap-dns", ["server"] = "resolver.example.test",
                ["domain_resolver"] = new JsonObject { ["server"] = "bootstrap-local" },
            },
            new JsonObject { ["type"] = "local", ["tag"] = "bootstrap-local" },
            new JsonObject { ["type"] = "local", ["tag"] = "unused-dns" });

        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(profile.ToJsonString(), "route:NL:VLESS"))!;

        var outboundTags = runtime["outbounds"]!.AsArray()
            .Select(node => node!["tag"]!.GetValue<string>()).ToHashSet(StringComparer.Ordinal);
        Assert.Contains("transport-hop", outboundTags);
        Assert.Contains("policy-hop", outboundTags);
        var dnsTags = runtime["dns"]!["servers"]!.AsArray()
            .Select(node => node!["tag"]!.GetValue<string>()).ToHashSet(StringComparer.Ordinal);
        Assert.Contains("bootstrap-dns", dnsTags);
        Assert.Contains("bootstrap-local", dnsTags);
        Assert.DoesNotContain("unused-dns", dnsTags);
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
    public void BuildAppliesCustomBypassDomainsAndProcesses()
    {
        var customDomains = new[] { "mycustomdomain.com", "*.customsub.org" };
        var customProcesses = new[] { "game.exe", "customapp.exe" };
        var runtime = JsonNode.Parse(TunnelProfileBuilder.Build(
            Profile("route:DE:VLESS"), "route:DE:VLESS", null, null,
            customDomains, customProcesses))!;

        Assert.True(runtime["route"]!["find_process"]!.GetValue<bool>());
        var rules = runtime["route"]!["rules"]!.AsArray();

        var processRule = rules.OfType<JsonObject>().FirstOrDefault(r => r.ContainsKey("process_name"));
        Assert.NotNull(processRule);
        Assert.Equal("direct", processRule["outbound"]!.GetValue<string>());
        var procNames = processRule["process_name"]!.AsArray().Select(n => n!.GetValue<string>()).ToArray();
        Assert.Contains("game.exe", procNames);
        Assert.Contains("customapp.exe", procNames);

        var domainRules = rules.OfType<JsonObject>().Where(r => r["outbound"]?.GetValue<string>() == "direct").ToArray();
        var allSuffixes = domainRules
            .Where(r => r.ContainsKey("domain_suffix"))
            .SelectMany(r => r["domain_suffix"]!.AsArray().Select(n => n!.GetValue<string>()))
            .ToArray();
        Assert.Contains("customsub.org", allSuffixes);
        Assert.Contains("mycustomdomain.com", allSuffixes);
    }

    [Fact]
    public void WindowsPipeProtocolSerializesCustomBypass()
    {
        var request = new WindowsPipeRequest
        {
            Command = WindowsPipeCommand.Connect,
            Profile = "{}",
            RouteTag = "route:DE:HYSTERIA2",
            CustomBypassDomains = new[] { "2ip.io", "example.com" },
            CustomBypassProcesses = new[] { "curl.exe", "discord" }
        };

        var payload = WindowsPipeProtocol.SerializeRequest(request);
        var deserialized = WindowsPipeProtocol.DeserializeRequest(payload);

        Assert.NotNull(deserialized.CustomBypassDomains);
        Assert.Equal(new[] { "2ip.io", "example.com" }, deserialized.CustomBypassDomains);
        Assert.NotNull(deserialized.CustomBypassProcesses);
        Assert.Equal(new[] { "curl.exe", "discord" }, deserialized.CustomBypassProcesses);
    }

    [Fact]
    public void BuildAppliesVpnOnlyMode()
    {
        var profile = Profile("route:NL:VLESS");
        var customDomains = new[] { "targetdomain.com", "*.targetsub.org" };
        var customProcesses = new[] { "game.exe", "customapp" };

        var runtimeJson = TunnelProfileBuilder.Build(
            profile,
            "route:NL:VLESS",
            customBypassDomains: customDomains,
            customBypassProcesses: customProcesses,
            splitTunnelMode: "vpn_only");

        var runtime = JsonNode.Parse(runtimeJson)!.AsObject();

        // In vpn_only mode, default route is direct!
        Assert.Equal("direct", runtime["route"]!["final"]!.GetValue<string>());
        Assert.True(runtime["route"]!["find_process"]!.GetValue<bool>());

        // Direct outbound must exist
        var outbounds = runtime["outbounds"]!.AsArray();
        Assert.Contains(outbounds.OfType<JsonObject>(), o => o["tag"]?.GetValue<string>() == "direct");

        var rules = runtime["route"]!["rules"]!.AsArray();

        // Process rule should route to VPN (route:NL:VLESS)
        var processRule = rules.OfType<JsonObject>().FirstOrDefault(r => r.ContainsKey("process_name"));
        Assert.NotNull(processRule);
        Assert.Equal("route:NL:VLESS", processRule["outbound"]!.GetValue<string>());
        var procNames = processRule["process_name"]!.AsArray().Select(n => n!.GetValue<string>()).ToArray();
        Assert.Contains("game.exe", procNames);
        Assert.Contains("customapp", procNames);
        Assert.Contains("customapp.exe", procNames);

        // Domain rule should route to VPN (route:NL:VLESS)
        var vpnDomainRules = rules.OfType<JsonObject>()
            .Where(r => r["outbound"]?.GetValue<string>() == "route:NL:VLESS" && r.ContainsKey("domain_suffix"))
            .ToArray();
        var allVpnSuffixes = vpnDomainRules
            .SelectMany(r => r["domain_suffix"]!.AsArray().Select(n => n!.GetValue<string>()))
            .ToArray();
        Assert.Contains("targetsub.org", allVpnSuffixes);
        Assert.Contains("targetdomain.com", allVpnSuffixes);

        // DNS should have final local-dns and route custom domains to remote-dns
        Assert.Equal("local-dns", runtime["dns"]!["final"]!.GetValue<string>());
        var dnsRules = runtime["dns"]!["rules"]!.AsArray();
        var customDnsRule = dnsRules.OfType<JsonObject>().FirstOrDefault(r => r["action"]?.GetValue<string>() == "route");
        Assert.NotNull(customDnsRule);
        Assert.Equal("remote-dns", customDnsRule["server"]!.GetValue<string>());
    }

    [Fact]
    public void WindowsPipeProtocolSerializesSplitTunnelMode()
    {
        var request = new WindowsPipeRequest
        {
            Command = WindowsPipeCommand.Connect,
            Profile = "{}",
            RouteTag = "route:DE:HYSTERIA2",
            CustomBypassDomains = new[] { "2ip.io" },
            CustomBypassProcesses = new[] { "curl.exe" },
            SplitTunnelMode = "vpn_only"
        };

        var payload = WindowsPipeProtocol.SerializeRequest(request);
        var deserialized = WindowsPipeProtocol.DeserializeRequest(payload);

        Assert.Equal("vpn_only", deserialized.SplitTunnelMode);
        Assert.Equal(new[] { "2ip.io" }, deserialized.CustomBypassDomains);
        Assert.Equal(new[] { "curl.exe" }, deserialized.CustomBypassProcesses);
    }
}
