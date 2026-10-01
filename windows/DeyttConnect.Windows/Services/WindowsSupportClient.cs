using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace DeyttConnect.Windows.Services;

public enum SupportDocumentKind
{
    Terms,
    Privacy,
}

public sealed record SupportTicket(int Id, bool IsOpen);

public sealed record SupportMessage(string Sender, string Text)
{
    public bool IsFromSupport => Sender.Equals("support", StringComparison.OrdinalIgnoreCase);
}

public sealed record SupportThreadSnapshot(
    SupportTicket? Ticket,
    IReadOnlyList<SupportMessage> Messages);

public sealed record SupportDocumentInline(string Text, Uri? NavigateUri = null);

public sealed record SupportDocumentBlock(IReadOnlyList<SupportDocumentInline> Inlines, bool IsHeading);

public sealed record SupportDocument(
    SupportDocumentKind Kind,
    IReadOnlyList<SupportDocumentBlock> Blocks);

public interface IWindowsSupportClient
{
    Task<long> GetAccountIdAsync(string token, CancellationToken cancellationToken = default);
    Task<SupportThreadSnapshot> GetThreadAsync(string token, CancellationToken cancellationToken = default);
    Task CreateTicketAsync(string token, string text, CancellationToken cancellationToken = default);
    Task SendMessageAsync(string token, int ticketId, string text, CancellationToken cancellationToken = default);
    Task CloseTicketAsync(string token, int ticketId, CancellationToken cancellationToken = default);
    Task<SupportDocument> GetDocumentAsync(SupportDocumentKind kind, CancellationToken cancellationToken = default);
}

public sealed class WindowsSupportException(string code, HttpStatusCode statusCode) : Exception(code)
{
    public string Code { get; } = code;
    public HttpStatusCode StatusCode { get; } = statusCode;
}

/// <summary>First-party support API and the canonical legal document source.</summary>
public sealed class WindowsSupportClient : IWindowsSupportClient
{
    private static readonly Uri ApiBase = new("https://deytt.space");
    private static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = false,
        UseCookies = false,
        AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
    })
    {
        Timeout = TimeSpan.FromSeconds(45),
    };

    private const string SessionHeader = "X-TG-App-Token";
    private const int MaxResponseBytes = 512 * 1024;
    private static readonly Regex SectionPattern = new(
        "<section\\b(?=[^>]*\\bid\\s*=\\s*['\\\"](?<id>terms|privacy)['\\\"])[^>]*>(?<body>[\\s\\S]*?)</section\\s*>",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex ExecutableMarkupPattern = new(
        "<(script|style|iframe|object|template)\\b[^>]*>[\\s\\S]*?</\\1\\s*>",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex CommentPattern = new("<!--([\\s\\S]*?)-->", RegexOptions.Compiled);
    private static readonly Regex TagPattern = new("<[^>]+>", RegexOptions.Compiled);
    private static readonly Regex AnchorPattern = new(
        "<a\\b(?<attributes>[^>]*)>(?<text>[\\s\\S]*?)</a\\s*>",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex HrefPattern = new(
        "\\bhref\\s*=\\s*(?:\"(?<double>[^\"]*)\"|'(?<single>[^']*)'|(?<bare>[^\\s>]+))",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex HorizontalWhitespacePattern = new("[\\t \\u00a0]+", RegexOptions.Compiled);
    private static readonly Regex ExcessNewlinesPattern = new("\\n{3,}", RegexOptions.Compiled);
    private const char LinkStartMarker = '\uE000';
    private const char LinkValueSeparator = '\u001F';

    public async Task<long> GetAccountIdAsync(string token, CancellationToken cancellationToken = default)
    {
        using var response = await SendApiAsync("/api/tg/me", HttpMethod.Get, null, token, cancellationToken);
        if (!response.RootElement.TryGetProperty("profile", out var profile) ||
            profile.ValueKind != JsonValueKind.Object ||
            !profile.TryGetProperty("tg_id", out var id) || !id.TryGetInt64(out var accountId) || accountId <= 0)
            throw new WindowsSupportException("invalid_response", HttpStatusCode.BadGateway);
        return accountId;
    }

    public async Task<SupportThreadSnapshot> GetThreadAsync(
        string token,
        CancellationToken cancellationToken = default)
    {
        using var response = await SendApiAsync("/api/tg/support", HttpMethod.Get, null, token, cancellationToken);
        var root = response.RootElement;

        SupportTicket? ticket = null;
        if (root.TryGetProperty("ticket", out var ticketValue) && ticketValue.ValueKind == JsonValueKind.Object &&
            TryGetInt32(ticketValue, "id", out var id) && id > 0)
        {
            ticket = new SupportTicket(
                id,
                ReadString(ticketValue, "status")?.Equals("open", StringComparison.OrdinalIgnoreCase) == true);
        }

        var messages = new List<SupportMessage>();
        if (root.TryGetProperty("messages", out var messagesValue) && messagesValue.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in messagesValue.EnumerateArray().Take(256))
            {
                if (item.ValueKind != JsonValueKind.Object)
                    continue;
                var text = ReadString(item, "text");
                var sender = ReadString(item, "sender");
                if (string.IsNullOrWhiteSpace(text) || text.Length > 16_000 || string.IsNullOrWhiteSpace(sender))
                    continue;
                messages.Add(new SupportMessage(sender, text));
            }
        }

        return new SupportThreadSnapshot(ticket, messages);
    }

    public async Task CreateTicketAsync(
        string token,
        string text,
        CancellationToken cancellationToken = default)
    {
        var clean = ValidateMessage(text, minimumLength: 5);
        using var response = await SendApiAsync(
            "/api/tg/support",
            HttpMethod.Post,
            new { text = clean, category = "other" },
            token,
            cancellationToken);
    }

    public async Task SendMessageAsync(
        string token,
        int ticketId,
        string text,
        CancellationToken cancellationToken = default)
    {
        if (ticketId <= 0)
            throw new WindowsSupportException("invalid_response", HttpStatusCode.BadRequest);
        var clean = ValidateMessage(text, minimumLength: 1);
        using var response = await SendApiAsync(
            "/api/tg/support/messages",
            HttpMethod.Post,
            new { ticket_id = ticketId, text = clean },
            token,
            cancellationToken);
    }

    public async Task CloseTicketAsync(
        string token,
        int ticketId,
        CancellationToken cancellationToken = default)
    {
        if (ticketId <= 0)
            throw new WindowsSupportException("invalid_response", HttpStatusCode.BadRequest);
        using var response = await SendApiAsync(
            "/api/tg/support/close",
            HttpMethod.Post,
            new { ticket_id = ticketId },
            token,
            cancellationToken);
    }

    public async Task<SupportDocument> GetDocumentAsync(
        SupportDocumentKind kind,
        CancellationToken cancellationToken = default)
    {
        var sectionId = kind switch
        {
            SupportDocumentKind.Terms => "terms",
            SupportDocumentKind.Privacy => "privacy",
            _ => throw new ArgumentOutOfRangeException(nameof(kind)),
        };

        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(ApiBase, "/info/"));
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/html"));
        request.Headers.UserAgent.ParseAdd("deytt-connect/windows");
        request.Headers.TryAddWithoutValidation("X-Deytt-Client", "deytt-connect");
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };

        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (response.StatusCode != HttpStatusCode.OK)
            throw new WindowsSupportException("document_unavailable", response.StatusCode);

        var bytes = await ReadBoundedAsync(response.Content, cancellationToken);
        string html;
        try
        {
            html = new System.Text.UTF8Encoding(false, true).GetString(bytes);
        }
        catch (System.Text.DecoderFallbackException)
        {
            throw new WindowsSupportException("invalid_document", HttpStatusCode.BadGateway);
        }

        var section = SectionPattern.Matches(html)
            .Cast<Match>()
            .FirstOrDefault(match => match.Groups["id"].Value.Equals(sectionId, StringComparison.OrdinalIgnoreCase));
        if (section is null)
            throw new WindowsSupportException("document_missing", HttpStatusCode.BadGateway);

        return new SupportDocument(kind, ParseDocumentBlocks(section.Groups["body"].Value));
    }

    private static async Task<JsonDocument> SendApiAsync(
        string path,
        HttpMethod method,
        object? body,
        string token,
        CancellationToken cancellationToken)
    {
        if (path is not ("/api/tg/me" or "/api/tg/support" or "/api/tg/support/messages" or "/api/tg/support/close"))
            throw new InvalidOperationException("Only the support API endpoints are allowed.");
        if (token.Length is < 32 or > 256)
            throw new WindowsSupportException("session_invalid", HttpStatusCode.Unauthorized);

        using var request = new HttpRequestMessage(method, new Uri(ApiBase, path));
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        request.Headers.UserAgent.ParseAdd("deytt-connect/windows");
        request.Headers.TryAddWithoutValidation("X-Deytt-Client", "deytt-connect");
        request.Headers.TryAddWithoutValidation(SessionHeader, token);
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        if (body is not null)
        {
            request.Content = new ByteArrayContent(JsonSerializer.SerializeToUtf8Bytes(body));
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json") { CharSet = "utf-8" };
        }

        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        var payload = await ReadBoundedAsync(response.Content, cancellationToken);
        if (!response.IsSuccessStatusCode)
            throw new WindowsSupportException(ReadErrorCode(payload), response.StatusCode);

        try
        {
            return JsonDocument.Parse(payload.Length == 0 ? "{}"u8.ToArray().AsMemory() : payload.AsMemory());
        }
        catch (JsonException)
        {
            throw new WindowsSupportException("invalid_response", HttpStatusCode.BadGateway);
        }
    }

    private static async Task<byte[]> ReadBoundedAsync(HttpContent content, CancellationToken cancellationToken)
    {
        if (content.Headers.ContentLength is long length && length > MaxResponseBytes)
            throw new WindowsSupportException("response_too_large", HttpStatusCode.BadGateway);

        await using var input = await content.ReadAsStreamAsync(cancellationToken);
        using var output = new MemoryStream();
        var buffer = new byte[8 * 1024];
        while (true)
        {
            var count = await input.ReadAsync(buffer.AsMemory(), cancellationToken);
            if (count == 0)
                break;
            if (output.Length + count > MaxResponseBytes)
                throw new WindowsSupportException("response_too_large", HttpStatusCode.BadGateway);
            await output.WriteAsync(buffer.AsMemory(0, count), cancellationToken);
        }
        return output.ToArray();
    }

    private static IReadOnlyList<SupportDocumentBlock> ParseDocumentBlocks(string sectionHtml)
    {
        var safeHtml = ExecutableMarkupPattern.Replace(sectionHtml, " ");
        safeHtml = CommentPattern.Replace(safeHtml, " ");
        var links = new List<Uri>();
        safeHtml = AnchorPattern.Replace(safeHtml, match =>
        {
            var label = TagPattern.Replace(match.Groups["text"].Value, " ");
            label = Regex.Replace(label, "\\s+", " ").Trim()
                .Replace(LinkStartMarker, ' ').Replace(LinkValueSeparator, ' ');
            if (label.Length == 0)
                return string.Empty;

            var attributes = HrefPattern.Match(match.Groups["attributes"].Value);
            var rawUri = attributes.Groups["double"].Success ? attributes.Groups["double"].Value :
                attributes.Groups["single"].Success ? attributes.Groups["single"].Value :
                attributes.Groups["bare"].Value;
            if (!TryGetSafeHttpsUri(rawUri, out var uri))
                return label;

            var linkIndex = links.Count;
            links.Add(uri);
            return $"{LinkStartMarker}{linkIndex}{LinkValueSeparator}{label}{LinkStartMarker}";
        });
        safeHtml = Regex.Replace(safeHtml, "<br\\b[^>]*>", "\n", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        safeHtml = Regex.Replace(safeHtml, "<li\\b[^>]*>", "\n• ", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        safeHtml = Regex.Replace(safeHtml, "</(p|li|div|section|article|h[1-6]|blockquote|tr)>", "\n", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        safeHtml = Regex.Replace(safeHtml, "<h[1-6]\\b[^>]*>", "\n# ", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        var text = WebUtility.HtmlDecode(TagPattern.Replace(safeHtml, " ")) ?? string.Empty;
        text = HorizontalWhitespacePattern.Replace(text, " ");
        text = ExcessNewlinesPattern.Replace(text, "\n\n");

        return text.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(line => line.Trim())
            .Where(line => line.Length > 0)
            .Select(line =>
            {
                var heading = line.StartsWith("# ", StringComparison.Ordinal);
                var value = heading ? line[2..].Trim() : line;
                return new SupportDocumentBlock(ParseDocumentInlines(value, links), heading);
            })
            .ToArray();
    }

    private static IReadOnlyList<SupportDocumentInline> ParseDocumentInlines(string text, IReadOnlyList<Uri> links)
    {
        var inlines = new List<SupportDocumentInline>();
        var cursor = 0;
        while (cursor < text.Length)
        {
            var marker = text.IndexOf(LinkStartMarker, cursor);
            if (marker < 0)
            {
                AddPlain(text[cursor..]);
                break;
            }

            AddPlain(text[cursor..marker]);
            var separator = text.IndexOf(LinkValueSeparator, marker + 1);
            var end = separator < 0 ? -1 : text.IndexOf(LinkStartMarker, separator + 1);
            if (separator < 0 || end < 0 ||
                !int.TryParse(text.AsSpan(marker + 1, separator - marker - 1), out var linkIndex))
            {
                AddPlain(LinkStartMarker.ToString());
                cursor = marker + 1;
                continue;
            }

            var label = text[(separator + 1)..end];
            if ((uint)linkIndex < (uint)links.Count)
                inlines.Add(new SupportDocumentInline(label, links[linkIndex]));
            else
                AddPlain(label);
            cursor = end + 1;
        }

        return inlines;

        void AddPlain(string value)
        {
            if (value.Length == 0)
                return;
            if (inlines.Count > 0 && inlines[^1].NavigateUri is null)
                inlines[^1] = inlines[^1] with { Text = inlines[^1].Text + value };
            else
                inlines.Add(new SupportDocumentInline(value));
        }
    }

    private static bool TryGetSafeHttpsUri(string value, out Uri uri)
    {
        var decoded = WebUtility.HtmlDecode(value).Trim();
        if (!decoded.Any(char.IsControl) && Uri.TryCreate(decoded, UriKind.Absolute, out var parsed) &&
            parsed.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase) &&
            !string.IsNullOrWhiteSpace(parsed.Host) && string.IsNullOrEmpty(parsed.UserInfo))
        {
            uri = parsed;
            return true;
        }

        uri = null!;
        return false;
    }

    private static string ValidateMessage(string text, int minimumLength)
    {
        var clean = text.Trim();
        if (clean.Length < minimumLength || clean.Length > 4000)
            throw new WindowsSupportException("message_length_invalid", HttpStatusCode.BadRequest);
        return clean;
    }

    private static bool TryGetInt32(JsonElement element, string name, out int value)
    {
        value = 0;
        return element.ValueKind == JsonValueKind.Object &&
               element.TryGetProperty(name, out var item) && item.TryGetInt32(out value);
    }

    private static string? ReadString(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) &&
        value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static string ReadErrorCode(byte[] payload)
    {
        try
        {
            using var document = JsonDocument.Parse(payload);
            return ReadString(document.RootElement, "error") ?? "request_failed";
        }
        catch (JsonException)
        {
            return "request_failed";
        }
    }
}
