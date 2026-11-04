using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using DeyttConnect.Windows.Controls;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private void ConfigureNavigation()
    {
        HomeNav.Click += (_, _) => RequestNavigation(MainTab.Home);
        RoutesNav.Click += (_, _) => RequestNavigation(MainTab.Routes);
        ProfileNav.Click += (_, _) => RequestNavigation(MainTab.Profile);
        SettingsNav.Click += (_, _) => RequestNavigation(MainTab.Settings);
        BottomHomeNav.Click += (_, _) => RequestNavigation(MainTab.Home);
        BottomRoutesNav.Click += (_, _) => RequestNavigation(MainTab.Routes);
        BottomProfileNav.Click += (_, _) => RequestNavigation(MainTab.Profile);
        BottomSettingsNav.Click += (_, _) => RequestNavigation(MainTab.Settings);
    }

    private void RequestNavigation(MainTab destination)
    {
        if (_activeTab == MainTab.Setup)
        {
            _activeSetupView?.Cancel();
            return;
        }

        ShowTab(destination);
    }

    private void ShowTab(MainTab tab)
    {
        var previousTab = _activeTab;
        if (previousTab == MainTab.Routes && tab != MainTab.Routes)
            SuspendRouteCountryProbes();
        if (previousTab == MainTab.Support && tab != MainTab.Support)
        {
            _supportView?.Deactivate();
            _supportViewActivated = false;
        }

        _activeTab = tab;
        _profileAvatarImage = null;
        PageHost.Children.Clear();
        PageHost.Children.Add(tab switch
        {
            MainTab.Home => BuildHomePage(),
            MainTab.Routes => BuildRoutesPage(),
            MainTab.Profile => BuildProfilePage(),
            MainTab.Settings => BuildSettingsPage(),
            MainTab.Support => BuildSupportPage(),
            MainTab.Setup => BuildSetupPage(),
            _ => BuildHomePage(),
        });
        SetNavigation(HomeNav, NavigationIconKind.Home, Copy("Главная", "Home"), tab == MainTab.Home);
        SetNavigation(RoutesNav, NavigationIconKind.Routes, Copy("Маршруты", "Routes"), tab == MainTab.Routes);
        SetNavigation(ProfileNav, NavigationIconKind.Profile, Copy("Профиль", "Profile"), tab is MainTab.Profile or MainTab.Support);
        SetNavigation(SettingsNav, NavigationIconKind.Settings, Copy("Настройки", "Settings"), tab == MainTab.Settings);
        UpdateBottomNavigation();
        UpdateShellStatus();
        if (tab == MainTab.Support && _supportView is not null &&
            (!_supportViewActivated || !string.Equals(_supportActivatedSessionToken, _sessionToken,
                StringComparison.Ordinal)))
        {
            _supportView?.Activate(_sessionToken);
            _supportViewActivated = true;
            _supportActivatedSessionToken = _sessionToken;
        }
        UpdateResponsiveLayout();
        PageScroll.Offset = new Vector(0, 0);
    }

    private void SetNavigation(Button button, NavigationIconKind icon, string label, bool selected)
    {
        ToolTip.SetTip(button, label);
        Avalonia.Automation.AutomationProperties.SetName(button, label);
        var content = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions(_compactLayout ? "*" : "Auto,*"),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
        };
        var glyph = new NavigationIcon(icon, selected);
        glyph.HorizontalAlignment = _compactLayout
            ? Avalonia.Layout.HorizontalAlignment.Center
            : Avalonia.Layout.HorizontalAlignment.Left;
        glyph.Margin = new Thickness(0, 0, _compactLayout ? 0 : 12, 0);
        content.Children.Add(glyph);
        if (!_compactLayout)
        {
            var title = DeyttTheme.TextBlock(label, 14,
                selected ? DeyttTheme.Text : DeyttTheme.Muted,
                selected ? FontWeight.SemiBold : FontWeight.Medium, wrap: false);
            title.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(title, 1);
            content.Children.Add(title);
        }

        button.MinWidth = _compactLayout ? 52 : 0;
        button.MinHeight = _compactLayout ? 52 : 0;
        var navigationSurface = new Border
        {
            Background = DeyttTheme.Brush(selected ? DeyttTheme.Selected : Colors.Transparent),
            BorderBrush = DeyttTheme.Brush(selected ? DeyttTheme.SelectedLine : Colors.Transparent),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12),
            Padding = _compactLayout ? new Thickness(0, 8) : new Thickness(13, 11),
            Child = content,
        };
        navigationSurface.Classes.Add("nav-surface");
        button.Content = navigationSurface;
    }

    private void UpdateBottomNavigation()
    {
        SetBottomNavigation(BottomHomeNav, NavigationIconKind.Home, Copy("Главная", "Home"), _activeTab == MainTab.Home);
        SetBottomNavigation(BottomRoutesNav, NavigationIconKind.Routes, Copy("Маршруты", "Routes"), _activeTab == MainTab.Routes);
        SetBottomNavigation(BottomProfileNav, NavigationIconKind.Profile, Copy("Профиль", "Profile"),
            _activeTab is MainTab.Profile or MainTab.Support);
        SetBottomNavigation(BottomSettingsNav, NavigationIconKind.Settings, Copy("Настройки", "Settings"),
            _activeTab == MainTab.Settings);
    }

    private static void SetBottomNavigation(Button button, NavigationIconKind icon, string label, bool selected)
    {
        button.CornerRadius = new CornerRadius(15);
        ToolTip.SetTip(button, label);
        var content = new StackPanel
        {
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            Spacing = 2,
        };
        var glyph = new NavigationIcon(icon, selected);
        content.Children.Add(glyph);
        var title = DeyttTheme.TextBlock(label, 11,
            selected ? DeyttTheme.Text : DeyttTheme.Muted,
            selected ? FontWeight.SemiBold : FontWeight.Medium, wrap: false);
        title.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        content.Children.Add(title);
        button.MinHeight = 58;
        Avalonia.Automation.AutomationProperties.SetName(button, label);
        ToolTip.SetTip(button, label);
        var navigationSurface = new Border
        {
            Background = DeyttTheme.Brush(selected ? DeyttTheme.Selected : Colors.Transparent),
            BorderBrush = DeyttTheme.Brush(selected ? DeyttTheme.SelectedLine : Colors.Transparent),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(15),
            Padding = new Thickness(8, 4),
            Child = content,
        };
        navigationSurface.Classes.Add("nav-surface");
        button.Content = navigationSurface;
    }
}
