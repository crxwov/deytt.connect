using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;

[assembly: System.Runtime.CompilerServices.InternalsVisibleTo("DeyttConnect.Windows.Tests")]

namespace DeyttConnect.Windows.Services;

public sealed record WindowsUpdateRelease(
    string Tag,
    string Name,
    string Notes,
    string AssetName,
    string? AssetUrl,
    long? AssetSize,
    string? AssetDigest)
{
    public bool CanDownloadVerifiedPackage =>
        AssetSize is > 0 and <= WindowsUpdateClient.MaximumPackageSizeBytes &&
        WindowsUpdateClient.TryParseSha256Digest(AssetDigest, out _) &&
        Uri.TryCreate(AssetUrl, UriKind.Absolute, out var uri) &&
        WindowsUpdateClient.IsOfficialReleaseUrl(uri, "download", Tag, AssetName);
}
public sealed record VerifiedWindowsUpdatePackage(string FilePath, string Sha256, long Size);

public static class WindowsUpdateClient
{
    public const long MaximumPackageSizeBytes = 512L * 1024 * 1024;

    private const int MaximumReleaseResponseBytes = 2 * 1024 * 1024;
    private const int MaximumRedirects = 5;
    private static readonly TimeSpan ReleaseCheckTimeout = TimeSpan.FromSeconds(12);
    private static readonly TimeSpan DownloadTimeout = TimeSpan.FromMinutes(10);

    private static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = false,
        ConnectTimeout = TimeSpan.FromSeconds(15),
        UseCookies = false,
    })
    {
        Timeout = Timeout.InfiniteTimeSpan,
    };

    public static async Task<WindowsUpdateRelease?> FindLatestWindowsReleaseAsync(
        CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(ReleaseCheckTimeout);

        using var request = new HttpRequestMessage(HttpMethod.Get,
            "https://api.github.com/repos/crxwov/deytt.connect/releases?per_page=30");
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        request.Headers.UserAgent.ParseAdd("deytt-connect/windows");
        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        response.EnsureSuccessStatusCode();

        var json = await ReadBoundedTextAsync(response.Content, MaximumReleaseResponseBytes, timeout.Token);
        return ParseLatestWindowsReleaseResponse(json);
    }

    internal static WindowsUpdateRelease? ParseLatestWindowsReleaseResponse(string json)
    {
        using var releases = JsonDocument.Parse(json);
        if (releases.RootElement.ValueKind != JsonValueKind.Array)
            throw new JsonException("The GitHub release response was not a list.");

        foreach (var release in releases.RootElement.EnumerateArray())
        {
            // Stable-channel selection fails closed when GitHub omits or malforms channel metadata.
            if (GetJsonBoolean(release, "draft") != false || GetJsonBoolean(release, "prerelease") != false)
                continue;

            var tag = GetJsonString(release, "tag_name");
            if (tag is null || !IsOfficialReleaseUrl(GetJsonString(release, "html_url"), "tag", tag))
                continue;
            if (!release.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
                continue;

            foreach (var asset in assets.EnumerateArray())
            {
                var name = GetJsonString(asset, "name");
                if (name is null || !name.Contains("windows", StringComparison.OrdinalIgnoreCase) ||
                    !name.Contains("x64", StringComparison.OrdinalIgnoreCase) ||
                    !name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
                    continue;

                var rawUrl = GetJsonString(asset, "browser_download_url");
                var size = GetJsonInt64(asset, "size");
                return new WindowsUpdateRelease(
                    tag,
                    GetJsonString(release, "name") ?? tag,
                    GetJsonString(release, "body") is { } body ? TrimReleaseNotes(body) : "",
                    name,
                    IsOfficialReleaseUrl(rawUrl, "download", tag, name) ? rawUrl : null,
                    size,
                    GetJsonString(asset, "digest"));
            }
        }

        return null;
    }

    public static async Task<VerifiedWindowsUpdatePackage> DownloadAndVerifyAsync(
        WindowsUpdateRelease release,
        Action<long, long>? onProgress,
        CancellationToken cancellationToken)
    {
        if (!release.CanDownloadVerifiedPackage ||
            !Uri.TryCreate(release.AssetUrl, UriKind.Absolute, out var assetUri) ||
            !TryParseSha256Digest(release.AssetDigest, out var expectedDigest) ||
            release.AssetSize is not { } expectedSize)
            throw new InvalidDataException("The release does not include a verifiable Windows ZIP.");

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(DownloadTimeout);

        var updatesDirectory = GetUpdatesDirectory();
        Directory.CreateDirectory(updatesDirectory);
        var safeTag = SanitizeTag(release.Tag);
        var outputPath = Path.Combine(updatesDirectory, $"deytt-connect-windows-x64-{safeTag}.zip");
        var partialPath = outputPath + ".part";

        try
        {
            using var response = await SendAssetRequestAsync(assetUri, release.Tag, release.AssetName, timeout.Token);
            if (response.Content.Headers.ContentLength is { } contentLength && contentLength != expectedSize)
                throw new InvalidDataException("The downloaded ZIP size does not match GitHub metadata.");

            await using var source = await response.Content.ReadAsStreamAsync(timeout.Token);
            long received = 0;
            string actualDigest;
            using (var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
            {
                await using (var destination = new FileStream(partialPath, FileMode.Create, FileAccess.Write,
                                 FileShare.None, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
                {
                    var buffer = new byte[64 * 1024];
                    while (true)
                    {
                        var count = await source.ReadAsync(buffer, timeout.Token);
                        if (count == 0)
                            break;

                        received += count;
                        if (received > expectedSize || received > MaximumPackageSizeBytes)
                            throw new InvalidDataException("The Windows ZIP exceeded its declared size limit.");

                        hash.AppendData(buffer, 0, count);
                        await destination.WriteAsync(buffer.AsMemory(0, count), timeout.Token);
                        onProgress?.Invoke(received, expectedSize);
                    }

                    await destination.FlushAsync(timeout.Token);
                }

                actualDigest = Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
            }

            if (received != expectedSize)
                throw new InvalidDataException("The downloaded ZIP size does not match GitHub metadata.");
            if (!CryptographicOperations.FixedTimeEquals(
                    Convert.FromHexString(actualDigest), Convert.FromHexString(expectedDigest)))
                throw new InvalidDataException("The Windows ZIP SHA-256 does not match GitHub metadata.");

            timeout.Token.ThrowIfCancellationRequested();
            File.Move(partialPath, outputPath, overwrite: true);
            return new VerifiedWindowsUpdatePackage(outputPath, actualDigest, received);
        }
        catch
        {
            TryDelete(partialPath);
            throw;
        }
    }

    public static async Task OpenVerifiedPackageInExplorerAsync(
        VerifiedWindowsUpdatePackage package,
        CancellationToken cancellationToken)
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Explorer is available only on Windows.");
        if (!File.Exists(package.FilePath))
            throw new FileNotFoundException("The verified Windows ZIP is no longer available.", package.FilePath);

        await using var archive = new FileStream(package.FilePath, FileMode.Open, FileAccess.Read, FileShare.Read,
            64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (archive.Length != package.Size)
            throw new InvalidDataException("The verified Windows ZIP changed after download.");
        var actualDigest = await SHA256.HashDataAsync(archive, cancellationToken);
        if (!TryParseSha256Digest($"sha256:{package.Sha256}", out var expectedDigest) ||
            !CryptographicOperations.FixedTimeEquals(actualDigest, Convert.FromHexString(expectedDigest)))
            throw new InvalidDataException("The verified Windows ZIP changed after download.");
        cancellationToken.ThrowIfCancellationRequested();

        var start = new ProcessStartInfo("explorer.exe")
        {
            UseShellExecute = true,
        };
        start.ArgumentList.Add("/select,");
        start.ArgumentList.Add(package.FilePath);
        Process.Start(start);
    }

    internal static bool TryParseSha256Digest(string? digest, out string normalizedDigest)
    {
        normalizedDigest = "";
        if (digest is null || !digest.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase))
            return false;

        var hex = digest["sha256:".Length..];
        if (hex.Length != 64 || hex.Any(character => !Uri.IsHexDigit(character)))
            return false;

        normalizedDigest = hex.ToLowerInvariant();
        return true;
    }

    private static bool IsOfficialReleaseUrl(
        string? rawUrl,
        string pathType,
        string? expectedTag,
        string? expectedAssetName = null) =>
        Uri.TryCreate(rawUrl, UriKind.Absolute, out var uri) &&
        IsOfficialReleaseUrl(uri, pathType, expectedTag, expectedAssetName);

    internal static bool IsOfficialReleaseUrl(
        Uri uri,
        string pathType,
        string? expectedTag,
        string? expectedAssetName = null)
    {
        if (uri.Scheme != Uri.UriSchemeHttps || !uri.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase) ||
            uri.UserInfo.Length != 0 || uri.Port != 443 || uri.Query.Length != 0 || uri.Fragment.Length != 0)
            return false;

        var segments = uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
        var expectedLength = pathType == "download" ? 6 : 5;
        if (segments.Length != expectedLength || segments[0] != "crxwov" || segments[1] != "deytt.connect" ||
            segments[2] != "releases" || segments[3] != pathType || expectedTag is null ||
            !Uri.UnescapeDataString(segments[4]).Equals(expectedTag, StringComparison.Ordinal))
            return false;

        return pathType != "download" || expectedAssetName is not null &&
            Uri.UnescapeDataString(segments[5]).Equals(expectedAssetName, StringComparison.Ordinal);
    }

    private static async Task<HttpResponseMessage> SendAssetRequestAsync(
        Uri initialUri,
        string expectedTag,
        string expectedAssetName,
        CancellationToken cancellationToken)
    {
        if (!IsOfficialReleaseUrl(initialUri, "download", expectedTag, expectedAssetName))
            throw new InvalidDataException("The Windows ZIP URL is not an official GitHub release asset.");

        var currentUri = initialUri;
        for (var redirect = 0; redirect <= MaximumRedirects; redirect++)
        {
            if (!IsAllowedAssetUri(currentUri))
                throw new InvalidDataException("GitHub redirected the Windows ZIP to an untrusted host.");

            using var request = new HttpRequestMessage(HttpMethod.Get, currentUri);
            request.Headers.UserAgent.ParseAdd("deytt-connect/windows");
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/octet-stream"));
            var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (response.IsSuccessStatusCode)
                return response;

            if (IsRedirect(response.StatusCode) && redirect < MaximumRedirects &&
                response.Headers.Location is { } location)
            {
                var nextUri = location.IsAbsoluteUri ? location : new Uri(currentUri, location);
                response.Dispose();
                if (!IsAllowedAssetUri(nextUri))
                    throw new InvalidDataException("GitHub redirected the Windows ZIP to an untrusted host.");
                currentUri = nextUri;
                continue;
            }

            response.Dispose();
            throw new HttpRequestException("GitHub did not return the Windows ZIP asset.");
        }

        throw new HttpRequestException("The Windows ZIP download used too many redirects.");
    }

    private static bool IsAllowedAssetUri(Uri uri) =>
        uri.Scheme == Uri.UriSchemeHttps && uri.UserInfo.Length == 0 && uri.Port == 443 &&
        (uri.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase) ||
         uri.Host.Equals("githubusercontent.com", StringComparison.OrdinalIgnoreCase) ||
         uri.Host.EndsWith(".githubusercontent.com", StringComparison.OrdinalIgnoreCase));

    private static bool IsRedirect(HttpStatusCode status) =>
        status is HttpStatusCode.MovedPermanently or HttpStatusCode.Redirect or HttpStatusCode.RedirectMethod or
            HttpStatusCode.TemporaryRedirect or HttpStatusCode.PermanentRedirect;

    private static string GetUpdatesDirectory()
    {
        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(localAppData))
            throw new IOException("The per-user local application data directory is unavailable.");
        return Path.Combine(localAppData, "DEYTT", "Connect", "updates");
    }

    private static async Task<string> ReadBoundedTextAsync(
        HttpContent content,
        int maximumBytes,
        CancellationToken cancellationToken)
    {
        await using var stream = await content.ReadAsStreamAsync(cancellationToken);
        using var output = new MemoryStream();
        var buffer = new byte[16 * 1024];
        while (true)
        {
            var read = await stream.ReadAsync(buffer, cancellationToken);
            if (read == 0)
                break;
            if (output.Length + read > maximumBytes)
                throw new InvalidDataException("The GitHub release response is too large.");
            output.Write(buffer, 0, read);
        }

        return new System.Text.UTF8Encoding(false, true).GetString(output.ToArray());
    }

    private static string? GetJsonString(JsonElement element, string property) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(property, out var value) &&
        value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static long? GetJsonInt64(JsonElement element, string property) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(property, out var value) &&
        value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var result) ? result : null;

    private static bool? GetJsonBoolean(JsonElement element, string property) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(property, out var value) &&
        value.ValueKind is JsonValueKind.True or JsonValueKind.False ? value.GetBoolean() : null;

    private static string TrimReleaseNotes(string notes)
    {
        var clean = new string(notes.Where(character => !char.IsControl(character) || character is '\n' or '\t')
            .Take(1800).ToArray()).Trim();
        return clean.Length == 0 ? "" : clean;
    }

    private static string SanitizeTag(string tag)
    {
        var safe = new string(tag.Select(character => char.IsAsciiLetterOrDigit(character) ||
            character is '.' or '_' or '-' ? character : '_').Take(80).ToArray()).Trim('_', '.', '-');
        return string.IsNullOrWhiteSpace(safe) ? "release" : safe;
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
