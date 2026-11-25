using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace DeyttConnect.Protocol;

public static class XrayRouteProfile
{
    private const string AutoTag = "route:AUTO:VLESS";
    private static readonly Regex Domain = new(
        @"\A(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*deytt\.space\z", RegexOptions.CultureInvariant);
    private static readonly Regex RouteTag = new(@"\Aroute:(AUTO|[A-Z]{2}):[A-Z0-9_]{1,20}\z",
        RegexOptions.CultureInvariant);

    public sealed record Runtime(string Profile, string Xray, IReadOnlyList<int> Ports);

    public static void Validate(string profile) => ReadRoutes(JsonNode.Parse(profile)!.AsObject());

    public static IReadOnlyDictionary<string, string> DescribeTransports(JsonElement profile) =>
        ReadRoutes(JsonObject.Create(profile)!).ToDictionary(
            route => route.Tag, route => route.Stream["network"]!.GetValue<string>(), StringComparer.Ordinal);

    public static async Task<Runtime?> PrepareAsync(string profile,
        Func<string, CancellationToken, Task<IPAddress[]>> resolve, CancellationToken cancellationToken)
    {
        var root = JsonNode.Parse(profile)!.AsObject();
        var routes = ReadRoutes(root);
        if (routes.Count == 0)
            return null;

        var proxies = root["outbounds"]!.AsArray();
        var inbounds = new JsonArray();
        var outbounds = new JsonArray();
        var rules = new JsonArray();
        var reservations = new List<TcpListener>();
        var addresses = new Dictionary<string, IPAddress>(StringComparer.Ordinal);
        var ports = new List<int>();
        try
        {
            foreach (var route in routes)
            {
                if (!addresses.TryGetValue(route.Host, out var address))
                {
                    var resolved = await resolve(route.Host, cancellationToken).ConfigureAwait(false);
                    address = resolved.FirstOrDefault(ip => ip.AddressFamily == AddressFamily.InterNetwork)
                        ?? throw new InvalidDataException("Xray endpoint has no IPv4 address.");
                    addresses.Add(route.Host, address);
                }

                var listener = new TcpListener(IPAddress.Loopback, 0);
                listener.Start();
                reservations.Add(listener);
                var port = ((IPEndPoint)listener.LocalEndpoint).Port;
                ports.Add(port);
                var inboundTag = $"local:{route.Tag}";
                inbounds.Add(new JsonObject
                {
                    ["tag"] = inboundTag, ["listen"] = "127.0.0.1", ["port"] = port,
                    ["protocol"] = "socks", ["settings"] = new JsonObject { ["auth"] = "noauth", ["udp"] = true },
                });
                outbounds.Add(new JsonObject
                {
                    ["tag"] = route.Tag, ["protocol"] = "vless", ["streamSettings"] = route.Stream,
                    ["settings"] = new JsonObject
                    {
                        ["vnext"] = new JsonArray(new JsonObject
                        {
                            ["address"] = address.ToString(), ["port"] = route.Port,
                            ["users"] = new JsonArray(new JsonObject
                            {
                                ["id"] = route.Uuid, ["encryption"] = "none",
                            }),
                        }),
                    },
                });
                rules.Add(new JsonObject
                {
                    ["type"] = "field", ["inboundTag"] = new JsonArray(inboundTag),
                    ["outboundTag"] = route.Tag,
                });

                var proxy = new JsonObject
                {
                    ["type"] = "socks", ["tag"] = route.Tag, ["server"] = "127.0.0.1", ["server_port"] = port,
                };
                var index = proxies.OfType<JsonObject>().FirstOrDefault(item => Read(item, "tag") == route.Tag);
                if (index is null)
                    proxies.Add(proxy);
                else
                    proxies[proxies.IndexOf(index)] = proxy;

                if (!route.Tag.StartsWith("route:RU:", StringComparison.Ordinal))
                    AddToSelector(proxies, route.Tag, tag => tag.Contains("автоподбор", StringComparison.OrdinalIgnoreCase));
                if (route.Tag != AutoTag)
                {
                    var countryTag = route.Tag[..route.Tag.LastIndexOf(':')];
                    AddToSelector(proxies, route.Tag, tag => tag == countryTag);
                }
            }
        }
        finally
        {
            foreach (var listener in reservations)
                listener.Stop();
        }

        if (routes.Any(route => route.Tag == AutoTag))
        {
            var automatic = proxies.OfType<JsonObject>().FirstOrDefault(item =>
                Read(item, "tag").Contains("автоподбор", StringComparison.OrdinalIgnoreCase));
            if (automatic is not null)
            {
                automatic["outbounds"] = new JsonArray(routes
                    .Where(route => !route.Tag.StartsWith("route:RU:", StringComparison.Ordinal))
                    .Select(route => (JsonNode?)JsonValue.Create(route.Tag)).ToArray());
                automatic.Remove("default");
            }
        }

        var xray = new JsonObject
        {
            ["log"] = new JsonObject { ["loglevel"] = "warning" },
            ["inbounds"] = inbounds,
            ["outbounds"] = outbounds,
            ["routing"] = new JsonObject { ["rules"] = rules },
        };
        return new Runtime(root.ToJsonString(), xray.ToJsonString(), ports);
    }

    private static void AddToSelector(JsonArray proxies, string routeTag, Func<string, bool> selector)
    {
        var group = proxies.OfType<JsonObject>().FirstOrDefault(item => selector(Read(item, "tag")));
        if (group?["outbounds"] is JsonArray members &&
            !members.Any(member => member?.GetValue<string>() == routeTag))
            members.Add(routeTag);
    }

    private static List<Route> ReadRoutes(JsonObject root)
    {
        var routes = new List<Route>();
        var extension = root["deytt"] as JsonObject;
        if (extension is null || !extension.ContainsKey("xray_routes"))
            return routes;
        if (extension["xray_routes"] is not JsonObject payload || payload["version"]?.GetValue<int>() != 1 ||
            payload["routes"] is not JsonObject entries || entries.Count > 32)
            throw new InvalidDataException("Unsupported Xray routes metadata.");

        foreach (var (tag, node) in entries)
        {
            if (!RouteTag.IsMatch(tag) || node is not JsonObject entry || Read(entry, "protocol") != "vless")
                throw new InvalidDataException("Invalid Xray route.");
            var host = Read(entry, "host");
            var sni = Read(entry, "sni");
            var port = entry["port"]?.GetValue<int>() ?? 0;
            var uuid = Read(entry, "uuid");
            var fingerprint = Read(entry, "fingerprint");
            if (!Domain.IsMatch(host) || !Domain.IsMatch(sni) || port is < 1 or > 65535 ||
                !Guid.TryParseExact(uuid, "D", out _) || !Regex.IsMatch(fingerprint, @"\A[a-z0-9_]{1,32}\z") ||
                entry["alpn"] is not JsonArray { Count: >= 1 and <= 3 } alpn ||
                alpn.Any(value => value?.GetValue<string>() is not ("h2" or "http/1.1" or "h3")))
                throw new InvalidDataException("Invalid Xray endpoint or TLS settings.");

            var transport = Read(entry, "transport");
            var stream = new JsonObject
            {
                ["network"] = transport, ["security"] = "tls",
                ["tlsSettings"] = new JsonObject
                {
                    ["serverName"] = sni, ["alpn"] = alpn.DeepClone(), ["fingerprint"] = fingerprint,
                },
            };
            if (transport is "xhttp" or "ws" or "httpupgrade")
            {
                var path = Read(entry, "path");
                if (path.Length is < 1 or > 256 || !path.StartsWith('/') ||
                    path.StartsWith("//", StringComparison.Ordinal) ||
                    path.Any(character => char.IsWhiteSpace(character) || char.IsControl(character) || character == '#'))
                    throw new InvalidDataException("Invalid Xray path.");

                var transportSettings = new JsonObject { ["path"] = path, ["host"] = sni };
                if (transport == "xhttp")
                {
                    var mode = Read(entry, "mode");
                    if (mode is not ("auto" or "packet-up" or "stream-up" or "stream-one"))
                        throw new InvalidDataException("Unsupported XHTTP mode.");
                    transportSettings["mode"] = mode;
                }
                stream[$"{transport}Settings"] = transportSettings;
            }
            else if (transport != "tcp")
            {
                throw new InvalidDataException("Unsupported Xray transport.");
            }
            routes.Add(new Route(tag, host, port, uuid, stream));
        }
        return routes;
    }

    private static string Read(JsonObject node, string field) => node[field]?.GetValue<string>() ?? "";

    private sealed record Route(string Tag, string Host, int Port, string Uuid, JsonObject Stream);
}

