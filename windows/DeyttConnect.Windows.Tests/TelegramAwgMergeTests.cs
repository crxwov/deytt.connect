using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class TelegramAwgMergeTests
{
    [Fact]
    public void MergeInterleavesBothGenerationsAndKeepsBothWhenOneIsEmpty()
    {
        var legacy = new[] { Profile("awg15:a", "15"), Profile("awg15:b", "15") };
        var current = new[] { Profile("awg31:a", "31"), Profile("awg31:b", "31") };

        var merged = TelegramApiClient.MergeAwgProfiles(current, legacy);
        var onlyLegacy = TelegramApiClient.MergeAwgProfiles(Array.Empty<WindowsAwgProfile>(), legacy);

        Assert.Equal(new[] { "awg31:a", "awg15:a", "awg31:b", "awg15:b" }, merged.Select(item => item.RouteId));
        Assert.Equal(new[] { "awg15:a", "awg15:b" }, onlyLegacy.Select(item => item.RouteId));
    }

    [Fact]
    public void MergeCapsBothManifestsAtThirtyTwoProfiles()
    {
        var current = Enumerable.Range(0, 20).Select(index => Profile($"awg31:{index}", "31")).ToArray();
        var legacy = Enumerable.Range(0, 20).Select(index => Profile($"awg15:{index}", "15")).ToArray();

        var merged = TelegramApiClient.MergeAwgProfiles(current, legacy);

        Assert.Equal(32, merged.Count);
        Assert.Equal(16, merged.Count(item => item.Generation == "31"));
        Assert.Equal(16, merged.Count(item => item.Generation == "15"));
    }

    [Fact]
    public void MergeSkipsProfilesThatDoNotFitAndContinuesToLaterSmallerProfiles()
    {
        var current = new[] { Profile("awg31:large", "31", new string('x', 4 * 1024 * 1024 - 1)) };
        var legacy = new[]
        {
            Profile("awg15:too-large-now", "15", new string('y', 2 * 1024 * 1024)),
            Profile("awg15:small-later", "15", "z"),
        };

        var merged = TelegramApiClient.MergeAwgProfiles(current, legacy);

        Assert.Equal(new[] { "awg31:large", "awg15:small-later" }, merged.Select(item => item.RouteId));
        Assert.Equal(4 * 1024 * 1024, merged.Sum(item => System.Text.Encoding.UTF8.GetByteCount(item.Config)));
    }

    private static WindowsAwgProfile Profile(string routeId, string generation, string config = "cfg") =>
        new(routeId, null, routeId, generation, config, generation);
}
