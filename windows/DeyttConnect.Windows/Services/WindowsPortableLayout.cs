namespace DeyttConnect.Windows.Services;

internal static class WindowsPortableLayout
{
    internal static string ResolveBundleRoot(string appBaseDirectory) =>
        FindPortableRoot(appBaseDirectory) ?? Path.GetFullPath(appBaseDirectory);

    internal static string? FindPortableRoot(string appBaseDirectory)
    {
        var appDirectory = Path.GetFullPath(appBaseDirectory.TrimEnd(
            Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
        if (!string.Equals(Path.GetFileName(appDirectory), "app", StringComparison.OrdinalIgnoreCase))
            return null;

        var root = Directory.GetParent(appDirectory)?.FullName;
        if (root is null ||
            !File.Exists(Path.Combine(root, "deyttconnect.exe")) ||
            !File.Exists(Path.Combine(root, "service", "DeyttConnect.Windows.Service.exe")) ||
            !File.Exists(Path.Combine(root, "vpn", "DeyttVpnEngine.exe")))
            return null;

        return root;
    }
}
