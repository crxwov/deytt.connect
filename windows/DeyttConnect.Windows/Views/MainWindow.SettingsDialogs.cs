using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
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
        var sites = ReadBypassSites();
        var content = new StackPanel { Spacing = 12 };
        content.Children.Add(DeyttTheme.TextBlock(
            Copy("Эти сайты открываются без VPN. Звёздочка включает поддомены. Остальной трафик идёт через выбранное соединение, включая AmneziaWG. Список обновляется вместе с подпиской.",
                "These sites bypass the VPN. An asterisk includes subdomains. Other traffic uses the selected connection, including AmneziaWG. The list updates with your subscription."),
            13, DeyttTheme.Muted));

        if (sites is null)
        {
            content.Children.Add(DeyttTheme.TextBlock(
                Copy("Добавьте подписку, чтобы загрузить список сайтов.",
                    "Add a subscription to load the site list."),
                13, DeyttTheme.Amber));
        }
        else if (sites.Count == 0)
        {
            content.Children.Add(DeyttTheme.TextBlock(
                Copy("В подписке нет отдельных сайтов для обхода VPN.",
                    "The subscription has no sites that bypass the VPN."),
                13, DeyttTheme.Muted));
        }
        else
        {
            var list = DeyttTheme.TextBlock(string.Join("\n", sites), 12,
                DeyttTheme.Text, family: DeyttTheme.JetBrainsMono);
            content.Children.Add(DeyttTheme.Card(new ScrollViewer
            {
                Content = list,
                MaxHeight = 390,
                VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
            }, DeyttTheme.Surface, DeyttTheme.Line, 14, new Thickness(12, 10)));
        }

        ShellContentDialog? dialog = null;
        var close = DeyttTheme.PrimaryButton(Copy("Понятно", "Got it"), () => dialog?.Close());
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
