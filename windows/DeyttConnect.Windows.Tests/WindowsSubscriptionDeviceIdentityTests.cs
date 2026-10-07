using System.Collections.Concurrent;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsSubscriptionDeviceIdentityTests
{
    [Fact]
    public void Installation_id_is_a_persisted_uuid()
    {
        var directory = CreateTemporaryDirectory();
        try
        {
            var path = Path.Combine(directory, "installation-id");

            var first = WindowsSubscriptionDeviceIdentity.GetOrCreateInstallationId(path);
            var second = WindowsSubscriptionDeviceIdentity.GetOrCreateInstallationId(path);

            Assert.Equal(first, second);
            Assert.True(Guid.TryParseExact(first, "D", out var parsed));
            Assert.NotEqual(Guid.Empty, parsed);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void Concurrent_installation_id_reads_share_one_uuid()
    {
        var directory = CreateTemporaryDirectory();
        try
        {
            var path = Path.Combine(directory, "installation-id");
            var ids = new ConcurrentBag<string>();

            Parallel.For(0, 16, _ =>
                ids.Add(WindowsSubscriptionDeviceIdentity.GetOrCreateInstallationId(path)));

            Assert.Equal(16, ids.Count);
            Assert.Single(ids.Distinct());
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void Profile_request_headers_match_the_app_device_contract()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://deytt.space/sub/token/example?format=singbox");
        const string installationId = "8a43f1c0-bc2d-4bb8-ae7a-f4c5e941ff54";
        const string sessionToken = "0123456789abcdef0123456789abcdef0123456789abcdef";

        WindowsSubscriptionDeviceIdentity.AddProfileHeaders(
            request, installationId, sessionToken, "10.0.19045");

        Assert.Equal(installationId, request.Headers.GetValues("X-HWID").Single());
        Assert.Equal("deytt-connect", request.Headers.GetValues("X-Deytt-Client").Single());
        Assert.Equal("Windows", request.Headers.GetValues("X-Device-Os").Single());
        Assert.Equal("10.0.19045", request.Headers.GetValues("X-Ver-Os").Single());
        Assert.Equal(sessionToken, request.Headers.GetValues("X-TG-App-Token").Single());
        Assert.False(request.Headers.Contains("X-Device-Model"));
    }

    [Fact]
    public void Profile_request_rejects_non_uuid_device_ids()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://deytt.space/sub/token/example?format=singbox");
        const string sessionToken = "0123456789abcdef0123456789abcdef0123456789abcdef";

        Assert.Throws<ArgumentException>(() => WindowsSubscriptionDeviceIdentity.AddProfileHeaders(
            request, "not-a-uuid", sessionToken, "10.0.19045"));
    }

    private static string CreateTemporaryDirectory()
    {
        var path = Path.Combine(Path.GetTempPath(), "deytt-device-id-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        return path;
    }
}
