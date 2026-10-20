using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace DeyttConnect.Windows.Service;

/// <summary>Reads only the current engine's authenticated loopback group selections.</summary>
internal sealed class ActiveRouteTelemetry : IDisposable
{
    private const int MaximumResponseBytes = 512 * 1024;
    private readonly HttpClient _http;
    private readonly Dictionary<string, Outbound> _outbounds;
    private readonly string _selectedTag;
    private readonly string _engineTag;

    private sealed record Outbound(string Type, IReadOnlySet<string> Members);

    private ActiveRouteTelemetry(string runtimeProfile, string selectedTag, int port, string secret)
    {
        using var document = JsonDocument.Parse(runtimeProfile);
        _outbounds = ReadOutbounds(document.RootElement);
        _selectedTag = selectedTag;
        _engineTag = document.RootElement.GetProperty("route").GetProperty("final").GetString()!;
        _http = new HttpClient(new SocketsHttpHandler
        {
            UseProxy = false,
            AllowAutoRedirect = false,
            UseCookies = false,
        })
        {
            BaseAddress = new Uri($"http://127.0.0.1:{port}/"),
            Timeout = TimeSpan.FromSeconds(2),
        };
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", secret);
    }

    public static (string Profile, ActiveRouteTelemetry Telemetry) Create(string runtimeProfile, string selectedTag)
    {
        // The engine binds the released ephemeral port. A bind collision causes its normal
        // verified startup to fail; the client never connects to another engine's API without this secret.
        var listener = new TcpListener(IPAddress.Loopback, 0);
        int port;
        try
        {
            listener.Start();
            port = ((IPEndPoint)listener.LocalEndpoint).Port;
        }
        finally { listener.Stop(); }
        var secret = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var root = JsonNode.Parse(runtimeProfile) as JsonObject
            ?? throw new InvalidDataException("The runtime profile must be an object.");
        // Replace any subscription controller/UI/file configuration: privileged runtime API
        // credentials belong only in the existing SYSTEM/Admin protected temporary profile.
        // Preexisting experimental properties (such as cache_file) must never be retained.
        root["experimental"] = new JsonObject
        {
            ["clash_api"] = new JsonObject
            {
                ["external_controller"] = $"127.0.0.1:{port}",
                ["secret"] = secret,
                ["access_control_allow_origin"] = new JsonArray("http://127.0.0.1"),
                ["access_control_allow_private_network"] = false,
            },
        };
        var profile = root.ToJsonString();
        return (profile, new ActiveRouteTelemetry(profile, selectedTag, port, secret));
    }

    public async Task<string?> ReadActiveRouteTagAsync(CancellationToken cancellationToken)
    {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        budget.CancelAfter(TimeSpan.FromSeconds(2));
        var boundedToken = budget.Token;
        using var response = await _http.GetAsync("proxies", HttpCompletionOption.ResponseHeadersRead,
            boundedToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode || response.Content.Headers.ContentLength > MaximumResponseBytes)
            return null;
        await using var input = await response.Content.ReadAsStreamAsync(boundedToken).ConfigureAwait(false);
        using var buffer = new MemoryStream();
        var chunk = new byte[8192];
        while (true)
        {
            var count = await input.ReadAsync(chunk, boundedToken).ConfigureAwait(false);
            if (count == 0)
                break;
            if (buffer.Length + count > MaximumResponseBytes)
                return null;
            buffer.Write(chunk, 0, count);
        }
        using var document = JsonDocument.Parse(buffer.ToArray(), new JsonDocumentOptions { MaxDepth = 32 });
        return ResolveActiveRouteTag(_outbounds, _engineTag, _selectedTag, document.RootElement);
    }

    internal static string? ResolveActiveRouteTag(string profile, string selectedTag, string response)
    {
        using var runtime = JsonDocument.Parse(profile);
        using var current = JsonDocument.Parse(response, new JsonDocumentOptions { MaxDepth = 32 });
        var engineTag = runtime.RootElement.GetProperty("route").GetProperty("final").GetString();
        return engineTag is null ? null : ResolveActiveRouteTag(ReadOutbounds(runtime.RootElement),
            engineTag, selectedTag, current.RootElement);
    }

    private static string? ResolveActiveRouteTag(Dictionary<string, Outbound> outbounds, string engineTag,
        string selectedTag, JsonElement response)
    {
        if (response.ValueKind != JsonValueKind.Object ||
            !response.TryGetProperty("proxies", out var proxies) || proxies.ValueKind != JsonValueKind.Object)
            return null;
        var visited = new HashSet<string>(StringComparer.Ordinal);
        string? explicitRoute = null;
        var tag = engineTag;
        for (var depth = 0; depth < 32; depth++)
        {
            if (!visited.Add(tag) || !outbounds.TryGetValue(tag, out var outbound))
                return null;
            if (IsCanonicalRoute(tag))
                explicitRoute ??= tag;
            if (outbound.Type is not ("selector" or "urltest"))
            {
                if (outbound.Type is not ("vless" or "trojan" or "hysteria2" or "wireguard"))
                    return null;
                if (explicitRoute is not null)
                    return explicitRoute;
                if (selectedTag.StartsWith("awg", StringComparison.Ordinal) && tag.StartsWith("deytt-awg", StringComparison.Ordinal))
                    return selectedTag;
                // A backend URLTest may contain network endpoints directly. Map a leaf only
                // when subscription membership gives exactly one country/protocol route.
                var candidates = outbounds.Keys.Where(IsCanonicalRoute)
                    .Where(candidate => candidate != "route:RU-DE:CHAIN" && ContainsLeaf(outbounds, candidate, tag, []))
                    .Take(2).ToArray();
                return candidates.Length == 1 ? candidates[0] : null;
            }
            if (!proxies.TryGetProperty(tag, out var group) || group.ValueKind != JsonValueKind.Object ||
                !group.TryGetProperty("now", out var now) || now.ValueKind != JsonValueKind.String ||
                now.GetString() is not { Length: > 0 and <= 256 } member || !outbound.Members.Contains(member))
                return null;
            tag = member;
        }
        return null;
    }

    private static bool ContainsLeaf(Dictionary<string, Outbound> outbounds, string tag, string leaf,
        HashSet<string> visited)
    {
        if (tag == leaf)
            return true;
        if (visited.Count >= 32 || !visited.Add(tag) || !outbounds.TryGetValue(tag, out var outbound))
            return false;
        return outbound.Members.Any(member => ContainsLeaf(outbounds, member, leaf, visited));
    }

    private static bool IsCanonicalRoute(string tag)
    {
        if (tag == "route:RU-DE:CHAIN")
            return true;
        var parts = tag.Split(':');
        return parts.Length == 3 && parts[0] == "route" &&
            parts[1] is "NL" or "DE" or "RU" or "FI" or "IT" &&
            parts[2] is "VLESS" or "TROJAN" or "HYSTERIA2";
    }

    private static Dictionary<string, Outbound> ReadOutbounds(JsonElement root)
    {
        var result = new Dictionary<string, Outbound>(StringComparer.Ordinal);
        foreach (var key in new[] { "outbounds", "endpoints" })
        {
            if (!root.TryGetProperty(key, out var array) || array.ValueKind != JsonValueKind.Array)
                continue;
            foreach (var item in array.EnumerateArray())
            {
                if (result.Count >= 4096 || !item.TryGetProperty("tag", out var tagValue) ||
                    tagValue.GetString() is not { Length: > 0 and <= 256 } tag ||
                    !item.TryGetProperty("type", out var typeValue) || typeValue.GetString() is not { } type)
                    throw new InvalidDataException("The runtime outbound catalog is invalid.");
                var members = item.TryGetProperty("outbounds", out var group) && group.ValueKind == JsonValueKind.Array
                    ? group.EnumerateArray().Select(member => member.GetString()).OfType<string>().ToHashSet(StringComparer.Ordinal)
                    : new HashSet<string>(StringComparer.Ordinal);
                if (!result.TryAdd(tag, new Outbound(type, members)))
                    throw new InvalidDataException("The runtime outbound catalog has duplicate tags.");
            }
        }
        return result;
    }

    public void Dispose() => _http.Dispose();
}
