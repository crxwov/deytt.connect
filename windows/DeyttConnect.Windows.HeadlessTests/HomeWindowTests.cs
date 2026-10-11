using Avalonia;
using Avalonia.Controls;
using Avalonia.Automation;
using Avalonia.Headless;
using Avalonia.Headless.XUnit;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Avalonia.VisualTree;
using DeyttConnect.Protocol;
using DeyttConnect.Windows;
using DeyttConnect.Windows.Views;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Controls;
using System.Reflection;
using Xunit;

namespace DeyttConnect.Windows.HeadlessTests;

public sealed class HomeWindowTests
{
    [Theory]
    [InlineData("ru", "Мбит/с")]
    [InlineData("en", "Mbit/s")]
    public void Speed_units_preserve_the_same_notation_across_pages(string language, string expected) =>
        Assert.Equal(expected, MainWindow.LocalizedSpeedUnit(language));

    [AvaloniaTheory]
    [InlineData(34)]
    [InlineData(42)]
    public void Chained_route_flags_fit_inside_their_frame(double width)
    {
        var flag = DeyttConnect.Windows.UI.RouteFlagVisual.Create("RU-DE", width, 28);
        var window = new Window { Content = flag, Width = 100, Height = 100 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            foreach (var child in Descendants(flag))
            {
                var position = child.TranslatePoint(new Point(0, 0), flag);
                Assert.NotNull(position);
                Assert.InRange(position.Value.X, -1, width);
                Assert.True(position.Value.X + child.Bounds.Width <= width + 1);
            }
        }
        finally { window.Close(); }
    }

    [AvaloniaFact]
    public void Window_commands_minimize_restore_and_close()
    {
        var window = new MainWindow(Fixture(tab: "settings")) { Width = 1360, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            Required<Button>(window, "MinimizeWindowButton").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Assert.Equal(WindowState.Minimized, window.WindowState);
            window.WindowState = WindowState.Normal;
            var maximize = Required<Button>(window, "MaximizeWindowButton");
            maximize.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Assert.Equal(WindowState.Maximized, window.WindowState);
            maximize.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Assert.Equal(WindowState.Normal, window.WindowState);
            Required<Button>(window, "CloseWindowButton").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Assert.False(window.IsVisible);
        }
        finally { window.Close(); }
    }

    [AvaloniaFact]
    public async Task Repeated_key_reset_click_opens_one_flow_and_cancel_releases_it()
    {
        var window = new MainWindow(Fixture(tab: "settings", signedIn: true));
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var method = typeof(MainWindow).GetMethod("ShowResetChoicesAsync",
                BindingFlags.Instance | BindingFlags.NonPublic)!;
            var first = (Task)method.Invoke(window, null)!;
            var second = (Task)method.Invoke(window, null)!;
            Dispatcher.UIThread.RunJobs();
            Assert.True(second.IsCompletedSuccessfully);
            Assert.False(first.IsCompleted);
            var host = Required<Grid>(window, "ModalOverlayHost");
            Assert.Single(host.Children);
            Descendants(host).OfType<Button>().Single(button =>
                AutomationProperties.GetAutomationId(button) == "DialogCloseButton")
                .RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            await first.WaitAsync(TimeSpan.FromSeconds(3));
            Assert.False((bool)typeof(MainWindow).GetField("_keyResetInFlight",
                BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(window)!);
        }
        finally { window.Close(); }
    }

    [AvaloniaTheory]
    [InlineData("home", "TopHomeNav")]
    [InlineData("routes", "TopRoutesNav")]
    [InlineData("profile", "TopProfileNav")]
    [InlineData("settings", "TopSettingsNav")]
    public void Regular_pages_keep_their_view_instance_across_navigation_when_inputs_are_unchanged(
        string tab, string navigationAutomationId)
    {
        var window = new MainWindow(Fixture(tab: tab, signedIn: true)) { Width = 1360, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var pageHost = Required<Grid>(window, "PageHost");
            var originalPage = Assert.Single(pageHost.Children);
            var originalContent = Assert.Single(Assert.IsType<Grid>(originalPage).Children);

            var alternateNavigationId = tab == "settings" ? "TopRoutesNav" : "TopSettingsNav";
            Required<Button>(window, alternateNavigationId).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            Required<Button>(window, navigationAutomationId).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();

            Assert.Same(originalPage, Assert.Single(pageHost.Children));
            Assert.Same(originalContent, Assert.Single(Assert.IsType<Grid>(originalPage).Children));
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Persistent_routes_page_refreshes_measurement_data_without_replacing_page()
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true)) { Width = 1360, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var pageHost = Required<Grid>(window, "PageHost");
            var originalPage = Assert.Single(pageHost.Children);
            var pageViewModel = Assert.IsType<RoutesViewModel>(originalPage.DataContext);

            Required<Button>(window, "TopSettingsNav").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            pageViewModel.ProbeResults = new Dictionary<string, WindowsRouteProbeResult>(StringComparer.Ordinal)
                {
                    ["qa:nl-vless"] = new("qa:nl-vless", 42, null, null, "waiting_speed"),
                };

            Required<Button>(window, "TopRoutesNav").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            var refreshedPage = Assert.Single(pageHost.Children);

            Assert.Same(originalPage, refreshedPage);
            Assert.Same(pageViewModel, refreshedPage.DataContext);
            Assert.Contains(Descendants(refreshedPage).OfType<TextBlock>(), text => text.Text == "42 мс");
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData("disconnected", "не подключено", "подключиться")]
    [InlineData("connecting", "подключаемся…", "отменить подключение")]
    [InlineData("error", "не удалось подключиться", "повторить")]
    [InlineData("connected", "подключено", "отключить")]
    public void Vpn_fixture_renders_truthful_state_in_home_presentation(
        string state, string expectedStatus, string expectedAction)
    {
        var window = new MainWindow(Fixture(signedIn: true, state: state)) { Width = 1360, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            Assert.False(Required<Control>(window, "SidebarPanel").IsVisible);
            Assert.False(Required<Control>(window, "BottomNavigationPanel").IsVisible);
            var map = Assert.Single(Descendants(Required<Grid>(window, "PageHost"))
                .OfType<RouteGlobeWebView>());
            Assert.Equal(expectedStatus, map.HomePresentation?.Status);
            Assert.Equal(expectedAction, map.HomePresentation?.ActionLabel);
            if (state == "error")
                Assert.Equal("Synthetic VPN error", map.HomePresentation?.Message);
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
        var window = new MainWindow(Fixture(tab: "settings", signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var sidebar = Required<Control>(window, "SidebarPanel");
            var topNavigation = Required<StackPanel>(window, "HeaderNavigation");
            var bottomNavigation = Required<Control>(window, "BottomNavigationPanel");
            Assert.False(sidebar.IsVisible);
            Assert.False(bottomNavigation.IsVisible);
            Assert.True(topNavigation.IsEffectivelyVisible);

            var homeButton = Required<Button>(window, "TopHomeNav");
            Assert.True(homeButton.IsEffectivelyVisible);
            Assert.True(homeButton.Focus());
            Assert.True(homeButton.IsFocused);

            window.KeyReleaseQwerty(PhysicalKey.Space, RawInputModifiers.None);
            Dispatcher.UIThread.RunJobs();

            Assert.True(Required<Control>(window, "WorkspaceHeader").IsVisible);
            var pageHost = Required<Grid>(window, "PageHost");
            Assert.Single(pageHost.Children);
            var homeMap = Assert.Single(Descendants(pageHost).OfType<RouteGlobeWebView>());
            Assert.True(homeMap.HomePresentation?.ActionEnabled);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Home_presentation_exposes_a_real_enabled_connection_action(double width)
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var map = Assert.Single(Descendants(Required<Grid>(window, "PageHost"))
                .OfType<RouteGlobeWebView>());
            var presentation = Assert.IsType<HomePresentationSnapshot>(map.HomePresentation);
            Assert.Equal("не подключено", presentation.Status);
            Assert.Equal("подключиться", presentation.ActionLabel);
            Assert.Equal("home-connect", presentation.Action);
            Assert.True(presentation.ActionEnabled);
            Assert.True(map.Bounds.Width > 300);
            Assert.True(map.Bounds.Height > 500);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Home_panel_presentation_uses_real_selected_route_and_measurement_state(double width)
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = width, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var map = Assert.Single(Descendants(Required<Grid>(window, "PageHost"))
                .OfType<RouteGlobeWebView>());
            var presentation = Assert.IsType<HomePresentationSnapshot>(map.HomePresentation);
            Assert.Equal("нидерланды", presentation.Country);
            Assert.Equal("амстердам", presentation.City);
            Assert.Equal("./hysteria", presentation.Protocol);
            Assert.Null(presentation.Latency);
            Assert.Null(presentation.Speed);
            Assert.Equal("nl", map.SelectedRoute);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData("vless", "./vless+ws")]
    [InlineData("trojan", "./trojan")]
    [InlineData("hysteria", "./hysteria")]
    [InlineData("awg31", "./amnezia3.1")]
    public void Home_protocol_uses_the_short_transport_label(string protocol, string expected)
    {
        var selectedRoute = protocol switch
        {
            "vless" => "nl-vless",
            "trojan" => "nl-trojan",
            "awg31" => "de-awg31",
            _ => "nl-hysteria2",
        };
        var window = new MainWindow(Fixture(signedIn: true, selectedRoute: selectedRoute,
            includeAmneziaRoute: protocol == "awg31"))
        {
            Width = 1360,
            Height = 820,
        };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var map = Assert.Single(Descendants(Required<Grid>(window, "PageHost"))
                .OfType<RouteGlobeWebView>());
            Assert.Equal(expected, map.HomePresentation?.Protocol);
        }
        finally { window.Close(); }
    }

    [Fact]
    public void Best_route_prefers_speed_when_latency_is_within_a_small_tolerance()
    {
        var samples = new[]
        {
            new WindowsRouteProbeResult("qa:nl-vless", 71, 6_837_500, null),
            new WindowsRouteProbeResult("qa:nl-trojan", 71, 9_337_500, null),
            new WindowsRouteProbeResult("qa:nl-hysteria2", 72, 10_362_500, null),
            new WindowsRouteProbeResult("qa:nl-slow-latency", 100, 100_000_000, null),
        };

        Assert.Equal("qa:nl-hysteria2", MainWindow.SelectBestMeasuredRoute(samples));
    }

    [Fact]
    public void Best_route_uses_lowest_latency_to_break_equal_speed_ties()
    {
        var samples = new[]
        {
            new WindowsRouteProbeResult("qa:nl-trojan", 72, 10_000_000, null),
            new WindowsRouteProbeResult("qa:nl-hysteria2", 71, 10_000_000, null),
        };

        Assert.Equal("qa:nl-hysteria2", MainWindow.SelectBestMeasuredRoute(samples));
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

            var map = Assert.Single(Descendants(Required<Grid>(window, "PageHost"))
                .OfType<RouteGlobeWebView>());
            Assert.Equal("ru-de", map.SelectedRoute);
            Assert.Equal("lte · белые списки", map.HomePresentation?.Country);
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
    public void Compact_home_uses_one_scrollable_web_surface_below_the_top_navigation()
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = 900, Height = 650 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var page = Required<Grid>(window, "PageHost");
            var map = Assert.Single(Descendants(page).OfType<RouteGlobeWebView>());
            Assert.True(map.Bounds.Height > 450);
            Assert.Single(page.Children);
            Assert.True(Required<Control>(window, "WorkspaceHeader").IsVisible);
            Assert.False(Required<Control>(window, "BottomNavigationPanel").IsVisible);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Wide_home_limits_vertical_stretch_and_keeps_top_navigation()
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = 1360, Height = 820 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var map = Assert.Single(Descendants(Required<Grid>(window, "PageHost"))
                .OfType<RouteGlobeWebView>());
            Assert.InRange(map.Bounds.Height, 520, 1000);
            Assert.False(Required<Control>(window, "SidebarPanel").IsVisible);
            Assert.True(Required<Control>(window, "WorkspaceHeader").IsVisible);
            Assert.False(Required<Control>(window, "BottomNavigationPanel").IsVisible);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Medium_home_keeps_connection_and_globe_in_the_same_local_surface()
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = 900, Height = 830 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var host = Required<Grid>(window, "PageHost");
            var map = Assert.Single(Descendants(host).OfType<RouteGlobeWebView>());
            Assert.True(map.Bounds.Width > 500);
            Assert.True(map.Bounds.Height >= 720);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Home_keeps_the_shared_header_outside_the_globe_surface()
    {
        var window = new MainWindow(Fixture(signedIn: true)) { Width = 900, Height = 830 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            Assert.True(Required<Control>(window, "WorkspaceHeader").IsVisible);
            var page = Required<Grid>(window, "PageHost");
            var map = Assert.Single(Descendants(page).OfType<RouteGlobeWebView>());
            var persistentPage = Assert.IsType<Grid>(Assert.Single(page.Children));
            Assert.Contains(map, Descendants(persistentPage).OfType<RouteGlobeWebView>());
            Assert.Equal("интерактивный глобус маршрута deytt.", AutomationProperties.GetName(map));
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Expanded_route_country_shows_flags_and_readable_response_and_speed_fields()
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true, routeProbeInProgress: true,
            selectedRoute: "de-hysteria2"))
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
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "отклик");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "скорость");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "не измерено");
            Assert.Contains(Descendants(expanded).OfType<TextBlock>(), text => text.Text == "не измерено");

            var routeOptions = Descendants(expanded).OfType<Button>()
                .Where(button => AutomationProperties.GetAutomationId(button)?.StartsWith(
                    "RouteOption-nl-", StringComparison.Ordinal) == true)
                .ToArray();
            Assert.Equal(3, routeOptions.Length);
            var routeRows = routeOptions.Select(option => Assert.IsType<Border>(option.GetVisualParent())).ToArray();
            Assert.All(routeRows, row => Assert.Equal(routeRows[0].Bounds.Height, row.Bounds.Height));
            var protocolLogos = routeOptions.SelectMany(Descendants)
                .OfType<DeyttConnect.Windows.UI.ProtocolLogo>().ToArray();
            Assert.Equal(3, protocolLogos.Length);
            Assert.All(protocolLogos, logo =>
            {
                Assert.Equal(36, logo.Bounds.Width);
                Assert.Equal(36, logo.Bounds.Height);
            });
            Assert.All(routeOptions, option =>
            {
                Assert.True(option.Bounds.Width > 300);
                var optionText = Descendants(option).OfType<TextBlock>().Select(text => text.Text).ToArray();
                Assert.Contains("не измерено", optionText);
                Assert.Contains("не измерено", optionText);
                Assert.DoesNotContain("отклик", optionText);
                Assert.DoesNotContain("скорость", optionText);
            });

            Assert.True(window.ApplyProbeProgress(
                [new WindowsRouteProbeResult("qa:nl-vless", null, null, null, "latency", 1)],
                ["qa:nl-vless"]));
            Dispatcher.UIThread.RunJobs();
            var pendingOption = Descendants(Required<Grid>(window, "PageHost")).OfType<Button>()
                .Single(button => AutomationProperties.GetAutomationId(button) == "RouteOption-nl-vless");
            var pendingTexts = Descendants(pendingOption).OfType<TextBlock>().ToArray();
            var pendingDots = pendingTexts
                .Where(text => text.Text is "·" or "··" or "···")
                .Select(text => (Text: text, Point: text.TranslatePoint(new Point(0, 0), pendingOption)))
                .Where(item => item.Point is not null)
                .ToArray();
            Assert.Single(pendingDots);
            Assert.True(pendingDots[0].Point!.Value.X > 0);

        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public void Unmeasured_routes_show_localized_state_and_a_measure_action()
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true,
            expandedRouteCountry: "NL")) { Width = 900, Height = 830 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();

            var page = Required<Grid>(window, "PageHost");
            Assert.Contains(Descendants(page).OfType<TextBlock>(), text => text.Text == "не измерено");
            var measure = Descendants(page).OfType<Button>().Single(button =>
                AutomationProperties.GetAutomationId(button) == "RouteCountryRefresh-NL");
            Assert.Contains(Descendants(measure).OfType<TextBlock>(), text => text.Text == "обновить замеры");
        }
        finally
        {
            window.Close();
        }
    }

    [Fact]
    public void Leftover_provider_adapter_does_not_report_a_stopped_service_as_running()
    {
        var runningServices = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        Assert.Null(WindowsExternalVpnDetector.GetRunningServiceForAdapter(
            "WireGuardManager", runningServices));

        runningServices.Add("WireGuardManager");
        Assert.Equal("WireGuardManager", WindowsExternalVpnDetector.GetRunningServiceForAdapter(
            "WireGuardManager", runningServices));
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
            var privacyHeader = Descendants(page).OfType<Button>().Single(button =>
                Descendants(button).OfType<TextBlock>().Any(text => text.Text == "приватность"));
            privacyHeader.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();

            page = Required<Grid>(window, "PageHost");
            var toggle = Assert.Single(Descendants(page).OfType<Button>(), button =>
                Descendants(button).OfType<TextBlock>().Any(text => text.Text == "показывать мой регион"));
            var content = Assert.IsType<Grid>(toggle.Content);
            var track = Assert.Single(content.Children.OfType<Border>());
            Assert.Equal(28, track.Height);
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
            if (!Descendants(page).OfType<Button>().Any(button =>
                    AutomationProperties.GetAutomationId(button) == "RouteCountryRefresh-NL"))
            {
                country.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Dispatcher.UIThread.RunJobs();
            }

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
            Assert.Contains("amneziawg", pageText.ToLowerInvariant());
            Assert.Contains(Descendants(page).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "AmneziaRefreshProfile");
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(720d)]
    [InlineData(1360d)]
    public void Routes_do_not_repeat_Amnezia_status_card_when_profile_is_available(double width)
    {
        var window = new MainWindow(Fixture(tab: "routes", signedIn: true, includeAmneziaRoute: true))
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
            Assert.DoesNotContain(Descendants(page).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "AmneziaRefreshProfile");
            Assert.DoesNotContain("профиль amneziawg доступен в списке маршрутов ниже", pageText.ToLowerInvariant());
            var germanyHeader = Descendants(page).OfType<Button>().Single(button =>
                AutomationProperties.GetAutomationId(button) == "RouteCountry-DE");
            germanyHeader.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            page = Required<Grid>(window, "PageHost");
            Assert.Contains(Descendants(page).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "RouteOption-de-awg31");
            Assert.Contains(Descendants(page).OfType<TextBlock>(), text =>
                text.Text?.Contains("amneziawg 3.1", StringComparison.OrdinalIgnoreCase) == true);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(520d, 480d)]
    [InlineData(1360d, 820d)]
    public async Task Information_dialog_uses_compact_acknowledgement_and_restores_focus(double width, double height)
    {
        var window = new MainWindow(Fixture()) { Width = width, Height = height };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var homeButton = Required<Button>(window, "TopHomeNav");
            Assert.True(homeButton.Focus());

            var showInfo = typeof(MainWindow).GetMethod("ShowInfoInShellAsync",
                BindingFlags.Instance | BindingFlags.NonPublic);
            Assert.NotNull(showInfo);
            var dialogTask = showInfo!.Invoke(window,
                ["Текущее подключение", "Проверка подключения завершена. Диагностика показывает доступность выбранного маршрута, но не гарантирует доступ к каждому сайту. Текущий внешний IP: 203.0.113.8. Если соединение не работает, выберите другой маршрут и повторите проверку.", null]) as Task
                ?? throw new InvalidOperationException("ShowInfoInShellAsync did not return a task.");
            Dispatcher.UIThread.RunJobs();

            var overlay = Required<Grid>(window, "ModalOverlayHost");
            var closeAction = Assert.Single(Descendants(overlay).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "DialogCloseAction");
            var card = Assert.Single(Descendants(overlay).OfType<Border>(), border =>
                border.Child is StackPanel);
            Assert.InRange(card.Bounds.Width, 300, 456);
            Assert.Equal(44, closeAction.Bounds.Height);
            Assert.True(closeAction.IsFocused);
            Assert.Contains(Descendants(overlay).OfType<TextBlock>(), text =>
                text.Text?.Contains("не гарантирует доступ к каждому сайту", StringComparison.OrdinalIgnoreCase) == true &&
                text.TextWrapping == Avalonia.Media.TextWrapping.Wrap);

            SaveSnapshotIfRequested(window, $"connection-info-{(int)width}.png");

            closeAction.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            await dialogTask.WaitAsync(TimeSpan.FromSeconds(3));

            Assert.False(overlay.IsVisible);
            Assert.True(homeButton.IsFocused);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(520d, false)]
    [InlineData(1360d, true)]
    public async Task Confirmation_dialog_preserves_cancel_and_confirm_results(double width, bool accept)
    {
        var window = new MainWindow(Fixture()) { Width = width, Height = 540 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var showConfirm = typeof(MainWindow).GetMethod("ConfirmInShellAsync",
                BindingFlags.Instance | BindingFlags.NonPublic);
            Assert.NotNull(showConfirm);
            var dialogTask = showConfirm!.Invoke(window,
                ["Показывать ваш примерный регион?", "Точка определяется по публичному IP и не влияет на выбор маршрута.",
                    "Показывать на карте", "не сейчас"]) as Task<bool>
                ?? throw new InvalidOperationException("ConfirmInShellAsync did not return a task.");
            Dispatcher.UIThread.RunJobs();

            var overlay = Required<Grid>(window, "ModalOverlayHost");
            var cancelAction = Assert.Single(Descendants(overlay).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "DialogCancelAction");
            var confirmAction = Assert.Single(Descendants(overlay).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "DialogConfirmAction");
            Assert.True(cancelAction.IsFocused);
            (accept ? confirmAction : cancelAction).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();

            Assert.Equal(accept, await dialogTask.WaitAsync(TimeSpan.FromSeconds(3)));
            Assert.False(overlay.IsVisible);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaTheory]
    [InlineData(520d, 480d)]
    [InlineData(1360d, 820d)]
    public async Task Active_vpn_notice_uses_responsive_actions_and_scrollable_copy(double width, double height)
    {
        var window = new MainWindow(Fixture()) { Width = width, Height = height };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var detected = new[]
            {
                new WindowsExternalVpnObservation("WireGuard", ["VPN interface detected"], ["WireGuardManager"]),
            };
            var layout = window.BuildActiveVpnNoticeContent(detected, includeStopButton: true,
                includeWindowsSettingsButton: true);
            var showContent = typeof(MainWindow).GetMethod("ShowContentInShellAsync",
                BindingFlags.Instance | BindingFlags.NonPublic);
            Assert.NotNull(showContent);
            var dialogTask = showContent!.Invoke(window,
                ["Активные VPN-службы", layout.Content, 560d, null, layout.Actions]) as Task<object?>
                ?? throw new InvalidOperationException("ShowContentInShellAsync did not return a task.");
            Dispatcher.UIThread.RunJobs();

            var overlay = Required<Grid>(window, "ModalOverlayHost");
            var card = Assert.Single(Descendants(overlay).OfType<Border>(), border =>
                border.Child is StackPanel);
            var closeAction = Assert.Single(Descendants(overlay).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "DialogCloseButton");
            var stopAction = Assert.Single(Descendants(overlay).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "ExternalVpnStop");
            var settingsAction = Assert.Single(Descendants(overlay).OfType<Button>(), button =>
                AutomationProperties.GetAutomationId(button) == "ExternalVpnSettings");
            var body = Assert.Single(Descendants(overlay).OfType<ScrollViewer>());
            Assert.InRange(card.Bounds.Width, 300, 560);
            Assert.Equal(44, stopAction.Bounds.Height);
            Assert.Equal(44, settingsAction.Bounds.Height);
            Assert.Equal(Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
                body.HorizontalScrollBarVisibility);
            Assert.Contains(Descendants(overlay).OfType<TextBlock>(), text =>
                text.Text?.Contains("VPN-службы или сетевые туннели", StringComparison.OrdinalIgnoreCase) == true);
            Assert.True(layout.ContinueButton.IsEnabled);
            Assert.True(layout.ContinueButton.IsEffectivelyVisible);
            Assert.True(stopAction.IsEffectivelyVisible);
            Assert.True(settingsAction.IsEffectivelyVisible);
            if (height < 600)
                Assert.True(body.MaxHeight <= 200, $"Dialog body did not reserve footer space: {body.MaxHeight}.");
            Assert.True(closeAction.IsFocused);
            SaveSnapshotIfRequested(window, $"active-vpn-notice-{(int)width}.png");

            closeAction.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            await dialogTask.WaitAsync(TimeSpan.FromSeconds(3));
            Assert.False(overlay.IsVisible);
        }
        finally
        {
            window.Close();
        }
    }

    [AvaloniaFact]
    public async Task Short_window_dialog_keeps_footer_reachable_by_scrolling()
    {
        var window = new MainWindow(Fixture(tab: "settings"))
            { Width = 520, MinHeight = 320, Height = 320 };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            var method = typeof(MainWindow).GetMethod("ShowInfoInShellAsync",
                BindingFlags.Instance | BindingFlags.NonPublic)!;
            var task = (Task)method.Invoke(window,
                ["Подробности", string.Join("\n", Enumerable.Repeat("Длинное описание ошибки подключения.", 30)), "Закрыть"] )!;
            Dispatcher.UIThread.RunJobs();
            var overlay = Required<Grid>(window, "ModalOverlayHost");
            var footer = Descendants(overlay).OfType<Button>().Single(button =>
                AutomationProperties.GetAutomationId(button) == "DialogCloseAction");
            var scroller = footer.GetVisualAncestors().OfType<ScrollViewer>().First();
            Assert.True(scroller.Bounds.Height <= window.Bounds.Height - 44);
            scroller.ScrollToEnd();
            Dispatcher.UIThread.RunJobs();
            Assert.True(IsFullyWithinViewport(footer, scroller));
            footer.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();
            await task.WaitAsync(TimeSpan.FromSeconds(3));
            Assert.False(overlay.IsVisible);
        }
        finally { window.Close(); }
    }

    private static void SaveSnapshotIfRequested(Window window, string fileName)
    {
        if (Environment.GetEnvironmentVariable("DEYTT_UI_SNAPSHOT_DIR") is not { Length: > 0 } snapshotDirectory)
            return;

        Directory.CreateDirectory(snapshotDirectory);
        AvaloniaHeadlessPlatform.ForceRenderTimerTick(2);
        Dispatcher.UIThread.RunJobs();
        using var bitmap = window.GetLastRenderedFrame()
            ?? throw new InvalidOperationException("The headless window did not render a frame.");
        var snapshotPath = Path.Combine(snapshotDirectory, fileName);
        using (var file = File.Create(snapshotPath))
            bitmap.Save(file, PngBitmapEncoderOptions.Default);
        Assert.True(new FileInfo(snapshotPath).Length > 0, "The headless snapshot was empty.");
    }

    [AvaloniaTheory]
    [InlineData(720d, "TopHomeNav")]
    [InlineData(1360d, "TopHomeNav")]
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
    [InlineData(720d, 520d)]
    [InlineData(1440d, 900d)]
    public void Signed_in_profile_keeps_one_tariff_action_in_the_expected_hierarchy(
        double width, double height)
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
            pageScroll.ScrollToEnd();
            Dispatcher.UIThread.RunJobs();
            Assert.True(IsFullyWithinViewport(tariffRow, pageScroll));
            Assert.True(subscriptionTop < tariffTop);
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
            var subscriptionHeader = Descendants(pageHost).OfType<Button>().Single(button =>
                Descendants(button).OfType<TextBlock>().Any(text => text.Text == "подписка"));
            subscriptionHeader.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Dispatcher.UIThread.RunJobs();

            pageHost = Required<Grid>(window, "PageHost");
            var loginAction = Descendants(pageHost).OfType<Button>()
                .Single(button => Descendants(button).OfType<TextBlock>()
                    .Any(text => text.Text == "войти через telegram"));
            var loginRow = Assert.IsType<Border>(loginAction.GetVisualParent());
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
    public void Unlimited_profile_shows_used_traffic_without_quota_or_percentage(double width)
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
            Assert.Contains("использовано 64 мб", summary);
            Assert.Contains("без лимита", summary);
            Assert.DoesNotContain("128 мб", summary);
            Assert.DoesNotContain("64 мб из", summary);
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
    public void Unlimited_profile_shows_zero_used_traffic_and_no_quota(double width)
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
            Assert.Contains("трафик", summary);
            Assert.Contains("использовано 0 б", summary);
            Assert.Contains("без лимита", summary);
            Assert.DoesNotContain("передано:", summary);
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
            Assert.Contains("использовано 512 мб из 1 гб", summary);
            Assert.DoesNotContain("без лимита", summary);
            Assert.DoesNotContain('%', summary);
        }
        finally
        {
            window.Close();
        }
    }

    [Fact]
    public void Subscription_expiry_highlight_includes_exactly_eight_days()
    {
        var now = DateTimeOffset.Parse("2026-10-11T12:00:00Z");

        Assert.True(MainWindow.SubscriptionExpiryNeedsHighlight(now.AddDays(8), now));
        Assert.False(MainWindow.SubscriptionExpiryNeedsHighlight(now.AddDays(8).AddTicks(1), now));
        Assert.True(MainWindow.SubscriptionExpiryNeedsHighlight(now.AddTicks(-1), now));
        Assert.False(MainWindow.SubscriptionExpiryNeedsHighlight(null, now));
    }

    [Fact]
    public void Device_limit_highlight_requires_a_positive_reached_limit()
    {
        Assert.True(MainWindow.SubscriptionDeviceLimitReached(10, 10));
        Assert.True(MainWindow.SubscriptionDeviceLimitReached(11, 10));
        Assert.False(MainWindow.SubscriptionDeviceLimitReached(9, 10));
        Assert.False(MainWindow.SubscriptionDeviceLimitReached(10, null));
        Assert.False(MainWindow.SubscriptionDeviceLimitReached(10, 0));
    }

    [Theory]
    [InlineData("ultra", "deytt. ultra")]
    [InlineData("DEYTT.Ultra", "deytt. ultra")]
    [InlineData(" deytt./ultra ", "deytt. ultra")]
    [InlineData("Standard", "Standard")]
    public void Tariff_display_name_normalizes_ultra_aliases(string input, string expected)
    {
        Assert.Equal(expected, MainWindow.NormalizeTariffDisplayName(input));
    }

    private static QaHomeFixture Fixture(string tab = "home", bool signedIn = false,
        bool routeProbeInProgress = false, TelegramSubscription? profileSubscription = null,
        string selectedRoute = "nl-hysteria2",
        string state = "disconnected", string? expandedRouteCountry = null,
        bool amneziaActive = false, int amneziaClients = 0, bool includeAmneziaRoute = false)
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
        if (includeAmneziaRoute)
            routes.Add(new WindowsRoute("de-awg31", "qa:de-awg31", "DE", "Германия", "🇩🇪", "AWG31",
                "AmneziaWG 3.1"));
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

