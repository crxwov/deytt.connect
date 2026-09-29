using System.Net;
using System.Net.Sockets;

namespace DeyttConnect.Windows.Controls;

/// <summary>Serves only the bundled atlas files to Linux WebKit from one local origin.</summary>
internal sealed class AtlasLoopbackAssetHost : IDisposable
{
    private static readonly IReadOnlyDictionary<string, string> AssetTypes = new Dictionary<string, string>(StringComparer.Ordinal)
    {
        ["/index.html"] = "text/html; charset=utf-8",
        ["/atlas-init.js"] = "application/javascript; charset=utf-8",
        ["/network-atlas.js"] = "application/javascript; charset=utf-8",
        ["/network-atlas.css"] = "text/css; charset=utf-8",
        ["/world-land.json"] = "application/json; charset=utf-8",
    };

    private readonly HttpListener _listener;
    private readonly string _assetDirectory;

    private AtlasLoopbackAssetHost(HttpListener listener, string assetDirectory, Uri atlasUri)
    {
        _listener = listener;
        _assetDirectory = assetDirectory;
        AtlasUri = atlasUri;
        _ = ServeAsync();
    }

    public Uri AtlasUri { get; }

    public static AtlasLoopbackAssetHost Start(string assetDirectory)
    {
        for (var attempt = 0; attempt < 8; attempt++)
        {
            var probe = new TcpListener(IPAddress.Loopback, 0);
            probe.Start();
            var port = ((IPEndPoint)probe.LocalEndpoint).Port;
            probe.Stop();

            var listener = new HttpListener();
            var origin = $"http://127.0.0.1:{port}/";
            listener.Prefixes.Add(origin);
            try
            {
                listener.Start();
                return new AtlasLoopbackAssetHost(listener, assetDirectory, new Uri(origin + "index.html"));
            }
            catch (HttpListenerException)
            {
                listener.Close();
                if (attempt == 7)
                    throw;
            }
        }

        throw new InvalidOperationException("Unable to bind the local atlas asset host.");
    }

    private async Task ServeAsync()
    {
        while (_listener.IsListening)
        {
            HttpListenerContext context;
            try
            {
                context = await _listener.GetContextAsync();
            }
            catch (HttpListenerException) when (!_listener.IsListening)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }

            _ = RespondAsync(context);
        }
    }

    private async Task RespondAsync(HttpListenerContext context)
    {
        var response = context.Response;
        try
        {
            var path = context.Request.Url?.AbsolutePath;
            if (context.Request.HttpMethod is not ("GET" or "HEAD") ||
                path is null || !AssetTypes.TryGetValue(path, out var contentType))
            {
                response.StatusCode = 404;
                return;
            }

            var fileName = path[1..];
            var filePath = Path.Combine(_assetDirectory, fileName);
            if (!File.Exists(filePath))
            {
                response.StatusCode = 404;
                return;
            }

            response.ContentType = contentType;
            response.Headers["Cache-Control"] = "no-store";
            response.Headers["X-Content-Type-Options"] = "nosniff";
            await using var file = File.OpenRead(filePath);
            response.ContentLength64 = file.Length;
            if (context.Request.HttpMethod == "GET")
                await file.CopyToAsync(response.OutputStream);
        }
        catch (Exception exception) when (exception is HttpListenerException or IOException or ObjectDisposedException)
        {
            // The browser may close its request while the control is being detached.
        }
        finally
        {
            try { response.Close(); } catch (Exception exception) when (exception is ObjectDisposedException or HttpListenerException) { }
        }
    }

    public void Dispose() => _listener.Close();
}
