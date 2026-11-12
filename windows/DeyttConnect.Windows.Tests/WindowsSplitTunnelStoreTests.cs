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

    [Fact]
    public void TryParseConfigJsonNormalizesPresetsDomainsProcessesAndModes()
    {
        const string payload = """
            {
              "activePresetId": "work",
              "mode": "full",
              "customDomains": ["https://2ip.ru/check", "*.DOMAIN.RU", "bad domain"],
              "customProcesses": ["C:\\Apps\\Firefox.exe", "discord.exe", "discord.exe"],
              "presets": [
                { "id": "default", "name": "Основной", "mode": "bypass", "customDomains": [], "customProcesses": [] },
                { "id": "work", "name": "  Работа  ", "mode": "vpn_only", "customDomains": [], "customProcesses": [] }
              ]
            }
            """;

        Assert.True(WindowsSplitTunnelStore.TryParseConfigJson(payload, out var config));
        Assert.NotNull(config);
        Assert.Equal("work", config!.ActivePresetId);
        Assert.True(config.IsFull);
        Assert.Equal(["*.domain.ru", "2ip.ru"], config.CustomDomains);
        Assert.Equal(["discord.exe", "Firefox.exe"], config.CustomProcesses);
        Assert.Equal(2, config.Presets!.Count);
        Assert.Equal("full", config.Presets[1].Mode);
        Assert.Equal(["*.domain.ru", "2ip.ru"], config.Presets[1].CustomDomains);

        Assert.False(WindowsSplitTunnelStore.TryParseConfigJson("{broken", out _));
        Assert.False(WindowsSplitTunnelStore.TryParseConfigJson(" ", out _));
    }
}
