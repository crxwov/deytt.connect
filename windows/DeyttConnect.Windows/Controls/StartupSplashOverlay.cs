using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Threading;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Controls;

/// <summary>Theme-aware startup wordmark with a short reveal.</summary>
public sealed class StartupSplashOverlay : Control
{
    public const long SplashAlignEndMs = 1100L;
    public const long SplashExitHoldMs = 220L;
    public const long SplashExitStartMs = SplashAlignEndMs + SplashExitHoldMs; // 1320ms
    public const long SplashExitDurationMs = 340L;
    public const long TotalDurationMs = SplashExitStartMs + SplashExitDurationMs; // 1660ms

    private readonly Stopwatch _stopwatch = new();
    private readonly DispatcherTimer _timer;
    private readonly TaskCompletionSource _completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private bool _reduceMotion;
    private bool _finished;
    private bool _dismissRequested;
    private long _dismissStartMs;

    public StartupSplashOverlay()
    {
        ZIndex = 5000;
        IsHitTestVisible = true;
        HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        VerticalAlignment = Avalonia.Layout.VerticalAlignment.Stretch;

        _timer = new DispatcherTimer(DispatcherPriority.Render)
        {
            Interval = TimeSpan.FromMilliseconds(16),
        };
        _timer.Tick += OnTimerTick;
    }

    private void OnTimerTick(object? sender, EventArgs e)
    {
        if (_finished) return;

        long elapsed = _stopwatch.ElapsedMilliseconds;
        if (_dismissRequested)
        {
            if (elapsed - _dismissStartMs >= SplashExitDurationMs)
            {
                Finish();
                return;
            }
        }
        else if (elapsed >= 6000L)
        {
            RequestDismiss();
        }

        InvalidateVisual();
    }

    public void RequestDismiss()
    {
        if (_finished || _dismissRequested) return;
        _dismissRequested = true;
        _dismissStartMs = _stopwatch.ElapsedMilliseconds;
    }

    public Task WaitForCompletionAsync() => _completion.Task;

    public void Start(bool reduceMotion = false)
    {
        _reduceMotion = reduceMotion;
        if (_finished) return;
        if (_stopwatch.IsRunning) return;
        IsVisible = true;
        IsHitTestVisible = true;
        _stopwatch.Restart();
        _timer.Start();
        InvalidateVisual();
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        if (!_stopwatch.IsRunning && !_finished)
        {
            Start(_reduceMotion);
        }
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        _timer.Stop();
        _stopwatch.Stop();
        _completion.TrySetResult();
    }

    public void Finish()
    {
        if (_finished) return;
        _finished = true;
        _timer.Stop();
        _stopwatch.Stop();

        Dispatcher.UIThread.Post(() =>
        {
            IsVisible = false;
            IsHitTestVisible = false;
            _completion.TrySetResult();
        }, DispatcherPriority.Normal);
    }

    private static double EvaluateCubicBezier(double x1, double y1, double x2, double y2, double t)
    {
        t = Math.Clamp(t, 0.0, 1.0);
        double u = t;
        for (int i = 0; i < 5; i++)
        {
            double currentX = 3 * (1 - u) * (1 - u) * u * x1 + 3 * (1 - u) * u * u * x2 + u * u * u;
            double slopeX = 3 * (1 - u) * (1 - u) * x1 + 6 * (1 - u) * u * (x2 - x1) + 3 * u * u * (1 - x2);
            if (Math.Abs(slopeX) < 1e-6) break;
            u -= (currentX - t) / slopeX;
            u = Math.Clamp(u, 0.0, 1.0);
        }
        return 3 * (1 - u) * (1 - u) * u * y1 + 3 * (1 - u) * u * u * y2 + u * u * u;
    }

    private static double AlignCurve(double t) => EvaluateCubicBezier(0.16, 1.0, 0.3, 1.0, t);

    public override void Render(DrawingContext context)
    {
        base.Render(context);
        if (_finished) return;

        if (!_stopwatch.IsRunning)
        {
            _stopwatch.Start();
        }

        long elapsed = _stopwatch.ElapsedMilliseconds;
        if (elapsed >= TotalDurationMs)
        {
            // Do not mutate visual state during render pass; OnTimerTick handles Finish
            return;
        }

        double width = Bounds.Width;
        double height = Bounds.Height;
        if (width <= 0 || height <= 0) return;

        double exitAlpha = !_dismissRequested ? 1.0 :
            Math.Clamp(1.0 - AlignCurve((elapsed - _dismissStartMs) / (double)SplashExitDurationMs), 0.0, 1.0);

        var alpha = (byte)(255 * exitAlpha);
        var background = DeyttTheme.Background;
        var foreground = DeyttTheme.Text;
        context.FillRectangle(new SolidColorBrush(Color.FromArgb(alpha, background.R, background.G, background.B)),
            new Rect(0, 0, width, height));

        var reveal = _reduceMotion ? 1.0 : AlignCurve(Math.Clamp(elapsed / 300.0, 0.0, 1.0));
        var textAlpha = (byte)(alpha * reveal);
        var typeface = new Typeface(DeyttTheme.Unbounded, FontStyle.Normal, FontWeight.ExtraBold);
        var fontSize = Math.Clamp(width * .048, 26, 58);
        FormattedText Word(string value, Color color) => new(value, System.Globalization.CultureInfo.InvariantCulture,
            FlowDirection.LeftToRight, typeface, fontSize,
            new SolidColorBrush(Color.FromArgb(textAlpha, color.R, color.G, color.B)));

        var mark = Word("./", DeyttTheme.IsDark ? Color.Parse("#65CCEE") : Color.Parse("#096683"));
        var name = Word("connect", foreground);
        var x = (width - mark.Width - name.Width) * .5;
        var y = (height - name.Height) * .5 + (1 - reveal) * 5;
        context.DrawText(mark, new Point(x, y));
        context.DrawText(name, new Point(x + mark.Width, y));
    }
}
