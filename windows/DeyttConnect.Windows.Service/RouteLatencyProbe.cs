using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;

namespace DeyttConnect.Windows.Service;

internal static class RouteLatencyProbe
{
    private static readonly Uri[] ColdTargets =
    [
        new("https://cp.cloudflare.com/generate_204"),
        new("https://connectivitycheck.gstatic.com/generate_204"),
    ];

    internal static readonly RouteLatencyProbeTimeouts ProductionTimeouts = new(
        Overall: TimeSpan.FromSeconds(12),
        Individual: TimeSpan.FromSeconds(6));

    internal static async Task<RouteLatencyProbeResult> MeasureAsync(
        HttpClient proxyClient,
        CancellationToken cancellationToken,
        Action<RouteLatencyProbeObservation>? reportProgress = null,
        RouteLatencyProbeTimeouts? timeouts = null)
    {
        ArgumentNullException.ThrowIfNull(proxyClient);
        cancellationToken.ThrowIfCancellationRequested();

        var limits = timeouts ?? ProductionTimeouts;
        limits.Validate();

        if (proxyClient.DefaultRequestHeaders.Contains("Authorization"))
        {
            return new RouteLatencyProbeResult(
                LatencyMilliseconds: null,
                Failure: "Не удалось выполнить HTTPS-проверку маршрута.",
                UsedColdFallback: false);
        }

        using var overallCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        overallCancellation.CancelAfter(limits.Overall);

        var coldOutcomes = new List<RouteLatencyProbeAttempt>(ColdTargets.Length * 2);
        RouteLatencyProbeAttempt? selectedCold = null;

        for (var round = 1; round <= 2 && selectedCold is null && !overallCancellation.IsCancellationRequested; round++)
        {
            using var coldCancellation = CancellationTokenSource.CreateLinkedTokenSource(overallCancellation.Token);
            var coldTasks = ColdTargets.Select(target => MeasureRequestAsync(proxyClient, target,
                coldCancellation.Token, cancellationToken, overallCancellation.Token, limits.Individual)).ToArray();
            var pendingCold = new HashSet<Task<RouteLatencyProbeAttempt>>(coldTasks);
            var roundOutcomes = new List<RouteLatencyProbeAttempt>(ColdTargets.Length);
            try
            {
                while (pendingCold.Count > 0 && selectedCold is null)
                {
                    var finished = await Task.WhenAny(pendingCold).ConfigureAwait(false);
                    pendingCold.Remove(finished);
                    var outcome = await finished.ConfigureAwait(false);
                    cancellationToken.ThrowIfCancellationRequested();
                    coldOutcomes.Add(outcome);
                    roundOutcomes.Add(outcome);
                    reportProgress?.Invoke(new RouteLatencyProbeObservation(round,
                        RouteLatencyProbePhase.ColdConnect, outcome.LatencyMilliseconds));
                    if (outcome.LatencyMilliseconds is not null)
                        selectedCold = outcome;
                }
            }
            finally
            {
                coldCancellation.Cancel();
                try { await Task.WhenAll(coldTasks).ConfigureAwait(false); }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { }
            }
            if (selectedCold is null && !roundOutcomes.Any(IsRetryable))
                break;
            if (selectedCold is null && round == 1 && !overallCancellation.IsCancellationRequested)
            {
                try { await Task.Delay(200, overallCancellation.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { }
            }
        }

        cancellationToken.ThrowIfCancellationRequested();

        if (selectedCold is null)
        {
            return new RouteLatencyProbeResult(
                LatencyMilliseconds: null,
                Failure: DescribeFailure(coldOutcomes),
                UsedColdFallback: false);
        }

        var coldLatency = selectedCold.LatencyMilliseconds!.Value;
        var warmLatencies = new List<long>(capacity: 3);

        for (var sample = 1; sample <= 3 && !overallCancellation.IsCancellationRequested; sample++)
        {
            var outcome = await MeasureRequestAsync(
                proxyClient,
                selectedCold.Target,
                overallCancellation.Token,
                cancellationToken,
                overallCancellation.Token,
                limits.Individual).ConfigureAwait(false);

            cancellationToken.ThrowIfCancellationRequested();
            reportProgress?.Invoke(new RouteLatencyProbeObservation(
                Attempt: sample,
                Phase: RouteLatencyProbePhase.WarmSample,
                LatencyMilliseconds: outcome.LatencyMilliseconds));

            if (outcome.LatencyMilliseconds is long latency)
                warmLatencies.Add(latency);
        }

        if (warmLatencies.Count == 0)
        {
            return new RouteLatencyProbeResult(
                LatencyMilliseconds: coldLatency,
                Failure: null,
                UsedColdFallback: true);
        }

        warmLatencies.Sort();
        var middle = warmLatencies.Count / 2;
        var median = warmLatencies.Count % 2 == 1
            ? warmLatencies[middle]
            : (long)Math.Round((warmLatencies[middle - 1] + warmLatencies[middle]) / 2d, MidpointRounding.AwayFromZero);
        return new RouteLatencyProbeResult(
            LatencyMilliseconds: median,
            Failure: null,
            UsedColdFallback: false);
    }

    private static async Task<RouteLatencyProbeAttempt> MeasureRequestAsync(
        HttpClient client,
        Uri target,
        CancellationToken phaseCancellation,
        CancellationToken userCancellation,
        CancellationToken overallCancellation,
        TimeSpan individualTimeout)
    {
        using var attemptCancellation = CancellationTokenSource.CreateLinkedTokenSource(phaseCancellation);
        attemptCancellation.CancelAfter(individualTimeout);

        var started = Stopwatch.GetTimestamp();
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, target);
            request.Headers.CacheControl = new CacheControlHeaderValue
            {
                NoCache = true,
                NoStore = true,
            };

            using var response = await client.SendAsync(
                request,
                HttpCompletionOption.ResponseHeadersRead,
                attemptCancellation.Token).ConfigureAwait(false);

            var elapsedMilliseconds = (long)Math.Max(1, Stopwatch.GetElapsedTime(started).TotalMilliseconds);
            var isOriginalTarget = response.RequestMessage?.RequestUri == target;
            var isExpectedResponse = response.StatusCode == HttpStatusCode.NoContent;

            if (isOriginalTarget && isExpectedResponse)
            {
                // A 204 normally has no body. Keep the measured header time if cleanup itself times out.
                try
                {
                    if (response.Content is not null)
                    {
                        await response.Content.CopyToAsync(Stream.Null, attemptCancellation.Token).ConfigureAwait(false);
                    }
                }
                catch (OperationCanceledException) when (userCancellation.IsCancellationRequested)
                {
                    throw;
                }
                catch (OperationCanceledException) when (overallCancellation.IsCancellationRequested || attemptCancellation.IsCancellationRequested)
                {
                    return new RouteLatencyProbeAttempt(target, elapsedMilliseconds, RouteLatencyProbeFailure.None);
                }
                catch (IOException)
                {
                    // The valid 204 headers are the latency sample; disposing the response cancels its body.
                }

                return new RouteLatencyProbeAttempt(target, elapsedMilliseconds, RouteLatencyProbeFailure.None);
            }

            // Drain invalid responses when possible; disposing cancels the body if it does not finish in time.
            if (response.Content is not null)
            {
                await response.Content.CopyToAsync(Stream.Null, attemptCancellation.Token).ConfigureAwait(false);
            }

            return new RouteLatencyProbeAttempt(
                target,
                LatencyMilliseconds: null,
                Failure: isOriginalTarget ? RouteLatencyProbeFailure.InvalidResponse : RouteLatencyProbeFailure.UnexpectedDestination,
                StatusCode: response.StatusCode);
        }
        catch (OperationCanceledException) when (userCancellation.IsCancellationRequested)
        {
            throw;
        }
        catch (OperationCanceledException) when (overallCancellation.IsCancellationRequested)
        {
            return new RouteLatencyProbeAttempt(target, null, RouteLatencyProbeFailure.OverallTimedOut);
        }
        catch (OperationCanceledException) when (attemptCancellation.IsCancellationRequested)
        {
            return new RouteLatencyProbeAttempt(target, null, RouteLatencyProbeFailure.AttemptTimedOut);
        }
        catch (OperationCanceledException)
        {
            return new RouteLatencyProbeAttempt(target, null, RouteLatencyProbeFailure.AttemptTimedOut);
        }
        catch (HttpRequestException error)
        {
            var failure = error.HttpRequestError switch
            {
                HttpRequestError.SecureConnectionError => RouteLatencyProbeFailure.TlsFailed,
                HttpRequestError.NameResolutionError => RouteLatencyProbeFailure.DnsFailed,
                HttpRequestError.ProxyTunnelError => RouteLatencyProbeFailure.ProxyFailed,
                _ => RouteLatencyProbeFailure.RequestFailed,
            };
            return new RouteLatencyProbeAttempt(target, null, failure, error.StatusCode);
        }
        catch (IOException) { return new RouteLatencyProbeAttempt(target, null, RouteLatencyProbeFailure.RequestFailed); }
        catch (InvalidOperationException) { return new RouteLatencyProbeAttempt(target, null, RouteLatencyProbeFailure.RequestFailed); }
    }

    private static bool IsRetryable(RouteLatencyProbeAttempt attempt) => attempt.Failure switch
    {
        RouteLatencyProbeFailure.AttemptTimedOut or RouteLatencyProbeFailure.OverallTimedOut or
            RouteLatencyProbeFailure.RequestFailed or RouteLatencyProbeFailure.DnsFailed => true,
        RouteLatencyProbeFailure.InvalidResponse => attempt.StatusCode == (HttpStatusCode)429 || (int?)attempt.StatusCode >= 500,
        _ => false,
    };

    private static string DescribeFailure(IReadOnlyList<RouteLatencyProbeAttempt> attempts)
    {
        if (attempts.Any(attempt => attempt.Failure == RouteLatencyProbeFailure.ProxyFailed))
            return "Не удалось открыть локальный канал диагностики VPN.";
        if (attempts.Any(attempt => attempt.Failure == RouteLatencyProbeFailure.TlsFailed))
            return "Не удалось установить защищённое TLS-соединение через маршрут.";
        if (attempts.Any(attempt => attempt.Failure == RouteLatencyProbeFailure.UnexpectedDestination))
            return "Проверочный сервер перенаправил HTTPS-запрос; замер не выполнен.";
        if (attempts.LastOrDefault(attempt => attempt.Failure == RouteLatencyProbeFailure.InvalidResponse) is { } response)
            return $"Проверочный сервер ответил HTTP {(int)response.StatusCode!}; замер не выполнен.";
        if (attempts.Count > 0 && attempts.All(attempt => attempt.Failure is RouteLatencyProbeFailure.AttemptTimedOut or RouteLatencyProbeFailure.OverallTimedOut))
            return "Маршрут не ответил на HTTPS-проверку вовремя.";
        if (attempts.Any(attempt => attempt.Failure == RouteLatencyProbeFailure.DnsFailed))
            return "Не удалось разрешить адрес проверочного сервера.";
        return "Соединение прервалось при HTTPS-проверке маршрута.";
    }

    private enum RouteLatencyProbeFailure
    {
        None,
        AttemptTimedOut,
        OverallTimedOut,
        InvalidResponse,
        UnexpectedDestination,
        RequestFailed,
        TlsFailed,
        DnsFailed,
        ProxyFailed,
    }

    private sealed record RouteLatencyProbeAttempt(
        Uri Target,
        long? LatencyMilliseconds,
        RouteLatencyProbeFailure Failure,
        HttpStatusCode? StatusCode = null);
}

internal enum RouteLatencyProbePhase
{
    ColdConnect,
    WarmSample,
}

internal sealed record RouteLatencyProbeObservation(
    int Attempt,
    RouteLatencyProbePhase Phase,
    long? LatencyMilliseconds);

internal sealed record RouteLatencyProbeResult(
    long? LatencyMilliseconds,
    string? Failure,
    bool UsedColdFallback);

internal readonly record struct RouteLatencyProbeTimeouts(TimeSpan Overall, TimeSpan Individual)
{
    internal void Validate()
    {
        if (Overall <= TimeSpan.Zero || Overall > TimeSpan.FromSeconds(12))
            throw new ArgumentOutOfRangeException(nameof(Overall), "Overall timeout must be between 0 and 12 seconds.");

        if (Individual <= TimeSpan.Zero || Individual > TimeSpan.FromSeconds(6))
            throw new ArgumentOutOfRangeException(nameof(Individual), "Individual timeout must be between 0 and 6 seconds.");

        if (Individual > Overall)
            throw new ArgumentException("Individual timeout cannot exceed overall timeout.", nameof(Individual));
    }
}
