using System.Collections.Generic;
using System;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Media;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private Control BuildSettingsPage()
    {
        var page = NewPage(Copy("ПОД ВАС", "MAKE IT YOURS"), Copy("Настройки", "Settings"));
        var compact = _compactLayout;
        var columns = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions(compact ? "*" : "*,*"),
            RowDefinitions = new RowDefinitions(compact ? "Auto,Auto" : "Auto"),
        };
        var generalColumn = new StackPanel
        {
            Spacing = 0,
            Margin = compact ? new Thickness(0) : new Thickness(0, 0, 10, 0),
        };
        var privacyColumn = new StackPanel
        {
            Spacing = 0,
            Margin = compact ? new Thickness(0, 4, 0, 0) : new Thickness(10, 0, 0, 0),
        };
        AddSection(generalColumn, Copy("ЯЗЫК И АККАУНТ", "LANGUAGE AND ACCOUNT"));
        var accountDetail = _sessionToken is null
            ? Copy("Подписка и устройства", "Subscription and devices")
            : string.IsNullOrWhiteSpace(_account?.Username)
                ? Copy("Аккаунт подключён", "Account connected")
                : $"@{_account!.Username}";
        var accountEntry = SettingsEntry("✓", _sessionToken is null
                ? Copy("Войти через Telegram", "Sign in with Telegram")
                : Copy("Telegram подключён", "Telegram connected"),
            accountDetail,
            "›", () =>
            {
                if (_sessionToken is null)
                    ShowSignInDialog();
                else
                    ShowTab(MainTab.Profile);
            }, emphasis: true);
        var accountEntryShownFirst = compact && _sessionToken is null;
        if (accountEntryShownFirst)
            generalColumn.Children.Add(accountEntry);
        generalColumn.Children.Add(SettingsEntry("Aa", Copy("Язык приложения", "App language"),
            _language == "ru" ? "Русский" : "English", "›", () => _ = ShowLanguagePickerAsync()));
        if (!accountEntryShownFirst)
        {
            generalColumn.Children.Add(DeyttTheme.Spacer(6));
            generalColumn.Children.Add(accountEntry);
        }
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↓", Copy("Импорт подписки", "Import subscription"),
            Copy("Вставить ссылку или подключить аккаунт", "Paste a link or connect your account"), "›",
            () => _ = ShowSetupWindowAsync()));
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↗", Copy("Ссылки подписки", "Subscription links"),
            WindowsImportLinkProtocol.IsRegistered()
                ? Copy("Открывать ссылки DEYTT в этом приложении", "Open DEYTT links in this app")
                : Copy("Назначить приложению ссылки DEYTT", "Open DEYTT links with this app"),
            WindowsImportLinkProtocol.IsRegistered()
                ? Copy("включено", "enabled")
                : Copy("включить", "enable"),
            ToggleImportLinkProtocol));

        AddSection(generalColumn, Copy("ПОДКЛЮЧЕНИЕ", "CONNECTION"));
        var splitStore = WindowsSplitTunnelStore.Load();
        var customCount = (splitStore.CustomDomains?.Count ?? 0) + (splitStore.CustomProcesses?.Count ?? 0);
        var splitSummary = customCount > 0
            ? Copy($"Подписка + {customCount} своих правил", $"Subscription + {customCount} custom rules")
            : Copy("Сайты из подписки · все протоколы", "Subscription sites · all protocols");
        generalColumn.Children.Add(SettingsEntry("↗", Copy("Раздельное туннелирование", "Split tunneling"),
            splitSummary, "›",
            ShowSplitTunnelingInfo));
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↻", Copy("Проверка маршрута", "Route check"),
            $"HTTP {_probeMethod} · {Copy("быстрая проверка, загрузка 1 с", "fast check, 1 s download")}", "›",
            () => _ = ShowProbeMethodPickerAsync()));
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↗", Copy("Служба VPN", "VPN service"),
            _vpnServiceAvailable
                ? Copy("Служба установлена · нажмите для проверки", "Service installed · click to repair")
                : Copy("Требуются права администратора", "Administrator approval required"), "›",
            () => _ = InstallVpnServiceAsync()));
        generalColumn.Children.Add(DeyttTheme.Spacer(4));
        generalColumn.Children.Add(SettingsEntry("↗", Copy("Бот DEYTT", "DEYTT bot"),
            Copy("Другие действия в Telegram", "More actions in Telegram"), "›", () => OpenExternal("https://t.me/deyttbot")));

        AddSection(privacyColumn, Copy("ВИД И ПРИВАТНОСТЬ", "APPEARANCE AND PRIVACY"));
        privacyColumn.Children.Add(ToggleEntry(Copy("Показывать мой регион", "Show my region"),
            Copy("IP · ipinfo.io · не GPS · только в памяти",
                "IP via ipinfo.io · no GPS · memory only"),
            _mapRegionEnabled, UpdateMapRegionEnabledAsync));
        privacyColumn.Children.Add(DeyttTheme.Spacer(8));
        privacyColumn.Children.Add(ToggleEntry(Copy("Уменьшить движение", "Reduce motion"),
            Copy("Скрыть звёздный фон и анимации",
                "Hide the starfield and animations"),
            _reduceMotion, value =>
            {
                _reduceMotion = value;
                return Task.CompletedTask;
            }));

        AddSection(privacyColumn, Copy("ОБНОВЛЕНИЯ", "UPDATES"));
        privacyColumn.Children.Add(SettingsEntry("↻", Copy("Проверить обновления", "Check for updates"),
            Copy("Текущая версия приложения", "Current application version"), "›",
            () => _ = ShowUpdateDialogAsync()));
        if (_preferencesIssue is not null)
        {
            privacyColumn.Children.Add(DeyttTheme.Spacer(10));
            privacyColumn.Children.Add(DeyttTheme.TextBlock(_preferencesIssue, 12, DeyttTheme.Coral));
        }
        columns.Children.Add(generalColumn);
        Grid.SetColumn(privacyColumn, compact ? 0 : 1);
        Grid.SetRow(privacyColumn, compact ? 1 : 0);
        columns.Children.Add(privacyColumn);
        page.Children.Add(columns);
        return page;
    }

    private Control SettingsEntry(string glyph, string title, string subtitle, string trailing,
        Action onClick, bool emphasis = false, bool glassSurface = false)
    {
        var content = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), MinHeight = 58 };
        var icon = DeyttTheme.TextBlock(glyph, 19, DeyttTheme.Sky,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false);
        icon.Width = 28;
        icon.TextAlignment = TextAlignment.Center;
        content.Children.Add(icon);
        var labels = new StackPanel { Spacing = 3, Margin = new Thickness(12, 0, 6, 0), VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(title, 16, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 11, DeyttTheme.Muted));
        Grid.SetColumn(labels, 1);
        content.Children.Add(labels);
        var arrow = DeyttTheme.TextBlock(trailing, 13, emphasis ? DeyttTheme.Sky : DeyttTheme.Muted,
            FontWeight.SemiBold, wrap: false);
        arrow.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(arrow, 2);
        content.Children.Add(arrow);
        var button = DeyttTheme.Action(content, onClick);
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.Padding = new Thickness(5, 5);
        button.CornerRadius = new CornerRadius(12);
        var row = glassSurface
            ? DeyttTheme.GlassCard(button,
                emphasis ? DeyttTheme.BlueSurface : DeyttTheme.Surface2,
                emphasis ? DeyttTheme.Sky : DeyttTheme.Line,
                14, new Thickness(4))
            : new Border
            {
                Background = DeyttTheme.Brush(DeyttTheme.Surface2),
                BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(4),
                Child = button,
            };
        button.GotFocus += (_, _) => row.BringIntoView();
        return row;
    }

    private Control ToggleEntry(string title, string subtitle, bool value, Func<bool, Task> update)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), MinHeight = 60 };
        var labels = new StackPanel { Spacing = 3, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(title, 16, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 11, DeyttTheme.Muted));
        grid.Children.Add(labels);
        var knob = new Grid
        {
            Width = 46,
            Height = 25,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        knob.Children.Add(new Ellipse
        {
            Width = 19,
            Height = 19,
            Fill = DeyttTheme.Brush(value ? DeyttTheme.Mint : DeyttTheme.Text),
            HorizontalAlignment = value ? Avalonia.Layout.HorizontalAlignment.Right : Avalonia.Layout.HorizontalAlignment.Left,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Margin = new Thickness(3),
        });
        var toggle = new Border
        {
            Child = knob,
            Width = 48,
            Height = 27,
            Background = DeyttTheme.Brush(value ? DeyttTheme.MintSurface : DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(value ? DeyttTheme.Mint : DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(15),
            ClipToBounds = true,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Margin = new Thickness(14, 0, 4, 0),
        };
        Grid.SetColumn(toggle, 1);
        grid.Children.Add(toggle);
        var button = DeyttTheme.Action(grid, () => _ = ApplyToggleAsync(!value, update));
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.Padding = new Thickness(5, 6);
        AutomationProperties.SetName(button, $"{title}: {(value ? Copy("включено", "on") : Copy("выключено", "off"))}");
        return new Border
        {
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(0, 0, 0, 1),
            Child = button,
        };
    }

    private async Task ApplyToggleAsync(bool value, Func<bool, Task> update)
    {
        await update(value);
        ApplyVisualPreferences();
        SavePreferences();
        ShowTab(MainTab.Settings);
    }

    private async Task ShowLanguagePickerAsync()
    {
        var choices = new StackPanel { Spacing = 8 };
        var buttons = new List<(string Language, Button Button)>();
        foreach (var (language, title) in new[] { ("ru", "Русский"), ("en", "English") })
        {
            var selected = _language == language;
            var choice = DeyttTheme.Action(
                DeyttTheme.Card(DeyttTheme.TextBlock(
                        $"{(selected ? "✓  " : string.Empty)}{title}", 16,
                        selected ? DeyttTheme.Sky : DeyttTheme.Text, FontWeight.SemiBold),
                    selected ? DeyttTheme.Selected : DeyttTheme.Surface,
                    selected ? DeyttTheme.SelectedLine : DeyttTheme.Line,
                    16, new Thickness(16, 13)),
                () => { });
            choices.Children.Add(choice);
            buttons.Add((language, choice));
        }
        var selectedLanguage = await ShowContentInShellAsync(
            Copy("Язык приложения", "App language"), choices, 390,
            modal =>
            {
                foreach (var (language, button) in buttons)
                    button.Click += (_, _) => modal.Close(language);
            });
        if (selectedLanguage is not string nextLanguage || nextLanguage == _language)
            return;

        _language = nextLanguage;
        SavePreferences();
        ShowTab(MainTab.Settings);
    }

    private async Task ShowProbeMethodPickerAsync()
    {
        var options = new StackPanel { Spacing = 8 };
        var optionButtons = new List<(string Method, Button Button)>();
        foreach (var option in new[]
        {
            (Method: "HEAD", Title: Copy("HEAD · только заголовки", "HEAD · headers only"),
                Detail: Copy("Быстрая проверка доступности с минимальным трафиком.",
                    "A quick availability check with minimal traffic.")),
            (Method: "GET", Title: Copy("GET · заголовки и содержимое", "GET · headers and body"),
                Detail: Copy("Загружает короткий ответ сервера. Подходит, если узел не отвечает на HEAD.",
                    "Downloads the short server response. Use it if a node does not answer HEAD.")),
        })
        {
            var selected = _probeMethod == option.Method;
            var labels = new StackPanel { Spacing = 4 };
            labels.Children.Add(DeyttTheme.TextBlock(
                $"{(selected ? "✓  " : string.Empty)}{option.Title}", 15,
                selected ? DeyttTheme.Sky : DeyttTheme.Text, FontWeight.SemiBold));
            labels.Children.Add(DeyttTheme.TextBlock(option.Detail, 12, DeyttTheme.Muted));
            var choice = DeyttTheme.Action(
                DeyttTheme.Card(labels, selected ? DeyttTheme.Selected : DeyttTheme.Surface,
                    selected ? DeyttTheme.SelectedLine : DeyttTheme.Line, 16, new Thickness(14, 11)), () => { });
            options.Children.Add(choice);
            optionButtons.Add((option.Method, choice));
        }
        var close = DeyttTheme.Action(DeyttTheme.TextBlock(Copy("Отмена", "Cancel"), 14,
            DeyttTheme.Muted, FontWeight.SemiBold), () => { });
        var content = new StackPanel
        {
            Spacing = 13,
            Children =
            {
                DeyttTheme.TextBlock(Copy("Способ проверки", "Check method"), 21,
                    DeyttTheme.Text, FontWeight.Bold),
                DeyttTheme.TextBlock(Copy("Проверка идёт через выбранный VPN-маршрут.",
                    "Checks run through the selected VPN route."), 13, DeyttTheme.Muted),
                options,
                close,
            },
        };
        var selectedMethod = await ShowContentInShellAsync(
            Copy("Проверка маршрута", "Route check"), content, 600,
            modal =>
            {
                foreach (var (method, button) in optionButtons)
                    button.Click += (_, _) => modal.Close(method);
                close.Click += (_, _) => modal.Close();
            });
        if (selectedMethod is not string method)
            return;

        _probeMethod = method;
        SavePreferences();
        ShowTab(MainTab.Settings);
    }
}
