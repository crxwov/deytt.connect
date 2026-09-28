using System.Buffers.Binary;
using System.IO.Pipes;
using System.Text.Json;

namespace DeyttConnect.Windows.Services;

public sealed record WindowsTunnelSnapshot(
    string State,
    string Detail,
    string? RouteTag = null,
    DateTimeOffset? ConnectedAt = null,
    IReadOnlyList<WindowsRouteProbeResult>? ProbeResults = null);

public sealed record WindowsRouteProbeResult(
    string RouteTag,
    long? LatencyMilliseconds,
    long? BytesPerSecond,
    string? Error);

public sealed class WindowsTunnelClient
{
    private const string PipeName = "DEYTT.Connect.v1";
    private const int MaximumResponseBytes = 1024 * 1024;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
    };

    public Task<WindowsTunnelSnapshot> GetStatusAsync(CancellationToken cancellationToken = default) =>
        SendAsync<WindowsTunnelSnapshot>(new { command = "status" }, cancellationToken, 5);

    public Task<WindowsTunnelSnapshot> ConnectAsync(string profile, string routeTag, string? awgConfig = null,
        CancellationToken cancellationToken = default) =>
        SendAsync<WindowsTunnelSnapshot>(new { command = "connect", profile, routeTag, awgConfig }, cancellationToken, 40);

    public Task<WindowsTunnelSnapshot> DisconnectAsync(CancellationToken cancellationToken = default) =>
        SendAsync<WindowsTunnelSnapshot>(new { command = "disconnect" }, cancellationToken, 15);

    public Task<WindowsTunnelSnapshot> ProbeAsync(string profile, IReadOnlyList<string> routeTags,
        string method, string token, IReadOnlyDictionary<string, string>? awgProfiles = null,
        CancellationToken cancellationToken = default) =>
        SendAsync<WindowsTunnelSnapshot>(new { command = "probe", profile, routeTags, method, token, awgProfiles },
            cancellationToken, 180);

    public Task<WindowsTunnelSnapshot> CancelProbeAsync(CancellationToken cancellationToken = default) =>
        SendAsync<WindowsTunnelSnapshot>(new { command = "cancel-probe" }, cancellationToken, 5);

    private static async Task<T> SendAsync<T>(object request, CancellationToken cancellationToken, int timeoutSeconds)
    {
        await using var pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut,
            PipeOptions.Asynchronous);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(timeoutSeconds));
        await pipe.ConnectAsync(timeout.Token).ConfigureAwait(false);

        var payload = JsonSerializer.SerializeToUtf8Bytes(request);
        var header = new byte[sizeof(int)];
        BinaryPrimitives.WriteInt32LittleEndian(header, payload.Length);
        await pipe.WriteAsync(header, timeout.Token).ConfigureAwait(false);
        await pipe.WriteAsync(payload, timeout.Token).ConfigureAwait(false);
        await pipe.FlushAsync(timeout.Token).ConfigureAwait(false);

        await pipe.ReadExactlyAsync(header, timeout.Token).ConfigureAwait(false);
        var length = BinaryPrimitives.ReadInt32LittleEndian(header);
        if (length is <= 0 or > MaximumResponseBytes)
            throw new IOException("The VPN service returned an invalid response size.");

        var response = new byte[length];
        await pipe.ReadExactlyAsync(response, timeout.Token).ConfigureAwait(false);
        return JsonSerializer.Deserialize<T>(response, JsonOptions)
               ?? throw new IOException("The VPN service returned an empty response.");
    }
}
