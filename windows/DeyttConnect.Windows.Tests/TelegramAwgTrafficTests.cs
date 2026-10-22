using System.Net;
using System.Text;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class TelegramAwgTrafficTests
{
    private const string SessionToken = "test-session-token-that-is-long-enough-for-validation";

    [Fact]
    public async Task ParsesAwgQuotaAndTreatsNaiveExpiryAsUtc()
    {
        using var client = CreateClient((request, _) =>
        {
            Assert.Equal(HttpMethod.Get, request.Method);
            Assert.Equal("/api/tg/traffic", request.RequestUri!.AbsolutePath);
            Assert.Equal(SessionToken, request.Headers.GetValues("X-TG-App-Token").Single());
            return Task.FromResult(JsonResponse("""{"awg":{"quota_bytes":0,"used_bytes":123,"expires_at":"2026-10-22T12:30:00","days_left":4}}"""));
        });

        var traffic = await new TelegramApiClient(client).GetAwgTrafficAsync(SessionToken);

        Assert.NotNull(traffic);
        Assert.Equal(0, traffic.QuotaBytes);
        Assert.Equal(123, traffic.UsedBytes);
        Assert.Equal(4, traffic.DaysLeft);
        Assert.Equal("2026-10-22T12:30:00.0000000+00:00", traffic.ExpiresAt);
    }

    [Fact]
    public async Task NullAwgIsAnAbsentOptionalQuota()
    {
        using var client = CreateClient((_, _) => Task.FromResult(JsonResponse("""{"awg":null}""")));

        var traffic = await new TelegramApiClient(client).GetAwgTrafficAsync(SessionToken);

        Assert.Null(traffic);
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("{\"awg\":[]}")]
    [InlineData("{\"awg\":{\"quota_bytes\":-1,\"used_bytes\":0}}")]
    [InlineData("{\"awg\":{\"quota_bytes\":1,\"used_bytes\":\"2\"}}")]
    [InlineData("{\"awg\":{\"quota_bytes\":1,\"used_bytes\":2,\"expires_at\":17}}")]
    public async Task MalformedOptionalQuotaIsRejected(string body)
    {
        using var client = CreateClient((_, _) => Task.FromResult(JsonResponse(body)));

        var error = await Assert.ThrowsAsync<TelegramApiException>(
            () => new TelegramApiClient(client).GetAwgTrafficAsync(SessionToken));

        Assert.Equal("invalid_response", error.Code);
        Assert.Equal(HttpStatusCode.BadGateway, error.StatusCode);
    }

    [Fact]
    public async Task UnauthorizedResponseRemainsVisibleToRefreshCaller()
    {
        using var client = CreateClient((_, _) => Task.FromResult(new HttpResponseMessage(HttpStatusCode.Unauthorized)
        {
            Content = new StringContent("{}", Encoding.UTF8, "application/json"),
        }));

        var error = await Assert.ThrowsAsync<TelegramApiException>(
            () => new TelegramApiClient(client).GetAwgTrafficAsync(SessionToken));

        Assert.True(error.IsUnauthorized);
    }

    [Fact]
    public async Task CallerCancellationIsPropagated()
    {
        using var client = CreateClient(async (_, cancellationToken) =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            return JsonResponse("{}");
        });
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(100));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => new TelegramApiClient(client).GetAwgTrafficAsync(SessionToken, cancellation.Token));
    }

    private static HttpResponseMessage JsonResponse(string body) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private static HttpClient CreateClient(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) =>
        new(new DelegateHandler(send));

    private sealed class DelegateHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send)
        : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            send(request, cancellationToken);
    }
}
