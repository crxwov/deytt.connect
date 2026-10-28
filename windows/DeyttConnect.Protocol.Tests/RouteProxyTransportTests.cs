using System.Net;
using System.Net.Sockets;
using System.Text;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class RouteProxyTransportTests
{
    [Fact]
    public async Task FreshClientsAuthenticateBeforeSendingRequestsWithoutLeakingCredentialsToDestination()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var endpoint = new ProbeRouteEndpoint("route:NL:VLESS", "test-inbound", port, "fixture-user", "fixture-pass", null);
        var requests = new List<string>();
        var server = Task.Run(async () =>
        {
            for (var index = 0; index < 6; index++)
            {
                using var client = await listener.AcceptTcpClientAsync(timeout.Token);
                var stream = client.GetStream();
                await AuthenticateAsync(stream, endpoint, true, timeout.Token);
                var connect = await ReadAsync(stream, 4, timeout.Token);
                Assert.Equal(new byte[] { 5, 1, 0, 3 }, connect);
                var length = (await ReadAsync(stream, 1, timeout.Token))[0];
                Assert.Equal("destination.invalid", Encoding.ASCII.GetString(await ReadAsync(stream, length, timeout.Token)));
                Assert.Equal(new byte[] { 0, 80 }, await ReadAsync(stream, 2, timeout.Token));
                await stream.WriteAsync(new byte[] { 5, 0, 0, 1, 127, 0, 0, 1, 0, 0 }, timeout.Token);
                requests.Add(await ReadHeadersAsync(stream, timeout.Token));
                await stream.WriteAsync(Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nready"), timeout.Token);
            }
        }, timeout.Token);

        for (var index = 0; index < 6; index++)
        {
            using var handler = RouteProxyTransport.CreateHandler(endpoint);
            using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(5) };
            Assert.Equal("ready", await client.GetStringAsync("http://destination.invalid/fixture", timeout.Token));
        }
        await server;
        Assert.Equal(6, requests.Count);
        Assert.All(requests, request =>
        {
            Assert.StartsWith("GET /fixture HTTP/1.1", request);
            Assert.DoesNotContain("Authorization", request, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("fixture-pass", request);
        });
    }

    [Fact]
    public async Task RejectedCredentialsCannotSendDestinationRequest()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var endpoint = new ProbeRouteEndpoint("awg31", "test-inbound", ((IPEndPoint)listener.LocalEndpoint).Port,
            "fixture-user", "fixture-pass", null);
        var server = Task.Run(async () =>
        {
            using var client = await listener.AcceptTcpClientAsync(timeout.Token);
            await AuthenticateAsync(client.GetStream(), endpoint, false, timeout.Token);
            var trailing = new byte[1];
            Assert.Equal(0, await client.GetStream().ReadAsync(trailing, timeout.Token));
        }, timeout.Token);
        using var handler = RouteProxyTransport.CreateHandler(endpoint);
        using var http = new HttpClient(handler);
        await Assert.ThrowsAsync<HttpRequestException>(() => http.GetAsync("http://destination.invalid/fixture", timeout.Token));
        await server;
    }

    private static async Task AuthenticateAsync(NetworkStream stream, ProbeRouteEndpoint endpoint, bool accepted, CancellationToken ct)
    {
        var greeting = await ReadAsync(stream, 2, ct);
        Assert.Equal(5, greeting[0]);
        Assert.Contains((byte)2, await ReadAsync(stream, greeting[1], ct));
        await stream.WriteAsync(new byte[] { 5, 2 }, ct);
        var auth = await ReadAsync(stream, 2, ct);
        Assert.Equal(1, auth[0]);
        Assert.Equal(endpoint.Username, Encoding.UTF8.GetString(await ReadAsync(stream, auth[1], ct)));
        var length = (await ReadAsync(stream, 1, ct))[0];
        Assert.Equal(endpoint.Password, Encoding.UTF8.GetString(await ReadAsync(stream, length, ct)));
        await stream.WriteAsync(new byte[] { 1, accepted ? (byte)0 : (byte)1 }, ct);
    }

    private static async Task<byte[]> ReadAsync(NetworkStream stream, int count, CancellationToken ct)
    {
        var bytes = new byte[count];
        await stream.ReadExactlyAsync(bytes, ct);
        return bytes;
    }

    private static async Task<string> ReadHeadersAsync(NetworkStream stream, CancellationToken ct)
    {
        var bytes = new List<byte>();
        while (bytes.Count < 16 * 1024)
        {
            bytes.Add((await ReadAsync(stream, 1, ct))[0]);
            if (bytes.Count >= 4 && bytes.TakeLast(4).SequenceEqual(new byte[] { 13, 10, 13, 10 }))
                return Encoding.ASCII.GetString(bytes.ToArray());
        }
        throw new InvalidDataException("Fixture header limit exceeded.");
    }
}
