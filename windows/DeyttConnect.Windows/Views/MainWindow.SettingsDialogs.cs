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

        // 3. Custom Processes (.exe) Section
        var processSection = new StackPanel { Spacing = 8 };
        processSection.Children.Add(DeyttTheme.RawTextBlock(
            Copy("СВОИ ПРИЛОЖЕНИЯ БЕЗ VPN (.EXE)", "CUSTOM APPLICATIONS WITHOUT VPN (.EXE)"),
            12, DeyttTheme.Blue, FontWeight.SemiBold));

        var processInputGrid = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto,Auto"),
            Margin = new Thickness(0, 0, 0, 4)
        };
        var processTextBox = new TextBox
        {
            PlaceholderText = Copy("имя процесса, например: game.exe", "process name, e.g. game.exe"),
            FontSize = 13,
            FontFamily = DeyttTheme.JetBrainsMono,
            Height = 38,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        processInputGrid.Children.Add(processTextBox);
        Grid.SetColumn(processTextBox, 0);

        var processListStack = new StackPanel { Spacing = 6 };

        void RefreshProcessList()
        {
            processListStack.Children.Clear();
            if (customProcesses.Count == 0)
            {
                processListStack.Children.Add(DeyttTheme.TextBlock(
                    Copy("Нет добавленных приложений", "No custom applications added"), 12, DeyttTheme.Muted));
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
                }
            }
        };
        processInputGrid.Children.Add(browseFileBtn);
        Grid.SetColumn(browseFileBtn, 2);

        processSection.Children.Add(processInputGrid);
        RefreshProcessList();
        processSection.Children.Add(new ScrollViewer
        {
            Content = processListStack,
            MaxHeight = 150,
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
