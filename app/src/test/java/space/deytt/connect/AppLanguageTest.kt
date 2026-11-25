package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun translatesPrimaryNavigationAndUppercaseSectionLabels() {
        assertEquals("Routes", AppLanguage.english("Маршруты"))
        assertEquals("QUICK SELECT", AppLanguage.english("БЫСТРЫЙ ВЫБОР"))
        assertEquals("LANGUAGE", AppLanguage.english("ЯЗЫК"))
        assertEquals("AMNEZIAWG · PROFILES", AppLanguage.english("AMNEZIAWG · ГОТОВЫЕ ПРОФИЛИ"))
        assertEquals("PRIVATE · ON DEVICE", AppLanguage.english("ЧАСТНО · НА УСТРОЙСТВЕ"))
        assertEquals("method", AppLanguage.english("метод"))
        assertEquals("Main navigation", AppLanguage.english("Основная навигация"))
        assertEquals("selected", AppLanguage.english("выбран"))
    }

    @Test
    fun translatesHomeQualityLabelsAndDynamicSpeedDescription() {
        assertEquals("Response", AppLanguage.english("Отклик"))
        assertEquals("Response: after connecting", AppLanguage.english("Отклик: после подключения"))
        assertEquals("Response bypasses VPN", AppLanguage.english("Отклик: в обход VPN"))
        assertEquals("Speed is unavailable because current bypass settings exclude this app from the VPN", AppLanguage.english("Скорость не измеряется: приложение исключено из VPN текущими настройками обхода"))
        assertEquals("HTTPS response bypasses VPN with the current settings", AppLanguage.english("HTTPS-отклик не проходит через VPN при текущих настройках обхода"))
        assertEquals("Speed", AppLanguage.english("Скорость"))
        assertEquals("Refresh measurement", AppLanguage.english("Обновить замер"))
        assertEquals("bypass · germany", AppLanguage.english("обход · германия"))
        assertEquals(
            "Download speed, 3-second sample. Tap to check routes.",
            AppLanguage.english("Скорость загрузки, проба 3 с. Нажмите для проверки маршрутов."),
        )
    }

    @Test
    fun translatesEntryFlowAndReleaseValidationCopy() {
        assertEquals("Enter code", AppLanguage.english("Введите код"))
        assertEquals("Get code", AppLanguage.english("Получить код"))
        assertEquals("Import your subscription", AppLanguage.english("Импортируйте подписку"))
        assertEquals("Link Telegram", AppLanguage.english("Привяжите Telegram"))
        assertEquals("Sign in to Telegram to update your subscription and routes.", AppLanguage.english("Войдите в Telegram, чтобы обновить подписку и маршруты."))
        assertEquals("Step 1 · Account", AppLanguage.english("Шаг 1 · Аккаунт"))
        assertEquals("Step 2 · Code", AppLanguage.english("Шаг 2 · Код"))
        assertEquals("Step 2 · Routes", AppLanguage.english("Шаг 2 · Маршруты"))
        assertEquals("Telegram username", AppLanguage.english("Имя пользователя Telegram"))
        assertEquals("Telegram verification code", AppLanguage.english("Код подтверждения Telegram"))
        assertEquals("Checking subscription…", AppLanguage.english("Проверяем подписку…"))
        assertEquals("Loading additional routes…", AppLanguage.english("Загружаем дополнительные маршруты…"))
        assertEquals("Saving subscription…", AppLanguage.english("Сохраняем подписку…"))
        assertEquals("Verify code", AppLanguage.english("Подтвердить код"))
        assertEquals("The code is incorrect or expired. Request a new one.", AppLanguage.english("Код неверный или истёк. Запроси новый."))
        assertEquals("The attempt limit was reached. Start sign-in again.", AppLanguage.english("Попытки закончились. Начни вход заново."))
        assertEquals(
            "Enter a Telegram username with 5 to 32 characters.",
            AppLanguage.english("Введи username Telegram длиной от 5 до 32 знаков."),
        )
        assertEquals("Sending…", AppLanguage.english("Отправляем…"))
        assertEquals("Checking…", AppLanguage.english("Проверяем…"))
        assertEquals(
            "The code was sent to Telegram and is valid for five minutes.",
            AppLanguage.english("Код отправлен в Telegram и действует 5 минут."),
        )
        assertEquals(
            "Android system permission is required to check this exit",
            AppLanguage.english("Для проверки через выход требуется системное разрешение Android"),
        )
        assertEquals(
            "Could not start sign-in. Check your connection and try again. Reference: ABC123",
            AppLanguage.english("Не удалось начать вход. Проверь соединение и попробуй снова. Код: ABC123"),
        )
        assertEquals(
            "The release link did not pass verification.",
            AppLanguage.english("Ссылка на релиз не прошла проверку."),
        )
    }

    @Test
    fun translatesSubscriptionSetupScreen() {
        assertEquals("Connect subscription", AppLanguage.english("Подключить подписку"))
        assertEquals("Link Telegram account", AppLanguage.english("Подключить аккаунт Telegram"))
        assertEquals(
            "Sign in to Telegram to load your subscription and routes.",
            AppLanguage.english("Войдите в Telegram, чтобы загрузить подписку и маршруты."),
        )
    }

    @Test
    fun translatesDynamicTrafficAndConnectionCopy() {
          assertEquals("Main", AppLanguage.english("Основной"))
          assertEquals("Connection failed to start", AppLanguage.english("Ошибка запуска соединения"))
          assertEquals(
              "Selected route failed the HTTPS check: the check timed out. Change the bypass setting or try again.",
              AppLanguage.english("Выбранный маршрут не прошёл HTTPS-проверку: проверка не завершилась вовремя. Смените обход или повторите попытку."),
          )
        assertEquals(
            "14 GB used of 20 GB. 10 GB downloaded, 4 GB uploaded.",
            AppLanguage.english("Использовано 14 GB из 20 GB. Скачано 10 GB, отправлено 4 GB."),
        )
        assertEquals(
            "Checking route and internet · 01:34",
            AppLanguage.english("Проверяем маршрут и интернет · 01:34"),
        )
        assertEquals("4 servers · choose a node", AppLanguage.english("4 сервера · выбрать точку"))
        assertEquals("Double route · 10 s · HEAD", AppLanguage.english("Через двойной маршрут · 10 с · HEAD"))
        assertEquals(
            "Route check: double route, 10-second timeout, HEAD method",
            AppLanguage.english("Проверка маршрута: через двойной маршрут, тайм-аут 10 секунд, метод HEAD"),
        )
    }

    @Test
    fun translatesAmneziaProfileCounts() {
        assertEquals("No profiles · refresh your subscription", AppLanguage.english("Нет профилей · обновите подписку"))
        assertEquals("1 profile loaded", AppLanguage.english("1 профиль загружен"))
        assertEquals("4 profiles loaded", AppLanguage.english("4 профиля загружено"))
        assertEquals("5 profiles loaded", AppLanguage.english("5 профилей загружено"))
    }

    @Test
    fun translatesRouteDiagnosticsAndUpdateDetails() {
        assertEquals("queued", AppLanguage.english("в очереди"))
        assertEquals("Route quality: Good", AppLanguage.english("Качество маршрута: good"))
        assertEquals(
            "Expand Netherlands exits to start the HTTP route check",
            AppLanguage.english("Раскрыть выходы Netherlands; откроется проверка HTTP маршрутов"),
        )
        assertEquals("Reset all keys?", AppLanguage.english("Сбросить все ключи?"))
        assertEquals("12 months", AppLanguage.english("12 мес."))
        assertEquals(
            "The GitHub file will be checked against the app package and signing certificate · 13 MB.",
            AppLanguage.english("Файл с GitHub будет проверен по имени пакета и подписи приложения · 13 MB."),
        )
        assertEquals("Downloading update · 72%", AppLanguage.english("Скачиваем обновление · 72%"))
    }

    @Test
    fun keepsTechnologyNamesAndUnknownServerLabelsIntact() {
        assertEquals("AmneziaWG 3.1", AppLanguage.english("AmneziaWG 3.1"))
        assertEquals("Шведский узел", AppLanguage.english("Шведский узел"))
    }

    @Test
    fun localizesKnownIpCitiesAndRegionsWithoutChangingUnknownPlaces() {
        assertEquals("Уфа, Башкортостан, RU", AppLanguage.russianLocation("Ufa, Bashkortostan, RU"))
        assertEquals("Тюмень", AppLanguage.russianLocation("Tyumen"))
        assertEquals("Example City, ZZ", AppLanguage.russianLocation("Example City, ZZ"))
    }
}
