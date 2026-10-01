using Microsoft.Win32;
using System.Runtime.Versioning;

namespace DeyttConnect.Windows.Services;

/// <summary>Associates validated DEYTT subscription links with this user's Windows app.</summary>
public static class WindowsImportLinkProtocol
{
    private const string SchemeKeyPath = @"Software\Classes\deytt.connect";
    private const string OpenCommandKeyPath = SchemeKeyPath + @"\shell\open\command";

    public static bool IsRegistered()
    {
        if (!OperatingSystem.IsWindows())
            return false;

        using var commandKey = Registry.CurrentUser.OpenSubKey(OpenCommandKeyPath, writable: false);
        var command = commandKey?.GetValue(null) as string;
        return command is not null &&
               TryGetExecutablePath(command, out var currentExecutable) &&
               string.Equals(currentExecutable, GetCurrentExecutablePath(), StringComparison.OrdinalIgnoreCase);
    }

    [SupportedOSPlatform("windows")]
    public static void Register()
    {
        EnsureWindows();
        var executable = GetCurrentExecutablePath();
        using var schemeKey = Registry.CurrentUser.CreateSubKey(SchemeKeyPath, writable: true)
                              ?? throw new IOException("Could not create the DEYTT link protocol key.");
        schemeKey.SetValue(null, "URL:DEYTT Connect subscription import", RegistryValueKind.String);
        schemeKey.SetValue("URL Protocol", "", RegistryValueKind.String);

        using var commandKey = Registry.CurrentUser.CreateSubKey(OpenCommandKeyPath, writable: true)
                               ?? throw new IOException("Could not create the DEYTT link handler command.");
        commandKey.SetValue(null, $"\"{executable}\" \"%1\"", RegistryValueKind.String);
    }

    [SupportedOSPlatform("windows")]
    public static void Unregister()
    {
        EnsureWindows();
        Registry.CurrentUser.DeleteSubKeyTree(SchemeKeyPath, throwOnMissingSubKey: false);
    }

    private static string GetCurrentExecutablePath()
    {
        if (WindowsPortableLayout.FindPortableRoot(AppContext.BaseDirectory) is { } portableRoot)
            return Path.Combine(portableRoot, "deyttconnect.exe");

        var path = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(path) || !Path.IsPathFullyQualified(path))
            throw new IOException("Could not locate the Windows app executable.");
        return Path.GetFullPath(path);
    }

    private static bool TryGetExecutablePath(string command, out string executable)
    {
        executable = "";
        if (command.Length < 3 || command[0] != '"')
            return false;
        var endQuote = command.IndexOf('"', 1);
        if (endQuote <= 1)
            return false;
        var path = command[1..endQuote];
        if (!Path.IsPathFullyQualified(path))
            return false;
        executable = Path.GetFullPath(path);
        return true;
    }

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("DEYTT link association is available only on Windows.");
    }
}
