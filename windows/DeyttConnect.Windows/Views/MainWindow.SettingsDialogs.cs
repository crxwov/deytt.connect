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

            return ReadDomains(entries[0], "domain_suffix").Select(domain => $"*.{domain}")
                .Concat(ReadDomains(entries[1], "domain"))
                .ToArray();
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private void ShowSplitTunnelingInfo()
    {
        var baseSites = ReadBypassSites();
        var currentConfig = WindowsSplitTunnelStore.Load();
        var customDomains = new List<string>(currentConfig.CustomDomains ?? []);
        var customProcesses = new List<string>(currentConfig.CustomProcesses ?? []);

        var rootStack = new StackPanel { Spacing = 14 };

        rootStack.Children.Add(DeyttTheme.TextBlock(
            Copy("Трафик этих сайтов и приложений идёт напрямую без VPN. Существующие правила подписки являются базовыми и их нельзя отключить.",
                "Traffic for these sites and applications goes directly without VPN. Existing subscription rules are immutable base rules."),
            13, DeyttTheme.Muted));

        // 1. Base Subscription Rules Card (read-only)
        var baseSection = new StackPanel { Spacing = 8 };
        baseSection.Children.Add(DeyttTheme.RawTextBlock(
            Copy("ПРАВИЛА ПОДПИСКИ (БАЗОВЫЕ, НЕЛЬЗЯ УБРАТЬ)", "SUBSCRIPTION RULES (BASE, IMMUTABLE)"),
            12, DeyttTheme.Muted, FontWeight.SemiBold));

        if (baseSites is null || baseSites.Count == 0)
        {
            baseSection.Children.Add(DeyttTheme.TextBlock(
                Copy("Правила подписки не загружены.", "Subscription rules not loaded."),
                12, DeyttTheme.Amber));
        }
        else
        {
            var baseList = DeyttTheme.TextBlock(string.Join("\n", baseSites), 12,
                DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
            baseSection.Children.Add(DeyttTheme.Card(new ScrollViewer
            {
                Content = baseList,
                MaxHeight = 120,
                VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
            }, DeyttTheme.Surface, DeyttTheme.Line, 12, new Thickness(10, 8)));
        }
        rootStack.Children.Add(baseSection);

        // 2. Custom Domains Section
        var domainsSection = new StackPanel { Spacing = 8 };
        domainsSection.Children.Add(DeyttTheme.RawTextBlock(
            Copy("СВОИ САЙТЫ БЕЗ VPN", "CUSTOM SITES WITHOUT VPN"),
            12, DeyttTheme.Blue, FontWeight.SemiBold));

        var domainInputGrid = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            Margin = new Thickness(0, 0, 0, 4)
        };
        var domainTextBox = new TextBox
        {
            PlaceholderText = Copy("например: mydomain.com или *.org", "e.g. mydomain.com or *.org"),
            FontSize = 13,
            FontFamily = DeyttTheme.JetBrainsMono,
            Height = 38,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        domainInputGrid.Children.Add(domainTextBox);
        Grid.SetColumn(domainTextBox, 0);

        var domainsListStack = new StackPanel { Spacing = 6 };

        void RefreshDomainsList()
        {
            domainsListStack.Children.Clear();
            if (customDomains.Count == 0)
            {
                domainsListStack.Children.Add(DeyttTheme.TextBlock(
                    Copy("Нет добавленных сайтов", "No custom sites added"), 12, DeyttTheme.Muted));
                return;
            }

            foreach (var d in customDomains.OrderBy(x => x, StringComparer.OrdinalIgnoreCase).ToArray())
            {
                var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
                var text = DeyttTheme.TextBlock(d, 12, DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
                text.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                row.Children.Add(text);
                Grid.SetColumn(text, 0);

                var deleteBtn = new Button
                {
                    Content = "✕",
                    Background = Brushes.Transparent,
                    BorderThickness = new Thickness(0),
                    Foreground = DeyttTheme.Brush(DeyttTheme.Coral),
                    Padding = new Thickness(8, 2),
                    FontSize = 14,
                    Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                };
                deleteBtn.Click += (_, _) =>
                {
                    customDomains.Remove(d);
                    WindowsSplitTunnelStore.Save(new WindowsSplitTunnelConfig(customDomains, customProcesses));
                    RefreshDomainsList();
                };
                row.Children.Add(deleteBtn);
                Grid.SetColumn(deleteBtn, 1);

                domainsListStack.Children.Add(DeyttTheme.Card(row, DeyttTheme.Surface, DeyttTheme.Line, 8, new Thickness(10, 6)));
            }
        }

        var addDomainBtn = new Button
        {
            Content = Copy("Добавить", "Add"),
            Height = 38,
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            FontWeight = FontWeight.SemiBold
        };
        addDomainBtn.Click += (_, _) =>
        {
            var normalized = WindowsSplitTunnelStore.NormalizeDomain(domainTextBox.Text);
            if (!string.IsNullOrWhiteSpace(normalized) && !customDomains.Contains(normalized, StringComparer.OrdinalIgnoreCase))
            {
                customDomains.Add(normalized);
                WindowsSplitTunnelStore.Save(new WindowsSplitTunnelConfig(customDomains, customProcesses));
                domainTextBox.Text = string.Empty;
                RefreshDomainsList();
            }
        };
        domainInputGrid.Children.Add(addDomainBtn);
        Grid.SetColumn(addDomainBtn, 1);

        domainsSection.Children.Add(domainInputGrid);
        RefreshDomainsList();
        domainsSection.Children.Add(new ScrollViewer
        {
            Content = domainsListStack,
            MaxHeight = 150,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto
        });
        rootStack.Children.Add(domainsSection);

        // 3. Custom Processes Section with running process picker
        var processSection = new StackPanel { Spacing = 8 };
        processSection.Children.Add(DeyttTheme.RawTextBlock(
            Copy("СВОИ ПРИЛОЖЕНИЯ И ПРОЦЕССЫ БЕЗ VPN", "CUSTOM APPLICATIONS & PROCESSES WITHOUT VPN"),
            12, DeyttTheme.Blue, FontWeight.SemiBold));
        processSection.Children.Add(DeyttTheme.TextBlock(
            Copy("Весь исходящий трафик выбранных процессов направляется напрямую в обход VPN на любые серверы и адреса.",
                "All outgoing traffic from selected processes goes directly without VPN to any server or address."),
            12, DeyttTheme.Muted));

        var processListStack = new StackPanel { Spacing = 6 };

        void RefreshProcessList()
        {
            processListStack.Children.Clear();
            if (customProcesses.Count == 0)
            {
                processListStack.Children.Add(DeyttTheme.TextBlock(
                    Copy("Нет добавленных процессов", "No custom processes added"), 12, DeyttTheme.Muted));
                return;
            }

            foreach (var p in customProcesses.OrderBy(x => x, StringComparer.OrdinalIgnoreCase).ToArray())
            {
                var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
                var text = DeyttTheme.TextBlock(p, 12, DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
                text.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
                row.Children.Add(text);
                Grid.SetColumn(text, 0);

                var deleteBtn = new Button
                {
                    Content = "✕",
                    Background = Brushes.Transparent,
                    BorderThickness = new Thickness(0),
                    Foreground = DeyttTheme.Brush(DeyttTheme.Coral),
                    Padding = new Thickness(8, 2),
                    FontSize = 14,
                    Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                };
                deleteBtn.Click += (_, _) =>
                {
                    customProcesses.Remove(p);
                    WindowsSplitTunnelStore.Save(new WindowsSplitTunnelConfig(customDomains, customProcesses));
                    RefreshProcessList();
                };
                row.Children.Add(deleteBtn);
                Grid.SetColumn(deleteBtn, 1);

                processListStack.Children.Add(DeyttTheme.Card(row, DeyttTheme.Surface, DeyttTheme.Line, 8, new Thickness(10, 6)));
            }
        }

        // Running processes selector panel
        var runningPickerCard = new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(12),
            IsVisible = false
        };

        var runningPickerContent = new StackPanel { Spacing = 8 };
        var searchProcessBox = new TextBox
        {
            PlaceholderText = Copy("Поиск по названию или имени процесса...", "Search by title or process name..."),
            FontSize = 12,
            FontFamily = DeyttTheme.JetBrainsMono,
            Height = 34,
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
                    var addedLabel = DeyttTheme.TextBlock(Copy("✓ Добавлен", "✓ Added"), 11, DeyttTheme.Mint);
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
                        Padding = new Thickness(8, 4),
                        Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
                    };
                    addBtn.Click += (_, _) =>
                    {
                        if (!customProcesses.Contains(app.ProcessName, StringComparer.OrdinalIgnoreCase))
                        {
                            customProcesses.Add(app.ProcessName);
                            WindowsSplitTunnelStore.Save(new WindowsSplitTunnelConfig(customDomains, customProcesses));
                            RefreshProcessList();
                            RenderRunningList(searchProcessBox.Text ?? string.Empty);
                        }
                    };
                    row.Children.Add(addBtn);
                    Grid.SetColumn(addBtn, 1);
                }

                runningItemsStack.Children.Add(DeyttTheme.Card(row, DeyttTheme.Surface, DeyttTheme.Line, 6, new Thickness(8, 5)));
            }
        }

        searchProcessBox.PropertyChanged += (_, e) =>
        {
            if (e.Property == TextBox.TextProperty)
                RenderRunningList(searchProcessBox.Text ?? string.Empty);
        };

        var pickRunningBtn = new Button
        {
            Content = Copy("⚡ Выбрать из запущенных процессов", "⚡ Pick from running processes"),
            Height = 36,
            Background = DeyttTheme.Brush(DeyttTheme.Selected),
            Foreground = DeyttTheme.Brush(DeyttTheme.Blue),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.SelectedLine),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(10),
            FontWeight = FontWeight.SemiBold,
            Padding = new Thickness(14, 0),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            Cursor = new Avalonia.Input.Cursor(Avalonia.Input.StandardCursorType.Hand)
        };
        pickRunningBtn.Click += (_, _) =>
        {
            runningPickerCard.IsVisible = !runningPickerCard.IsVisible;
            if (runningPickerCard.IsVisible)
                RenderRunningList(searchProcessBox.Text ?? string.Empty);
        };

        runningPickerCard.Child = runningPickerContent;

        var processInputGrid = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto,Auto"),
            Margin = new Thickness(0, 0, 0, 4)
        };
        var processTextBox = new TextBox
        {
            PlaceholderText = Copy("или имя процесса вручную: game.exe", "or enter process name: game.exe"),
            FontSize = 12,
            FontFamily = DeyttTheme.JetBrainsMono,
            Height = 38,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        processInputGrid.Children.Add(processTextBox);
        Grid.SetColumn(processTextBox, 0);

        var addProcessBtn = new Button
        {
            Content = Copy("Добавить", "Add"),
            Height = 38,
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            FontWeight = FontWeight.SemiBold
        };
        addProcessBtn.Click += (_, _) =>
        {
            var normalized = WindowsSplitTunnelStore.NormalizeProcess(processTextBox.Text);
            if (!string.IsNullOrWhiteSpace(normalized) && !customProcesses.Contains(normalized, StringComparer.OrdinalIgnoreCase))
            {
                customProcesses.Add(normalized);
                WindowsSplitTunnelStore.Save(new WindowsSplitTunnelConfig(customDomains, customProcesses));
                processTextBox.Text = string.Empty;
                RefreshProcessList();
                if (runningPickerCard.IsVisible)
                    RenderRunningList(searchProcessBox.Text ?? string.Empty);
            }
        };
        processInputGrid.Children.Add(addProcessBtn);
        Grid.SetColumn(addProcessBtn, 1);

        var browseFileBtn = new Button
        {
            Content = Copy("Обзор…", "Browse…"),
            Height = 38,
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center
        };
        browseFileBtn.Click += async (_, _) =>
        {
            var topLevel = TopLevel.GetTopLevel(this);
            if (topLevel is null) return;
            var files = await topLevel.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
            {
                Title = Copy("Выберите приложение для обхода VPN", "Select application to bypass VPN"),
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
                    WindowsSplitTunnelStore.Save(new WindowsSplitTunnelConfig(customDomains, customProcesses));
                    RefreshProcessList();
                    if (runningPickerCard.IsVisible)
                        RenderRunningList(searchProcessBox.Text ?? string.Empty);
                }
            }
        };
        processInputGrid.Children.Add(browseFileBtn);
        Grid.SetColumn(browseFileBtn, 2);

        processSection.Children.Add(pickRunningBtn);
        processSection.Children.Add(runningPickerCard);
        processSection.Children.Add(processInputGrid);
        RefreshProcessList();
        processSection.Children.Add(new ScrollViewer
        {
            Content = processListStack,
            MaxHeight = 130,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto
        });
        rootStack.Children.Add(processSection);

        ShellContentDialog? dialog = null;
        var close = DeyttTheme.PrimaryButton(Copy("Готово", "Done"), () =>
        {
            dialog?.Close();
            RenderActiveTabPreservingScroll();
        });
        rootStack.Children.Add(close);

        var body = new StackPanel
        {
            Spacing = 14,
            Children =
            {
                DeyttTheme.TextBlock(Copy("Раздельное туннелирование", "Split tunneling"),
                    21, DeyttTheme.Text, FontWeight.Bold),
                rootStack,
            },
        };
        _ = ShowContentInShellAsync(Copy("Без VPN", "Without VPN"), body, 760,
            shellDialog => dialog = shellDialog);
    }
}
