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

    // The shared subscription includes Russia. Windows automatic selection is
    // intentionally limited to the supported European, single-country routes.
    private static void ConfigureEuropeanAutoRoute(JsonArray outbounds, string autoTag)
    {
        var byTag = outbounds.OfType<JsonObject>().ToDictionary(
            outbound => ReadString(outbound, "tag")!, StringComparer.Ordinal);
        var automatic = byTag[autoTag];
        if (ReadString(automatic, "type") is not ("urltest" or "selector"))
            throw new InvalidDataException("Automatic route must select European servers.");

        string[] countries = ["NL", "DE", "FI", "IT"];
        var candidates = countries.SelectMany(country => Protocols.Select(protocol => $"route:{country}:{protocol}"))
            .Where(tag => byTag.TryGetValue(tag, out var outbound) &&
                          ReadString(outbound, "type") is "vless" or "trojan" or "hysteria2" &&
                          string.IsNullOrEmpty(ReadString(outbound, "detour")))
            .ToArray();
        if (candidates.Length == 0)
            throw new InvalidDataException("В подписке нет европейских серверов для автоподбора. Обновите доступ или выберите маршрут вручную.");

        automatic["outbounds"] = new JsonArray(candidates.Select(tag => (JsonNode?)JsonValue.Create(tag)).ToArray());
        if (ReadString(automatic, "default") is { } defaultTag && !candidates.Contains(defaultTag, StringComparer.Ordinal))
            automatic.Remove("default");
    }

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
        string? upstreamInterface = null,
        IReadOnlyList<string>? customBypassDomains = null,
        IReadOnlyList<string>? customBypassProcesses = null,
        string? splitTunnelMode = null, bool pruneUnusedDependencies = true)
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

        // Runtime diagnostics belong to the service's bounded pipe readers.
        // Subscription logging can suppress startup errors or write arbitrary
        // paths as SYSTEM; always emit errors to the engine's console instead.
        root["log"] = new JsonObject
        {
            ["disabled"] = false,
            ["level"] = "error",
        };

        // Privileged service security boundaries: user profiles must never control
        // engine cache file paths, certificates, experimental features, or local services.
        root.Remove("experimental");
        root.Remove("certificate");
        root.Remove("services");

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
        // A subscription's generic name can collide with other sing-box clients.
        tun["interface_name"] = "deytt-connect";
        if (tun["address"] is JsonArray addresses &&
            !addresses.Any(node => node is JsonValue val && val.TryGetValue<string>(out var text) && text.Contains(':')))
        {
            addresses.Add("fdfe:dcba:9876::1/126");
        }
        tun["auto_route"] = true;
        tun["strict_route"] = true;
        tun["stack"] = "mixed";
        root["inbounds"] = new JsonArray(tun);

        var outboundArray = root["outbounds"] as JsonArray
                            ?? throw new InvalidDataException("Subscription has no outbounds.");
        var tags = new HashSet<string>(StringComparer.Ordinal);
        foreach (var node in outboundArray)
        {
            if (node is not JsonObject outbound || ReadString(outbound, "tag") is not { Length: > 0 } tag ||
                !tags.Add(tag))
                throw new InvalidDataException("Subscription has invalid outbound tags.");

            SanitizeHysteriaOutbound(outbound);
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
            if (selectedTag == autoTag)
                ConfigureEuropeanAutoRoute(outboundArray, autoTag);
            engineTag = selectedTag;
        }

        var isVpnOnly = string.Equals(splitTunnelMode, "vpn_only", StringComparison.OrdinalIgnoreCase);

        if (!tags.Contains("direct"))
        {
            outboundArray.Add(new JsonObject { ["type"] = "direct", ["tag"] = "direct" });
            tags.Add("direct");
        }

        var route = root["route"] as JsonObject
                    ?? throw new InvalidDataException("Subscription has no route policy.");
        route["final"] = isVpnOnly ? "direct" : engineTag;
        route["auto_detect_interface"] = upstreamInterface is null;
        if (upstreamInterface is null)
            route.Remove("default_interface");
        else
            route["default_interface"] = upstreamInterface;
        route["find_process"] = true;
        if (root["dns"] is JsonObject dns && dns["servers"] is JsonArray servers)
        {
            foreach (var server in servers.OfType<JsonObject>())
            {
                if (server.ContainsKey("detour") && ReadString(server, "tag") != "local-dns")
                    server["detour"] = engineTag;
            }
        }

        ApplyDomainBypass(root, route, customBypassDomains, customBypassProcesses, isVpnOnly, engineTag);
        if (awgConfig is not null)
        {
            var rules = route["rules"] as JsonArray ?? new JsonArray();
            if (route["rules"] is null)
                route["rules"] = rules;
            rules.Add(CreateAwgResolveRule((JsonObject)endpointArray![0]!));
        }
        // The core starts unused endpoints too; their bootstrap failures must not abort the selected route.
        if (pruneUnusedDependencies)
            PruneRouteDependencies(root, [engineTag]);
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
            endpoints[0].AwgConfig, upstreamInterface, pruneUnusedDependencies: false)) as JsonObject
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
        => PruneRouteDependencies(root, probes.Select(probe => AwgConfigTag(probe.RouteTag, probe.AwgConfig)));

    private static void PruneRouteDependencies(JsonObject root, IEnumerable<string> selectedTags)
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

        foreach (var tag in selectedTags)
            pendingTags.Enqueue(tag);
        EnqueueTag(root["route"]?["final"]);
        EnqueueRuleTargets(root["route"]?["rules"]);
        if (root["dns"]?["servers"] is JsonArray dnsServers)
            foreach (var server in dnsServers.OfType<JsonObject>())
                EnqueueTag(server["detour"]);

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
                throw new InvalidDataException("A selected route dependency is unavailable.");
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

        PruneDnsDependencies(root, requiredOutboundTags, requiredEndpointTags, outboundByTag, endpointByTag);

        void EnqueueRuleTargets(JsonNode? value)
        {
            if (value is not JsonArray rules)
                return;
            foreach (var rule in rules.OfType<JsonObject>())
            {
                EnqueueTag(rule["outbound"]);
                EnqueueRuleTargets(rule["rules"]);
            }
        }

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

    private static void PruneDnsDependencies(JsonObject root, HashSet<string> outboundTags,
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
            if (ReadDnsTag(server["domain_resolver"]) is { } resolverTag && requiredDnsTags.Add(resolverTag))
                pendingDnsTags.Enqueue(resolverTag);
        }

        dns["servers"] = new JsonArray(servers.OfType<JsonObject>()
            .Where(server => ReadString(server, "tag") is not { Length: > 0 } tag ||
                             requiredDnsTags.Contains(tag))
            .Select(server => (JsonNode?)server.DeepClone()).ToArray());

        void AddDnsTag(JsonNode? value)
        {
            if (ReadDnsTag(value) is { } tag)
                requiredDnsTags.Add(tag);
        }

        static string? ReadDnsTag(JsonNode? value)
        {
            if (value is JsonObject options)
                value = options["server"];
            return value is JsonValue jsonValue && jsonValue.TryGetValue<string>(out var tag) &&
                   !string.IsNullOrWhiteSpace(tag) ? tag : null;
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

    private static void ApplyDomainBypass(JsonObject root, JsonObject route,
        IReadOnlyList<string>? customBypassDomains,
        IReadOnlyList<string>? customBypassProcesses,
        bool isVpnOnly,
        string engineTag)
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

        var customSuffixes = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var customExact = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (customBypassDomains is not null)
        {
            foreach (var domain in customBypassDomains)
            {
                var trimmed = domain.Trim().ToLowerInvariant();
                if (string.IsNullOrWhiteSpace(trimmed)) continue;
                if (trimmed.StartsWith("*."))
                {
                    customSuffixes.Add(trimmed[2..]);
                }
                else if (trimmed.StartsWith('.'))
                {
                    customSuffixes.Add(trimmed[1..]);
                }
                else
                {
                    customExact.Add(trimmed);
                    customSuffixes.Add(trimmed);
                }
            }
        }

        var processRules = new List<JsonObject>();
        if (customBypassProcesses is not null)
        {
            var validProcesses = customBypassProcesses
                .Select(p => p.Trim())
                .Where(p => !string.IsNullOrWhiteSpace(p))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();
            if (validProcesses.Count > 0)
            {
                var procArr = new JsonArray();
                var added = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                foreach (var p in validProcesses)
                {
                    var baseName = p.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? p[..^4] : p;
                    var withExe = baseName + ".exe";

                    if (added.Add(p)) procArr.Add(p);
                    if (added.Add(baseName)) procArr.Add(baseName);
                    if (added.Add(withExe)) procArr.Add(withExe);

                    var pLower = p.ToLowerInvariant();
                    if (added.Add(pLower)) procArr.Add(pLower);
                    var baseLower = baseName.ToLowerInvariant();
                    if (added.Add(baseLower)) procArr.Add(baseLower);
                    var exeLower = withExe.ToLowerInvariant();
                    if (added.Add(exeLower)) procArr.Add(exeLower);
                }
                processRules.Add(new JsonObject
                {
                    ["process_name"] = procArr,
                    ["outbound"] = isVpnOnly ? engineTag : "direct",
                });
            }
        }

        var ipv6Reject = new JsonObject
        {
            ["ip_version"] = 6,
            ["action"] = "reject",
        };

        var customRuleNodes = new JsonArray();
        foreach (var pr in processRules)
            customRuleNodes.Add(pr);

        if (isVpnOnly)
        {
            if (customSuffixes.Count > 0)
                customRuleNodes.Add(new JsonObject { ["domain_suffix"] = ToJsonArray(customSuffixes), ["outbound"] = engineTag });
            if (customExact.Count > 0)
                customRuleNodes.Add(new JsonObject { ["domain"] = ToJsonArray(customExact), ["outbound"] = engineTag });
        }
        else
        {
            foreach (var s in customSuffixes) suffixes.Add(s);
            foreach (var e in customExact) exact.Add(e);
            customRuleNodes.Add(new JsonObject { ["domain_suffix"] = ToJsonArray(suffixes), ["outbound"] = "direct" });
            customRuleNodes.Add(new JsonObject { ["domain"] = ToJsonArray(exact), ["outbound"] = "direct" });
        }

        var merged = new JsonArray();
        var inserted = false;
        var hasIpv6Rule = routeRules.OfType<JsonObject>().Any(r => r["ip_version"]?.GetValue<int>() == 6);
        foreach (var rule in routeRules)
        {
            if (!inserted && rule is JsonObject obj &&
                ReadString(obj, "action") is not ("sniff" or "hijack-dns"))
            {
                if (!hasIpv6Rule)
                    merged.Add(ipv6Reject.DeepClone());
                foreach (var node in customRuleNodes)
                    merged.Add(node?.DeepClone());
                inserted = true;
            }
            merged.Add(rule?.DeepClone());
        }
        if (!inserted)
        {
            if (!hasIpv6Rule)
                merged.Add(ipv6Reject.DeepClone());
            foreach (var node in customRuleNodes)
                merged.Add(node?.DeepClone());
        }
        route["rules"] = merged;

        var dns = root["dns"] as JsonObject
                  ?? throw new InvalidDataException("Subscription has no DNS policy.");
        dns["strategy"] = "ipv4_only";
        dns["reverse_mapping"] = true;

        if (isVpnOnly)
        {
            var dnsServers = dns["servers"] as JsonArray;
            var remoteDnsTag = dnsServers?.OfType<JsonObject>()
                .FirstOrDefault(s => ReadString(s, "tag") != "local-dns")?["tag"]?.GetValue<string>()
                ?? "remote-dns";

            var dnsRules = new JsonArray();
            if (customSuffixes.Count > 0)
            {
                dnsRules.Add(new JsonObject
                {
                    ["domain_suffix"] = ToJsonArray(customSuffixes),
                    ["action"] = "route",
                    ["server"] = remoteDnsTag,
                });
            }
            if (customExact.Count > 0)
            {
                dnsRules.Add(new JsonObject
                {
                    ["domain"] = ToJsonArray(customExact),
                    ["action"] = "route",
                    ["server"] = remoteDnsTag,
                });
            }
            dns["rules"] = dnsRules;
            dns["final"] = "local-dns";
        }
        else
        {
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
        }
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

    private static void SanitizeHysteriaOutbound(JsonObject outbound)
    {
        if (outbound["server_ports"] is not JsonArray serverPorts)
            return;

        var hasServerPort = outbound["server_port"]?.GetValue<int>() is > 0;
        if (hasServerPort)
        {
            outbound.Remove("server_ports");
            outbound.Remove("hop_interval");
            outbound.Remove("hop_interval_max");
            return;
        }

        var validRanges = new JsonArray();
        foreach (var item in serverPorts)
        {
            var str = item?.GetValue<string>()?.Trim();
            if (string.IsNullOrEmpty(str)) continue;
            if (str.All(char.IsDigit))
                validRanges.Add($"{str}:{str}");
            else if (str.Contains(':') || str.Contains('-'))
                validRanges.Add(str.Replace('-', ':'));
        }

        if (validRanges.Count > 0)
        {
            outbound["server_ports"] = validRanges;
        }
        else
        {
            outbound.Remove("server_ports");
            outbound.Remove("hop_interval");
            outbound.Remove("hop_interval_max");
        }
    }

    private static string? ReadString(JsonObject source, string name) =>
        source[name] is JsonValue value && value.TryGetValue<string>(out var text) ? text : null;
}
