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
            if (!_qaFixture)
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
        var presetsCardStack = new StackPanel { Spacing = 12 };
        var presetsSection = DeyttTheme.DialogPanel(presetsCardStack, 18, new Thickness(18, 16));

        var presetsHeader = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            RowDefinitions = new RowDefinitions("Auto"),
            RowSpacing = 8,
        };
        var presetsTitleRow = new StackPanel { Orientation = Avalonia.Layout.Orientation.Horizontal, Spacing = 8, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        presetsTitleRow.Children.Add(DeyttTheme.TextBlock(Copy("Сохранённые профили", "Saved presets"), 15, DeyttTheme.Text, FontWeight.SemiBold));
        var presetsCountBadge = DeyttTheme.TextBlock(Copy($"{presets.Count}", $"{presets.Count}"), 12, DeyttTheme.Muted, FontWeight.Medium);
        presetsTitleRow.Children.Add(presetsCountBadge);
        presetsHeader.Children.Add(presetsTitleRow);

        var addPresetBtn = new Button
        {
            Content = Copy("+ Добавить профиль", "+ Add preset"),
            Background = Brushes.Transparent,
            BorderBrush = DeyttTheme.Brush(DeyttTheme.DialogLine),
            BorderThickness = new Thickness(1),
            Foreground = DeyttTheme.Brush(DeyttTheme.DialogAccent),
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };
        Avalonia.Automation.AutomationProperties.SetAutomationId(addPresetBtn, "SplitPresetAdd");
        DeyttTheme.StyleDialogButton(addPresetBtn);
        addPresetBtn.Foreground = DeyttTheme.Brush(DeyttTheme.DialogAccent);
        presetsHeader.Children.Add(addPresetBtn);
        Grid.SetColumn(addPresetBtn, 1);
        presetsCardStack.Children.Add(presetsHeader);

        // Inline Add Preset Form
        var addPresetRow = new WrapPanel
        {
            Orientation = Avalonia.Layout.Orientation.Horizontal,
            IsVisible = false
        };
        var presetNameBox = new TextBox
        {
            PlaceholderText = Copy("Название (например: Игры, Работа...)", "Preset name (e.g. Gaming, Work...)"),
            Width = 300,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center
        };
        Avalonia.Automation.AutomationProperties.SetAutomationId(presetNameBox, "SplitPresetName");
        DeyttTheme.StyleDialogInput(presetNameBox);
        presetNameBox.Margin = new Thickness(0, 0, 8, 8);
        var confirmAddPresetBtn = new Button
        {
            Content = Copy("Создать", "Create"),
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };
        Avalonia.Automation.AutomationProperties.SetAutomationId(confirmAddPresetBtn, "SplitPresetCreate");
        DeyttTheme.StyleDialogButton(confirmAddPresetBtn, primary: true);
        confirmAddPresetBtn.Margin = new Thickness(0, 0, 8, 8);
        var cancelAddPresetBtn = new Button
        {
            Content = Copy("Отмена", "Cancel"),
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };
        Avalonia.Automation.AutomationProperties.SetAutomationId(cancelAddPresetBtn, "SplitPresetCancel");
        DeyttTheme.StyleDialogButton(cancelAddPresetBtn);
        cancelAddPresetBtn.Margin = new Thickness(0, 0, 0, 8);

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
        presetsCardStack.Children.Add(addPresetRow);

        var presetsWrapPanel = new WrapPanel { Orientation = Avalonia.Layout.Orientation.Horizontal };
        presetsCardStack.Children.Add(presetsWrapPanel);

        rootStack.Children.Add(presetsSection);

        // 1. Mode selector
        var modeSelectorGrid = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,*"),
            ColumnSpacing = 10,
            RowSpacing = 10,
        };

        Button? bypassModeCard = null;
        Button? vpnOnlyModeCard = null;
        Border? bypassModeSurface = null;
        Border? vpnOnlyModeSurface = null;
        TextBlock? bypassModeTitle = null;
        TextBlock? vpnOnlyModeTitle = null;
        TextBlock? bypassModeCheck = null;
        TextBlock? vpnOnlyModeCheck = null;

        var bypassHeader = new StackPanel { Spacing = 4 };
        bypassModeTitle = DeyttTheme.TextBlock(Copy("Обход VPN для списка", "Bypass VPN for list"), 13, weight: FontWeight.SemiBold);
        bypassHeader.Children.Add(bypassModeTitle);
        bypassHeader.Children.Add(DeyttTheme.TextBlock(
            Copy("Весь интернет через VPN. Сайты и программы в списке идут напрямую.",
                "All traffic through VPN. Listed sites and apps go direct."),
            12, DeyttTheme.Muted));

        var bypassContent = new StackPanel { Spacing = 8, Children = { bypassHeader } };
        bypassModeCard = DeyttTheme.DialogChoice(bypassContent, false, () =>
        {
            currentMode = WindowsSplitTunnelConfig.ModeBypass;
            SaveAndRefresh();
        });
        bypassModeSurface = (Border)bypassModeCard.Content!;
        bypassModeCheck = (TextBlock)((Grid)bypassModeSurface.Child!).Children[1];

        var vpnOnlyHeader = new StackPanel { Spacing = 4 };
        vpnOnlyModeTitle = DeyttTheme.TextBlock(Copy("VPN только для списка", "VPN only for list"), 13, weight: FontWeight.SemiBold);
        vpnOnlyHeader.Children.Add(vpnOnlyModeTitle);
        vpnOnlyHeader.Children.Add(DeyttTheme.TextBlock(
            Copy("Весь интернет идёт напрямую. Только сайты и программы в списке идут через VPN.",
                "All traffic goes direct. Only listed sites and apps go through VPN."),
            12, DeyttTheme.Muted));

        var vpnOnlyContent = new StackPanel { Spacing = 8, Children = { vpnOnlyHeader } };
        vpnOnlyModeCard = DeyttTheme.DialogChoice(vpnOnlyContent, false, () =>
        {
            currentMode = WindowsSplitTunnelConfig.ModeVpnOnly;
            SaveAndRefresh();
        });
        Avalonia.Automation.AutomationProperties.SetAutomationId(bypassModeCard!, "SplitModeBypass");
        Avalonia.Automation.AutomationProperties.SetAutomationId(vpnOnlyModeCard!, "SplitModeVpnOnly");
        vpnOnlyModeSurface = (Border)vpnOnlyModeCard.Content!;
        vpnOnlyModeCheck = (TextBlock)((Grid)vpnOnlyModeSurface.Child!).Children[1];

        modeSelectorGrid.Children.Add(bypassModeCard);
        modeSelectorGrid.Children.Add(vpnOnlyModeCard);
        Grid.SetColumn(bypassModeCard, 0);
        Grid.SetColumn(vpnOnlyModeCard, 1);
        rootStack.Children.Add(modeSelectorGrid);

        void UpdateModeCardsVisual()
        {
            var isVpnOnly = string.Equals(currentMode, WindowsSplitTunnelConfig.ModeVpnOnly, StringComparison.OrdinalIgnoreCase);
            void Update(Button button, Border surface, TextBlock title, TextBlock check, bool selected)
            {
                surface.Background = selected ? DeyttTheme.Brush(DeyttTheme.DialogSelected) : DeyttTheme.DialogSurface;
                surface.BorderBrush = DeyttTheme.Brush(selected ? DeyttTheme.DialogSelectionLine : DeyttTheme.DialogLine);
                title.Foreground = DeyttTheme.Brush(selected ? DeyttTheme.Mint : DeyttTheme.Text);
                check.Text = selected ? "✓" : "○";
                check.Foreground = DeyttTheme.Brush(selected ? DeyttTheme.Mint : DeyttTheme.Muted);
                Avalonia.Automation.AutomationProperties.SetName(button,
                    title.Text + (selected ? Copy(", выбрано", ", selected") : ""));
            }

            Update(bypassModeCard!, bypassModeSurface!, bypassModeTitle!, bypassModeCheck!, !isVpnOnly);
            Update(vpnOnlyModeCard!, vpnOnlyModeSurface!, vpnOnlyModeTitle!, vpnOnlyModeCheck!, isVpnOnly);
        }

        // 2. Base rules are read-only and kept visually quiet.
        var baseRulesContent = new StackPanel { Spacing = 10 };
        var baseHeaderGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var baseTitleRow = new StackPanel
        {
            Orientation = Avalonia.Layout.Orientation.Horizontal,
            Spacing = 8,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Children =
            {
                DeyttTheme.TextBlock(Copy("Базовые правила подписки", "Base subscription rules"), 14, DeyttTheme.Text, FontWeight.SemiBold),
            }
        };
        baseHeaderGrid.Children.Add(baseTitleRow);
        Grid.SetColumn(baseTitleRow, 0);

        var baseBadge = new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.DialogSelected),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.DialogSelectionLine),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(10, 5),
            Child = DeyttTheme.TextBlock(Copy("Всегда напрямую", "Always direct"), 11, DeyttTheme.Mint, FontWeight.SemiBold, wrap: false)
        };
        baseHeaderGrid.Children.Add(baseBadge);
        Grid.SetColumn(baseBadge, 1);
        baseRulesContent.Children.Add(baseHeaderGrid);

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
                    Background = DeyttTheme.DialogSurface,
                    BorderBrush = DeyttTheme.Brush(DeyttTheme.DialogLine),
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(9),
                    Padding = new Thickness(10, 5),
                    Margin = new Thickness(0, 0, 6, 6),
                    Child = DeyttTheme.TextBlock(site, 11, DeyttTheme.Muted, wrap: false)
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
        baseRulesContent.Children.Add(baseCardContent);
        rootStack.Children.Add(DeyttTheme.DialogPanel(baseRulesContent, 18, new Thickness(18, 16)));

        // 3. Custom Domains Section
        var domainsSection = new StackPanel { Spacing = 8 };
        var domainsHeaderGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var domainsTitle = DeyttTheme.TextBlock(string.Empty, 15, DeyttTheme.Text, FontWeight.SemiBold);
        domainsHeaderGrid.Children.Add(domainsTitle);
        Grid.SetColumn(domainsTitle, 0);

        var domainsCountBadge = DeyttTheme.TextBlock(string.Empty, 12, DeyttTheme.Muted);
        domainsHeaderGrid.Children.Add(domainsCountBadge);
        Grid.SetColumn(domainsCountBadge, 1);
        domainsSection.Children.Add(domainsHeaderGrid);

        var domainInputGrid = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            RowDefinitions = new RowDefinitions("Auto"),
            ColumnSpacing = 8,
            RowSpacing = 8,
        };
        var domainTextBox = new TextBox
        {
            PlaceholderText = Copy("например: mydomain.com или *.domain.ru", "e.g. mydomain.com or *.domain.ru"),
            MinHeight = 44,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        DeyttTheme.StyleDialogInput(domainTextBox);
        Avalonia.Automation.AutomationProperties.SetAutomationId(domainTextBox, "SplitDomainInput");
        domainInputGrid.Children.Add(domainTextBox);
        Grid.SetColumn(domainTextBox, 0);

        var addDomainBtn = new Button
        {
            Content = Copy("+ Добавить", "+ Add"),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };
        DeyttTheme.StyleDialogButton(addDomainBtn);
        Avalonia.Automation.AutomationProperties.SetAutomationId(addDomainBtn, "SplitDomainAdd");

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
        rootStack.Children.Add(DeyttTheme.DialogPanel(domainsSection, 18, new Thickness(18, 16)));

        // 4. Custom Processes Section
        var processSection = new StackPanel { Spacing = 8 };
        var processHeaderGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        var processTitle = DeyttTheme.TextBlock(string.Empty, 15, DeyttTheme.Text, FontWeight.SemiBold);
        processHeaderGrid.Children.Add(processTitle);
        Grid.SetColumn(processTitle, 0);

        var processCountBadge = DeyttTheme.TextBlock(string.Empty, 12, DeyttTheme.Muted);
        processHeaderGrid.Children.Add(processCountBadge);
        Grid.SetColumn(processCountBadge, 1);
        processSection.Children.Add(processHeaderGrid);

        var processDesc = DeyttTheme.TextBlock(string.Empty, 12, DeyttTheme.Muted);
        processSection.Children.Add(processDesc);

        // Process Action Buttons (Side by Side)
        var processActionsGrid = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,*"),
            ColumnSpacing = 8,
        };

        var pickRunningBtn = new Button
        {
            Content = Copy("Выбрать запущенное приложение", "Pick a running app"),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };
        DeyttTheme.StyleDialogButton(pickRunningBtn);
        Avalonia.Automation.AutomationProperties.SetAutomationId(pickRunningBtn, "SplitRunningPickerToggle");
        pickRunningBtn.Background = DeyttTheme.Brush(DeyttTheme.DialogSelected);
        pickRunningBtn.Foreground = DeyttTheme.Brush(DeyttTheme.Mint);
        pickRunningBtn.BorderBrush = DeyttTheme.Brush(DeyttTheme.DialogSelectionLine);

        var browseFileBtn = new Button
        {
            Content = Copy("Обзор файла .exe…", "Browse for .exe…"),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };
        DeyttTheme.StyleDialogButton(browseFileBtn);
        Avalonia.Automation.AutomationProperties.SetAutomationId(browseFileBtn, "SplitProcessBrowse");

        processActionsGrid.Children.Add(pickRunningBtn);
        processActionsGrid.Children.Add(browseFileBtn);
        Grid.SetColumn(pickRunningBtn, 0);
        Grid.SetColumn(browseFileBtn, 1);
        processSection.Children.Add(processActionsGrid);

        // Running processes selector panel
        var runningPickerCard = DeyttTheme.DialogPanel(new StackPanel(), 16, new Thickness(16));
        runningPickerCard.IsVisible = false;

        var runningPickerContent = new StackPanel { Spacing = 8 };
        var searchProcessBox = new TextBox
        {
            PlaceholderText = Copy("Поиск по названию окна или имени процесса...", "Search by window title or process name..."),
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        DeyttTheme.StyleDialogInput(searchProcessBox);
        Avalonia.Automation.AutomationProperties.SetAutomationId(searchProcessBox, "SplitRunningSearch");
        searchProcessBox.MinHeight = 40;
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
                        Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                    };
                    DeyttTheme.StyleDialogButton(addBtn);
                    Avalonia.Automation.AutomationProperties.SetAutomationId(addBtn, $"SplitRunningAdd_{app.ProcessName}");
                    addBtn.MinHeight = 34;
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

                runningItemsStack.Children.Add(DeyttTheme.DialogPanel(row, 12, new Thickness(12, 8)));
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
            MinHeight = 44,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        DeyttTheme.StyleDialogInput(processTextBox);
        Avalonia.Automation.AutomationProperties.SetAutomationId(processTextBox, "SplitProcessInput");
        processInputGrid.Children.Add(processTextBox);
        Grid.SetColumn(processTextBox, 0);

        var addProcessBtn = new Button
        {
            Content = Copy("+ Добавить", "+ Add"),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };
        DeyttTheme.StyleDialogButton(addProcessBtn);
        Avalonia.Automation.AutomationProperties.SetAutomationId(addProcessBtn, "SplitProcessAdd");

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

        void ApplyResponsiveLayout(double contentWidth)
        {
            var compact = contentWidth < 600;
            modeSelectorGrid.ColumnDefinitions = new ColumnDefinitions(compact ? "*" : "*,*");
            modeSelectorGrid.RowDefinitions = new RowDefinitions(compact ? "Auto,Auto" : "Auto");
            modeSelectorGrid.ColumnSpacing = compact ? 0 : 10;
            modeSelectorGrid.RowSpacing = compact ? 8 : 10;
            Grid.SetColumn(bypassModeCard!, 0);
            Grid.SetRow(bypassModeCard!, 0);
            Grid.SetColumn(vpnOnlyModeCard!, compact ? 0 : 1);
            Grid.SetRow(vpnOnlyModeCard!, compact ? 1 : 0);

            presetsHeader.ColumnDefinitions = new ColumnDefinitions(compact ? "*" : "*,Auto");
            presetsHeader.RowDefinitions = new RowDefinitions(compact ? "Auto,Auto" : "Auto");
            Grid.SetColumn(presetsTitleRow, 0);
            Grid.SetRow(presetsTitleRow, 0);
            Grid.SetColumn(addPresetBtn, compact ? 0 : 1);
            Grid.SetRow(addPresetBtn, compact ? 1 : 0);
            addPresetBtn.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left;

            processActionsGrid.ColumnDefinitions = new ColumnDefinitions(compact ? "*" : "*,*");
            processActionsGrid.RowDefinitions = new RowDefinitions(compact ? "Auto,Auto" : "Auto");
            processActionsGrid.RowSpacing = compact ? 8 : 0;
            Grid.SetColumn(pickRunningBtn, 0);
            Grid.SetRow(pickRunningBtn, 0);
            Grid.SetColumn(browseFileBtn, compact ? 0 : 1);
            Grid.SetRow(browseFileBtn, compact ? 1 : 0);

            void ConfigureInputRow(Grid grid, Control field, Control action)
            {
                grid.ColumnDefinitions = new ColumnDefinitions(compact ? "*" : "*,Auto");
                grid.RowDefinitions = new RowDefinitions(compact ? "Auto,Auto" : "Auto");
                grid.RowSpacing = compact ? 8 : 0;
                Grid.SetColumn(field, 0);
                Grid.SetRow(field, 0);
                Grid.SetColumn(action, compact ? 0 : 1);
                Grid.SetRow(action, compact ? 1 : 0);
                action.HorizontalAlignment = compact
                    ? Avalonia.Layout.HorizontalAlignment.Stretch
                    : Avalonia.Layout.HorizontalAlignment.Left;
            }

            ConfigureInputRow(domainInputGrid, domainTextBox, addDomainBtn);
            ConfigureInputRow(processInputGrid, processTextBox, addProcessBtn);
        }

        rootStack.SizeChanged += (_, args) => ApplyResponsiveLayout(args.NewSize.Width);
        ApplyResponsiveLayout(720);
        processSection.Children.Add(processInputGrid);

        var processListStack = new StackPanel { Spacing = 6 };
        processSection.Children.Add(new ScrollViewer
        {
            Content = processListStack,
            MaxHeight = 160,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled
        });
        rootStack.Children.Add(DeyttTheme.DialogPanel(processSection, 18, new Thickness(18, 16)));

        // Dynamic Refresh Function
        refreshAllDynamicUi = () =>
        {
            UpdateModeCardsVisual();
            var isVpnOnly = string.Equals(currentMode, WindowsSplitTunnelConfig.ModeVpnOnly, StringComparison.OrdinalIgnoreCase);

            domainsTitle.Text = isVpnOnly
                ? Copy("Сайты через VPN", "Sites through VPN")
                : Copy("Сайты в обход VPN", "Sites bypassing VPN");
            domainsCountBadge.Text = Copy($"{customDomains.Count} сайтов", $"{customDomains.Count} sites");

            processTitle.Text = isVpnOnly
                ? Copy("Приложения через VPN", "Apps through VPN")
                : Copy("Приложения в обход VPN", "Apps bypassing VPN");
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
                domainsListStack.Children.Add(DeyttTheme.DialogPanel(
                    DeyttTheme.TextBlock(Copy("Список пуст. Введите домен выше и нажмите «+ Добавить».",
                        "List is empty. Enter a domain above and select «+ Add»."), 12, DeyttTheme.Muted),
                    12, new Thickness(12, 10)));
            }
            else
            {
                foreach (var d in customDomains.OrderBy(x => x, StringComparer.OrdinalIgnoreCase).ToArray())
                {
                    var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), ColumnSpacing = 8 };
                    var text = DeyttTheme.TextBlock(d, 12, DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
                    text.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                    row.Children.Add(text);
                    Grid.SetColumn(text, 0);

                    var deleteBtn = new Button
                    {
                        Content = "×",
                        Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                    };
                    DeyttTheme.StyleDialogButton(deleteBtn);
                    deleteBtn.Width = 36;
                    deleteBtn.MinHeight = 32;
                    deleteBtn.Padding = new Thickness(0);
                    deleteBtn.Foreground = DeyttTheme.Brush(DeyttTheme.Coral);
                    Avalonia.Automation.AutomationProperties.SetAutomationId(deleteBtn, $"SplitDomainRemove_{d}");
                    Avalonia.Automation.AutomationProperties.SetName(deleteBtn, Copy($"Удалить домен {d}", $"Remove domain {d}"));
                    deleteBtn.Click += (_, _) =>
                    {
                        customDomains.Remove(d);
                        SaveAndRefresh();
                    };
                    row.Children.Add(deleteBtn);
                    Grid.SetColumn(deleteBtn, 1);

                    domainsListStack.Children.Add(DeyttTheme.DialogPanel(row, 12, new Thickness(12, 6)));
                }
            }

            // Refresh process list
            processListStack.Children.Clear();
            if (customProcesses.Count == 0)
            {
                processListStack.Children.Add(DeyttTheme.DialogPanel(
                    DeyttTheme.TextBlock(Copy("Список пуст. Выберите процесс из запущенных или укажите .exe файл.",
                        "List is empty. Pick a running app or browse for an .exe file."), 12, DeyttTheme.Muted),
                    12, new Thickness(12, 10)));
            }
            else
            {
                foreach (var p in customProcesses.OrderBy(x => x, StringComparer.OrdinalIgnoreCase).ToArray())
                {
                    var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), ColumnSpacing = 8 };
                    var text = DeyttTheme.TextBlock(p, 12, DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
                    text.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                    row.Children.Add(text);
                    Grid.SetColumn(text, 0);

                    var deleteBtn = new Button
                    {
                        Content = "×",
                        Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                    };
                    DeyttTheme.StyleDialogButton(deleteBtn);
                    deleteBtn.Width = 36;
                    deleteBtn.MinHeight = 32;
                    deleteBtn.Padding = new Thickness(0);
                    deleteBtn.Foreground = DeyttTheme.Brush(DeyttTheme.Coral);
                    Avalonia.Automation.AutomationProperties.SetAutomationId(deleteBtn, $"SplitProcessRemove_{p}");
                    Avalonia.Automation.AutomationProperties.SetName(deleteBtn, Copy($"Удалить приложение {p}", $"Remove app {p}"));
                    deleteBtn.Click += (_, _) =>
                    {
                        customProcesses.Remove(p);
                        SaveAndRefresh();
                        if (runningPickerCard.IsVisible)
                            RenderRunningList(searchProcessBox.Text ?? string.Empty);
                    };
                    row.Children.Add(deleteBtn);
                    Grid.SetColumn(deleteBtn, 1);

                    processListStack.Children.Add(DeyttTheme.DialogPanel(row, 12, new Thickness(12, 6)));
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
                var choiceDetails = new StackPanel { Spacing = 4 };
                var presetName = DeyttTheme.TextBlock(pr.Name, 13, DeyttTheme.Text, FontWeight.SemiBold);
                presetName.MaxWidth = 230;
                choiceDetails.Children.Add(presetName);
                choiceDetails.Children.Add(DeyttTheme.TextBlock(Copy(
                    $"{(isVpnOnlyMode ? "только vpn" : "обход vpn")} · {ruleCount} правил",
                    $"{(isVpnOnlyMode ? "vpn only" : "bypass vpn")} · {ruleCount} rules"),
                    11, DeyttTheme.Muted));
                var targetId = pr.Id;
                var selectPresetBtn = DeyttTheme.DialogChoice(choiceDetails, isActive, () =>
                {
                    if (!string.Equals(activePresetId, targetId, StringComparison.OrdinalIgnoreCase))
                        SwitchPreset(targetId);
                });
                Avalonia.Automation.AutomationProperties.SetAutomationId(selectPresetBtn, $"SplitPresetSelect_{pr.Id}");
                Avalonia.Automation.AutomationProperties.SetName(selectPresetBtn,
                    pr.Name + (isActive ? Copy(", выбрано", ", selected") : ""));
                selectPresetBtn.MinWidth = 210;
                selectPresetBtn.MaxWidth = 330;
                selectPresetBtn.Margin = new Thickness(0, 0, 8, 8);

                var presetControls = new StackPanel
                {
                    Orientation = Avalonia.Layout.Orientation.Horizontal,
                    Spacing = 6,
                    VerticalAlignment = Avalonia.Layout.VerticalAlignment.Stretch,
                };
                presetControls.Children.Add(selectPresetBtn);
                if (presets.Count > 1)
                {
                    var delBtn = new Button
                    {
                        Content = "×",
                        Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand),
                        VerticalAlignment = Avalonia.Layout.VerticalAlignment.Stretch,
                        Margin = new Thickness(0, 0, 8, 8),
                    };
                    DeyttTheme.StyleDialogButton(delBtn);
                    delBtn.Width = 42;
                    delBtn.MinHeight = 42;
                    Avalonia.Automation.AutomationProperties.SetAutomationId(delBtn, $"SplitPresetDelete_{pr.Id}");
                    Avalonia.Automation.AutomationProperties.SetName(delBtn,
                        Copy($"Удалить профиль {pr.Name}", $"Delete preset {pr.Name}"));
                    var capturedId = pr.Id;
                    delBtn.Click += (_, _) => DeletePreset(capturedId);
                    presetControls.Children.Add(delBtn);
                }
                presetsWrapPanel.Children.Add(presetControls);
            }
        };

        refreshAllDynamicUi();

        ShellContentDialog? dialog = null;
        var footerButton = DeyttTheme.DialogPrimaryButton(
            DeyttTheme.TextBlock(Copy("Готово", "Done"), 15, Colors.White, FontWeight.SemiBold, wrap: false), () =>
        {
            dialog?.Close();
        });
        footerButton.Width = 180;
        footerButton.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;

        await ShowContentInShellAsync(
            Copy("Раздельное туннелирование", "Split tunneling"),
            rootStack,
            720,
            shellDialog => dialog = shellDialog,
            footerButton);

        if (!_qaFixture)
            await ApplySplitTunnelRulesAsync();
    }
}
