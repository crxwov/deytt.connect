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
