using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
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
        var baseSites = ReadBypassSites() ?? [];
        var splitConfig = WindowsSplitTunnelStore.Load();
        var customDomains = (splitConfig.CustomDomains ?? []).ToList();
        var customProcesses = (splitConfig.CustomProcesses ?? []).ToList();

        void SaveAndRefresh(Action? afterSave = null)
        {
            WindowsSplitTunnelStore.Save(new WindowsSplitTunnelConfig(customDomains, customProcesses));
            RenderActiveTabPreservingScroll();
            afterSave?.Invoke();
        }

        var content = new StackPanel { Spacing = 14 };

        content.Children.Add(DeyttTheme.TextBlock(
            Copy("Трафик сайтов и программ из этого списка идёт напрямую без VPN. Существующие правила подписки неизменяемы. Добавьте свои сайты или приложения для прямого соединения.",
                "Traffic for sites and apps in this list goes directly without VPN. Subscription rules are immutable. Add your own sites or applications for direct connection."),
            13, DeyttTheme.Muted));

        var sectionsPanel = new StackPanel { Spacing = 16 };

        // 1. Base Subscription Rules (Immutable)
        var baseHeader = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
        };
        var baseTitle = DeyttTheme.TextBlock(Copy("Базовые сайты из подписки", "Base subscription sites"), 14, DeyttTheme.Text, FontWeight.SemiBold);
        var baseBadge = new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            CornerRadius = new CornerRadius(6),
            Padding = new Thickness(8, 2),
            Child = DeyttTheme.TextBlock(Copy("неизменяемые", "immutable"), 11, DeyttTheme.Muted),
        };
        Grid.SetColumn(baseTitle, 0);
        Grid.SetColumn(baseBadge, 1);
        baseHeader.Children.Add(baseTitle);
        baseHeader.Children.Add(baseBadge);
        sectionsPanel.Children.Add(baseHeader);

        if (baseSites.Count == 0)
        {
            sectionsPanel.Children.Add(DeyttTheme.TextBlock(
                Copy("В подписке нет базовых правил обхода.", "The subscription has no base bypass rules."),
                12, DeyttTheme.Muted));
        }
        else
        {
            var baseList = DeyttTheme.TextBlock(string.Join("\n", baseSites), 12, DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
            sectionsPanel.Children.Add(DeyttTheme.Card(new ScrollViewer
            {
                Content = baseList,
                MaxHeight = 110,
                VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
            }, DeyttTheme.Surface, DeyttTheme.Line, 10, new Thickness(10, 8)));
        }

        // 2. Custom Domains
        var customDomainsContainer = new StackPanel { Spacing = 8 };
        void RebuildDomainsList()
        {
            customDomainsContainer.Children.Clear();
            if (customDomains.Count == 0)
            {
                customDomainsContainer.Children.Add(DeyttTheme.TextBlock(
                    Copy("Своих сайтов пока нет. Добавьте домен ниже.", "No custom sites yet. Add a domain below."),
                    12, DeyttTheme.Muted));
                return;
            }

            var listStack = new StackPanel { Spacing = 6 };
            foreach (var domain in customDomains)
            {
                var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
                var label = DeyttTheme.TextBlock(domain, 12, DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
                var capturedDomain = domain;
                var del = DeyttTheme.Action(
                    new Border
                    {
                        Padding = new Thickness(6, 2),
                        Background = DeyttTheme.Brush(Colors.Transparent),
                        Child = DeyttTheme.TextBlock("✕", 12, DeyttTheme.Coral, FontWeight.Bold),
                    },
                    () =>
                    {
                        customDomains.Remove(capturedDomain);
                        SaveAndRefresh(RebuildDomainsList);
                    });
                Grid.SetColumn(label, 0);
                Grid.SetColumn(del, 1);
                row.Children.Add(label);
                row.Children.Add(del);
                listStack.Children.Add(row);
            }
            customDomainsContainer.Children.Add(DeyttTheme.Card(new ScrollViewer
            {
                Content = listStack,
                MaxHeight = 130,
                VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
            }, DeyttTheme.Surface, DeyttTheme.Line, 10, new Thickness(10, 8)));
        }

        sectionsPanel.Children.Add(DeyttTheme.TextBlock(Copy("Свои сайты без VPN", "Custom sites without VPN"), 14, DeyttTheme.Text, FontWeight.SemiBold));
        sectionsPanel.Children.Add(DeyttTheme.TextBlock(
            Copy("Домен или маска (*.domain.com). Трафик пойдёт в обход туннеля.",
                "Domain or wildcard (*.domain.com). Traffic will bypass the tunnel."),
            12, DeyttTheme.Muted));

        var domainInputGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,8,Auto") };
        var domainTextBox = new TextBox
        {
            PlaceholderText = "*.example.com",
            MinHeight = 36,
            FontSize = 13,
            Foreground = DeyttTheme.Brush(DeyttTheme.Text),
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(10, 6),
            VerticalContentAlignment = VerticalAlignment.Center,
        };
        var domainErrorLabel = DeyttTheme.TextBlock("", 12, DeyttTheme.Coral);
        domainErrorLabel.IsVisible = false;

        void AddDomainAction()
        {
            var normalized = WindowsSplitTunnelStore.NormalizeDomain(domainTextBox.Text);
            if (normalized is null)
            {
                domainErrorLabel.Text = Copy("Некорректный домен (пример: *.example.com)", "Invalid domain (e.g. *.example.com)");
                domainErrorLabel.IsVisible = true;
                return;
            }
            domainErrorLabel.IsVisible = false;
            if (!customDomains.Contains(normalized, StringComparer.OrdinalIgnoreCase))
            {
                customDomains.Add(normalized);
                customDomains.Sort(StringComparer.OrdinalIgnoreCase);
                domainTextBox.Text = string.Empty;
                SaveAndRefresh(RebuildDomainsList);
            }
        }

        var addDomainButton = DeyttTheme.Action(
            new Border
            {
                Background = DeyttTheme.Brush(DeyttTheme.BlueSurface),
                BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(14, 8),
                Child = DeyttTheme.TextBlock(Copy("Добавить", "Add"), 13, DeyttTheme.Blue, FontWeight.SemiBold),
            }, AddDomainAction);

        Grid.SetColumn(domainTextBox, 0);
        Grid.SetColumn(addDomainButton, 2);
        domainInputGrid.Children.Add(domainTextBox);
        domainInputGrid.Children.Add(addDomainButton);

        sectionsPanel.Children.Add(domainInputGrid);
        sectionsPanel.Children.Add(domainErrorLabel);
        RebuildDomainsList();
        sectionsPanel.Children.Add(customDomainsContainer);

        // 3. Custom Processes
        var customProcessesContainer = new StackPanel { Spacing = 8 };
        void RebuildProcessesList()
        {
            customProcessesContainer.Children.Clear();
            if (customProcesses.Count == 0)
            {
                customProcessesContainer.Children.Add(DeyttTheme.TextBlock(
                    Copy("Своих приложений пока нет. Добавьте .exe ниже.", "No custom apps yet. Add an .exe below."),
                    12, DeyttTheme.Muted));
                return;
            }

            var listStack = new StackPanel { Spacing = 6 };
            foreach (var proc in customProcesses)
            {
                var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
                var label = DeyttTheme.TextBlock(proc, 12, DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
                var capturedProc = proc;
                var del = DeyttTheme.Action(
                    new Border
                    {
                        Padding = new Thickness(6, 2),
                        Background = DeyttTheme.Brush(Colors.Transparent),
                        Child = DeyttTheme.TextBlock("✕", 12, DeyttTheme.Coral, FontWeight.Bold),
                    },
                    () =>
                    {
                        customProcesses.Remove(capturedProc);
                        SaveAndRefresh(RebuildProcessesList);
                    });
                Grid.SetColumn(label, 0);
                Grid.SetColumn(del, 1);
                row.Children.Add(label);
                row.Children.Add(del);
                listStack.Children.Add(row);
            }
            customProcessesContainer.Children.Add(DeyttTheme.Card(new ScrollViewer
            {
                Content = listStack,
                MaxHeight = 130,
                VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
            }, DeyttTheme.Surface, DeyttTheme.Line, 10, new Thickness(10, 8)));
        }

        sectionsPanel.Children.Add(DeyttTheme.TextBlock(Copy("Свои приложения без VPN", "Custom apps without VPN"), 14, DeyttTheme.Text, FontWeight.SemiBold));
        sectionsPanel.Children.Add(DeyttTheme.TextBlock(
            Copy("Трафик этих программ пойдёт напрямую мимо туннеля.",
                "Traffic of these programs will bypass the tunnel."),
            12, DeyttTheme.Muted));

        var processInputGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,8,Auto,8,Auto") };
        var processTextBox = new TextBox
        {
            PlaceholderText = "app.exe",
            MinHeight = 36,
            FontSize = 13,
            Foreground = DeyttTheme.Brush(DeyttTheme.Text),
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(10, 6),
            VerticalContentAlignment = VerticalAlignment.Center,
        };
        var processErrorLabel = DeyttTheme.TextBlock("", 12, DeyttTheme.Coral);
        processErrorLabel.IsVisible = false;

        void AddProcessAction()
        {
            var normalized = WindowsSplitTunnelStore.NormalizeProcess(processTextBox.Text);
            if (normalized is null)
            {
                processErrorLabel.Text = Copy("Укажите файл .exe (например: telegram.exe)", "Specify an .exe file (e.g. telegram.exe)");
                processErrorLabel.IsVisible = true;
                return;
            }
            processErrorLabel.IsVisible = false;
            if (!customProcesses.Contains(normalized, StringComparer.OrdinalIgnoreCase))
            {
                customProcesses.Add(normalized);
                customProcesses.Sort(StringComparer.OrdinalIgnoreCase);
                processTextBox.Text = string.Empty;
                SaveAndRefresh(RebuildProcessesList);
            }
        }

        async void BrowseProcessAction()
        {
            try
            {
                var topLevel = TopLevel.GetTopLevel(this);
                if (topLevel?.StorageProvider is { } storage)
                {
                    var files = await storage.OpenFilePickerAsync(new FilePickerOpenOptions
                    {
                        Title = Copy("Выберите исполняемый файл (.exe)", "Select executable file (.exe)"),
                        AllowMultiple = false,
                        FileTypeFilter = [new FilePickerFileType("Executable") { Patterns = ["*.exe"] }],
                    });
                    var selected = files?.FirstOrDefault();
                    if (selected is not null)
                    {
                        var normalized = WindowsSplitTunnelStore.NormalizeProcess(selected.Name);
                        if (normalized is not null && !customProcesses.Contains(normalized, StringComparer.OrdinalIgnoreCase))
                        {
                            customProcesses.Add(normalized);
                            customProcesses.Sort(StringComparer.OrdinalIgnoreCase);
                            SaveAndRefresh(RebuildProcessesList);
                        }
                    }
                }
            }
            catch
            {
                // File picker exception ignored
            }
        }

        var addProcessButton = DeyttTheme.Action(
            new Border
            {
                Background = DeyttTheme.Brush(DeyttTheme.BlueSurface),
                BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(14, 8),
                Child = DeyttTheme.TextBlock(Copy("Добавить", "Add"), 13, DeyttTheme.Blue, FontWeight.SemiBold),
            }, AddProcessAction);

        var browseProcessButton = DeyttTheme.Action(
            new Border
            {
                Background = DeyttTheme.Brush(DeyttTheme.Surface2),
                BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(12, 8),
                Child = DeyttTheme.TextBlock(Copy("Обзор…", "Browse…"), 13, DeyttTheme.Text, FontWeight.Medium),
            }, BrowseProcessAction);

        Grid.SetColumn(processTextBox, 0);
        Grid.SetColumn(browseProcessButton, 2);
        Grid.SetColumn(addProcessButton, 4);
        processInputGrid.Children.Add(processTextBox);
        processInputGrid.Children.Add(browseProcessButton);
        processInputGrid.Children.Add(addProcessButton);

        sectionsPanel.Children.Add(processInputGrid);
        sectionsPanel.Children.Add(processErrorLabel);
        RebuildProcessesList();
        sectionsPanel.Children.Add(customProcessesContainer);

        var scrollContainer = new ScrollViewer
        {
            Content = sectionsPanel,
            MaxHeight = 440,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
        };
        content.Children.Add(scrollContainer);

        ShellContentDialog? dialog = null;
        var close = DeyttTheme.PrimaryButton(Copy("Готово", "Done"), () =>
        {
            SaveAndRefresh();
            dialog?.Close();
        });
        content.Children.Add(close);

        var body = new StackPanel
        {
            Spacing = 14,
            Children =
            {
                DeyttTheme.TextBlock(Copy("Раздельное туннелирование", "Split tunneling"),
                    21, DeyttTheme.Text, FontWeight.Bold),
                content,
            },
        };
        _ = ShowContentInShellAsync(Copy("Без VPN", "Without VPN"), body, 720,
            shellDialog => dialog = shellDialog);
    }
}
