using Avalonia;
using Avalonia.Controls;
using Avalonia.Automation;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Protocol;
using DeyttConnect.Windows;
using DeyttConnect.Windows.Views;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Controls;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class HomeWindowTests
{
    [AvaloniaTheory]
    [InlineData("disconnected", "Не подключено", "Не подключено", "Подключиться")]
    [InlineData("connecting", "Подключаемся…", "Подключаем VPN…", "Отменить подключение")]
    [InlineData("error", "Ошибка VPN", "Ошибка VPN", "Подключиться")]
    [InlineData("connected", "VPN подключён", "Подключено", "Отключить")]
    public void Vpn_fixture_renders_truthful_state_across_header_rail_home_and_action(
        string state, string expectedHeader, string expectedHome, string expectedAction)
    {
        var window = new MainWindow(Fixture(signedIn: true, state: state)) { Width = 1360, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            Assert.Equal(expectedHeader, Required<TextBlock>(window, "HeaderStatusText").Text);
            Assert.Equal(expectedHeader, Required<TextBlock>(window, "SidebarVpnText").Text);
            var pageText = Descendants(Required<Grid>(window, "PageHost")).OfType<TextBlock>()
                .Select(text => text.Text).ToArray();
            Assert.Contains(expectedHome, pageText);
            Assert.Contains(expectedAction, pageText);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d, true)]
    [InlineData(1360d, false)]
    public void Home_navigation_and_controls_remain_reachable_at_supported_widths(double width, bool compact)
    {
        var window = new MainWindow(Fixture(tab: "settings")) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var sidebar = Required<Control>(window, "SidebarPanel");
            var bottomNavigation = Required<Control>(window, "BottomNavigationPanel");
            Assert.Equal(!compact, sidebar.IsVisible);
            Assert.Equal(compact, bottomNavigation.IsVisible);

            var homeButton = Required<Button>(window, compact ? "BottomHomeNav" : "HomeNav");
            Assert.True(homeButton.IsEffectivelyVisible);
            Assert.True(homeButton.Focus());
            Assert.True(homeButton.IsFocused);

            window.KeyReleaseQwerty(PhysicalKey.Space, RawInputModifiers.None);
            Dispatcher.UIThread.RunJobs();

            Assert.Equal("Ваше подключение", Required<TextBlock>(window, "WorkspaceTitle").Text);
            var pageHost = Required<Grid>(window, "PageHost");
            Assert.Single(pageHost.Children);
            Assert.Contains(Descendants(pageHost).OfType<Button>(), button => button.IsEffectivelyVisible && button.IsEnabled);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Home_connection_action_keeps_a_usable_hit_target(double width)
    {
        var window = new MainWindow(Fixture()) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var action = Assert.Single(Descendants(Required<Grid>(window, "PageHost"))
                .OfType<Button>(), button => button.Content is TextBlock text && text.Text == "Войти через Telegram");
            var hitTarget = Assert.IsType<Border>(action.GetVisualParent());

            Assert.True(action.IsEffectivelyVisible);
            Assert.True(action.IsEnabled);
            Assert.True(hitTarget.Bounds.Width >= 320);
            Assert.InRange(hitTarget.Height, 62, 68);
            Assert.True(action.Focus());
            Assert.True(action.IsFocused);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Home_route_globe_is_present_and_sized_for_the_route(double width)
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var controls = Descendants(Required<Grid>(window, "PageHost")).ToArray();
            var map = Assert.Single(controls.OfType<RouteGlobeWebView>());
            Assert.True(map.Bounds.Width > 300);
            Assert.False(string.IsNullOrWhiteSpace(map.SelectedRoute));
            Assert.NotEmpty(map.AvailableLocations);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Android_atlas_route_keys_support_single_and_double_hops()
    {
        Assert.Equal("nl", RouteGlobeWebView.RouteKeyFor("NL_VLESS"));
        Assert.Equal("de", RouteGlobeWebView.RouteKeyFor("DE_HYSTERIA2"));
        Assert.Equal("ru-de", RouteGlobeWebView.RouteKeyFor("RU-DE_TROJAN"));
        Assert.Equal("auto", RouteGlobeWebView.RouteKeyFor("unknown"));
    }

    [AvaloniaFact]
    public void Route_map_labels_source_only_when_location_consent_is_enabled()
    {
        var map = new RouteGlobeWebView();
        var syntheticOrigin = new WindowsNetworkLocation(54.735, 55.958, "Уфа", "Башкортостан", "RU");

        map.OriginLocation = syntheticOrigin;
        Assert.Null(map.OriginLocation);

        map.OriginConsentGranted = true;
        map.OriginLocation = syntheticOrigin;
        Assert.Equal(syntheticOrigin, map.OriginLocation);

        map.OriginConsentGranted = false;
        Assert.Null(map.OriginLocation);
    }

    [AvaloniaFact]
    public void Short_compact_home_keeps_globe_and_route_actions_above_bottom_navigation()
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = 900, Height = 650 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var page = Descendants(Required<Grid>(window, "PageHost")).ToArray();
            var map = Assert.Single(page.OfType<RouteGlobeWebView>());
            Assert.True(map.Bounds.Height > 450);
            var overview = Assert.IsType<Grid>(Assert.Single(Required<Grid>(window, "PageHost").Children));
            Assert.Equal(2, overview.ColumnDefinitions.Count);
            Assert.InRange(Math.Abs(overview.Children[0].Bounds.Width - overview.Children[1].Bounds.Width), 0, 32);
            Assert.Contains(page.OfType<TextBlock>(), text => text.Text == "ПРОТОКОЛ");
            Assert.DoesNotContain(page.OfType<TextBlock>(), text => text.Text == "QA Demo");
            Assert.True(Required<Control>(window, "BottomNavigationPanel").IsVisible);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Wide_home_splits_map_and_connection_panel_evenly()
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = 900, Height = 830 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var host = Required<Grid>(window, "PageHost");
            var layout = Assert.IsType<Grid>(Assert.Single(host.Children));
            Assert.Equal(2, layout.ColumnDefinitions.Count);
            Assert.Equal(2, layout.Children.Count);
            var mapPanel = layout.Children[0];
            var connectionPanel = layout.Children[1];
            Assert.InRange(Math.Abs(mapPanel.Bounds.Width - connectionPanel.Bounds.Width), 0, 32);
            Assert.Single(Descendants(mapPanel).OfType<RouteGlobeWebView>());
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Home_hides_the_extra_header_and_uses_wheel_only_map_controls()
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = 900, Height = 830 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            Assert.False(Required<Control>(window, "WorkspaceHeader").IsVisible);
            var page = Required<Grid>(window, "PageHost");
            var map = Assert.Single(Descendants(page).OfType<RouteGlobeWebView>());
            var mapPanel = Assert.IsType<Border>(map.Parent?.Parent);
            Assert.Empty(Descendants(mapPanel).OfType<Button>());
            Assert.DoesNotContain(Descendants(page).OfType<TextBlock>(), text =>
                text.Text is "КАРТА СЕТИ" or "NETWORK MAP" or "1:1");
            Assert.Contains(Descendants(page).OfType<TextBlock>(), text => text.Text == "ПИНГ");
            Assert.Contains(Descendants(page).OfType<TextBlock>(), text => text.Text == "СКОРОСТЬ");
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Expanded_route_country_shows_flags_and_readable_ping_and_speed_fields()
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true, routeProbeInProgress: true))
        {
            Width = 900,
            Height = 830,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var page = Required<Grid>(window, "PageHost");
            var countryHeader = Descendants(page).OfType<Button>().Single(button =>
                Descendants(button).OfType<TextBlock>().Any(text => text.Text == "Нидерланды") &&
                Descendants(button).OfType<TextBlock>().Any(text => text.Text == "VLESS"));

            countryHeader.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();

            var expanded = Required<Grid>(window, "PageHost");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "🇳🇱");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "ПИНГ");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "СКОРОСТЬ");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "— ms");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "— Mbps");
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Settings_toggle_track_is_compact_and_at_the_right_edge()
    {
        var window = new MainWindow(Fixture(tab: "settings", signedIn: true))
            { Width = 900, Height = 830 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var page = Required<Grid>(window, "PageHost");
            var toggle = Assert.Single(Descendants(page).OfType<Button>(), button =>
                Descendants(button).OfType<TextBlock>().Any(text => text.Text == "Показывать мой регион"));
            var content = Assert.IsType<Grid>(toggle.Content);
            var track = Assert.Single(content.Children.OfType<Border>());
            Assert.Equal(27, track.Height);
            Assert.True(content.Bounds.Width > 600);
            Assert.True(track.Bounds.X > content.Bounds.Width - 80);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Route_progress_only_renders_when_content_changes_and_restores_keyboard_focus(double width)
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var pageHost = Required<Grid>(window, "PageHost");
            var focusableButtons = Descendants(pageHost).OfType<Button>()
                .Where(button => button.IsEnabled && button.IsEffectivelyVisible).ToArray();
            Assert.NotEmpty(focusableButtons);
            var focusIndex = Math.Min(2, focusableButtons.Length - 1);
            Assert.True(focusableButtons[focusIndex].Focus());
            Dispatcher.UIThread.RunJobs();

            var snapshot = new WindowsRouteProbeResult("qa:nl-vless", null, null, null, "retry", 2);
            Assert.True(window.ApplyProbeProgress([snapshot], [snapshot.RouteTag]));
            Dispatcher.UIThread.RunJobs();
            var changedPage = Assert.Single(pageHost.Children);
            var focusedAfterChange = Assert.IsType<Button>(window.FocusManager?.GetFocusedElement());
            var buttonsAfterChange = Descendants(pageHost).OfType<Button>()
                .Where(button => button.IsEnabled && button.IsEffectivelyVisible).ToArray();
            Assert.True(buttonsAfterChange[focusIndex].IsFocused);

            Assert.False(window.ApplyProbeProgress([snapshot], [snapshot.RouteTag]));
            Assert.Same(changedPage, Assert.Single(pageHost.Children));
            Assert.Same(focusedAfterChange, window.FocusManager?.GetFocusedElement());
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Stop_button_cancel_transition_moves_focus_to_nearest_enabled_control(double width)
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true, routeProbeInProgress: true))
        {
            Width = width,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var stopButton = Descendants(Required<Grid>(window, "PageHost")).OfType<Button>()
                .Single(button => AutomationProperties.GetAutomationId(button) == "RouteDiagnosticsAction");
            Assert.Equal("Остановить проверку", Assert.IsType<TextBlock>(stopButton.Content).Text);
            Assert.True(stopButton.IsEnabled);
            Assert.True(stopButton.Focus());
            Dispatcher.UIThread.RunJobs();

            window.MarkProbeCancellationRequested();
            Dispatcher.UIThread.RunJobs();

            var stoppingButton = Descendants(Required<Grid>(window, "PageHost")).OfType<Button>()
                .Single(button => AutomationProperties.GetAutomationId(button) == "RouteDiagnosticsAction");
            Assert.Equal("Останавливаем…", Assert.IsType<TextBlock>(stoppingButton.Content).Text);
            Assert.False(stoppingButton.IsEffectivelyEnabled);
            var focused = Assert.IsType<Button>(window.FocusManager?.GetFocusedElement());
            Assert.NotSame(stoppingButton, focused);
            Assert.True(focused.IsEffectivelyEnabled);
            Assert.True(focused.IsEffectivelyVisible);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d, "BottomHomeNav")]
    [InlineData(1360d, "HomeNav")]
    public void Pending_route_focus_restore_does_not_override_navigation(double width, string homeNavigationName)
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var snapshot = new WindowsRouteProbeResult("qa:nl-vless", null, null, null, "retry", 2);
            Assert.True(window.ApplyProbeProgress([snapshot], [snapshot.RouteTag]));

            var homeNavigation = Required<Button>(window, homeNavigationName);
            Assert.True(homeNavigation.Focus());
            window.KeyReleaseQwerty(PhysicalKey.Space, RawInputModifiers.None);
            Dispatcher.UIThread.RunJobs();

            Assert.Equal("Ваше подключение", Required<TextBlock>(window, "WorkspaceTitle").Text);
            Assert.Same(homeNavigation, window.FocusManager?.GetFocusedElement());
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d, 520d, true)]
    [InlineData(1440d, 900d, false)]
    public void Signed_in_profile_keeps_one_tariff_action_in_the_expected_hierarchy(
        double width, double height, bool compact)
    {
        var window = new MainWindow(Fixture(tab: "profile", signedIn: true))
        {
            Width = width,
            Height = height,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var pageHost = Required<Grid>(window, "PageHost");
            var tariffActions = Descendants(pageHost).OfType<Button>()
                .Where(button => Descendants(button).OfType<TextBlock>()
                    .Any(text => text.Text == "Тарифы и оплата"))
                .ToArray();
            var tariffAction = Assert.Single(tariffActions);
            var tariffRow = Assert.IsType<Border>(tariffAction.GetVisualParent());
            var subscriptionHeading = Descendants(pageHost).OfType<TextBlock>()
                .Single(text => text.Text == "ПОДПИСКА");
            var pageScroll = Required<ScrollViewer>(window, "PageScroll");
            var tariffTop = tariffRow.TranslatePoint(new Point(0, 0), pageScroll)!.Value.Y;
            var subscriptionTop = subscriptionHeading.TranslatePoint(new Point(0, 0), pageScroll)!.Value.Y;

            Assert.True(tariffAction.IsEffectivelyVisible);
            Assert.True(IsFullyWithinViewport(tariffRow, Required<ScrollViewer>(window, "PageScroll")));
            Assert.Equal(compact, tariffTop < subscriptionTop);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Signed_out_settings_login_row_scrolls_fully_into_view_on_keyboard_focus()
    {
        var window = new MainWindow(Fixture(tab: "settings")) { Width = 720, Height = 520 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var pageScroll = Required<ScrollViewer>(window, "PageScroll");
            Assert.True(pageScroll.BringIntoViewOnFocusChange);
            var pageHost = Required<Grid>(window, "PageHost");
            var loginAction = Descendants(pageHost).OfType<Button>()
                .Single(button => Descendants(button).OfType<TextBlock>()
                    .Any(text => text.Text == "Войти через Telegram"));
            var loginRow = Assert.IsType<Border>(loginAction.GetVisualParent());
            Assert.True(IsFullyWithinViewport(loginRow, pageScroll), "Login action should start fully visible at 720x520.");
            pageScroll.ScrollToEnd();
            Dispatcher.UIThread.RunJobs();
            Assert.True(pageScroll.Offset.Y > 0);

            Assert.True(loginAction.Focus());
            Dispatcher.UIThread.RunJobs();

            Assert.True(loginAction.IsFocused);
            var loginOrigin = loginRow.TranslatePoint(new Point(0, 0), pageScroll);
            Assert.True(IsFullyWithinViewport(loginRow, pageScroll),
                $"offset={pageScroll.Offset.Y}, origin={loginOrigin}, rowHeight={loginRow.Bounds.Height}, viewport={pageScroll.Viewport.Height}");
            Assert.True(loginRow.Bounds.Height > 0);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Unlimited_profile_shows_total_transferred_without_quota_usage_or_percentage(double width)
    {
        var subscription = ProfileSubscription(trafficLimit: null, trafficUsed: 64L * 1024 * 1024,
            trafficTotal: 128L * 1024 * 1024);
        var window = new MainWindow(Fixture(tab: "profile", signedIn: true, profileSubscription: subscription))
        {
            Width = width,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var summary = ProfileSummary(window);
            Assert.Contains("передано: 128 MB", summary);
            Assert.Contains("без установленного лимита", summary);
            Assert.DoesNotContain("64 MB /", summary);
            Assert.DoesNotContain('%', summary);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Unlimited_profile_hides_transfer_line_when_usage_is_zero(double width)
    {
        var subscription = ProfileSubscription(trafficLimit: null, trafficUsed: 0, trafficTotal: null);
        var window = new MainWindow(Fixture(tab: "profile", signedIn: true, profileSubscription: subscription))
        {
            Width = width,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var summary = ProfileSummary(window);
            Assert.DoesNotContain("передано:", summary);
            Assert.DoesNotContain("без установленного лимита", summary);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Limited_profile_keeps_used_over_limit_summary(double width)
    {
        var subscription = ProfileSubscription(trafficLimit: 1024L * 1024 * 1024,
            trafficUsed: 512L * 1024 * 1024, trafficTotal: null);
        var window = new MainWindow(Fixture(tab: "profile", signedIn: true, profileSubscription: subscription))
        {
            Width = width,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var summary = ProfileSummary(window);
            Assert.Contains("512 MB / 1 GB", summary);
            Assert.DoesNotContain("без установленного лимита", summary);
            Assert.DoesNotContain('%', summary);
        }
        finally
        {
            window.Close();
        }
    }

    private static QaHomeFixture Fixture(string tab = "home", bool signedIn = false,
        bool routeProbeInProgress = false, TelegramSubscription? profileSubscription = null,
        string state = "disconnected")
    {
        var routes = new[]
        {
            new WindowsRoute("auto", "qa:auto", "AUTO", "Автоподбор", "✦", "AUTO", "Автоподбор"),
            new WindowsRoute("nl-vless", "qa:nl-vless", "NL", "Нидерланды", "🇳🇱", "VLESS", "VLESS"),
        };
        var account = signedIn
            ? new TelegramAccount("qa_fixture", "QA Demo", false,
                profileSubscription ?? ProfileSubscription(trafficLimit: null, trafficUsed: null, trafficTotal: null))
            : null;
        var subscription = signedIn
            ? new TelegramKeysSnapshot(true, false, 0,
                [new TelegramHappDevice(0, false, "QA fixture", "Synthetic desktop", "now")],
                "{\"qa_fixture\":true}", routes, [])
            : null;
        var tunnel = state switch
        {
            "connected" => new WindowsTunnelSnapshot("connected", "Synthetic only", "qa:nl-vless",
                HealthCheckedAt: DateTimeOffset.UtcNow),
            "connecting" => new WindowsTunnelSnapshot("starting", "Synthetic only"),
            "error" => new WindowsTunnelSnapshot("error", "Synthetic VPN error"),
            _ => new WindowsTunnelSnapshot("disconnected", "Synthetic only"),
        };
        return new QaHomeFixture(signedIn, "ru", "auto", tab, account, subscription, routes,
            tunnel,
            RouteProbeInProgress: routeProbeInProgress);
    }

    private static TelegramSubscription ProfileSubscription(long? trafficLimit, long? trafficUsed, long? trafficTotal) =>
        new(true, true, "qa", "QA Demo", "2099-12-31T00:00:00Z",
            false, 3, 1, trafficLimit, trafficUsed)
        {
            TrafficTotalBytes = trafficTotal,
        };

    private static string ProfileSummary(MainWindow window) => string.Join(" ",
        Descendants(Required<Grid>(window, "PageHost")).OfType<TextBlock>().Select(text => text.Text));

    private static T Required<T>(Control control, string name) where T : Control =>
        control.FindControl<T>(name) ?? throw new Xunit.Sdk.XunitException($"Missing control: {name}");

    private static bool IsFullyWithinViewport(Control control, ScrollViewer scrollViewer)
    {
        var origin = control.TranslatePoint(new Point(0, 0), scrollViewer);
        return origin is { } point && point.Y >= -1.5 &&
            point.Y + control.Bounds.Height <= scrollViewer.Viewport.Height + 1.5;
    }

    private static IEnumerable<Control> Descendants(Control root)
    {
        foreach (var child in root.GetVisualChildren().OfType<Control>())
        {
            yield return child;
            foreach (var descendant in Descendants(child))
                yield return descendant;
        }
    }
}
