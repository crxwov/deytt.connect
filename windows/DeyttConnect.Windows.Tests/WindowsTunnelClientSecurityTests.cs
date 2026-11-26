using System.IO.Pipes;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsTunnelClientSecurityTests
{
    [Fact]
    public async Task ValidateServerIdentityRejectsNonSystemPipeServer()
    {
        if (!OperatingSystem.IsWindows())
            return;

        var pipeName = "DEYTT.Test.Security." + Guid.NewGuid().ToString("N");
        await using var server = new NamedPipeServerStream(pipeName, PipeDirection.InOut, 1,
            PipeTransmissionMode.Byte, PipeOptions.Asynchronous);

        var serverTask = server.WaitForConnectionAsync();

        await using var client = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut,
            PipeOptions.Asynchronous);
        await client.ConnectAsync();
        await serverTask;

        var ex = Assert.Throws<UnauthorizedAccessException>(() =>
            WindowsTunnelClient.ValidateServerIdentity(client));
        Assert.Contains("not owned by LocalSystem", ex.Message);

        WindowsTunnelClient.ValidateServerIdentity(client, allowCurrentProcessForTesting: true);
    }
}
