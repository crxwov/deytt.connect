namespace DeyttConnect.Windows.Services;

/// <summary>
/// Temporary incident diagnostics: a bounded local log tracing the route-probe chain
/// (webview message → dispatcher → queue → service probe → result push) so a broken
/// link can be identified from the user's machine without interactive debugging.
/// </summary>
internal static class UiDiagnostics
{
    private static readonly object Gate = new();
    private static bool _headerWritten;

    private static string Path => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "DEYTT", "Connect", "ui-diagnostics.log");

    internal static void Log(string message)
    {
        try
        {
            lock (Gate)
            {
                if (!_headerWritten)
                {
                    Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
                    File.WriteAllText(Path, $"=== deytt./connect UI diagnostics {DateTime.Now:yyyy-MM-dd HH:mm:ss} pid={Environment.ProcessId} ===\n");
                    _headerWritten = true;
                }
                File.AppendAllText(Path, $"{DateTime.Now:HH:mm:ss.fff} {message}\n");
            }
        }
        catch
        {
            // Diagnostics must never break the app.
        }
    }
}
