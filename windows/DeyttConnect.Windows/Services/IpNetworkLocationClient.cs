using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;

namespace DeyttConnect.Windows.Services;

public sealed record WindowsNetworkLocation(
    double Latitude,
    double Longitude,
    string City,
    string Region,
    string CountryCode)
{
    public string PlaceLabel => string.Join(", ", new[] { City, Region, CountryCode }
        .Where(value => !string.IsNullOrWhiteSpace(value))
        .Distinct(StringComparer.OrdinalIgnoreCase));
}

/// <summary>Resolves only approximate public-IP location data after the user opts in.</summary>
public static class IpNetworkLocationClient
{
    private const int MaxResponseBytes = 8_192;
    private static readonly HttpClient Client = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = false,
        UseCookies = false,
        ConnectTimeout = TimeSpan.FromMilliseconds(3_500),
    })
    {
        Timeout = TimeSpan.FromSeconds(7),
    };

    public static async Task<WindowsNetworkLocation> FetchAsync(CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://ipinfo.io/json");
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        request.Headers.CacheControl = new CacheControlHeaderValue { NoStore = true };

        using var response = await Client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        if (response.StatusCode != HttpStatusCode.OK)
            throw new HttpRequestException("IP location request was not successful.");
        if (response.Content.Headers.ContentLength is > MaxResponseBytes)
            throw new InvalidDataException("IP location response exceeds its size limit.");

        await using var responseStream = await response.Content.ReadAsStreamAsync(cancellationToken)
            .ConfigureAwait(false);
        using var payload = new MemoryStream();
        var buffer = new byte[2_048];
        while (true)
        {
            var read = await responseStream.ReadAsync(buffer.AsMemory(), cancellationToken).ConfigureAwait(false);
            if (read == 0)
                break;
            if (payload.Length + read > MaxResponseBytes)
                throw new InvalidDataException("IP location response exceeds its size limit.");
            await payload.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
        }

        using var json = JsonDocument.Parse(payload.ToArray());
        var coordinates = ReadString(json.RootElement, "loc").Split(',', StringSplitOptions.TrimEntries);
        if (coordinates.Length != 2 ||
            !double.TryParse(coordinates[0], System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var latitude) ||
            !double.TryParse(coordinates[1], System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var longitude) ||
            !double.IsFinite(latitude) || !double.IsFinite(longitude) ||
            latitude is < -85 or > 85 || longitude is < -180 or > 180)
            throw new InvalidDataException("IP location response has invalid coordinates.");

        return new WindowsNetworkLocation(
            latitude,
            longitude,
            ReadPlacePart(json.RootElement, "city"),
            ReadPlacePart(json.RootElement, "region"),
            ReadCountryCode(json.RootElement));
    }

    private static string ReadPlacePart(JsonElement value, string propertyName)
    {
        var text = ReadString(value, propertyName);
        return new string(text.Where(character => !char.IsControl(character)).Take(64).ToArray()).Trim();
    }

    private static string ReadCountryCode(JsonElement value)
    {
        var code = ReadString(value, "country").Trim().ToUpperInvariant();
        return code.Length == 2 && code.All(character => character is >= 'A' and <= 'Z') ? code : string.Empty;
    }

    private static string ReadString(JsonElement value, string propertyName) =>
        value.TryGetProperty(propertyName, out var property) && property.ValueKind == JsonValueKind.String
            ? property.GetString() ?? string.Empty
            : string.Empty;
}
