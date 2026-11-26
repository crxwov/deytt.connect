using System.Net;
using System.Text;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class TelegramUsernameTests
{
    [Theory]
    [InlineData("alice_1", "@alice_1")]
    [InlineData(" @Alice_1 ", "@Alice_1")]
    [InlineData("abcde", "@abcde")]
    [InlineData("abcdefghijklmnopqrstuvwxyz123456", "@abcdefghijklmnopqrstuvwxyz123456")]
    public void NormalizesAcceptedUsernames(string input, string expected)
    {
        Assert.True(TelegramApiClient.TryNormalizeUsername(input, out var actual));
        Assert.Equal(expected, actual);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("abcd")]
    [InlineData("abcdefghijklmnopqrstuvwxyz1234567")]
    [InlineData("@@alice")]
    [InlineData("alice-name")]
    [InlineData("alice name")]
    [InlineData("алиса")]
    [InlineData("alice\nother")]
    [InlineData("alice<script>")]
    [InlineData("https://t.me/alice")]
    public async Task InvalidInputNeverSendsPairingRequest(string? input)
    {
        Assert.False(TelegramApiClient.TryNormalizeUsername(input, out var normalized));
        Assert.Equal("", normalized);
        using var handler = new PairingHandler();
        using var http = new HttpClient(handler);
        var error = await Assert.ThrowsAsync<TelegramApiException>(
            () => new TelegramApiClient(http).StartPairingAsync(input!));
        Assert.Equal("invalid_username", error.Code);
        Assert.Equal(0, handler.RequestCount);
    }

    [Fact]
    public async Task PairingPayloadUsesValidatedUsernameWithoutPrefix()
    {
        using var handler = new PairingHandler();
        using var http = new HttpClient(handler);
        await new TelegramApiClient(http).StartPairingAsync(" @Alice_1 ");
        Assert.Equal(1, handler.RequestCount);
        Assert.Equal("{\"username\":\"Alice_1\"}", handler.Body);
    }

    private sealed class PairingHandler : HttpMessageHandler
    {
        public int RequestCount { get; private set; }
        public string? Body { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            RequestCount++;
            Body = await request.Content!.ReadAsStringAsync(cancellationToken);
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent("{\"challenge\":\"test-challenge\",\"bot_url\":\"https://t.me/deyttbot\",\"delivery\":\"sent\"}", Encoding.UTF8, "application/json"),
            };
        }
    }
}
