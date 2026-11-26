using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Styling;
using Avalonia.Media.Transformation;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private CancellationTokenSource? _pageEntranceCancellation;

    private async Task PlayEntranceAsync(Control target, CancellationToken cancellationToken = default)
    {
        _pageEntranceCancellation?.Cancel();
        using var entrance = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _pageEntranceCancellation = entrance;
        var animation = new Animation
        {
            Duration = TimeSpan.FromMilliseconds(_reduceMotion ? 100 : 380),
            Easing = new CubicEaseOut(),
            Children =
            {
                new KeyFrame { Cue = new Cue(0), Setters = { new Setter(OpacityProperty, 0.08d) } },
                new KeyFrame { Cue = new Cue(1), Setters = { new Setter(OpacityProperty, 1d) } },
            },
        };
        if (!_reduceMotion)
        {
            animation.Children[0].Setters.Add(new Setter(RenderTransformProperty, TransformOperations.Parse("translate(0px, 18px) scale(0.99)")));
            animation.Children[1].Setters.Add(new Setter(RenderTransformProperty, TransformOperations.Parse("translate(0px, 0px) scale(1)")));
        }
        try { await animation.RunAsync(target, entrance.Token); }
        catch (OperationCanceledException) when (entrance.IsCancellationRequested) { }
        finally
        {
            if (ReferenceEquals(_pageEntranceCancellation, entrance))
                _pageEntranceCancellation = null;
        }
    }
}
