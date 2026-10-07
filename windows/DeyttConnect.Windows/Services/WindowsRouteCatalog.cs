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
        ("IT", "италия", "🇮🇹"),
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
        if (profile.ValueKind != JsonValueKind.Object)
            throw new InvalidDataException("The subscription root is not an object.");
        if (!profile.TryGetProperty("inbounds", out var inbounds) ||
            inbounds.ValueKind != JsonValueKind.Array)
            throw new InvalidDataException("The subscription has no inbound list.");
        var tun = inbounds.EnumerateArray().FirstOrDefault(item =>
            item.ValueKind == JsonValueKind.Object && ReadString(item, "type") == "tun");
        if (tun.ValueKind != JsonValueKind.Object)
        {
            if (inbounds.GetArrayLength() == 0)
                throw new InvalidDataException("The subscription has an empty inbound list.");
            if (inbounds.EnumerateArray().Any(item =>
                    ReadString(item, "type") is "mixed" or "socks" or "http"))
                throw new InvalidDataException("The subscription has only proxy inbounds.");
            throw new InvalidDataException("The subscription has no TUN inbound.");
        }
        if (!tun.TryGetProperty("address", out var address))
            throw new InvalidDataException("The subscription TUN inbound has no address.");
        if (address.ValueKind != JsonValueKind.Array)
            throw new InvalidDataException("The subscription TUN address is not an array.");

        if (!profile.TryGetProperty("outbounds", out var outbounds) || outbounds.ValueKind != JsonValueKind.Array)
            throw new InvalidDataException("The subscription has no outbounds.");

        var outboundItems = outbounds.EnumerateArray().ToArray();
        var outboundTags = CollectTags(outboundItems);
        var allTags = new HashSet<string>(outboundTags, StringComparer.Ordinal);
        JsonElement[] endpointItems = profile.TryGetProperty("endpoints", out var endpoints) &&
                                      endpoints.ValueKind == JsonValueKind.Array
            ? endpoints.EnumerateArray().ToArray()
            : Array.Empty<JsonElement>();
        AddTags(endpointItems, allTags);

        if (allTags.Count == 0)
            throw new InvalidDataException("The subscription has an empty outbound list.");
        var hasNetworkOutbound = outboundItems.Any(HasNetworkTransport);
        var hasNetworkEndpoint = endpointItems.Any(HasNetworkTransport);
        if (!hasNetworkOutbound && !hasNetworkEndpoint)
            throw new InvalidDataException("The subscription has no network outbounds or endpoints.");

        var autoTag = outboundItems.Select(item => ReadString(item, "tag"))
            .FirstOrDefault(tag => tag?.Contains("автоподбор", StringComparison.OrdinalIgnoreCase) == true);
        var chainTag = "route:RU-DE:CHAIN";
        if (autoTag is null)
            throw new InvalidDataException("The subscription is missing the automatic route.");

        var routes = new List<WindowsRoute>
        {
            new("auto", autoTag, "AUTO", "Автоподбор", "✦", "AUTO", "Автоподбор"),
        };
        if (outboundTags.Contains(chainTag))
            routes.Add(new WindowsRoute("ru-de", chainTag, "RU-DE", "LTE + белые списки", "🇷🇺→🇩🇪", "CHAIN", "RU → DE"));

        foreach (var country in Countries)
        foreach (var protocol in Protocols)
        {
            var tag = $"route:{country.Code}:{protocol.Code}";
            if (outboundTags.Contains(tag))
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
            ReadString(route, "final") is not { Length: > 0 } finalTag || !allTags.Contains(finalTag) ||
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

    private static HashSet<string> CollectTags(IEnumerable<JsonElement> items)
    {
        var tags = new HashSet<string>(StringComparer.Ordinal);
        AddTags(items, tags);
        return tags;
    }

    private static void AddTags(IEnumerable<JsonElement> items, HashSet<string> tags)
    {
        foreach (var item in items)
        {
            if (item.ValueKind != JsonValueKind.Object ||
                ReadString(item, "tag") is not { Length: > 0 } tag || !tags.Add(tag))
                throw new InvalidDataException("The subscription has invalid or duplicate profile tags.");
        }
    }

    private static bool HasNetworkTransport(JsonElement item)
    {
        var type = ReadString(item, "type");
        if (string.IsNullOrWhiteSpace(type))
            return false;

        return type.ToLowerInvariant() is not ("urltest" or "selector" or "direct" or "block" or "dns");
    }
}
