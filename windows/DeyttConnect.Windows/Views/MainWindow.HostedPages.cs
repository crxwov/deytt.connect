using System.Security.Cryptography;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private SetupWindow? _activeSetupView;
    private Bitmap? _setupPagePreview;
    private Grid? _setupBackdropPage;
    private SupportView? _supportView;
    private TaskCompletionSource<SetupWindowResult?>? _setupFlowCompletion;
    private MainTab _setupReturnTab = MainTab.Home;
    private MainTab _supportReturnTab = MainTab.Profile;
    private bool _hostedViewEventsRegistered;
    private bool _allowCloseAfterSupportPrompt;
    private bool _supportClosePromptPending;

    private Control BuildSetupPage()
    {
        if (_activeSetupView is { } setupView)
        {
            if (_setupPagePreview is null) return setupView;
            if (_setupBackdropPage?.Children.Contains(setupView) == true) return _setupBackdropPage;
            return _setupBackdropPage = new Grid
            {
                ClipToBounds = true,
                Children =
                {
                    new Image { Source = _setupPagePreview, Stretch = Stretch.UniformToFill, Margin = new Avalonia.Thickness(-24),
                        Effect = new BlurEffect { Radius = 16 }, IsHitTestVisible = false },
                    setupView,
                },
            };
        }

        return DeyttTheme.TextBlock(Copy("Мастер настройки недоступен.", "Setup is unavailable."),
            14, DeyttTheme.Muted);
    }

    private Control BuildSupportPage()
    {
        EnsureHostedViewLifecycle();
        _supportView ??= CreateSupportView();
        return _supportView;
    }

    private SupportView CreateSupportView()
    {
        var view = new SupportView(_sessionToken, RequestSupportSignInAsync,
            (title, message, label) => ConfirmInShellAsync(title, message, label), _language);
        view.BackRequested += (_, _) => ShowTab(_supportReturnTab);
        return view;
    }

    private async Task<string?> RequestSupportSignInAsync()
    {
        await ShowSetupWindowAsync();

        if (_sessionToken is null && OperatingSystem.IsWindows())
        {
            try
            {
                _sessionToken = WindowsSessionStore.Load();
            }
            catch (Exception error) when (error is CryptographicException or IOException or UnauthorizedAccessException)
            {
                _sessionToken = null;
            }

            UpdateShellStatus();
        }

        return _sessionToken;
    }

    private void EnsureHostedViewLifecycle()
    {
        if (_hostedViewEventsRegistered)
            return;

        _hostedViewEventsRegistered = true;
        Closing += OnClosingWithSupportDraft;
        Closed += (_, _) =>
        {
            _activeSetupView?.Cancel();
            _supportView?.Dispose();
        };
    }

    private async void OnClosingWithSupportDraft(object? sender, WindowClosingEventArgs args)
    {
        if (_allowCloseAfterSupportPrompt || _supportView is null)
            return;

        args.Cancel = true;
        if (_supportClosePromptPending)
            return;

        _supportClosePromptPending = true;
        try
        {
            if (await _supportView.ConfirmDiscardDraftAsync())
            {
                _allowCloseAfterSupportPrompt = true;
                Close();
            }
        }
        finally
        {
            _supportClosePromptPending = false;
        }
    }
}
