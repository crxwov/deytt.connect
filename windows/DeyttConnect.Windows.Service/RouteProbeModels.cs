namespace DeyttConnect.Windows.Service;

internal sealed record ProbeRouteEndpoint(
    string RouteTag,
    string InboundTag,
    int Port,
    string Username,
    string Password,
    string? AwgConfig);

internal sealed record ServiceProbeResult(
    string RouteTag,
    long? LatencyMilliseconds,
    long? BytesPerSecond,
    string? Error,
    string? Stage = null,
    int? Attempt = null,
    long? BytesReceived = null,
    long? TotalBytes = null);
