using System.Text.Json;
using System.Text.RegularExpressions;

namespace DeyttConnect.Windows.Services;

public sealed record SplitTunnelPreset(
    string Id,
    string Name,
    string Mode = "bypass",
    IReadOnlyList<string>? CustomDomains = null,
    IReadOnlyList<string>? CustomProcesses = null);

public sealed record WindowsSplitTunnelConfig(
    IReadOnlyList<string>? CustomDomains = null,
    IReadOnlyList<string>? CustomProcesses = null,
    string? Mode = "bypass",
    string? ActivePresetId = null,
    IReadOnlyList<SplitTunnelPreset>? Presets = null)
{
    public const string ModeBypass = "bypass";
    public const string ModeVpnOnly = "vpn_only";

    public bool IsVpnOnly => string.Equals(Mode, ModeVpnOnly, StringComparison.OrdinalIgnoreCase);
}

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
        {
            var defaultPreset = new SplitTunnelPreset("default", "Основной", WindowsSplitTunnelConfig.ModeBypass, [], []);
            return new WindowsSplitTunnelConfig([], [], WindowsSplitTunnelConfig.ModeBypass, "default", [defaultPreset]);
        }

        try
        {
            var info = new FileInfo(path);
            if (info.Length is < 1 or > 64 * 1024)
            {
                var defaultPreset = new SplitTunnelPreset("default", "Основной", WindowsSplitTunnelConfig.ModeBypass, [], []);
                return new WindowsSplitTunnelConfig([], [], WindowsSplitTunnelConfig.ModeBypass, "default", [defaultPreset]);
            }

            var value = JsonSerializer.Deserialize<WindowsSplitTunnelConfig>(File.ReadAllBytes(path), JsonOptions);
            if (value is null)
            {
                var defaultPreset = new SplitTunnelPreset("default", "Основной", WindowsSplitTunnelConfig.ModeBypass, [], []);
                return new WindowsSplitTunnelConfig([], [], WindowsSplitTunnelConfig.ModeBypass, "default", [defaultPreset]);
            }

            var rootDomains = NormalizeDomainList(value.CustomDomains);
            var rootProcesses = NormalizeProcessList(value.CustomProcesses);
            var rootMode = NormalizeMode(value.Mode);

            var presetsList = new List<SplitTunnelPreset>();
            if (value.Presets is { Count: > 0 })
            {
                foreach (var p in value.Presets)
                {
                    if (string.IsNullOrWhiteSpace(p.Id) || string.IsNullOrWhiteSpace(p.Name)) continue;
                    presetsList.Add(new SplitTunnelPreset(
                        p.Id.Trim(),
                        p.Name.Trim(),
                        NormalizeMode(p.Mode),
                        NormalizeDomainList(p.CustomDomains),
                        NormalizeProcessList(p.CustomProcesses)));
                }
            }

            if (presetsList.Count == 0)
            {
                presetsList.Add(new SplitTunnelPreset(
                    "default",
                    "Основной",
                    rootMode,
                    rootDomains,
                    rootProcesses));
            }

            var activeId = value.ActivePresetId;
            var activePreset = presetsList.FirstOrDefault(p => string.Equals(p.Id, activeId, StringComparison.OrdinalIgnoreCase))
                               ?? presetsList[0];

            return new WindowsSplitTunnelConfig(
                activePreset.CustomDomains,
                activePreset.CustomProcesses,
                activePreset.Mode,
                activePreset.Id,
                presetsList);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        {
            var defaultPreset = new SplitTunnelPreset("default", "Основной", WindowsSplitTunnelConfig.ModeBypass, [], []);
            return new WindowsSplitTunnelConfig([], [], WindowsSplitTunnelConfig.ModeBypass, "default", [defaultPreset]);
        }
    }

    public static void Save(WindowsSplitTunnelConfig config)
    {
        EnsureWindows();

        var currentDomains = NormalizeDomainList(config.CustomDomains);
        var currentProcesses = NormalizeProcessList(config.CustomProcesses);
        var currentMode = NormalizeMode(config.Mode);

        var presetsList = new List<SplitTunnelPreset>();
        if (config.Presets is { Count: > 0 })
        {
            foreach (var p in config.Presets)
            {
                if (string.IsNullOrWhiteSpace(p.Id) || string.IsNullOrWhiteSpace(p.Name)) continue;
                presetsList.Add(new SplitTunnelPreset(
                    p.Id.Trim(),
                    p.Name.Trim(),
                    NormalizeMode(p.Mode),
                    NormalizeDomainList(p.CustomDomains),
                    NormalizeProcessList(p.CustomProcesses)));
            }
        }

        var activeId = config.ActivePresetId;
        if (string.IsNullOrWhiteSpace(activeId) && presetsList.Count > 0)
            activeId = presetsList[0].Id;

        if (presetsList.Count == 0)
        {
            activeId = "default";
            presetsList.Add(new SplitTunnelPreset(
                activeId,
                "Основной",
                currentMode,
                currentDomains,
                currentProcesses));
        }
        else
        {
            var activeIndex = presetsList.FindIndex(p => string.Equals(p.Id, activeId, StringComparison.OrdinalIgnoreCase));
            if (activeIndex >= 0)
            {
                var existing = presetsList[activeIndex];
                presetsList[activeIndex] = existing with
                {
                    Mode = currentMode,
                    CustomDomains = currentDomains,
                    CustomProcesses = currentProcesses
                };
            }
            else
            {
                activeId = presetsList[0].Id;
            }
        }

        var safe = new WindowsSplitTunnelConfig(currentDomains, currentProcesses, currentMode, activeId, presetsList);
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

    private static IReadOnlyList<string> NormalizeDomainList(IEnumerable<string>? raw) =>
        (raw ?? [])
            .Select(NormalizeDomain)
            .Where(d => d is not null)
            .Select(d => d!)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(d => d, StringComparer.OrdinalIgnoreCase)
            .ToArray();

    private static IReadOnlyList<string> NormalizeProcessList(IEnumerable<string>? raw) =>
        (raw ?? [])
            .Select(NormalizeProcess)
            .Where(p => p is not null)
            .Select(p => p!)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(p => p, StringComparer.OrdinalIgnoreCase)
            .ToArray();

    private static string NormalizeMode(string? mode) =>
        string.Equals(mode, WindowsSplitTunnelConfig.ModeVpnOnly, StringComparison.OrdinalIgnoreCase)
            ? WindowsSplitTunnelConfig.ModeVpnOnly
            : WindowsSplitTunnelConfig.ModeBypass;

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
        var trimmed = raw.Trim().Trim('"', '\'');
        var fileName = Path.GetFileName(trimmed);
        if (string.IsNullOrWhiteSpace(fileName))
            fileName = trimmed;
        var invalid = Path.GetInvalidFileNameChars();
        if (fileName.IndexOfAny(invalid) >= 0)
        {
            var clean = new string(fileName.Where(c => !invalid.Contains(c)).ToArray()).Trim();
            if (string.IsNullOrWhiteSpace(clean)) return null;
            fileName = clean;
        }
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
