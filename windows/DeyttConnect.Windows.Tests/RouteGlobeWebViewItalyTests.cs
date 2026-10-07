using DeyttConnect.Windows.Controls;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class RouteGlobeWebViewItalyTests
{
    [Theory]
    [InlineData("IT", "it")]
    [InlineData("route:IT:VLESS", "it")]
    [InlineData("awg31:it", "it")]
    public void RouteKeyForMapsItalianRoutesToTheItalianAtlasLocation(string routeId, string expected)
    {
        Assert.Equal(expected, RouteGlobeWebView.RouteKeyFor(routeId));
    }
}
