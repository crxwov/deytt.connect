using System;
using System.Collections.Generic;
using System.Linq;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private Control BuildProfilePage()
    {
        var page = NewPage(Copy("ВАШ АККАУНТ", "YOUR ACCOUNT"), Copy("Профиль", "Profile"));
        var signedIn = _sessionToken is not null;
        var account = _account;
        var loading = signedIn && (_profileRefreshInProgress ||
            (_keysSnapshot is null && _profileLoadIssue is null));
        var accountError = signedIn && !loading &&
            (account is null || (_keysSnapshot is null && _profileLoadIssue is not null));
        var compact = _compactLayout;
        var columns = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions(compact ? "*" : "1.02*,0.98*"),
            RowDefinitions = new RowDefinitions(compact ? "Auto,Auto" : "Auto"),
        };
        var accountColumn = new StackPanel
        {
            Spacing = 0,
            Margin = compact ? new Thickness(0) : new Thickness(0, 0, 10, 0),
        };
        var managementColumn = new StackPanel
        {
            Spacing = 10,
            Margin = compact ? new Thickness(0, 8, 0, 0) : new Thickness(10, 0, 0, 0),
        };

        accountColumn.Children.Add(BuildProfileIdentityCard(account, signedIn, loading));
        var subscription = account?.Subscription;
        var isSubscriptionActive = subscription?.Active == true;
        var planPaymentEntry = SettingsEntry("₽",
            isSubscriptionActive ? Copy("Продлить подписку", "Renew subscription") : Copy("Выбрать тариф", "Choose a plan"),
            isSubscriptionActive ? Copy("Продление или смена тарифа", "Renew or change your plan")
                : Copy("Тарифы и способы оплаты", "Plans and payment methods"),
            isSubscriptionActive ? Copy("продлить", "renew") : Copy("выбрать", "choose"),
            () => _ = ShowPlanPickerAsync(), emphasis: isSubscriptionActive, glassSurface: true);
        AutomationProperties.SetAutomationId(planPaymentEntry, "ProfileRenewalRow");
        AddSection(accountColumn, Copy("ПОДПИСКА", "SUBSCRIPTION"));
        var subscriptionTitle = !signedIn
            ? Copy("Подключите Telegram", "Connect Telegram")
            : loading
                ? Copy("Загружаем данные подписки…", "Loading subscription…")
                : accountError
                    ? Copy("Не удалось загрузить данные аккаунта.", "Could not load account data.")
                    : subscription is null
                        ? Copy("Данные подписки недоступны", "Subscription details unavailable")
                        : subscription.Active
                            ? subscription.TariffName ?? Copy("Подписка активна", "Subscription active")
                            : Copy("Нет активной подписки", "No active subscription");
        var subscriptionDetail = !signedIn
            ? Copy("Войдите, чтобы увидеть тариф и срок действия.",
                "Sign in to see your plan and expiry date.")
            : loading
                ? Copy("Проверяем тариф и доступ…", "Checking your plan and access…")
                : accountError
                    ? _profileLoadIssue ?? Copy("Проверьте соединение и повторите загрузку.",
                        "Check your connection and retry.")
                    : SubscriptionSummary(subscription);
        var subscriptionCard = new StackPanel { Spacing = 7 };
        subscriptionCard.Children.Add(DeyttTheme.TextBlock(subscriptionTitle, 18,
            accountError ? DeyttTheme.Coral : DeyttTheme.Text, FontWeight.SemiBold));
        subscriptionCard.Children.Add(DeyttTheme.TextBlock(subscriptionDetail, 13, DeyttTheme.Muted));
        if (signedIn && !loading && (accountError || subscription is null))
        {
            var retry = DeyttTheme.Action(
                DeyttTheme.TextBlock(Copy("Повторить загрузку", "Retry loading"), 13,
                    DeyttTheme.Coral, FontWeight.SemiBold),
                () => _ = RefreshSignedInAccountAsync());
            retry.Margin = new Thickness(0, 10, 0, 0);
            retry.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left;
            subscriptionCard.Children.Add(retry);
        }
        else if (signedIn && !loading && _profileLoadIssue is not null)
        {
            subscriptionCard.Children.Add(DeyttTheme.TextBlock(_profileLoadIssue, 12, DeyttTheme.Amber));
        }
        var subscriptionSurface = BuildProfileStarrySubscriptionCard(subscriptionCard);
        accountColumn.Children.Add(subscriptionSurface);
        accountColumn.Children.Add(DeyttTheme.Spacer(8));
        accountColumn.Children.Add(planPaymentEntry);
        if (_paymentFlow.PendingPaymentId is not null)
        {
            accountColumn.Children.Add(DeyttTheme.Spacer(6));
            accountColumn.Children.Add(SettingsEntry("↻", Copy("Проверить платёж", "Check payment"),
                Copy("Счёт ожидает подтверждения", "Payment is awaiting confirmation"),
                Copy("проверить", "check"), () => _ = CheckPaymentStatusAsync()));
        }
        if (!string.IsNullOrWhiteSpace(_profileActionStatus))
        {
            accountColumn.Children.Add(DeyttTheme.Spacer(8));
            var actionStatus = new StackPanel { Spacing = 8 };
            actionStatus.Children.Add(DeyttTheme.TextBlock(_profileActionStatus, 12, DeyttTheme.Muted));
            if (_profileActionRetry is { } retry)
            {
                var retryButton = DeyttTheme.Action(
                    DeyttTheme.TextBlock(
                        _paymentFlow.RetryUsesConfirmedQuote
                            ? Copy("Повторить создание счёта", "Retry checkout")
                            : Copy("Повторить расчёт", "Retry quote"), 13,
                        DeyttTheme.Sky, FontWeight.SemiBold),
                    () => _ = retry());
                retryButton.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left;
                actionStatus.Children.Add(retryButton);
            }
            accountColumn.Children.Add(DeyttTheme.Card(actionStatus,
                DeyttTheme.Surface2, DeyttTheme.Line, 14, new Thickness(13, 10)));
        }
        var supportEntry = SettingsEntry("✉", Copy("Поддержка", "Support"),
            Copy("Написать команде в приложении · онлайн", "Chat with our team in the app · online"),
            Copy("открыть", "open"), () => _ = ShowSupportWindowAsync(), glassSurface: true);
        AutomationProperties.SetAutomationId(supportEntry, "ProfileSupportRow");
        accountColumn.Children.Add(DeyttTheme.Spacer(8));
        accountColumn.Children.Add(supportEntry);

        managementColumn.Children.Add(BuildProfileDisclosure("▣",
            Copy("Устройства и сессии", "Devices and sessions"),
            Copy("Подключения и управление доступом", "Connections and access"),
            _profileDevicesExpanded,
            () => { _profileDevicesExpanded = !_profileDevicesExpanded; RenderActiveTabPreservingScroll(); },
            _profileDevicesExpanded ? BuildProfileDevicesContent(signedIn, loading, accountError) : null));
        managementColumn.Children.Add(BuildProfileDisclosure("◇",
            Copy("Помощь и документы", "Help and documents"),
            Copy("Поддержка, условия и конфиденциальность", "Support, terms and privacy"),
            _profileHelpExpanded,
            () => { _profileHelpExpanded = !_profileHelpExpanded; RenderActiveTabPreservingScroll(); },
            _profileHelpExpanded ? SettingsEntry("?", Copy("Центр помощи", "Help center"),
                Copy("Поддержка, условия использования и политика конфиденциальности",
                    "Support, terms of use, and privacy policy"), "›",
                () => _ = ShowHelpAndDocumentsAsync()) : null));

        columns.Children.Add(accountColumn);
        Grid.SetColumn(managementColumn, compact ? 0 : 1);
        Grid.SetRow(managementColumn, compact ? 1 : 0);
        columns.Children.Add(managementColumn);
        page.Children.Add(columns);
        return page;
    }

    private Control BuildProfileIdentityCard(TelegramAccount? account, bool signedIn, bool loading)
    {
        var avatar = new Border
        {
            Width = 56,
            Height = 56,
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            CornerRadius = new CornerRadius(28),
            ClipToBounds = true,
        };
        if (_telegramAvatarBitmap is { } bitmap)
        {
            var image = new Image { Source = bitmap, Stretch = Stretch.UniformToFill };
            _profileAvatarImage = image;
            avatar.Child = image;
        }
        else
        {
            avatar.Child = DeyttTheme.TextBlock("•", 23, DeyttTheme.Text, FontWeight.Bold, wrap: false);
        }
        var labels = new StackPanel { Spacing = 3, Margin = new Thickness(14, 0, 6, 0),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(AccountDisplayName(account, signedIn), 18,
            DeyttTheme.Text, FontWeight.SemiBold));
        var subtitle = !signedIn
            ? Copy("Войти в аккаунт", "Sign in to your account")
            : loading
                ? Copy("Загружаем профиль…", "Loading profile…")
                : account is null
                    ? Copy("Не удалось обновить профиль", "Could not refresh profile")
                    : string.IsNullOrWhiteSpace(account.Username)
                        ? Copy("Telegram подключён", "Telegram connected")
                        : $"@{account.Username}";
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 12, DeyttTheme.Muted));
        var row = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), MinHeight = 60 };
        row.Children.Add(avatar);
        Grid.SetColumn(labels, 1);
        row.Children.Add(labels);
        var arrow = DeyttTheme.TextBlock("›", 22, DeyttTheme.Muted, wrap: false);
        arrow.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(arrow, 2);
        row.Children.Add(arrow);
        var content = new StackPanel { Spacing = 7, Children = { row } };
        if (FormatAccountTenure(account) is { } tenure)
            content.Children.Add(DeyttTheme.TextBlock(tenure, 12, DeyttTheme.Muted));
        var action = DeyttTheme.Action(content, () =>
        {
            if (signedIn) ShowTelegramAccountMenu();
            else ShowSignInDialog();
        });
        action.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        action.Padding = new Thickness(16, 14);
        var card = DeyttTheme.GlassCard(action, DeyttTheme.Surface2, DeyttTheme.Sky,
            20, new Thickness(0));
        AutomationProperties.SetAutomationId(card, "ProfileIdentityCard");
        return card;
    }

    private Control BuildProfileDisclosure(string glyph, string title, string subtitle, bool expanded,
        Action onToggle, Control? details)
    {
        var header = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"),
            ColumnSpacing = 12, MinHeight = 58 };
        var icon = new Border
        {
            Width = 36,
            Height = 36,
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            CornerRadius = new CornerRadius(11),
            Child = DeyttTheme.TextBlock(glyph, 17, DeyttTheme.Sky, FontWeight.SemiBold,
                DeyttTheme.JetBrainsMono, wrap: false),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        header.Children.Add(icon);
        var labels = new StackPanel { Spacing = 4,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        labels.Children.Add(DeyttTheme.TextBlock(title, 16, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 11, DeyttTheme.Muted));
        Grid.SetColumn(labels, 1);
        header.Children.Add(labels);
        var indicator = DeyttTheme.TextBlock(expanded ? "−" : "+", 20,
            DeyttTheme.Muted, FontWeight.Normal, wrap: false);
        indicator.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(indicator, 2);
        header.Children.Add(indicator);
        var button = DeyttTheme.Action(header, onToggle);
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.Padding = new Thickness(2, 9);
        var group = new StackPanel { Spacing = 6, Children = { button } };
        if (expanded && details is not null)
            group.Children.Add(details);
        return DeyttTheme.GlassCard(group, DeyttTheme.Surface, DeyttTheme.Line, 17,
            new Thickness(12, 8));
    }

    private Control BuildProfileDevicesContent(bool signedIn, bool loading, bool accountError)
    {
        var content = new StackPanel { Spacing = 6 };
        IReadOnlyList<TelegramHappDevice> devices = signedIn ? _keysSnapshot?.HappDevices ?? [] : [];
        var deviceSummary = !signedIn
            ? Copy("Подключите аккаунт, чтобы увидеть устройства.",
                "Connect your account to see devices.")
            : loading
                ? Copy("Загружаем список…", "Loading devices…")
                : accountError
                    ? Copy("Не удалось загрузить устройства. Повторите загрузку профиля.",
                        "Could not load devices. Retry the profile request.")
                    : $"{devices.Count} {Copy("устройств Happ", "Happ devices")} · " +
                      $"{_keysSnapshot?.AmneziaClients ?? 0} {Copy("клиентов AWG", "AWG clients")}";
        content.Children.Add(DeyttTheme.TextBlock(deviceSummary, 12, DeyttTheme.Muted));
        if (signedIn && !loading && !accountError && devices.Count == 0)
            content.Children.Add(DeyttTheme.TextBlock(Copy("Активных устройств Happ пока нет.",
                "There are no active Happ devices."), 13, DeyttTheme.Muted));
        if (signedIn && !loading && !accountError)
        {
            foreach (var device in devices)
            {
                var deviceOs = !string.IsNullOrWhiteSpace(device.OsVersion) ? device.OsVersion : device.Os;
                var deviceName = string.Join(" · ", new[] { device.Model, deviceOs }
                    .Where(value => !string.IsNullOrWhiteSpace(value)));
                if (deviceName.Length == 0)
                    deviceName = Copy("Устройство Happ", "Happ device");
                var state = device.Blocked
                    ? Copy("обновления заблокированы", "updates blocked")
                    : Copy("доступ разрешён", "access allowed");
                content.Children.Add(SettingsEntry("H", deviceName,
                    $"{state} · {FormatLastSeen(device.LastSeen)}", Copy("подробнее", "details"),
                    () => ShowHappDeviceDetails(device)));
            }
        }
        content.Children.Add(DeyttTheme.Spacer(5));
        content.Children.Add(DeyttTheme.TextBlock(Copy("СЕАНСЫ ПРИЛОЖЕНИЯ", "APP SESSIONS"),
            9, DeyttTheme.Muted, FontWeight.SemiBold, DeyttTheme.JetBrainsMono));
        if (!signedIn)
        {
            content.Children.Add(SettingsEntry("↗", Copy("Войти, чтобы управлять сеансами", "Sign in to manage sessions"),
                Copy("Можно завершить старые входы на других устройствах", "End old app sessions on other devices"),
                "›", ShowSignInDialog));
        }
        else if (loading)
        {
            content.Children.Add(DeyttTheme.TextBlock(Copy("Загружаем сеансы…", "Loading sessions…"),
                12, DeyttTheme.Muted));
        }
        else if (_sessionLoadIssue is not null)
        {
            content.Children.Add(SettingsEntry("↻", Copy("Сеансы не загружены", "Sessions unavailable"),
                _sessionLoadIssue, Copy("повторить", "retry"), () => _ = RefreshSignedInAccountAsync()));
        }
        else if (_sessions.Count == 0)
        {
            content.Children.Add(DeyttTheme.TextBlock(Copy("Активных сеансов приложения нет.",
                "There are no active app sessions."), 12, DeyttTheme.Muted));
        }
        else
        {
            foreach (var session in _sessions)
            {
                var desktop = session.DeviceOs?.Contains("windows", StringComparison.OrdinalIgnoreCase) == true ||
                    session.DeviceOs?.Contains("desktop", StringComparison.OrdinalIgnoreCase) == true ||
                    session.DeviceOs?.Contains("mac", StringComparison.OrdinalIgnoreCase) == true ||
                    session.DeviceOs?.Contains("linux", StringComparison.OrdinalIgnoreCase) == true;
                var platform = string.Join(" · ", new[] { session.DeviceModel, session.DeviceOs }
                    .Where(value => !string.IsNullOrWhiteSpace(value)));
                var subtitle = string.Join(" · ", new[]
                {
                    platform,
                    session.Current
                        ? Copy("Это устройство · текущий сеанс", "This device · current session")
                        : $"{Copy("Вход", "Signed in")}: {FormatLastSeen(session.CreatedAt)}",
                }.Where(value => !string.IsNullOrWhiteSpace(value)));
                content.Children.Add(SettingsEntry(desktop ? "▣" : "▯", platform.Length > 0 ? platform : session.Label,
                    subtitle, session.Current ? Copy("текущий", "current") : Copy("управлять", "manage"),
                    () => _ = ManageSessionAsync(session)));
                content.Children.Add(DeyttTheme.Spacer(7));
            }
        }
        content.Children.Add(SettingsEntry("+", Copy("Обновить список", "Refresh list"),
            Copy("Заново загрузить устройства и доступ", "Reload devices and access"), "↻", () =>
            {
                if (signedIn) _ = RefreshSignedInAccountAsync();
                else ShowSignInDialog();
            }));
        content.Children.Add(SettingsEntry("↻", Copy("Сбросить ключи", "Reset keys"),
            Copy("Отозвать текущие ключи выбранного типа", "Revoke current keys by type"),
            Copy("управлять", "manage"), () => _ = ShowResetChoicesAsync()));
        return DeyttTheme.Card(content, DeyttTheme.Surface2, DeyttTheme.Line,
            19, new Thickness(15, 13));
    }
}
