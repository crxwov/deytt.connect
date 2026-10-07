using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Text;
using System.Text.RegularExpressions;

namespace DeyttConnect.Windows.Services;

internal sealed record WindowsExternalVpnObservation(
    string Name,
    IReadOnlyList<string> Signals,
    IReadOnlyList<string> RunningServiceNames);

internal enum WindowsExternalVpnStopResult
{
    Stopped,
    Cancelled,
    Failed,
}

internal static partial class WindowsExternalVpnDetector
{
    private sealed record Provider(string Name, string ServiceName, string[] AdapterTokens);

    private static readonly Provider[] Providers =
    [
        new("Happ", "HappService", ["happ", "sing-box"]),
        new("WireGuard", "WireGuardManager", ["wireguard", "wintun"]),
        new("OpenVPN", "OpenVPNService", ["openvpn", "tap-windows"]),
        new("OpenVPN Connect", "OpenVPNServiceInteractive", ["openvpn connect"]),
        new("NordVPN", "nordvpn-service", ["nordlynx", "nordvpn"]),
        new("Mullvad", "MullvadVPN", ["mullvad"]),
        new("Proton VPN", "ProtonVPNService", ["protonvpn"]),
        new("Windscribe", "WindscribeService", ["windscribe"]),
    ];

    private static readonly HashSet<string> StoppableServices = Providers
        .Select(provider => provider.ServiceName)
        .ToHashSet(StringComparer.OrdinalIgnoreCase);

    private static readonly HttpClient PublicIpClient = new(new SocketsHttpHandler
    {
        UseProxy = true,
        AllowAutoRedirect = false,
        ConnectTimeout = TimeSpan.FromSeconds(3),
    }) { Timeout = TimeSpan.FromSeconds(4) };

    public static async Task<IReadOnlyList<WindowsExternalVpnObservation>> DetectAsync(
        CancellationToken cancellationToken = default)
    {
        if (!OperatingSystem.IsWindows())
            return [];

        var serviceStates = await Task.WhenAll(Providers.Select(async provider =>
            (provider.ServiceName,
                IsRunning: await IsServiceRunningAsync(provider.ServiceName, cancellationToken).ConfigureAwait(false))))
            .ConfigureAwait(false);
        var runningServices = serviceStates.Where(item => item.IsRunning)
            .Select(item => item.ServiceName).ToHashSet(StringComparer.OrdinalIgnoreCase);

        var results = new Dictionary<string, (HashSet<string> Signals, HashSet<string> Services)>(
            StringComparer.OrdinalIgnoreCase);

        foreach (var provider in Providers)
        {
            if (runningServices.Contains(provider.ServiceName))
                Add(provider.Name, $"Служба {provider.ServiceName}: активна", provider.ServiceName);
        }

        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (adapter.OperationalStatus != OperationalStatus.Up ||
                adapter.Name.Contains("deytt", StringComparison.OrdinalIgnoreCase) ||
                adapter.Description.Contains("deytt", StringComparison.OrdinalIgnoreCase))
                continue;

            var identity = $"{adapter.Name} {adapter.Description}";
            var provider = Providers.FirstOrDefault(candidate => candidate.AdapterTokens.Any(token =>
                identity.Contains(token, StringComparison.OrdinalIgnoreCase)));
            var isTunnel = adapter.NetworkInterfaceType is NetworkInterfaceType.Tunnel or NetworkInterfaceType.Ppp;
            if (provider is null && !isTunnel)
                continue;

            var name = provider?.Name ?? "Другой VPN-туннель";
            Add(name, $"Сетевой интерфейс: {adapter.Name}",
                provider?.ServiceName);
        }

        return results
            .OrderBy(entry => entry.Key, StringComparer.CurrentCultureIgnoreCase)
            .Select(entry => new WindowsExternalVpnObservation(entry.Key,
                entry.Value.Signals.Order(StringComparer.CurrentCultureIgnoreCase).ToArray(),
                entry.Value.Services.Order(StringComparer.OrdinalIgnoreCase).ToArray()))
            .ToArray();

        void Add(string name, string signal, string? serviceName)
        {
            if (!results.TryGetValue(name, out var entry))
                entry = (new HashSet<string>(StringComparer.CurrentCultureIgnoreCase),
                    new HashSet<string>(StringComparer.OrdinalIgnoreCase));
            entry.Signals.Add(signal);
            if (serviceName is not null)
                entry.Services.Add(serviceName);
            results[name] = entry;
        }
    }

    public static async Task<WindowsExternalVpnStopResult> StopDetectedServicesAsync(
        IEnumerable<string> serviceNames,
        CancellationToken cancellationToken = default)
    {
        if (!OperatingSystem.IsWindows())
            return WindowsExternalVpnStopResult.Failed;

        var safeNames = serviceNames
            .Where(StoppableServices.Contains)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
        if (safeNames.Length == 0)
            return WindowsExternalVpnStopResult.Failed;

        // Names are restricted to the fixed provider allowlist above before being
        // embedded in an elevated script. No process names or adapter strings execute.
        var literalNames = string.Join(",", safeNames.Select(name => $"'{name}'"));
        var script = "$ErrorActionPreference='Stop'; " +
                     $"foreach($name in @({literalNames})) {{ " +
                     "$service=Get-Service -Name $name -ErrorAction Stop; " +
                     "if($service.Status -eq 'Running') { " +
                     "Stop-Service -Name $name -ErrorAction Stop; " +
                     "$service.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(20)) } }";
        var encodedScript = Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
        var executable = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),
            "WindowsPowerShell", "v1.0", "powershell.exe");
        var start = new ProcessStartInfo(executable)
        {
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        start.ArgumentList.Add("-NoProfile");
        start.ArgumentList.Add("-NonInteractive");
        start.ArgumentList.Add("-EncodedCommand");
        start.ArgumentList.Add(encodedScript);

        try
        {
            using var process = Process.Start(start);
            if (process is null)
                return WindowsExternalVpnStopResult.Failed;
            await process.WaitForExitAsync(cancellationToken).WaitAsync(TimeSpan.FromSeconds(35), cancellationToken)
                .ConfigureAwait(false);
            return process.ExitCode == 0
                ? WindowsExternalVpnStopResult.Stopped
                : WindowsExternalVpnStopResult.Failed;
        }
        catch (System.ComponentModel.Win32Exception error) when (error.NativeErrorCode == 1223)
        {
            return WindowsExternalVpnStopResult.Cancelled;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            return WindowsExternalVpnStopResult.Failed;
        }
    }

    public static async Task<bool> CloseHappAppAsync(CancellationToken cancellationToken = default)
    {
        if (!OperatingSystem.IsWindows())
            return false;

        var closed = true;
        foreach (var process in Process.GetProcessesByName("Happ"))
        {
            using (process)
            {
                try
                {
                    if (process.HasExited)
                        continue;
                    if (!process.CloseMainWindow())
                    {
                        closed = false;
                        continue;
                    }
                    await process.WaitForExitAsync(cancellationToken)
                        .WaitAsync(TimeSpan.FromSeconds(4), cancellationToken).ConfigureAwait(false);
                }
                catch (Exception error) when (error is InvalidOperationException or
                                               System.ComponentModel.Win32Exception or TimeoutException)
                {
                    closed = false;
                }
            }
        }
        return closed;
    }

    public static async Task<string?> GetCurrentPublicIpAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            var value = (await PublicIpClient.GetStringAsync("https://api.ipify.org", cancellationToken)
                .ConfigureAwait(false)).Trim();
            return IPAddress.TryParse(value, out _) ? value : null;
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or IOException)
        {
            return null;
        }
    }

    private static async Task<bool> IsServiceRunningAsync(string serviceName, CancellationToken cancellationToken)
    {
        try
        {
            var scPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "sc.exe");
            using var process = new Process
            {
                StartInfo = new ProcessStartInfo(scPath)
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                },
            };
            process.StartInfo.ArgumentList.Add("query");
            process.StartInfo.ArgumentList.Add(serviceName);
            if (!process.Start())
                return false;
            var output = await process.StandardOutput.ReadToEndAsync(cancellationToken).ConfigureAwait(false);
            await process.WaitForExitAsync(cancellationToken).WaitAsync(TimeSpan.FromSeconds(2), cancellationToken)
                .ConfigureAwait(false);
            return process.ExitCode == 0 && RunningServiceStateRegex().IsMatch(output);
        }
        catch (Exception error) when (error is InvalidOperationException or System.ComponentModel.Win32Exception or
                                      OperationCanceledException or TimeoutException or IOException)
        {
            if (error is OperationCanceledException && cancellationToken.IsCancellationRequested)
                throw;
            return false;
        }
    }

    [GeneratedRegex(@"STATE\s*:\s*4\s+RUNNING", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex RunningServiceStateRegex();
}
