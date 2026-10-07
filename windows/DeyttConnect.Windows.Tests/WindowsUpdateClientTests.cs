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
}
