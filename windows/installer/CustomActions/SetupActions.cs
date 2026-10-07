using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Security.Principal;
using Microsoft.Win32;
using WixToolset.Dtf.WindowsInstaller;

namespace DeyttConnect.Setup
{
    public static class SetupActions
    {
        // Parse the executable, never an occurrence of its name in an argument.
        public static bool IsConnectService(string command)
        {
            if (string.IsNullOrWhiteSpace(command)) return false;
            command = Environment.ExpandEnvironmentVariables(command.Trim());
            string executable, arguments;
            var quotedExecutable = command[0] == '"';
            if (quotedExecutable)
            {
                int end = command.IndexOf('"', 1);
                if (end < 0) return false;
                executable = command.Substring(1, end - 1);
                arguments = command.Substring(end + 1).Trim();
            }
            else
            {
                const string name = "DeyttConnect.Windows.Service.exe";
                int end = command.IndexOf(name, StringComparison.OrdinalIgnoreCase);
                if (end < 0) return false;
                end += name.Length;
                executable = command.Substring(0, end);
                arguments = command.Substring(end).Trim();
                if (executable.IndexOfAny(new[] { ' ', '\t', '"' }) >= 0) return false;
            }
            try
            {
                return Path.IsPathRooted(executable) && !executable.StartsWith(@"\\", StringComparison.Ordinal) &&
                    string.Equals(Path.GetFileName(executable), "DeyttConnect.Windows.Service.exe", StringComparison.OrdinalIgnoreCase) &&
                    (arguments.Length == 0 || string.Equals(arguments, "--service", StringComparison.OrdinalIgnoreCase));
            }
            catch (ArgumentException) { return false; }
        }

        [CustomAction]
        public static ActionResult DetectLegacyService(Session session)
        {
            session["CONNECT_SERVICE_COMPATIBLE"] = IsConnectService(session["EXISTING_SERVICE_PATH"]) ? "1" : "";
            var identity = WindowsIdentity.GetCurrent();
            // Never launch the interactive client with the installer's elevated token.
            session["CAN_LAUNCH_CONNECT"] = !new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator) ? "1" : "";
            session["WEBVIEW2_PRESENT"] = HasWebView2Runtime() ? "1" : "";
            return ActionResult.Success;
        }

        public static bool HasWebView2Runtime()
        {
            const string key = @"SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
            foreach (var hive in new[] { RegistryHive.LocalMachine, RegistryHive.CurrentUser })
                foreach (var view in new[] { RegistryView.Registry32, RegistryView.Registry64 })
                    using (var root = RegistryKey.OpenBaseKey(hive, view))
                    using (var runtime = root.OpenSubKey(key))
                    {
                        Version version;
                        if (Version.TryParse(runtime?.GetValue("pv") as string, out version) && version.Major > 0)
                            return true;
                    }
            return false;
        }

        [CustomAction]
        public static ActionResult PrepareWebView2(Session session)
        {
            var data = new CustomActionData();
            data["SetupPath"] = session.Format("[#WebViewBootstrapper]");
            session["InstallWebView2"] = data.ToString();
            return ActionResult.Success;
        }

        [CustomAction]
        public static ActionResult InstallWebView2(Session session)
        {
            try
            {
                var path = session.CustomActionData["SetupPath"];
                using (var process = Process.Start(new ProcessStartInfo(path, "/silent /install")
                { UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden }))
                {
                    if (!process.WaitForExit(600000))
                        throw new IOException("установка webview2 заняла слишком много времени. проверьте подключение к интернету и повторите установку.");
                    if (process.ExitCode == 3010 || process.ExitCode == 1641)
                        session.SetMode(InstallRunMode.RebootAtEnd, true);
                    else if (process.ExitCode != 0)
                        throw new IOException("не удалось установить компонент карты microsoft webview2 (код " + process.ExitCode + "). проверьте подключение к интернету и повторите установку.");
                }
                return ActionResult.Success;
            }
            catch (Exception exception)
            {
                session.Log("WebView2 prerequisite: {0}", exception.Message);
                using (var record = new Record(1))
                {
                    record[0] = exception.Message.ToLowerInvariant();
                    session.Message(InstallMessage.Error, record);
                }
                return ActionResult.Failure;
            }
        }

        [CustomAction]
        public static ActionResult ValidateInstallDirectory(Session session)
        {
            if (session["REMOVE"] == "ALL") return ActionResult.Success;
            try
            {
                var directory = new DirectoryInfo(session["INSTALLFOLDER"]);
                while (directory != null)
                {
                    if (directory.Exists && (directory.Attributes & System.IO.FileAttributes.ReparsePoint) != 0)
                        throw new IOException("папка установки содержит ссылку на другую папку. выберите обычную локальную папку.");
                    directory = directory.Parent;
                }
                return ActionResult.Success;
            }
            catch (Exception exception)
            {
                session.Log("Install directory validation: {0}", exception.Message);
                using (var record = new Record(1))
                {
                    record[0] = exception.Message.ToLowerInvariant();
                    session.Message(InstallMessage.Error, record);
                }
                return ActionResult.Failure;
            }
        }

        public static void CreateDesktopShortcut(string desktop, string executable)
        {
            if (!File.Exists(executable)) throw new FileNotFoundException("Не найден файл приложения.", executable);
            Directory.CreateDirectory(desktop);
            var shortcutPath = Path.Combine(desktop, "deytt connect.lnk");
            var link = (IShellLinkW)new ShellLink();
            try
            {
                var persist = (IPersistFile)link;
                if (File.Exists(shortcutPath))
                {
                    persist.Load(shortcutPath, 0);
                    var target = new System.Text.StringBuilder(32768);
                    link.GetPath(target, target.Capacity, IntPtr.Zero, 0);
                    if (!string.Equals(Path.GetFullPath(target.ToString()), Path.GetFullPath(executable), StringComparison.OrdinalIgnoreCase))
                        throw new IOException("на рабочем столе уже есть другой ярлык с именем deytt connect. переименуйте его и повторите попытку.");
                }
                link.SetPath(executable);
                link.SetWorkingDirectory(Path.GetDirectoryName(executable));
                link.SetDescription("deytt connect — подключение к vpn");
                link.SetIconLocation(executable, 0);
                link.SetShowCmd(1);
                persist.Save(shortcutPath, true);
            }
            finally { Marshal.FinalReleaseComObject(link); }
        }

        [CustomAction]
        public static ActionResult FinishSetup(Session session)
        {
            session["FINISH_ERROR"] = "";
            session["FINISH_ACTIONS_OK"] = "";
            try
            {
                var executable = Path.Combine(session["INSTALLFOLDER"], "DeyttConnect.Windows.exe");
                if (session["CREATE_DESKTOP_SHORTCUT"] == "1")
                    CreateDesktopShortcut(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), executable);
                if (session["LAUNCH_CONNECT"] == "1" && session["CAN_LAUNCH_CONNECT"] == "1")
                    Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(executable) });
                session["FINISH_ACTIONS_OK"] = "1";
            }
            catch (Exception exception)
            {
                session.Log("Finish setup failed: {0}", exception.Message);
                session["FINISH_ERROR"] = "приложение установлено. не удалось выполнить выбранное действие: " + exception.Message.ToLowerInvariant() + " можно снять галочку и завершить установку.";
            }
            return ActionResult.Success;
        }

        [CustomAction]
        public static ActionResult PrepareShortcutRemoval(Session session)
        {
            var data = new CustomActionData();
            data["Desktop"] = session["DesktopFolder"];
            data["Executable"] = Path.Combine(session["INSTALLFOLDER"], "DeyttConnect.Windows.exe");
            session["RemoveDesktopShortcut"] = data.ToString();
            return ActionResult.Success;
        }

        [CustomAction]
        public static ActionResult RemoveDesktopShortcut(Session session)
        {
            // Do not remove an unrelated/replaced shortcut or user data. Major upgrades skip this action.
            try
            {
                var path = Path.Combine(session.CustomActionData["Desktop"], "deytt connect.lnk");
                if (!File.Exists(path)) return ActionResult.Success;
                var link = (IShellLinkW)new ShellLink();
                try
                {
                    ((IPersistFile)link).Load(path, 0);
                    var target = new System.Text.StringBuilder(32768);
                    link.GetPath(target, target.Capacity, IntPtr.Zero, 0);
                    if (string.Equals(target.ToString(), session.CustomActionData["Executable"], StringComparison.OrdinalIgnoreCase))
                        File.Delete(path);
                }
                finally { Marshal.FinalReleaseComObject(link); }
            }
            catch (Exception exception) { session.Log("Optional desktop shortcut cleanup: {0}", exception.Message); }
            return ActionResult.Success;
        }
    }

    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    internal class ShellLink { }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
    internal interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder file, int max, IntPtr data, uint flags);
        void GetIDList(out IntPtr list); void SetIDList(IntPtr list);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder text, int max);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string text);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder path, int max);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string path);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder text, int max);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string text);
        void GetHotkey(out short key); void SetHotkey(short key);
        void GetShowCmd(out int command); void SetShowCmd(int command);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder icon, int max, out int index);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string icon, int index);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string path, uint reserved);
        void Resolve(IntPtr window, uint flags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string path);
    }
}
