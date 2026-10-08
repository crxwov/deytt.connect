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
        ("IT", "Италия"),
    ];

    private static readonly string[] Protocols = ["VLESS", "TROJAN", "HYSTERIA2"];
    private static readonly HashSet<string> DomainOnlyKeys =
        ["domain", "domain_suffix", "domain_keyword", "domain_regex"];

    public static IReadOnlyList<string> GetTunAddresses(string runtimeProfile)
    {
        var root = JsonNode.Parse(runtimeProfile) as JsonObject
                   ?? throw new InvalidDataException("Windows tunnel profile is invalid.");
        var inbounds = root["inbounds"] as JsonArray;
        var tun = inbounds?.OfType<JsonObject>()
            .FirstOrDefault(inbound => ReadString(inbound, "type") == "tun");
        if (tun is null || tun["address"] is not JsonArray addresses)
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

    public static string Build(string profileJson, string selectedTag, string? awgConfig = null,
        string? upstreamInterface = null)
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
        var tunInbound = inboundArray.OfType<JsonObject>()
            .FirstOrDefault(inbound => ReadString(inbound, "type") == "tun");
        if (tunInbound is null || tunInbound["address"] is not JsonArray)
            throw new InvalidDataException("Subscription has an unsupported Windows inbound.");
        // The subscription may also contain proxy inbounds. Windows only needs its TUN;
        // dropping the others avoids exposing subscription listeners from the privileged service.
        var tun = (JsonObject)tunInbound.DeepClone();
        // The shared profile includes an Android-only field that this engine rejects.
        tun.Remove("dns_mode");
        tun["auto_route"] = true;
        tun["strict_route"] = upstreamInterface is null;
        root["inbounds"] = new JsonArray(tun);

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
        if (autoTag is null)
            throw new InvalidDataException("Subscription has no supported automatic route.");

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
        route["auto_detect_interface"] = upstreamInterface is null;
        if (upstreamInterface is null)
            route.Remove("default_interface");
        else
            route["default_interface"] = upstreamInterface;
        if (root["dns"] is JsonObject dns && dns["servers"] is JsonArray servers)
        {
            foreach (var server in servers.OfType<JsonObject>())
            {
                if (server.ContainsKey("detour") && ReadString(server, "tag") != "local-dns")
                    server["detour"] = engineTag;
            }
        }

        ApplyDomainBypass(root, route);
        if (awgConfig is not null)
        {
            var rules = route["rules"] as JsonArray ?? new JsonArray();
            if (route["rules"] is null)
                route["rules"] = rules;
            rules.Add(CreateAwgResolveRule((JsonObject)endpointArray![0]!));
        }
        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = false });
    }

    public static string BuildProbeProfile(string profileJson, IReadOnlyList<ProbeRouteEndpoint> endpoints,
        string? upstreamInterface = null)
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
            _ = Build(profileJson, endpoint.RouteTag, endpoint.AwgConfig, upstreamInterface);
        }

        var root = JsonNode.Parse(Build(profileJson, endpoints[0].RouteTag,
            endpoints[0].AwgConfig, upstreamInterface)) as JsonObject
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
            if (endpoint.AwgConfig is not null)
            {
                var awg = endpointArray.OfType<JsonObject>().Single(node =>
                    ReadString(node, "tag") == AwgEndpointTag(endpoint.RouteTag));
                var resolveRule = CreateAwgResolveRule(awg);
                resolveRule["inbound"] = new JsonArray(endpoint.InboundTag);
                inboundRules.Add(resolveRule);
            }
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
        route["rules"] = inboundRules.DeepClone();
        route["final"] = AwgConfigTag(endpoints[0].RouteTag, endpoints[0].AwgConfig);

        if (root["dns"] is JsonObject dns && dns["servers"] is JsonArray servers)
        {
            foreach (var server in servers.OfType<JsonObject>())
                server.Remove("detour");
        }

        PruneProbeDependencies(root, endpoints);

        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = false });
    }

    private static JsonObject CreateAwgResolveRule(JsonObject endpoint)
    {
        // The embedded AWG netstack has no DNS servers. Resolve before routing a
        // domain destination into it; the DNS transport itself uses numeric addresses.
        var addresses = endpoint["address"] as JsonArray;
        var ipv4 = false;
        var ipv6 = false;
        foreach (var node in addresses ?? new JsonArray())
        {
            if (node is not JsonValue value || !value.TryGetValue<string>(out var cidr) ||
                !System.Net.IPAddress.TryParse(cidr.Split('/')[0], out var address))
                continue;
            ipv4 |= address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork;
            ipv6 |= address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6;
        }
        return new JsonObject
        {
            ["action"] = "resolve",
            ["strategy"] = ipv4 && ipv6 ? "prefer_ipv4" : ipv6 ? "ipv6_only" : "ipv4_only",
        };
    }

    internal static void PruneProbeDependencies(JsonObject root, IReadOnlyList<ProbeRouteEndpoint> probes)
    {
        var outbounds = root["outbounds"] as JsonArray
                        ?? throw new InvalidDataException("Subscription has no outbounds.");
        var outboundByTag = outbounds.OfType<JsonObject>()
            .ToDictionary(node => ReadString(node, "tag")!, StringComparer.Ordinal);
        var endpoints = root["endpoints"] as JsonArray ?? new JsonArray();
        var endpointByTag = endpoints.OfType<JsonObject>()
            .ToDictionary(node => ReadString(node, "tag")!, StringComparer.Ordinal);
        var requiredOutboundTags = new HashSet<string>(StringComparer.Ordinal);
        var requiredEndpointTags = new HashSet<string>(StringComparer.Ordinal);
        var pendingTags = new Queue<string>();

        foreach (var probe in probes)
            pendingTags.Enqueue(AwgConfigTag(probe.RouteTag, probe.AwgConfig));

        while (pendingTags.TryDequeue(out var tag))
        {
            if (outboundByTag.TryGetValue(tag, out var outbound))
            {
                if (!requiredOutboundTags.Add(tag))
                    continue;
                EnqueueTag(outbound["detour"]);
                EnqueueTag(outbound["default"]);
                EnqueueTags(outbound["outbounds"]);
            }
            else if (endpointByTag.TryGetValue(tag, out var endpoint))
            {
                if (!requiredEndpointTags.Add(tag))
                    continue;
                EnqueueTag(endpoint["detour"]);
            }
            else
            {
                throw new InvalidDataException("A diagnostic route dependency is unavailable.");
            }
        }

        root["outbounds"] = new JsonArray(outbounds.OfType<JsonObject>()
            .Where(node => requiredOutboundTags.Contains(ReadString(node, "tag")!))
            .Select(node => (JsonNode?)node.DeepClone()).ToArray());
        if (requiredEndpointTags.Count == 0)
            root.Remove("endpoints");
        else
            root["endpoints"] = new JsonArray(endpoints.OfType<JsonObject>()
                .Where(node => requiredEndpointTags.Contains(ReadString(node, "tag")!))
                .Select(node => (JsonNode?)node.DeepClone()).ToArray());

        PruneProbeDns(root, requiredOutboundTags, requiredEndpointTags, outboundByTag, endpointByTag);

        void EnqueueTags(JsonNode? value)
        {
            if (value is not JsonArray members)
                return;
            foreach (var member in members)
                EnqueueTag(member);
        }

        void EnqueueTag(JsonNode? value)
        {
            if (value is JsonValue jsonValue && jsonValue.TryGetValue<string>(out var dependency) &&
                !string.IsNullOrWhiteSpace(dependency))
                pendingTags.Enqueue(dependency);
        }
    }

    private static void PruneProbeDns(JsonObject root, HashSet<string> outboundTags,
        HashSet<string> endpointTags, IReadOnlyDictionary<string, JsonObject> outboundByTag,
        IReadOnlyDictionary<string, JsonObject> endpointByTag)
    {
        if (root["dns"] is not JsonObject dns || dns["servers"] is not JsonArray servers)
            return;

        var requiredDnsTags = new HashSet<string>(StringComparer.Ordinal);
        AddDnsTag(dns["final"]);
        if (dns["rules"] is JsonArray rules)
        {
            foreach (var rule in rules.OfType<JsonObject>())
                AddDnsTag(rule["server"]);
        }
        if (root["route"] is JsonObject route)
            AddDnsTag(route["default_domain_resolver"]);
        foreach (var tag in outboundTags)
            AddDnsTag(outboundByTag[tag]["domain_resolver"]);
        foreach (var tag in endpointTags)
            AddDnsTag(endpointByTag[tag]["domain_resolver"]);

        if (requiredDnsTags.Count == 0)
            return;
        var serversByTag = servers.OfType<JsonObject>()
            .Where(server => ReadString(server, "tag") is { Length: > 0 })
            .GroupBy(server => ReadString(server, "tag")!, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.First(), StringComparer.Ordinal);
        if (requiredDnsTags.Any(tag => !serversByTag.ContainsKey(tag)))
            return;

        var pendingDnsTags = new Queue<string>(requiredDnsTags);
        while (pendingDnsTags.TryDequeue(out var tag))
        {
            if (!serversByTag.TryGetValue(tag, out var server))
                return;
            if (server["domain_resolver"] is JsonValue resolver &&
                resolver.TryGetValue<string>(out var resolverTag) && requiredDnsTags.Add(resolverTag))
                pendingDnsTags.Enqueue(resolverTag);
        }

        dns["servers"] = new JsonArray(servers.OfType<JsonObject>()
            .Where(server => ReadString(server, "tag") is not { Length: > 0 } tag ||
                             requiredDnsTags.Contains(tag))
            .Select(server => (JsonNode?)server.DeepClone()).ToArray());

        void AddDnsTag(JsonNode? value)
        {
            if (value is JsonValue jsonValue && jsonValue.TryGetValue<string>(out var tag) &&
                !string.IsNullOrWhiteSpace(tag))
                requiredDnsTags.Add(tag);
        }
    }

    private static string AwgConfigTag(string routeTag, string? awgConfig) =>
        awgConfig is null ? routeTag : AwgEndpointTag(routeTag);

    private static bool IsAwgRoute(string routeTag)
    {
        if (routeTag is "awg15" or "awg31")
            return true;
        var prefixLength = routeTag.StartsWith("awg15:", StringComparison.Ordinal) ? 6 :
            routeTag.StartsWith("awg31:", StringComparison.Ordinal) ? 6 : 0;
        if (prefixLength == 0 || routeTag.Length > 178 || routeTag.Length == prefixLength)
            return false;
        return routeTag.AsSpan(prefixLength).IndexOfAnyExcept(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".AsSpan()) < 0;
    }

    internal static string AwgEndpointTag(string routeTag)
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
