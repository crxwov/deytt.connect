using System.Text;
using System.Text.Json;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class ActiveRouteTelemetryTests
{
    private const string Profile = """
        {"route":{"final":"auto"},"outbounds":[
        {"type":"urltest","tag":"auto","outbounds":["route:NL:VLESS","route:DE:TROJAN"]},
        {"type":"vless","tag":"route:NL:VLESS"},
        {"type":"trojan","tag":"route:DE:TROJAN"}]}
        """;

    [Theory]
    [InlineData("route:NL:VLESS", "route:NL:VLESS")]
    [InlineData("route:DE:TROJAN", "route:DE:TROJAN")]
    [InlineData("route:FI:VLESS", null)]
    [InlineData("direct", null)]
    [InlineData("", null)]
    public void ResolvesOnlyCurrentMemberOfRuntimeAutomaticGroup(string now, string? expected)
    {
        var response = JsonSerializer.Serialize(new { proxies = new Dictionary<string, object>
            { ["auto"] = new { now } } });
        Assert.Equal(expected, ActiveRouteTelemetry.ResolveActiveRouteTag(Profile, "auto", response));
    }

    [Fact]
    public void NestedSelectorMustUseCurrentVerifiedMembership()
    {
        const string profile = """
            {"route":{"final":"auto"},"outbounds":[
            {"type":"urltest","tag":"auto","outbounds":["route:FI:VLESS"]},
            {"type":"selector","tag":"route:FI:VLESS","outbounds":["endpoint-fi"]},
            {"type":"vless","tag":"endpoint-fi"}]}
            """;
        Assert.Equal("route:FI:VLESS", ActiveRouteTelemetry.ResolveActiveRouteTag(profile, "auto",
            """{"proxies":{"auto":{"now":"route:FI:VLESS"},"route:FI:VLESS":{"now":"endpoint-fi"}}}"""));
        Assert.Null(ActiveRouteTelemetry.ResolveActiveRouteTag(profile, "auto",
            """{"proxies":{"auto":{"now":"route:FI:VLESS"},"route:FI:VLESS":{"now":"unknown"}}}"""));
        Assert.Null(ActiveRouteTelemetry.ResolveActiveRouteTag(profile, "auto",
            """{"proxies":{"auto":{"now":"route:FI:VLESS"}}}"""));
    }

    [Fact]
    public void EndpointMembershipMayIdentifyOneRouteButNeverGuessAmbiguousRegion()
    {
        const string profile = """
            {"route":{"final":"auto"},"outbounds":[
            {"type":"urltest","tag":"auto","outbounds":["leaf"]},
            {"type":"selector","tag":"route:NL:VLESS","outbounds":["leaf"]},
            {"type":"vless","tag":"leaf"}]}
            """;
        const string response = """{"proxies":{"auto":{"now":"leaf"}}}""";
        Assert.Equal("route:NL:VLESS", ActiveRouteTelemetry.ResolveActiveRouteTag(profile, "auto", response));
        var ambiguous = profile.Replace("{\"type\":\"vless\",\"tag\":\"leaf\"}",
            "{\"type\":\"selector\",\"tag\":\"route:DE:VLESS\",\"outbounds\":[\"leaf\"]},{\"type\":\"vless\",\"tag\":\"leaf\"}");
        Assert.Null(ActiveRouteTelemetry.ResolveActiveRouteTag(ambiguous, "auto", response));
    }

    [Fact]
    public void CyclesMissingNowAndNonNetworkLeavesNeverProduceLocation()
    {
        const string cycle = """{"route":{"final":"auto"},"outbounds":[{"type":"selector","tag":"auto","outbounds":["auto"]}]}""";
        Assert.Null(ActiveRouteTelemetry.ResolveActiveRouteTag(cycle, "auto", """{"proxies":{"auto":{"now":"auto"}}}"""));
        Assert.Null(ActiveRouteTelemetry.ResolveActiveRouteTag(Profile, "auto", """{"proxies":{"auto":{}}}"""));
        var direct = Profile.Replace("\"type\":\"vless\"", "\"type\":\"direct\"");
        Assert.Null(ActiveRouteTelemetry.ResolveActiveRouteTag(direct, "auto",
            """{"proxies":{"auto":{"now":"route:NL:VLESS"}}}"""));
    }

    [Fact]
    public void RuntimeApiIsEphemeralAuthenticatedLoopbackAndReplacesSubscriptionUi()
    {
        var source = Profile[..^1] + ",\"experimental\":{\"clash_api\":{\"external_controller\":\"0.0.0.0:9090\",\"external_ui\":\"unsafe\",\"secret\":\"old\"}}}";
        var first = ActiveRouteTelemetry.Create(source, "auto");
        var second = ActiveRouteTelemetry.Create(source, "auto");
        using var firstLifetime = first.Telemetry;
        using var secondLifetime = second.Telemetry;
        using var firstProfile = JsonDocument.Parse(first.Profile);
        using var secondProfile = JsonDocument.Parse(second.Profile);
        var api = firstProfile.RootElement.GetProperty("experimental").GetProperty("clash_api");
        Assert.StartsWith("127.0.0.1:", api.GetProperty("external_controller").GetString());
        Assert.Equal(64, api.GetProperty("secret").GetString()!.Length);
        Assert.NotEqual(api.GetProperty("secret").GetString(), secondProfile.RootElement.GetProperty("experimental").GetProperty("clash_api").GetProperty("secret").GetString());
        Assert.False(api.TryGetProperty("external_ui", out _));
        Assert.False(api.GetProperty("access_control_allow_private_network").GetBoolean());
    }

    [Fact]
    public void OptionalActiveRoutePreservesSelectedModeAndOldResponseCompatibility()
    {
        var snapshot = new WindowsTunnelSnapshot("connected", "ok", "auto", ActiveRouteTag: "route:FI:VLESS");
        var result = WindowsPipeProtocol.DeserializeResponse(WindowsPipeProtocol.SerializeResponse(snapshot));
        Assert.Equal("auto", result.RouteTag);
        Assert.Equal("route:FI:VLESS", result.ActiveRouteTag);
        var old = WindowsPipeProtocol.DeserializeResponse(Encoding.UTF8.GetBytes("{\"state\":\"connected\",\"detail\":\"ok\",\"routeTag\":\"auto\"}"));
        Assert.Null(old.ActiveRouteTag);
    }
}
