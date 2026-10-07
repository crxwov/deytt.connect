using System.Text.Json;
using System.Text.Json.Nodes;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsSubscriptionProfileNormalizerTests
{
    [Fact]
    public void EmptyInboundsBecomeAWindowsTunAndTheRouteCatalogLoads()
    {
        const string autoTag = "🇪🇺 автоподбор";
        var outbounds = new JsonArray
        {
            new JsonObject { ["type"] = "urltest", ["tag"] = autoTag },
            new JsonObject { ["type"] = "selector", ["tag"] = "route:RU-DE:CHAIN" },
        };
        foreach (var country in new[] { "NL", "RU", "DE", "FI" })
        foreach (var protocol in new[] { "VLESS", "TROJAN", "HYSTERIA2" })
            outbounds.Add(new JsonObject
            {
                ["type"] = protocol.ToLowerInvariant(),
                ["tag"] = $"route:{country}:{protocol}",
            });

        var source = new JsonObject
        {
            ["inbounds"] = new JsonArray(),
            ["outbounds"] = outbounds,
            ["route"] = new JsonObject
            {
                ["final"] = autoTag,
                ["rules"] = new JsonArray(new JsonObject
                {
                    ["protocol"] = "dns",
                    ["action"] = "hijack-dns",
                }),
            },
        }.ToJsonString();

        var normalized = WindowsSubscriptionProfileNormalizer.AddTunForEmptyInbounds(source);
        using var profile = JsonDocument.Parse(normalized);
        var tun = profile.RootElement.GetProperty("inbounds")[0];
        Assert.Equal("tun", tun.GetProperty("type").GetString());
        Assert.Equal("172.19.0.1/30", tun.GetProperty("address")[0].GetString());
        Assert.Equal(14, WindowsRouteCatalog.Parse(profile.RootElement).Count);
    }

    [Fact]
    public void ExistingProxyInboundIsNotReplaced()
    {
        const string source = "{\"inbounds\":[{\"type\":\"mixed\",\"tag\":\"existing\"}]}";
        Assert.Equal(source, WindowsSubscriptionProfileNormalizer.AddTunForEmptyInbounds(source));
    }

    [Fact]
    public void GroupsWithoutNetworkOutboundsCannotAppearAsConnectableRoutes()
    {
        const string source = """
            {
              "inbounds": [{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"]}],
              "outbounds": [
                {"type":"urltest","tag":"🇪🇺 автоподбор","outbounds":[]},
                {"type":"direct","tag":"direct"}
              ],
              "route": {"final":"🇪🇺 автоподбор","rules":[{"protocol":"dns","action":"hijack-dns"}]}
            }
            """;
        using var profile = JsonDocument.Parse(source);
        var error = Assert.Throws<InvalidDataException>(() => WindowsRouteCatalog.Parse(profile.RootElement));
        Assert.Equal("The subscription has no network outbounds or endpoints.", error.Message);
    }
}
