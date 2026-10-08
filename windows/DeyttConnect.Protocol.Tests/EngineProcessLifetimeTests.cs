using System.Diagnostics;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class EngineProcessLifetimeTests
{
    [Fact]
    public async Task DisposingLifetimeKillsOnlyTheAttachedProcess()
    {
        if (!OperatingSystem.IsWindows())
            return;

        Process? attached = null;
        Process? unrelated = null;
        EngineProcessLifetime? lifetime = null;
        try
        {
            unrelated = StartSleeper();
            attached = StartSleeper();
            lifetime = EngineProcessLifetime.Attach(attached);

            lifetime.Dispose();
            lifetime = null;

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await attached.WaitForExitAsync(timeout.Token);

            Assert.True(attached.HasExited);
            Assert.False(unrelated.HasExited);
        }
        finally
        {
            lifetime?.Dispose();
            StopTestProcess(attached);
            StopTestProcess(unrelated);
            attached?.Dispose();
            unrelated?.Dispose();
        }
    }

    private static Process StartSleeper()
    {
        var powershell = Path.Combine(Environment.SystemDirectory, "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!File.Exists(powershell))
            throw new FileNotFoundException("Windows PowerShell is required for the native process lifetime test.");

        var start = new ProcessStartInfo
        {
            FileName = powershell,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        start.ArgumentList.Add("-NoProfile");
        start.ArgumentList.Add("-NonInteractive");
        start.ArgumentList.Add("-Command");
        start.ArgumentList.Add("Start-Sleep -Seconds 120");
        return Process.Start(start)
               ?? throw new InvalidOperationException("Could not start a test-owned sleeper process.");
    }

    private static void StopTestProcess(Process? process)
    {
        if (process is null)
            return;
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
                process.WaitForExit(5_000);
            }
        }
        catch (Exception error) when (error is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            // Cleanup applies only to the sleeper started by this test.
        }
    }
}
