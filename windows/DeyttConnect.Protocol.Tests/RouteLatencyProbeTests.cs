using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using DeyttConnect.Windows.Service;
using Xunit;

namespace DeyttConnect.Protocol.Tests;

public sealed class RouteLatencyProbeTests
{
    [Fact]
    public async Task FallsBackToIndependentTargetAndReturnsWarmMedian()
    {
        var requests = new ConcurrentBag<RequestSnapshot>();
        var cloudflareCalls = 0;
        var googleCalls = 0;

        using var handler = new CallbackHandler(async (request, cancellationToken) =>
        {
            requests.Add(RequestSnapshot.From(request));

            if (request.RequestUri!.Host == "cp.cloudflare.com")
            {
                Interlocked.Increment(ref cloudflareCalls);
                return Response(request, HttpStatusCode.ServiceUnavailable);
            }

            var call = Interlocked.Increment(ref googleCalls);
            var delay = call switch
            {
                1 => 5,
                2 => 20,
                3 => 100,
                _ => 50,
            };
            await Task.Delay(delay, cancellationToken);
            return Response(request, HttpStatusCode.NoContent);
        });
        using var client = NewClient(handler);
        var observations = new List<RouteLatencyProbeObservation>();

        var result = await RouteLatencyProbe.MeasureAsync(
            client,
            CancellationToken.None,
            observations.Add,
            new RouteLatencyProbeTimeouts(TimeSpan.FromSeconds(3), TimeSpan.FromSeconds(2)));

        Assert.NotNull(result.LatencyMilliseconds);
        Assert.Null(result.Failure);
        Assert.False(result.UsedColdFallback);
        Assert.Equal(1, cloudflareCalls);
        Assert.Equal(4, googleCalls);

        var warmTimes = observations
            .Where(observation => observation.Phase == RouteLatencyProbePhase.WarmSample)
            .Select(observation => Assert.IsType<long>(observation.LatencyMilliseconds))
            .OrderBy(value => value)
            .ToArray();
        Assert.Equal(3, warmTimes.Length);
        Assert.Equal(
            new[] { 1, 2, 3 },
            observations.Where(observation => observation.Phase == RouteLatencyProbePhase.WarmSample)
                .Select(observation => observation.Attempt));
        Assert.Equal(warmTimes[1], result.LatencyMilliseconds);
        Assert.Equal(4, observations.Count(observation => observation.LatencyMilliseconds is not null));

        Assert.Equal(
            new[] { "connectivitycheck.gstatic.com", "cp.cloudflare.com" },
            requests.Select(request => request.Host).Distinct().OrderBy(host => host, StringComparer.Ordinal));
        Assert.All(requests, request =>
        {
            Assert.Equal(HttpMethod.Get, request.Method);
            Assert.Null(request.Authorization);
            Assert.True(request.NoCache);
            Assert.True(request.NoStore);
            Assert.False(request.ConnectionClose);
        });
    }

    [Fact]
    public async Task ReturnsColdSampleWhenNoWarmSampleSucceeds()
    {
        var googleCalls = 0;
        using var handler = new CallbackHandler((request, _) =>
        {
            if (request.RequestUri!.Host == "cp.cloudflare.com")
                return Task.FromResult(Response(request, HttpStatusCode.BadGateway));

            var call = Interlocked.Increment(ref googleCalls);
            return Task.FromResult(Response(request, call == 1
                ? HttpStatusCode.NoContent
                : HttpStatusCode.ServiceUnavailable));
        });
        using var client = NewClient(handler);

        var result = await RouteLatencyProbe.MeasureAsync(
            client,
            CancellationToken.None,
            timeouts: new RouteLatencyProbeTimeouts(TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(1)));

        Assert.NotNull(result.LatencyMilliseconds);
        Assert.Null(result.Failure);
        Assert.True(result.UsedColdFallback);
        Assert.Equal(4, googleCalls);
    }

    [Fact]
    public async Task UsesMedianOfSuccessfulWarmSamplesWhenOneWarmAttemptFails()
    {
        var googleCalls = 0;
        var observations = new List<RouteLatencyProbeObservation>();
        using var handler = new CallbackHandler((request, _) =>
        {
            if (request.RequestUri!.Host == "cp.cloudflare.com")
                return Task.FromResult(Response(request, HttpStatusCode.BadGateway));

            var call = Interlocked.Increment(ref googleCalls);
            return Task.FromResult(Response(request, call == 3
                ? HttpStatusCode.ServiceUnavailable
                : HttpStatusCode.NoContent));
        });
        using var client = NewClient(handler);

        var result = await RouteLatencyProbe.MeasureAsync(
            client,
            CancellationToken.None,
            observations.Add,
            new RouteLatencyProbeTimeouts(TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(1)));

        var warmTimes = observations
            .Where(observation => observation.Phase == RouteLatencyProbePhase.WarmSample)
            .Where(observation => observation.LatencyMilliseconds is not null)
            .Select(observation => observation.LatencyMilliseconds!.Value)
            .OrderBy(value => value)
            .ToArray();
        Assert.Equal(2, warmTimes.Length);
        Assert.Equal((long)Math.Round((warmTimes[0] + warmTimes[1]) / 2d, MidpointRounding.AwayFromZero), result.LatencyMilliseconds);
        Assert.False(result.UsedColdFallback);
    }

    [Fact]
    public async Task RejectsUnexpectedStatusAndRedirected204()
    {
        using var handler = new CallbackHandler((request, _) =>
        {
            if (request.RequestUri!.Host == "cp.cloudflare.com")
                return Task.FromResult(Response(request, HttpStatusCode.OK));

            var response = new HttpResponseMessage(HttpStatusCode.NoContent)
            {
                RequestMessage = new HttpRequestMessage(HttpMethod.Get, "https://example.invalid/generate_204"),
            };
            return Task.FromResult(response);
        });
        using var client = NewClient(handler);

        var result = await RouteLatencyProbe.MeasureAsync(
            client,
            CancellationToken.None,
            timeouts: new RouteLatencyProbeTimeouts(TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(1)));

        Assert.Null(result.LatencyMilliseconds);
        Assert.False(result.UsedColdFallback);
        Assert.Contains("перенаправил", result.Failure ?? string.Empty, StringComparison.Ordinal);
    }

    [Fact]
    public async Task DoesNotSendClientDefaultAuthorizationHeader()
    {
        var requestCount = 0;
        using var handler = new CallbackHandler((request, _) =>
        {
            Interlocked.Increment(ref requestCount);
            return Task.FromResult(Response(request, HttpStatusCode.NoContent));
        });
        using var client = NewClient(handler);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "synthetic-token");

        var result = await RouteLatencyProbe.MeasureAsync(client, CancellationToken.None);

        Assert.Null(result.LatencyMilliseconds);
        Assert.Equal(0, requestCount);
        Assert.DoesNotContain("synthetic-token", result.Failure ?? string.Empty, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ReportsTimeoutSeparatelyFromUserCancellation()
    {
        using var timeoutHandler = new CallbackHandler(async (_, cancellationToken) =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            throw new InvalidOperationException("Unreachable");
        });
        using var timeoutClient = NewClient(timeoutHandler);

        var timedOut = await RouteLatencyProbe.MeasureAsync(
            timeoutClient,
            CancellationToken.None,
            timeouts: new RouteLatencyProbeTimeouts(TimeSpan.FromMilliseconds(300), TimeSpan.FromMilliseconds(60)));

        Assert.Null(timedOut.LatencyMilliseconds);
        Assert.Contains("вовремя", timedOut.Failure ?? string.Empty, StringComparison.Ordinal);

        using var cancelHandler = new CallbackHandler(async (_, cancellationToken) =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            throw new InvalidOperationException("Unreachable");
        });
        using var cancelClient = NewClient(cancelHandler);
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(60));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => RouteLatencyProbe.MeasureAsync(
            cancelClient,
            cancellation.Token,
            timeouts: new RouteLatencyProbeTimeouts(TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(1))));
    }

    [Fact]
    public async Task RecoversWhenBothColdConnectionsAreResetBeforeRouteBecomesReady()
    {
        var calls = new ConcurrentDictionary<string, int>();
        var observations = new List<RouteLatencyProbeObservation>();
        using var handler = new CallbackHandler((request, _) =>
        {
            var attempt = calls.AddOrUpdate(request.RequestUri!.Host, 1, (_, previous) => previous + 1);
            if (attempt == 1)
                throw new HttpRequestException(HttpRequestError.ConnectionError, "synthetic reset");
            return Task.FromResult(Response(request, HttpStatusCode.NoContent));
        });
        using var client = NewClient(handler);
        var result = await RouteLatencyProbe.MeasureAsync(client, CancellationToken.None, observations.Add);
        Assert.NotNull(result.LatencyMilliseconds);
        Assert.Null(result.Failure);
        Assert.Contains(observations, observation => observation.Phase == RouteLatencyProbePhase.ColdConnect && observation.Attempt == 2 && observation.LatencyMilliseconds is not null);
        Assert.All(observations, observation => Assert.InRange(observation.Attempt, 1, 3));
    }

    [Fact]
    public async Task ReportsTlsFailureWithoutRetryingInvalidSecurityHandshake()
    {
        var calls = 0;
        using var handler = new CallbackHandler((_, _) =>
        {
            Interlocked.Increment(ref calls);
            throw new HttpRequestException(HttpRequestError.SecureConnectionError, "synthetic secret not for UI");
        });
        using var client = NewClient(handler);
        var result = await RouteLatencyProbe.MeasureAsync(client, CancellationToken.None);
        Assert.Null(result.LatencyMilliseconds);
        Assert.Equal(2, calls);
        Assert.Contains("TLS", result.Failure);
        Assert.DoesNotContain("secret", result.Failure);
    }

    [Fact]
    public async Task ReportsHttpStatusAndRetriesOnlyTransientStatuses()
    {
        var calls = 0;
        using var handler = new CallbackHandler((request, _) =>
        {
            Interlocked.Increment(ref calls);
            return Task.FromResult(Response(request, HttpStatusCode.Forbidden));
        });
        using var client = NewClient(handler);
        var result = await RouteLatencyProbe.MeasureAsync(client, CancellationToken.None);
        Assert.Null(result.LatencyMilliseconds);
        Assert.Equal(2, calls);
        Assert.Contains("HTTP 403", result.Failure);
    }

    [Fact]
    public async Task RecoversFromTemporaryCheckerOverload()
    {
        var calls = new ConcurrentDictionary<string, int>();
        using var handler = new CallbackHandler((request, _) =>
        {
            var attempt = calls.AddOrUpdate(request.RequestUri!.Host, 1, (_, previous) => previous + 1);
            return Task.FromResult(Response(request, attempt == 1 ? HttpStatusCode.ServiceUnavailable : HttpStatusCode.NoContent));
        });
        using var client = NewClient(handler);
        var result = await RouteLatencyProbe.MeasureAsync(client, CancellationToken.None);
        Assert.NotNull(result.LatencyMilliseconds);
        Assert.Null(result.Failure);
    }

    private static HttpClient NewClient(HttpMessageHandler handler) => new(handler)
    {
        Timeout = Timeout.InfiniteTimeSpan,
    };

    private static HttpResponseMessage Response(HttpRequestMessage request, HttpStatusCode status) => new(status)
    {
        RequestMessage = request,
    };

    private sealed class CallbackHandler(
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) => send(request, cancellationToken);
    }

    private sealed record RequestSnapshot(
        string Host,
        HttpMethod Method,
        string? Authorization,
        bool NoCache,
        bool NoStore,
        bool ConnectionClose)
    {
        internal static RequestSnapshot From(HttpRequestMessage request) => new(
            request.RequestUri!.Host,
            request.Method,
            request.Headers.Authorization?.ToString(),
            request.Headers.CacheControl?.NoCache == true,
            request.Headers.CacheControl?.NoStore == true,
            request.Headers.ConnectionClose == true);
    }
}
