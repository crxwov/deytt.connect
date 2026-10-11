using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsUpdateClientTests
{
    [Fact]
    public void ParseLatestWindowsReleaseResponse_SkipsPrereleaseAndSelectsNextStable()
    {
        const string json = """
            [
              {
                "draft": false,
                "prerelease": true,
                "tag_name": "v2.0.0-beta.1",
                "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v2.0.0-beta.1",
                "assets": [{
                  "name": "deytt-connect-windows-x64.zip",
                  "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v2.0.0-beta.1/deytt-connect-windows-x64.zip",
                  "size": 1234
                }]
              },
              {
                "draft": false,
                "prerelease": false,
                "tag_name": "v1.9.0",
                "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v1.9.0",
                "assets": [{
                  "name": "deytt-connect-windows-x64.zip",
                  "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x64.zip",
                  "size": 2345
                }]
              }
            ]
            """;

        var release = WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json);

        Assert.NotNull(release);
        Assert.Equal("v1.9.0", release.Tag);
        Assert.Equal("deytt-connect-windows-x64.zip", release.AssetName);
        Assert.Equal(2345, release.AssetSize);
    }

    [Fact]
    public void ParseLatestWindowsReleaseResponse_ReturnsNullWhenAllReleasesArePrereleases()
    {
        const string json = """
            [{
              "draft": false,
              "prerelease": true,
              "tag_name": "v2.0.0-beta.1",
              "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v2.0.0-beta.1",
              "assets": [{
                "name": "deytt-connect-windows-x64.zip",
                "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v2.0.0-beta.1/deytt-connect-windows-x64.zip",
                "size": 1234
              }]
            }]
            """;

        Assert.Null(WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json));
    }

    [Fact]
    public void ParseLatestWindowsReleaseResponse_UsesVersionedPortableAssetForMatchingTag()
    {
        const string json = """
            [{
              "draft": false,
              "prerelease": false,
              "tag_name": "v0.8.31",
              "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v0.8.31",
              "assets": [
                {
                  "name": "deytt-connect-0.8.30-portable.zip",
                  "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v0.8.31/deytt-connect-0.8.30-portable.zip",
                  "size": 1000
                },
                {
                  "name": "deytt-connect-0.8.31-portable.zip",
                  "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v0.8.31/deytt-connect-0.8.31-portable.zip",
                  "size": 1400,
                  "digest": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                }
              ]
            }]
            """;

        var release = WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json);

        Assert.NotNull(release);
        Assert.Equal("v0.8.31", release.Tag);
        Assert.Equal("deytt-connect-0.8.31-portable.zip", release.AssetName);
        Assert.Equal(1400, release.AssetSize);
        Assert.Equal(
            "https://github.com/crxwov/deytt.connect/releases/download/v0.8.31/deytt-connect-0.8.31-portable.zip",
            release.AssetUrl);
    }

    [Fact]
    public void ParseLatestWindowsReleaseResponse_FailsClosedForMissingOrMalformedPrereleaseFlag()
    {
        const string releaseWithoutFlag = """
            {
              "draft": false,
              "tag_name": "v1.9.0",
              "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v1.9.0",
              "assets": [{
                "name": "deytt-connect-windows-x64.zip",
                "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x64.zip",
                "size": 2345
              }]
            }
            """;
        const string releaseWithMalformedFlag = """
            {
              "draft": false,
              "prerelease": "false",
              "tag_name": "v1.9.0",
              "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v1.9.0",
              "assets": [{
                "name": "deytt-connect-windows-x64.zip",
                "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x64.zip",
                "size": 2345
              }]
            }
            """;

        Assert.Null(WindowsUpdateClient.ParseLatestWindowsReleaseResponse($"[{releaseWithoutFlag}]"));
        Assert.Null(WindowsUpdateClient.ParseLatestWindowsReleaseResponse($"[{releaseWithMalformedFlag}]"));
    }

    [Fact]
    public void ParseLatestWindowsReleaseResponse_FailsClosedForMissingOrMalformedDraftFlag()
    {
        const string releaseWithoutFlag = """
            {
              "prerelease": false,
              "tag_name": "v1.9.0",
              "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v1.9.0",
              "assets": [{
                "name": "deytt-connect-windows-x64.zip",
                "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x64.zip",
                "size": 2345
              }]
            }
            """;
        const string releaseWithMalformedFlag = """
            {
              "draft": "false",
              "prerelease": false,
              "tag_name": "v1.9.0",
              "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v1.9.0",
              "assets": [{
                "name": "deytt-connect-windows-x64.zip",
                "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x64.zip",
                "size": 2345
              }]
            }
            """;

        Assert.Null(WindowsUpdateClient.ParseLatestWindowsReleaseResponse($"[{releaseWithoutFlag}]"));
        Assert.Null(WindowsUpdateClient.ParseLatestWindowsReleaseResponse($"[{releaseWithMalformedFlag}]"));
    }

    [Fact]
    public void ParseLatestWindowsReleaseResponse_SkipsDraftAndRequiresCanonicalWindowsZipName()
    {
        const string json = """
            [
              {
                "draft": true,
                "prerelease": false,
                "tag_name": "v2.0.0",
                "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v2.0.0",
                "assets": [{
                  "name": "deytt-connect-windows-x64.zip",
                  "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v2.0.0/deytt-connect-windows-x64.zip",
                  "size": 1000
                }]
              },
              {
                "draft": false,
                "prerelease": false,
                "tag_name": "v1.9.0",
                "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v1.9.0",
                "assets": [
                  {
                    "name": "deytt-connect-windows-x86.zip",
                    "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x86.zip",
                    "size": 1100
                  },
                  {
                    "name": "deytt-connect-windows-x64.exe",
                    "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x64.exe",
                    "size": 1200
                  },
                  {
                    "name": "deytt-connect-windows-x64-preview.zip",
                    "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x64-preview.zip",
                    "size": 1300
                  },
                  {
                    "name": "deytt-connect-windows-x64.zip",
                    "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x64.zip",
                    "size": 1400
                  }
                ]
              }
            ]
            """;

        var release = WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json);

        Assert.NotNull(release);
        Assert.Equal("v1.9.0", release.Tag);
        Assert.Equal("deytt-connect-windows-x64.zip", release.AssetName);
        Assert.Equal(1400, release.AssetSize);
        Assert.Equal(
            "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x64.zip",
            release.AssetUrl);
    }

    [Fact]
    public void ParseLatestWindowsReleaseResponse_RejectsNonCanonicalWindowsZipNames()
    {
        const string json = """
            [{
              "draft": false,
              "prerelease": false,
              "tag_name": "v1.9.0",
              "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v1.9.0",
              "assets": [{
                "name": "deytt-connect-windows-x64-preview.zip",
                "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v1.9.0/deytt-connect-windows-x64-preview.zip",
                "size": 1234,
                "digest": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
              }]
            }]
            """;

        Assert.Null(WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json));
    }

    [Fact]
    public void ParseLatestWindowsReleaseResponse_SelectsTheRequestedInstallerKind()
    {
        const string json = """
            [{
              "draft": false,
              "prerelease": false,
              "tag_name": "v0.8.51",
              "html_url": "https://github.com/crxwov/deytt.connect/releases/tag/v0.8.51",
              "assets": [
                {
                  "name": "deytt-connect-0.8.51-portable.zip",
                  "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v0.8.51/deytt-connect-0.8.51-portable.zip",
                  "size": 1000,
                  "digest": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                },
                {
                  "name": "deytt-connect-0.8.51.msi",
                  "browser_download_url": "https://github.com/crxwov/deytt.connect/releases/download/v0.8.51/deytt-connect-0.8.51.msi",
                  "size": 2000,
                  "digest": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                }
              ]
            }]
            """;

        var release = WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json, WindowsUpdatePackageKind.Msi);

        Assert.NotNull(release);
        Assert.Equal(WindowsUpdatePackageKind.Msi, release.PackageKind);
        Assert.Equal("deytt-connect-0.8.51.msi", release.AssetName);
        Assert.True(release.CanDownloadVerifiedPackage);

        var portableRelease = WindowsUpdateClient.ParseLatestWindowsReleaseResponse(
            json, WindowsUpdatePackageKind.PortableZip);
        Assert.NotNull(portableRelease);
        Assert.Equal(WindowsUpdatePackageKind.PortableZip, portableRelease.PackageKind);
        Assert.Equal("deytt-connect-0.8.51-portable.zip", portableRelease.AssetName);
    }

    [Fact]
    public void ParseLatestWindowsReleaseResponse_SelectsHighestVersionRegardlessOfPublicationOrder()
    {
        const string json = """
            [
              {"draft":false,"prerelease":false,"tag_name":"v0.8.51","html_url":"https://github.com/crxwov/deytt.connect/releases/tag/v0.8.51","assets":[
                {"name":"deytt-connect-0.8.51-portable.zip","browser_download_url":"https://github.com/crxwov/deytt.connect/releases/download/v0.8.51/deytt-connect-0.8.51-portable.zip","size":1000,"digest":"sha256:0000000000000000000000000000000000000000000000000000000000000000"},
                {"name":"deytt-connect-0.8.51.msi","browser_download_url":"https://github.com/crxwov/deytt.connect/releases/download/v0.8.51/deytt-connect-0.8.51.msi","size":2000,"digest":"sha256:0000000000000000000000000000000000000000000000000000000000000000"}]},
              {"draft":false,"prerelease":false,"tag_name":"v0.8.52","html_url":"https://github.com/crxwov/deytt.connect/releases/tag/v0.8.52","assets":[
                {"name":"deytt-connect-0.8.52.msi","browser_download_url":"https://github.com/crxwov/deytt.connect/releases/download/v0.8.52/deytt-connect-0.8.52.msi","size":2000,"digest":"sha256:0000000000000000000000000000000000000000000000000000000000000000"}]},
              {"draft":false,"prerelease":false,"tag_name":"v0.8.50","html_url":"https://github.com/crxwov/deytt.connect/releases/tag/v0.8.50","assets":[
                {"name":"deytt-connect-0.8.50-portable.zip","browser_download_url":"https://github.com/crxwov/deytt.connect/releases/download/v0.8.50/deytt-connect-0.8.50-portable.zip","size":1000,"digest":"sha256:0000000000000000000000000000000000000000000000000000000000000000"},
                {"name":"deytt-connect-0.8.50.msi","browser_download_url":"https://github.com/crxwov/deytt.connect/releases/download/v0.8.50/deytt-connect-0.8.50.msi","size":2000,"digest":"sha256:0000000000000000000000000000000000000000000000000000000000000000"}]}
            ]
            """;

        var msi = WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json, WindowsUpdatePackageKind.Msi);
        var portable = WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json, WindowsUpdatePackageKind.PortableZip);
        var any = WindowsUpdateClient.ParseLatestWindowsReleaseResponse(json);

        Assert.Equal("v0.8.52", msi?.Tag);
        Assert.Equal("deytt-connect-0.8.52.msi", msi?.AssetName);
        Assert.Equal("v0.8.51", portable?.Tag);
        Assert.Equal("deytt-connect-0.8.51-portable.zip", portable?.AssetName);
        Assert.Equal("v0.8.52", any?.Tag);
        Assert.True(msi!.CanDownloadVerifiedPackage);
        Assert.True(portable!.CanDownloadVerifiedPackage);
    }

    [Theory]
    [InlineData("v0.8.50", "0.8.50", false)]
    [InlineData("V0.08.050", "0.8.50.0", false)]
    [InlineData("v0.8.51", "0.8.50", true)]
    [InlineData("v0.8.49", "0.8.50", false)]
    [InlineData("v1.0.0", "0.99.99", true)]
    [InlineData("v0.9.0", "0.8.99", true)]
    [InlineData("v0.7.99", "0.8.0", false)]
    [InlineData("v0.8.50.1", "0.8.50.0", true)]
    public void IsNewerThan_NeverOffersSameOrOlderVersion(
        string releaseTag,
        string installed,
        bool expected)
    {
        Assert.True(Version.TryParse(installed, out var installedVersion));

        Assert.Equal(expected, WindowsUpdateClient.IsNewerThan(releaseTag, installedVersion));
    }

    [Fact]
    public void GetCurrentInstallationKind_UsesPortableBundleMarkers()
    {
        var root = Path.Combine(Path.GetTempPath(), "deytt-update-test-" + Guid.NewGuid().ToString("N"));
        var app = Path.Combine(root, "app");
        try
        {
            Directory.CreateDirectory(app);
            Directory.CreateDirectory(Path.Combine(root, "service"));
            Directory.CreateDirectory(Path.Combine(root, "vpn"));
            File.WriteAllBytes(Path.Combine(root, "deyttconnect.exe"), []);
            File.WriteAllBytes(Path.Combine(root, "service", "DeyttConnect.Windows.Service.exe"), []);
            File.WriteAllBytes(Path.Combine(root, "vpn", "DeyttVpnEngine.exe"), []);

            Assert.Equal(WindowsUpdatePackageKind.PortableZip,
                WindowsUpdateClient.GetCurrentInstallationKind(app));
            Assert.Equal(WindowsUpdatePackageKind.Msi,
                WindowsUpdateClient.GetCurrentInstallationKind(
                    Path.Combine(root, "Program Files", "DEYTT", "Connect")));
        }
        finally
        {
            if (Directory.Exists(root))
                Directory.Delete(root, recursive: true);
        }
    }
}

