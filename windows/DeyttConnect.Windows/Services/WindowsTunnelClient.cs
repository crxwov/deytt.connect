using System.IO.Pipes;
using DeyttConnect.Protocol;

namespace DeyttConnect.Windows.Services;

public sealed class WindowsTunnelClient
{
    private const string PipeName = "DEYTT.Connect.v1";

    public Task<WindowsTunnelSnapshot> GetStatusAsync(CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest { Command = WindowsPipeCommand.Status }, cancellationToken, 5);

    public Task<WindowsTunnelSnapshot> ConnectAsync(string profile, string routeTag, string? awgConfig = null,
        CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest
            { Command = WindowsPipeCommand.Connect, Profile = profile, RouteTag = routeTag, AwgConfig = awgConfig },
            cancellationToken, 40);

    public Task<WindowsTunnelSnapshot> DisconnectAsync(CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest { Command = WindowsPipeCommand.Disconnect }, cancellationToken, 15);

    public Task<WindowsTunnelSnapshot> ProbeAsync(string profile, IReadOnlyList<string> routeTags,
        string method, string token, IReadOnlyDictionary<string, string>? awgProfiles = null,
        CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest
            { Command = WindowsPipeCommand.Probe, Profile = profile, RouteTags = routeTags, Method = method,
                Token = token, AwgProfiles = awgProfiles }, cancellationToken, 180);

    public Task<WindowsTunnelSnapshot> CancelProbeAsync(CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest { Command = WindowsPipeCommand.CancelProbe }, cancellationToken, 5);

    public Task<WindowsTunnelSnapshot> GetProbeProgressAsync(CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest { Command = WindowsPipeCommand.ProbeProgress }, cancellationToken, 5);

    private static async Task<WindowsTunnelSnapshot> SendAsync(WindowsPipeRequest request,
        CancellationToken cancellationToken, int timeoutSeconds)
    {
        await using var pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut,
            PipeOptions.Asynchronous);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(timeoutSeconds));
        await pipe.ConnectAsync(timeout.Token).ConfigureAwait(false);

        var payload = WindowsPipeProtocol.SerializeRequest(request);
        await WindowsPipeProtocol.WriteFrameAsync(pipe, payload, WindowsPipeProtocol.MaximumRequestBytes, timeout.Token)
            .ConfigureAwait(false);
        var response = await WindowsPipeProtocol.ReadFrameAsync(pipe, WindowsPipeProtocol.MaximumResponseBytes,
            timeout.Token).ConfigureAwait(false);
        return WindowsPipeProtocol.DeserializeResponse(response);
    }
}
