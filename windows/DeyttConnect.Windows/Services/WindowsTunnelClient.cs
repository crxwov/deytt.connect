using System.IO.Pipes;
using System.Runtime.InteropServices;
using DeyttConnect.Protocol;
using Microsoft.Win32.SafeHandles;

namespace DeyttConnect.Windows.Services;

public sealed class WindowsTunnelClient
{
    private const string PipeName = "DEYTT.Connect.v1";
    private const string ServiceName = "DEYTTConnectVpn";
    private const uint ScManagerConnect = 0x0001;
    private const uint ServiceQueryConfig = 0x0001;
    private const uint ServiceQueryStatus = 0x0004;
    private const uint ServiceRunning = 0x00000004;
    private const int ServiceStatusProcessInfo = 0;
    private const int ErrorInsufficientBuffer = 122;

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

        // A standard user can query the SCM service PID but cannot open a LocalSystem process token.
        if (!IsVpnServiceProcess(serverPid))
            throw new UnauthorizedAccessException("The Windows tunnel service named pipe is not owned by LocalSystem.");
    }

    private static bool IsVpnServiceProcess(uint serverPid)
    {
        var manager = OpenSCManager(null, null, ScManagerConnect);
        if (manager == IntPtr.Zero)
            return false;

        try
        {
            var service = OpenService(manager, ServiceName, ServiceQueryConfig | ServiceQueryStatus);
            if (service == IntPtr.Zero)
                return false;

            try
            {
                return QueryServiceStatusEx(service, ServiceStatusProcessInfo, out var status,
                           (uint)Marshal.SizeOf<ServiceStatusProcess>(), out _) &&
                       status.CurrentState == ServiceRunning && status.ProcessId == serverPid &&
                       IsLocalSystemService(service);
            }
            finally
            {
                CloseServiceHandle(service);
            }
        }
        finally
        {
            CloseServiceHandle(manager);
        }
    }

    private static bool IsLocalSystemService(IntPtr service)
    {
        if (QueryServiceConfig(service, IntPtr.Zero, 0, out var bytesNeeded) ||
            Marshal.GetLastWin32Error() != ErrorInsufficientBuffer || bytesNeeded == 0)
            return false;

        var buffer = Marshal.AllocHGlobal(checked((int)bytesNeeded));
        try
        {
            if (!QueryServiceConfig(service, buffer, bytesNeeded, out _))
                return false;

            var config = Marshal.PtrToStructure<ServiceConfiguration>(buffer);
            var startName = Marshal.PtrToStringUni(config.ServiceStartName);
            return string.Equals(startName, "LocalSystem", StringComparison.OrdinalIgnoreCase) ||
                   string.Equals(startName, "NT AUTHORITY\\SYSTEM", StringComparison.OrdinalIgnoreCase) ||
                   string.Equals(startName, "SYSTEM", StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ServiceStatusProcess
    {
        public uint ServiceType;
        public uint CurrentState;
        public uint ControlsAccepted;
        public uint Win32ExitCode;
        public uint ServiceSpecificExitCode;
        public uint CheckPoint;
        public uint WaitHint;
        public uint ProcessId;
        public uint ServiceFlags;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ServiceConfiguration
    {
        public uint ServiceType;
        public uint StartType;
        public uint ErrorControl;
        public IntPtr BinaryPathName;
        public IntPtr LoadOrderGroup;
        public uint TagId;
        public IntPtr Dependencies;
        public IntPtr ServiceStartName;
        public IntPtr DisplayName;
    }

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr OpenSCManager(string? machineName, string? databaseName, uint desiredAccess);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr OpenService(IntPtr databaseHandle, string serviceName, uint desiredAccess);

    [DllImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QueryServiceStatusEx(IntPtr serviceHandle, int infoLevel,
        out ServiceStatusProcess serviceStatus, uint bufferSize, out uint bytesNeeded);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QueryServiceConfig(IntPtr serviceHandle, IntPtr serviceConfig,
        uint bufferSize, out uint bytesNeeded);

    [DllImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseServiceHandle(IntPtr handle);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint serverProcessId);
}
