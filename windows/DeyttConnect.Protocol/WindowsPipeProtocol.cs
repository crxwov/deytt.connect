using System.Buffers.Binary;
using System.Net;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Net.Sockets;

namespace DeyttConnect.Protocol;

public enum WindowsPipeCommand
{
    Status,
    Disconnect,
    Connect,
    Probe,
    CancelProbe,
    ProbeProgress,
}

public sealed record WindowsPipeRequest
{
    [JsonPropertyName("command")]
    [JsonConverter(typeof(WindowsPipeCommandJsonConverter))]
    public required WindowsPipeCommand Command { get; init; }

    [JsonPropertyName("profile")]
    public string? Profile { get; init; }

    [JsonPropertyName("routeTag")]
    public string? RouteTag { get; init; }

    [JsonPropertyName("awgConfig")]
    public string? AwgConfig { get; init; }

    [JsonPropertyName("routeTags")]
    public IReadOnlyList<string>? RouteTags { get; init; }

    [JsonPropertyName("method")]
    public string? Method { get; init; }

    [JsonPropertyName("token")]
    public string? Token { get; init; }

    [JsonPropertyName("awgProfiles")]
    public IReadOnlyDictionary<string, string>? AwgProfiles { get; init; }
}

public sealed record WindowsTunnelSnapshot(
    string State,
    string Detail,
    string? RouteTag = null,
    DateTimeOffset? ConnectedAt = null,
    IReadOnlyList<WindowsRouteProbeResult>? ProbeResults = null,
    DateTimeOffset? HealthCheckedAt = null);

public sealed record WindowsRouteProbeResult(
    string RouteTag,
    long? LatencyMilliseconds,
    long? BytesPerSecond,
    string? Error,
    string? Stage = null,
    int? Attempt = null,
    long? BytesReceived = null,
    long? TotalBytes = null);

public static class WindowsPipeProtocol
{
    // The 2 MiB profile plus 4 MiB of AWG configs can expand several-fold when
    // JSON escapes Unicode and control characters. Keep enough bounded headroom.
    public const int MaximumRequestBytes = 40 * 1024 * 1024;
    public const int MaximumResponseBytes = 1024 * 1024;
    public const int MaximumProbeRouteCount = 32;
    public const int MaximumAwgProfiles = 32;

    private static readonly JsonSerializerOptions RequestOptions = new()
    {
        Converters = { new WindowsPipeCommandJsonConverter() },
    };

    private static readonly JsonSerializerOptions ResponseOptions = new()
    {
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNameCaseInsensitive = true,
    };

    public static byte[] SerializeRequest(WindowsPipeRequest request) =>
        request.Command switch
        {
            WindowsPipeCommand.Status => Serialize(new { command = "status" }, RequestOptions, MaximumRequestBytes),
            WindowsPipeCommand.Disconnect => Serialize(new { command = "disconnect" }, RequestOptions, MaximumRequestBytes),
            WindowsPipeCommand.Connect => Serialize(new
            {
                command = "connect", profile = request.Profile, routeTag = request.RouteTag,
                awgConfig = request.AwgConfig,
            }, RequestOptions, MaximumRequestBytes),
            WindowsPipeCommand.Probe => Serialize(new
            {
                command = "probe", profile = request.Profile, routeTags = request.RouteTags,
                method = request.Method, token = request.Token, awgProfiles = request.AwgProfiles,
            }, RequestOptions, MaximumRequestBytes),
            WindowsPipeCommand.CancelProbe => Serialize(new { command = "cancel-probe" }, RequestOptions,
                MaximumRequestBytes),
            WindowsPipeCommand.ProbeProgress => Serialize(new { command = "probe-progress" }, RequestOptions,
                MaximumRequestBytes),
            _ => throw new JsonException("Unsupported pipe command."),
        };

    public static WindowsPipeRequest DeserializeRequest(ReadOnlySpan<byte> payload) =>
        Deserialize<WindowsPipeRequest>(payload, RequestOptions, MaximumRequestBytes);

    public static byte[] SerializeResponse(WindowsTunnelSnapshot response) =>
        Serialize(response, ResponseOptions, MaximumResponseBytes);

    public static WindowsTunnelSnapshot DeserializeResponse(ReadOnlySpan<byte> payload) =>
        Deserialize<WindowsTunnelSnapshot>(payload, ResponseOptions, MaximumResponseBytes);

    public static async Task WriteFrameAsync(Stream stream, ReadOnlyMemory<byte> payload,
        int maximumBytes, CancellationToken cancellationToken = default)
    {
        ValidateLength(payload.Length, maximumBytes);
        var header = new byte[sizeof(int)];
        BinaryPrimitives.WriteInt32LittleEndian(header, payload.Length);
        await stream.WriteAsync(header, cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync(payload, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    public static async Task<byte[]> ReadFrameAsync(Stream stream, int maximumBytes,
        CancellationToken cancellationToken = default)
    {
        var header = new byte[sizeof(int)];
        await stream.ReadExactlyAsync(header, cancellationToken).ConfigureAwait(false);
        var length = BinaryPrimitives.ReadInt32LittleEndian(header);
        ValidateLength(length, maximumBytes);
        var payload = new byte[length];
        await stream.ReadExactlyAsync(payload, cancellationToken).ConfigureAwait(false);
        return payload;
    }

    private static byte[] Serialize<T>(T value, JsonSerializerOptions options, int maximumBytes)
    {
        var payload = JsonSerializer.SerializeToUtf8Bytes(value, options);
        ValidateLength(payload.Length, maximumBytes);
        return payload;
    }

    private static T Deserialize<T>(ReadOnlySpan<byte> payload, JsonSerializerOptions options, int maximumBytes)
    {
        ValidateLength(payload.Length, maximumBytes);
        return JsonSerializer.Deserialize<T>(payload, options)
               ?? throw new JsonException("The pipe payload was empty.");
    }

    private static void ValidateLength(int length, int maximumBytes)
    {
        if (length <= 0 || length > maximumBytes)
            throw new InvalidDataException("Invalid pipe frame length.");
    }
}

public static class WindowsTunnelHealth
{
    public static readonly TimeSpan MaximumFreshness = TimeSpan.FromSeconds(120);
    public const int FailuresBeforeDegraded = 2;

    public static WindowsTunnelHealthTransition ApplyCanaryResult(bool passed, int consecutiveFailures,
        bool tunnelRouteProven = false)
    {
        // A host-side HTTPS canary does not identify its selected NIC or prove that
        // strict_route sent it through TUN. Keep health unknown until route-bound.
        if (passed)
            return new WindowsTunnelHealthTransition(tunnelRouteProven ? "connected" : "unknown", 0);

        var nextFailures = passed ? 0 : consecutiveFailures + 1;
        var state = nextFailures >= FailuresBeforeDegraded ? "degraded" : "health_checking";
        return new WindowsTunnelHealthTransition(state, nextFailures);
    }

    public static bool IsTunnelActive(string state) =>
        state is "connected" or "health_checking" or "degraded" or "unknown" or "starting" or "checking";

    public static bool IsFreshlyConnected(WindowsTunnelSnapshot snapshot, DateTimeOffset now) =>
        snapshot.State == "connected" && snapshot.HealthCheckedAt is { } checkedAt &&
        checkedAt <= now && now - checkedAt <= MaximumFreshness;
}

public static class WindowsTunnelCanaryRoute
{
    private const int InterfaceIndexLength = sizeof(uint);

    public static WindowsTunnelCanaryInterface? SelectInterface(IEnumerable<string> configuredAddresses,
        IEnumerable<WindowsTunnelCanaryInterface> interfaces)
    {
        var available = interfaces.Where(item => item.InterfaceIndex > 0).ToArray();
        foreach (var configured in configuredAddresses)
        {
            var addressText = configured.Split('/', 2)[0];
            if (!IPAddress.TryParse(addressText, out var address) ||
                IPAddress.Any.Equals(address) || IPAddress.IPv6Any.Equals(address) ||
                IPAddress.IsLoopback(address))
                continue;
            foreach (var tunnelInterface in available)
                if (tunnelInterface.Address.Equals(address))
                    return tunnelInterface;
        }
        return null;
    }

    public static byte[] EncodeInterfaceIndex(IPAddress address, int interfaceIndex)
    {
        if (interfaceIndex <= 0)
            throw new ArgumentOutOfRangeException(nameof(interfaceIndex));

        var bytes = new byte[InterfaceIndexLength];
        if (address.AddressFamily == AddressFamily.InterNetwork)
            BinaryPrimitives.WriteUInt32BigEndian(bytes, checked((uint)interfaceIndex));
        else if (address.AddressFamily == AddressFamily.InterNetworkV6)
            BinaryPrimitives.WriteUInt32LittleEndian(bytes, checked((uint)interfaceIndex));
        else
            throw new ArgumentException("Unsupported tunnel address family.", nameof(address));
        return bytes;
    }
}

public sealed record WindowsTunnelCanaryInterface(IPAddress Address, int InterfaceIndex);

public sealed record WindowsTunnelHealthTransition(string State, int ConsecutiveFailures);

public sealed class WindowsPipeCommandJsonConverter : JsonConverter<WindowsPipeCommand>
{
    public override WindowsPipeCommand Read(ref Utf8JsonReader reader, Type typeToConvert,
        JsonSerializerOptions options)
    {
        if (reader.TokenType != JsonTokenType.String)
            throw new JsonException("Invalid pipe command.");

        return reader.GetString() switch
        {
            "status" => WindowsPipeCommand.Status,
            "disconnect" => WindowsPipeCommand.Disconnect,
            "connect" => WindowsPipeCommand.Connect,
            "probe" => WindowsPipeCommand.Probe,
            "cancel-probe" => WindowsPipeCommand.CancelProbe,
            "probe-progress" => WindowsPipeCommand.ProbeProgress,
            _ => throw new JsonException("Unsupported pipe command."),
        };
    }

    public override void Write(Utf8JsonWriter writer, WindowsPipeCommand value, JsonSerializerOptions options) =>
        writer.WriteStringValue(value switch
        {
            WindowsPipeCommand.Status => "status",
            WindowsPipeCommand.Disconnect => "disconnect",
            WindowsPipeCommand.Connect => "connect",
            WindowsPipeCommand.Probe => "probe",
            WindowsPipeCommand.CancelProbe => "cancel-probe",
            WindowsPipeCommand.ProbeProgress => "probe-progress",
            _ => throw new JsonException("Unsupported pipe command."),
        });
}
