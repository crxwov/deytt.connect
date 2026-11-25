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
    WindowsUpdatePackageKind PackageKind,
    string? PackageVersion = null)
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
    Deb,
    Rpm,
    Arch,
}

public sealed record VerifiedWindowsUpdatePackage(string FilePath, string Sha256, long Size);

public static class WindowsUpdateClient
{
    public const long MaximumPackageSizeBytes = 512L * 1024 * 1024;
    private const string LegacyWindowsAssetName = "deytt-connect-windows-x64.zip";
    private const string ServerUpdateManifestUrl = "https://deytt.space/connect/releases/update.json";
    private const string GitHubReleasesUrl = "https://api.github.com/repos/crxwov/deytt.connect/releases?per_page=30";

    private const int MaximumReleaseResponseBytes = 2 * 1024 * 1024;
    private const int MaximumRedirects = 5;
    private static readonly TimeSpan ServerCheckTimeout = TimeSpan.FromSeconds(6);
    private static readonly TimeSpan ReleaseCheckTimeout = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan DownloadTimeout = TimeSpan.FromMinutes(10);

    private static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = false,
        ConnectTimeout = TimeSpan.FromSeconds(15),
        UseCookies = false,
        SslOptions = new System.Net.Security.SslClientAuthenticationOptions
        {
            CertificateRevocationCheckMode = System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck,
        },
    })
    {
        Timeout = Timeout.InfiniteTimeSpan,
    };

    public static async Task<WindowsUpdateRelease?> FindLatestWindowsReleaseAsync(
        CancellationToken cancellationToken,
        WindowsUpdatePackageKind? preferredKind = null)
    {
        // Prefer the fast first-party mirror (no DPI/rate-limits), then consult GitHub
        // for fallback assets, newer releases, or release notes missing from update.json.
        WindowsUpdateRelease? serverRelease = null;
        Exception? serverError = null;
        try
        {
            serverRelease = await FetchServerManifestReleaseAsync(cancellationToken, preferredKind);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception error)
        {
            serverError = error;
        }

        WindowsUpdateRelease? githubRelease = null;
        Exception? githubError = null;
        var needGitHub = serverRelease is null || string.IsNullOrWhiteSpace(serverRelease.Notes);
        if (needGitHub || serverRelease is not null)
        {
            try
            {
                githubRelease = await FetchGitHubReleaseAsync(cancellationToken, preferredKind);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception error)
            {
                githubError = error;
            }
        }

        if (serverRelease is null && githubRelease is null)
        {
            if (githubError is not null)
                System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(githubError).Throw();
            if (serverError is not null)
                System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(serverError).Throw();
            return null;
        }

        return MergeServerAndGitHubRelease(serverRelease, githubRelease);
    }

    private static async Task<WindowsUpdateRelease?> FetchServerManifestReleaseAsync(
        CancellationToken cancellationToken,
        WindowsUpdatePackageKind? preferredKind)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(ServerCheckTimeout);

        using var request = new HttpRequestMessage(HttpMethod.Get, ServerUpdateManifestUrl);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        request.Headers.UserAgent.ParseAdd(OperatingSystem.IsLinux() ? "deytt-connect/linux" : "deytt-connect/windows");
        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        response.EnsureSuccessStatusCode();

        var json = await ReadBoundedTextAsync(response.Content, MaximumReleaseResponseBytes, timeout.Token);
        return ParseServerManifestResponse(json, preferredKind);
    }

    private static async Task<WindowsUpdateRelease?> FetchGitHubReleaseAsync(
        CancellationToken cancellationToken,
        WindowsUpdatePackageKind? preferredKind)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(ReleaseCheckTimeout);

        using var request = new HttpRequestMessage(HttpMethod.Get, GitHubReleasesUrl);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        request.Headers.UserAgent.ParseAdd(OperatingSystem.IsLinux() ? "deytt-connect/linux" : "deytt-connect/windows");
        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        response.EnsureSuccessStatusCode();

        var json = await ReadBoundedTextAsync(response.Content, MaximumReleaseResponseBytes, timeout.Token);
        return ParseLatestWindowsReleaseResponse(json, preferredKind);
    }

    internal static WindowsUpdateRelease? MergeServerAndGitHubRelease(
        WindowsUpdateRelease? serverRelease,
        WindowsUpdateRelease? githubRelease)
    {
        if (serverRelease is null)
            return githubRelease;
        if (githubRelease is null)
            return serverRelease;

        if (!TryGetReleaseEffectiveVersion(serverRelease, out var serverVersion))
            return githubRelease;
        if (!TryGetReleaseEffectiveVersion(githubRelease, out var githubVersion))
            return serverRelease;

        var cmp = CompareReleaseVersions(serverVersion, githubVersion);
        if (cmp < 0)
            return githubRelease;

        // Same or newer version on server mirror: prefer server URL, enriching notes from GitHub if needed.
        if (string.IsNullOrWhiteSpace(serverRelease.Notes) && !string.IsNullOrWhiteSpace(githubRelease.Notes))
            return serverRelease with { Notes = githubRelease.Notes };
        return serverRelease;
    }

    internal static WindowsUpdateRelease? ParseServerManifestResponse(
        string json,
        WindowsUpdatePackageKind? preferredKind = null)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
            return null;

        var rawTag = GetJsonString(root, "tag") ?? GetJsonString(root, "version");
        var rawVersion = StripReleasePrefix(GetJsonString(root, "version") ?? rawTag);
        if (!TryParseReleaseVersion(rawVersion, out var manifestVersion))
            return null;

        var tag = string.IsNullOrWhiteSpace(rawTag) ? $"connect./{rawVersion}" : rawTag.Trim();
        var name = GetJsonString(root, "name")?.Trim();
        if (string.IsNullOrWhiteSpace(name))
            name = tag;
        var rawNotes = GetJsonString(root, "notes") ?? GetJsonString(root, "changelog") ?? GetJsonString(root, "body") ?? "";
        var notes = TrimReleaseNotes(rawNotes);

        var targetKind = preferredKind ?? (OperatingSystem.IsLinux()
            ? WindowsUpdatePackageKind.Deb
            : WindowsUpdatePackageKind.Msi);
        var (urlKeys, sizeKeys, digestKeys) = targetKind switch
        {
            WindowsUpdatePackageKind.Msi => (
                new[] { "windows_msi_url", "msi_url", "windows_url" },
                new[] { "windows_msi_size", "msi_size", "windows_size" },
                new[] { "windows_msi_sha256", "windows_msi_digest", "msi_sha256", "msi_digest", "windows_sha256" }),
            WindowsUpdatePackageKind.PortableZip => (
                new[] { "windows_zip_url", "portable_url", "zip_url" },
                new[] { "windows_zip_size", "portable_size", "zip_size" },
                new[] { "windows_zip_sha256", "windows_zip_digest", "portable_sha256", "zip_sha256" }),
            WindowsUpdatePackageKind.Deb => (new[] { "deb_url" }, new[] { "deb_size" }, new[] { "deb_sha256" }),
            WindowsUpdatePackageKind.Rpm => (new[] { "rpm_url" }, new[] { "rpm_size" }, new[] { "rpm_sha256" }),
            WindowsUpdatePackageKind.Arch => (new[] { "arch_url" }, new[] { "arch_size" }, new[] { "arch_sha256" }),
            _ => (Array.Empty<string>(), Array.Empty<string>(), Array.Empty<string>()),
        };

        var assetUrl = ReadFirstString(root, urlKeys);
        var assetSize = ReadFirstInt64(root, sizeKeys);
        var assetDigest = NormalizeDigest(ReadFirstString(root, digestKeys));
        if (string.IsNullOrWhiteSpace(assetUrl) || !Uri.TryCreate(assetUrl, UriKind.Absolute, out var uri))
            return null;

        var assetName = Path.GetFileName(Uri.UnescapeDataString(uri.AbsolutePath));
        if (string.IsNullOrWhiteSpace(assetName) || !IsOfficialReleaseUrl(uri, "download", tag, assetName))
            return null;

        var effectiveVersion = manifestVersion;
        if (targetKind is WindowsUpdatePackageKind.Msi or WindowsUpdatePackageKind.PortableZip)
        {
            if (TryGetSupportedWindowsAsset(tag, assetName, out var parsedKind, out var parsedVersion))
            {
                if (parsedKind != targetKind)
                    return null;
                effectiveVersion = parsedVersion;
            }
            else
            {
                return null;
            }
        }
        else if (!TryGetLinuxPackageVersion(assetName, targetKind, out effectiveVersion))
        {
            return null;
        }

        var candidate = new WindowsUpdateRelease(
            tag,
            name!,
            notes,
            assetName,
            assetUrl,
            assetSize,
            assetDigest,
            targetKind,
            FormatVersion(effectiveVersion));
        return candidate.CanDownloadVerifiedPackage ? candidate : null;
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
        var linux = preferredKind is WindowsUpdatePackageKind.Deb or WindowsUpdatePackageKind.Rpm or WindowsUpdatePackageKind.Arch;
        foreach (var release in releases.RootElement.EnumerateArray())
        {
            // Stable-channel selection fails closed when GitHub omits or malforms channel metadata.
            if (GetJsonBoolean(release, "draft") != false || GetJsonBoolean(release, "prerelease") != false)
                continue;

            var tag = GetJsonString(release, "tag_name");
            if (tag is null || !IsOfficialReleaseUrl(GetJsonString(release, "html_url"), "tag", tag))
                continue;
            var versionTag = StripReleasePrefix(tag);
            if (!TryParseReleaseVersion(versionTag, out var releaseVersion))
                continue;
            if (!release.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
                continue;

            foreach (var asset in assets.EnumerateArray())
            {
                var name = GetJsonString(asset, "name");
                if (name is null)
                    continue;
                var packageKind = preferredKind.GetValueOrDefault();
                var candidateVersion = releaseVersion;
                if (linux
                    ? !TryGetLinuxPackageVersion(name, packageKind, out candidateVersion)
                    : !TryGetSupportedWindowsAsset(tag, name, out packageKind, out candidateVersion))
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
                    packageKind,
                    FormatVersion(candidateVersion));
                if (preferredKind is not null && packageKind != preferredKind)
                    continue;

                if (linux && !candidate.CanDownloadVerifiedPackage)
                    continue;
                if (latestVersion is null || CompareReleaseVersions(candidateVersion, latestVersion) > 0)
                {
                    latestVersion = candidateVersion;
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

    internal static string StripReleasePrefix(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return "";
        var trimmed = value.Trim();
        if (trimmed.StartsWith("connect./", StringComparison.OrdinalIgnoreCase))
            return trimmed["connect./".Length..];
        if (trimmed.StartsWith("connect/", StringComparison.OrdinalIgnoreCase))
            return trimmed["connect/".Length..];
        return trimmed;
    }

    internal static bool IsNewerThan(string? releaseTag, Version? installedVersion) =>
        installedVersion is not null && TryParseReleaseVersion(StripReleasePrefix(releaseTag), out var releaseVersion) &&
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

    private static string FormatVersion(Version version) => version.Revision >= 0
        ? $"{version.Major}.{version.Minor}.{version.Build}.{version.Revision}"
        : $"{version.Major}.{version.Minor}.{version.Build}";

    private static bool TryGetSupportedWindowsAsset(
        string tag,
        string name,
        out WindowsUpdatePackageKind packageKind,
        out Version assetVersion)
    {
        packageKind = WindowsUpdatePackageKind.PortableZip;
        assetVersion = new Version(0, 0, 0);
        if (!TryParseReleaseVersion(StripReleasePrefix(tag), out var tagVersion))
            return false;

        if (string.Equals(name, LegacyWindowsAssetName, StringComparison.Ordinal))
        {
            assetVersion = tagVersion;
            return true;
        }

        if (name.StartsWith("DEYTTConnect-Windows-", StringComparison.Ordinal))
        {
            if (name.EndsWith("-portable.zip", StringComparison.Ordinal) &&
                TryParseReleaseVersion(name["DEYTTConnect-Windows-".Length..^"-portable.zip".Length], out assetVersion))
                return true;
            if (name.EndsWith(".msi", StringComparison.Ordinal) &&
                TryParseReleaseVersion(name["DEYTTConnect-Windows-".Length..^".msi".Length], out assetVersion))
            {
                packageKind = WindowsUpdatePackageKind.Msi;
                return true;
            }
        }

        if (name.StartsWith("deytt-connect-", StringComparison.Ordinal))
        {
            if (name.EndsWith("-portable.zip", StringComparison.Ordinal) &&
                TryParseReleaseVersion(name["deytt-connect-".Length..^"-portable.zip".Length], out assetVersion))
                return true;
            if (name.EndsWith(".msi", StringComparison.Ordinal) &&
                TryParseReleaseVersion(name["deytt-connect-".Length..^".msi".Length], out assetVersion))
            {
                packageKind = WindowsUpdatePackageKind.Msi;
                return true;
            }
        }

        return false;
    }

    internal static WindowsUpdatePackageKind GetCurrentInstallationKind(string appBaseDirectory)
    {
        if (OperatingSystem.IsLinux())
        {
            var distribution = File.ReadAllLines("/etc/os-release")
                .Where(line => line.StartsWith("ID=", StringComparison.Ordinal) ||
                               line.StartsWith("ID_LIKE=", StringComparison.Ordinal))
                .SelectMany(line => line[(line.IndexOf('=') + 1)..].Trim('"', '\'').Split(' '));
            if (distribution.Contains("arch"))
                return WindowsUpdatePackageKind.Arch;
            if (distribution.Any(id => id is "fedora" or "rhel" or "centos" or "rocky" or "almalinux"))
                return WindowsUpdatePackageKind.Rpm;
            return WindowsUpdatePackageKind.Deb;
        }

        return WindowsPortableLayout.FindPortableRoot(appBaseDirectory) is null
            ? WindowsUpdatePackageKind.Msi
            : WindowsUpdatePackageKind.PortableZip;
    }

    private static bool TryGetLinuxPackageVersion(string name, WindowsUpdatePackageKind kind, out Version version)
    {
        var (prefix, suffix) = kind switch
        {
            WindowsUpdatePackageKind.Deb => ("deytt-connect_", "_amd64.deb"),
            WindowsUpdatePackageKind.Rpm => ("deytt-connect-", "-1.x86_64.rpm"),
            WindowsUpdatePackageKind.Arch => ("deytt-connect-", "-1-x86_64.pkg.tar.zst"),
            _ => ("", ""),
        };
        version = new Version();
        return prefix.Length > 0 && name.Length > prefix.Length + suffix.Length &&
               name.StartsWith(prefix, StringComparison.Ordinal) && name.EndsWith(suffix, StringComparison.Ordinal) &&
               TryParseReleaseVersion(name[prefix.Length..^suffix.Length], out version) && version.Revision < 0;
    }

    internal static Version? GetInstalledVersion() => typeof(WindowsUpdateClient).Assembly.GetName().Version;

    internal static bool TryGetReleaseEffectiveVersion(WindowsUpdateRelease release, out Version version)
    {
        if (!string.IsNullOrWhiteSpace(release.PackageVersion) &&
            TryParseReleaseVersion(release.PackageVersion, out version))
            return true;
        if (release.PackageKind is WindowsUpdatePackageKind.Msi or WindowsUpdatePackageKind.PortableZip &&
            TryGetSupportedWindowsAsset(release.Tag, release.AssetName, out _, out version))
            return true;
        if (release.PackageKind is WindowsUpdatePackageKind.Deb or WindowsUpdatePackageKind.Rpm or WindowsUpdatePackageKind.Arch &&
            TryGetLinuxPackageVersion(release.AssetName, release.PackageKind, out version))
            return true;
        return TryParseReleaseVersion(StripReleasePrefix(release.Tag), out version);
    }

    internal static string GetDisplayVersion(WindowsUpdateRelease release) =>
        TryGetReleaseEffectiveVersion(release, out var version)
            ? FormatVersion(version)
            : StripReleasePrefix(release.Tag);

    internal static bool IsReleaseForInstallation(WindowsUpdateRelease release, WindowsUpdatePackageKind kind)
    {
        if (release.PackageKind != kind || GetInstalledVersion() is not { } installed)
            return false;
        return TryGetReleaseEffectiveVersion(release, out var available) &&
               CompareReleaseVersions(available, installed) > 0;
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
            throw new InvalidDataException("The release does not include a verifiable Windows package.");

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(DownloadTimeout);

        var updatesDirectory = GetUpdatesDirectory();
        Directory.CreateDirectory(updatesDirectory);
        var safeTag = SanitizeTag(release.Tag);
        var extension = release.PackageKind == WindowsUpdatePackageKind.Msi ? ".msi" : ".zip";
        var packageName = release.PackageKind is WindowsUpdatePackageKind.Msi or WindowsUpdatePackageKind.PortableZip
            ? $"deytt-connect-windows-x64-{safeTag}{extension}"
            : Path.GetFileName(release.AssetName);
        var outputPath = Path.Combine(updatesDirectory, packageName);
        var partialPath = outputPath + "." + Guid.NewGuid().ToString("N") + ".part";

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
        if (uri.Scheme != Uri.UriSchemeHttps || uri.UserInfo.Length != 0 || uri.Port != 443 ||
            uri.Query.Length != 0 || uri.Fragment.Length != 0)
            return false;

        var decodedPath = Uri.UnescapeDataString(uri.AbsolutePath);
        if (uri.Host.Equals("deytt.space", StringComparison.OrdinalIgnoreCase) ||
            uri.Host.Equals("ru.deytt.space", StringComparison.OrdinalIgnoreCase))
        {
            if (pathType == "tag")
            {
                return decodedPath is "/download/" or "/download" or "/connect/" ||
                       decodedPath.StartsWith("/connect/releases/", StringComparison.Ordinal);
            }
            if (pathType == "download")
            {
                if (string.IsNullOrWhiteSpace(expectedAssetName) ||
                    expectedAssetName.Contains('/') || expectedAssetName.Contains('\\') ||
                    expectedAssetName.Contains("..", StringComparison.Ordinal))
                    return false;
                return decodedPath.Equals($"/connect/releases/{expectedAssetName}", StringComparison.Ordinal);
            }
            return false;
        }

        if (!uri.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase))
            return false;

        if (expectedTag is null || pathType == "download" && expectedAssetName is null)
            return false;
        var expectedPath = $"/crxwov/deytt.connect/releases/{pathType}/{expectedTag}";
        if (pathType == "download")
            expectedPath += "/" + expectedAssetName;
        return decodedPath.Equals(expectedPath, StringComparison.Ordinal);
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
            request.Headers.UserAgent.ParseAdd(OperatingSystem.IsLinux() ? "deytt-connect/linux" : "deytt-connect/windows");
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
         uri.Host.EndsWith(".githubusercontent.com", StringComparison.OrdinalIgnoreCase) ||
         uri.Host.Equals("deytt.space", StringComparison.OrdinalIgnoreCase) ||
         uri.Host.Equals("ru.deytt.space", StringComparison.OrdinalIgnoreCase));

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

    private static string? ReadFirstString(JsonElement element, IReadOnlyList<string> keys)
    {
        foreach (var key in keys)
            if (GetJsonString(element, key) is { Length: > 0 } value)
                return value.Trim();
        return null;
    }

    private static long? ReadFirstInt64(JsonElement element, IReadOnlyList<string> keys)
    {
        foreach (var key in keys)
            if (GetJsonInt64(element, key) is { } value)
                return value;
        return null;
    }

    private static string? NormalizeDigest(string? rawDigest)
    {
        if (string.IsNullOrWhiteSpace(rawDigest))
            return null;
        var trimmed = rawDigest.Trim();
        if (trimmed.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase))
            return trimmed;
        if (trimmed.Length == 64 && trimmed.All(Uri.IsHexDigit))
            return "sha256:" + trimmed.ToLowerInvariant();
        return trimmed;
    }

    internal static string FormatDisplayNotes(string? rawNotes)
    {
        if (string.IsNullOrWhiteSpace(rawNotes))
            return "";
        var lines = new List<string>();
        foreach (var rawLine in rawNotes.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n'))
        {
            var trimmed = rawLine.Trim();
            if (trimmed.StartsWith("### ", StringComparison.Ordinal) ||
                trimmed.StartsWith("## ", StringComparison.Ordinal) ||
                trimmed.StartsWith("# ", StringComparison.Ordinal))
                trimmed = trimmed.TrimStart('#', ' ').Trim();
            // Stop before the GitHub download-asset explanation footer if present.
            if (trimmed.StartsWith("файлы", StringComparison.OrdinalIgnoreCase) ||
                trimmed.StartsWith("ассеты", StringComparison.OrdinalIgnoreCase))
                break;
            if (trimmed.StartsWith("- ", StringComparison.Ordinal) ||
                trimmed.StartsWith("* ", StringComparison.Ordinal))
                trimmed = "• " + trimmed[2..].Trim();
            trimmed = trimmed.Replace("`", "", StringComparison.Ordinal);
            if (trimmed.Length == 0)
            {
                if (lines.Count > 0 && lines[^1].Length > 0)
                    lines.Add("");
                continue;
            }
            lines.Add(trimmed);
        }
        while (lines.Count > 0 && lines[^1].Length == 0)
            lines.RemoveAt(lines.Count - 1);
        return string.Join('\n', lines);
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
