using System.Buffers.Binary;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Win32.SafeHandles;

namespace DeyttConnect.Windows.Service;

internal static class PipeServer
{
    private const string PipeName = @"\\.\pipe\DEYTT.Connect.v1";
    private const int MaximumRequestBytes = 12 * 1024 * 1024;
    private const uint PipeAccessDuplex = 0x00000003;
    private const uint FileFlagFirstPipeInstance = 0x00080000;
    private const uint FileFlagOverlapped = 0x40000000;
    private const uint PipeTypeByte = 0;
    private const uint PipeReadModeByte = 0;
    private const uint PipeWait = 0;
    private const uint PipeRejectRemoteClients = 0x00000008;
    private const uint SecurityDescriptorRevision = 1;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

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
        var header = new byte[sizeof(int)];
        await pipe.ReadExactlyAsync(header, cancellationToken).ConfigureAwait(false);
        var length = BinaryPrimitives.ReadInt32LittleEndian(header);
        if (length is <= 0 or > MaximumRequestBytes)
            throw new IOException("Invalid request length.");

        var payload = new byte[length];
        await pipe.ReadExactlyAsync(payload, cancellationToken).ConfigureAwait(false);
        using var document = JsonDocument.Parse(payload);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object ||
            !root.TryGetProperty("command", out var commandValue) ||
            commandValue.ValueKind != JsonValueKind.String)
            throw new JsonException("Invalid request.");

        ServiceSnapshot result;
        switch (commandValue.GetString())
        {
            case "status":
                result = engine.Snapshot;
                break;
            case "disconnect":
                engine.Stop();
                result = engine.Snapshot;
                break;
            case "connect":
                if (!root.TryGetProperty("profile", out var profileValue) ||
                    profileValue.ValueKind != JsonValueKind.String ||
                    !root.TryGetProperty("routeTag", out var routeValue) ||
                    routeValue.ValueKind != JsonValueKind.String)
                    throw new JsonException("Missing connection details.");
                string? awgConfig = null;
                if (root.TryGetProperty("awgConfig", out var awgValue) && awgValue.ValueKind != JsonValueKind.Null)
                {
                    if (awgValue.ValueKind != JsonValueKind.String)
                        throw new JsonException("Invalid AmneziaWG profile.");
                    awgConfig = awgValue.GetString();
                }
                result = await engine.ConnectAsync(profileValue.GetString()!, routeValue.GetString()!, awgConfig,
                    cancellationToken).ConfigureAwait(false);
                break;
            case "probe":
                if (!root.TryGetProperty("profile", out var probeProfileValue) ||
                    probeProfileValue.ValueKind != JsonValueKind.String ||
                    !root.TryGetProperty("routeTags", out var routesValue) ||
                    routesValue.ValueKind != JsonValueKind.Array ||
                    !root.TryGetProperty("method", out var methodValue) ||
                    methodValue.ValueKind != JsonValueKind.String ||
                    !root.TryGetProperty("token", out var tokenValue) ||
                    tokenValue.ValueKind != JsonValueKind.String)
                    throw new JsonException("Missing diagnostic details.");
                var routeTags = new List<string>();
                foreach (var routeTag in routesValue.EnumerateArray())
                {
                    if (routeTag.ValueKind != JsonValueKind.String || routeTags.Count == 32)
                        throw new JsonException("Invalid diagnostic routes.");
                    routeTags.Add(routeTag.GetString()!);
                }
                var awgProfiles = new Dictionary<string, string>(StringComparer.Ordinal);
                if (root.TryGetProperty("awgProfiles", out var awgProfilesValue) &&
                    awgProfilesValue.ValueKind != JsonValueKind.Null)
                {
                    if (awgProfilesValue.ValueKind != JsonValueKind.Object)
                        throw new JsonException("Invalid AmneziaWG diagnostic profiles.");
                    foreach (var item in awgProfilesValue.EnumerateObject())
                    {
                        if (item.Value.ValueKind != JsonValueKind.String || awgProfiles.Count == 16)
                            throw new JsonException("Invalid AmneziaWG diagnostic profile.");
                        awgProfiles.Add(item.Name, item.Value.GetString()!);
                    }
                }
                result = await engine.ProbeAsync(probeProfileValue.GetString()!, routeTags, awgProfiles,
                    methodValue.GetString()!, tokenValue.GetString()!, cancellationToken).ConfigureAwait(false);
                break;
            case "cancel-probe":
                result = engine.CancelProbe();
                break;
            default:
                throw new JsonException("Unsupported command.");
        }

        await WriteResponseAsync(pipe, result, cancellationToken).ConfigureAwait(false);
    }

    private static async Task WriteResponseAsync(NamedPipeServerStream pipe, ServiceSnapshot result,
        CancellationToken cancellationToken)
    {
        var payload = JsonSerializer.SerializeToUtf8Bytes(result, JsonOptions);
        var header = new byte[sizeof(int)];
        BinaryPrimitives.WriteInt32LittleEndian(header, payload.Length);
        await pipe.WriteAsync(header, cancellationToken).ConfigureAwait(false);
        await pipe.WriteAsync(payload, cancellationToken).ConfigureAwait(false);
        await pipe.FlushAsync(cancellationToken).ConfigureAwait(false);
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
