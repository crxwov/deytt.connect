using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using DeyttConnect.Shared;

namespace DeyttConnect.Windows.Services;

public sealed class TelegramApiClient
{
    private static readonly Uri ApiBase = new("https://deytt.space");
    private static readonly Regex UsernamePattern = new("^[A-Za-z0-9_]{5,32}$", RegexOptions.CultureInvariant);
    private static readonly Regex CodePattern = new("^[0-9]{6}$", RegexOptions.CultureInvariant);
    private static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = false,
        UseCookies = false,
        AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
    })
    {
        Timeout = TimeSpan.FromSeconds(45),
    };

    // AmneziaWG is optional during setup; never let its edge list hold the
    // required subscription profile behind one HTTP timeout per edge.
    private static readonly TimeSpan OptionalAwgFetchBudget = TimeSpan.FromSeconds(8);
    private const int MaxConcurrentAwgProfileRequests = 4;
    private static readonly TimeSpan EssentialRequestTimeout = TimeSpan.FromSeconds(30);
    // Bound the complete metadata -> profile -> optional-enrichment chain, not just each hop.
    private static readonly TimeSpan SubscriptionFetchBudget = TimeSpan.FromSeconds(35);

    private const string SessionHeader = "X-TG-App-Token";
    private const string AwgServersHeader = "X-Deytt-Awg-Servers";
    private const int MaxResponseBytes = 512 * 1024;
    private const int MaxAvatarBytes = 512 * 1024;
    private const int MaxProfileBytes = 2 * 1024 * 1024;
    private const int MaxAwgManifestBytes = 32 * 1024;
    private const int MaxAwgProfileCount = 32;
    private const int MaxAwgProfilesTotalBytes = 4 * 1024 * 1024;

    public async Task<PairingStart> StartPairingAsync(string username, CancellationToken cancellationToken = default)
    {
        var normalized = username.Trim();
        if (normalized.StartsWith('@'))
            normalized = normalized[1..];
        if (!UsernamePattern.IsMatch(normalized))
            throw new TelegramApiException("invalid_username", HttpStatusCode.UnprocessableEntity);

        using var response = await SendJsonAsync(
            "/api/tg/mobile/pair/start", HttpMethod.Post,
            new { username = normalized }, null, cancellationToken);

        var root = response.RootElement;
        return new PairingStart(
            RequiredString(root, "challenge"),
            RequiredBotUrl(root, "bot_url"),
            OptionalString(root, "delivery") ?? "start_required");
    }

    public async Task<VerifiedPairing> VerifyPairingAsync(
        string challenge,
        string code,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(challenge) || challenge.Length > 128 || !CodePattern.IsMatch(code))
            throw new TelegramApiException("pair_code_invalid", HttpStatusCode.BadRequest);

        using var response = await SendJsonAsync(
            "/api/tg/mobile/pair/verify", HttpMethod.Post,
            new { challenge, code }, null, cancellationToken);

        var root = response.RootElement;
        var token = RequiredString(root, "token");
        if (token.Length is < 32 or > 256)
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);

        var profile = RequiredObject(root, "profile");
        return new VerifiedPairing(token, TelegramAccount.ParseProfile(profile));
    }

    public async Task<TelegramAccount> GetAccountAsync(string token, CancellationToken cancellationToken = default)
    {
        using var response = await SendJsonAsync(
            "/api/tg/me", HttpMethod.Get, null, token, cancellationToken);
        return TelegramAccount.ParseProfile(RequiredObject(response.RootElement, "profile"));
    }

    public async Task<byte[]?> GetAvatarAsync(string token, CancellationToken cancellationToken = default)
    {
        if (token.Length is < 32 or > 256)
            throw new TelegramApiException("session_invalid", HttpStatusCode.Unauthorized);

        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(ApiBase, "/api/tg/me/avatar"));
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("image/*"));
        request.Headers.UserAgent.ParseAdd("deytt-connect/windows");
        request.Headers.TryAddWithoutValidation("X-Deytt-Client", "deytt-connect");
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        request.Headers.TryAddWithoutValidation(SessionHeader, token);

        using var requestTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        requestTimeout.CancelAfter(EssentialRequestTimeout);
        try
        {
            using var response = await Http.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, requestTimeout.Token);
            if (response.StatusCode is HttpStatusCode.NoContent or HttpStatusCode.NotFound)
                return null;
            if (!response.IsSuccessStatusCode)
                throw new TelegramApiException(
                    response.StatusCode == HttpStatusCode.Unauthorized ? "session_invalid" : "avatar_unavailable",
                    response.StatusCode);

            var mediaType = response.Content.Headers.ContentType?.MediaType;
            if (mediaType is null || !mediaType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
                return null;
            var bytes = await ReadBoundedAsync(response.Content, requestTimeout.Token, MaxAvatarBytes);
            return bytes.Length > 0 ? bytes : null;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw new OperationCanceledException(cancellationToken);
        }
        catch (OperationCanceledException)
        {
            // The avatar is optional; an internal response timeout must not fail account refresh.
            return null;
        }
    }

    public async Task<TelegramKeysSnapshot> GetSubscriptionAsync(
        string token,
        CancellationToken cancellationToken = default,
        IProgress<string>? progress = null)
    {
        using var subscriptionTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        subscriptionTimeout.CancelAfter(SubscriptionFetchBudget);
        var subscriptionToken = subscriptionTimeout.Token;

        progress?.Report("metadata");
        using var keys = await SendJsonAsync("/api/tg/keys", HttpMethod.Get, null, token, subscriptionToken);
        var root = keys.RootElement;
        var happ = RequiredObject(root, "happ");
        if (!happ.TryGetProperty("available", out var availableValue) ||
            availableValue.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);

        var devices = ParseHappDevices(happ);
        var awg = root.TryGetProperty("amneziawg", out var awgElement) &&
                  awgElement.ValueKind == JsonValueKind.Object;
        var awgActive = awg && ReadBoolean(awgElement, "active") == true;
        var awgClients = awg ? ReadArrayCount(awgElement, "clients") : 0;
        if (!availableValue.GetBoolean())
        {
            progress?.Report("ready");
            return new TelegramKeysSnapshot(false, awgActive, awgClients, devices, null, [], []);
        }

        var rawUrl = ReadString(happ, "sub_url");
        if (!TryValidateSubscriptionUrl(rawUrl, out var subscriptionUri))
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);

        var profileUri = new UriBuilder(subscriptionUri!)
        {
            Query = string.IsNullOrEmpty(subscriptionUri!.Query)
                ? "format=singbox"
                : $"{subscriptionUri.Query.TrimStart('?')}&format=singbox",
        }.Uri;
        using var request = new HttpRequestMessage(HttpMethod.Get, profileUri);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        request.Headers.UserAgent.ParseAdd("deytt-connect/windows");
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        WindowsSubscriptionDeviceIdentity.AddProfileHeaders(request, token);

        using var requestTimeout = CancellationTokenSource.CreateLinkedTokenSource(subscriptionToken);
        requestTimeout.CancelAfter(EssentialRequestTimeout);
        byte[] payload;
        progress?.Report("profile");
        try
        {
            using var response = await Http.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, requestTimeout.Token);
            payload = await ReadBoundedAsync(response.Content, requestTimeout.Token, MaxProfileBytes);
            if (response.Headers.TryGetValues("X-Deytt-Device-Blocked", out var blockedValues) &&
                blockedValues.Any(value => string.Equals(value.Trim(), "1", StringComparison.Ordinal)))
                throw new TelegramApiException("device_blocked", HttpStatusCode.Forbidden);
            if (!response.IsSuccessStatusCode)
                throw new TelegramApiException(ReadErrorCode(payload), response.StatusCode);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw new OperationCanceledException(cancellationToken);
        }

        var profileStage = "json";
        try
        {
            using var profile = JsonDocument.Parse(payload.AsMemory());
            profileStage = "utf8";
            var profileJson = new UTF8Encoding(false, true).GetString(payload);
            profileStage = "normalize";
            profileJson = WindowsSubscriptionProfileNormalizer.AddTunForEmptyInbounds(profileJson);
            using var normalizedProfile = JsonDocument.Parse(profileJson);
            progress?.Report("optional");
            profileStage = "awg";
            var awgProfiles = await FetchAwgProfilesAsync(subscriptionUri!, token, subscriptionToken);
            if (awgProfiles.Count == 0 && (awgActive || awgClients > 0))
                System.Diagnostics.Trace.TraceWarning(
                    "AmneziaWG is advertised for this account ({0} active key(s)), but no usable route profile was returned.",
                    awgClients);
            profileStage = "routes";
            var routes = WindowsRouteCatalog.Parse(normalizedProfile.RootElement, awgProfiles);
            progress?.Report("ready");
            return new TelegramKeysSnapshot(true, awgActive, awgClients, devices, profileJson, routes, awgProfiles);
        }
        catch (Exception error) when (error is JsonException or DecoderFallbackException or InvalidDataException or
                                      InvalidOperationException or KeyNotFoundException or FormatException or ArgumentException)
        {
            var diagnosticCode = SubscriptionProfileErrorCode(profileStage, error);
            System.Diagnostics.Trace.TraceError("Telegram subscription profile rejected at {0} ({1}).",
                profileStage, error.GetType().Name);
            throw new TelegramApiException(diagnosticCode, HttpStatusCode.BadGateway);
        }
    }

    private static string SubscriptionProfileErrorCode(string stage, Exception error)
    {
        if (stage != "routes" || error is not InvalidDataException invalid)
            return $"subscription_invalid_{stage}";

        return invalid.Message switch
        {
            "The subscription root is not an object." => "subscription_invalid_routes_root",
            "The subscription has no inbound list." => "subscription_invalid_routes_inbounds",
            "The subscription has no TUN inbound." => "subscription_invalid_routes_tun",
            "The subscription has an empty inbound list." => "subscription_invalid_routes_inbounds_empty",
            "The subscription has only proxy inbounds." => "subscription_invalid_routes_inbounds_proxy",
            "The subscription TUN inbound has no address." => "subscription_invalid_routes_tun_address_missing",
            "The subscription TUN address is not an array." => "subscription_invalid_routes_tun_address_shape",
            "The subscription has no outbounds." => "subscription_invalid_routes_outbounds",
            "The subscription has an empty outbound list." => "subscription_invalid_routes_outbounds_empty",
            "The subscription has no network outbounds or endpoints." => "subscription_invalid_routes_outbounds_no_network",
            "The subscription has invalid or duplicate profile tags." => "subscription_invalid_routes_tags",
            "The subscription is missing the automatic or RU-DE route." => "subscription_invalid_routes_automatic",
            "The subscription is missing both automatic and RU-DE routes." => "subscription_invalid_routes_auto_chain",
            "The subscription is missing the automatic route." => "subscription_invalid_routes_auto",
            "The subscription is missing the RU-DE route." => "subscription_invalid_routes_chain",
            "The subscription is missing a required route." => "subscription_invalid_routes_required",
            "The subscription has an unsupported route or DNS policy." => "subscription_invalid_routes_policy",
            _ => "subscription_invalid_routes",
        };
    }

    public async Task<IReadOnlyList<TelegramTariff>> GetTariffsAsync(
        CancellationToken cancellationToken = default)
    {
        using var response = await SendJsonAsync("/api/tg/tariffs", HttpMethod.Get, null, null, cancellationToken);
        if (!response.RootElement.TryGetProperty("tariffs", out var values) ||
            values.ValueKind != JsonValueKind.Array || values.GetArrayLength() > 64)
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);

        var tariffs = new List<TelegramTariff>();
        foreach (var item in values.EnumerateArray())
        {
            var code = ReadString(item, "code");
            var name = ReadString(item, "name");
            if (item.ValueKind != JsonValueKind.Object ||
                string.IsNullOrWhiteSpace(code) || code.Length > 80 || code.Any(char.IsControl) ||
                !TryReadInt32(item, "devices", out var devices) || devices is < 1 or > 10 ||
                !TryReadInt32(item, "months", out var months) || months is < 1 or > 36 ||
                !TryReadInt32(item, "rubles", out var rubles) || rubles < 0)
                continue;
            tariffs.Add(new TelegramTariff(code, SafeLabel(name, code), devices, months, rubles));
        }
        return tariffs;
    }

    public async Task<TelegramQuote> GetQuoteAsync(
        string token,
        string kind,
        string? plan = null,
        int? months = null,
        int? devices = null,
        int? extra = null,
        CancellationToken cancellationToken = default)
    {
        ValidateCheckoutKind(kind);
        var parts = new List<string> { "kind=" + Uri.EscapeDataString(kind) };
        if (!string.IsNullOrWhiteSpace(plan))
        {
            if (plan.Length > 80 || plan.Any(char.IsControl))
                throw new TelegramApiException("invalid_response", HttpStatusCode.BadRequest);
            parts.Add("plan=" + Uri.EscapeDataString(plan));
        }
        if (months is not null)
            parts.Add("months=" + months.Value);
        if (devices is not null)
            parts.Add("devices=" + devices.Value);
        if (extra is not null)
            parts.Add("extra=" + extra.Value);
        if (months is < 1 or > 36 || devices is < 1 or > 10 || extra is < 1 or > 10)
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadRequest);

        using var response = await SendJsonAsync(
            "/api/tg/quote?" + string.Join('&', parts), HttpMethod.Get, null, token, cancellationToken);
        var root = response.RootElement;
        if (!TryReadInt32(root, "devices", out var quotedDevices) || quotedDevices is < 1 or > 10 ||
            (!TryReadInt32(root, "months", out var quotedMonths) && kind != "add_devices") ||
            quotedMonths is < 0 or > 36 || quotedMonths == 0 && kind != "add_devices" ||
            !TryReadInt32(root, "rubles", out var rubles) || rubles < 0 ||
            !TryReadInt32(root, "stars", out var stars) || stars < 0)
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);
        return new TelegramQuote(ReadString(root, "name") ?? plan ?? "Подписка",
            quotedDevices, quotedMonths, rubles, stars);
    }

    public async Task<TelegramCheckout> CreateCheckoutAsync(
        string token,
        string kind,
        string method,
        string? plan = null,
        int? months = null,
        int? devices = null,
        int? extra = null,
        CancellationToken cancellationToken = default)
    {
        ValidateCheckoutKind(kind);
        if (method is not ("stars" or "platega"))
            throw new TelegramApiException("invalid_payment_method", HttpStatusCode.BadRequest);
        if (months is < 1 or > 36 || devices is < 1 or > 10 || extra is < 1 or > 10)
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadRequest);

        var body = new Dictionary<string, object?>
        {
            ["kind"] = kind,
            ["method"] = method,
        };
        if (plan is not null) body["plan"] = plan;
        if (months is not null) body["months"] = months.Value;
        if (devices is not null) body["devices"] = devices.Value;
        if (extra is not null) body["extra"] = extra.Value;

        using var response = await SendJsonAsync("/api/tg/checkout", HttpMethod.Post, body, token, cancellationToken);
        var root = response.RootElement;
        var urlProperty = method == "stars" ? "invoice_url" : "pay_url";
        var paymentUrl = ReadString(root, urlProperty);
        if (!Uri.TryCreate(paymentUrl, UriKind.Absolute, out var uri) ||
            uri.Scheme != Uri.UriSchemeHttps || uri.Port != 443 || uri.UserInfo.Length != 0)
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);
        var externalId = ReadString(root, "external_id");
        if (externalId is not null && !Regex.IsMatch(externalId, "^[A-Za-z0-9_-]{1,64}$", RegexOptions.CultureInvariant))
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);
        return new TelegramCheckout(uri.AbsoluteUri, externalId);
    }

    public async Task<string> GetPaymentStatusAsync(
        string token,
        string externalId,
        CancellationToken cancellationToken = default)
    {
        if (!Regex.IsMatch(externalId, "^[A-Za-z0-9_-]{1,64}$", RegexOptions.CultureInvariant))
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadRequest);
        using var response = await SendJsonAsync(
            "/api/tg/payment-status?external_id=" + Uri.EscapeDataString(externalId),
            HttpMethod.Get, null, token, cancellationToken);
        return ReadString(response.RootElement, "status") ??
               throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);
    }

    public async Task<IReadOnlyList<TelegramAppSession>> GetSessionsAsync(
        string token,
        CancellationToken cancellationToken = default)
    {
        using var response = await SendJsonAsync(
            "/api/tg/sessions", HttpMethod.Get, null, token, cancellationToken);
        if (!response.RootElement.TryGetProperty("sessions", out var values) ||
            values.ValueKind != JsonValueKind.Array || values.GetArrayLength() > 64)
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);

        var sessions = new List<TelegramAppSession>();
        foreach (var item in values.EnumerateArray())
        {
            var id = ReadString(item, "id");
            if (item.ValueKind != JsonValueKind.Object || string.IsNullOrWhiteSpace(id) ||
                id.Length > 128 || id.Any(char.IsControl))
                continue;
            sessions.Add(new TelegramAppSession(
                id,
                SafeLabel(ReadString(item, "label"), "deytt.connect"),
                ReadBoolean(item, "current") ?? false,
                ReadString(item, "created_at"),
                ReadString(item, "expires_at")));
        }
        return sessions;
    }

    public async Task RevokeSessionAsync(
        string token,
        string sessionId,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(sessionId) || sessionId.Length > 128 || sessionId.Any(char.IsControl))
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadRequest);
        using var response = await SendJsonAsync(
            "/api/tg/sessions/revoke", HttpMethod.Post, new { session_id = sessionId }, token, cancellationToken);
        if (ReadBoolean(response.RootElement, "ok") is false)
            throw new TelegramApiException("session_revoke_unconfirmed", HttpStatusCode.BadGateway);
    }

    public async Task ResetKeysAsync(
        string token,
        string scope,
        CancellationToken cancellationToken = default)
    {
        if (scope is not ("all" or "awg" or "happ"))
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadRequest);
        using var response = await SendJsonAsync(
            "/api/tg/keys/reset", HttpMethod.Post, new { scope }, token, cancellationToken);
        if (ReadBoolean(response.RootElement, "ok") is false)
            throw new TelegramApiException("key_reset_unconfirmed", HttpStatusCode.BadGateway);
    }

    private static void ValidateCheckoutKind(string kind)
    {
        if (kind is not ("preset" or "custom" or "add_time" or "add_devices"))
            throw new TelegramApiException("invalid_checkout_kind", HttpStatusCode.BadRequest);
    }

    private static bool TryReadInt32(JsonElement element, string propertyName, out int result)
    {
        result = 0;
        return element.ValueKind == JsonValueKind.Object &&
               element.TryGetProperty(propertyName, out var value) && value.TryGetInt32(out result);
    }

    public async Task SetHappDeviceBlockedAsync(
        string token,
        long deviceId,
        bool blocked,
        CancellationToken cancellationToken = default)
    {
        if (deviceId <= 0)
            throw new TelegramApiException("device_update_unconfirmed", HttpStatusCode.BadRequest);
        var action = blocked ? "revoke" : "restore";
        using var result = await SendJsonAsync(
            $"/api/tg/happ/devices/{action}", HttpMethod.Post,
            new { device_id = deviceId }, token, cancellationToken);
        var root = result.RootElement;
        if (!ReadBoolean(root, "ok") is true || ReadBoolean(root, "blocked") != blocked)
            throw new TelegramApiException("device_update_unconfirmed", HttpStatusCode.BadGateway);
    }

    public async Task LogoutAsync(string token, CancellationToken cancellationToken = default)
    {
        using var response = await SendJsonAsync(
            "/api/tg/logout", HttpMethod.Post, new { }, token, cancellationToken);
    }

    private static async Task<JsonDocument> SendJsonAsync(
        string path,
        HttpMethod method,
        object? body,
        string? token,
        CancellationToken cancellationToken)
    {
        if (!path.StartsWith("/api/tg/", StringComparison.Ordinal) || path.Contains("..", StringComparison.Ordinal))
            throw new InvalidOperationException("Only first-party Telegram API endpoints are allowed.");

        using var request = new HttpRequestMessage(method, new Uri(ApiBase, path));
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        request.Headers.UserAgent.ParseAdd("deytt-connect/windows");
        request.Headers.TryAddWithoutValidation("X-Deytt-Client", "deytt-connect");
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };

        if (token is not null)
        {
            if (token.Length is < 32 or > 256)
                throw new TelegramApiException("session_invalid", HttpStatusCode.Unauthorized);
            request.Headers.TryAddWithoutValidation(SessionHeader, token);
        }

        if (body is not null)
            request.Content = new ByteArrayContent(JsonSerializer.SerializeToUtf8Bytes(body));
        if (request.Content is not null)
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json") { CharSet = "utf-8" };

        using var requestTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        requestTimeout.CancelAfter(EssentialRequestTimeout);
        try
        {
            using var response = await Http.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, requestTimeout.Token);
            var payload = await ReadBoundedAsync(response.Content, requestTimeout.Token);
            if (!response.IsSuccessStatusCode)
                throw new TelegramApiException(ReadErrorCode(payload), response.StatusCode);

            try
            {
                return JsonDocument.Parse(payload.Length == 0 ? "{}"u8.ToArray().AsMemory() : payload.AsMemory());
            }
            catch (JsonException)
            {
                throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw new OperationCanceledException(cancellationToken);
        }
    }

    private static async Task<byte[]> ReadBoundedAsync(
        HttpContent content,
        CancellationToken cancellationToken,
        int maxBytes = MaxResponseBytes)
    {
        if (content.Headers.ContentLength is long length && length > maxBytes)
            throw new TelegramApiException("response_too_large", HttpStatusCode.BadGateway);

        await using var input = await content.ReadAsStreamAsync(cancellationToken);
        using var output = new MemoryStream();
        var buffer = new byte[8 * 1024];
        while (true)
        {
            var count = await input.ReadAsync(buffer.AsMemory(), cancellationToken);
            if (count == 0)
                break;
            if (output.Length + count > maxBytes)
                throw new TelegramApiException("response_too_large", HttpStatusCode.BadGateway);
            await output.WriteAsync(buffer.AsMemory(0, count), cancellationToken);
        }
        return output.ToArray();
    }

    private static async Task<IReadOnlyList<WindowsAwgProfile>> FetchAwgProfilesAsync(
        Uri subscriptionUri,
        string sessionToken,
        CancellationToken cancellationToken)
    {
        using var fetchBudget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        fetchBudget.CancelAfter(OptionalAwgFetchBudget);
        var fetchToken = fetchBudget.Token;

        // Legacy AmneziaWG 1.5 and current 3.1 are separate entitlements and
        // endpoint formats. Fetch both independently so one missing generation
        // cannot hide the other, while sharing the same optional-work deadline.
        var generations = await Task.WhenAll(
            FetchAwgProfilesForGenerationAsync(subscriptionUri, sessionToken, "31", fetchToken, cancellationToken),
            FetchAwgProfilesForGenerationAsync(subscriptionUri, sessionToken, "15", fetchToken, cancellationToken));
        cancellationToken.ThrowIfCancellationRequested();

        return MergeAwgProfiles(generations);
    }

    internal static IReadOnlyList<WindowsAwgProfile> MergeAwgProfiles(
        params IReadOnlyList<WindowsAwgProfile>[] generations)
    {
        if (generations.Length == 0)
            return [];

        var profiles = new List<WindowsAwgProfile>(
            Math.Min(MaxAwgProfileCount, generations.Sum(items => items.Count)));
        var profilesBytes = 0;
        var maxGenerationCount = generations.Max(items => items.Count);
        for (var index = 0; index < maxGenerationCount && profiles.Count < MaxAwgProfileCount; index++)
        {
            foreach (var generation in generations)
            {
                if (index >= generation.Count)
                    continue;
                if (profiles.Count == MaxAwgProfileCount)
                    break;
                var profile = generation[index];
                var bodyBytes = Encoding.UTF8.GetByteCount(profile.Config);
                if (profilesBytes + bodyBytes > MaxAwgProfilesTotalBytes)
                    continue;
                profiles.Add(profile);
                profilesBytes += bodyBytes;
            }
        }
        return profiles;
    }

    private static async Task<IReadOnlyList<WindowsAwgProfile>> FetchAwgProfilesForGenerationAsync(
        Uri subscriptionUri,
        string sessionToken,
        string generation,
        CancellationToken fetchToken,
        CancellationToken cancellationToken)
    {
        AwgDownload? first;
        try
        {
            first = await DownloadAwgProfileAsync(subscriptionUri, null, generation, sessionToken, fetchToken);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return [];
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (TelegramApiException error) when (!error.IsUnauthorized)
        {
            return [];
        }
        catch (Exception error) when (IsNonFatal(error))
        {
            return [];
        }

        if (first is null)
            return [];

        IReadOnlyList<AwgServer> servers;
        if (string.IsNullOrWhiteSpace(first.Manifest))
        {
            if (TryValidateAwg(first.Config))
                return [CreateAwgProfile(generation, null, "Основной", "AWG", first.Config!)];
            return [];
        }

        try
        {
            if (Encoding.UTF8.GetByteCount(first.Manifest) > MaxAwgManifestBytes)
                return [];
            using var manifest = JsonDocument.Parse(first.Manifest);
            if (manifest.RootElement.ValueKind != JsonValueKind.Array ||
                manifest.RootElement.GetArrayLength() > 16)
                return [];
            var parsedServers = new List<AwgServer>();
            var ids = new HashSet<string>(StringComparer.Ordinal);
            foreach (var item in manifest.RootElement.EnumerateArray())
            {
                if (item.ValueKind != JsonValueKind.Object || ReadString(item, "id") is not { Length: > 0 } id ||
                    id.Length > 128 || id.Any(char.IsControl) || !ids.Add(id))
                    return [];
                parsedServers.Add(new AwgServer(
                    id,
                    SafeLabel(ReadString(item, "label"), id),
                    SafeLabel(ReadString(item, "short_label"), id.ToUpperInvariant())));
            }
            servers = parsedServers;
        }
        catch (JsonException)
        {
            return [];
        }

        if (servers.Count == 0)
            return TryValidateAwg(first.Config)
                ? [CreateAwgProfile(generation, null, "Основной", "AWG", first.Config!)]
                : [];

        var configs = new string?[servers.Count];
        if (TryValidateAwg(first.Config))
            configs[0] = first.Config;

        try
        {
            await Parallel.ForEachAsync(
                Enumerable.Range(0, servers.Count),
                new ParallelOptions
                {
                    CancellationToken = fetchToken,
                    MaxDegreeOfParallelism = MaxConcurrentAwgProfileRequests,
                },
                async (index, token) =>
                {
                    if (configs[index] is not null)
                        return;

                    try
                    {
                        var response = await DownloadAwgProfileAsync(
                            subscriptionUri, servers[index].Id, generation, sessionToken, token);
                        if (TryValidateAwg(response?.Config))
                            configs[index] = response!.Config;
                    }
                    catch (TelegramApiException error) when (!error.IsUnauthorized)
                    {
                        // An unavailable optional AWG edge does not invalidate the core profile.
                    }
                    catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested && !fetchToken.IsCancellationRequested)
                    {
                        // Skip an individually timed-out optional edge while the overall budget remains.
                    }
                    catch (OperationCanceledException)
                    {
                        throw;
                    }
                    catch (Exception error) when (IsNonFatal(error))
                    {
                        // Keep other successfully fetched optional edges.
                    }
                });
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            // Return profiles completed before the optional-fetch deadline.
        }

        cancellationToken.ThrowIfCancellationRequested();

        var profiles = new List<WindowsAwgProfile>(servers.Count);
        var profilesBytes = 0;
        for (var index = 0; index < servers.Count; index++)
        {
            var body = configs[index];
            if (body is null)
                continue;

            var server = servers[index];
            var bodyBytes = Encoding.UTF8.GetByteCount(body);
            if (profilesBytes + bodyBytes <= MaxAwgProfilesTotalBytes)
            {
                profiles.Add(CreateAwgProfile(generation, server.Id, server.Label, server.ShortLabel, body));
                profilesBytes += bodyBytes;
            }
        }
        return profiles;
    }

    private static bool IsNonFatal(Exception error) =>
        error is not (OutOfMemoryException or StackOverflowException or AccessViolationException);

    private static async Task<AwgDownload?> DownloadAwgProfileAsync(
        Uri subscriptionUri,
        string? serverId,
        string generation,
        string sessionToken,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, BuildAwgUri(subscriptionUri, serverId, generation));
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/plain"));
        request.Headers.UserAgent.ParseAdd("deytt-connect/windows");
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        WindowsSubscriptionDeviceIdentity.AddProfileHeaders(request, sessionToken);

        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        var manifest = response.Headers.TryGetValues(AwgServersHeader, out var values)
            ? values.FirstOrDefault()
            : null;
        if (response.StatusCode == HttpStatusCode.NotFound)
            return null;
        if (response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
            throw new TelegramApiException("subscription_unavailable", response.StatusCode);
        if (!response.IsSuccessStatusCode)
            return null;

        var bytes = await ReadBoundedAsync(response.Content, cancellationToken,
            WindowsAwgProfileParser.MaximumProfileBytes);
        try
        {
            return new AwgDownload(new UTF8Encoding(false, true).GetString(bytes), manifest);
        }
        catch (DecoderFallbackException)
        {
            return null;
        }
    }

    internal static Uri BuildAwgUri(Uri subscriptionUri, string? serverId, string generation = "31")
    {
        if (generation is not ("15" or "31"))
            throw new ArgumentOutOfRangeException(nameof(generation));
        var builder = new UriBuilder(subscriptionUri);
        var parts = builder.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Where(part =>
            {
                var separator = part.IndexOf('=');
                var key = separator < 0 ? part : part[..separator];
                return !Uri.UnescapeDataString(key).Equals("format", StringComparison.OrdinalIgnoreCase) &&
                       !Uri.UnescapeDataString(key).Equals("version", StringComparison.OrdinalIgnoreCase) &&
                       !Uri.UnescapeDataString(key).Equals("server_id", StringComparison.OrdinalIgnoreCase);
            })
            .ToList();
        parts.Add(generation == "15" ? "format=amneziawg" : "format=amneziawg31");
        if (serverId is not null)
            parts.Add("server_id=" + Uri.EscapeDataString(serverId));
        builder.Query = string.Join('&', parts);
        return builder.Uri;
    }

    private static bool TryValidateAwg(string? config)
    {
        try
        {
            _ = WindowsAwgProfileParser.Parse(config, "deytt-validation");
            return true;
        }
        catch (InvalidDataException)
        {
            return false;
        }
        catch (Exception error) when (IsNonFatal(error))
        {
            return false;
        }
    }

    private static WindowsAwgProfile CreateAwgProfile(
        string generation, string? serverId, string label, string shortLabel, string config)
    {
        var routePrefix = "awg" + generation;
        var routeId = serverId is null ? routePrefix : routePrefix + ":" + Base64Url(Encoding.UTF8.GetBytes(serverId));
        return new WindowsAwgProfile(routeId, serverId, label, shortLabel, config, generation);
    }

    private static string Base64Url(byte[] value) =>
        Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static string SafeLabel(string? value, string fallback)
    {
        var normalized = value?.Trim();
        return string.IsNullOrEmpty(normalized) || normalized.Length > 128 || normalized.Any(char.IsControl)
            ? fallback
            : normalized;
    }

    private static string ReadErrorCode(byte[] payload)
    {
        try
        {
            using var document = JsonDocument.Parse(payload);
            return OptionalString(document.RootElement, "error") ?? "request_failed";
        }
        catch (JsonException)
        {
            return "request_failed";
        }
    }

    private static string RequiredString(JsonElement element, string propertyName) =>
        OptionalString(element, propertyName) is { Length: > 0 } value
            ? value
            : throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);

    private static JsonElement RequiredObject(JsonElement element, string propertyName)
    {
        if (element.ValueKind != JsonValueKind.Object ||
            !element.TryGetProperty(propertyName, out var value) ||
            value.ValueKind != JsonValueKind.Object)
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);
        return value;
    }

    private static string? OptionalString(JsonElement element, string propertyName) =>
        element.ValueKind == JsonValueKind.Object &&
        element.TryGetProperty(propertyName, out var value) &&
        value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static string RequiredBotUrl(JsonElement element, string propertyName)
    {
        var value = RequiredString(element, propertyName);
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) ||
            uri.Scheme != Uri.UriSchemeHttps ||
            !uri.Host.Equals("t.me", StringComparison.OrdinalIgnoreCase) ||
            uri.Port != 443)
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);
        return uri.AbsoluteUri;
    }

    private static bool TryValidateSubscriptionUrl(string? value, out Uri? uri)
    {
        uri = null;
        if (string.IsNullOrWhiteSpace(value) || value.Length > 2048 ||
            !Uri.TryCreate(value.Trim(), UriKind.Absolute, out var parsed))
            return false;
        var host = parsed.Host.TrimEnd('.');
        var allowedHost = host.Equals("deytt.space", StringComparison.OrdinalIgnoreCase) ||
                          host.EndsWith(".deytt.space", StringComparison.OrdinalIgnoreCase);
        var pathParts = parsed.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (!parsed.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase) ||
            !allowedHost || parsed.UserInfo.Length != 0 || parsed.Port != 443 ||
            parsed.Fragment.Length != 0 || pathParts.Length != 3 ||
            pathParts[0] != "sub" || pathParts[1] != "token" ||
            !Regex.IsMatch(pathParts[2], "^[A-Za-z0-9_-]{32,128}$", RegexOptions.CultureInvariant))
            return false;
        uri = parsed;
        return true;
    }

    private static IReadOnlyList<TelegramHappDevice> ParseHappDevices(JsonElement happ)
    {
        if (!happ.TryGetProperty("devices", out var array) || array.ValueKind != JsonValueKind.Array)
            return [];
        var devices = new List<TelegramHappDevice>();
        foreach (var item in array.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object ||
                !item.TryGetProperty("id", out var idValue) || !idValue.TryGetInt64(out var id) || id <= 0)
                continue;
            devices.Add(new TelegramHappDevice(
                id,
                ReadBoolean(item, "blocked") == true,
                ReadString(item, "os") ?? "",
                ReadString(item, "model") ?? "",
                ReadString(item, "last_seen"))
            {
                OsVersion = ReadString(item, "os_version"),
                BlockedAt = ReadString(item, "blocked_at"),
            });
        }
        return devices;
    }

    private static int ReadArrayCount(JsonElement element, string propertyName) =>
        element.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.Array
            ? value.GetArrayLength()
            : 0;

    private static string? ReadString(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) &&
        value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static bool? ReadBoolean(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) &&
        value.ValueKind is JsonValueKind.True or JsonValueKind.False ? value.GetBoolean() : null;
}

public sealed record PairingStart(string Challenge, string BotUrl, string Delivery);
public sealed record VerifiedPairing(string Token, TelegramAccount Account);
public sealed record TelegramTariff(string Code, string Name, int Devices, int Months, int Rubles);
public sealed record TelegramQuote(string Name, int Devices, int Months, int Rubles, int Stars);
public sealed record TelegramCheckout(string PaymentUrl, string? ExternalId);
public sealed record TelegramAppSession(string Id, string Label, bool Current, string? CreatedAt, string? ExpiresAt);
public sealed record TelegramHappDevice(long Id, bool Blocked, string Os, string Model, string? LastSeen)
{
    public string? OsVersion { get; init; }
    public string? BlockedAt { get; init; }
}
public sealed record TelegramKeysSnapshot(
    bool HappAvailable,
    bool AmneziaActive,
    int AmneziaClients,
    IReadOnlyList<TelegramHappDevice> HappDevices,
    string? ProfileJson,
    IReadOnlyList<WindowsRoute> Routes,
    IReadOnlyList<WindowsAwgProfile> AwgProfiles);
public sealed record WindowsAwgProfile(
    string RouteId, string? ServerId, string Label, string ShortLabel, string Config, string Generation = "31");
internal sealed record AwgServer(string Id, string Label, string ShortLabel);
internal sealed record AwgDownload(string Config, string? Manifest);
public sealed record TelegramAccount(
    string Username,
    string FirstName,
    bool Blocked,
    TelegramSubscription? Subscription)
{
    public string LastName { get; init; } = "";
    public string? RegisteredAt { get; init; }
    public long? ServiceDays { get; init; }

    public static TelegramAccount ParseProfile(JsonElement profile)
    {
        if (profile.ValueKind != JsonValueKind.Object)
            throw new TelegramApiException("invalid_response", HttpStatusCode.BadGateway);

        var username = ReadString(profile, "username") ?? "";
        var firstName = ReadString(profile, "first_name") ?? "";
        var blocked = ReadBoolean(profile, "blocked") ?? false;
        TelegramSubscription? subscription = null;
        if (profile.TryGetProperty("subscription", out var value) && value.ValueKind == JsonValueKind.Object)
        {
            subscription = new TelegramSubscription(
                ReadBoolean(value, "active") ?? false,
                ReadBoolean(value, "paid_active") ?? false,
                ReadString(value, "access_type") ?? "none",
                ReadString(value, "tariff_name") ?? ReadString(value, "tariff_code"),
                ReadString(value, "expires_at"),
                ReadBoolean(value, "unlimited_time") ?? false,
                ReadInt32(value, "device_limit"),
                ReadInt32(value, "devices_used"),
                ReadInt64(value, "traffic_limit_bytes"),
                ReadInt64(value, "traffic_used_bytes"))
            {
                TrafficTotalBytes = ReadInt64(value, "traffic_total_bytes"),
            };
        }
        return new TelegramAccount(username, firstName, blocked, subscription)
        {
            LastName = ReadString(profile, "last_name") ?? "",
            RegisteredAt = ReadString(profile, "registered_at"),
            ServiceDays = ReadInt64(profile, "service_days"),
        };
    }

    private static string? ReadString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static bool? ReadBoolean(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? value.GetBoolean()
            : null;

    private static int? ReadInt32(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number &&
        value.TryGetInt32(out var result) ? result : null;

    private static long? ReadInt64(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number &&
        value.TryGetInt64(out var result) ? result : null;
}

public sealed record TelegramSubscription(
    bool Active,
    bool PaidActive,
    string AccessType,
    string? TariffName,
    string? ExpiresAt,
    bool UnlimitedTime,
    int? DeviceLimit,
    int? DevicesUsed,
    long? TrafficLimitBytes,
    long? TrafficUsedBytes)
{
    public long? TrafficTotalBytes { get; init; }
}

public sealed class TelegramApiException(string code, HttpStatusCode statusCode) : Exception(code)
{
    public string Code { get; } = code;
    public HttpStatusCode StatusCode { get; } = statusCode;
    public bool IsUnauthorized => StatusCode == HttpStatusCode.Unauthorized || Code is "session_invalid" or "session_expired";
}

