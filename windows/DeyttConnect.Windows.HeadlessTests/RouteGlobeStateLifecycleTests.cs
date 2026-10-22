using System.Reflection;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Avalonia.Threading;
using DeyttConnect.Windows.Controls;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class RouteGlobeStateLifecycleTests
{
    [AvaloniaFact]
    public void Profile_quotas_replay_independently_and_clear_on_signout()
    {
        var (window, globe) = CreateReadyHost();
        try
        {
            var mainQuota = new { state = "ready", usedBytes = 25L, limitBytes = 100L, expiresAt = "2026-11-11T00:00:00Z" };
            var awgQuota = new { state = "ready", usedBytes = 75L, limitBytes = 200L, expiresAt = "2026-10-29T00:00:00Z" };
            globe.UpdateProfileData(true, "qa", "qa", 1, true, "ultra", null, null, null, [], [], null, mainQuota, awgQuota);
            using var profile = JsonDocument.Parse(Field<string>(globe, "_profileDataJson"));
            Assert.Equal(25, profile.RootElement.GetProperty("mainQuota").GetProperty("usedBytes").GetInt64());
            Assert.Equal(75, profile.RootElement.GetProperty("awgQuota").GetProperty("usedBytes").GetInt64());
            Assert.NotEqual(profile.RootElement.GetProperty("mainQuota").GetProperty("expiresAt").GetString(),
                profile.RootElement.GetProperty("awgQuota").GetProperty("expiresAt").GetString());
            var script = (string)typeof(RouteGlobeWebView).GetMethod("BuildStateScript", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(globe, null)!;
            Assert.Contains("\"usedBytes\":25", script);
            Assert.Contains("\"usedBytes\":75", script);

            globe.UpdateProfileData(false, null, null, null, false, null, null, null, null, [], [], null,
                new { state = "absent" }, new { state = "absent" });
            using var signedOut = JsonDocument.Parse(Field<string>(globe, "_profileDataJson"));
            Assert.False(signedOut.RootElement.GetProperty("isSignedIn").GetBoolean());
            Assert.False(signedOut.RootElement.GetProperty("awgQuota").TryGetProperty("usedBytes", out _));
        }
        finally { window.Close(); }
    }

    [AvaloniaFact]
    public async Task Returning_home_bypasses_a_response_stalled_while_hidden()
    {
        var (window, globe) = CreateReadyHost();
        var withheld = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var scripts = new List<string>();
        globe.StateScriptExecutor = script =>
        {
            scripts.Add(script);
            return scripts.Count == 1 ? withheld.Task : Task.CompletedTask;
        };
        try
        {
            globe.HostActive = false;
            Dispatcher.UIThread.RunJobs();
            Assert.True(Field<bool>(globe, "_statePushQueued"));

            globe.SelectedRoute = "de";
            globe.ResumeHost();
            await DrainAsync();

            Assert.True(globe.HostActive);
            Assert.Contains("\"de\"", scripts.Last());
            Assert.Contains("deyttResumeMap", scripts.Last());
            Assert.Equal(Field<int>(globe, "_stateRevision"), Field<int>(globe, "_appliedRevision"));
            Assert.False(Field<bool>(globe, "_statePushQueued"));
        }
        finally { window.Close(); withheld.TrySetResult(); }
    }

    [AvaloniaFact]
    public async Task Old_generation_completion_cannot_release_the_replacement_queue()
    {
        var (window, globe) = CreateReadyHost();
        var oldResponse = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var newResponse = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var calls = 0;
        globe.StateScriptExecutor = _ => ++calls == 1 ? oldResponse.Task : newResponse.Task;
        try
        {
            globe.ResumeHost();
            Dispatcher.UIThread.RunJobs();
            ReplaceBackend(globe);
            globe.SelectedRoute = "it";
            globe.ResumeHost();
            await DrainAsync();
            Assert.Equal(2, calls);

            oldResponse.SetResult();
            await DrainAsync();
            Assert.True(Field<bool>(globe, "_statePushQueued"));
            Assert.NotEqual(Field<int>(globe, "_stateRevision"), Field<int>(globe, "_appliedRevision"));

            newResponse.SetResult();
            await DrainAsync();
            Assert.False(Field<bool>(globe, "_statePushQueued"));
            Assert.Equal(Field<int>(globe, "_stateRevision"), Field<int>(globe, "_appliedRevision"));
        }
        finally { window.Close(); oldResponse.TrySetResult(); newResponse.TrySetResult(); }
    }

    [AvaloniaFact]
    public async Task Late_failure_from_a_detached_backend_cannot_fail_the_current_page()
    {
        var (window, globe) = CreateReadyHost();
        var oldResponse = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var calls = 0;
        globe.StateScriptExecutor = _ => ++calls == 1 ? oldResponse.Task : Task.CompletedTask;
        try
        {
            globe.ResumeHost();
            Dispatcher.UIThread.RunJobs();
            ReplaceBackend(globe);
            globe.ResumeHost();
            await DrainAsync();
            oldResponse.SetException(new IOException("synthetic destroyed adapter response"));
            await DrainAsync();

            Assert.False(Field<bool>(globe, "_atlasFailed"));
            Assert.False(Field<bool>(globe, "_retryPending"));
            Assert.Equal(0, Field<int>(globe, "_automaticRetries"));
            Assert.Equal(Field<int>(globe, "_stateRevision"), Field<int>(globe, "_appliedRevision"));
            _ = oldResponse.Task.Exception;
        }
        finally { window.Close(); oldResponse.TrySetResult(); }
    }

    [AvaloniaFact]
    public async Task Profile_bridge_dispatches_renew_and_preserves_session_identifier_case()
    {
        var (window, globe) = CreateReadyHost();
        string? sessionId = null;
        var renewRequests = 0;
        globe.TelegramSessionManagementRequested += value => sessionId = value;
        globe.RenewRequested += () => renewRequests++;
        var webView = Field<NativeWebView>(globe, "_webView");
        var onMessage = typeof(RouteGlobeWebView).GetMethod("OnWebMessageReceived",
            BindingFlags.Instance | BindingFlags.NonPublic)!;
        try
        {
            onMessage.Invoke(globe, [webView, new WebMessageReceivedEventArgs
                { Body = "\"profile:session:manage:AbC-09xY\"" }]);
            onMessage.Invoke(globe, [webView, new WebMessageReceivedEventArgs
                { Body = "\"profile:renew\"" }]);
            await DrainAsync();

            Assert.Equal("AbC-09xY", sessionId);
            Assert.Equal(1, renewRequests);
        }
        finally { window.Close(); }
    }

    [AvaloniaFact]
    public async Task Webview_window_controls_reach_the_native_window()
    {
        var (window, globe) = CreateReadyHost();
        var webView = Field<NativeWebView>(globe, "_webView");
        var onMessage = typeof(RouteGlobeWebView).GetMethod("OnWebMessageReceived",
            BindingFlags.Instance | BindingFlags.NonPublic)!;
        var closeRequests = 0;
        globe.ExitRequested += () => closeRequests++;
        try
        {
            onMessage.Invoke(globe, [webView, new WebMessageReceivedEventArgs { Body = "\"window:minimize\"" }]);
            await DrainAsync();
            Assert.Equal(WindowState.Minimized, window.WindowState);

            onMessage.Invoke(globe, [webView, new WebMessageReceivedEventArgs { Body = "\"window:maximize\"" }]);
            await DrainAsync();
            Assert.Equal(WindowState.Maximized, window.WindowState);
            onMessage.Invoke(globe, [webView, new WebMessageReceivedEventArgs { Body = "\"window:maximize\"" }]);
            await DrainAsync();
            Assert.Equal(WindowState.Normal, window.WindowState);

            onMessage.Invoke(globe, [webView, new WebMessageReceivedEventArgs { Body = "\"window:close\"" }]);
            await DrainAsync();
            Assert.Equal(1, closeRequests);
        }
        finally { window.Close(); }
    }

    private static (Window, RouteGlobeWebView) CreateReadyHost()
    {
        var globe = new RouteGlobeWebView(disableNativeInitialization: true);
        var window = new Window { Width = 800, Height = 600, Content = globe };
        window.Show();
        Dispatcher.UIThread.RunJobs();
        SetField(globe, "_webView", new NativeWebView());
        SetField(globe, "_pageReady", true);
        return (window, globe);
    }

    private static void ReplaceBackend(RouteGlobeWebView globe)
    {
        typeof(RouteGlobeWebView).GetMethod("DetachCurrentWebView", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(globe, null);
        SetField(globe, "_webView", new NativeWebView());
        SetField(globe, "_webViewGeneration", Field<int>(globe, "_webViewGeneration") + 1);
        SetField(globe, "_pageReady", true);
    }

    private static T Field<T>(RouteGlobeWebView globe, string name) =>
        (T)typeof(RouteGlobeWebView).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(globe)!;

    private static void SetField(RouteGlobeWebView globe, string name, object value) =>
        typeof(RouteGlobeWebView).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(globe, value);

    private static async Task DrainAsync()
    {
        Dispatcher.UIThread.RunJobs();
        await Task.Delay(20);
        Dispatcher.UIThread.RunJobs();
    }
}
