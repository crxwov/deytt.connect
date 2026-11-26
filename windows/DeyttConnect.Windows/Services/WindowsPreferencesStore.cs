using System.Text.Json;

namespace DeyttConnect.Windows.Services;

public sealed record CustomMapPoint(string City, string Country, double Latitude, double Longitude)
{
    internal bool IsValid() => !string.IsNullOrWhiteSpace(City) && City.Length <= 80 && !City.Any(char.IsControl) &&
        Country is { Length: 2 } && Country.All(char.IsAsciiLetter) &&
        Latitude is >= -90 and <= 90 && Longitude is >= -180 and <= 180;
}

public sealed record WindowsPreferences(
    string Language = "ru",
    string SelectedRoute = "auto",
    string ProbeMethod = "HEAD",
    bool MapRegionEnabled = false,
    bool ReduceMotion = false,
    bool MapRegionConsentGranted = false,
    bool MapRegionConsentAsked = false,
    string ThemePreference = "dark",
    string WindowChromePreference = "deytt",
    CustomMapPoint? MapPoint = null);

public static class WindowsPreferencesStore
{
    private const string ProductFolder = "DEYTT\\Connect";
    private const string PreferencesFileName = "preferences.json";
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public static WindowsPreferences Load()
    {
        var path = PreferencesPath;
        if (!File.Exists(path))
            return new WindowsPreferences();

        try
        {
            var info = new FileInfo(path);
            if (info.Length is < 1 or > 16 * 1024)
                return new WindowsPreferences();
            var value = JsonSerializer.Deserialize<WindowsPreferences>(File.ReadAllBytes(path), JsonOptions);
            if (value is null)
                return new WindowsPreferences();
            return new WindowsPreferences(
                value.Language is "ru" or "en" ? value.Language : "ru",
                IsKnownRoute(value.SelectedRoute) ? value.SelectedRoute : "auto",
                value.ProbeMethod is "HEAD" or "GET" ? value.ProbeMethod : "HEAD",
                value.MapRegionEnabled && value.MapRegionConsentGranted,
                value.ReduceMotion,
                value.MapRegionConsentGranted,
                value.MapRegionConsentAsked,
                value.ThemePreference is "system" or "light" or "dark" or "legacy" ? value.ThemePreference : "system",
                value.WindowChromePreference is "deytt" or "system" ? value.WindowChromePreference : "deytt",
                value.MapPoint?.IsValid() == true ? value.MapPoint : null);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        {
            return new WindowsPreferences();
        }
    }

    public static void Save(WindowsPreferences preferences)
    {
        var safe = new WindowsPreferences(
            preferences.Language is "ru" or "en" ? preferences.Language : "ru",
            IsKnownRoute(preferences.SelectedRoute) ? preferences.SelectedRoute : "auto",
            preferences.ProbeMethod is "HEAD" or "GET" ? preferences.ProbeMethod : "HEAD",
            preferences.MapRegionEnabled,
            preferences.ReduceMotion,
            preferences.MapRegionConsentGranted,
            preferences.MapRegionConsentAsked,
            preferences.ThemePreference is "system" or "light" or "dark" or "legacy" ? preferences.ThemePreference : "system",
            preferences.WindowChromePreference is "deytt" or "system" ? preferences.WindowChromePreference : "deytt",
            preferences.MapPoint?.IsValid() == true ? preferences.MapPoint : null);
        var path = PreferencesPath;
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

    private static string PreferencesPath
    {
        get
        {
            var basePath = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var properPath = Path.Combine(basePath, "DEYTT", "Connect", PreferencesFileName);
            if (!File.Exists(properPath) && OperatingSystem.IsLinux())
            {
                var legacyPath = Path.Combine(basePath, "DEYTT\\Connect", PreferencesFileName);
                if (File.Exists(legacyPath))
                {
                    try
                    {
                        Directory.CreateDirectory(Path.GetDirectoryName(properPath)!);
                        File.Copy(legacyPath, properPath, overwrite: true);
                    }
                    catch { }
                }
            }
            return properPath;
        }
    }

    internal static bool IsKnownRoute(string route) => route is "auto" or "ru-de" or "route:DE:LTE" ||
        System.Text.RegularExpressions.Regex.IsMatch(route,
            "^route:(NL|RU|DE|FI|IT):(VLESS|TROJAN|HYSTERIA2|TUIC)$",
            System.Text.RegularExpressions.RegexOptions.CultureInvariant) ||
        route == "awg31" ||
        System.Text.RegularExpressions.Regex.IsMatch(route,
            "^awg31:[A-Za-z0-9_-]{1,172}$",
            System.Text.RegularExpressions.RegexOptions.CultureInvariant);

    private static void EnsureWindows()
    {
    }
}
