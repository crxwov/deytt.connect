using Microsoft.Win32;
using System.Runtime.InteropServices;

namespace DeyttConnect.Windows.Services;

/// <summary>Associates validated DEYTT subscription links with this user's Windows app.</summary>
public static class WindowsImportLinkProtocol
{
    private const string MimeScheme = "x-scheme-handler/deytt.connect";
    private const string DesktopId = "deytt-connect.desktop";
    private const string SchemeKeyPath = @"Software\Classes\deytt.connect";
    private const string OpenCommandKeyPath = SchemeKeyPath + @"\shell\open\command";

    public static bool IsRegistered()
    {
        if (OperatingSystem.IsLinux())
        {
            var handler = g_app_info_get_default_for_type(MimeScheme, 1);
            if (handler == IntPtr.Zero)
                return false;
            try { return Marshal.PtrToStringUTF8(g_app_info_get_id(handler)) == DesktopId; }
            finally { g_object_unref(handler); }
        }
        if (!OperatingSystem.IsWindows())
            return false;

        using var commandKey = Registry.CurrentUser.OpenSubKey(OpenCommandKeyPath, writable: false);
        var command = commandKey?.GetValue(null) as string;
        return command is not null &&
               TryGetExecutablePath(command, out var currentExecutable) &&
               string.Equals(currentExecutable, GetCurrentExecutablePath(), StringComparison.OrdinalIgnoreCase);
    }

    public static void Register()
    {
        if (OperatingSystem.IsLinux())
        {
            SetLinuxAssociation(enabled: true);
            return;
        }
        if (!OperatingSystem.IsWindows())
            return;
        var executable = GetCurrentExecutablePath();
        using var schemeKey = Registry.CurrentUser.CreateSubKey(SchemeKeyPath, writable: true)
                              ?? throw new IOException("Could not create the DEYTT link protocol key.");
        schemeKey.SetValue(null, "URL:DEYTT Connect subscription import", RegistryValueKind.String);
        schemeKey.SetValue("URL Protocol", "", RegistryValueKind.String);

        using var commandKey = Registry.CurrentUser.CreateSubKey(OpenCommandKeyPath, writable: true)
                               ?? throw new IOException("Could not create the DEYTT link handler command.");
        commandKey.SetValue(null, $"\"{executable}\" \"%1\"", RegistryValueKind.String);
    }

    public static void Unregister()
    {
        if (OperatingSystem.IsLinux())
        {
            SetLinuxAssociation(enabled: false);
            return;
        }
        if (!OperatingSystem.IsWindows())
            return;
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

    private static void SetLinuxAssociation(bool enabled)
    {
        var app = g_desktop_app_info_new(DesktopId);
        if (app == IntPtr.Zero)
            throw new IOException("The deytt./connect desktop entry is missing.");
        try
        {
            var result = enabled
                ? g_app_info_set_as_default_for_type(app, MimeScheme, IntPtr.Zero)
                : g_app_info_remove_supports_type(app, MimeScheme, IntPtr.Zero);
            if (result == 0)
                throw new IOException("Could not update the deytt./connect link association.");
        }
        finally { g_object_unref(app); }
    }

    [DllImport("libgio-2.0.so.0")]
    private static extern IntPtr g_app_info_get_default_for_type(string contentType, int mustSupportUris);

    [DllImport("libgio-2.0.so.0")]
    private static extern IntPtr g_app_info_get_id(IntPtr app);

    [DllImport("libgio-2.0.so.0")]
    private static extern IntPtr g_desktop_app_info_new(string desktopId);

    [DllImport("libgio-2.0.so.0")]
    private static extern int g_app_info_set_as_default_for_type(IntPtr app, string contentType, IntPtr error);

    [DllImport("libgio-2.0.so.0")]
    private static extern int g_app_info_remove_supports_type(IntPtr app, string contentType, IntPtr error);

    [DllImport("libgobject-2.0.so.0")]
    private static extern void g_object_unref(IntPtr app);
}
