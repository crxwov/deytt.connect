using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace DeyttConnect.Windows.Services;

/// <summary>Stores an imported subscription profile encrypted for the current Windows user.</summary>
public static class WindowsImportedSubscriptionStore
{
    private const uint UiForbidden = 0x1;
    private const int MaxProfileBytes = 2 * 1024 * 1024;

    public static void Save(string profileJson)
    {
        EnsureWindows();
        var plain = Encoding.UTF8.GetBytes(profileJson);
        if (plain.Length is < 1 or > MaxProfileBytes)
            throw new InvalidDataException("The imported subscription is outside the supported size limit.");

        byte[]? protectedProfile = null;
        try
        {
            protectedProfile = Transform(plain, protect: true);
            var path = ProfilePath;
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tempPath = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                using (var stream = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write,
                           FileShare.None, 4096, FileOptions.WriteThrough))
                {
                    stream.Write(protectedProfile);
                    stream.Flush(flushToDisk: true);
                }
                File.Move(tempPath, path, overwrite: true);
            }
            finally
            {
                if (File.Exists(tempPath))
                    File.Delete(tempPath);
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plain);
            if (protectedProfile is not null)
                CryptographicOperations.ZeroMemory(protectedProfile);
        }
    }

    public static string? Load()
    {
        EnsureWindows();
        var path = ProfilePath;
        if (!File.Exists(path))
            return null;

        byte[]? protectedProfile = null;
        byte[]? plain = null;
        try
        {
            var info = new FileInfo(path);
            if (info.Length is < 1 or > MaxProfileBytes + 64 * 1024)
                throw new CryptographicException("The imported subscription file is invalid.");
            protectedProfile = File.ReadAllBytes(path);
            plain = Transform(protectedProfile, protect: false);
            if (plain.Length is < 1 or > MaxProfileBytes)
                throw new CryptographicException("The imported subscription is outside the supported size limit.");
            return Encoding.UTF8.GetString(plain);
        }
        catch (CryptographicException)
        {
            File.Delete(path);
            return null;
        }
        finally
        {
            if (protectedProfile is not null)
                CryptographicOperations.ZeroMemory(protectedProfile);
            if (plain is not null)
                CryptographicOperations.ZeroMemory(plain);
        }
    }

    public static void Clear()
    {
        EnsureWindows();
        var path = ProfilePath;
        if (File.Exists(path))
            File.Delete(path);
    }

    private static string ProfilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "DEYTT", "Connect", "subscription-profile.dat");

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Imported subscription protection is available only on Windows.");
    }

    private static byte[] Transform(byte[] input, bool protect)
    {
        var inputPointer = Marshal.AllocHGlobal(input.Length);
        Marshal.Copy(input, 0, inputPointer, input.Length);
        var source = new DataBlob { Length = input.Length, Data = inputPointer };
        var output = default(DataBlob);
        IntPtr description = IntPtr.Zero;
        try
        {
            var succeeded = protect
                ? CryptProtectData(ref source, "deytt.connect imported subscription", IntPtr.Zero,
                    IntPtr.Zero, IntPtr.Zero, UiForbidden, out output)
                : CryptUnprotectData(ref source, out description, IntPtr.Zero,
                    IntPtr.Zero, IntPtr.Zero, UiForbidden, out output);
            if (!succeeded)
                throw new CryptographicException(new Win32Exception(Marshal.GetLastWin32Error()).Message);

            var result = new byte[output.Length];
            Marshal.Copy(output.Data, result, 0, output.Length);
            return result;
        }
        finally
        {
            Marshal.Copy(new byte[input.Length], 0, inputPointer, input.Length);
            Marshal.FreeHGlobal(inputPointer);
            if (output.Data != IntPtr.Zero)
            {
                Marshal.Copy(new byte[output.Length], 0, output.Data, output.Length);
                LocalFree(output.Data);
            }
            if (description != IntPtr.Zero)
                LocalFree(description);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob
    {
        public int Length;
        public IntPtr Data;
    }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptProtectData(
        ref DataBlob dataIn, string description, IntPtr optionalEntropy, IntPtr reserved,
        IntPtr prompt, uint flags, out DataBlob dataOut);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptUnprotectData(
        ref DataBlob dataIn, out IntPtr description, IntPtr optionalEntropy, IntPtr reserved,
        IntPtr prompt, uint flags, out DataBlob dataOut);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LocalFree(IntPtr memory);
}
