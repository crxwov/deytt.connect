using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Threading;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Controls;

/// <summary>
/// Native Avalonia port of the mobile startup terminal typewriter splash (./c).
/// Plays a physics-based typewriter reveal with strike bounce, cursor blink,
/// settling alignment, and smooth fade-out into the main application.
/// </summary>
public sealed class StartupSplashOverlay : Control
{
    public const long SplashAlignEndMs = 1100L;
    public const long SplashExitHoldMs = 220L;
    public const long SplashExitStartMs = SplashAlignEndMs + SplashExitHoldMs; // 1320ms
    public const long SplashExitDurationMs = 340L;
    public const long TotalDurationMs = SplashExitStartMs + SplashExitDurationMs; // 1660ms

    private static readonly char[] Characters = ['.', '/', 'c'];
    private static readonly long[] RevealTimes = [140L, 320L, 500L];

    private static readonly float[][] StrikeY =
    [
        [-5f, 4f, 3f],
        [-7f, -2f, -3f],
        [-5f, 3f, 2f],
    ];

    private static readonly float[][] StrikeRotation =
    [
        [0f, -2.5f, -2f],
        [0f, 2.5f, 2f],
        [0f, -2f, -1.5f],
    ];

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

    private static double StrikeCurve(double t) => EvaluateCubicBezier(0.2, 0.0, 0.0, 1.0, t);
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

        // Backdrop: Deep space black-blue
        var bgBrush = new SolidColorBrush(Color.FromArgb((byte)(255 * exitAlpha), 8, 13, 23));
        context.FillRectangle(bgBrush, new Rect(0, 0, width, height));

        double aligned = _reduceMotion ? 1.0 : AlignCurve(Math.Clamp((elapsed - 680.0) / 420.0, 0.0, 1.0));
        double fontSize = Math.Clamp(width * 0.065, 48.0, 72.0);
        double tracking = fontSize * (-0.02 - 0.045 * aligned);

        int visibleCount = 0;
        for (int i = 0; i < RevealTimes.Length; i++)
        {
            if (elapsed >= RevealTimes[i]) visibleCount++;
        }

        var typeface = new Typeface(DeyttTheme.Unbounded, FontStyle.Normal, FontWeight.ExtraBold);
        var formattedChars = new FormattedText[Characters.Length];
        var advances = new double[Characters.Length];
        for (int i = 0; i < Characters.Length; i++)
        {
            formattedChars[i] = new FormattedText(
                Characters[i].ToString(),
                System.Globalization.CultureInfo.InvariantCulture,
                FlowDirection.LeftToRight,
                typeface,
                fontSize,
                Brushes.White);
            advances[i] = formattedChars[i].Width + tracking;
        }

        double cursorWidth = 4.0;
        double cursorMargin = 8.0;
        double terminalWidth = 0.0;
        for (int i = 0; i < visibleCount; i++)
            terminalWidth += advances[i];
        terminalWidth += cursorMargin + cursorWidth;

        double baseline = height / 2.0;
        double startX = (width - terminalWidth) / 2.0;
        double x = startX;

        for (int i = 0; i < visibleCount; i++)
        {
            double rawStrike = Math.Clamp((elapsed - RevealTimes[i]) / 100.0, 0.0, 1.0);
            int segment = rawStrike < 0.5 ? 0 : 1;
            double fraction = StrikeCurve(Math.Clamp(rawStrike * 2.0 - segment, 0.0, 1.0));

            double offsetY = _reduceMotion ? 0.0 :
                (StrikeY[i][segment] + (StrikeY[i][segment + 1] - StrikeY[i][segment]) * fraction) * (1.0 - aligned) * 1.5;
            double rotation = _reduceMotion ? 0.0 :
                (StrikeRotation[i][segment] + (StrikeRotation[i][segment + 1] - StrikeRotation[i][segment]) * fraction) * (1.0 - aligned);
            double scale = _reduceMotion ? 1.0 :
                (1.22 + (0.96 - 1.22) * fraction);

            double charHeight = formattedChars[i].Height;
            double pivotX = (advances[i] - tracking) / 2.0;
            double pivotY = 0;

            // Brand colors: "./" in Sky cyan (#38BDF8), "c" in pure white (#FFFFFF)
            var charBrush = (i < 2)
                ? new SolidColorBrush(Color.FromArgb((byte)(255 * exitAlpha), 56, 189, 248))
                : new SolidColorBrush(Color.FromArgb((byte)(255 * exitAlpha), 255, 255, 255));

            var charFormatted = new FormattedText(
                Characters[i].ToString(),
                System.Globalization.CultureInfo.InvariantCulture,
                FlowDirection.LeftToRight,
                typeface,
                fontSize,
                charBrush);

            using (context.PushTransform(
                Matrix.CreateTranslation(-pivotX, -pivotY) *
                Matrix.CreateScale(scale, scale) *
                Matrix.CreateRotation(rotation * Math.PI / 180.0) *
                Matrix.CreateTranslation(x + pivotX, baseline + offsetY)))
            {
                context.DrawText(charFormatted, new Point(0, -charHeight / 2.0));
            }

            x += advances[i];
        }

        // Terminal block cursor: blinking cursor in hold state
        double cursorAlpha = elapsed < 680.0 ? 1.0 :
            ((elapsed / 450) % 2 == 0 ? 0.85 : 0.15);
        if (cursorAlpha > 0.001 && exitAlpha > 0.001)
        {
            var cursorBrush = new SolidColorBrush(Color.FromArgb((byte)(255 * cursorAlpha * exitAlpha), 56, 189, 248));
            double cursorTop = baseline - fontSize * 0.36;
            double cursorHeight = fontSize * 0.72;
            var cursorRect = new Rect(x + cursorMargin, cursorTop, cursorWidth, cursorHeight);
            context.DrawRectangle(cursorBrush, null, cursorRect, 2.0, 2.0);
        }
    }
}
