using System;
using System.Collections.Generic;
using System.IO;

namespace DeyttConnect.Setup
{
    // Only product-owned files/caches are removed. Unknown files are user data.
    public static class ConnectDataCleanup
    {
        public static void RemoveUserData(string directory)
        {
            if (!IsPlainPath(directory) || !Directory.Exists(directory)) return;
            foreach (var name in new[] { "telegram-session.dat", "subscription-profile.dat", "preferences.json", "installation-id" })
            {
                DeletePlainFile(Path.Combine(directory, name));
                foreach (var temporary in Directory.GetFiles(directory, name + ".*.tmp"))
                {
                    var temporaryName = Path.GetFileName(temporary);
                    var suffix = temporaryName.Substring(name.Length + 1, temporaryName.Length - name.Length - 5);
                    if (Guid.TryParseExact(suffix, "N", out _)) DeletePlainFile(temporary);
                }
            }
            DeleteCache(Path.Combine(directory, "updates"));
            DeleteCache(Path.Combine(directory, "WebView2"));
            DeleteEmptyDirectory(directory);
            DeleteEmptyDirectory(Path.GetDirectoryName(directory));
        }

        public static void RemoveRuntime(string directory)
        {
            if (!IsPlainPath(directory) || !Directory.Exists(directory)) return;
            foreach (var path in Directory.GetFiles(directory, "*.json"))
                if (Guid.TryParseExact(Path.GetFileNameWithoutExtension(path), "N", out _)) DeletePlainFile(path);
            DeleteEmptyDirectory(directory);
            DeleteEmptyDirectory(Path.GetDirectoryName(directory));
        }

        public static void RemoveInstalledCache(string directory)
        {
            DeleteCache(Path.Combine(directory, "DeyttConnect.Windows.exe.WebView2"));
            DeleteEmptyDirectory(directory);
        }

        public static bool IsPlainPath(string path)
        {
            if (string.IsNullOrWhiteSpace(path) || !Path.IsPathRooted(path)) return false;
            for (var current = Path.GetFullPath(path); !string.IsNullOrEmpty(current); current = Path.GetDirectoryName(current))
                if ((Directory.Exists(current) || File.Exists(current)) &&
                    (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) return false;
            return true;
        }

        public static void DeleteCache(string directory)
        {
            if (!IsPlainPath(directory) || !Directory.Exists(directory)) return;
            // Preflight without following links; do not partially erase a linked cache.
            var pending = new Stack<string>();
            pending.Push(directory);
            while (pending.Count > 0)
            {
                foreach (var child in Directory.GetFileSystemEntries(pending.Pop()))
                {
                    var attributes = File.GetAttributes(child);
                    if ((attributes & FileAttributes.ReparsePoint) != 0) return;
                    if ((attributes & FileAttributes.Directory) != 0) pending.Push(child);
                }
            }
            DeleteCacheContents(directory);
        }

        private static void DeleteCacheContents(string directory)
        {
            if (!IsPlainPath(directory)) return;
            foreach (var path in Directory.GetFiles(directory)) DeletePlainFile(path);
            foreach (var path in Directory.GetDirectories(directory)) DeleteCacheContents(path);
            DeleteEmptyDirectory(directory);
        }

        private static void DeletePlainFile(string path)
        {
            if (File.Exists(path) && IsPlainPath(path)) File.Delete(path);
        }

        private static void DeleteEmptyDirectory(string directory)
        {
            if (IsPlainPath(directory) && Directory.Exists(directory) && Directory.GetFileSystemEntries(directory).Length == 0)
                Directory.Delete(directory);
        }
    }
}
