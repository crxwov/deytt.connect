using System.Net;

namespace DeyttConnect.Windows.Service;

internal static class RouteProxyTransport
{
    internal static HttpClientHandler CreateHandler(ProbeRouteEndpoint endpoint) => new()
    {
        UseProxy = true,
        // mixed inbound supports authenticated SOCKS5. Unlike HTTP CONNECT,
        // credentials are exchanged before opening the destination connection;
        // no HTTP407/connection-close retry race can distort cold measurements.
        Proxy = new WebProxy($"socks5://127.0.0.1:{endpoint.Port}")
        {
            Credentials = new NetworkCredential(endpoint.Username, endpoint.Password),
        },
        AllowAutoRedirect = false,
    };
}
