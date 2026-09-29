using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private Control BuildTelegramIdentityEntry(TelegramAccount? account, bool signedIn, Action onClick)
    {
        var name = AccountDisplayName(account, signedIn);
        var subtitle = account is not null && !string.IsNullOrWhiteSpace(account.Username)
            ? $"@{account.Username}"
            : signedIn
                ? Copy("Не удалось обновить профиль", "Could not refresh profile")
                : Copy("Войти в аккаунт", "Sign in to your account");
        var initial = account is null
            ? "•"
            : name.TrimStart('@').FirstOrDefault() is var letter && letter != '\0'
                ? letter.ToString().ToUpper(_language == "ru" ? CultureInfo.GetCultureInfo("ru-RU") : CultureInfo.GetCultureInfo("en-US"))
                : "•";
        var avatar = new Border
        {
            Width = 56,
            Height = 56,
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(28),
            ClipToBounds = true,
        };
        if (_telegramAvatarBitmap is null)
        {
            avatar.Child = DeyttTheme.TextBlock(initial, 23, DeyttTheme.Text, FontWeight.Bold);
        }
        else
        {
            var image = new Image { Source = _telegramAvatarBitmap, Stretch = Stretch.UniformToFill };
            _profileAvatarImage = image;
            avatar.Child = image;
        }

        var row = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), MinHeight = 70 };
        row.Children.Add(avatar);
        var labels = new StackPanel
        {
            Spacing = 3,
            Margin = new Thickness(14, 0, 6, 0),
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        labels.Children.Add(DeyttTheme.TextBlock(name, 17, DeyttTheme.Text, FontWeight.SemiBold));
        labels.Children.Add(DeyttTheme.TextBlock(subtitle, 12, DeyttTheme.Muted));
        Grid.SetColumn(labels, 1);
        row.Children.Add(labels);
        var trailing = DeyttTheme.TextBlock(signedIn ? Copy("управлять", "manage") : "›",
            13, DeyttTheme.Muted, FontWeight.SemiBold, wrap: false);
        trailing.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        Grid.SetColumn(trailing, 2);
        row.Children.Add(trailing);

        var cardContent = new StackPanel { Spacing = 9 };
        cardContent.Children.Add(row);
        if (FormatAccountTenure(account) is { } tenure)
            cardContent.Children.Add(DeyttTheme.TextBlock(tenure, 12, DeyttTheme.Muted));
        var button = DeyttTheme.Action(cardContent, onClick);
        button.Padding = new Thickness(14, 12);
        return new Border
        {
            Background = DeyttTheme.Brush(DeyttTheme.Surface),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(20),
            Child = button,
        };
    }

    private string AccountDisplayName(TelegramAccount? account, bool signedIn)
    {
        if (account is null)
            return signedIn ? Copy("Telegram подключён", "Telegram connected") : Copy("Подключить Telegram", "Connect Telegram");
        var fullName = string.Join(" ", new[] { account.FirstName, account.LastName }
            .Where(value => !string.IsNullOrWhiteSpace(value)));
        if (!string.IsNullOrWhiteSpace(fullName))
            return fullName;
        return string.IsNullOrWhiteSpace(account.Username) ? "Telegram" : $"@{account.Username}";
    }

    private string? FormatAccountTenure(TelegramAccount? account)
    {
        if (account?.RegisteredAt is not { Length: > 0 } raw ||
            !DateTimeOffset.TryParse(raw, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var registered))
            return null;
        var culture = _language == "ru" ? CultureInfo.GetCultureInfo("ru-RU") : CultureInfo.GetCultureInfo("en-US");
        var result = Copy("С нами с ", "With us since ") + registered.ToLocalTime().ToString("d MMM yyyy", culture);
        if (account.ServiceDays is not long days || days < 0)
            return result;

        if (_language == "ru")
        {
            var ending = days % 100 is >= 11 and <= 14 ? "дней" : (days % 10) switch
            {
                1 => "день",
                >= 2 and <= 4 => "дня",
                _ => "дней",
            };
            result += $" · {days} {ending} в сервисе";
        }
        else
        {
            result += $" · {days} {(days == 1 ? "day with DEYTT" : "days with DEYTT")}";
        }
        return result;
    }

    private async Task RefreshTelegramAvatarAsync(string token)
    {
        if (!string.Equals(_telegramAvatarSessionToken, token, StringComparison.Ordinal))
        {
            ClearTelegramAvatar();
            _telegramAvatarSessionToken = token;
        }

        try
        {
            var bytes = await _telegramApi.GetAvatarAsync(token);
            if (string.Equals(_telegramAvatarSessionToken, token, StringComparison.Ordinal))
                SetTelegramAvatar(bytes);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            throw;
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            // Keep the current image if the optional avatar endpoint is temporarily unavailable.
        }
    }

    private void SetTelegramAvatar(byte[]? bytes)
    {
        Bitmap? bitmap = null;
        if (bytes is { Length: > 0 })
        {
            try
            {
                using var stream = new MemoryStream(bytes);
                bitmap = Bitmap.DecodeToWidth(stream, 256, BitmapInterpolationMode.HighQuality);
            }
            catch (Exception error) when (error is ArgumentException or InvalidDataException or NotSupportedException or InvalidOperationException)
            {
                // The profile can still be identified by its text fallback if the image is malformed.
            }
        }

        var previous = _telegramAvatarBitmap;
        _telegramAvatarBitmap = bitmap;
        if (_profileAvatarImage is not null)
            _profileAvatarImage.Source = bitmap;
        previous?.Dispose();
    }

    private void ClearTelegramAvatar()
    {
        _telegramAvatarSessionToken = null;
        SetTelegramAvatar(null);
    }

    private void ShowTelegramAccountMenu()
    {
        var token = _sessionToken;
        if (token is null)
        {
            ShowSignInDialog();
            return;
        }

        var name = AccountDisplayName(_account, signedIn: true);
        var username = _account is null || string.IsNullOrWhiteSpace(_account.Username)
            ? string.Empty
            : $"@{_account.Username}";
        var window = new Window
        {
            Title = Copy("Telegram", "Telegram"),
            Width = 430,
            MinWidth = 360,
            MaxWidth = 500,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
        };
        var content = new StackPanel { Spacing = 12 };
        content.Children.Add(DeyttTheme.TextBlock("./c · TELEGRAM", 9, DeyttTheme.Muted,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono));
        content.Children.Add(DeyttTheme.TextBlock(Copy("Аккаунт подключён", "Account connected"),
            21, DeyttTheme.Text, FontWeight.Bold));
        content.Children.Add(DeyttTheme.TextBlock(
            string.Join("\n", new[] { name, username }.Where(value => !string.IsNullOrWhiteSpace(value))),
            14, DeyttTheme.Muted));
        content.Children.Add(DeyttTheme.TextBlock(
            Copy("Профиль и сессия связаны с Telegram. Переподключение загрузит свежие профили; отключение завершит эту сессию.",
                "Your profile and session are linked to Telegram. Reconnect to refresh profiles, or disconnect to end this account session."),
            13, DeyttTheme.Muted));

        var reconnect = DeyttTheme.PrimaryButton(Copy("Переподключить", "Reconnect"), () =>
        {
            window.Close();
            if (_sessionToken == token)
                _ = ShowSetupWindowAsync();
        });
        content.Children.Add(reconnect);
        var disconnect = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("Отключить аккаунт", "Disconnect account"), 14,
                DeyttTheme.Coral, FontWeight.SemiBold),
            () =>
            {
                window.Close();
                _ = ConfirmTelegramDisconnectAsync(token);
            });
        content.Children.Add(disconnect);
        content.Children.Add(DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("Закрыть", "Close"), 13, DeyttTheme.Muted),
            window.Close));
        window.Content = DeyttTheme.Card(content, DeyttTheme.Surface2, DeyttTheme.Line,
            22, new Thickness(21));
        window.ShowDialog(this);
    }

    private async Task ConfirmTelegramDisconnectAsync(string token)
    {
        if (_sessionToken != token)
            return;
        if (!await ConfirmDialogAsync(
                Copy("Отключить аккаунт?", "Disconnect account?"),
                Copy("Текущая сессия Telegram будет завершена на этом устройстве.",
                    "The current Telegram session will end on this device."),
                Copy("Отключить", "Disconnect")))
            return;
        if (_sessionToken == token)
            SignOut();
    }

    private void ShowHappDeviceDetails(TelegramHappDevice device)
    {
        var platform = device.Os.Trim();
        var version = device.OsVersion?.Trim();
        var os = !string.IsNullOrWhiteSpace(version) &&
                 (string.IsNullOrWhiteSpace(platform) || version.StartsWith(platform, StringComparison.OrdinalIgnoreCase))
            ? version
            : string.Join(" ", new[] { platform, version }.Where(value => !string.IsNullOrWhiteSpace(value)));
        var name = string.IsNullOrWhiteSpace(device.Model)
            ? Copy("Устройство Happ", "Happ device")
            : device.Model;
        var blocked = device.Blocked;
        var state = blocked
            ? Copy("Обновления заблокированы", "Subscription updates blocked")
            : Copy("Доступ разрешён", "Access allowed");
        var window = new Window
        {
            Title = name,
            Width = 470,
            MinWidth = 360,
            MaxWidth = 520,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
        };
        var content = new StackPanel { Spacing = 12 };
        content.Children.Add(DeyttTheme.TextBlock("HAPP · DEVICE", 9, DeyttTheme.Sky,
            FontWeight.SemiBold, DeyttTheme.JetBrainsMono));
        content.Children.Add(DeyttTheme.TextBlock(name, 21, DeyttTheme.Text, FontWeight.Bold));
        if (!string.IsNullOrWhiteSpace(os))
            content.Children.Add(DeyttTheme.TextBlock(os, 13, DeyttTheme.Muted));
        content.Children.Add(DeyttTheme.TextBlock(state, 14,
            blocked ? DeyttTheme.Coral : DeyttTheme.Mint, FontWeight.SemiBold));
        content.Children.Add(DeyttTheme.TextBlock(
            $"{Copy("Последнее подключение", "Last connection")}: {FormatDeviceDate(device.LastSeen)}",
            13, DeyttTheme.Muted));
        if (blocked && !string.IsNullOrWhiteSpace(device.BlockedAt))
            content.Children.Add(DeyttTheme.TextBlock(
                $"{Copy("Заблокировано", "Blocked on")}: {FormatDeviceDate(device.BlockedAt)}",
                13, DeyttTheme.Muted));
        content.Children.Add(DeyttTheme.TextBlock(
            blocked
                ? Copy("После восстановления сервер проверит, есть ли свободное место в тарифе.",
                    "After restoring access, the server checks whether your plan has a free device slot.")
                : Copy("Блокировка остановит обновление подписки на этом устройстве. Уже загруженные ключи и активное VPN-соединение продолжат работать.",
                    "Blocking stops subscription refresh on this device. Downloaded keys and an active VPN connection keep working."),
            13, DeyttTheme.Muted));
        content.Children.Add(DeyttTheme.PrimaryButton(
            blocked ? Copy("Восстановить доступ", "Restore access") : Copy("Заблокировать", "Block device"),
            () =>
            {
                window.Close();
                _ = ToggleHappDeviceAsync(device);
            }));
        content.Children.Add(DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("Закрыть", "Close"), 13, DeyttTheme.Muted),
            window.Close));
        window.Content = DeyttTheme.Card(content, DeyttTheme.Surface2, DeyttTheme.Line,
            22, new Thickness(21));
        window.ShowDialog(this);
    }

    private string FormatDeviceDate(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return Copy("нет данных", "not available");
        if (!DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal, out var parsed))
            return Copy("нет данных", "not available");
        var culture = _language == "ru" ? CultureInfo.GetCultureInfo("ru-RU") : CultureInfo.GetCultureInfo("en-US");
        return parsed.ToLocalTime().ToString("g", culture);
    }

    private async Task ShowHappDeviceActionErrorAsync(TelegramApiException error)
    {
        var slotLimit = error.Code is "device_limit_reached" or "device_slot_limit_reached";
        var deviceMissing = error.Code is "device_not_found" or "happ_device_not_found";
        var title = slotLimit
            ? Copy("Нет свободного места в тарифе", "No free device slot")
            : deviceMissing
                ? Copy("Устройство не найдено", "Device not found")
                : Copy("Не удалось обновить устройство", "Could not update the device");
        var detail = slotLimit
            ? Copy("Освободите место в тарифе, затем повторите восстановление доступа.",
                "Free a slot in your plan, then retry restoring device access.")
            : deviceMissing
                ? Copy("Устройство больше не зарегистрировано. Обновите список и повторите действие.",
                    "This device is no longer registered. Refresh the list and retry the action.")
                : Copy("Сервер не подтвердил изменение. Проверьте подключение и обновите список.",
                    "The server did not confirm the change. Check your connection and refresh the list.");
        var retry = false;
        var refresh = DeyttTheme.PrimaryButton(Copy("Обновить список", "Refresh list"), () =>
        {
            retry = true;
        });
        var close = DeyttTheme.Action(DeyttTheme.TextBlock(Copy("Закрыть", "Close"),
            13, DeyttTheme.Muted), () => { });
        var window = new Window
        {
            Title = title,
            Width = 440,
            MinWidth = 360,
            MaxWidth = 500,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = DeyttTheme.Brush(DeyttTheme.Background),
            Content = DeyttTheme.Card(new StackPanel
            {
                Spacing = 13,
                Children =
                {
                    DeyttTheme.TextBlock(title, 20, DeyttTheme.Text, FontWeight.Bold),
                    DeyttTheme.TextBlock(detail, 13, DeyttTheme.Muted),
                    refresh,
                    close,
                },
            }, DeyttTheme.Surface2, DeyttTheme.Line, 21, new Thickness(20)),
        };
        if (refresh.Child is Button refreshButton)
            refreshButton.Click += (_, _) => window.Close();
        close.Click += (_, _) => window.Close();
        await window.ShowDialog(this);
        if (retry && _sessionToken is not null)
            await RefreshSignedInAccountAsync();
    }
}
