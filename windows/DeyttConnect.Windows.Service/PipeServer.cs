using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text.Json;
using DeyttConnect.Protocol;
using Microsoft.Win32.SafeHandles;

namespace DeyttConnect.Windows.Service;

internal static class PipeServer
{
    private const string PipeName = @"\\.\pipe\DEYTT.Connect.v1";
    private const uint PipeAccessDuplex = 0x00000003;
    private const uint FileFlagFirstPipeInstance = 0x00080000;
    private const uint FileFlagOverlapped = 0x40000000;
    private const uint PipeTypeByte = 0;
    private const uint PipeReadModeByte = 0;
    private const uint PipeWait = 0;
    private const uint PipeRejectRemoteClients = 0x00000008;
    private const uint SecurityDescriptorRevision = 1;

    public static async Task RunAsync(string allowedUserSid, EngineController engine,
        CancellationToken cancellationToken)
    {
        _ = new SecurityIdentifier(allowedUserSid);
        var activeClients = new HashSet<Task>();
        var firstPipeInstance = true;
        while (!cancellationToken.IsCancellationRequested)
        {
            activeClients.RemoveWhere(task => task.IsCompleted);
            while (activeClients.Count >= 15)
            {
                await Task.WhenAny(activeClients).ConfigureAwait(false);
                activeClients.RemoveWhere(task => task.IsCompleted);
            }
            var pipe = CreateServerPipe(allowedUserSid, firstPipeInstance);
            firstPipeInstance = false;
            try
            {
                await pipe.WaitForConnectionAsync(cancellationToken).ConfigureAwait(false);
                activeClients.Add(ProcessClientAsync(pipe, engine, cancellationToken));
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                pipe.Dispose();
                break;
            }
            catch (IOException)
            {
                pipe.Dispose();
                // A disconnected or malformed local client gets a fresh pipe instance.
            }
        }

        try
        {
            await Task.WhenAll(activeClients).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
    }

    private static async Task ProcessClientAsync(NamedPipeServerStream pipe, EngineController engine,
        CancellationToken cancellationToken)
    {
        using (pipe)
        {
            try
            {
                await HandleClientAsync(pipe, engine, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
            }
            catch (IOException)
            {
                // A disconnected or malformed local client is discarded without logging input.
            }
            catch (JsonException)
            {
                // Malformed local client input is never echoed to service logs.
            }
            catch (Exception)
            {
                // Service failures are returned as generic status messages, never as raw profiles.
            }
        }
    }

    private static async Task HandleClientAsync(NamedPipeServerStream pipe, EngineController engine,
        CancellationToken cancellationToken)
    {
        var payload = await WindowsPipeProtocol.ReadFrameAsync(pipe, WindowsPipeProtocol.MaximumRequestBytes,
            cancellationToken).ConfigureAwait(false);
        var request = WindowsPipeProtocol.DeserializeRequest(payload);

        ServiceSnapshot result;
        switch (request.Command)
        {
            case WindowsPipeCommand.Status:
                result = engine.Snapshot;
                break;
            case WindowsPipeCommand.Disconnect:
                engine.Stop();
                result = engine.Snapshot;
                break;
            case WindowsPipeCommand.Connect:
                if (request.Profile is null || request.RouteTag is null)
                    throw new JsonException("Missing connection details.");
                result = await engine.ConnectAsync(request.Profile, request.RouteTag, request.AwgConfig,
                    cancellationToken).ConfigureAwait(false);
                break;
            case WindowsPipeCommand.Probe:
                if (request.Profile is null || request.RouteTags is null || request.Method is null ||
                    request.Token is null)
                    throw new JsonException("Missing diagnostic details.");
                if (request.RouteTags.Count > WindowsPipeProtocol.MaximumProbeRouteCount ||
                    request.AwgProfiles?.Count > WindowsPipeProtocol.MaximumAwgProfiles)
                    throw new JsonException("Invalid diagnostic details.");
                result = await engine.ProbeAsync(request.Profile, request.RouteTags,
                    request.AwgProfiles ?? new Dictionary<string, string>(StringComparer.Ordinal),
                    request.Method, request.Token, cancellationToken).ConfigureAwait(false);
                break;
            case WindowsPipeCommand.CancelProbe:
                result = engine.CancelProbe();
                break;
            case WindowsPipeCommand.ProbeProgress:
                result = engine.GetProbeProgress();
                break;
            default:
                throw new JsonException("Unsupported command.");
        }

        var response = new WindowsTunnelSnapshot(result.State, result.Detail, result.RouteTag,
            result.ConnectedAt, result.ProbeResults?.Select(probe => new WindowsRouteProbeResult(
                probe.RouteTag, probe.LatencyMilliseconds, probe.BytesPerSecond, probe.Error,
                probe.Stage, probe.Attempt, probe.BytesReceived, probe.TotalBytes)).ToArray(),
            result.HealthCheckedAt);
        var responsePayload = WindowsPipeProtocol.SerializeResponse(response);
        await WindowsPipeProtocol.WriteFrameAsync(pipe, responsePayload, WindowsPipeProtocol.MaximumResponseBytes,
            cancellationToken).ConfigureAwait(false);
    }

    private static NamedPipeServerStream CreateServerPipe(string allowedUserSid, bool firstInstance)
    {
        var sddl = $"D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;{allowedUserSid})";
        if (!ConvertStringSecurityDescriptorToSecurityDescriptor(sddl,
                SecurityDescriptorRevision, out var descriptor, out _))
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());

        try
        {
            var attributes = new SecurityAttributes
            {
                Length = Marshal.SizeOf<SecurityAttributes>(),
                SecurityDescriptor = descriptor,
                InheritHandle = false,
            };
            var flags = PipeAccessDuplex | FileFlagOverlapped |
                        (firstInstance ? FileFlagFirstPipeInstance : 0u);
            var handle = CreateNamedPipe(PipeName,
                flags,
                PipeTypeByte | PipeReadModeByte | PipeWait | PipeRejectRemoteClients,
                16, 64 * 1024, 64 * 1024, 0, ref attributes);
            if (handle.IsInvalid)
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            return new NamedPipeServerStream(PipeDirection.InOut, isAsync: true,
                isConnected: false, handle);
        }
        finally
        {
            _ = LocalFree(descriptor);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SecurityAttributes
    {
        public int Length;
        public IntPtr SecurityDescriptor;
        [MarshalAs(UnmanagedType.Bool)] public bool InheritHandle;
    }

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptor(
        string stringSecurityDescriptor, uint revision, out IntPtr securityDescriptor,
        out uint securityDescriptorSize);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafePipeHandle CreateNamedPipe(string pipeName, uint openMode,
        uint pipeMode, uint maximumInstances, uint outputBufferSize, uint inputBufferSize,
        uint defaultTimeout, ref SecurityAttributes securityAttributes);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr memory);
}
