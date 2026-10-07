using DeyttConnect.Windows.Controls;
using Xunit;
using DeyttConnect.Windows.Services;

namespace DeyttConnect.Windows.Tests;

public sealed class RouteGlobeWebViewItalyTests
{
    [Theory]
    [InlineData("IT", "it")]
    [InlineData("route:IT:VLESS", "it")]
    [InlineData("awg31:it", "it")]
    [InlineData("awg15:it", "it")]
    public void RouteKeyForMapsItalianRoutesToTheItalianAtlasLocation(string routeId, string expected)
    {
        Assert.Equal(expected, RouteGlobeWebView.RouteKeyFor(routeId));
    }
    [Theory]
    [InlineData("awg31:aXQ", "AWG31")]
    [InlineData("awg15:aXQ", "AWG15")]
    public void EncodedAwgIdUsesSubscriptionCountryInsteadOfDecodingGeography(string routeId, string protocol)
    {
        var route = new WindowsRoute(routeId, routeId, "IT", "италия", "🇮🇹", protocol, "amneziawg");
        Assert.Equal("it", RouteGlobeWebView.RouteKeyFor(route.Id, new[] { route }));
    }
}
