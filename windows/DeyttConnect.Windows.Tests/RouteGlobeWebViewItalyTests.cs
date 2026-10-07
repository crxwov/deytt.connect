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
    public void RouteKeyForMapsItalianRoutesToTheItalianAtlasLocation(string routeId, string expected)
    {
        Assert.Equal(expected, RouteGlobeWebView.RouteKeyFor(routeId));
    }
    [Fact]
    public void EncodedAwgIdUsesSubscriptionCountryInsteadOfDecodingGeography()
    {
        var route = new WindowsRoute("awg31:aXQ", "awg31:aXQ", "IT", "италия", "🇮🇹", "AWG31", "amneziawg");
        Assert.Equal("it", RouteGlobeWebView.RouteKeyFor(route.Id, new[] { route }));
    }}
