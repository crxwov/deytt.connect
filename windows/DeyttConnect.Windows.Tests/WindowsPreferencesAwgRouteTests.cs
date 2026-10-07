using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsPreferencesAwgRouteTests
{
    [Theory]
    [InlineData("awg15")]
    [InlineData("awg15:aXQ")]
    [InlineData("awg31")]
    [InlineData("awg31:aXQ")]
    public void PreferencesAcceptBothAwgGenerations(string routeId)
    {
        Assert.True(WindowsPreferencesStore.IsKnownRoute(routeId));
    }

    [Theory]
    [InlineData("awg20:aXQ")]
    [InlineData("awg15:unsafe/id")]
    [InlineData("awg31:")]
    public void PreferencesRejectUnknownOrMalformedAwgRouteIds(string routeId)
    {
        Assert.False(WindowsPreferencesStore.IsKnownRoute(routeId));
    }
}
