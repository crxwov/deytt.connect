using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class TelegramAwgUrlTests
{
    [Fact]
    public void BaseAwgRequestMatchesAndroidFormatAndKeepsUnrelatedQueryParameters()
    {
        var source = new Uri("https://deytt.space/sub/key?lang=ru&format=old&version=15&keep=one&keep=two#fragment");

        var result = TelegramApiClient.BuildAwgUri(source, serverId: null);

        Assert.Equal("https", result.Scheme);
        Assert.Equal("/sub/key", result.AbsolutePath);
        Assert.Equal("#fragment", result.Fragment);
        Assert.Equal("lang=ru&keep=one&keep=two&format=amneziawg31", result.Query.TrimStart('?'));
    }

    [Fact]
    public void PerServerAwgRequestAddsEncodedServerId()
    {
        var source = new Uri("https://deytt.space/sub/key?format=awg&server_id=old");

        var result = TelegramApiClient.BuildAwgUri(source, "edge eu/1");

        Assert.Equal("format=amneziawg31&server_id=edge%20eu%2F1", result.Query.TrimStart('?'));
    }

    [Fact]
    public void LegacyAwgRequestUsesTheAmnezia15Format()
    {
        var source = new Uri("https://deytt.space/sub/key?format=singbox&lang=ru");

        var result = TelegramApiClient.BuildAwgUri(source, "it", generation: "15");

        Assert.Equal("lang=ru&format=amneziawg&server_id=it", result.Query.TrimStart('?'));
    }

    [Fact]
    public void AwgFormatRejectsUnknownGeneration()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            TelegramApiClient.BuildAwgUri(new Uri("https://deytt.space/sub/key"), null, "20"));
    }
}
