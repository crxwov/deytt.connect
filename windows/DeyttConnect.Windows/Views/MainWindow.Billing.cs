using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.UI;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private async Task ShowPlanPickerAsync()
    {
        var token = _sessionToken;
        if (token is null)
        {
            ShowSignInDialog();
            return;
        }

        if (!_paymentFlow.TryEnterBillingFlow())
            return;

        try
        {
            _profileActionRetry = null;
            _profileActionStatus = Copy("Загружаем планы…", "Loading plans…");
            ShowTab(MainTab.Profile);
            var account = await _telegramApi.GetAccountAsync(token);
            if (_sessionToken != token)
                return;
            _account = account;
            _profileActionStatus = null;
            ShowTab(MainTab.Profile);

            var subscription = account.Subscription;
            if (subscription?.PaidActive == true && subscription.UnlimitedTime)
            {
                var contact = await ChooseOptionAsync(
                    Copy("Бессрочная подписка", "Lifetime subscription"),
                    Copy("Продлевать её не нужно. Если хотите изменить условия, напишите в поддержку.",
                        "No renewal is needed. Contact support if you want to change your plan."),
                    [
                        (Copy("Открыть поддержку", "Open support"), Copy("В приложении", "In app"), "support"),
                        (Copy("Открыть Telegram", "Open Telegram"), Copy("Бот DEYTT", "DEYTT bot"), "telegram"),
                    ]);
                if (contact == "support")
                    await ShowSupportWindowAsync();
                else if (contact == "telegram")
                    OpenExternal("https://t.me/deyttbot");
                return;
            }

            if (subscription?.PaidActive == true && subscription.DeviceLimit is null)
            {
                var contact = await ChooseOptionAsync(
                    Copy("План без лимита устройств", "Unlimited device plan"),
                    Copy("Поддержка поможет продлить его с сохранением индивидуальных условий.",
                        "Support can renew it while preserving your custom terms."),
                    [
                        (Copy("Открыть поддержку", "Open support"), Copy("В приложении", "In app"), "support"),
                        (Copy("Открыть Telegram", "Open Telegram"), Copy("Бот DEYTT", "DEYTT bot"), "telegram"),
                    ]);
                if (contact == "support")
                    await ShowSupportWindowAsync();
                else if (contact == "telegram")
                    OpenExternal("https://t.me/deyttbot");
                return;
            }

            if (subscription?.PaidActive == true)
            {
                var change = await ChooseOptionAsync(
                    Copy("Управление подпиской", "Manage subscription"),
                    Copy("Выберите, что хотите изменить.", "Choose what you want to change."),
                    [
                        (Copy("Продлить срок", "Extend subscription"), Copy("Добавить месяцы к текущему плану", "Add months to your plan"), "add_time"),
                        (Copy("Добавить устройства", "Add devices"), Copy("Увеличить лимит подключений", "Increase your device limit"), "add_devices"),
                    ]);
                if (change == "add_time")
                    await ChooseSubscriptionDurationAsync(token);
                else if (change == "add_devices")
                {
                    var currentLimit = Math.Max(1, subscription.DeviceLimit ?? 1);
                    var maximum = Math.Max(0, 10 - currentLimit);
                    if (maximum == 0)
                    {
                        ShowInfoDialog(Copy("Лимит устройств", "Device limit"),
                            Copy("Для текущего плана уже достигнут доступный лимит. Изменить условия поможет поддержка.",
                                "Your plan has reached the available device limit. Contact support to change it."));
                        return;
                    }
                    var extra = await ShowNumberDialogAsync(Copy("Сколько добавить?", "How many to add?"),
                        Copy("Количество дополнительных устройств", "Additional device slots"), 1, maximum);
                    if (extra is not null)
                        await CreateQuoteAndCheckoutAsync(token, "add_devices", null, null, null, extra);
                }
                return;
            }

            var tariffs = await _telegramApi.GetTariffsAsync();
            if (_sessionToken != token)
                return;
            if (tariffs.Count == 0)
            {
                ShowInfoDialog(Copy("Планы временно недоступны", "Plans are unavailable"),
                    Copy("Попробуйте снова позже или напишите в поддержку.",
                        "Try again later or contact support."));
                return;
            }

            var choices = tariffs.Select(tariff =>
                ($"{tariff.Name} · {tariff.Devices} {Copy("устр.", "devices")} · {tariff.Months} {Copy("мес.", "months")} · {tariff.Rubles} ₽",
                    Copy("Точную сумму подтвердит сервер", "The server confirms the final total"),
                    tariff.Code)).ToList();
            choices.Add((Copy("Свой план", "Custom plan"),
                Copy("Выберите число устройств и срок", "Choose the number of devices and duration"), "custom"));
            var selectedPlan = await ChooseOptionAsync(Copy("Выберите план", "Choose a plan"),
                Copy("Сначала получите расчёт. Оплата начнётся только после подтверждения суммы.",
                    "Review a server quote first. Payment starts only after you confirm the total."), choices);
            if (selectedPlan is null)
                return;
            if (selectedPlan == "custom")
            {
                var devices = await ShowNumberDialogAsync(Copy("Количество устройств", "Number of devices"),
                    Copy("Можно выбрать от 1 до 10 устройств.", "Choose 1 to 10 devices."), 1, 10);
                if (devices is null)
                    return;
                var months = await ShowNumberDialogAsync(Copy("Срок подписки", "Subscription duration"),
                    Copy("Количество месяцев от 1 до 36.", "Choose from 1 to 36 months."), 1, 36);
                if (months is not null)
                    await CreateQuoteAndCheckoutAsync(token, "custom", null, months, devices, null);
                return;
            }
            var selectedTariff = tariffs.FirstOrDefault(tariff => tariff.Code == selectedPlan);
            await CreateQuoteAndCheckoutAsync(token, "preset", selectedPlan, null, null, null,
                selectedTariff?.Name);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            _profileActionStatus = null;
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Не удалось загрузить планы", "Could not load plans"),
                Copy("Проверьте интернет и повторите попытку. Сервер не подтвердил запрос.",
                    "Check your connection and retry. The server did not confirm the request."));
        }
        finally
        {
            _paymentFlow.ExitBillingFlow();
        }
    }

    private async Task ChooseSubscriptionDurationAsync(string token)
    {
        var choices = new List<(string Title, string Detail, string Value)>
        {
            (Copy("1 месяц", "1 month"), Copy("Добавить к подписке", "Add to your subscription"), "1"),
            (Copy("3 месяца", "3 months"), Copy("Добавить к подписке", "Add to your subscription"), "3"),
            (Copy("6 месяцев", "6 months"), Copy("Добавить к подписке", "Add to your subscription"), "6"),
            (Copy("12 месяцев", "12 months"), Copy("Добавить к подписке", "Add to your subscription"), "12"),
            (Copy("Другой срок", "Custom duration"), Copy("От 1 до 36 месяцев", "From 1 to 36 months"), "custom"),
        };
        var selected = await ChooseOptionAsync(Copy("Срок подписки", "Subscription duration"),
            Copy("Выберите срок, затем проверьте расчёт перед оплатой.",
                "Choose a duration and review the quote before paying."), choices);
        if (selected is null)
            return;
        int? months = selected == "custom"
            ? await ShowNumberDialogAsync(Copy("Количество месяцев", "Number of months"),
                Copy("Можно выбрать от 1 до 36 месяцев.", "Choose from 1 to 36 months."), 1, 36)
            : int.Parse(selected, System.Globalization.CultureInfo.InvariantCulture);
        if (months is not null && _sessionToken == token)
            await CreateQuoteAndCheckoutAsync(token, "add_time", null, months, null, null);
    }

    private async Task CreateQuoteAndCheckoutAsync(
        string token,
        string kind,
        string? plan,
        int? months,
        int? devices,
        int? extra,
        string? selectedPlanLabel = null)
    {
        try
        {
            _profileActionRetry = null;
            _profileActionStatus = Copy("Рассчитываем стоимость…", "Calculating the total…");
            ShowTab(MainTab.Profile);
            var checkout = await _paymentFlow.StartAsync(
                () => _telegramApi.GetQuoteAsync(token, kind, plan, months, devices, extra),
                quote => ChooseOptionAsync(Copy("Подтвердите сумму", "Confirm the total"),
                    BuildQuoteDetail(quote, kind),
                    [
                        (Copy("Оплатить картой", "Pay by card"),
                            Copy($"Защищённая страница · {quote.Rubles} ₽", $"Secure checkout · {quote.Rubles} ₽"), "platega"),
                        (Copy("Telegram Stars", "Telegram Stars"),
                            Copy($"Оплата через Telegram · ⭐ {quote.Stars}", $"Pay through Telegram · ⭐ {quote.Stars}"), "stars"),
                    ]),
                method => _telegramApi.CreateCheckoutAsync(token, kind, method, plan, months, devices, extra));
            if (_sessionToken != token)
                return;
            if (checkout is null)
            {
                _profileActionStatus = null;
                ShowTab(MainTab.Profile);
                return;
            }
            await PresentCheckoutAsync(checkout);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            if (_sessionToken != token)
                return;
            if (_paymentFlow.CanRetry)
            {
                var selection = selectedPlanLabel ?? plan ??
                    (months is { } duration
                        ? Copy($"План на {duration} мес.", $"{duration}-month plan")
                        : Copy("Выбранный план", "Selected plan"));
                _profileActionStatus = $"{selection} · {BillingError(error)}";
                _profileActionRetry = () => RetryPaymentFlowAsync(token);
                ShowTab(MainTab.Profile);
                return;
            }
            _profileActionStatus = null;
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Не удалось подготовить оплату", "Could not prepare payment"),
                BillingError(error));
        }
    }

    private string BuildQuoteDetail(TelegramQuote quote, string kind)
    {
        var duration = kind == "add_devices"
            ? Copy("до конца текущей подписки", "until the current subscription ends")
            : Copy($"{quote.Months} мес.", $"{quote.Months} months");
        return $"{quote.Name}\n{quote.Devices} {Copy("устройств", "devices")} · {duration}\n{quote.Rubles} ₽ · ⭐ {quote.Stars}";
    }

    private async Task RetryPaymentFlowAsync(string token)
    {
        if (_sessionToken != token)
            return;
        if (!_paymentFlow.TryEnterBillingFlow())
            return;
        try
        {
            _profileActionRetry = null;
            _profileActionStatus = Copy("Повторяем подготовку счёта…", "Retrying checkout preparation…");
            ShowTab(MainTab.Profile);
            var checkout = await _paymentFlow.RetryAsync();
            if (_sessionToken != token)
                return;
            if (checkout is null)
            {
                _profileActionStatus = null;
                ShowTab(MainTab.Profile);
                return;
            }
            await PresentCheckoutAsync(checkout);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            if (_sessionToken != token)
                return;
            _profileActionStatus = BillingError(error);
            _profileActionRetry = () => RetryPaymentFlowAsync(token);
            ShowTab(MainTab.Profile);
        }
        finally
        {
            _paymentFlow.ExitBillingFlow();
        }
    }

    private async Task PresentCheckoutAsync(TelegramCheckout checkout)
    {
        var hasPendingId = _paymentFlow.PendingPaymentId is not null;
        _profileActionStatus = !hasPendingId
            ? Copy("Счёт создан. Откройте страницу оплаты, чтобы продолжить.",
                "Checkout is ready. Open the payment page to continue.")
            : Copy("Счёт создан. Статус можно проверить в профиле.",
                "Checkout is ready. You can check its status from Profile.");
        ShowTab(MainTab.Profile);

        var nextStep = await ChooseOptionAsync(Copy("Счёт готов", "Checkout is ready"),
            Copy("Откройте защищённую страницу оплаты. Приложение не запрашивает данные карты.",
                "Open the secure payment page. The app never asks for card details."),
            [
                (Copy("Открыть оплату", "Open payment"), Copy("Перейти в браузер", "Continue in your browser"), "open"),
                .. (!hasPendingId
                    ? Array.Empty<(string Title, string Detail, string Value)>()
                    : new[] { (Copy("Проверить статус", "Check status"), Copy("Запросить подтверждение у сервера", "Ask the server for confirmation"), "check") }),
            ]);
        if (nextStep == "open")
            OpenExternal(checkout.PaymentUrl);
        else if (nextStep == "check")
            await CheckPaymentStatusAsync();
    }

    private async Task CheckPaymentStatusAsync()
    {
        var token = _sessionToken;
        var externalId = _paymentFlow.PendingPaymentId;
        if (token is null || externalId is null)
        {
            ShowInfoDialog(Copy("Нет ожидающего платежа", "No pending payment"),
                Copy("Создайте счёт в разделе тарифов и оплаты.", "Create a checkout from Plans and payment."));
            return;
        }

        _profileActionRetry = null;
        _profileActionStatus = Copy("Проверяем платёж…", "Checking payment…");
        ShowTab(MainTab.Profile);
        try
        {
            var status = await _telegramApi.GetPaymentStatusAsync(token, externalId);
            if (_sessionToken != token)
                return;
            if (status == "paid")
            {
                var refreshed = await RefreshAccountDataAsync(token);
                _paymentFlow.ApplyPaymentStatus(status, externalId, refreshed);
                _profileActionStatus = refreshed
                    ? Copy("Оплата подтверждена", "Payment confirmed")
                    : Copy("Оплата подтверждена, но данные подписки не обновились.",
                        "Payment confirmed, but subscription details could not be refreshed.");
                ShowTab(MainTab.Profile);
                ShowInfoDialog(Copy("Оплата подтверждена", "Payment confirmed"),
                    refreshed
                        ? Copy("Данные подписки обновлены.", "Your subscription details have been refreshed.")
                        : Copy("Оплата прошла. Обновите профиль позже, чтобы загрузить данные подписки.",
                            "Payment succeeded. Refresh Profile later to load subscription details."));
            }
            else
            {
                _profileActionStatus = Copy("Платёж ещё не подтверждён. Проверьте позже.",
                    "Payment is not confirmed yet. Check again later.");
                ShowTab(MainTab.Profile);
            }
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            _profileActionStatus = BillingError(error);
            ShowTab(MainTab.Profile);
        }
    }

    private async Task ShowResetChoicesAsync()
    {
        var token = _sessionToken;
        if (token is null)
        {
            ShowSignInDialog();
            return;
        }

        var selected = await ChooseOptionAsync(Copy("Что сбросить?", "What should be reset?"),
            Copy("Сброс отзовёт ключи и может отключить активные устройства.",
                "Resetting keys revokes access and may disconnect active devices."),
            [
                (Copy("Все ключи", "All keys"), Copy("Happ и AmneziaWG", "Happ and AmneziaWG"), "all"),
                ("AmneziaWG", Copy("Ключи этого протокола", "Keys for this protocol"), "awg"),
                ("Happ", Copy("Ключи этого приложения", "Keys for this app"), "happ"),
            ]);
        if (selected is null)
            return;
        var scopeLabel = selected == "all" ? Copy("все ключи", "all keys") : selected == "awg" ? "AmneziaWG" : "Happ";
        if (!await ConfirmDialogAsync(Copy($"Сбросить {scopeLabel}?", $"Reset {scopeLabel}?"),
                Copy("Текущие ключи будут отозваны. Это может временно отключить устройства.",
                    "Current keys will be revoked. This can temporarily disconnect devices."),
                Copy("Сбросить", "Reset")))
            return;

        _profileActionStatus = Copy("Сбрасываем ключи…", "Resetting keys…");
        ShowTab(MainTab.Profile);
        try
        {
            await _telegramApi.ResetKeysAsync(token, selected);
            if (_sessionToken != token)
                return;
            _profileActionStatus = Copy("Ключи сброшены. Обновляем аккаунт…", "Keys reset. Refreshing account…");
            await RefreshAccountDataAsync(token);
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Ключи сброшены", "Keys reset"),
                Copy("Доступ обновлён. При необходимости переподключите устройства.",
                    "Access has been refreshed. Reconnect your devices if needed."));
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            _profileActionStatus = null;
            ShowTab(MainTab.Profile);
            ShowInfoDialog(Copy("Не удалось сбросить ключи", "Could not reset keys"),
                Copy("Сервер не подтвердил сброс. Обновите профиль и попробуйте снова.",
                    "The server did not confirm the reset. Refresh your profile and retry."));
        }
    }

    private async Task<string?> ChooseOptionAsync(
        string title,
        string detail,
        IReadOnlyList<(string Title, string Detail, string Value)> options)
    {
        var choices = new StackPanel { Spacing = 7 };
        var optionButtons = new List<(string Value, Button Button)>();
        foreach (var option in options)
        {
            var labels = new StackPanel { Spacing = 3 };
            labels.Children.Add(DeyttTheme.TextBlock(option.Title, 15, DeyttTheme.Text, FontWeight.SemiBold));
            if (!string.IsNullOrWhiteSpace(option.Detail))
                labels.Children.Add(DeyttTheme.TextBlock(option.Detail, 12, DeyttTheme.Muted));
            var button = DeyttTheme.Action(labels, () => { });
            button.Padding = new Thickness(13, 10);
            optionButtons.Add((option.Value, button));
            choices.Children.Add(new Border
            {
                Background = DeyttTheme.Brush(DeyttTheme.Surface),
                BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(13),
                Child = button,
            });
        }
        var cancel = DeyttTheme.Action(DeyttTheme.TextBlock(Copy("Отмена", "Cancel"), 13,
            DeyttTheme.Muted, FontWeight.SemiBold), () => { });
        cancel.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        cancel.Margin = new Thickness(0, 3, 0, 0);
        choices.Children.Add(cancel);
        var content = new StackPanel
        {
            Spacing = 12,
            Children =
            {
                DeyttTheme.TextBlock(detail, 13, DeyttTheme.Muted),
                choices,
            },
        };
        var result = await ShowContentInShellAsync(title, content, 560, modal =>
        {
            foreach (var (value, button) in optionButtons)
                button.Click += (_, _) => modal.Close(value);
            cancel.Click += (_, _) => modal.Close();
        });
        return result as string;
    }

    private async Task<int?> ShowNumberDialogAsync(string title, string detail, int minimum, int maximum)
    {
        var value = new NumericUpDown
        {
            Minimum = minimum,
            Maximum = maximum,
            Increment = 1,
            Value = minimum,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
        };
        var cancel = DeyttTheme.Action(DeyttTheme.TextBlock(Copy("Отмена", "Cancel"), 13,
            DeyttTheme.Muted, FontWeight.SemiBold), () => { });
        cancel.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        var confirm = DeyttTheme.PrimaryButton(Copy("Продолжить", "Continue"), () => { });
        var content = new StackPanel
        {
            Spacing = 13,
            Children =
            {
                DeyttTheme.TextBlock(detail, 13, DeyttTheme.Muted),
                value,
                cancel,
                confirm,
            },
        };
        var result = await ShowContentInShellAsync(title, content, 460, modal =>
        {
            cancel.Click += (_, _) => modal.Close();
            if (confirm.Child is Button confirmButton)
                confirmButton.Click += (_, _) =>
                {
                    if (value.Value is { } number)
                        modal.Close(decimal.ToInt32(number));
                };
        });
        return result is int selected ? selected : null;
    }

    private string BillingError(Exception error)
    {
        if (error is not TelegramApiException apiError)
            return Copy("Не удалось связаться с сервером. Проверьте интернет и повторите попытку.",
                "Could not reach the server. Check your connection and retry.");

        return apiError.Code switch
        {
            "rate_limited" => Copy("Слишком частые запросы. Повторите позже.",
                "Too many requests. Try again later."),
            "payment_unavailable" => Copy("Оплата сейчас недоступна. Повторите позже.",
                "Payments are unavailable right now. Try again later."),
            "payment_not_found" => Copy("Сервер не нашёл этот платёж. Проверьте счёт в профиле.",
                "The server could not find this payment. Check the checkout in Profile."),
            "bad_custom_params" or "bad_plan" => Copy("Сервер не смог рассчитать выбранный план. Проверьте параметры.",
                "The server could not price this plan. Check your selection."),
            "device_limit_exceeded" or "invalid_extra" or "devices_max" =>
                Copy("Выберите меньше дополнительных устройств.", "Choose fewer additional devices."),
            "subscription_unlimited" or "unlimited_subscription" =>
                Copy("Бессрочный доступ не требует продления.", "Lifetime access does not need renewal."),
            "unlimited_devices_plan" => Copy("Изменить этот план поможет поддержка.",
                "Contact support to change this plan."),
            var code when code.Length is > 0 and <= 64 &&
                code.All(character => char.IsAsciiLetterOrDigit(character) || character is '_' or '-') =>
                Copy($"Ошибка сервера: {code}", $"Server error: {code}"),
            _ => Copy("Сервер не смог выполнить запрос. Повторите попытку позже.",
                "The server could not complete the request. Try again later."),
        };
    }
}
