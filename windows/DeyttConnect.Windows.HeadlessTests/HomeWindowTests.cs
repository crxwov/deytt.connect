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
    [InlineData("disconnected", "не подключено", "не подключено", "подключиться")]
    [InlineData("connecting", "подключаемся…", "подключаем vpn…", "отменить подключение")]
    [InlineData("error", "ошибка vpn", "ошибка vpn", "подключиться")]
    [InlineData("connected", "vpn подключён", "подключено", "отключить")]
    public void Vpn_fixture_renders_truthful_state_across_header_home_and_action(
        string state, string expectedHeader, string expectedHome, string expectedAction)
    {
        var window = new MainWindow(Fixture(signedIn: true, state: state)) { Width = 1360, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            Assert.Equal(expectedHeader, Required<TextBlock>(window, "HeaderStatusText").Text);
            Assert.False(Required<Control>(window, "SidebarPanel").IsVisible);
            Assert.True(Required<Control>(window, "BottomNavigationPanel").IsVisible);
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
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Home_navigation_and_controls_remain_reachable_at_supported_widths(double width)
    {
        var window = new MainWindow(Fixture(tab: "settings")) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var sidebar = Required<Control>(window, "SidebarPanel");
            var bottomNavigation = Required<Control>(window, "BottomNavigationPanel");
            Assert.False(sidebar.IsVisible);
            Assert.True(bottomNavigation.IsVisible);
            Assert.True(bottomNavigation.Bounds.Height >= 60,
                $"Bottom navigation did not reserve space: {bottomNavigation.Bounds}");

            var homeButton = Required<Button>(window, "BottomHomeNav");
            Assert.True(homeButton.IsEffectivelyVisible);
            Assert.True(homeButton.Focus());
            Assert.True(homeButton.IsFocused);

            window.KeyReleaseQwerty(PhysicalKey.Space, RawInputModifiers.None);
            Dispatcher.UIThread.RunJobs();

            Assert.Equal("ваше подключение", Required<TextBlock>(window, "WorkspaceTitle").Text);
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
        var window = new MainWindow(Fixture(signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var page = Required<Grid>(window, "PageHost");
            var action = Assert.Single(Descendants(page)
                .OfType<Button>(), button => AutomationProperties.GetAutomationId(button) == "HomeConnectionAction");
            var hitTarget = Assert.Single(Descendants(page).OfType<Border>(), border =>
                AutomationProperties.GetAutomationId(border) == "HomeConnectionActionTile");
            var status = Assert.Single(Descendants(page).OfType<Grid>(), grid =>
                AutomationProperties.GetAutomationId(grid) == "HomeConnectionStatus");
            var actionLabels = Descendants(action).OfType<TextBlock>().ToArray();
            var actionLabel = Assert.Single(actionLabels);
            var labelCenter = actionLabel.TranslatePoint(
                new Point(actionLabel.Bounds.Width / 2, actionLabel.Bounds.Height / 2), action)!.Value;
            var tileText = Descendants(hitTarget).OfType<TextBlock>().Select(text => text.Text).ToArray();
            var statusText = Descendants(status).OfType<TextBlock>().Select(text => text.Text).ToArray();

            Assert.True(action.IsEffectivelyVisible);
            Assert.True(action.IsEnabled);
            Assert.True(hitTarget.Bounds.Width >= 320);
            Assert.InRange(hitTarget.Height, 72, 90);
            Assert.Equal("подключиться", actionLabel.Text);
            Assert.InRange(Math.Abs(labelCenter.X - action.Bounds.Width / 2), 0, 1.5);
            Assert.InRange(Math.Abs(labelCenter.Y - action.Bounds.Height / 2), 0, 1.5);
            Assert.Single(tileText);
            Assert.Equal("подключиться", tileText[0]!);
            Assert.Contains("не подключено", statusText);
            Assert.Contains("подписка готова · выберите маршрут и подключитесь", statusText);
            Assert.DoesNotContain(status, Descendants(hitTarget));
            Assert.DoesNotContain("не подключено", actionLabels.Select(text => text.Text));
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
    public void Home_panel_matches_protocol_path_measurements_and_compact_action_order(double width)
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var page = Required<Grid>(window, "PageHost");
            var protocol = Assert.Single(Descendants(page).OfType<TextBlock>(), text => text.Text == "./hysteria2");
            var path = Assert.Single(Descendants(page).OfType<Border>(), border =>
                AutomationProperties.GetAutomationId(border) == "HomeTrafficPath");
            var measurements = Assert.Single(Descendants(page).OfType<Border>(), border =>
                AutomationProperties.GetAutomationId(border) == "HomeRouteMeasurements");
            var status = Assert.Single(Descendants(page).OfType<Grid>(), grid =>
                AutomationProperties.GetAutomationId(grid) == "HomeConnectionStatus");
            var tile = Assert.Single(Descendants(page).OfType<Border>(), border =>
                AutomationProperties.GetAutomationId(border) == "HomeConnectionActionTile");
            var pathText = Descendants(path).OfType<TextBlock>().Select(text => text.Text).ToArray();
            Assert.Contains("регион скрыт", pathText);
            Assert.Contains("амстердам", pathText);

            static double Top(Control control, Control relativeTo) =>
                control.TranslatePoint(new Point(0, 0), relativeTo)?.Y ?? double.NaN;
            Assert.True(Top(protocol, page) < Top(path, page));
            Assert.True(Top(path, page) < Top(measurements, page));
            Assert.False(status.IsVisible);
            Assert.True(Top(measurements, page) < Top(tile, page));
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Home_double_route_shows_known_country_hops_without_guessing_a_city()
    {
        var window = new MainWindow(Fixture(signedIn: true, selectedRoute: "ru-de"))
        {
            Width = 1360,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var page = Required<Grid>(window, "PageHost");
            var path = Assert.Single(Descendants(page).OfType<Border>(), border =>
                AutomationProperties.GetAutomationId(border) == "HomeTrafficPath");
            var pathText = Descendants(path).OfType<TextBlock>().Select(text => text.Text).ToArray();
            Assert.Contains("./lte ru+de", Descendants(page).OfType<TextBlock>().Select(text => text.Text));
            Assert.Contains("регион скрыт", pathText);
            Assert.Contains("россия", pathText);
            Assert.Contains("франкфурт", pathText);
            Assert.DoesNotContain("санкт-петербург", pathText);
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
            Assert.Contains(page.OfType<TextBlock>(), text => text.Text == "./hysteria2");
            Assert.DoesNotContain(page.OfType<TextBlock>(), text => text.Text == "QA Demo");
            Assert.True(Required<Control>(window, "BottomNavigationPanel").IsVisible);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Wide_home_limits_vertical_stretch_and_keeps_bottom_navigation()
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = 1360, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var page = Required<Grid>(window, "PageHost");
            var overview = Assert.IsType<Grid>(Assert.Single(page.Children));
            Assert.InRange(overview.Bounds.Height, 480, 520);
            Assert.False(Required<Control>(window, "SidebarPanel").IsVisible);
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
                text.Text is "карта сети" or "NETWORK MAP" or "1:1");
            Assert.Contains(Descendants(page).OfType<TextBlock>(), text => text.Text == "пинг");
            Assert.Contains(Descendants(page).OfType<TextBlock>(), text => text.Text == "скорость");
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
                AutomationProperties.GetAutomationId(button) == "RouteCountry-NL");

            countryHeader.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();

            var expanded = Required<Grid>(window, "PageHost");
            Assert.Contains(Descendants(expanded).OfType<Border>(), flag =>
                AutomationProperties.GetName(flag) == "флаг нидерландов");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "пинг");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "скорость");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "— ms");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "— mbps");

            var routeOptions = Descendants(expanded).OfType<Button>()
                .Where(button => AutomationProperties.GetAutomationId(button)?.StartsWith(
                    "RouteOption-nl-", StringComparison.Ordinal) == true)
                .ToArray();
            Assert.Equal(3, routeOptions.Length);
            Assert.All(routeOptions, option =>
            {
                Assert.True(option.Bounds.Width > 300);
                Assert.Contains(Descendants(option).OfType<TextBlock>(), text => text.Text == "пинг");
                Assert.Contains(Descendants(option).OfType<TextBlock>(), text => text.Text == "скорость");
            });

            Assert.True(window.ApplyProbeProgress(
                [new WindowsRouteProbeResult("qa:nl-vless", null, null, null, "latency", 1)],
                ["qa:nl-vless"]));
            Dispatcher.UIThread.RunJobs();
            var pendingOption = Descendants(Required<Grid>(window, "PageHost")).OfType<Button>()
                .Single(button => AutomationProperties.GetAutomationId(button) == "RouteOption-nl-vless");
            var pendingTexts = Descendants(pendingOption).OfType<TextBlock>().ToArray();
            var pingLabel = Assert.Single(pendingTexts, text => text.Text == "пинг");
            var labelLeft = pingLabel.TranslatePoint(new Point(0, 0), pendingOption)?.X;
            var pendingDots = pendingTexts
                .Where(text => text.Text is "·" or "··" or "···")
                .Select(text => (Text: text, Point: text.TranslatePoint(new Point(0, 0), pendingOption)))
                .Where(item => item.Point is not null)
                .ToArray();
            Assert.NotEmpty(pendingDots);
            var pendingDot = pendingDots.OrderBy(item => Math.Abs(
                labelLeft!.Value - item.Point!.Value.X)).First();
            var dotsLeft = pendingDot.Point?.X;
            Assert.NotNull(labelLeft);
            Assert.NotNull(dotsLeft);
            Assert.InRange(Math.Abs(labelLeft.Value - dotsLeft.Value), 0, 0.5);

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
                Descendants(button).OfType<TextBlock>().Any(text => text.Text == "показывать мой регион"));
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
    public void Route_probe_stop_cancel_transition_moves_focus_to_nearest_enabled_control(double width)
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true,
            routeProbeInProgress: true, expandedRouteCountry: "NL"))
        {
            Width = width,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var stopButton = Descendants(Required<Grid>(window, "PageHost")).OfType<Button>()
                .Single(button => AutomationProperties.GetAutomationId(button) == "RouteCountryRefresh-NL");
            Assert.Equal("остановить замеры", Assert.IsType<TextBlock>(stopButton.Content).Text);
            Assert.True(stopButton.IsEnabled);
            Assert.True(stopButton.Focus());
            Dispatcher.UIThread.RunJobs();

            window.MarkProbeCancellationRequested();
            Dispatcher.UIThread.RunJobs();

            var stoppingButton = Descendants(Required<Grid>(window, "PageHost")).OfType<Button>()
                .Single(button => AutomationProperties.GetAutomationId(button) == "RouteCountryRefresh-NL");
            Assert.Equal("останавливаем…", Assert.IsType<TextBlock>(stoppingButton.Content).Text);
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
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Routes_offer_country_scoped_refresh_without_global_probe_button(double width)
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var page = Required<Grid>(window, "PageHost");
            Assert.DoesNotContain(Descendants(page).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "RouteDiagnosticsAction");
            Assert.DoesNotContain(Descendants(page).OfType<TextBlock>(), text =>
                text.Text == "проверить все маршруты");

            var country = Assert.Single(Descendants(page).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "RouteCountry-NL");
            country.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();

            var refresh = Assert.Single(Descendants(Required<Grid>(window, "PageHost")).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "RouteCountryRefresh-NL");
            Assert.True(refresh.IsEnabled);
            Assert.Contains("обновить замеры", Descendants(refresh).OfType<TextBlock>()
                .Select(text => text.Text));
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d, false, 0)]
    [InlineData(1360d, true, 2)]
    public void Routes_explain_when_Amnezia_profile_is_missing(double width, bool amneziaActive,
        int activeAmneziaClients)
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true, amneziaActive: amneziaActive,
            amneziaClients: activeAmneziaClients))
        {
            Width = width,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var page = Required<Grid>(window, "PageHost");
            var pageText = string.Join(" | ", Descendants(page).OfType<TextBlock>().Select(text => text.Text));
            var sessionToken = typeof(MainWindow).GetField("_sessionToken",
                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)?.GetValue(window);
            var routeValues = typeof(MainWindow).GetField("_routes",
                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)?.GetValue(window)
                as IEnumerable<WindowsRoute>;
            var activeTab = typeof(MainWindow).GetField("_activeTab",
                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)?.GetValue(window);
            var routesSummary = routeValues is null
                ? "<null>"
                : string.Join(",", routeValues.Select(route => route.Protocol));
            Assert.True(pageText.Contains("AmneziaWG", StringComparison.Ordinal),
                $"Amnezia label missing; session={sessionToken is string session && session.Length > 0}; " +
                $"routes={routesSummary}; tab={activeTab}; text={pageText}");
            Assert.Contains(Descendants(page).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "AmneziaRefreshProfile");
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d, "BottomHomeNav")]
    [InlineData(1360d, "BottomHomeNav")]
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

            Assert.Equal("ваше подключение", Required<TextBlock>(window, "WorkspaceTitle").Text);
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
                    .Any(text => text.Text == "тарифы и оплата"))
                .ToArray();
            var tariffAction = Assert.Single(tariffActions);
            var tariffRow = Assert.IsType<Border>(tariffAction.GetVisualParent());
            var subscriptionHeading = Descendants(pageHost).OfType<TextBlock>()
                .Single(text => text.Text == "подписка");
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
                    .Any(text => text.Text == "войти через telegram"));
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
            Assert.Contains("передано: 128 mb", summary);
            Assert.Contains("без установленного лимита", summary);
            Assert.DoesNotContain("64 mb /", summary);
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
            Assert.Contains("512 mb / 1 gb", summary);
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
        string selectedRoute = "nl-hysteria2",
        string state = "disconnected", string? expandedRouteCountry = null,
        bool amneziaActive = false, int amneziaClients = 0)
    {
        var routes = new List<WindowsRoute>
        {
            new WindowsRoute("auto", "qa:auto", "AUTO", "Автоподбор", "✦", "AUTO", "Автоподбор"),
            new WindowsRoute("ru-de", "qa:ru-de", "RU-DE", "Россия → Германия", "🇷🇺→🇩🇪", "CHAIN", "RU → DE"),
            new WindowsRoute("nl-vless", "qa:nl-vless", "NL", "Нидерланды", "🇳🇱", "VLESS", "VLESS"),
            new WindowsRoute("nl-trojan", "qa:nl-trojan", "NL", "Нидерланды", "🇳🇱", "TROJAN", "Trojan"),
            new WindowsRoute("nl-hysteria2", "qa:nl-hysteria2", "NL", "Нидерланды", "🇳🇱", "HYSTERIA2", "Hysteria 2"),
            new WindowsRoute("de-vless", "qa:de-vless", "DE", "Германия", "🇩🇪", "VLESS", "VLESS"),
            new WindowsRoute("de-trojan", "qa:de-trojan", "DE", "Германия", "🇩🇪", "TROJAN", "Trojan"),
            new WindowsRoute("de-hysteria2", "qa:de-hysteria2", "DE", "Германия", "🇩🇪", "HYSTERIA2", "Hysteria 2"),
            new WindowsRoute("ru-vless", "qa:ru-vless", "RU", "Россия", "🇷🇺", "VLESS", "VLESS"),
            new WindowsRoute("ru-trojan", "qa:ru-trojan", "RU", "Россия", "🇷🇺", "TROJAN", "Trojan"),
            new WindowsRoute("ru-hysteria2", "qa:ru-hysteria2", "RU", "Россия", "🇷🇺", "HYSTERIA2", "Hysteria 2"),
            new WindowsRoute("fi-vless", "qa:fi-vless", "FI", "Финляндия", "🇫🇮", "VLESS", "VLESS"),
            new WindowsRoute("fi-trojan", "qa:fi-trojan", "FI", "Финляндия", "🇫🇮", "TROJAN", "Trojan"),
            new WindowsRoute("fi-hysteria2", "qa:fi-hysteria2", "FI", "Финляндия", "🇫🇮", "HYSTERIA2", "Hysteria 2"),
        };
        var account = signedIn
            ? new TelegramAccount("qa_fixture", "QA Demo", false,
                profileSubscription ?? ProfileSubscription(trafficLimit: null, trafficUsed: null, trafficTotal: null))
            : null;
        var subscription = signedIn
            ? new TelegramKeysSnapshot(true, amneziaActive, amneziaClients,
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
        return new QaHomeFixture(signedIn, "ru", selectedRoute, tab, account, subscription, routes,
            tunnel,
            RouteProbeInProgress: routeProbeInProgress,
            DisableNativeMapInitialization: true,
            ExpandedRouteCountry: expandedRouteCountry);
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

