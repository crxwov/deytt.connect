using System.Text.Json;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsSplitTunnelStoreTests
{
    [Fact]
    public void ConfigFlagsAndNormalizationRecognizeFullMode()
    {
        var full = new WindowsSplitTunnelConfig(["example.org"], ["app.exe"], WindowsSplitTunnelConfig.ModeFull);
        Assert.True(full.IsFull);
        Assert.False(full.IsVpnOnly);

        var deserialized = JsonSerializer.Deserialize<WindowsSplitTunnelConfig>(
            "{\"mode\":\"full\",\"customDomains\":[\"2ip.ru\"]}",
            new JsonSerializerOptions(JsonSerializerDefaults.Web));
        Assert.NotNull(deserialized);
        Assert.True(deserialized!.IsFull);
        Assert.Equal("full", deserialized.Mode);
    }
}
