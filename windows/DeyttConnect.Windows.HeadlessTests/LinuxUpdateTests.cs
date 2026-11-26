using System.Text.Json;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class LinuxUpdateTests
{
    [Theory]
    [InlineData(WindowsUpdatePackageKind.Deb, "deytt-connect_0.11.144_amd64.deb")]
    [InlineData(WindowsUpdatePackageKind.Rpm, "deytt-connect-0.11.144-1.x86_64.rpm")]
    [InlineData(WindowsUpdatePackageKind.Arch, "deytt-connect-0.11.144-1-x86_64.pkg.tar.zst")]
    public void Selects_verified_asset_for_package_format(WindowsUpdatePackageKind kind, string asset)
    {
        const string tag = "connect./0.11.128";
        const string releaseRoot = "https://github.com/crxwov/deytt.connect/releases";
        var json = JsonSerializer.Serialize(new[]
        {
            new
            {
                draft = false,
                prerelease = false,
                tag_name = tag,
                html_url = $"{releaseRoot}/tag/{tag}",
                assets = new[]
                {
                    new
                    {
                        name = asset,
                        browser_download_url = $"{releaseRoot}/download/{tag}/{asset}",
                        digest = "sha256:" + new string('a', 64),
                        size = 1000L,
                    },
                },
            },
        });

        var release = WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json, kind);
        Assert.NotNull(release);
        Assert.Equal(asset, release.AssetName);
        Assert.True(release.CanDownloadVerifiedPackage);
        Assert.Null(WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json,
            WindowsUpdatePackageKind.Msi));
    }
}
