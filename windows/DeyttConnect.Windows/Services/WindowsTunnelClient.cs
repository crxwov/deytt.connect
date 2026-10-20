using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using DeyttConnect.Protocol;
using Microsoft.Win32.SafeHandles;

namespace DeyttConnect.Windows.Services;

public sealed class WindowsTunnelClient
{
    private const string PipeName = "DEYTT.Connect.v1";

    public Task<WindowsTunnelSnapshot> GetStatusAsync(CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest { Command = WindowsPipeCommand.Status }, cancellationToken, 5);

    public Task<WindowsTunnelSnapshot> MeasureTunnelLatencyAsync(CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest { Command = WindowsPipeCommand.MeasureTunnelLatency }, cancellationToken, 10);

    public Task<WindowsTunnelSnapshot> ConnectAsync(string profile, string routeTag, string? awgConfig = null,
        IReadOnlyList<string>? customBypassDomains = null,
        IReadOnlyList<string>? customBypassProcesses = null,
        string? splitTunnelMode = null,
        CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest
            {
                Command = WindowsPipeCommand.Connect,
                Profile = profile,
                RouteTag = routeTag,
                AwgConfig = awgConfig,
                CustomBypassDomains = customBypassDomains,
                CustomBypassProcesses = customBypassProcesses,
                SplitTunnelMode = splitTunnelMode
            },
            // Includes bounded DNS bootstrap, AWG resolution and engine check.
            // A cold start must not abandon a still-valid service request.
            cancellationToken, 75);

    public Task<WindowsTunnelSnapshot> DisconnectAsync(CancellationToken cancellationToken = default) =>
        SendAsync(new WindowsPipeRequest { Command = WindowsPipeCommand.Disconnect }, cancellationToken, 15);

    public Task<WindowsTunnelSnapshot> ProbeAsync(string profile, IReadOnlyList<string> routeTags,
        string method, string token, IReadOnlyDictionary<string, string>? awgProfiles = null,
        CancellationToken cancellationToken = default) =>
        // Includes twelve-second latency checks in batches of four, preparation,
        // and two sequential speed attempts (six-second headers + five-second body).
        SendAsync(BuildProbeRequest(profile, routeTags, method, token, awgProfiles), cancellationToken,
            60 + Math.Min(routeTags.Count, WindowsPipeProtocol.MaximumProbeRouteCount) * 26);

    internal static WindowsPipeRequest BuildProbeRequest(string profile, IReadOnlyList<string> routeTags,
        string method, string token, IReadOnlyDictionary<string, string>? awgProfiles)
    {
        var requested = new HashSet<string>(routeTags, StringComparer.Ordinal);
        return new WindowsPipeRequest
        {
            Command = WindowsPipeCommand.Probe, Profile = profile, RouteTags = routeTags, Method = method,
            Token = token,
            AwgProfiles = awgProfiles?.Where(item => requested.Contains(item.Key))
                .ToDictionary(item => item.Key, item => item.Value, StringComparer.Ordinal),
        };
    }

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

        ValidateServerIdentity(pipe);

        var payload = WindowsPipeProtocol.SerializeRequest(request);
        await WindowsPipeProtocol.WriteFrameAsync(pipe, payload, WindowsPipeProtocol.MaximumRequestBytes, timeout.Token)
            .ConfigureAwait(false);
        var response = await WindowsPipeProtocol.ReadFrameAsync(pipe, WindowsPipeProtocol.MaximumResponseBytes,
            timeout.Token).ConfigureAwait(false);
        return WindowsPipeProtocol.DeserializeResponse(response);
    }


    internal static void ValidateServerIdentity(NamedPipeClientStream pipe, bool allowCurrentProcessForTesting = false)
    {
        if (!OperatingSystem.IsWindows())
            return;

        if (!GetNamedPipeServerProcessId(pipe.SafePipeHandle, out var serverPid) || serverPid == 0)
            throw new UnauthorizedAccessException("Unable to verify Windows tunnel service identity.");

        if (allowCurrentProcessForTesting && serverPid == (uint)Environment.ProcessId)
            return;

        var userSid = GetProcessUserSid(serverPid);
        if (!string.Equals(userSid, "S-1-5-18", StringComparison.OrdinalIgnoreCase))
            throw new UnauthorizedAccessException($"The Windows tunnel service named pipe is not owned by LocalSystem (PID: {serverPid}, SID: {userSid ?? "unknown"}).");
    }

    private const uint ProcessQueryLimitedInformation = 0x1000;
    private const uint TokenQuery = 0x0008;

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint serverProcessId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint processAccess, [MarshalAs(UnmanagedType.Bool)] bool bInheritHandle, uint processId);

    [DllImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool OpenProcessToken(IntPtr processHandle, uint desiredAccess, out SafeAccessTokenHandle tokenHandle);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    private static string? GetProcessUserSid(uint processId)
    {
        var processHandle = OpenProcess(ProcessQueryLimitedInformation, false, processId);
        if (processHandle == IntPtr.Zero)
            throw new UnauthorizedAccessException($"Failed to query tunnel service process {processId} (Error {Marshal.GetLastWin32Error()}).");

        try
        {
            if (!OpenProcessToken(processHandle, TokenQuery, out var tokenHandle))
                throw new UnauthorizedAccessException($"Failed to query tunnel service process token for PID {processId} (Error {Marshal.GetLastWin32Error()}).");

            using (tokenHandle)
            {
                using var identity = new WindowsIdentity(tokenHandle.DangerousGetHandle());
                return identity.User?.Value;
            }
        }
        finally
        {
            CloseHandle(processHandle);
        }
    }
}
