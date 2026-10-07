using System.Text;

namespace DeyttConnect.Windows.Services;

internal static class WindowsSubscriptionDeviceIdentity
{
    private const string ProductFolder = "DEYTT\\Connect";
    private const string InstallationIdFileName = "installation-id";
    private const int LockAttempts = 50;
    private static readonly TimeSpan LockRetryDelay = TimeSpan.FromMilliseconds(20);

    public static void AddProfileHeaders(HttpRequestMessage request, string sessionToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (sessionToken.Length is < 32 or > 256)
            throw new TelegramApiException("session_invalid", System.Net.HttpStatusCode.Unauthorized);

        AddProfileHeaders(request, GetOrCreateInstallationId(), sessionToken,
            Environment.OSVersion.Version.ToString());
    }

    internal static void AddProfileHeaders(
        HttpRequestMessage request,
        string installationId,
        string sessionToken,
        string osVersion)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (!Guid.TryParseExact(installationId, "D", out var parsedId) || parsedId == Guid.Empty)
            throw new ArgumentException("The installation ID must be a non-empty UUID.", nameof(installationId));
        if (sessionToken.Length is < 32 or > 256)
            throw new TelegramApiException("session_invalid", System.Net.HttpStatusCode.Unauthorized);

        request.Headers.TryAddWithoutValidation("X-HWID", parsedId.ToString("D"));
        request.Headers.TryAddWithoutValidation("X-Deytt-Client", "deytt-connect");
        request.Headers.TryAddWithoutValidation("X-Device-Os", "Windows");
        request.Headers.TryAddWithoutValidation("X-Ver-Os", SafeHeaderValue(osVersion));
        request.Headers.TryAddWithoutValidation("X-TG-App-Token", sessionToken);
        // Do not send a generic Windows model: the server uses this field for
        // same-device recovery, and a shared value could match unrelated PCs.
    }

    internal static string GetOrCreateInstallationId(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
            throw new ArgumentException("An installation ID path is required.", nameof(path));
        var directory = Path.GetDirectoryName(path);
        if (string.IsNullOrWhiteSpace(directory))
            throw new ArgumentException("The installation ID path must include a directory.", nameof(path));
        Directory.CreateDirectory(directory);

        IOException? lastLockError = null;
        for (var attempt = 0; attempt < LockAttempts; attempt++)
        {
            try
            {
                using var stream = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite,
                    FileShare.None, 64, FileOptions.WriteThrough);
                if (stream.Length is > 0 and <= 64)
                {
                    var bytes = new byte[(int)stream.Length];
                    stream.Position = 0;
                    stream.ReadExactly(bytes);
                    var stored = Encoding.ASCII.GetString(bytes).Trim();
                    if (Guid.TryParseExact(stored, "D", out var existing) && existing != Guid.Empty)
                        return existing.ToString("D");
                }

                var installationId = Guid.NewGuid().ToString("D");
                var value = Encoding.ASCII.GetBytes(installationId);
                stream.Position = 0;
                stream.SetLength(0);
                stream.Write(value);
                stream.Flush(flushToDisk: true);
                return installationId;
            }
            catch (IOException error) when (attempt < LockAttempts - 1)
            {
                lastLockError = error;
                Thread.Sleep(LockRetryDelay);
            }
        }

        throw new IOException("Could not access the saved installation ID.", lastLockError);
    }

    private static string GetOrCreateInstallationId()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Subscription device identity is available only on Windows.");
        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(localAppData))
            throw new IOException("The Windows application data directory is unavailable.");
        var path = Path.Combine(localAppData, ProductFolder, InstallationIdFileName);
        return GetOrCreateInstallationId(path);
    }

    private static string SafeHeaderValue(string value) =>
        new(value.Where(character => character is >= ' ' and <= '~').Take(64).ToArray());
}
