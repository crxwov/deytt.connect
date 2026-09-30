using System.Text.Json;
using System.Text.Json.Nodes;
using System.Security.Cryptography;
using System.Text;
using DeyttConnect.Shared;

namespace DeyttConnect.Windows.Service;

internal static class TunnelProfileBuilder
{
    private static readonly (string Code, string Name)[] Countries =
    [
        ("NL", "Нидерланды"),
        ("RU", "Россия"),
        ("DE", "Германия"),
        ("FI", "Финляндия"),
    ];

    private static readonly string[] Protocols = ["VLESS", "TROJAN", "HYSTERIA2"];
    private static readonly HashSet<string> DomainOnlyKeys =
        ["domain", "domain_suffix", "domain_keyword", "domain_regex"];

    public static IReadOnlyList<string> GetTunAddresses(string runtimeProfile)
    {
        var root = JsonNode.Parse(runtimeProfile) as JsonObject
                   ?? throw new InvalidDataException("Windows tunnel profile is invalid.");
        var inbounds = root["inbounds"] as JsonArray;
        if (inbounds is not { Count: 1 } || inbounds[0] is not JsonObject tun ||
            tun["address"] is not JsonArray addresses)
            throw new InvalidDataException("Windows tunnel profile has no TUN address.");

        var result = new List<string>(addresses.Count);
        foreach (var node in addresses)
        {
            if (node is not JsonValue value || !value.TryGetValue<string>(out var address) ||
                string.IsNullOrWhiteSpace(address))
                throw new InvalidDataException("Windows tunnel profile has an invalid TUN address.");
            result.Add(address);
        }
        if (result.Count == 0)
            throw new InvalidDataException("Windows tunnel profile has no TUN address.");
        return result;
    }

    public static string Build(string profileJson, string selectedTag, string? awgConfig = null)
    {
        JsonObject root;
        try
        {
            root = JsonNode.Parse(profileJson) as JsonObject
                   ?? throw new InvalidDataException("Subscription must be an object.");
        }
        catch (JsonException error)
        {
            throw new InvalidDataException("Subscription JSON is invalid.", error);
        }

        var inboundArray = root["inbounds"] as JsonArray
                           ?? throw new InvalidDataException("Subscription has no inbounds.");
        if (inboundArray.Count != 1 || inboundArray[0] is not JsonObject tun ||
            ReadString(tun, "type") != "tun" || tun["address"] is not JsonArray)
            throw new InvalidDataException("Subscription has an unsupported Windows inbound.");
        tun["auto_route"] = true;
        tun["strict_route"] = true;

        var outboundArray = root["outbounds"] as JsonArray
                            ?? throw new InvalidDataException("Subscription has no outbounds.");
        var tags = new HashSet<string>(StringComparer.Ordinal);
        foreach (var node in outboundArray)
        {
            if (node is not JsonObject outbound || ReadString(outbound, "tag") is not { Length: > 0 } tag ||
                !tags.Add(tag))
                throw new InvalidDataException("Subscription has invalid outbound tags.");
        }

        var usedTags = new HashSet<string>(tags, StringComparer.Ordinal);
        var endpointArray = root["endpoints"] as JsonArray;
        if (endpointArray is not null)
        {
            foreach (var node in endpointArray)
            {
                if (node is not JsonObject endpoint || ReadString(endpoint, "tag") is not { Length: > 0 } tag ||
                    !usedTags.Add(tag))
                    throw new InvalidDataException("Subscription has invalid or duplicate endpoint tags.");
            }
        }

        var autoTag = tags.FirstOrDefault(tag => tag.Contains("автоподбор", StringComparison.OrdinalIgnoreCase));
        const string chainTag = "route:RU-DE:CHAIN";
        if (autoTag is null || !tags.Contains(chainTag))
            throw new InvalidDataException("Subscription has no supported automatic or RU-DE route.");

        foreach (var (country, _) in Countries)
        foreach (var protocol in Protocols)
            if (!tags.Contains($"route:{country}:{protocol}"))
                throw new InvalidDataException("Subscription is missing a required route.");

        string engineTag;
        if (awgConfig is not null)
        {
            if (!IsAwgRoute(selectedTag))
                throw new InvalidDataException("An AmneziaWG profile was supplied for a non-AmneziaWG route.");
            engineTag = AwgEndpointTag(selectedTag);
            if (!usedTags.Add(engineTag))
                throw new InvalidDataException("The AmneziaWG endpoint tag conflicts with the subscription.");
            endpointArray = new JsonArray(WindowsAwgProfileParser.Parse(awgConfig, engineTag));
            root["endpoints"] = endpointArray;
            if (root["route"] is JsonObject awgRoute && awgRoute["rules"] is JsonArray awgRules)
            {
                var retainedRules = new JsonArray();
                foreach (var rule in awgRules.OfType<JsonObject>())
                {
                    if (ReadString(rule, "outbound") == "direct" ||
                        ReadString(rule, "action") is "sniff" or "hijack-dns")
                        retainedRules.Add(rule.DeepClone());
                }
                awgRoute["rules"] = retainedRules;
            }
        }
        else
        {
            if (IsAwgRoute(selectedTag))
                throw new InvalidDataException("The selected AmneziaWG profile is unavailable.");
            if (selectedTag != autoTag && selectedTag != chainTag &&
                !Countries.Any(country => Protocols.Any(protocol =>
                    selectedTag == $"route:{country.Code}:{protocol}")))
                throw new InvalidDataException("Selected route is not supported.");
            if (!tags.Contains(selectedTag))
                throw new InvalidDataException("Selected route is unavailable in the subscription.");
            engineTag = selectedTag;
        }

        var route = root["route"] as JsonObject
                    ?? throw new InvalidDataException("Subscription has no route policy.");
        route["final"] = engineTag;
        route["auto_detect_interface"] = true;
        if (root["dns"] is JsonObject dns && dns["servers"] is JsonArray servers)
        {
            foreach (var server in servers.OfType<JsonObject>())
            {
                if (server.ContainsKey("detour") && ReadString(server, "tag") != "local-dns")
                    server["detour"] = engineTag;
            }
        }

        ApplyDomainBypass(root, route);
        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = false });
    }

    public static string BuildProbeProfile(string profileJson, IReadOnlyList<ProbeRouteEndpoint> endpoints)
    {
        if (endpoints.Count is < 1 or > 32 ||
            endpoints.Select(endpoint => endpoint.RouteTag).Distinct(StringComparer.Ordinal).Count() != endpoints.Count ||
            endpoints.Select(endpoint => endpoint.Port).Distinct().Count() != endpoints.Count)
            throw new InvalidDataException("The diagnostic route list is invalid.");

        foreach (var endpoint in endpoints)
        {
            if (endpoint.Port is < 1 or > 65535 || string.IsNullOrWhiteSpace(endpoint.Username) ||
                string.IsNullOrWhiteSpace(endpoint.Password))
                throw new InvalidDataException("A diagnostic proxy endpoint is invalid.");
            _ = Build(profileJson, endpoint.RouteTag, endpoint.AwgConfig);
        }

        var root = JsonNode.Parse(Build(profileJson, endpoints[0].RouteTag, endpoints[0].AwgConfig)) as JsonObject
                   ?? throw new InvalidDataException("Subscription JSON is invalid.");
        var endpointArray = root["endpoints"] as JsonArray ?? new JsonArray();
        var endpointTags = new HashSet<string>(StringComparer.Ordinal);
        foreach (var node in endpointArray.OfType<JsonObject>())
            if (ReadString(node, "tag") is { Length: > 0 } tag)
                endpointTags.Add(tag);
        foreach (var probeEndpoint in endpoints.Where(endpoint => endpoint.AwgConfig is not null))
        {
            var awgTag = AwgEndpointTag(probeEndpoint.RouteTag);
            if (endpointTags.Add(awgTag))
                endpointArray.Add(WindowsAwgProfileParser.Parse(probeEndpoint.AwgConfig, awgTag));
        }
        if (endpointArray.Count > 0)
            root["endpoints"] = endpointArray;
        var inbounds = new JsonArray();
        var inboundRules = new JsonArray();
        foreach (var endpoint in endpoints)
        {
            inbounds.Add(new JsonObject
            {
                ["type"] = "mixed",
                ["tag"] = endpoint.InboundTag,
                ["listen"] = "127.0.0.1",
                ["listen_port"] = endpoint.Port,
                ["users"] = new JsonArray(new JsonObject
                {
                    ["username"] = endpoint.Username,
                    ["password"] = endpoint.Password,
                }),
            });
            inboundRules.Add(new JsonObject
            {
                ["inbound"] = new JsonArray(endpoint.InboundTag),
                ["action"] = "route",
                ["outbound"] = AwgConfigTag(endpoint.RouteTag, endpoint.AwgConfig),
            });
        }
        root["inbounds"] = inbounds;

        var route = root["route"] as JsonObject
                    ?? throw new InvalidDataException("Subscription has no route policy.");
        var rules = new JsonArray();
        foreach (var rule in inboundRules)
            rules.Add(rule?.DeepClone());
        if (route["rules"] is JsonArray originalRules)
        {
            foreach (var rule in originalRules)
                rules.Add(rule?.DeepClone());
        }
        route["rules"] = rules;
        route["final"] = AwgConfigTag(endpoints[0].RouteTag, endpoints[0].AwgConfig);

        if (root["dns"] is JsonObject dns && dns["servers"] is JsonArray servers)
        {
            foreach (var server in servers.OfType<JsonObject>())
                server.Remove("detour");
        }

        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = false });
    }

    private static string AwgConfigTag(string routeTag, string? awgConfig) =>
        awgConfig is null ? routeTag : AwgEndpointTag(routeTag);

    private static bool IsAwgRoute(string routeTag)
    {
        if (routeTag == "awg31")
            return true;
        if (!routeTag.StartsWith("awg31:", StringComparison.Ordinal) || routeTag.Length > 178)
            return false;
        return routeTag.AsSpan("awg31:".Length).IndexOfAnyExcept(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".AsSpan()) < 0;
    }

    private static string AwgEndpointTag(string routeTag)
    {
        if (!IsAwgRoute(routeTag))
            throw new InvalidDataException("The AmneziaWG route identifier is invalid.");
        var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(routeTag))).ToLowerInvariant();
        return "deytt-awg-" + digest[..16];
    }

    private static void ApplyDomainBypass(JsonObject root, JsonObject route)
    {
        var routeRules = route["rules"] as JsonArray
                         ?? throw new InvalidDataException("Subscription route rules are invalid.");
        var suffixes = new HashSet<string>(["ru", "xn--p1ai"], StringComparer.OrdinalIgnoreCase);
        var exact = new HashSet<string>(["cp.cloudflare.com"], StringComparer.OrdinalIgnoreCase);

        foreach (var rule in routeRules.OfType<JsonObject>())
        {
            if (ReadString(rule, "outbound") != "direct" ||
                rule.Select(item => item.Key).Any(key =>
                    !DomainOnlyKeys.Contains(key) && key is not ("outbound" or "action")))
                continue;

            AddDomains(rule["domain_suffix"] as JsonArray, suffixes, removeLeadingDot: true);
            AddDomains(rule["domain"] as JsonArray, exact, removeLeadingDot: false);
        }

        var bypassRules = new JsonArray
        {
            new JsonObject { ["domain_suffix"] = ToJsonArray(suffixes), ["outbound"] = "direct" },
            new JsonObject { ["domain"] = ToJsonArray(exact), ["outbound"] = "direct" },
        };
        var merged = new JsonArray();
        var inserted = false;
        foreach (var rule in routeRules)
        {
            if (!inserted && rule is JsonObject obj &&
                ReadString(obj, "action") is not ("sniff" or "hijack-dns"))
            {
                foreach (var bypass in bypassRules)
                    merged.Add(bypass?.DeepClone());
                inserted = true;
            }
            merged.Add(rule?.DeepClone());
        }
        if (!inserted)
            foreach (var bypass in bypassRules)
                merged.Add(bypass?.DeepClone());
        route["rules"] = merged;

        var dns = root["dns"] as JsonObject
                  ?? throw new InvalidDataException("Subscription has no DNS policy.");
        var dnsRules = new JsonArray
        {
            new JsonObject
            {
                ["domain_suffix"] = ToJsonArray(suffixes),
                ["action"] = "route",
                ["server"] = "local-dns",
            },
            new JsonObject
            {
                ["domain"] = ToJsonArray(exact),
                ["action"] = "route",
                ["server"] = "local-dns",
            },
        };

        if (dns["rules"] is JsonArray originalDnsRules)
        {
            foreach (var node in originalDnsRules)
            {
                if (node is not JsonObject rule || !IsLegacyLocalDomainRule(rule))
                    dnsRules.Add(node?.DeepClone());
            }
        }
        dns["rules"] = dnsRules;
        dns["reverse_mapping"] = true;
    }

    private static bool IsLegacyLocalDomainRule(JsonObject rule) =>
        ReadString(rule, "server") == "local-dns" &&
        rule.Select(item => item.Key).All(key =>
            DomainOnlyKeys.Contains(key) || key is "action" or "server");

    private static void AddDomains(JsonArray? source, HashSet<string> target, bool removeLeadingDot)
    {
        if (source is null)
            return;
        foreach (var node in source)
        {
            if (node is not JsonValue value || !value.TryGetValue<string>(out var text) ||
                string.IsNullOrWhiteSpace(text))
                throw new InvalidDataException("Subscription contains an invalid domain bypass rule.");
            target.Add(removeLeadingDot ? text.TrimStart('.') : text);
        }
    }

    private static JsonArray ToJsonArray(IEnumerable<string> values)
    {
        var array = new JsonArray();
        foreach (var value in values.Order(StringComparer.OrdinalIgnoreCase))
            array.Add(value);
        return array;
    }

    private static string? ReadString(JsonObject source, string name) =>
        source[name] is JsonValue value && value.TryGetValue<string>(out var text) ? text : null;
}
