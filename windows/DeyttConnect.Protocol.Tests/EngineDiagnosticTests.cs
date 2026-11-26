using System.Reflection;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class EngineDiagnosticTests
{
    [Theory]
    [InlineData("deytt-connect", true)]
    [InlineData("DEYTT-CONNECT-RECOVERY", true)]
    [InlineData("sing-tun", false)]
    [InlineData("happ-tun", false)]
    public void TunnelCanaryDoesNotBindToAnotherClientsAdapter(string name, bool owned) =>
        Assert.Equal(owned, EngineController.IsOwnedTunInterface(name));

    [Theory]
    [InlineData("ERROR start inbound/tun[tun-in]: configure tun interface: A device attached to the system is not functioning.", true)]
    [InlineData("ERROR configure tun interface: access denied", false)]
    [InlineData("ERROR outbound/vless: A device attached to the system is not functioning.", false)]
    [InlineData("ERROR configure tun interface: timeout", false)]
    public void DeviceRecoveryMatchesOnlyTheSpecificTunStartupFailure(string diagnostic, bool expected) =>
        Assert.Equal(expected, EngineController.IsTunDeviceNotFunctioning(diagnostic));

    [Fact]
    public void DeviceRecoveryChangesOnlyTheAppOwnedInterfaceIdentity()
    {
        const string profile = """
            {"inbounds":[{"type":"tun","interface_name":"deytt-connect","address":["172.19.0.1/30"],"strict_route":true}],
             "route":{"final":"route:NL:VLESS"},"outbounds":[{"tag":"route:NL:VLESS","type":"vless"}]}
            """;
        var original = System.Text.Json.Nodes.JsonNode.Parse(profile)!;
        var recovered = System.Text.Json.Nodes.JsonNode.Parse(EngineController.WithRecoveryTunInterface(profile))!;
        Assert.Equal("deytt-connect-recovery", recovered["inbounds"]![0]!["interface_name"]!.GetValue<string>());
        original["inbounds"]![0]!["interface_name"] = "deytt-connect-recovery";
        Assert.True(System.Text.Json.Nodes.JsonNode.DeepEquals(original, recovered));
    }

    [Theory]
    [InlineData("ERROR start inbound/tun[tun-in]: configure tun interface: Cannot create a file when that file already exists.", true)]
    [InlineData("ERROR outbound/vless: bind: address already in use", false)]
    [InlineData("ERROR configure tun interface: access denied", false)]
    public void OnlyTheSpecificExistingTunAdapterFailureIsRetryable(string diagnostic, bool expected) =>
        Assert.Equal(expected, EngineController.IsTunAdapterAlreadyExists(diagnostic));

    private sealed class DelayedReader(string text) : StringReader(text)
    {
        public override async ValueTask<int> ReadAsync(Memory<char> buffer,
            CancellationToken cancellationToken = default)
        {
            await Task.Delay(25, cancellationToken);
            return await base.ReadAsync(buffer, cancellationToken);
        }
    }

    [Fact]
    public async Task ExitWaitIncludesTerminalOutputThatArrivesAsynchronously()
    {
        var diagnostic = new EngineController.EngineRuntimeDiagnostic();
        diagnostic.Attach(new StringReader(""), new DelayedReader("ERROR cold adapter initialization failed"));
        Assert.Null(diagnostic.ExitReason);

        await diagnostic.WaitForDrainAsync();

        Assert.Contains("cold adapter initialization failed", EngineController.FormatEngineExit(1, diagnostic));
    }

    [Fact]
    public async Task RuntimeDrainCapturesStdoutAndUnterminatedStderrBeforeExitFormatting()
    {
        var diagnostic = new EngineController.EngineRuntimeDiagnostic();
        diagnostic.Attach(new StringReader("INFO engine starting\n"),
            new StringReader("panic: adapter initialization failed host=edge.example.test"));
        await diagnostic.WaitForDrainAsync();

        var result = EngineController.FormatEngineExit(1, diagnostic);
        Assert.Contains("adapter initialization failed", result);
        Assert.Contains("Код завершения: 0x00000001", result);
        Assert.DoesNotContain("edge.example.test", result);
    }

    [Fact]
    public async Task StdoutFailureIsRetainedEvenWhenStderrEndsWithInfo()
    {
        var diagnostic = new EngineController.EngineRuntimeDiagnostic();
        diagnostic.Attach(new StringReader("FATAL adapter creation failed\n"),
            new StringReader("INFO shutting down\n"));
        await diagnostic.WaitForDrainAsync();

        Assert.Contains("adapter creation failed", diagnostic.ExitReason);
    }

    [Fact]
    public void ShutdownDnsCancellationDoesNotOverwriteTheEngineStartupFailure()
    {
        var diagnostic = new EngineController.EngineRuntimeDiagnostic();
        diagnostic.Record("FATAL[0000] inbound/tun: failed to initialize adapter");
        diagnostic.Record("ERROR[0001] dns: exchange failed: context canceled");

        var result = EngineController.FormatEngineExit(1, diagnostic);

        Assert.Contains("failed to initialize adapter", result);
        Assert.DoesNotContain("exchange failed: context canceled", result);
    }

    [Fact]
    public async Task UnlabelledTerminalCauseNoLongerBecomesUnknownExit()
    {
        var diagnostic = new EngineController.EngineRuntimeDiagnostic();
        diagnostic.Attach(new StringReader(""), new StringReader("bind: address already in use"));
        await diagnostic.WaitForDrainAsync();

        Assert.Contains("bind: address already in use", EngineController.FormatEngineExit(1, diagnostic));
    }

    [Fact]
    public async Task OversizedOutputIsDrainedWithoutDiscardingFollowingFailure()
    {
        var diagnostic = new EngineController.EngineRuntimeDiagnostic();
        diagnostic.Attach(new StringReader(new string('x', 100_000) + "\nERROR failed to create TUN\n"),
            new StringReader(""));
        await diagnostic.WaitForDrainAsync();

        Assert.Contains("failed to create TUN", diagnostic.ExitReason);
    }

    private static string Summarize(string diagnostic) =>
        (string)typeof(EngineController).GetMethod("SummarizeEngineDiagnostic",
            BindingFlags.Static | BindingFlags.NonPublic)!.Invoke(null, [diagnostic])!;

    [Fact]
    public void NestedWebSocketCauseSurvivesSanitizedContext()
    {
        var result = Summarize("ERROR[0000] outbound/vless[route:NL:VLESS]: connection to api.example.test:443: " +
            "dial transport: websocket: bad handshake: HTTP 403");

        Assert.Contains("websocket: bad handshake: HTTP 403", result);
        Assert.DoesNotContain("api.example.test", result);
        Assert.Contains("[домен скрыт]", result);
    }

    [Fact]
    public void LongerDiagnosticStillHidesCredentialsAndIdentifiers()
    {
        var result = Summarize("ERROR[0000] TLS handshake failed: host=edge.example.test " +
            "password=synthetic-secret uuid=12345678-1234-1234-1234-123456789abc " +
            "peer 22345678-1234-1234-1234-123456789abc at 203.0.113.5:443");

        Assert.Contains("TLS handshake failed", result);
        Assert.DoesNotContain("synthetic-secret", result);
        Assert.DoesNotContain("edge.example.test", result);
        Assert.DoesNotContain("12345678-1234", result);
        Assert.DoesNotContain("22345678-1234", result);
        Assert.DoesNotContain("203.0.113.5", result);
    }

    [Fact]
    public void DiagnosticRemainsBounded()
    {
        var result = Summarize("ERROR[0000] " + string.Join(" ", Enumerable.Repeat("failure", 100)));

        Assert.Equal(257, result.Length);
        Assert.EndsWith("…", result);
    }
}
