using System.Collections.Concurrent;
using System.Net;
using System.Text;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class TelegramAwgFetchTests
{
    private const string SessionToken = "test-session-token-that-is-long-enough-for-validation";
    private static readonly string ValidConfig = $"[Interface]\nPrivateKey = {Convert.ToBase64String(new byte[32])}\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = {Convert.ToBase64String(Enumerable.Repeat((byte)1, 32).ToArray())}\nAllowedIPs = 0.0.0.0/0\nEndpoint = vpn.example.test:51820\n";

    [Fact]
    public async Task WindowsFetchRequestsOnlyAwg31()
    {
        var formats = new ConcurrentBag<string?>();
        using var client = CreateClient((request, _) =>
        {
            formats.Add(Query(request, "format"));
            return Task.FromResult(AwgResponse(ValidConfig));
        });
        var profiles = await new TelegramApiClient(client).FetchAwgProfilesAsync(SubscriptionUri, SessionToken);
        Assert.Single(profiles);
        Assert.All(formats, format => Assert.Equal("amneziawg31", format));
        Assert.All(profiles, profile => Assert.Equal("31", profile.Generation));
    }

    [Fact]
    public async Task OptionalBaseFetchCanExceedPreviousEightSecondBudgetAndCarriesSessionHeader()
    {
        var requests = new ConcurrentBag<HttpRequestMessage>();
        using var client = CreateClient(async (request, cancellationToken) =>
        {
            requests.Add(CloneHeaders(request));
            if (IsCurrentGeneration(request) && !HasServerId(request))
            {
                await Task.Delay(TimeSpan.FromSeconds(9), cancellationToken);
                return AwgResponse(ValidConfig);
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });
        var api = new TelegramApiClient(client);

        var profiles = await api.FetchAwgProfilesAsync(SubscriptionUri, SessionToken);

        Assert.Contains(profiles, profile => profile.RouteId == "awg31");
        Assert.All(requests, request => Assert.Equal(SessionToken, request.Headers.GetValues("X-TG-App-Token").Single()));
        Assert.True(requests.Count > 0);
        foreach (var request in requests)
            request.Dispose();
    }

    [Fact]
    public async Task MalformedManifestPreservesValidBaseProfile()
    {
        using var client = CreateClient((request, _) => Task.FromResult(
            IsCurrentGeneration(request) && !HasServerId(request)
                ? AwgResponse(ValidConfig, "{not-json")
                : new HttpResponseMessage(HttpStatusCode.NotFound)));
        var api = new TelegramApiClient(client);

        var profiles = await api.FetchAwgProfilesAsync(SubscriptionUri, SessionToken);

        Assert.Contains(profiles, profile => profile.RouteId == "awg31" && profile.Config == ValidConfig);
    }

    [Fact]
    public async Task SuccessfulServerProfileSurvivesAnotherServerFailure()
    {
        using var client = CreateClient((request, _) => Task.FromResult(
            RespondToManifestServer(request)));
        var api = new TelegramApiClient(client);

        var profiles = await api.FetchAwgProfilesAsync(SubscriptionUri, SessionToken);

        Assert.Contains(profiles, profile => profile.ServerId == "good-node");
        Assert.DoesNotContain(profiles, profile => profile.ServerId == "broken-node");
    }

    [Fact]
    public async Task CallerCancellationCancelsOptionalFetch()
    {
        using var client = CreateClient(async (_, cancellationToken) =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            return new HttpResponseMessage(HttpStatusCode.OK);
        });
        var api = new TelegramApiClient(client);
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(100));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => api.FetchAwgProfilesAsync(SubscriptionUri, SessionToken, cancellation.Token));
    }

    private static HttpResponseMessage RespondToManifestServer(HttpRequestMessage request)
    {
        if (!IsCurrentGeneration(request))
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        if (!HasServerId(request))
        {
            var response = AwgResponse(ValidConfig);
            response.Headers.TryAddWithoutValidation("X-Deytt-Awg-Servers",
                "[{\"id\":\"base-node\",\"label\":\"Base\"},{\"id\":\"good-node\",\"label\":\"Good\"},{\"id\":\"broken-node\",\"label\":\"Broken\"}]");
            return response;
        }
        var serverId = Query(request, "server_id");
        return serverId == "good-node"
            ? AwgResponse(ValidConfig)
            : new HttpResponseMessage(HttpStatusCode.BadGateway);
    }

    private static HttpClient CreateClient(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) =>
        new(new DelegateHandler(send));

    private static HttpResponseMessage AwgResponse(string config, string? manifest = null)
    {
        var response = new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent(config, Encoding.UTF8, "text/plain"),
        };
        if (manifest is not null)
            response.Headers.TryAddWithoutValidation("X-Deytt-Awg-Servers", manifest);
        return response;
    }

    private static bool IsCurrentGeneration(HttpRequestMessage request) =>
        Query(request, "format") == "amneziawg31";

    private static bool HasServerId(HttpRequestMessage request) => Query(request, "server_id") is not null;

    private static string? Query(HttpRequestMessage request, string name) =>
        request.RequestUri!.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Select(part => part.Split('=', 2))
            .Where(part => Uri.UnescapeDataString(part[0]) == name)
            .Select(part => part.Length == 2 ? Uri.UnescapeDataString(part[1]) : "")
            .FirstOrDefault();

    private static HttpRequestMessage CloneHeaders(HttpRequestMessage request)
    {
        var clone = new HttpRequestMessage(request.Method, request.RequestUri);
        foreach (var header in request.Headers)
            clone.Headers.TryAddWithoutValidation(header.Key, header.Value);
        return clone;
    }

    private static readonly Uri SubscriptionUri = new("https://deytt.space/sub/test?lang=ru");

    private sealed class DelegateHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send)
        : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            send(request, cancellationToken);
    }
}
