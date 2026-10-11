using System.Reflection;
using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Avalonia.Threading;
using DeyttConnect.Windows.Controls;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class RouteGlobeStateLifecycleTests
{
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
