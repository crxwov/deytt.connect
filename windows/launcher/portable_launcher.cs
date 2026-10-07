using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;

internal static class PortableLauncher
{
    [STAThread]
    private static void Main(string[] args)
    {
        var executable = Process.GetCurrentProcess().MainModule.FileName;
        var root = Path.GetDirectoryName(executable);
        var appDirectory = Path.Combine(root, "app");
        var app = Path.Combine(appDirectory, "DeyttConnect.Windows.exe");
        var service = Path.Combine(root, "service", "DeyttConnect.Windows.Service.exe");
        var engine = Path.Combine(root, "vpn", "DeyttVpnEngine.exe");
        if (!File.Exists(app) || !File.Exists(service) || !File.Exists(engine))
        {
            ShowError("пакет deytt connect неполный. распакуйте весь архив и запустите deyttconnect.exe снова.");
            return;
        }

        try
        {
            var start = new ProcessStartInfo(app)
            {
                WorkingDirectory = appDirectory,
                UseShellExecute = false,
                Arguments = String.Join(" ", args.Select(QuoteArgument).ToArray()),
            };
            Process.Start(start);
        }
        catch (Exception)
        {
            ShowError("не удалось открыть deytt connect. проверьте, что пакет распакован полностью.");
        }
    }

    private static string QuoteArgument(string value)
    {
        if (value.Length > 0 && value.IndexOfAny(new[] { ' ', '\t', '\n', '\v', '"' }) < 0)
            return value;

        var result = new StringBuilder("\"");
        var backslashes = 0;
        foreach (var character in value)
        {
            if (character == '\\')
            {
                backslashes++;
                continue;
            }
            if (character == '"')
            {
                result.Append('\\', backslashes * 2 + 1);
                result.Append('"');
            }
            else
            {
                result.Append('\\', backslashes);
                result.Append(character);
            }
            backslashes = 0;
        }
        result.Append('\\', backslashes * 2);
        result.Append('"');
        return result.ToString();
    }

    private static void ShowError(string message)
    {
        MessageBoxW(IntPtr.Zero, message, "deytt connect", 0x10);
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int MessageBoxW(IntPtr window, string text, string caption, uint type);
}
