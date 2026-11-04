using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using DeyttConnect.Protocol;
using DeyttConnect.Windows.Services;

namespace DeyttConnect.Windows.Views;

public partial class MainWindow
{
    private void RenderActiveTabPreservingScroll()
    {
        var offset = PageScroll.Offset;
        ShowTab(_activeTab);
        PageScroll.Offset = offset;
    }

    private void ShowSignInDialog() => _ = ShowSetupWindowAsync();

    private async Task RestoreSessionAsync(CancellationToken cancellationToken = default)
    {
        if (!OperatingSystem.IsWindows())
            return;

        string? token;
        try
        {
            token = WindowsSessionStore.Load();
        }
        catch (Exception error) when (error is CryptographicException or IOException or UnauthorizedAccessException)
        {
            return;
        }

        if (token is null)
        {
            RestoreImportedSubscription();
            return;
        }

        _sessionToken = token;
        var subscriptionSnapshotLoaded = false;
        try
        {
            await RefreshAccountDataAsync(token, cancellationToken);
            // A successful API response, including an explicit no-subscription
            // result, is authoritative. Do not replace it with the older offline cache.
            subscriptionSnapshotLoaded = _keysSnapshot is not null;
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or OperationCanceledException or IOException)
        {
            _account = null;
            _keysSnapshot = null;
            _routes = [];
            _profileLoadIssue = Copy("Не удалось загрузить данные аккаунта.", "Could not load account data.");
        }

        if (!subscriptionSnapshotLoaded)
            RestoreImportedSubscription();

        if (IsVisible)
            ShowTab(_activeTab);
    }

    private async Task<bool> RefreshAccountDataAsync(
        string token,
        CancellationToken cancellationToken = default,
        TelegramAccount? verifiedAccount = null,
        IProgress<string>? progress = null)
    {
        var accountTask = verifiedAccount is null
            ? _telegramApi.GetAccountAsync(token, cancellationToken)
            : Task.FromResult(verifiedAccount);
        var subscriptionTask = _telegramApi.GetSubscriptionAsync(token, cancellationToken, progress);
        var avatarTask = RefreshTelegramAvatarAsync(token, cancellationToken);
        var sessionsTask = LoadSessionsForRefreshAsync(token, cancellationToken);
        if (verifiedAccount is not null)
            _account = verifiedAccount;

        try
        {
            await Task.WhenAll(accountTask, subscriptionTask, avatarTask, sessionsTask);
            _account = await accountTask;
            _keysSnapshot = await subscriptionTask;
            _sessions = await sessionsTask ?? [];
            _routes = _keysSnapshot.Routes;
            _profileLoadIssue = _keysSnapshot.HappAvailable
                ? null
                : Copy("Активная подписка не найдена", "No active subscription found");
            if (!_routes.Any(route => route.Id == _selectedRoute))
            {
                _selectedRoute = "auto";
                SavePreferences();
            }
            return _keysSnapshot.HappAvailable;
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            throw;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or OperationCanceledException or IOException)
        {
            if (accountTask.IsCompletedSuccessfully)
                _account = accountTask.Result;
            _keysSnapshot = null;
            _routes = [];
            _profileLoadIssue = error is TelegramApiException { Code: "subscription_invalid_routes_outbounds_empty" or
                "subscription_invalid_routes_outbounds_no_network" }
                ? Copy("Подписка не содержит доступных маршрутов. Проверь её и повтори обновление позже.",
                    "The subscription has no available routes. Check it and refresh again later.")
                : Copy("Не удалось загрузить маршруты. Повтори обновление позже.",
                    "Could not load routes. Try refreshing again later.");
            return false;
        }
    }

    private async Task<IReadOnlyList<TelegramAppSession>?> LoadSessionsForRefreshAsync(
        string token,
        CancellationToken cancellationToken)
    {
        try
        {
            var sessions = await _telegramApi.GetSessionsAsync(token, cancellationToken);
            _sessionLoadIssue = null;
            return sessions.Where(session =>
                string.Equals(session.Client, "deytt-connect", StringComparison.OrdinalIgnoreCase)).ToArray();
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            throw;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or OperationCanceledException or IOException)
        {
            _sessionLoadIssue = Copy("Проверь интернет и повтори обновление.",
                "Check your connection and refresh again.");
            return null;
        }
    }

    private async Task RefreshSignedInAccountAsync()
    {
        var token = _sessionToken;
        if (token is null)
        {
            ShowSignInDialog();
            return;
        }

        _profileRefreshInProgress = true;
        if (IsVisible)
            RenderActiveTabPreservingScroll();
        try
        {
            await RefreshAccountDataAsync(token);
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or OperationCanceledException or IOException)
        {
            ShowInfoDialog(Copy("Не удалось обновить профиль", "Could not refresh profile"),
                Copy("Проверь интернет и повтори попытку.", "Check your connection and try again."));
        }
        finally
        {
            _profileRefreshInProgress = false;
            if (IsVisible)
                RenderActiveTabPreservingScroll();
        }
    }

    private async Task ToggleHappDeviceAsync(TelegramHappDevice device)
    {
        var token = _sessionToken;
        if (token is null || device.Id <= 0 || _happDeviceChangesInFlight.Contains(device.Id))
            return;
        var nextBlocked = !device.Blocked;
        var title = nextBlocked
            ? Copy("Заблокировать устройство?", "Block this device?")
            : Copy("Вернуть устройство?", "Restore this device?");
        var detail = nextBlocked
            ? Copy("Устройство больше не сможет обновлять подписку. Уже загруженные ключи и активное VPN-подключение продолжат работать. Чтобы отозвать ключи, используйте общий сброс Happ.",
                "This device will no longer be able to refresh its subscription. Downloaded keys and an active VPN connection will keep working. Use the Happ key reset to revoke keys.")
            : Copy("Устройство снова сможет обновлять подписку. Сервер проверит, есть ли свободное место в вашем плане.",
                "This device will be able to refresh its subscription again. The server will check for a free device slot in your plan.");
        if (!await ConfirmDialogAsync(title, detail,
                nextBlocked ? Copy("Заблокировать", "Block") : Copy("Восстановить", "Restore")))
            return;
        if (_sessionToken != token || !_happDeviceChangesInFlight.Add(device.Id))
            return;

        try
        {
            await _telegramApi.SetHappDeviceBlockedAsync(token, device.Id, nextBlocked);
            try
            {
                await RefreshAccountDataAsync(token);
                ShowTab(_activeTab);
            }
            catch (TelegramApiException error) when (error.IsUnauthorized)
            {
                throw;
            }
            catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
            {
                ShowTab(_activeTab);
                ShowInfoDialog(Copy("Изменение подтверждено", "Change confirmed"),
                    Copy("Устройство обновлено, но список не удалось перезагрузить.",
                        "The device was updated, but the list could not be refreshed."));
            }
        }
        catch (TelegramApiException error) when (error.IsUnauthorized)
        {
            ClearLocalSession();
            ShowTab(_activeTab);
            ShowInfoDialog(Copy("Сеанс истёк", "Session expired"),
                Copy("Войди через Telegram ещё раз.", "Sign in with Telegram again."));
        }
        catch (TelegramApiException error) when (error.Code is
                   "device_limit_reached" or "device_slot_limit_reached" or
                   "device_not_found" or "happ_device_not_found")
        {
            _happDeviceChangesInFlight.Remove(device.Id);
            await ShowHappDeviceActionErrorAsync(error, device, token);
        }
        catch (Exception error) when (error is TelegramApiException or HttpRequestException or TaskCanceledException or IOException)
        {
            ShowInfoDialog(Copy("Не удалось обновить устройство", "Could not update the device"),
                Copy("Сервер не подтвердил изменение. Проверь интернет и обнови список.",
                    "The server did not confirm the change. Check your connection and refresh the list."));
        }
        finally
        {
            _happDeviceChangesInFlight.Remove(device.Id);
        }
    }

    private async Task ManageSessionAsync(TelegramAppSession session)
    {
        var sessionToken = _sessionToken;
        if (_sessionRevokesInFlight.Contains(session.Id))
            return;
        var detail = session.Current
            ? Copy("Это текущий сеанс на этом устройстве.", "This is the current session on this device.")
            : Copy("Этот сеанс можно завершить. VPN-ключи останутся действительными.",
                "You can end this session. VPN keys will remain valid.");
        detail += $"\n{Copy("Вход", "Signed in")}: {FormatLastSeen(session.CreatedAt)}";
        if (!string.IsNullOrWhiteSpace(session.ExpiresAt))
            detail += $"\n{Copy("Действует до", "Valid until")}: {FormatLastSeen(session.ExpiresAt)}";

        if (session.Current)
        {
            ShowInfoDialog(session.Label, detail);
            return;
        }
        if (string.IsNullOrWhiteSpace(session.Id))
        {
            ShowInfoDialog(session.Label, detail);
            return;
        }
        var confirmation = detail + "\n" + Copy(
            "На другом устройстве потребуется снова войти через Telegram. VPN-ключи останутся действительными.",
            "The other device will need to sign in through Telegram again. VPN keys will remain valid.");
        if (!await ConfirmDialogAsync(Copy("Завершить сеанс?", "End this session?"), confirmation,
                Copy("Завершить", "End session")))
            return;

        var token = _sessionToken;
        if (token is null)
        {
            ShowSignInDialog();
            return;
        }
        if (token != sessionToken)
            return;
        if (!_sessionRevokesInFlight.Add(session.Id))
            return;
        try
        {
            await _telegramApi.RevokeSessionAsync(token, session.Id);
            if (_sessionToken != token)
                return;
            await RefreshAccountDataAsync(token);
            ShowTab(MainTab.Profile);
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
            ShowInfoDialog(Copy("Не удалось завершить сеанс", "Could not end session"),
                Copy("Сервер не подтвердил действие. Обнови список и повтори попытку.",
                    "The server did not confirm the action. Refresh the list and try again."));
        }
        finally
        {
            _sessionRevokesInFlight.Remove(session.Id);
        }
    }

    private bool ClearLocalSession()
    {
        var cleared = true;
        try
        {
            WindowsSessionStore.Clear();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            _profileLoadIssue = Copy("Не удалось удалить сохранённый сеанс Windows.",
                "Could not remove the saved Windows session.");
            cleared = false;
        }

        _sessionToken = null;
        _account = null;
        ClearTelegramAvatar();
        _keysSnapshot = null;
        _sessions = [];
        _routes = [];
        _selectedRoute = "auto";
        _paymentFlow.Reset();
        _profileActionStatus = null;
        _profileActionRetry = null;
        _sessionLoadIssue = null;
        if (cleared)
            _profileLoadIssue = null;
        SavePreferences();
        return cleared;
    }
}
