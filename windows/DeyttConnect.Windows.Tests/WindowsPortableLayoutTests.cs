using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsPortableLayoutTests
{
    [Fact]
    public void Complete_portable_tree_resolves_bundle_root()
    {
        var root = CreateTemporaryRoot();
        try
        {
            var app = Path.Combine(root, "app");
            Directory.CreateDirectory(app);
            Directory.CreateDirectory(Path.Combine(root, "service"));
            Directory.CreateDirectory(Path.Combine(root, "vpn"));
            File.WriteAllText(Path.Combine(root, "deyttconnect.exe"), "launcher");
            File.WriteAllText(Path.Combine(root, "service", "DeyttConnect.Windows.Service.exe"), "service");
            File.WriteAllText(Path.Combine(root, "vpn", "DeyttVpnEngine.exe"), "engine");

            Assert.Equal(root, WindowsPortableLayout.FindPortableRoot(app));
            Assert.Equal(root, WindowsPortableLayout.ResolveBundleRoot(app));

            File.Delete(Path.Combine(root, "vpn", "DeyttVpnEngine.exe"));
            Assert.Null(WindowsPortableLayout.FindPortableRoot(app));
            Assert.Equal(app, WindowsPortableLayout.ResolveBundleRoot(app));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    private static string CreateTemporaryRoot()
    {
        var root = Path.Combine(Path.GetTempPath(), "deytt-portable-layout-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        return root;
    }
}
