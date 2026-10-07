using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private readonly SemaphoreSlim _shellModalGate = new(1, 1);
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
        var confirm = DeyttTheme.PrimaryButton(confirmLabel, () => { });
        var actions = new StackPanel
        {
            Orientation = Avalonia.Layout.Orientation.Horizontal,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right,
            Spacing = 10,
            Children = { cancel, confirm },
        };
        var content = new StackPanel
        {
            Spacing = 16,
            Children =
            {
                DeyttTheme.TextBlock(message, 14, DeyttTheme.Muted),
                actions,
            },
        };

        var result = await ShowContentInShellAsync(title, content, 560, modal =>
        {
            cancel.Click += (_, _) => modal.Close(false);
            if (confirm.Child is Button confirmButton)
                confirmButton.Click += (_, _) => modal.Close(true);
        });
        return result is true;
    }

    private async Task ShowInfoInShellAsync(string title, string message, string? closeLabel = null)
    {
        var close = DeyttTheme.PrimaryButton(closeLabel ?? Copy("Понятно", "Got it"), () => { });
        var content = new StackPanel
        {
            Spacing = 16,
            Children =
            {
                DeyttTheme.TextBlock(message, 14, DeyttTheme.Muted),
                close,
            },
        };

        await ShowContentInShellAsync(title, content, 560, modal =>
        {
            if (close.Child is Button closeButton)
                closeButton.Click += (_, _) => modal.Close();
        });
    }

    private async Task<object?> ShowContentInShellAsync(
        string title,
        Control content,
        double maxWidth = 620,
        Action<ShellContentDialog>? configure = null)
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
        Control? previousFocus = null;
        (bool Sidebar, bool BottomNavigation, bool Page, bool Header) previousEnabled = default;

        try
        {
            await _shellModalGate.WaitAsync(closingCancellation.Token);
            gateAcquired = true;
            await Dispatcher.UIThread.InvokeAsync(() =>
            {
                previousFocus = FocusManager?.GetFocusedElement() as Control;
                previousEnabled = (SidebarPanel.IsEnabled, BottomNavigationPanel.IsEnabled,
                    PageScroll.IsEnabled, WorkspaceHeader.IsEnabled);
                var close = DeyttTheme.Action(
                    DeyttTheme.TextBlock("×", 22, DeyttTheme.Muted, FontWeight.SemiBold),
                    () => modal.Close());
                close.Width = 38;
                close.Height = 38;
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

                var availableCardHeight = Math.Max(340, Bounds.Height - 60);
                var body = new ScrollViewer
                {
                    Content = content,
                    MaxHeight = Math.Max(240, availableCardHeight - 104),
                    VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
                    HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
                };
                var card = DeyttTheme.Card(new StackPanel
                {
                    Spacing = 16,
                    Children = { header, body },
                }, DeyttTheme.Surface2, DeyttTheme.Line, 21, new Thickness(24));
                card.MaxWidth = maxWidth;
                card.MaxHeight = availableCardHeight;
                card.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
                card.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                card.Margin = new Thickness(22);

                var overlay = new Grid
                {
                    Background = new SolidColorBrush(Color.FromArgb(208, 3, 7, 12)),
                    Focusable = true,
                };
                KeyboardNavigation.SetTabNavigation(overlay, KeyboardNavigationMode.Cycle);
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
                WorkspaceHeader.IsEnabled = false;
                ModalOverlayHost.Children.Clear();
                ModalOverlayHost.Children.Add(overlay);
                ModalOverlayHost.IsVisible = true;
                Dispatcher.UIThread.Post(() => close.Focus(), DispatcherPriority.Input);
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
                        WorkspaceHeader.IsEnabled = previousEnabled.Header;
                        var focusTarget = previousFocus is { IsVisible: true, IsEnabled: true } &&
                                          previousFocus.IsAttachedToVisualTree()
                            ? previousFocus
                            : _compactLayout
                                ? _activeTab switch
                                {
                                    MainTab.Routes => BottomRoutesNav,
                                    MainTab.Profile or MainTab.Support => BottomProfileNav,
                                    MainTab.Settings => BottomSettingsNav,
                                    _ => BottomHomeNav,
                                }
                                : _activeTab switch
                                {
                                    MainTab.Routes => RoutesNav,
                                    MainTab.Profile or MainTab.Support => ProfileNav,
                                    MainTab.Settings => SettingsNav,
                                    _ => HomeNav,
                                };
                        Dispatcher.UIThread.Post(() => focusTarget.Focus(), DispatcherPriority.Input);
                    }
                });
                _shellModalGate.Release();
            }
        }
    }

    private sealed class ShellContentDialog
    {
        private readonly TaskCompletionSource<object?> _completion =
            new(TaskCreationOptions.RunContinuationsAsynchronously);

        public Task<object?> Completion => _completion.Task;
        public event Action? Closed;

        public void Close(object? result = null)
        {
            if (_completion.TrySetResult(result))
                Closed?.Invoke();
        }
    }
}
