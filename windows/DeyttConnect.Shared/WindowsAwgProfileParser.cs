using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace DeyttConnect.Shared;

public static class WindowsAwgProfileParser
{
    public const int MaximumProfileBytes = 512 * 1024;

    private static readonly Regex SafeEndpointTag = new("^[A-Za-z0-9._-]{1,96}$", RegexOptions.CultureInvariant);
    private static readonly string[] IntegerObfuscationKeys = ["jc", "jmin", "jmax", "s1", "s2", "s3", "s4"];
    private static readonly string[] HeaderKeys = ["h1", "h2", "h3", "h4"];
    private static readonly string[] InjectionKeys = ["i1", "i2", "i3", "i4", "i5"];
    private static readonly Dictionary<string, string> TimingKeys = new(StringComparer.OrdinalIgnoreCase)
    {
        ["contentpaddingaddition"] = "content_padding_addition",
        ["rekeyaftertime"] = "rekey_after_time",
        ["rekeytimeout"] = "rekey_timeout",
        ["rejectaftertime"] = "reject_after_time",
        ["keepalivetimeout"] = "keepalive_timeout",
        ["maxhandshakeattempts"] = "max_handshake_attempts",
    };
    private static readonly HashSet<string> InterfaceKeys = new(StringComparer.OrdinalIgnoreCase)
    {
        "privatekey", "address", "dns", "mtu", "listenport", "jc", "jmin", "jmax",
        "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
        "headerprotectionkey", "contentpaddingaddition", "rekeyaftertime", "rekeytimeout",
        "rejectaftertime", "keepalivetimeout", "maxhandshakeattempts", "randomtrailers", "disablecookies",
    };
    private static readonly HashSet<string> PeerKeys = new(StringComparer.OrdinalIgnoreCase)
    {
        "publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive",
        "persistentkeepaliveinterval",
    };

    public static JsonObject Parse(string? content, string endpointTag)
    {
        if (string.IsNullOrWhiteSpace(content) || Encoding.UTF8.GetByteCount(content) > MaximumProfileBytes)
            throw new InvalidDataException("The AmneziaWG profile is empty or too large.");
        if (!SafeEndpointTag.IsMatch(endpointTag))
            throw new InvalidDataException("The AmneziaWG endpoint tag is invalid.");

        var interfaceValues = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
        var peerValues = new List<Dictionary<string, List<string>>>();
        Dictionary<string, List<string>>? current = null;
        var sawInterface = false;

        foreach (var rawLine in content.Split('\n'))
        {
            var line = rawLine.Trim();
            if (line.Length == 0 || line.StartsWith('#') || line.StartsWith(';'))
                continue;
            if (line[0] == '[' && line[^1] == ']')
            {
                var section = line[1..^1].Trim();
                if (section.Equals("Interface", StringComparison.OrdinalIgnoreCase))
                {
                    if (sawInterface)
                        throw new InvalidDataException("The AmneziaWG profile has duplicate Interface sections.");
                    sawInterface = true;
                    current = interfaceValues;
                }
                else if (section.Equals("Peer", StringComparison.OrdinalIgnoreCase))
                {
                    current = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
                    peerValues.Add(current);
                }
                else
                {
                    throw new InvalidDataException("The AmneziaWG profile contains an unsupported section.");
                }
                continue;
            }

            if (current is null)
                throw new InvalidDataException("The AmneziaWG profile has a value outside a section.");
            var separator = line.IndexOf('=');
            if (separator <= 0)
                throw new InvalidDataException("The AmneziaWG profile contains an invalid setting.");
            var key = NormalizeKey(line[..separator]);
            var value = line[(separator + 1)..].Trim();
            if (key.Length == 0 || value.Length == 0 || value.Contains('\0'))
                throw new InvalidDataException("The AmneziaWG profile contains an empty or invalid setting.");
            if (!current.TryGetValue(key, out var values))
                current.Add(key, values = []);
            if (values.Count != 0 && key is not ("address" or "allowedips" or "dns"))
                throw new InvalidDataException("The AmneziaWG profile repeats a single-value setting.");
            values.Add(value);
        }

        if (!sawInterface || peerValues.Count == 0)
            throw new InvalidDataException("The AmneziaWG profile needs Interface and Peer sections.");
        if (interfaceValues.Keys.Any(key => !InterfaceKeys.Contains(key)) ||
            peerValues.Any(values => values.Keys.Any(key => !PeerKeys.Contains(key))))
            throw new InvalidDataException("The AmneziaWG profile contains an unsupported setting.");

        var privateKey = Required(interfaceValues, "privatekey");
        ValidateKey(privateKey, "PrivateKey");
        var addressValues = SplitList(RequiredMany(interfaceValues, "address"));
        if (addressValues.Count == 0)
            throw new InvalidDataException("The AmneziaWG profile has no interface address.");
        var addresses = addressValues.Select(value => ValidatePrefix(value, "Address")).ToArray();

        var endpoint = new JsonObject
        {
            ["type"] = "awg",
            ["tag"] = endpointTag,
            ["useIntegratedTun"] = false,
            ["private_key"] = privateKey,
            ["address"] = ToJsonArray(addresses),
        };

        AddOptionalInteger(interfaceValues, endpoint, "mtu", "mtu", 576, 9000);
        AddOptionalInteger(interfaceValues, endpoint, "listenport", "listen_port", 0, 65535);
        foreach (var key in IntegerObfuscationKeys)
            AddOptionalInteger(interfaceValues, endpoint, key, key, 0, 1_000_000);
        foreach (var key in HeaderKeys)
            AddOptionalHeader(interfaceValues, endpoint, key);
        foreach (var key in InjectionKeys)
            AddOptionalInjection(interfaceValues, endpoint, key);
        foreach (var (sourceKey, targetKey) in TimingKeys)
            AddOptionalSafeString(interfaceValues, endpoint, sourceKey, targetKey);
        if (Optional(interfaceValues, "headerprotectionkey") is { } headerProtectionKey)
        {
            ValidateKey(headerProtectionKey, "HeaderProtectionKey");
            endpoint["header_protection_key"] = headerProtectionKey;
        }
        AddOptionalBool(interfaceValues, endpoint, "randomtrailers", "random_trailers");
        AddOptionalBool(interfaceValues, endpoint, "disablecookies", "disable_cookies");

        var peers = new JsonArray();
        var hasDefaultRoute = false;
        foreach (var values in peerValues)
        {
            var publicKey = Required(values, "publickey");
            ValidateKey(publicKey, "PublicKey");
            var allowedValues = SplitList(RequiredMany(values, "allowedips"));
            if (allowedValues.Count == 0)
                throw new InvalidDataException("An AmneziaWG peer has no allowed routes.");
            var allowedIps = allowedValues.Select(value => ValidatePrefix(value, "AllowedIPs")).ToArray();
            hasDefaultRoute |= allowedIps.Any(IsDefaultPrefix);

            var peer = new JsonObject
            {
                ["public_key"] = publicKey,
                ["allowed_ips"] = ToJsonArray(allowedIps),
            };
            if (Optional(values, "presharedkey") is { } presharedKey)
            {
                ValidateKey(presharedKey, "PresharedKey");
                peer["preshared_key"] = presharedKey;
            }

            var remote = ParseRemote(Required(values, "endpoint"));
            peer["address"] = remote.Host;
            peer["port"] = remote.Port;
            AddOptionalInteger(values, peer, "persistentkeepalive", "persistent_keepalive_interval", 0, 65535);
            AddOptionalInteger(values, peer, "persistentkeepaliveinterval", "persistent_keepalive_interval", 0, 65535);
            if (values.ContainsKey("persistentkeepalive") && values.ContainsKey("persistentkeepaliveinterval"))
                throw new InvalidDataException("An AmneziaWG peer repeats its keepalive setting.");
            peers.Add(peer);
        }

        if (!hasDefaultRoute)
            throw new InvalidDataException("The AmneziaWG profile has no default route.");
        endpoint["peers"] = peers;
        return endpoint;
    }

    private static string NormalizeKey(string value)
    {
        var key = value.Trim().Replace("_", "", StringComparison.Ordinal)
            .Replace("-", "", StringComparison.Ordinal)
            .Replace(" ", "", StringComparison.Ordinal);
        if (key.Any(character => !char.IsAsciiLetterOrDigit(character)))
            throw new InvalidDataException("The AmneziaWG profile contains an invalid setting name.");
        return key.ToLowerInvariant();
    }

    private static string Required(Dictionary<string, List<string>> values, string key) =>
        Optional(values, key) ?? throw new InvalidDataException("The AmneziaWG profile is missing a required setting.");

    private static string? Optional(Dictionary<string, List<string>> values, string key) =>
        values.TryGetValue(key, out var items) ? items[0] : null;

    private static IReadOnlyList<string> RequiredMany(Dictionary<string, List<string>> values, string key) =>
        values.TryGetValue(key, out var items)
            ? items
            : throw new InvalidDataException("The AmneziaWG profile is missing a required setting.");

    private static IReadOnlyList<string> SplitList(IEnumerable<string> values) =>
        values.SelectMany(value => value.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
            .ToArray();

    private static string ValidatePrefix(string value, string field)
    {
        var parts = value.Split('/', 2, StringSplitOptions.TrimEntries);
        if (parts.Length != 2 || !IPAddress.TryParse(parts[0], out var address) ||
            !int.TryParse(parts[1], NumberStyles.None, CultureInfo.InvariantCulture, out var prefixLength))
            throw new InvalidDataException($"The AmneziaWG {field} value is invalid.");
        var maxPrefix = address.GetAddressBytes().Length * 8;
        if (prefixLength < 0 || prefixLength > maxPrefix)
            throw new InvalidDataException($"The AmneziaWG {field} prefix length is invalid.");
        return $"{address}/{prefixLength.ToString(CultureInfo.InvariantCulture)}";
    }

    private static bool IsDefaultPrefix(string prefix)
    {
        var slash = prefix.LastIndexOf('/');
        return slash >= 0 && prefix[(slash + 1)..] == "0";
    }

    private static void ValidateKey(string value, string field)
    {
        try
        {
            if (Convert.FromBase64String(value).Length != 32)
                throw new InvalidDataException($"The AmneziaWG {field} value is invalid.");
        }
        catch (FormatException error)
        {
            throw new InvalidDataException($"The AmneziaWG {field} value is invalid.", error);
        }
    }

    private static void AddOptionalInteger(
        Dictionary<string, List<string>> source,
        JsonObject target,
        string sourceKey,
        string targetKey,
        int minimum,
        int maximum)
    {
        if (Optional(source, sourceKey) is not { } value)
            return;
        if (!int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out var parsed) ||
            parsed < minimum || parsed > maximum)
            throw new InvalidDataException("The AmneziaWG profile contains an invalid numeric setting.");
        target[targetKey] = parsed;
    }

    private static void AddOptionalHeader(Dictionary<string, List<string>> source, JsonObject target, string key)
    {
        if (Optional(source, key) is not { } value)
            return;
        if (value.Length > 32 || !Regex.IsMatch(value, "^(?:0[xX])?[0-9A-Fa-f]+$", RegexOptions.CultureInvariant))
            throw new InvalidDataException("The AmneziaWG profile contains an invalid packet header value.");
        target[key] = value;
    }

    private static void AddOptionalInjection(Dictionary<string, List<string>> source, JsonObject target, string key)
    {
        if (Optional(source, key) is not { } value)
            return;
        if (value.Length > 4096 || value.Any(char.IsControl))
            throw new InvalidDataException("The AmneziaWG profile contains an invalid packet mask.");
        target[key] = value;
    }

    private static void AddOptionalSafeString(
        Dictionary<string, List<string>> source,
        JsonObject target,
        string sourceKey,
        string targetKey)
    {
        if (Optional(source, sourceKey) is not { } value)
            return;
        if (value.Length > 64 || !Regex.IsMatch(value, "^[A-Za-z0-9._+-]+$", RegexOptions.CultureInvariant))
            throw new InvalidDataException("The AmneziaWG profile contains an invalid timing setting.");
        target[targetKey] = value;
    }

    private static void AddOptionalBool(Dictionary<string, List<string>> source, JsonObject target,
        string sourceKey, string targetKey)
    {
        if (Optional(source, sourceKey) is not { } value)
            return;
        if (!bool.TryParse(value, out var parsed))
            throw new InvalidDataException("The AmneziaWG profile contains an invalid boolean setting.");
        target[targetKey] = parsed;
    }

    private static (string Host, int Port) ParseRemote(string value)
    {
        string host;
        string portValue;
        if (value.StartsWith('['))
        {
            var closeBracket = value.IndexOf(']');
            if (closeBracket <= 1 || closeBracket + 2 >= value.Length || value[closeBracket + 1] != ':' ||
                !IPAddress.TryParse(value[1..closeBracket], out var address) || address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetworkV6)
                throw new InvalidDataException("The AmneziaWG peer endpoint is invalid.");
            host = $"[{address}]";
            portValue = value[(closeBracket + 2)..];
        }
        else
        {
            var separator = value.LastIndexOf(':');
            if (separator <= 0 || value[..separator].Contains(':'))
                throw new InvalidDataException("The AmneziaWG peer endpoint is invalid.");
            host = value[..separator].Trim();
            portValue = value[(separator + 1)..].Trim();
            if (!IPAddress.TryParse(host, out _) &&
                (host.Any(char.IsControl) || Uri.CheckHostName(host) != UriHostNameType.Dns))
                throw new InvalidDataException("The AmneziaWG peer host is invalid.");
        }

        if (!ushort.TryParse(portValue, NumberStyles.None, CultureInfo.InvariantCulture, out var port) || port == 0)
            throw new InvalidDataException("The AmneziaWG peer port is invalid.");
        return (host, port);
    }

    private static JsonArray ToJsonArray(IEnumerable<string> values)
    {
        var result = new JsonArray();
        foreach (var value in values)
            result.Add(value);
        return result;
    }
}
