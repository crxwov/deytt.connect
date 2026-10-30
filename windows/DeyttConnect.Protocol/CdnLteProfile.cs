using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace DeyttConnect.Protocol;

public static class CdnLteProfile
{
    public const string RouteTag = "route:DE:LTE";
    private const string Host = "front.deytt.space";
    private const string Path = "/api/v2/cdn-lte-stream/";

    public static string Attach(string profile, string? encodedMetadata)
    {
        if (string.IsNullOrWhiteSpace(encodedMetadata))
            return profile;
        if (encodedMetadata.Length > 8192)
            throw new InvalidDataException("CDN LTE metadata is too large.");

        JsonObject metadata;
        try
        {
            metadata = JsonNode.Parse(new UTF8Encoding(false, true).GetString(Convert.FromBase64String(encodedMetadata)))
                       as JsonObject ?? throw new InvalidDataException("CDN LTE metadata is invalid.");
        }
        catch (Exception error) when (error is FormatException or DecoderFallbackException or JsonException)
        {
            throw new InvalidDataException("CDN LTE metadata is invalid.", error);
        }
        if (ReadInt(metadata, "version") != 1 || metadata["outbound"] is not JsonObject outbound)
            throw new InvalidDataException("CDN LTE metadata version is unsupported.");
        ValidateOutbound(outbound);

        var root = JsonNode.Parse(profile) as JsonObject ?? throw new InvalidDataException("Subscription is invalid.");
        var outbounds = root["outbounds"] as JsonArray ?? throw new InvalidDataException("Subscription has no outbounds.");
        if (outbounds.OfType<JsonObject>().Any(item => ReadString(item, "tag") == RouteTag))
            throw new InvalidDataException("Subscription already contains the CDN LTE route.");
        var extension = root["deytt"] as JsonObject;
        if (extension is null)
            root["deytt"] = extension = new JsonObject();
        if (extension.ContainsKey("cdn_lte"))
            throw new InvalidDataException("Subscription already contains CDN LTE metadata.");
        extension["cdn_lte"] = outbound.DeepClone();

        var updated = new JsonArray();
        var inserted = false;
        foreach (var item in outbounds)
        {
            updated.Add(item?.DeepClone());
            if (inserted || item is not JsonObject current ||
                ReadString(current, "tag")?.Contains("автоподбор", StringComparison.OrdinalIgnoreCase) != true)
                continue;
            if (ReadString(current, "type") is not ("urltest" or "selector"))
                throw new InvalidDataException("Automatic route has an unsupported type.");
            updated.Add(new JsonObject
            {
                ["type"] = "socks",
                ["tag"] = RouteTag,
                ["server"] = "127.0.0.1",
                ["server_port"] = 1,
            });
            inserted = true;
        }
        if (!inserted)
            throw new InvalidDataException("Subscription has no automatic route.");
        root["outbounds"] = updated;
        return root.ToJsonString();
    }

    public static bool HasValidMetadata(string profile)
    {
        try
        {
            var root = JsonNode.Parse(profile) as JsonObject;
            return root?["deytt"] is JsonObject extension && extension["cdn_lte"] is JsonObject outbound &&
                   ValidateAttached(root, outbound);
        }
        catch (Exception error) when (error is JsonException or InvalidDataException or InvalidOperationException)
        {
            return false;
        }
    }

    public static string BuildXrayConfig(string profile, int socksPort)
    {
        if (socksPort is < 1 or > 65535)
            throw new InvalidDataException("CDN LTE SOCKS port is invalid.");
        var outbound = ReadOutbound(profile).DeepClone() as JsonObject ?? throw new InvalidDataException("Invalid outbound");
        string resolvedIp = "188.72.103.4";
        try
        {
            var addresses = System.Net.Dns.GetHostAddresses(Host);
            if (addresses.Length > 0)
                resolvedIp = addresses[0].ToString();
        }
        catch { }

        if (outbound["settings"] is JsonObject settings &&
            settings["vnext"] is JsonArray vnext &&
            vnext.Count > 0 &&
            vnext[0] is JsonObject serverObj)
        {
            serverObj["address"] = resolvedIp;
        }

        return new JsonObject
        {
            ["log"] = new JsonObject { ["loglevel"] = "warning" },
            ["dns"] = new JsonObject { ["servers"] = new JsonArray("77.88.8.8", "8.8.8.8", "localhost") },
            ["inbounds"] = new JsonArray(new JsonObject
            {
                ["listen"] = "127.0.0.1",
                ["port"] = socksPort,
                ["protocol"] = "socks",
                ["settings"] = new JsonObject { ["auth"] = "noauth", ["udp"] = true },
            }),
            ["outbounds"] = new JsonArray(outbound),
        }.ToJsonString();
    }

    public static string UseSocksPort(string profile, int socksPort)
    {
        if (socksPort is < 1 or > 65535)
            throw new InvalidDataException("CDN LTE SOCKS port is invalid.");
        var root = JsonNode.Parse(profile) as JsonObject ?? throw new InvalidDataException("Subscription is invalid.");
        var outbound = (root["outbounds"] as JsonArray)?.OfType<JsonObject>()
            .FirstOrDefault(item => ReadString(item, "tag") == RouteTag)
            ?? throw new InvalidDataException("Subscription has no local CDN LTE outbound.");
        if (ReadString(outbound, "type") != "socks" || ReadString(outbound, "server") != "127.0.0.1")
            throw new InvalidDataException("CDN LTE local outbound is invalid.");
        outbound["server_port"] = socksPort;

        if (root["route"] is JsonObject routeObj && routeObj["rules"] is JsonArray rules)
        {
            var directIps = new JsonArray("188.72.103.0/24", "77.88.8.8/32", "8.8.8.8/32", "1.1.1.1/32");
            try
            {
                foreach (var addr in System.Net.Dns.GetHostAddresses(Host))
                {
                    directIps.Add($"{addr}/32");
                }
            }
            catch { }

            var directRule = new JsonObject
            {
                ["domain"] = new JsonArray("front.deytt.space"),
                ["domain_suffix"] = new JsonArray(".yccdn.ru", ".deytt.space"),
                ["ip_cidr"] = directIps,
                ["outbound"] = "direct",
            };
            rules.Insert(0, directRule);
        }

        if (root["dns"] is JsonObject dnsObj && dnsObj["rules"] is JsonArray dnsRules)
        {
            var localDnsRule = new JsonObject
            {
                ["domain"] = new JsonArray("front.deytt.space"),
                ["domain_suffix"] = new JsonArray(".yccdn.ru", ".deytt.space"),
                ["action"] = "route",
                ["server"] = "local-dns",
            };
            dnsRules.Insert(0, localDnsRule);
        }

        return root.ToJsonString();
    }

    private static JsonObject ReadOutbound(string profile)
    {
        var root = JsonNode.Parse(profile) as JsonObject ?? throw new InvalidDataException("Subscription is invalid.");
        if (root["deytt"] is not JsonObject extension || extension["cdn_lte"] is not JsonObject outbound)
            throw new InvalidDataException("Subscription has no CDN LTE metadata.");
        ValidateOutbound(outbound);
        return outbound;
    }

    private static bool ValidateAttached(JsonObject root, JsonObject outbound)
    {
        ValidateOutbound(outbound);
        return root["outbounds"] is JsonArray outbounds && outbounds.OfType<JsonObject>().Any(item =>
            ReadString(item, "tag") == RouteTag && ReadString(item, "type") == "socks" &&
            ReadString(item, "server") == "127.0.0.1" && ReadInt(item, "server_port") is >= 1 and <= 65535);
    }

    private static void ValidateOutbound(JsonObject outbound)
    {
        if (ReadString(outbound, "tag") != RouteTag || ReadString(outbound, "protocol") != "vless" ||
            outbound["settings"]?["vnext"] is not JsonArray servers || servers.Count != 1 ||
            servers[0] is not JsonObject server || ReadString(server, "address") != Host || ReadInt(server, "port") != 443 ||
            server["users"] is not JsonArray users || users.Count != 1 || users[0] is not JsonObject user ||
            !Guid.TryParseExact(ReadString(user, "id"), "D", out _) || ReadString(user, "encryption") != "none" ||
            outbound["streamSettings"] is not JsonObject stream || ReadString(stream, "network") != "xhttp" ||
            ReadString(stream, "security") != "tls" || stream["tlsSettings"] is not JsonObject tls ||
            ReadString(tls, "serverName") != Host || ReadString(tls, "fingerprint") != "chrome" ||
            tls["alpn"] is not JsonArray alpn || !alpn.Any(item => ReadString(item) == "h2") ||
            !alpn.Any(item => ReadString(item) == "http/1.1") ||
            stream["xhttpSettings"] is not JsonObject xhttp || ReadString(xhttp, "path") != Path ||
            ReadString(xhttp, "mode") != "packet-up" || xhttp["extra"] is not JsonObject extra ||
            ReadString(extra, "uplinkHTTPMethod") != "GET" || ReadString(extra, "uplinkDataPlacement") != "header" ||
            ReadString(extra, "uplinkDataKey") != "data" || ReadInt(extra, "scMaxEachPostBytes") != 4096 ||
            ReadInt(extra, "scMinPostsIntervalMs") != 30 || ReadInt(extra, "serverMaxHeaderBytes") != 32768 ||
            ReadBool(extra, "noSSEHeader") != true)
            throw new InvalidDataException("CDN LTE XHTTP metadata is invalid.");
    }

    private static string? ReadString(JsonObject source, string key) =>
        source[key] is JsonValue value && value.TryGetValue<string>(out var text) ? text : null;

    private static string? ReadString(JsonNode? node) =>
        node is JsonValue value && value.TryGetValue<string>(out var text) ? text : null;

    private static int ReadInt(JsonObject source, string key) =>
        source[key] is JsonValue value && value.TryGetValue<int>(out var number) ? number : 0;

    private static bool? ReadBool(JsonObject source, string key) =>
        source[key] is JsonValue value && value.TryGetValue<bool>(out var flag) ? flag : null;
}
