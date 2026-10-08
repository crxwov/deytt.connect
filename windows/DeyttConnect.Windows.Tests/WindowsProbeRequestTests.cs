using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsProbeRequestTests
{
    [Fact]
    public void CountryProbeOnlySendsProfilesForRequestedRoutes()
    {
        var profiles = new Dictionary<string, string>
        {
            ["awg31:bmw"] = "nl-profile",
            ["awg31:ZGU"] = "de-profile",
            ["awg15:bmw"] = "legacy-profile",
        };
        var request = WindowsTunnelClient.BuildProbeRequest("{}", ["route:NL:VLESS", "awg31:bmw"],
            "HEAD", "session-token", profiles);
        var restored = WindowsPipeProtocol.DeserializeRequest(WindowsPipeProtocol.SerializeRequest(request));
        Assert.Equal(new[] { "awg31:bmw" }, restored.AwgProfiles!.Keys);
        Assert.Equal("nl-profile", restored.AwgProfiles["awg31:bmw"]);
        Assert.Equal("session-token", restored.Token);
        Assert.Equal(2, restored.RouteTags!.Count);
    }

    [Fact]
    public void NonAwgProbeDoesNotSendUnrelatedPrivateProfiles()
    {
        var request = WindowsTunnelClient.BuildProbeRequest("{}", ["route:DE:VLESS"], "GET", "",
            new Dictionary<string, string> { ["awg31:bmw"] = "unrelated-profile" });
        Assert.Empty(request.AwgProfiles!);
    }
}
