using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private readonly SemaphoreSlim _shellModalGate = new(1, 1);
    private static readonly TimeSpan HomePreviewCaptureTimeout = TimeSpan.FromMilliseconds(800);
    private ShellContentDialog? _activeShellContentDialog;

    private async Task<bool> ConfirmInShellAsync(
        string title,
        string message,
        string confirmLabel,
        string? cancelLabel = null)
    {
        var cancel = DeyttTheme.Action(
            DeyttTheme.TextBlock(cancelLabel ?? Copy("Отмена", "Cancel"), 14,
                DeyttTheme.Muted, FontWeight.SemiBold),
            () => { });
        cancel.Height = 44;
        cancel.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        cancel.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        AutomationProperties.SetAutomationId(cancel, "DialogCancelAction");
        AutomationProperties.SetName(cancel, cancelLabel ?? Copy("Отмена", "Cancel"));
        var confirm = DeyttTheme.PrimaryButton(confirmLabel, () => { });
        confirm.Height = 48;
        confirm.CornerRadius = new CornerRadius(14);
        if (confirm.Child is Button confirmActionButton)
        {
            AutomationProperties.SetAutomationId(confirmActionButton, "DialogConfirmAction");
            AutomationProperties.SetName(confirmActionButton, confirmLabel);
        }
        var actions = new StackPanel
        {
            Spacing = 2,
            Children = { confirm, cancel },
        };
        var content = new StackPanel
        {
            Children = { DeyttTheme.TextBlock(message, 14, DeyttTheme.Muted) },
        };

        var result = await ShowContentInShellAsync(title, content, 456, modal =>
        {
            modal.InitialFocusTarget = cancel;
            cancel.Click += (_, _) => modal.Close(false);
            if (confirm.Child is Button nestedConfirmButton)
                nestedConfirmButton.Click += (_, _) => modal.Close(true);
        }, actions);
        return result is true;
    }

    private async Task ShowInfoInShellAsync(string title, string message, string? closeLabel = null)
    {
        var closeButton = DeyttTheme.Action(
            DeyttTheme.TextBlock(closeLabel ?? Copy("Понятно", "Got it"), 14,
                DeyttTheme.Text, FontWeight.SemiBold), static () => { });
        closeButton.Height = 44;
        closeButton.MinWidth = 136;
        closeButton.Padding = new Thickness(16, 8);
        closeButton.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right;
        closeButton.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        closeButton.Background = DeyttTheme.Brush(DeyttTheme.Surface2);
        closeButton.BorderBrush = DeyttTheme.Brush(DeyttTheme.Line);
        closeButton.BorderThickness = new Thickness(1);
        closeButton.CornerRadius = new CornerRadius(14);
        AutomationProperties.SetAutomationId(closeButton, "DialogCloseAction");
        AutomationProperties.SetName(closeButton, closeLabel ?? Copy("Понятно", "Got it"));
        var content = new StackPanel
        {
            Children = { DeyttTheme.TextBlock(message, 14, DeyttTheme.Muted) },
        };

        await ShowContentInShellAsync(title, content, 456, modal =>
        {
            modal.InitialFocusTarget = closeButton;
            closeButton.Click += (_, _) => modal.Close();
        }, closeButton);
    }

    private async Task<object?> ShowContentInShellAsync(
        string title,
        Control content,
        double maxWidth = 620,
        Action<ShellContentDialog>? configure = null,
        Control? footer = null)
    {
        var modal = new ShellContentDialog();
        using var closingCancellation = new CancellationTokenSource();
        EventHandler closedHandler = (_, _) =>
        {
            closingCancellation.Cancel();
            modal.Close();
        };
        Closed += closedHandler;
        var gateAcquired = false;
        Bitmap? homePreview = null;
        Task<Bitmap?>? previewCaptureTask = null;
        Control? previousFocus = null;
        (bool Sidebar, bool BottomNavigation, bool Page, bool HeaderNavigation) previousEnabled = default;

        try
        {
            await _shellModalGate.WaitAsync(closingCancellation.Token);
            gateAcquired = true;
            if (_activeTab == MainTab.Home && _routeGlobe is not null)
            {
                try
                {
                    previewCaptureTask = _routeGlobe.CapturePreviewAsync();
                    homePreview = await previewCaptureTask.WaitAsync(HomePreviewCaptureTimeout,
                        closingCancellation.Token);
                }
                catch (TimeoutException)
                {
                    // Use the theme surface when the atlas cannot be captured quickly.
                    if (previewCaptureTask is not null)
                        _ = previewCaptureTask.ContinueWith(task => task.Result?.Dispose(),
                            CancellationToken.None, TaskContinuationOptions.OnlyOnRanToCompletion,
                            TaskScheduler.Default);
                }
                catch (OperationCanceledException) when (closingCancellation.IsCancellationRequested)
                {
                    return null;
                }
            }
            await Dispatcher.UIThread.InvokeAsync(() =>
            {
                previousFocus = FocusManager?.GetFocusedElement() as Control;
                previousEnabled = (SidebarPanel.IsEnabled, BottomNavigationPanel.IsEnabled,
                    PageScroll.IsEnabled, HeaderNavigation.IsEnabled);
                var close = DeyttTheme.Action(
                    DeyttTheme.TextBlock("×", 22, DeyttTheme.Muted, FontWeight.SemiBold),
                    () => modal.Close());
                close.Width = 38;
                close.Height = 38;
                AutomationProperties.SetAutomationId(close, "DialogCloseButton");
                AutomationProperties.SetName(close, Copy("Закрыть окно", "Close dialog"));
                var header = new Grid
                {
                    ColumnDefinitions = new ColumnDefinitions("*,Auto"),
                    ColumnSpacing = 16,
                    VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                    Children =
                    {
                        DeyttTheme.TextBlock(title, 21, DeyttTheme.Text, FontWeight.Bold),
                        close,
                    },
                };
                Grid.SetColumn(close, 1);

                var availableCardHeight = Math.Max(96, Bounds.Height - 44);
                var availableCardWidth = Bounds.Width > 0
                    ? Math.Max(320, Bounds.Width - 44)
                    : maxWidth;
                var cardMaxWidth = Math.Min(maxWidth, availableCardWidth);
                var footerHeight = 0d;
                if (footer is not null)
                {
                    footer.Measure(new Size(Math.Max(1, cardMaxWidth - 48), double.PositiveInfinity));
                    footerHeight = footer.DesiredSize.Height + 16;
                }
                var body = new ScrollViewer
                {
                    Content = content,
                    MaxHeight = Math.Max(48, availableCardHeight - 104 - footerHeight),
                    VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
                    HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
                };
                var cardContents = new StackPanel
                {
                    Spacing = 16,
                    Children = { header, body },
                };
                if (footer is not null)
                    cardContents.Children.Add(footer);
                // On short windows let the header and footer scroll with the
                // body, so every action remains reachable.
                Control dialogContents = cardContents;
                if (availableCardHeight < 360)
                {
                    body.MaxHeight = double.PositiveInfinity;
                    dialogContents = new ScrollViewer
                    {
                        Content = cardContents,
                        MaxHeight = Math.Max(48, availableCardHeight - 48),
                        VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
                        HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
                    };
                }
                var card = DeyttTheme.Card(dialogContents, DeyttTheme.Surface2, DeyttTheme.Line, 21,
                    new Thickness(24));
                card.MaxWidth = cardMaxWidth;
                card.MaxHeight = availableCardHeight;
                card.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
                card.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                card.Margin = new Thickness(22);

                var overlay = new Grid
                {
                    Background = Brushes.Transparent,
                    Focusable = true,
                };
                KeyboardNavigation.SetTabNavigation(overlay, KeyboardNavigationMode.Cycle);
                if (homePreview is not null)
                    overlay.Children.Add(new Image
                    {
                        Source = homePreview,
                        Stretch = Stretch.Fill,
                        IsHitTestVisible = false,
                    });
                var pageTone = DeyttTheme.Background;
                overlay.Children.Add(new Border
                {
                    Background = new SolidColorBrush(Color.FromArgb(208,
                        pageTone.R, pageTone.G, pageTone.B)),
                    IsHitTestVisible = false,
                });
                overlay.Children.Add(card);
                overlay.KeyDown += (_, args) =>
                {
                    if (args.Key == Key.Escape)
                    {
                        modal.Close();
                        args.Handled = true;
                    }
                };

                configure?.Invoke(modal);
                _activeShellContentDialog = modal;
                // WebView2 owns a native child window and otherwise paints over Avalonia dialogs.
                if (_routeGlobe?.Parent is Panel mapHost)
                    mapHost.Children.Remove(_routeGlobe);
                SidebarPanel.IsEnabled = false;
                BottomNavigationPanel.IsEnabled = false;
                PageScroll.IsEnabled = false;
                HeaderNavigation.IsEnabled = false;
                ModalOverlayHost.Children.Clear();
                ModalOverlayHost.Children.Add(overlay);
                ModalOverlayHost.IsVisible = true;
                _ = PlayEntranceAsync(card, closingCancellation.Token);
                Dispatcher.UIThread.Post(() => (modal.InitialFocusTarget ?? close).Focus(),
                    DispatcherPriority.Input);
            });

            return await modal.Completion;
        }
        catch (OperationCanceledException) when (closingCancellation.IsCancellationRequested)
        {
            return null;
        }
        finally
        {
            Closed -= closedHandler;
            if (gateAcquired)
            {
                await Dispatcher.UIThread.InvokeAsync(() =>
                {
                    if (ReferenceEquals(_activeShellContentDialog, modal))
                    {
                        _activeShellContentDialog = null;
                        ModalOverlayHost.Children.Clear();
                        ModalOverlayHost.IsVisible = false;
                        if (_activeTab == MainTab.Home)
                            RenderActiveTabPreservingScroll();
                        SidebarPanel.IsEnabled = previousEnabled.Sidebar;
                        BottomNavigationPanel.IsEnabled = previousEnabled.BottomNavigation;
                        PageScroll.IsEnabled = previousEnabled.Page;
                        HeaderNavigation.IsEnabled = previousEnabled.HeaderNavigation;
                        var focusTarget = previousFocus is { IsVisible: true, IsEnabled: true } &&
                                          previousFocus.IsAttachedToVisualTree()
                            ? previousFocus
                            : _compactLayout
                                ? _activeTab switch
                                {
                                    MainTab.Routes => TopRoutesNav,
                                    MainTab.Profile or MainTab.Support => TopProfileNav,
                                    MainTab.Settings => TopSettingsNav,
                                    _ => TopHomeNav,
                                }
                                : _activeTab switch
                                {
                                    MainTab.Routes => TopRoutesNav,
                                    MainTab.Profile or MainTab.Support => TopProfileNav,
                                    MainTab.Settings => TopSettingsNav,
                                    _ => TopHomeNav,
                                };
                        Dispatcher.UIThread.Post(() => focusTarget.Focus(), DispatcherPriority.Input);
                    }
                });
                homePreview?.Dispose();
                homePreview = null;
                _shellModalGate.Release();
            }
        }
    }

    private sealed class ShellContentDialog
    {
        private readonly TaskCompletionSource<object?> _completion =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public Task<object?> Completion => _completion.Task;
        public Control? InitialFocusTarget { get; set; }
        public event Action? Closed;

        public void Close(object? result = null)
        {
            if (_completion.TrySetResult(result))
                Closed?.Invoke();
        }
    }
}
