using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.ComponentModel;

namespace DeyttConnect.Windows.Services;

public static class WindowsSessionStore
{
    private const uint CryptprotectUiForbidden = 0x1;
    private const string ProductFolder = "DEYTT\\Connect";
    private const string SessionFileName = "telegram-session.dat";

    public static void Save(string token)
    {
        EnsureWindows();
        if (token.Length is < 32 or > 256)
            throw new CryptographicException("The Telegram session token has an invalid length.");

        var plain = Encoding.UTF8.GetBytes(token);
        byte[]? protectedToken = null;
        try
        {
            protectedToken = Protect(plain);
            var path = SessionPath;
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tempPath = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                using (var stream = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
                {
                    stream.Write(protectedToken);
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
            if (protectedToken is not null)
                CryptographicOperations.ZeroMemory(protectedToken);
        }
    }

    public static string? Load()
    {
        EnsureWindows();
        var path = SessionPath;
        if (!File.Exists(path))
            return null;

        byte[]? protectedToken = null;
        byte[]? plain = null;
        try
        {
            var info = new FileInfo(path);
            if (info.Length is < 1 or > 16 * 1024)
                throw new CryptographicException("The saved Telegram session is invalid.");
            protectedToken = File.ReadAllBytes(path);
            plain = Unprotect(protectedToken);
            var token = Encoding.UTF8.GetString(plain);
            return token.Length is >= 32 and <= 256 ? token : null;
        }
        catch (CryptographicException)
        {
            File.Delete(path);
            return null;
        }
        finally
        {
            if (protectedToken is not null)
                CryptographicOperations.ZeroMemory(protectedToken);
            if (plain is not null)
                CryptographicOperations.ZeroMemory(plain);
        }
    }

    public static void Clear()
    {
        EnsureWindows();
        var path = SessionPath;
        if (File.Exists(path))
            File.Delete(path);
    }

    private static string SessionPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        ProductFolder,
        SessionFileName);

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("Telegram session protection is available only on Windows.");
    }

    private static byte[] Protect(byte[] input) => Transform(input, protect: true);
    private static byte[] Unprotect(byte[] input) => Transform(input, protect: false);

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
                ? CryptProtectData(ref source, "deytt.connect Telegram session", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero,
                    CryptprotectUiForbidden, out output)
                : CryptUnprotectData(ref source, out description, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero,
                    CryptprotectUiForbidden, out output);
            if (!succeeded)
                throw new CryptographicException(new Win32Exception(Marshal.GetLastWin32Error()).Message);

            var result = new byte[output.Length];
            Marshal.Copy(output.Data, result, 0, output.Length);
            return result;
        }
        finally
        {
            var zeros = new byte[input.Length];
            Marshal.Copy(zeros, 0, inputPointer, input.Length);
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
        ref DataBlob dataIn,
        string description,
        IntPtr optionalEntropy,
        IntPtr reserved,
        IntPtr prompt,
        uint flags,
        out DataBlob dataOut);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptUnprotectData(
        ref DataBlob dataIn,
        out IntPtr description,
        IntPtr optionalEntropy,
        IntPtr reserved,
        IntPtr prompt,
        uint flags,
        out DataBlob dataOut);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LocalFree(IntPtr memory);
}
