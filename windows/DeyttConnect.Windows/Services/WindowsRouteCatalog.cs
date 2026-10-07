using System.Text.Json;
using DeyttConnect.Shared;
using System.Text.RegularExpressions;

namespace DeyttConnect.Windows.Services;

public sealed record WindowsRoute(
    string Id,
    string Tag,
    string CountryCode,
    string CountryName,
    string Flag,
    string Protocol,
    string ProtocolName,
    string? ProfileId = null,
    string? ProfileName = null);

public static class WindowsRouteCatalog
{
    private static readonly (string Code, string Name, string Flag)[] Countries =
    [
        ("NL", "Нидерланды", "🇳🇱"),
        ("RU", "Россия", "🇷🇺"),
        ("DE", "Германия", "🇩🇪"),
        ("FI", "Финляндия", "🇫🇮"),
        ("IT", "Италия", "🇮🇹"),
    ];

    private static readonly (string Code, string Name)[] Protocols =
    [
        ("VLESS", "VLESS"),
        ("TROJAN", "Trojan"),
        ("HYSTERIA2", "Hysteria 2"),
    ];

    private static readonly Regex RegionPrefix = new("^(NL|DE|RU|FI|IT)(?:$|[-_\\s])",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    public static IReadOnlyList<WindowsRoute> Parse(
        JsonElement profile,
        IReadOnlyList<WindowsAwgProfile>? awgProfiles = null)
    {
        if (profile.ValueKind != JsonValueKind.Object ||
            !profile.TryGetProperty("inbounds", out var inbounds) ||
            inbounds.ValueKind != JsonValueKind.Array ||
            !inbounds.EnumerateArray().Any(item =>
                item.ValueKind == JsonValueKind.Object &&
                ReadString(item, "type") == "tun" &&
                item.TryGetProperty("address", out var address) && address.ValueKind == JsonValueKind.Array))
            throw new InvalidDataException("The subscription has no supported TUN inbound.");

        if (!profile.TryGetProperty("outbounds", out var outbounds) || outbounds.ValueKind != JsonValueKind.Array)
            throw new InvalidDataException("The subscription has no outbounds.");

        var tags = new HashSet<string>(StringComparer.Ordinal);
        foreach (var outbound in outbounds.EnumerateArray())
        {
            if (outbound.ValueKind != JsonValueKind.Object ||
                ReadString(outbound, "tag") is not { Length: > 0 } tag || !tags.Add(tag))
                throw new InvalidDataException("The subscription has invalid or duplicate outbound tags.");
        }

        var autoTag = tags.FirstOrDefault(tag => tag.Contains("автоподбор", StringComparison.OrdinalIgnoreCase));
        var chainTag = "route:RU-DE:CHAIN";
        if (autoTag is null || !tags.Contains(chainTag))
            throw new InvalidDataException("The subscription is missing the automatic or RU-DE route.");

        var routes = new List<WindowsRoute>
        {
            new("auto", autoTag, "AUTO", "Автоподбор", "✦", "AUTO", "Автоподбор"),
            new("ru-de", chainTag, "RU-DE", "LTE + белые списки", "🇷🇺→🇩🇪", "CHAIN", "RU → DE"),
        };

        foreach (var country in Countries)
        foreach (var protocol in Protocols)
        {
            var tag = $"route:{country.Code}:{protocol.Code}";
            if (country.Code != "IT" && !tags.Contains(tag))
                throw new InvalidDataException("The subscription is missing a required route.");
            if (tags.Contains(tag))
                routes.Add(new WindowsRoute(tag, tag, country.Code, country.Name, country.Flag,
                    protocol.Code, protocol.Name));
        }

        foreach (var awgProfile in (awgProfiles ?? []).Take(16))
        {
            if (awgProfile.RouteId != "awg31" &&
                !Regex.IsMatch(awgProfile.RouteId, "^awg31:[A-Za-z0-9_-]{1,172}$", RegexOptions.CultureInvariant))
                continue;
            try
            {
                _ = WindowsAwgProfileParser.Parse(awgProfile.Config, "deytt-validation");
            }
            catch (InvalidDataException)
            {
                continue;
            }

            var region = RegionPrefix.Match(awgProfile.ShortLabel.Trim());
            var countryCode = region.Success ? region.Groups[1].Value.ToUpperInvariant() : "AWG_UNKNOWN";
            var country = Countries.FirstOrDefault(item => item.Code == countryCode);
            routes.Add(new WindowsRoute(
                awgProfile.RouteId,
                awgProfile.RouteId,
                countryCode,
                country.Name ?? "Регион не указан",
                country.Flag ?? "◉",
                "AWG31",
                "AmneziaWG",
                awgProfile.RouteId,
                awgProfile.Label));
        }

        if (!profile.TryGetProperty("route", out var route) ||
            ReadString(route, "final") is not { Length: > 0 } finalTag || !tags.Contains(finalTag) ||
            !route.TryGetProperty("rules", out var rules) || rules.ValueKind != JsonValueKind.Array ||
            !rules.EnumerateArray().Any(rule =>
                rule.ValueKind == JsonValueKind.Object &&
                ReadString(rule, "protocol") == "dns" &&
                ReadString(rule, "action") == "hijack-dns"))
            throw new InvalidDataException("The subscription has an unsupported route or DNS policy.");

        return routes;
    }

    private static string? ReadString(JsonElement element, string propertyName) =>
        element.ValueKind == JsonValueKind.Object &&
        element.TryGetProperty(propertyName, out var value) &&
        value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
}
