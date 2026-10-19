using System.Text.Json;
using System.Text.RegularExpressions;

namespace DeyttConnect.Windows.Services;

public sealed record WindowsSplitTunnelConfig(
    IReadOnlyList<string>? CustomDomains = null,
    IReadOnlyList<string>? CustomProcesses = null);

public static class WindowsSplitTunnelStore
{
    private const string ProductFolder = "DEYTT\\Connect";
    private const string SplitTunnelFileName = "split_tunnel.json";
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    private static readonly Regex DomainRegex = new(
        @"^(\*\.)?([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z0-9-]{2,}$",
        RegexOptions.CultureInvariant);

    public static WindowsSplitTunnelConfig Load()
    {
        EnsureWindows();
        var path = SplitTunnelPath;
        if (!File.Exists(path))
            return new WindowsSplitTunnelConfig([], []);

        try
        {
            var info = new FileInfo(path);
            if (info.Length is < 1 or > 64 * 1024)
                return new WindowsSplitTunnelConfig([], []);
            var value = JsonSerializer.Deserialize<WindowsSplitTunnelConfig>(File.ReadAllBytes(path), JsonOptions);
            if (value is null)
                return new WindowsSplitTunnelConfig([], []);

            var domains = (value.CustomDomains ?? [])
                .Select(NormalizeDomain)
                .Where(d => d is not null)
                .Select(d => d!)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(d => d, StringComparer.OrdinalIgnoreCase)
                .ToArray();

            var processes = (value.CustomProcesses ?? [])
                .Select(NormalizeProcess)
                .Where(p => p is not null)
                .Select(p => p!)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(p => p, StringComparer.OrdinalIgnoreCase)
                .ToArray();

            return new WindowsSplitTunnelConfig(domains, processes);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        {
            return new WindowsSplitTunnelConfig([], []);
        }
    }

    public static void Save(WindowsSplitTunnelConfig config)
    {
        EnsureWindows();
        var domains = (config.CustomDomains ?? [])
            .Select(NormalizeDomain)
            .Where(d => d is not null)
            .Select(d => d!)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(d => d, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        var processes = (config.CustomProcesses ?? [])
            .Select(NormalizeProcess)
            .Where(p => p is not null)
            .Select(p => p!)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(p => p, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        var safe = new WindowsSplitTunnelConfig(domains, processes);
        var path = SplitTunnelPath;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporaryPath = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var stream = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write,
                       FileShare.None, 4096, FileOptions.WriteThrough))
            {
                JsonSerializer.Serialize(stream, safe, JsonOptions);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporaryPath, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    public static string? NormalizeDomain(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var domain = raw.Trim().ToLowerInvariant();
        if (domain.StartsWith("http://", StringComparison.Ordinal))
            domain = domain[7..];
        else if (domain.StartsWith("https://", StringComparison.Ordinal))
            domain = domain[8..];
        var slash = domain.IndexOfAny(['/', '?', '#', ':']);
        if (slash >= 0)
            domain = domain[..slash].Trim();
        if (string.IsNullOrWhiteSpace(domain)) return null;
        if (domain.StartsWith('.'))
            domain = "*" + domain;
        if (!DomainRegex.IsMatch(domain)) return null;
        return domain;
    }

    public static string? NormalizeProcess(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var trimmed = raw.Trim();
        var fileName = Path.GetFileName(trimmed);
        if (string.IsNullOrWhiteSpace(fileName)) return null;
        if (!fileName.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) return null;
        return fileName;
    }

    private static string SplitTunnelPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        ProductFolder,
        SplitTunnelFileName);

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Windows split tunneling is available only on Windows.");
    }
}
