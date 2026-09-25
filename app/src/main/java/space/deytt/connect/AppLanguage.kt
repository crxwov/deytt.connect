package space.deytt.connect

import android.app.Activity
import android.content.Context
import java.util.Locale

internal object AppLanguage {
    private const val PREFS = "profile_settings"
    private const val KEY = "app_language"
    const val RU = "ru"
    const val EN = "en"

    fun current(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, null)
        ?.takeIf { it == RU || it == EN }
        ?: if (Locale.getDefault().language == EN) EN else RU

    fun label(context: Context): String = if (current(context) == EN) "English" else "Русский"

    fun set(context: Context, language: String) {
        require(language == RU || language == EN)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }

    fun text(context: Context, value: String): String = if (current(context) == EN) english(value) else value

    fun Activity.uiCopy(value: String): String = text(this, value)

    fun locationLabel(context: Context, value: String): String =
        if (current(context) == EN) english(value) else russianLocation(value)

    internal fun russianLocation(value: String): String = russianPlaces.entries
        .sortedByDescending { it.key.length }
        .fold(value) { result, (source, localized) -> result.replace(source, localized, ignoreCase = true) }

    private val russianPlaces = mapOf(
        "Republic of Bashkortostan" to "Республика Башкортостан",
        "Bashkortostan" to "Башкортостан",
        "Ufa" to "Уфа",
        "Moscow Oblast" to "Московская область",
        "Moscow" to "Москва",
        "Saint Petersburg" to "Санкт-Петербург",
        "Leningrad Oblast" to "Ленинградская область",
        "Yekaterinburg" to "Екатеринбург",
        "Sverdlovsk Oblast" to "Свердловская область",
        "Nizhny Novgorod" to "Нижний Новгород",
        "Novosibirsk" to "Новосибирск",
        "Kazan" to "Казань",
        "Tatarstan" to "Татарстан",
        "Samara" to "Самара",
        "Rostov-on-Don" to "Ростов-на-Дону",
        "Chelyabinsk" to "Челябинск",
        "Krasnoyarsk" to "Красноярск",
        "Vladivostok" to "Владивосток",
        "Krasnodar" to "Краснодар",
        "Tyumen" to "Тюмень",
        "Omsk" to "Омск",
        "Perm" to "Пермь",
        "Voronezh" to "Воронеж",
        "Volgograd" to "Волгоград",
        "Saratov" to "Саратов",
    )

    internal fun english(value: String): String {
        val upperCase = value == value.uppercase(Locale.ROOT)
        val normalized = if (upperCase) value.lowercase(Locale.ROOT) else value
        (translations[value] ?: translations[normalized])?.let {
            return if (upperCase) it.uppercase(Locale.ROOT) else it
        }
        Regex("^версия (.+)$").matchEntire(value)?.let { return "VERSION ${it.groupValues[1]}" }
        Regex("^Проверяем маршрут и интернет · (.+)$").matchEntire(value)?.let {
            return "Checking route and internet · ${it.groupValues[1]}"
        }
        Regex("^Настраиваем защищённый туннель · (.+)$").matchEntire(value)?.let {
            return "Setting up the secure tunnel · ${it.groupValues[1]}"
        }
        Regex("^из (.+) использовано$").matchEntire(value)?.let { return "${it.groupValues[1]} used" }
        Regex("^из (.+)$").matchEntire(value)?.let { return "of ${it.groupValues[1]}" }
        Regex("^Использовано (.+) из (.+)\\. Скачано (.+), отправлено (.+)\\.$").matchEntire(value)?.let {
            return "${it.groupValues[1]} used of ${it.groupValues[2]}. ${it.groupValues[3]} downloaded, ${it.groupValues[4]} uploaded."
        }
        Regex("^Передано (.+) без заданного лимита\\. Скачано (.+), отправлено (.+)\\.$").matchEntire(value)?.let {
            return "${it.groupValues[1]} transferred with no set limit. ${it.groupValues[2]} downloaded, ${it.groupValues[3]} uploaded."
        }
        Regex("^([0-9]+) сервер · выбрать точку$").matchEntire(value)?.let { return "${it.groupValues[1]} server · choose a node" }
        Regex("^([0-9]+) сервера · выбрать точку$").matchEntire(value)?.let { return "${it.groupValues[1]} servers · choose a node" }
        Regex("^([0-9]+) серверов · выбрать точку$").matchEntire(value)?.let { return "${it.groupValues[1]} servers · choose a node" }
        Regex("^Нет профилей · обновите подписку$").matchEntire(value)?.let { return "No profiles · refresh your subscription" }
        Regex("^1 профиль загружен$").matchEntire(value)?.let { return "1 profile loaded" }
        Regex("^([0-9]+) профиля загружено$").matchEntire(value)?.let { return "${it.groupValues[1]} profiles loaded" }
        Regex("^([0-9]+) профилей загружено$").matchEntire(value)?.let { return "${it.groupValues[1]} profiles loaded" }
        Regex("^Пинг (.+)\\. Короткое нажатие проверяет один сервер, удержание — все$")
            .matchEntire(value)?.let { return "Ping ${it.groupValues[1]}. Tap to check one server; press and hold to check all." }
        Regex("^Проверка маршрута: Via Proxy, Double, тайм-аут 10 секунд, метод (.+)$")
            .matchEntire(value)?.let { return "Route check: Via Proxy, Double, 10-second timeout, ${it.groupValues[1]} method" }
        Regex("^Via Proxy · Double · 10 с · (.+)$").matchEntire(value)?.let {
            return "Via Proxy · Double · 10 s · ${it.groupValues[1]}"
        }
        Regex("^Проверка маршрута: через двойной маршрут, тайм-аут 10 секунд, метод (.+)$")
            .matchEntire(value)?.let { return "Route check: double route, 10-second timeout, ${it.groupValues[1]} method" }
        Regex("^Через двойной маршрут · 10 с · (.+)$").matchEntire(value)?.let {
            return "Double route · 10 s · ${it.groupValues[1]}"
        }
        Regex("^Загружено маршрутов: ([0-9]+)\\. Текущий туннель не прерывался\\.$").matchEntire(value)?.let {
            return "Loaded routes: ${it.groupValues[1]}. Your current tunnel was not interrupted."
        }
        Regex("^Проверяем задержку (.+)$").matchEntire(value)?.let {
            return "Checking latency for ${it.groupValues[1]}"
        }
        Regex("^Проверка через (.+) завершена$").matchEntire(value)?.let {
            return "Check through ${it.groupValues[1]} complete"
        }
        Regex("^Доступна (.+)\\. Откройте официальный релиз и проверьте APK перед установкой\\.$")
            .matchEntire(value)?.let {
                return "Version ${it.groupValues[1]} is available. Open the official release and verify the APK before installing."
            }
        Regex("^Проверить задержку через выход (.+): два HTTPS-запроса HEAD или GET, тайм-аут 10 секунд$")
            .matchEntire(value)?.let {
                return "Check latency through ${it.groupValues[1]}: two HEAD or GET HTTPS requests, 10-second timeout"
            }
        Regex("^Задержка через выбранный выход: ([0-9]+) миллисекунд, два HTTPS-запроса HEAD или GET через прокси$")
            .matchEntire(value)?.let {
                return "Latency through selected exit: ${it.groupValues[1]} milliseconds, two HEAD or GET HTTPS requests"
            }
        Regex("^Проверяю выход через локальный прокси методом (.+), два запроса, тайм-аут 10 секунд$")
            .matchEntire(value)?.let {
                return "Checking the exit with ${it.groupValues[1]}, two requests, 10-second timeout"
            }
        Regex("^(.+)-проверка маршрута (.+): ([0-9]+) миллисекунд$").matchEntire(value)?.let {
            return "${it.groupValues[1]} route check for ${it.groupValues[2]}: ${it.groupValues[3]} milliseconds"
        }
        Regex("^Сервер маршрута (.+) не ответил$").matchEntire(value)?.let {
            return "Route server ${it.groupValues[1]} did not respond"
        }
        Regex("^Для маршрута (.+) нет проверяемых серверов$").matchEntire(value)?.let {
            return "No checkable servers for route ${it.groupValues[1]}"
        }
        Regex("^Проверка всех серверов (.+): (.+)$").matchEntire(value)?.let {
            return "All-node check for ${it.groupValues[1]}: ${it.groupValues[2]}"
        }
        return value
            .replace("Санкт-Петербург", "Saint Petersburg")
            .replace("Франкфурт", "Frankfurt")
            .replace("Хельсинки", "Helsinki")
            .replace("Амстердам", "Amsterdam")
            .replace("Петербург", "Saint Petersburg")
            .replace("Нидерланды", "Netherlands")
            .replace("Германия", "Germany")
            .replace("Финляндия", "Finland")
            .replace("Россия", "Russia")
            .replace("мс", "ms")
    }

    private val translations = mapOf(
        "Назад" to "Back",
        "Основная навигация" to "Main navigation",
        "выбран" to "selected",
        "частно · на устройстве" to "PRIVATE · ON DEVICE",
        "Готово проверить официальный релиз." to "Ready to check the official release.",
        "Проверяем официальный релиз…" to "Checking the official release…",
        "Установлена последняя версия." to "The latest version is installed.",
        "Не удалось проверить официальный релиз. Повторите попытку позже." to "Could not check the official release. Try again later.",
        "Проверить снова" to "Check again",
        "Открыть официальный релиз" to "Open official release",
        "Главная" to "Home",
        "Маршруты" to "Routes",
        "Профиль" to "Profile",
        "Настройки" to "Settings",
        "Автоподбор: Нидерланды, Германия, Финляндия, Россия" to "Auto-select: Netherlands, Germany, Finland, Russia",
        "amneziawg · готовые профили" to "AmneziaWG · profiles",
        "состояние соединения" to "connection status",
        "./c · без аккаунта" to "./c · not linked",
        "Состояние соединения" to "Connection status",
        "Не подключено" to "Not connected",
        "Подключено" to "Connected",
        "Готово к подключению" to "Ready to connect",
        "Подключить" to "Connect",
        "Отключить" to "Disconnect",
        "Отключаем…" to "Disconnecting…",
        "Повторить" to "Try again",
        "Соединение выключено" to "Connection off",
        "VPN подключён" to "Connected",
        "VPN отключён" to "Not connected",
        "Соединение активно" to "Connection active",
        "Завершаем работу туннеля" to "Closing the tunnel",
        "Попробуйте ещё раз или выберите другой маршрут" to "Try again or choose another route",
        "Проверяем маршрут и интернет" to "Checking route and internet",
        "Настраиваем защищённый туннель" to "Setting up the secure tunnel",
        "Профили AmneziaWG 1.5 и 3.1 не загружены. Обновите подписку." to "AmneziaWG 1.5 and 3.1 profiles are missing. Refresh the subscription.",
        "ВХОД" to "ENTRY",
        "ВЫХОД" to "EXIT",
        "Ищем регион…" to "Locating region…",
        "направление маршрута" to "ROUTE DIRECTION",
        "через 🇷🇺 Петербург" to "via 🇷🇺 Saint Petersburg",
        "Автоподбор" to "Auto-select",
        "Путь от входа до выбранного выхода" to "Route from entry to selected exit",
        "Место скрыто" to "Location hidden",
        "Определяем регион…" to "Locating region…",
        "Исходная сеть скрыта во время подключения" to "Source network is hidden while connected",
        "Регион недоступен" to "Region unavailable",
        "отключено в настройках" to "disabled in settings",
        "примерно по IP" to "approx. by IP",
        "примерно по IP · только в памяти" to "approx. by IP · session only",
        "геолокация временно недоступна" to "Location temporarily unavailable",
        "отключите соединение для определения сети" to "Disconnect to locate your network",
        "ожидает сетевого запроса" to "Waiting for network lookup",
        "ТРАФИК ПОДПИСКИ" to "SUBSCRIPTION TRAFFIC",
        "без лимита" to "unlimited",
        "передано" to "transferred",
        "нет данных" to "no data",
        "узлы · протоколы · AmneziaWG" to "NODES · PROTOCOLS · AmneziaWG",
        "Маршруты" to "Routes",
        "Выберите выход. Изменение вступит в силу при следующем подключении." to "Choose an exit. The change takes effect on your next connection.",
        "быстрый выбор" to "QUICK SELECT",
        "Выберем доступный узел" to "Choose an available node",
        "Россия → Германия" to "Russia → Germany",
        "Двойной маршрут · Санкт-Петербург → Франкфурт" to "Double route · Saint Petersburg → Frankfurt",
        "AmneziaWG · готовые профили" to "AmneziaWG · ready profiles",
        "Профили не найдены · обновите подписку" to "No profiles found · refresh the subscription",
        "1 сервер · выбрать точку" to "1 server · choose a node",
        "пинг" to "ping",
        "проверка…" to "checking…",
        "нет ответа" to "no response",
        "нет" to "none",
        "отключите соединение" to "disconnect first",
        "через прокси…" to "checking route…",
        "ошибка" to "error",
        "тайм-аут" to "timed out",
        "Проверяю выход через локальный прокси методом HEAD, два запроса, тайм-аут 10 секунд" to "Checking the exit with two HEAD requests, 10-second timeout",
        "обновить" to "refresh",
        "Профили не найдены. Обновить подписку" to "No profiles found. Refresh subscription",
        "выходы по стране" to "EXITS BY COUNTRY",
        "подписка · устройства · ключи" to "SUBSCRIPTION · DEVICES · KEYS",
        "трафик подписки" to "subscription traffic",
        "использовано · без установленного лимита" to "used · no limit set",
        "ЗАГРУЖЕНО" to "DOWNLOADED",
        "ОТДАНО" to "UPLOADED",
        "ЛИМИТ" to "LIMIT",
        "Без лимита" to "No limit",
        "ДЕЙСТВУЕТ ДО" to "EXPIRES",
        "Без срока" to "No expiry",
        "конфигурации на устройстве" to "profiles on this device",
        "открыть" to "open",
        "действия" to "actions",
        "Продлить или сменить тариф" to "Extend or change plan",
        "Тарифы и увеличение лимита в Telegram" to "Plans and data upgrades in Telegram",
        "бот" to "bot",
        "Импортировать подписку" to "Import subscription",
        "Сбросить ключи в Telegram" to "Reset keys in Telegram",
        "Переход к подтверждению в боте" to "Continue to confirmation in the bot",
        "Поддержка" to "Support",
        "Написать команде DEYTT" to "Contact DEYTT support",
        "Сброс — только после подтверждения в Telegram." to "Keys reset only after confirmation in Telegram.",
        "Настройки" to "Settings",
        "язык" to "LANGUAGE",
        "Язык приложения" to "App language",
        "НАСТРОЙКИ · ЯЗЫК" to "SETTINGS · LANGUAGE",
        "Смена применяется сразу ко всем вкладкам." to "Changes apply across all tabs immediately.",
        "Текущий язык" to "Current language",
        "Выбрать" to "Select",
        "аккаунт" to "ACCOUNT",
        "Добавить приложение" to "Link Telegram account",
        "Telegram подключён" to "Telegram connected",
        "Подтвердить аккаунт и загрузить профили из бота" to "Verify your account and import profiles from the bot",
        "Профиль и подписка связаны с этим устройством" to "Profile and subscription are linked to this device",
        "добавить" to "link",
        "управлять" to "manage",
        "официальный релиз" to "OFFICIAL RELEASE",
        "Проверить обновления" to "Check for updates",
        "Проверить задержку" to "Check latency",
        "Аватар Telegram" to "Telegram avatar",
        "Значок AmneziaWG" to "AmneziaWG mark",
        "Маршрут через Россию и Германию" to "Route through Russia and Germany",
        "подключение" to "CONNECTION",
        "Проверка маршрута" to "Route check",
        "настроить" to "configure",
        "ПОДКЛЮЧЕНИЕ · ДИАГНОСТИКА" to "CONNECTION · DIAGNOSTICS",
        "Метод проверки" to "Check method",
        "метод" to "method",
        "Проверка идёт через выбранный двойной маршрут. Запрос ограничен десятью секундами." to "The check uses the selected double route and is limited to ten seconds.",
        "Короткий запрос HEAD" to "Short HEAD request",
        "Запрос GET с ответом" to "GET request with response",
        "Проверяет заголовки, меньше данных" to "Checks headers and transfers less data",
        "Проверяет доступность ответа целиком" to "Checks that the full response is available",
        "выбрано" to "selected",
        "Готово" to "Done",
        "Разрешения соединения Android" to "Android connection permissions",
        "Системные разрешения и блокировка" to "System permissions and connection controls",
        "Бот DEYTT" to "DEYTT bot",
        "Ключи, подписка и другие действия" to "Keys, subscription, and more",
        "приватность" to "PRIVACY",
        "Показывать сеть на карте" to "Show network location on the map",
        "ipinfo.io видит IP запроса; координаты в приложении не сохраняются. GPS не используется." to "ipinfo.io sees the lookup request; the app does not store coordinates or use GPS.",
        "Сократить анимацию фона" to "Reduce background motion",
        "Остановить мерцание звёздного неба." to "Stop the starfield twinkle.",
        "Карта маршрутов встроена и работает офлайн. Чтобы определить регион, запрос с IP-адресом получает ipinfo.io. Приложение не сохраняет IP или координаты: место остаётся только в памяти до закрытия. Выключение функции сразу убирает точку с карты." to "The route map works offline. For regional lookup, ipinfo.io receives a request from your IP address. The app does not store your IP or coordinates; the location stays in memory until you close the app. Turn this off to remove the point from the map.",
        "./c · АККАУНТ" to "./c · ACCOUNT",
        "Укажи Telegram username. Бот подтвердит вход одноразовым кодом и подключит существующие профили." to "Enter your Telegram username. The bot will verify the sign-in with a one-time code and link your existing profiles.",
        "Открыть Telegram" to "Open Telegram",
        "Продолжить" to "Continue",
        "Подтвердить код" to "Verify code",
        "Проверь Telegram" to "Check Telegram",
        "Аккаунт подключён" to "Account connected",
        "Нажми Start у бота. Код появится в Telegram и действует 5 минут." to "Tap Start in the bot. The code will arrive in Telegram and expires in five minutes.",
        "Ссылка готова. Открой бота ещё раз кнопкой ниже." to "The link is ready. Open the bot with the button below.",
        "Введи username Telegram длиной от 5 до 32 знаков." to "Enter a Telegram username with 5 to 32 characters.",
        "Создаём ссылку…" to "Creating link…",
        "Подготавливаем одноразовое подтверждение." to "Preparing one-time verification.",
        "Ссылка действует 5 минут. Код отправит бот после нажатия Start." to "The link is valid for five minutes. The bot sends a code after you tap Start.",
        "Введи шесть цифр из сообщения бота." to "Enter the six digits sent by the bot.",
        "Проверяем код…" to "Verifying code…",
        "Подтверждаем Telegram-аккаунт и загружаем профили." to "Verifying your Telegram account and loading profiles.",
        "Аккаунт подключён. Не удалось обновить профили — повтори вход позже." to "Account connected. Profiles could not be refreshed; try linking again later.",
        "Аккаунт подключён. Активной подписки для импорта пока нет." to "Account connected. There is no active subscription to import yet.",
        "Проверь username Telegram." to "Check your Telegram username.",
        "Слишком частые попытки. Подожди немного и повтори." to "Too many attempts. Wait a bit and try again.",
        "Лимит попыток исчерпан. Создай новую ссылку." to "The attempt limit was reached. Create a new link.",
        "Код неверный или уже истёк. Проверь Telegram или создай новую ссылку." to "The code is incorrect or expired. Check Telegram or create a new link.",
        "Для этого аккаунта вход недоступен. Напиши в поддержку." to "Sign-in is unavailable for this account. Contact support.",
        "Не удалось завершить вход. Проверь соединение и попробуй ещё раз." to "Could not finish sign-in. Check your connection and try again.",
        "ДИАГНОСТИКА · ВСЕ ТОЧКИ" to "DIAGNOSTICS · ALL NODES",
        "Задержка · все серверы" to "Latency · all servers",
        "проверяю…" to "checking…",
        "нет ответа" to "no response",
        "Сброс — только после подтверждения в Telegram." to "Keys reset only after confirmation in Telegram.",
        "КЛЮЧИ · ПОДТВЕРЖДЕНИЕ" to "KEYS · CONFIRMATION",
        "Открыть управление ключами?" to "Open key management?",
        "Бот покажет действие сброса и попросит подтвердить его отдельно. Существующие ключи не изменятся, пока вы не подтвердите операцию там." to "The bot will show the reset action and ask you to confirm it. Existing keys will stay unchanged until you confirm there.",
        "Отмена" to "Cancel",
        "Открыть бота" to "Open bot",
        "./c · СЕССИЯ" to "./c · SESSION",
        "Отключить Telegram?" to "Disconnect Telegram?",
        "Приложение перестанет получать профиль и обновления ключей." to "The app will stop receiving your profile and key updates.",
        "Завершаем…" to "Signing out…",
        "Не удалось отозвать сессию. Проверь соединение и повтори." to "Could not revoke the session. Check your connection and try again.",
        "Вход" to "Entry",
        "Выход" to "Exit",
        "Франкфурт" to "Frankfurt",
        "Санкт-Петербург" to "Saint Petersburg",
        "Хельсинки" to "Helsinki",
        "Амстердам" to "Amsterdam",
        "Германия" to "Germany",
        "Россия" to "Russia",
        "Финляндия" to "Finland",
        "Нидерланды" to "Netherlands",
    )
}
