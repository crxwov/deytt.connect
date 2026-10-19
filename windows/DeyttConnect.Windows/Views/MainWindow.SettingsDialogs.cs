using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private sealed record RunningAppInfo(string ProcessName, string DisplayName, bool HasWindow);

    private static List<RunningAppInfo> GetRunningApplications()
    {
        var result = new List<RunningAppInfo>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        var systemProcesses = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "System", "Idle", "Registry", "smss", "csrss", "wininit", "services",
            "lsass", "svchost", "fontdrvhost", "winlogon", "dwm", "conhost",
            "sihost", "taskhostw", "explorer", "ctfmon", "SecurityHealthSystray",
            "SecurityHealthService", "SearchHost", "StartMenuExperienceHost",
            "ShellExperienceHost", "RuntimeBroker", "audiodg", "wlanext",
            "DeyttConnect.Windows", "DeyttConnect.Windows.Service", "DeyttVpnEngine"
        };

        try
        {
            var processes = System.Diagnostics.Process.GetProcesses();
            foreach (var p in processes)
            {
                try
                {
                    var name = p.ProcessName;
                    if (string.IsNullOrWhiteSpace(name) || systemProcesses.Contains(name))
                        continue;

                    var exeName = name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? name : name + ".exe";
                    if (!seen.Add(exeName))
                        continue;

                    string? windowTitle = null;
                    try
                    {
                        if (p.MainWindowHandle != IntPtr.Zero && !string.IsNullOrWhiteSpace(p.MainWindowTitle))
                            windowTitle = p.MainWindowTitle.Trim();
                    }
                    catch
                    {
                    }

                    var hasWindow = !string.IsNullOrWhiteSpace(windowTitle);
                    var displayName = hasWindow ? windowTitle! : exeName;
                    if (displayName.Length > 45)
                        displayName = displayName.Substring(0, 42) + "…";

                    result.Add(new RunningAppInfo(exeName, displayName, hasWindow));
                }
                catch
                {
                }
                finally
                {
                    try { p.Dispose(); } catch { }
                }
            }
        }
        catch
        {
        }

        return result
            .OrderByDescending(x => x.HasWindow)
            .ThenBy(x => x.DisplayName, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private IReadOnlyList<string>? ReadBypassSites()
    {
        if (_keysSnapshot?.ProfileJson is not { Length: > 0 } profile)
            return null;

        try
        {
            using var document = JsonDocument.Parse(profile);
            if (!document.RootElement.TryGetProperty("dns", out var dns) ||
                !dns.TryGetProperty("rules", out var rules) ||
                rules.ValueKind != JsonValueKind.Array)
                return null;

            var entries = rules.EnumerateArray().ToArray();
            if (entries.Length < 2)
                return null;

            static IEnumerable<string> ReadDomains(JsonElement rule, string property)
            {
                if (!rule.TryGetProperty(property, out var values) || values.ValueKind != JsonValueKind.Array)
                    return [];

                return values.EnumerateArray()
                    .Where(value => value.ValueKind == JsonValueKind.String)
                    .Select(value => value.GetString())
                    .Where(value => !string.IsNullOrWhiteSpace(value))
                    .Select(value => value!);
            }

            var suffixes = ReadDomains(entries[0], "domain_suffix")
                .Select(domain => domain.StartsWith('.') ? $"*{domain}" : $"*.{domain}");
            var exact = ReadDomains(entries[1], "domain");
            return suffixes.Concat(exact).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private async void ShowSplitTunnelingInfo()
    {
        var baseSites = ReadBypassSites();
        var currentConfig = WindowsSplitTunnelStore.Load();
        var presets = new List<SplitTunnelPreset>(currentConfig.Presets ?? []);
        var activePresetId = currentConfig.ActivePresetId ?? "default";
        var customDomains = new List<string>(currentConfig.CustomDomains ?? []);
        var customProcesses = new List<string>(currentConfig.CustomProcesses ?? []);
        var currentMode = currentConfig.Mode ?? WindowsSplitTunnelConfig.ModeBypass;

        var rootStack = new StackPanel { Spacing = 16 };

        var subtitleText = DeyttTheme.TextBlock(
            Copy("Настройте, какой трафик направлять через VPN, а какой — напрямую через вашего провайдера.",
                "Configure which traffic goes through VPN and which goes directly through your ISP."),
            13, DeyttTheme.Muted);
        rootStack.Children.Add(subtitleText);

        Action? refreshAllDynamicUi = null;

        void SyncCurrentStateToPreset()
        {
            var idx = presets.FindIndex(p => string.Equals(p.Id, activePresetId, StringComparison.OrdinalIgnoreCase));
            if (idx >= 0)
            {
                presets[idx] = presets[idx] with
                {
                    Mode = currentMode,
                    CustomDomains = customDomains.ToArray(),
                    CustomProcesses = customProcesses.ToArray()
                };
            }
        }

        void SwitchPreset(string targetPresetId)
        {
            SyncCurrentStateToPreset();
            var target = presets.FirstOrDefault(p => string.Equals(p.Id, targetPresetId, StringComparison.OrdinalIgnoreCase));
            if (target is null) return;

            activePresetId = target.Id;
            currentMode = target.Mode ?? WindowsSplitTunnelConfig.ModeBypass;
            customDomains.Clear();
            customDomains.AddRange(target.CustomDomains ?? []);
            customProcesses.Clear();
            customProcesses.AddRange(target.CustomProcesses ?? []);

            SaveAndRefresh();
        }

        void SaveAndRefresh()
        {
            SyncCurrentStateToPreset();
            WindowsSplitTunnelStore.Save(new WindowsSplitTunnelConfig(
                customDomains,
                customProcesses,
                currentMode,
                activePresetId,
                presets));
            refreshAllDynamicUi?.Invoke();
        }

        void DeletePreset(string presetId)
        {
            if (presets.Count <= 1) return;
            var idx = presets.FindIndex(p => string.Equals(p.Id, presetId, StringComparison.OrdinalIgnoreCase));
            if (idx < 0) return;

            presets.RemoveAt(idx);
            if (string.Equals(activePresetId, presetId, StringComparison.OrdinalIgnoreCase))
            {
                var nextPreset = presets[0];
                activePresetId = nextPreset.Id;
                currentMode = nextPreset.Mode ?? WindowsSplitTunnelConfig.ModeBypass;
                customDomains.Clear();
                customDomains.AddRange(nextPreset.CustomDomains ?? []);
                customProcesses.Clear();
                customProcesses.AddRange(nextPreset.CustomProcesses ?? []);
            }
            SaveAndRefresh();
        }

        // 0. Presets Section
        var presetsSection = new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.Surface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(14, 12)
        };

        var presetsCardStack = new StackPanel { Spacing = 10 };
        presetsSection.Child = presetsCardStack;

        var presetsHeader = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var presetsTitleRow = new StackPanel { Orientation = Avalonia.Layout.Orientation.Horizontal, Spacing = 8, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        presetsTitleRow.Children.Add(DeyttTheme.TextBlock(Copy("ПРЕСЕТЫ НАСТРОЕК", "PRESETS"), 11, DeyttTheme.Muted, FontWeight.Bold));
        var presetsCountBadge = DeyttTheme.TextBlock(Copy($"{presets.Count}", $"{presets.Count}"), 11, DeyttTheme.Blue, FontWeight.SemiBold);
        presetsTitleRow.Children.Add(presetsCountBadge);
        presetsHeader.Children.Add(presetsTitleRow);

        var addPresetBtn = new Button
        {
            Content = Copy("+ Новый пресет", "+ New preset"),
            Classes = { "deytt-button" },
            Background = Brushes.Transparent,
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            Foreground = DeyttTheme.Brush(DeyttTheme.Blue),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(10, 4),
            FontSize = 11,
            FontWeight = FontWeight.SemiBold,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };
        presetsHeader.Children.Add(addPresetBtn);
        Grid.SetColumn(addPresetBtn, 1);
        presetsCardStack.Children.Add(presetsHeader);

        // Inline Add Preset Form
        var addPresetRow = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto,Auto"),
            ColumnSpacing = 6,
            IsVisible = false
        };
        var presetNameBox = new TextBox
        {
            PlaceholderText = Copy("Название (например: Игры, Работа...)", "Preset name (e.g. Gaming, Work...)"),
            FontSize = 12,
            CornerRadius = new CornerRadius(8),
            MinHeight = 32,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center
        };
        var confirmAddPresetBtn = new Button
        {
            Content = Copy("Создать", "Create"),
            Classes = { "deytt-button" },
            Background = DeyttTheme.Brush(DeyttTheme.Blue),
            Foreground = Brushes.White,
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(12, 4),
            FontSize = 11,
            FontWeight = FontWeight.SemiBold,
            MinHeight = 32,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };
        var cancelAddPresetBtn = new Button
        {
            Content = Copy("Отмена", "Cancel"),
            Classes = { "deytt-button" },
            Background = Brushes.Transparent,
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            Foreground = DeyttTheme.Brush(DeyttTheme.Muted),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(10, 4),
            FontSize = 11,
            MinHeight = 32,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };

        void DoAddPreset()
        {
            var name = presetNameBox.Text?.Trim();
            if (!string.IsNullOrWhiteSpace(name))
            {
                var newId = Guid.NewGuid().ToString("N")[..8];
                var newPreset = new SplitTunnelPreset(
                    newId,
                    name,
                    WindowsSplitTunnelConfig.ModeBypass,
                    [],
                    []);
                presets.Add(newPreset);
                presetNameBox.Text = "";
                addPresetRow.IsVisible = false;
                SwitchPreset(newId);
            }
        }

        confirmAddPresetBtn.Click += (_, _) => DoAddPreset();
        presetNameBox.KeyDown += (_, e) =>
        {
            if (e.Key == Avalonia.Input.Key.Enter)
            {
                e.Handled = true;
                DoAddPreset();
            }
            else if (e.Key == Avalonia.Input.Key.Escape)
            {
                e.Handled = true;
                addPresetRow.IsVisible = false;
            }
        };
        cancelAddPresetBtn.Click += (_, _) =>
        {
            presetNameBox.Text = "";
            addPresetRow.IsVisible = false;
        };
        addPresetBtn.Click += (_, _) =>
        {
            addPresetRow.IsVisible = !addPresetRow.IsVisible;
            if (addPresetRow.IsVisible)
                presetNameBox.Focus();
        };

        addPresetRow.Children.Add(presetNameBox);
        addPresetRow.Children.Add(confirmAddPresetBtn);
        addPresetRow.Children.Add(cancelAddPresetBtn);
        Grid.SetColumn(presetNameBox, 0);
        Grid.SetColumn(confirmAddPresetBtn, 1);
        Grid.SetColumn(cancelAddPresetBtn, 2);
        presetsCardStack.Children.Add(addPresetRow);

        var presetsWrapPanel = new WrapPanel { Orientation = Avalonia.Layout.Orientation.Horizontal };
        presetsCardStack.Children.Add(presetsWrapPanel);

        rootStack.Children.Add(presetsSection);

        // 1. Mode Selector (Segmented Cards)
        var modeSelectorGrid = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,*"),
            ColumnSpacing = 10,
        };

        Border? bypassModeCard = null;
        Border? vpnOnlyModeCard = null;
        TextBlock? bypassModeTitle = null;
        TextBlock? vpnOnlyModeTitle = null;
        TextBlock? bypassModeCheck = null;
        TextBlock? vpnOnlyModeCheck = null;

        // Card 1: Bypass mode
        var bypassHeader = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        bypassModeTitle = DeyttTheme.TextBlock(Copy("Обход VPN для списка", "Bypass VPN for list"), 13, weight: FontWeight.SemiBold);
        bypassModeCheck = DeyttTheme.TextBlock("✓", 13, DeyttTheme.Blue, FontWeight.Bold);
        bypassHeader.Children.Add(bypassModeTitle);
        bypassHeader.Children.Add(bypassModeCheck);
        Grid.SetColumn(bypassModeTitle, 0);
        Grid.SetColumn(bypassModeCheck, 1);

        var bypassStack = new StackPanel
        {
            Spacing = 4,
            Children =
            {
                bypassHeader,
                DeyttTheme.TextBlock(
                    Copy("Весь интернет через VPN. Сайты и программы из списка идут напрямую.",
                        "All traffic through VPN. Sites and apps in the list go direct."),
                    11, DeyttTheme.Muted)
            }
        };

        bypassModeCard = new Border
        {
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(14, 12),
            BorderThickness = new Thickness(1.5),
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand),
            Child = bypassStack,
        };
        bypassModeCard.PointerPressed += (_, _) =>
        {
            currentMode = WindowsSplitTunnelConfig.ModeBypass;
            SaveAndRefresh();
        };

        // Card 2: VPN only mode
        var vpnOnlyHeader = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        vpnOnlyModeTitle = DeyttTheme.TextBlock(Copy("VPN только для списка", "VPN only for list"), 13, weight: FontWeight.SemiBold);
        vpnOnlyModeCheck = DeyttTheme.TextBlock("✓", 13, DeyttTheme.Blue, FontWeight.Bold);
        vpnOnlyHeader.Children.Add(vpnOnlyModeTitle);
        vpnOnlyHeader.Children.Add(vpnOnlyModeCheck);
        Grid.SetColumn(vpnOnlyModeTitle, 0);
        Grid.SetColumn(vpnOnlyModeCheck, 1);

        var vpnOnlyStack = new StackPanel
        {
            Spacing = 4,
            Children =
            {
                vpnOnlyHeader,
                DeyttTheme.TextBlock(
                    Copy("Весь интернет напрямую. Через VPN идут только сайты и программы из списка.",
                        "All traffic direct. Only sites and apps in the list go through VPN."),
                    11, DeyttTheme.Muted)
            }
        };

        vpnOnlyModeCard = new Border
        {
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(14, 12),
            BorderThickness = new Thickness(1.5),
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand),
            Child = vpnOnlyStack,
        };
        vpnOnlyModeCard.PointerPressed += (_, _) =>
        {
            currentMode = WindowsSplitTunnelConfig.ModeVpnOnly;
            SaveAndRefresh();
        };

        modeSelectorGrid.Children.Add(bypassModeCard);
        modeSelectorGrid.Children.Add(vpnOnlyModeCard);
        Grid.SetColumn(bypassModeCard, 0);
        Grid.SetColumn(vpnOnlyModeCard, 1);
        rootStack.Children.Add(modeSelectorGrid);

        void UpdateModeCardsVisual()
        {
            var isVpnOnly = string.Equals(currentMode, WindowsSplitTunnelConfig.ModeVpnOnly, StringComparison.OrdinalIgnoreCase);
            if (!isVpnOnly)
            {
                bypassModeCard!.Background = DeyttTheme.Brush(DeyttTheme.Selected);
                bypassModeCard.BorderBrush = DeyttTheme.Brush(DeyttTheme.SelectedLine);
                bypassModeTitle!.Foreground = DeyttTheme.Brush(DeyttTheme.Blue);
                bypassModeCheck!.IsVisible = true;

                vpnOnlyModeCard!.Background = DeyttTheme.Brush(DeyttTheme.Surface);
                vpnOnlyModeCard.BorderBrush = DeyttTheme.Brush(DeyttTheme.Line);
                vpnOnlyModeTitle!.Foreground = DeyttTheme.Brush(DeyttTheme.Text);
                vpnOnlyModeCheck!.IsVisible = false;
            }
            else
            {
                bypassModeCard!.Background = DeyttTheme.Brush(DeyttTheme.Surface);
                bypassModeCard.BorderBrush = DeyttTheme.Brush(DeyttTheme.Line);
                bypassModeTitle!.Foreground = DeyttTheme.Brush(DeyttTheme.Text);
                bypassModeCheck!.IsVisible = false;

                vpnOnlyModeCard!.Background = DeyttTheme.Brush(DeyttTheme.Selected);
                vpnOnlyModeCard.BorderBrush = DeyttTheme.Brush(DeyttTheme.SelectedLine);
                vpnOnlyModeTitle!.Foreground = DeyttTheme.Brush(DeyttTheme.Blue);
                vpnOnlyModeCheck!.IsVisible = true;
            }
        }

        // 2. Base Rules Card (Lock icon, chips, subtle badge)
        var baseRulesSection = new StackPanel { Spacing = 8 };
        var baseHeaderGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var baseTitleRow = new StackPanel
        {
            Orientation = Avalonia.Layout.Orientation.Horizontal,
            Spacing = 6,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Children =
            {
                DeyttTheme.TextBlock("🔒", 12),
                DeyttTheme.RawTextBlock(Copy("Базовые правила подписки", "Base subscription rules"), 12, DeyttTheme.Text, FontWeight.SemiBold),
            }
        };
        baseHeaderGrid.Children.Add(baseTitleRow);
        Grid.SetColumn(baseTitleRow, 0);

        var baseBadge = new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.Selected),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.SelectedLine),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(8, 2),
            Child = DeyttTheme.TextBlock(Copy("всегда без vpn", "always direct"), 10, DeyttTheme.Mint, FontWeight.SemiBold, wrap: false)
        };
        baseHeaderGrid.Children.Add(baseBadge);
        Grid.SetColumn(baseBadge, 1);
        baseRulesSection.Children.Add(baseHeaderGrid);

        var chipsWrap = new WrapPanel
        {
            Orientation = Avalonia.Layout.Orientation.Horizontal,
        };
        if (baseSites is not null && baseSites.Count > 0)
        {
            foreach (var site in baseSites)
            {
                var chip = new Border
                {
                    Background = DeyttTheme.Brush(DeyttTheme.Surface2),
                    BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(8),
                    Padding = new Thickness(8, 3),
                    Margin = new Thickness(0, 0, 6, 6),
                    Child = DeyttTheme.TextBlock(site, 11, DeyttTheme.Muted, family: DeyttTheme.JetBrainsMono, wrap: false)
                };
                chipsWrap.Children.Add(chip);
            }
        }
        else
        {
            chipsWrap.Children.Add(DeyttTheme.TextBlock(Copy("Правила подписки не загружены.", "Subscription rules not loaded."), 12, DeyttTheme.Amber));
        }

        var baseCardContent = new StackPanel
        {
            Spacing = 8,
            Children =
            {
                chipsWrap,
                DeyttTheme.TextBlock(
                    Copy("Встроены в конфигурацию подписки для стабильной работы российских сервисов и госуслуг.",
                        "Built into subscription to ensure reliable access to essential services."),
                    11, DeyttTheme.Muted)
            }
        };
        baseRulesSection.Children.Add(DeyttTheme.Card(baseCardContent, DeyttTheme.Surface, DeyttTheme.Line, 14, new Thickness(14, 12)));
        rootStack.Children.Add(baseRulesSection);

        // 3. Custom Domains Section
        var domainsSection = new StackPanel { Spacing = 8 };
        var domainsHeaderGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var domainsTitle = DeyttTheme.TextBlock(string.Empty, 12, DeyttTheme.Blue, FontWeight.SemiBold);
        domainsHeaderGrid.Children.Add(domainsTitle);
        Grid.SetColumn(domainsTitle, 0);

        var domainsCountBadge = DeyttTheme.TextBlock(string.Empty, 11, DeyttTheme.Muted);
        domainsHeaderGrid.Children.Add(domainsCountBadge);
        Grid.SetColumn(domainsCountBadge, 1);
        domainsSection.Children.Add(domainsHeaderGrid);

        var domainInputGrid = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            ColumnSpacing = 8,
        };
        var domainTextBox = new TextBox
        {
            PlaceholderText = Copy("например: mydomain.com или *.domain.ru", "e.g. mydomain.com or *.domain.ru"),
            FontSize = 13,
            FontFamily = DeyttTheme.JetBrainsMono,
            Height = 40,
            CornerRadius = new CornerRadius(10),
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        domainInputGrid.Children.Add(domainTextBox);
        Grid.SetColumn(domainTextBox, 0);

        var addDomainBtn = new Button
        {
            Content = Copy("+ Добавить", "+ Add"),
            Height = 40,
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(16, 0),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            FontWeight = FontWeight.SemiBold,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };

        void AddDomainAction()
        {
            var normalized = WindowsSplitTunnelStore.NormalizeDomain(domainTextBox.Text);
            if (!string.IsNullOrWhiteSpace(normalized) && !customDomains.Contains(normalized, StringComparer.OrdinalIgnoreCase))
            {
                customDomains.Add(normalized);
                domainTextBox.Text = string.Empty;
                SaveAndRefresh();
            }
        }
        addDomainBtn.Click += (_, _) => AddDomainAction();
        domainTextBox.KeyDown += (_, e) =>
        {
            if (e.Key == Avalonia.Input.Key.Enter)
            {
                AddDomainAction();
                e.Handled = true;
            }
        };
        domainInputGrid.Children.Add(addDomainBtn);
        Grid.SetColumn(addDomainBtn, 1);
        domainsSection.Children.Add(domainInputGrid);

        var domainsListStack = new StackPanel { Spacing = 6 };
        domainsSection.Children.Add(new ScrollViewer
        {
            Content = domainsListStack,
            MaxHeight = 160,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled
        });
        rootStack.Children.Add(domainsSection);

        // 4. Custom Processes Section
        var processSection = new StackPanel { Spacing = 8 };
        var processHeaderGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var processTitle = DeyttTheme.TextBlock(string.Empty, 12, DeyttTheme.Blue, FontWeight.SemiBold);
        processHeaderGrid.Children.Add(processTitle);
        Grid.SetColumn(processTitle, 0);

        var processCountBadge = DeyttTheme.TextBlock(string.Empty, 11, DeyttTheme.Muted);
        processHeaderGrid.Children.Add(processCountBadge);
        Grid.SetColumn(processCountBadge, 1);
        processSection.Children.Add(processHeaderGrid);

        var processDesc = DeyttTheme.TextBlock(string.Empty, 11, DeyttTheme.Muted);
        processSection.Children.Add(processDesc);

        // Process Action Buttons (Side by Side)
        var processActionsGrid = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,*"),
            ColumnSpacing = 8,
        };

        var pickRunningBtn = new Button
        {
            Content = Copy("⚡ Выбрать из запущенных", "⚡ Pick from running apps"),
            Height = 38,
            Background = DeyttTheme.Brush(DeyttTheme.Selected),
            Foreground = DeyttTheme.Brush(DeyttTheme.Blue),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.SelectedLine),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(10),
            FontWeight = FontWeight.SemiBold,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };

        var browseFileBtn = new Button
        {
            Content = Copy("📁 Выбрать .exe файл…", "📁 Browse .exe file…"),
            Height = 38,
            Background = DeyttTheme.Brush(DeyttTheme.Surface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(10),
            FontWeight = FontWeight.SemiBold,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };

        processActionsGrid.Children.Add(pickRunningBtn);
        processActionsGrid.Children.Add(browseFileBtn);
        Grid.SetColumn(pickRunningBtn, 0);
        Grid.SetColumn(browseFileBtn, 1);
        processSection.Children.Add(processActionsGrid);

        // Running processes selector panel
        var runningPickerCard = new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.Surface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(12),
            IsVisible = false
        };

        var runningPickerContent = new StackPanel { Spacing = 8 };
        var searchProcessBox = new TextBox
        {
            PlaceholderText = Copy("Поиск по названию окна или имени процесса...", "Search by window title or process name..."),
            FontSize = 12,
            FontFamily = DeyttTheme.JetBrainsMono,
            Height = 36,
            CornerRadius = new CornerRadius(8),
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        runningPickerContent.Children.Add(searchProcessBox);

        var runningItemsStack = new StackPanel { Spacing = 4 };
        runningPickerContent.Children.Add(new ScrollViewer
        {
            Content = runningItemsStack,
            MaxHeight = 180,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto
        });
        runningPickerCard.Child = runningPickerContent;

        void RenderRunningList(string filter)
        {
            runningItemsStack.Children.Clear();
            var allRunning = GetRunningApplications();
            var filtered = string.IsNullOrWhiteSpace(filter)
                ? allRunning
                : allRunning.Where(a =>
                    a.DisplayName.Contains(filter, StringComparison.OrdinalIgnoreCase) ||
                    a.ProcessName.Contains(filter, StringComparison.OrdinalIgnoreCase)).ToList();

            if (filtered.Count == 0)
            {
                runningItemsStack.Children.Add(DeyttTheme.TextBlock(
                    Copy("Ничего не найдено", "No running applications found"), 12, DeyttTheme.Muted));
                return;
            }

            foreach (var app in filtered)
            {
                var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
                var textPanel = new StackPanel { Spacing = 1 };
                textPanel.Children.Add(DeyttTheme.RawTextBlock(app.DisplayName, 12, DeyttTheme.Text, FontWeight.SemiBold));
                textPanel.Children.Add(DeyttTheme.RawTextBlock(app.ProcessName, 11, DeyttTheme.Muted, family: DeyttTheme.JetBrainsMono));
                row.Children.Add(textPanel);
                Grid.SetColumn(textPanel, 0);

                var isAlreadyAdded = customProcesses.Contains(app.ProcessName, StringComparer.OrdinalIgnoreCase);
                if (isAlreadyAdded)
                {
                    var addedLabel = DeyttTheme.TextBlock(Copy("✓ Добавлен", "✓ Added"), 11, DeyttTheme.Mint, FontWeight.SemiBold);
                    addedLabel.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                    row.Children.Add(addedLabel);
                    Grid.SetColumn(addedLabel, 1);
                }
                else
                {
                    var addBtn = new Button
                    {
                        Content = Copy("+ Добавить", "+ Add"),
                        FontSize = 11,
                        FontWeight = FontWeight.SemiBold,
                        Padding = new Thickness(10, 4),
                        CornerRadius = new CornerRadius(6),
                        Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                    };
                    addBtn.Click += (_, _) =>
                    {
                        if (!customProcesses.Contains(app.ProcessName, StringComparer.OrdinalIgnoreCase))
                        {
                            customProcesses.Add(app.ProcessName);
                            SaveAndRefresh();
                            RenderRunningList(searchProcessBox.Text ?? string.Empty);
                        }
                    };
                    row.Children.Add(addBtn);
                    Grid.SetColumn(addBtn, 1);
                }

                runningItemsStack.Children.Add(DeyttTheme.Card(row, DeyttTheme.Surface2, DeyttTheme.Line, 8, new Thickness(10, 6)));
            }
        }

        searchProcessBox.PropertyChanged += (_, e) =>
        {
            if (e.Property == TextBox.TextProperty)
                RenderRunningList(searchProcessBox.Text ?? string.Empty);
        };

        pickRunningBtn.Click += (_, _) =>
        {
            runningPickerCard.IsVisible = !runningPickerCard.IsVisible;
            if (runningPickerCard.IsVisible)
                RenderRunningList(searchProcessBox.Text ?? string.Empty);
        };

        browseFileBtn.Click += async (_, _) =>
        {
            var topLevel = TopLevel.GetTopLevel(this);
            if (topLevel is null) return;
            var files = await topLevel.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
            {
                Title = Copy("Выберите приложение для правила", "Select application for rule"),
                AllowMultiple = false,
                FileTypeFilter = [new FilePickerFileType("Executable (*.exe)") { Patterns = ["*.exe"] }]
            });
            if (files.Count > 0)
            {
                var fileName = files[0].Name;
                var normalized = WindowsSplitTunnelStore.NormalizeProcess(fileName);
                if (!string.IsNullOrWhiteSpace(normalized) && !customProcesses.Contains(normalized, StringComparer.OrdinalIgnoreCase))
                {
                    customProcesses.Add(normalized);
                    SaveAndRefresh();
                    if (runningPickerCard.IsVisible)
                        RenderRunningList(searchProcessBox.Text ?? string.Empty);
                }
            }
        };

        processSection.Children.Add(runningPickerCard);

        // Manual process input
        var processInputGrid = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            ColumnSpacing = 8,
        };
        var processTextBox = new TextBox
        {
            PlaceholderText = Copy("или введите имя процесса вручную (например: discord.exe)", "or enter process name manually: discord.exe"),
            FontSize = 12,
            FontFamily = DeyttTheme.JetBrainsMono,
            Height = 38,
            CornerRadius = new CornerRadius(10),
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        processInputGrid.Children.Add(processTextBox);
        Grid.SetColumn(processTextBox, 0);

        var addProcessBtn = new Button
        {
            Content = Copy("+ Добавить", "+ Add"),
            Height = 38,
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(16, 0),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            FontWeight = FontWeight.SemiBold,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };

        void AddProcessAction()
        {
            var normalized = WindowsSplitTunnelStore.NormalizeProcess(processTextBox.Text);
            if (!string.IsNullOrWhiteSpace(normalized) && !customProcesses.Contains(normalized, StringComparer.OrdinalIgnoreCase))
            {
                customProcesses.Add(normalized);
                processTextBox.Text = string.Empty;
                SaveAndRefresh();
                if (runningPickerCard.IsVisible)
                    RenderRunningList(searchProcessBox.Text ?? string.Empty);
            }
        }
        addProcessBtn.Click += (_, _) => AddProcessAction();
        processTextBox.KeyDown += (_, e) =>
        {
            if (e.Key == Avalonia.Input.Key.Enter)
            {
                AddProcessAction();
                e.Handled = true;
            }
        };
        processInputGrid.Children.Add(addProcessBtn);
        Grid.SetColumn(addProcessBtn, 1);
        processSection.Children.Add(processInputGrid);

        var processListStack = new StackPanel { Spacing = 6 };
        processSection.Children.Add(new ScrollViewer
        {
            Content = processListStack,
            MaxHeight = 160,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled
        });
        rootStack.Children.Add(processSection);

        // Dynamic Refresh Function
        refreshAllDynamicUi = () =>
        {
            UpdateModeCardsVisual();
            var isVpnOnly = string.Equals(currentMode, WindowsSplitTunnelConfig.ModeVpnOnly, StringComparison.OrdinalIgnoreCase);

            domainsTitle.Text = isVpnOnly
                ? Copy("САЙТЫ ЧЕРЕЗ VPN", "SITES THROUGH VPN")
                : Copy("САЙТЫ В ОБХОД VPN", "SITES BYPASSING VPN");
            domainsCountBadge.Text = Copy($"{customDomains.Count} сайтов", $"{customDomains.Count} sites");

            processTitle.Text = isVpnOnly
                ? Copy("ПРИЛОЖЕНИЯ ЧЕРЕЗ VPN", "APPLICATIONS THROUGH VPN")
                : Copy("ПРИЛОЖЕНИЯ В ОБХОД VPN", "APPLICATIONS BYPASSING VPN");
            processCountBadge.Text = Copy($"{customProcesses.Count} приложений", $"{customProcesses.Count} apps");

            processDesc.Text = isVpnOnly
                ? Copy("Весь исходящий трафик выбранных процессов направляется через VPN.",
                    "All outgoing traffic from selected processes goes through VPN.")
                : Copy("Весь исходящий трафик выбранных процессов направляется напрямую в обход VPN на любые серверы и адреса.",
                    "All outgoing traffic from selected processes goes directly without VPN to any server or address.");

            // Refresh domains list
            domainsListStack.Children.Clear();
            if (customDomains.Count == 0)
            {
                domainsListStack.Children.Add(DeyttTheme.Card(
                    DeyttTheme.TextBlock(Copy("Список пуст. Введите домен выше и нажмите «+ Добавить».",
                        "List is empty. Enter domain above and click «+ Add»."), 12, DeyttTheme.Muted),
                    DeyttTheme.Surface, DeyttTheme.Line, 8, new Thickness(12, 10)));
            }
            else
            {
                foreach (var d in customDomains.OrderBy(x => x, StringComparer.OrdinalIgnoreCase).ToArray())
                {
                    var row = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), ColumnSpacing = 8 };
                    var icon = DeyttTheme.TextBlock("🌐", 12);
                    row.Children.Add(icon);
                    Grid.SetColumn(icon, 0);

                    var text = DeyttTheme.TextBlock(d, 12, DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
                    text.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                    row.Children.Add(text);
                    Grid.SetColumn(text, 1);

                    var deleteBtn = new Button
                    {
                        Content = "✕",
                        Background = Brushes.Transparent,
                        BorderThickness = new Thickness(0),
                        Foreground = DeyttTheme.Brush(DeyttTheme.Coral),
                        Padding = new Thickness(8, 2),
                        FontSize = 13,
                        FontWeight = FontWeight.SemiBold,
                        Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                    };
                    deleteBtn.Click += (_, _) =>
                    {
                        customDomains.Remove(d);
                        SaveAndRefresh();
                    };
                    row.Children.Add(deleteBtn);
                    Grid.SetColumn(deleteBtn, 2);

                    domainsListStack.Children.Add(DeyttTheme.Card(row, DeyttTheme.Surface, DeyttTheme.Line, 8, new Thickness(12, 6)));
                }
            }

            // Refresh process list
            processListStack.Children.Clear();
            if (customProcesses.Count == 0)
            {
                processListStack.Children.Add(DeyttTheme.Card(
                    DeyttTheme.TextBlock(Copy("Список пуст. Выберите процесс из запущенных или укажите .exe файл.",
                        "List is empty. Pick a running process or browse an .exe file."), 12, DeyttTheme.Muted),
                    DeyttTheme.Surface, DeyttTheme.Line, 8, new Thickness(12, 10)));
            }
            else
            {
                foreach (var p in customProcesses.OrderBy(x => x, StringComparer.OrdinalIgnoreCase).ToArray())
                {
                    var row = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), ColumnSpacing = 8 };
                    var icon = DeyttTheme.TextBlock("⚡", 12, DeyttTheme.Sky);
                    row.Children.Add(icon);
                    Grid.SetColumn(icon, 0);

                    var text = DeyttTheme.TextBlock(p, 12, DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
                    text.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                    row.Children.Add(text);
                    Grid.SetColumn(text, 1);

                    var deleteBtn = new Button
                    {
                        Content = "✕",
                        Background = Brushes.Transparent,
                        BorderThickness = new Thickness(0),
                        Foreground = DeyttTheme.Brush(DeyttTheme.Coral),
                        Padding = new Thickness(8, 2),
                        FontSize = 13,
                        FontWeight = FontWeight.SemiBold,
                        Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                    };
                    deleteBtn.Click += (_, _) =>
                    {
                        customProcesses.Remove(p);
                        SaveAndRefresh();
                        if (runningPickerCard.IsVisible)
                            RenderRunningList(searchProcessBox.Text ?? string.Empty);
                    };
                    row.Children.Add(deleteBtn);
                    Grid.SetColumn(deleteBtn, 2);

                    processListStack.Children.Add(DeyttTheme.Card(row, DeyttTheme.Surface, DeyttTheme.Line, 8, new Thickness(12, 6)));
                }
            }

            // Refresh Presets Chips
            presetsCountBadge.Text = $"{presets.Count}";
            presetsWrapPanel.Children.Clear();
            foreach (var pr in presets)
            {
                var isActive = string.Equals(pr.Id, activePresetId, StringComparison.OrdinalIgnoreCase);
                var isVpnOnlyMode = string.Equals(pr.Mode, WindowsSplitTunnelConfig.ModeVpnOnly, StringComparison.OrdinalIgnoreCase);
                var ruleCount = (pr.CustomDomains?.Count ?? 0) + (pr.CustomProcesses?.Count ?? 0);

                var chipCard = new Border
                {
                    CornerRadius = new CornerRadius(10),
                    BorderThickness = new Thickness(isActive ? 1.5 : 1),
                    BorderBrush = DeyttTheme.Brush(isActive ? DeyttTheme.SelectedLine : DeyttTheme.Line),
                    Background = DeyttTheme.Brush(isActive ? DeyttTheme.Selected : DeyttTheme.Surface2),
                    Padding = new Thickness(10, 6),
                    Margin = new Thickness(0, 0, 8, 8),
                    Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                };

                var chipContent = new StackPanel
                {
                    Orientation = Avalonia.Layout.Orientation.Horizontal,
                    Spacing = 6,
                    VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center
                };

                if (isActive)
                {
                    chipContent.Children.Add(DeyttTheme.TextBlock("✓", 11, DeyttTheme.Blue, FontWeight.Bold));
                }

                chipContent.Children.Add(DeyttTheme.TextBlock(
                    pr.Name,
                    12,
                    isActive ? DeyttTheme.Blue : DeyttTheme.Text,
                    FontWeight.SemiBold));

                // Small badge showing mode (обход / vpn)
                var modeBadge = DeyttTheme.Card(
                    DeyttTheme.TextBlock(
                        isVpnOnlyMode ? Copy("vpn", "vpn") : Copy("обход", "bypass"),
                        9,
                        DeyttTheme.Muted),
                    DeyttTheme.Surface,
                    DeyttTheme.Line,
                    4,
                    new Thickness(5, 1));
                chipContent.Children.Add(modeBadge);

                if (ruleCount > 0)
                {
                    chipContent.Children.Add(DeyttTheme.TextBlock(
                        $"{ruleCount}",
                        10,
                        DeyttTheme.Muted));
                }

                // Delete button (only if more than 1 preset exists)
                if (presets.Count > 1)
                {
                    var delBtn = new Button
                    {
                        Content = "✕",
                        Classes = { "deytt-button" },
                        Background = Brushes.Transparent,
                        Foreground = DeyttTheme.Brush(DeyttTheme.Muted),
                        Padding = new Thickness(4, 0),
                        FontSize = 10,
                        Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                    };
                    var capturedId = pr.Id;
                    delBtn.Click += (_, e) =>
                    {
                        e.Handled = true;
                        DeletePreset(capturedId);
                    };
                    chipContent.Children.Add(delBtn);
                }

                chipCard.Child = chipContent;

                var targetId = pr.Id;
                chipCard.PointerPressed += (_, _) =>
                {
                    if (!string.Equals(activePresetId, targetId, StringComparison.OrdinalIgnoreCase))
                    {
                        SwitchPreset(targetId);
                    }
                };

                presetsWrapPanel.Children.Add(chipCard);
            }
        };

        refreshAllDynamicUi();

        ShellContentDialog? dialog = null;
        var footerButton = DeyttTheme.PrimaryButton(Copy("Готово", "Done"), () =>
        {
            dialog?.Close();
        });

        await ShowContentInShellAsync(
            Copy("Раздельное туннелирование", "Split tunneling"),
            rootStack,
            720,
            shellDialog => dialog = shellDialog,
            footerButton);

        await ApplySplitTunnelRulesAsync();
    }
}
