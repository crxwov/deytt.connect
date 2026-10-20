using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Service;

namespace DeyttConnect.Windows.Services;

/// <summary>Measures optional download speed without starting or switching a tunnel.</summary>
internal static class HomeConnectionProbe
{
    internal static async Task<WindowsRouteProbeResult> MeasureSpeedAsync(string routeTag, string? token,
        bool measureSpeed, Action<WindowsRouteProbeResult> report, CancellationToken cancellationToken)
    {
        var result = new WindowsRouteProbeResult(routeTag, null, null, null, "complete");
        if (!measureSpeed || string.IsNullOrWhiteSpace(token))
        {
            report(result);
            return result;
        }

        using var handler = new SocketsHttpHandler { UseProxy = false, AllowAutoRedirect = false };
        using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(6) };

        using var request = new HttpRequestMessage(HttpMethod.Get, "https://deytt.space/api/tg/mobile/probe/download");
        request.Headers.TryAddWithoutValidation("X-TG-App-Token", token);
        request.Headers.AcceptEncoding.ParseAdd("identity");
        request.Headers.CacheControl = new CacheControlHeaderValue { NoStore = true };
        report(result with { Stage = "waiting_speed" });
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        if (response.StatusCode != HttpStatusCode.OK)
            return result with { Error = response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden
                ? "Обновите вход для замера скорости." : "Сервер скорости временно недоступен." };

        await using var body = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));
        const long limit = 32L * 1024 * 1024;
        var expected = response.Content.Headers.ContentLength is > 0 ? Math.Min(response.Content.Headers.ContentLength.Value, limit) : limit;
        var buffer = new byte[32 * 1024];
        long received = 0;
        var started = Stopwatch.GetTimestamp();
        var lastReport = started;
        var timedOut = false;
        while (received < expected)
        {
            int count;
            try { count = await body.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, expected - received)), timeout.Token).ConfigureAwait(false); }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { timedOut = true; break; }
            if (count == 0) break;
            received += count;
            if (Stopwatch.GetElapsedTime(lastReport).TotalMilliseconds < 200) continue;
            var speed = (long)(received / Stopwatch.GetElapsedTime(started).TotalSeconds);
            report(result with { BytesPerSecond = speed, Stage = "download", BytesReceived = received, TotalBytes = expected });
            lastReport = Stopwatch.GetTimestamp();
        }
        cancellationToken.ThrowIfCancellationRequested();
        var seconds = Stopwatch.GetElapsedTime(started).TotalSeconds;
        if (seconds < .1 || received < 256 * 1024 || !timedOut && received < expected)
            return result with { Error = "Недостаточно данных для замера скорости." };
        return result with { BytesPerSecond = (long)(received / seconds), BytesReceived = received, TotalBytes = expected };
    }
}
