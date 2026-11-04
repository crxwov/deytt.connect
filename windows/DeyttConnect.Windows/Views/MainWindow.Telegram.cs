using System.Security.Cryptography;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private Task<bool> ConfirmDialogAsync(string title, string message, string confirmLabel,
        string? cancelLabel = null) =>
        ConfirmInShellAsync(title, message, confirmLabel, cancelLabel);

    private void SavePreferences()
    {
        if (!OperatingSystem.IsWindows())
            return;
        try
        {
            WindowsPreferencesStore.Save(new WindowsPreferences(
                _language, _selectedRoute, _probeMethod, _mapRegionEnabled, _reduceMotion,
                _mapRegionConsentGranted, _mapRegionConsentAsked));
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            _preferencesIssue = Copy("Настройки не сохранились на этом устройстве.",
                "Settings could not be saved on this device.");
        }
    }

    private string FormatLastSeen(string? value)
    {
        if (!DateTimeOffset.TryParse(value, out var lastSeen))
            return Copy("дата неизвестна", "date unknown");
        return lastSeen.ToLocalTime().ToString("dd MMM yyyy HH:mm");
    }

    private async Task ShowTelegramPairingDialogAsync()
    {
        if (!OperatingSystem.IsWindows())
        {
            ShowInfoDialog(Copy("Вход через Telegram", "Sign in with Telegram"),
                Copy("Сохранение сеанса защищено средствами Windows. Завершите вход в версии для Windows.",
                    "Session storage uses Windows protection. Complete sign-in in the Windows build."));
            return;
        }

        var title = DeyttTheme.TextBlock(Copy("Добавить приложение", "Add this app"), 22,
            DeyttTheme.Text, FontWeight.Bold);
        var detail = DeyttTheme.TextBlock(
            Copy("Укажи Telegram username. Бот отправит одноразовый код, чтобы подключить существующую подписку.",
                "Enter your Telegram username. The bot sends a one-time code to link your subscription."),
            13, DeyttTheme.Muted);
        var usernameInput = new TextBox
        {
            PlaceholderText = "@username",
            MaxLength = 33,
            MinHeight = 52,
            FontSize = 15,
            Foreground = DeyttTheme.Brush(DeyttTheme.Text),
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(15, 10),
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        var codeInput = new TextBox
        {
            PlaceholderText = "123456",
            MaxLength = 6,
            MinHeight = 58,
            FontSize = 22,
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Foreground = DeyttTheme.Brush(DeyttTheme.Text),
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(15, 10),
            IsVisible = false,
        };
        var status = DeyttTheme.TextBlock("", 12, DeyttTheme.Muted);
        var openBot = DeyttTheme.Action(
            DeyttTheme.TextBlock(Copy("Открыть бота в Telegram", "Open the Telegram bot"), 14,
                DeyttTheme.Sky, FontWeight.SemiBold), () => { });
        openBot.IsVisible = false;
        var primary = DeyttTheme.PrimaryButton(Copy("Получить код", "Get code"), () => { });
        var primaryButton = (Button)primary.Child!;
        var primaryLabel = (TextBlock)primaryButton.Content!;

        using var pairingCancellation = new CancellationTokenSource();
        ShellContentDialog? pairingDialog = null;
        var content = new StackPanel
        {
            Spacing = 13,
            Children = { title, detail, usernameInput, codeInput, status, openBot, primary },
        };

        var step = 0;
        var challenge = "";
        var botUrl = "";
        var needsBotStart = false;
        void SetStatus(string message, Color color)
        {
            status.Text = message;
            status.Foreground = DeyttTheme.Brush(color);
        }

        void SetStep(int value)
        {
            step = value;
            usernameInput.IsVisible = step == 0;
            codeInput.IsVisible = step == 1;
            openBot.IsVisible = step == 1 && needsBotStart;
            primaryLabel.Text = step switch
            {
                0 => Copy("Получить код", "Get code"),
                1 => Copy("Подтвердить код", "Verify code"),
                _ => Copy("Готово", "Done"),
            };
            title.Text = step switch
            {
                0 => Copy("Добавить приложение", "Add this app"),
                1 when needsBotStart => Copy("Открой бота", "Open the bot"),
                1 => Copy("Код отправлен", "Code sent"),
                _ => Copy("Аккаунт подключён", "Account connected"),
            };
            detail.Text = step switch
            {
                0 => Copy("Укажи Telegram username. Бот отправит одноразовый код, чтобы подключить существующую подписку.",
                    "Enter your Telegram username. The bot sends a one-time code to link your subscription."),
                1 when needsBotStart => Copy("Этот аккаунт ещё не начинал чат с ботом. Открой его один раз, затем введи код из сообщения.",
                    "This account has not started the bot chat yet. Open it once, then enter the code from its message."),
                1 => Copy("Введи шестизначный код из Telegram. Он действует пять минут.",
                    "Enter the six-digit code from Telegram. It expires in five minutes."),
                _ => Copy("Профиль и сведения о подписке загружены.", "Profile and subscription details are loaded."),
            };
        }

        openBot.Click += (_, _) =>
        {
            if (botUrl.Length > 0)
                OpenExternal(botUrl);
        };
        primaryButton.Click += async (_, _) =>
        {
            if (step == 2)
            {
                pairingDialog?.Close();
                return;
            }

            primaryButton.IsEnabled = false;
            try
            {
                if (step == 0)
                {
                    var username = usernameInput.Text?.Trim() ?? "";
                    SetStatus(Copy("Создаю запрос на вход…", "Starting sign-in…"), DeyttTheme.Blue);
                    var pairing = await _telegramApi.StartPairingAsync(username, pairingCancellation.Token);
                    if (pairingCancellation.IsCancellationRequested || pairingDialog?.Completion.IsCompleted == true)
                        return;
                    challenge = pairing.Challenge;
                    botUrl = pairing.BotUrl;
                    needsBotStart = pairing.Delivery != "sent";
                    SetStep(1);
                    SetStatus(pairing.Delivery switch
                    {
                        "sent" => Copy("Код отправлен в Telegram. Переключаться в бота не нужно.",
                            "The code was sent in Telegram. You do not need to open the bot."),
                        "delivery_failed" => Copy("Не удалось доставить код. Открой бота ниже и повтори.",
                            "The code could not be delivered. Open the bot below and try again."),
                        _ => Copy("Открой бота ниже, чтобы получить код для входа.",
                            "Open the bot below to receive your sign-in code."),
                    }, DeyttTheme.Blue);
                }
                else
                {
                    var code = codeInput.Text?.Trim() ?? "";
                    SetStatus(Copy("Проверяю код и загружаю профиль…", "Verifying the code and loading your profile…"),
                        DeyttTheme.Blue);
                    var pairing = await _telegramApi.VerifyPairingAsync(challenge, code, pairingCancellation.Token);
                    if (pairingCancellation.IsCancellationRequested || pairingDialog?.Completion.IsCompleted == true)
                        return;
                    WindowsSessionStore.Save(pairing.Token);
                    _sessionToken = pairing.Token;
                    _account = pairing.Account;
                    ShowTab(_activeTab);
                    var refreshFailed = false;
                    try
                    {
                        SetStatus(Copy("Код подтверждён. Загружаю подписку и маршруты…",
                            "Code verified. Loading your subscription and routes…"), DeyttTheme.Blue);
                        var progress = new Progress<string>(stage =>
                        {
                            if (pairingCancellation.IsCancellationRequested ||
                                pairingDialog?.Completion.IsCompleted == true)
                                return;
                            var message = stage switch
                            {
                                "metadata" => Copy("Проверяю подписку…", "Checking your subscription…"),
                                "profile" => Copy("Загружаю маршруты…", "Loading routes…"),
                                "optional" => Copy("Проверяю дополнительные маршруты…", "Checking optional routes…"),
                                "ready" => Copy("Маршруты готовы. Завершаю вход…", "Routes are ready. Finishing sign-in…"),
                                _ => Copy("Загружаю данные аккаунта…", "Loading account data…"),
                            };
                            SetStatus(message, DeyttTheme.Blue);
                        });
                        refreshFailed = !await RefreshAccountDataAsync(
                            pairing.Token, pairingCancellation.Token, pairing.Account, progress);
                    }
                    catch (TelegramApiException error) when (error.IsUnauthorized)
                    {
                        ClearLocalSession();
                        throw;
                    }
                    catch (OperationCanceledException) when (pairingCancellation.IsCancellationRequested)
                    {
                        throw;
                    }
                    catch (Exception error) when (error is TelegramApiException or HttpRequestException or OperationCanceledException or IOException)
                    {
                        refreshFailed = true;
                        _profileLoadIssue = Copy("Аккаунт подключён, но данные профиля не загрузились.",
                            "Account linked, but profile data could not be loaded.");
                    }

                    ShowTab(_activeTab);
                    SetStep(2);
                    SetStatus(refreshFailed
                        ? Copy("Аккаунт подключён, но не удалось обновить данные профиля.",
                            "Account linked, but the profile could not be refreshed.")
                        : Copy("Аккаунт подключён. Подписка загружена.", "Account linked. Subscription loaded."),
                        DeyttTheme.Mint);
                }
            }
            catch (TelegramApiException error)
            {
                SetStatus(PairingError(error), DeyttTheme.Coral);
                primaryLabel.Text = step == 0
                    ? Copy("Получить код", "Get code")
                    : Copy("Подтвердить код", "Verify code");
            }
            catch (HttpRequestException)
            {
                SetStatus(Copy("Не удалось связаться с сервером. Проверь интернет и повтори.",
                    "Could not reach the server. Check your connection and retry."), DeyttTheme.Coral);
                primaryLabel.Text = step == 0
                    ? Copy("Получить код", "Get code")
                    : Copy("Подтвердить код", "Verify code");
            }
            catch (OperationCanceledException) when (pairingCancellation.IsCancellationRequested ||
                                                       pairingDialog?.Completion.IsCompleted == true)
            {
                // Closing the dialog cancels its in-flight request; it is not a timeout.
            }
            catch (OperationCanceledException)
            {
                SetStatus(Copy("Запрос занял слишком много времени. Повтори попытку.",
                    "The request timed out. Please retry."), DeyttTheme.Coral);
                primaryLabel.Text = step == 0
                    ? Copy("Получить код", "Get code")
                    : Copy("Подтвердить код", "Verify code");
            }
            catch (CryptographicException)
            {
                SetStatus(Copy("Не удалось безопасно сохранить сеанс Windows.",
                    "Could not safely save the Windows session."), DeyttTheme.Coral);
                primaryLabel.Text = Copy("Подтвердить код", "Verify code");
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                SetStatus(Copy("Не удалось сохранить сеанс в папке данных Windows.",
                    "Could not save the session in Windows app data."), DeyttTheme.Coral);
                primaryLabel.Text = Copy("Подтвердить код", "Verify code");
            }
            finally
            {
                if (!pairingCancellation.IsCancellationRequested && pairingDialog?.Completion.IsCompleted != true)
                    primaryButton.IsEnabled = true;
            }
        };

        SetStep(0);
        try
        {
            await ShowContentInShellAsync(
                Copy("Вход через Telegram", "Telegram sign-in"), content, 500,
                modal =>
                {
                    pairingDialog = modal;
                    modal.Closed += () => pairingCancellation.Cancel();
                });
        }
        finally
        {
            if (!pairingCancellation.IsCancellationRequested)
                pairingCancellation.Cancel();
        }
    }

    private string PairingError(TelegramApiException error) => error.Code switch
    {
        "invalid_username" => Copy("Проверь Telegram username: от 5 до 32 латинских букв, цифр или _.",
            "Enter a Telegram username with 5–32 Latin letters, digits, or underscores."),
        "rate_limited" or "pairing_rate_limited" => Copy("Слишком много попыток. Подожди немного и повтори.",
            "Too many attempts. Wait a little and retry."),
        "pair_code_invalid" => Copy("Код неверный или истёк. Запроси новый код.",
            "The code is invalid or expired. Request a new one."),
        "pair_code_locked" => Copy("Попытки исчерпаны. Запроси новый код.",
            "Too many code attempts. Request a new one."),
        "account_blocked" => Copy("Этот Telegram-аккаунт заблокирован.",
            "This Telegram account is blocked."),
        "not_registered" => Copy("Сначала открой бота DEYTT в Telegram и создай аккаунт.",
            "Start the DEYTT bot in Telegram and create an account first."),
        "pairing_unavailable" => Copy("Вход через Telegram сейчас недоступен.",
            "Telegram sign-in is currently unavailable."),
        "session_expired" or "session_invalid" => Copy("Сеанс истёк. Войди через Telegram ещё раз.",
            "Your session expired. Sign in with Telegram again."),
        _ => Copy("Сервер не подтвердил вход. Повтори попытку позже.",
            "The server could not confirm sign-in. Please retry later."),
    };

    private string SubscriptionSummary(TelegramSubscription? subscription)
    {
        if (subscription is null)
            return Copy("Профиль загружен. Нет данных о подписке.", "Profile loaded. Subscription details are unavailable.");
        if (!subscription.Active)
            return Copy("Подписка не активна", "Subscription is inactive");

        var details = new List<string>();
        if (subscription.UnlimitedTime)
            details.Add(Copy("Без ограничения по времени", "No time limit"));
        else if (DateTimeOffset.TryParse(subscription.ExpiresAt, out var expiresAt))
            details.Add($"{Copy("до", "until")} {expiresAt.ToLocalTime():d MMM yyyy}");
        if (subscription.DeviceLimit is int deviceLimit)
            details.Add($"{Copy("устройства", "devices")}: {subscription.DevicesUsed?.ToString() ?? "0"}/{deviceLimit}");
        if (subscription.TrafficLimitBytes is > 0 and long trafficLimit)
            details.Add($"{FormatBytes(subscription.TrafficUsedBytes ?? 0)} / {FormatBytes(trafficLimit)}");
        if (subscription.TrafficLimitBytes is > 0)
        {
            if (subscription.TrafficTotalBytes is >= 0 and long trafficTotal)
                details.Add($"{Copy("всего передано", "total transferred")}: {FormatBytes(trafficTotal)}");
        }
        else
        {
            var trafficUsed = Math.Max(0, subscription.TrafficUsedBytes ?? 0);
            var trafficTotal = Math.Max(0, subscription.TrafficTotalBytes ?? 0);
            var transferred = trafficTotal > 0 ? trafficTotal : trafficUsed;
            if (transferred > 0)
                details.Add($"{Copy("передано", "transferred")}: {FormatBytes(transferred)} · {Copy("без установленного лимита", "no quota limit")}");
        }
        return details.Count > 0 ? string.Join(" · ", details) : Copy("Подписка активна", "Subscription active");
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024)
            return $"{bytes} b";
        var units = new[] { "kb", "mb", "gb", "tb" };
        double value = bytes;
        var unit = -1;
        do
        {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.Length - 1);
        return $"{value:0.#} {units[unit]}";
    }

    private async void SignOut()
    {
        var token = _sessionToken;
        if (token is null)
            return;

        try
        {
            await _telegramApi.LogoutAsync(token);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            // The server has already invalidated this session.
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            ShowInfoDialog(Copy("Не удалось завершить сеанс", "Could not end the session"),
                Copy("Проверь интернет и повтори выход.", "Check your connection and try signing out again."));
            return;
        }

        var localStateCleared = ClearLocalSession();
        ShowTab(_activeTab);
        if (!localStateCleared)
            ShowInfoDialog(Copy("Сеанс завершён", "Session signed out"), _profileLoadIssue ?? "");
    }

    private async void ShowInfoDialog(string title, string message) =>
        await ShowInfoInShellAsync(title, message);

    private void OpenExternal(string url)
    {
        try
        {
            if (!Uri.TryCreate(url, UriKind.Absolute, out var parsedUri) ||
                (!string.Equals(parsedUri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase) &&
                 !string.Equals(parsedUri.Scheme, "tg", StringComparison.OrdinalIgnoreCase)))
            {
                return;
            }

            Process.Start(new ProcessStartInfo(parsedUri.AbsoluteUri) { UseShellExecute = true });
        }
        catch (Exception error)
        {
            ShowInfoDialog(Copy("Не удалось открыть ссылку", "Could not open link"), error.Message);
        }
    }

    private string Copy(string russian, string english) => (_language == "ru" ? russian : english).ToLowerInvariant();
}
