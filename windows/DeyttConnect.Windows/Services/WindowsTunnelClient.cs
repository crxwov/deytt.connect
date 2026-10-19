using System.IO.Pipes;
using System.Security.Principal;
using DeyttConnect.Protocol;

namespace DeyttConnect.Windows.Services;

public sealed class WindowsTunnelClient
{
    private const string PipeName = "DEYTT.Connect.v1";
    private readonly string _pipeName;

    public WindowsTunnelClient(string pipeName = PipeName)
    {
        _pipeName = string.IsNullOrWhiteSpace(pipeName) ? PipeName : pipeName;
    }

    public Task<WindowsTunnelSnapshot> GetStatusAsync(CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest { Command = WindowsPipeCommand.Status }, cancellationToken, 5);

    public Task<WindowsTunnelSnapshot> ConnectAsync(string profile, string routeTag, string? awgConfig = null,
        IReadOnlyList<string>? customBypassDomains = null,
        IReadOnlyList<string>? customBypassProcesses = null,
        CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest
            {
                Command = WindowsPipeCommand.Connect, Profile = profile, RouteTag = routeTag,
                AwgConfig = awgConfig,
                CustomBypassDomains = customBypassDomains,
                CustomBypassProcesses = customBypassProcesses,
            },
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

    private async Task<WindowsTunnelSnapshot> SendAsync(WindowsPipeRequest request,
        CancellationToken cancellationToken, int timeoutSeconds)
    {
        await using var pipe = new NamedPipeClientStream(".", _pipeName, PipeDirection.InOut,
            PipeOptions.Asynchronous, TokenImpersonationLevel.Anonymous);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(timeoutSeconds));
        await pipe.ConnectAsync(timeout.Token).ConfigureAwait(false);
        if (OperatingSystem.IsWindows() &&
            string.Equals(_pipeName, PipeName, StringComparison.OrdinalIgnoreCase))
        {
            var ownerSid = PipesAclExtensions.GetAccessControl(pipe).GetOwner(typeof(SecurityIdentifier)) as SecurityIdentifier;
            if (ownerSid is null ||
                (!ownerSid.IsWellKnown(WellKnownSidType.LocalSystemSid) &&
                 !ownerSid.IsWellKnown(WellKnownSidType.BuiltinAdministratorsSid)))
            {
                throw new UnauthorizedAccessException("Служба DeyttConnect запущена от недоверенной учётной записи.");
            }
        }

        var payload = WindowsPipeProtocol.SerializeRequest(request);
        await WindowsPipeProtocol.WriteFrameAsync(pipe, payload, WindowsPipeProtocol.MaximumRequestBytes, timeout.Token)
            .ConfigureAwait(false);
        var response = await WindowsPipeProtocol.ReadFrameAsync(pipe, WindowsPipeProtocol.MaximumResponseBytes,
            timeout.Token).ConfigureAwait(false);
        return WindowsPipeProtocol.DeserializeResponse(response);
    }
}
