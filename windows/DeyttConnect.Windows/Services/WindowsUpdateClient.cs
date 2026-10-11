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
    string? AssetDigest,
    WindowsUpdatePackageKind PackageKind)
{
    public bool CanDownloadVerifiedPackage =>
        AssetSize is > 0 and <= WindowsUpdateClient.MaximumPackageSizeBytes &&
        WindowsUpdateClient.TryParseSha256Digest(AssetDigest, out _) &&
        Uri.TryCreate(AssetUrl, UriKind.Absolute, out var uri) &&
        WindowsUpdateClient.IsOfficialReleaseUrl(uri, "download", Tag, AssetName);
}
public enum WindowsUpdatePackageKind
{
    PortableZip,
    Msi,
}

public sealed record VerifiedWindowsUpdatePackage(string FilePath, string Sha256, long Size);

public static class WindowsUpdateClient
{
    public const long MaximumPackageSizeBytes = 512L * 1024 * 1024;
    private const string LegacyWindowsAssetName = "deytt-connect-windows-x64.zip";

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
        CancellationToken cancellationToken,
        WindowsUpdatePackageKind? preferredKind = null)
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
        return ParseLatestWindowsReleaseResponse(json, preferredKind);
    }

    internal static WindowsUpdateRelease? ParseLatestWindowsReleaseResponse(
        string json,
        WindowsUpdatePackageKind? preferredKind = null)
    {
        using var releases = JsonDocument.Parse(json);
        if (releases.RootElement.ValueKind != JsonValueKind.Array)
            throw new JsonException("The GitHub release response was not a list.");

        WindowsUpdateRelease? latestCandidate = null;
        Version? latestVersion = null;
        foreach (var release in releases.RootElement.EnumerateArray())
        {
            // Stable-channel selection fails closed when GitHub omits or malforms channel metadata.
            if (GetJsonBoolean(release, "draft") != false || GetJsonBoolean(release, "prerelease") != false)
                continue;

            var tag = GetJsonString(release, "tag_name");
            if (tag is null || !TryParseReleaseVersion(tag, out var releaseVersion) ||
                !IsOfficialReleaseUrl(GetJsonString(release, "html_url"), "tag", tag))
                continue;
            if (!release.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
                continue;

            foreach (var asset in assets.EnumerateArray())
            {
                var name = GetJsonString(asset, "name");
                if (name is null || !TryGetSupportedWindowsAsset(tag, name, out var packageKind))
                    continue;

                var rawUrl = GetJsonString(asset, "browser_download_url");
                var size = GetJsonInt64(asset, "size");
                var candidate = new WindowsUpdateRelease(
                    tag,
                    GetJsonString(release, "name") ?? tag,
                    GetJsonString(release, "body") is { } body ? TrimReleaseNotes(body) : "",
                    name,
                    IsOfficialReleaseUrl(rawUrl, "download", tag, name) ? rawUrl : null,
                    size,
                    GetJsonString(asset, "digest"),
                    packageKind);
                if (preferredKind is not null && packageKind != preferredKind)
                    continue;

                if (latestVersion is null || CompareReleaseVersions(releaseVersion, latestVersion) > 0)
                {
                    latestVersion = releaseVersion;
                    latestCandidate = candidate;
                }
            }
        }

        return latestCandidate;
    }

    internal static bool TryParseReleaseVersion(string? value, out Version version)
    {
        version = new Version(0, 0, 0);
        if (string.IsNullOrWhiteSpace(value))
            return false;

        var normalized = value.Trim();
        if (normalized.StartsWith('v') || normalized.StartsWith('V'))
            normalized = normalized[1..];
        if (normalized.Contains('-') || normalized.Contains('+'))
            return false;

        var components = normalized.Split('.');
        if (components.Length is < 3 or > 4)
            return false;
        var parts = new int[components.Length];
        for (var index = 0; index < components.Length; index++)
        {
            var component = components[index];
            if (component.Length == 0 || component.Any(character => !char.IsAsciiDigit(character)) ||
                !int.TryParse(component, System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out parts[index]))
                return false;
        }
        version = parts.Length == 3
            ? new Version(parts[0], parts[1], parts[2])
            : new Version(parts[0], parts[1], parts[2], parts[3]);
        return true;
    }

    internal static bool IsNewerThan(string? releaseTag, Version? installedVersion) =>
        installedVersion is not null && TryParseReleaseVersion(releaseTag, out var releaseVersion) &&
        CompareReleaseVersions(releaseVersion, installedVersion) > 0;

    private static int CompareReleaseVersions(Version left, Version right)
    {
        var leftParts = new[] { left.Major, left.Minor, Math.Max(left.Build, 0), Math.Max(left.Revision, 0) };
        var rightParts = new[] { right.Major, right.Minor, Math.Max(right.Build, 0), Math.Max(right.Revision, 0) };
        for (var index = 0; index < leftParts.Length; index++)
        {
            var comparison = leftParts[index].CompareTo(rightParts[index]);
            if (comparison != 0)
                return comparison;
        }
        return 0;
    }

    private static bool TryGetSupportedWindowsAsset(
        string tag,
        string name,
        out WindowsUpdatePackageKind packageKind)
    {
        packageKind = WindowsUpdatePackageKind.PortableZip;
        if (!TryParseReleaseVersion(tag, out var version))
            return false;
        var normalized = $"{version.Major}.{version.Minor}.{version.Build}";
        if (version.Revision >= 0)
            normalized += $".{version.Revision}";

        if (string.Equals(name, LegacyWindowsAssetName, StringComparison.Ordinal))
            return true;
        if (string.Equals(name, $"deytt-connect-{normalized}-portable.zip", StringComparison.Ordinal))
            return true;
        if (string.Equals(name, $"deytt-connect-{normalized}.msi", StringComparison.Ordinal))
        {
            packageKind = WindowsUpdatePackageKind.Msi;
            return true;
        }
        return false;
    }

    internal static WindowsUpdatePackageKind GetCurrentInstallationKind(string appBaseDirectory) =>
        WindowsPortableLayout.FindPortableRoot(appBaseDirectory) is null
            ? WindowsUpdatePackageKind.Msi
            : WindowsUpdatePackageKind.PortableZip;

    internal static Version? GetInstalledVersion() => typeof(WindowsUpdateClient).Assembly.GetName().Version;

    internal static bool IsReleaseForInstallation(WindowsUpdateRelease release, WindowsUpdatePackageKind kind) =>
        release.PackageKind == kind && IsNewerThan(release.Tag, GetInstalledVersion());

    public static async Task<VerifiedWindowsUpdatePackage> DownloadAndVerifyAsync(
        WindowsUpdateRelease release,
        Action<long, long>? onProgress,
        CancellationToken cancellationToken)
    {
        if (!release.CanDownloadVerifiedPackage ||
            !Uri.TryCreate(release.AssetUrl, UriKind.Absolute, out var assetUri) ||
            !TryParseSha256Digest(release.AssetDigest, out var expectedDigest) ||
            release.AssetSize is not { } expectedSize)
            throw new InvalidDataException("The release does not include a verifiable Windows package.");

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(DownloadTimeout);

        var updatesDirectory = GetUpdatesDirectory();
        Directory.CreateDirectory(updatesDirectory);
        var safeTag = SanitizeTag(release.Tag);
        var extension = release.PackageKind == WindowsUpdatePackageKind.Msi ? ".msi" : ".zip";
        var outputPath = Path.Combine(updatesDirectory, $"deytt-connect-windows-x64-{safeTag}{extension}");
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
                            throw new InvalidDataException("The Windows package exceeded its declared size limit.");

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
                throw new InvalidDataException("The Windows package SHA-256 does not match GitHub metadata.");

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

    public static async Task LaunchVerifiedMsiInstallerAsync(
        VerifiedWindowsUpdatePackage package,
        CancellationToken cancellationToken)
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Windows Installer is available only on Windows.");
        if (!string.Equals(Path.GetExtension(package.FilePath), ".msi", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("The verified update package is not an MSI.");

        await VerifySavedPackageAsync(package, cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();
        var start = new ProcessStartInfo("msiexec.exe")
        {
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Normal,
        };
        start.ArgumentList.Add("/i");
        start.ArgumentList.Add(package.FilePath);
        _ = Process.Start(start) ?? throw new InvalidOperationException("Windows Installer did not start.");
    }

    private static async Task VerifySavedPackageAsync(
        VerifiedWindowsUpdatePackage package,
        CancellationToken cancellationToken)
    {
        if (!File.Exists(package.FilePath))
            throw new FileNotFoundException("The verified Windows package is no longer available.", package.FilePath);
        await using var archive = new FileStream(package.FilePath, FileMode.Open, FileAccess.Read, FileShare.Read,
            64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (archive.Length != package.Size)
            throw new InvalidDataException("The verified Windows package changed after download.");
        var actualDigest = await SHA256.HashDataAsync(archive, cancellationToken);
        if (!TryParseSha256Digest($"sha256:{package.Sha256}", out var expectedDigest) ||
            !CryptographicOperations.FixedTimeEquals(actualDigest, Convert.FromHexString(expectedDigest)))
            throw new InvalidDataException("The verified Windows package changed after download.");
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
            throw new InvalidDataException("The Windows package URL is not an official GitHub release asset.");

        var currentUri = initialUri;
        for (var redirect = 0; redirect <= MaximumRedirects; redirect++)
        {
            if (!IsAllowedAssetUri(currentUri))
                throw new InvalidDataException("GitHub redirected the Windows package to an untrusted host.");

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
                    throw new InvalidDataException("GitHub redirected the Windows package to an untrusted host.");
                currentUri = nextUri;
                continue;
            }

            response.Dispose();
            throw new HttpRequestException("GitHub did not return the Windows package asset.");
        }

        throw new HttpRequestException("The Windows package download used too many redirects.");
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
