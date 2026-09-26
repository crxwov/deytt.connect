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
        "Amsterdam" to "Амстердам",
        "Frankfurt am Main" to "Франкфурт",
        "Frankfurt" to "Франкфурт",
        "Helsinki" to "Хельсинки",
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
        val additions = mapOf(
            "Изменить точку выхода?" to "Change exit?",
            "Текущее соединение завершится. После выбора подключитесь снова, чтобы применить новый маршрут." to "Your current connection will end. Connect again after selecting the new route.",
            "Оставить подключение" to "Keep connection",
            "Остановить и сменить" to "Stop and change",
            "Значок AmneziaWG" to "AmneziaWG icon",
            "Оригинальный знак AmneziaWG" to "AmneziaWG logo",
            "Маршрут через Россию и Германию" to "Route through Russia and Germany",
            "Путь от входа через транзит к выбранному выходу" to "Route from your location through the selected exit",
            "выходы · выбор · диагностика" to "LOCATIONS · PROTOCOLS",
            "Раскройте страну, чтобы проверить выходы. Выбор не запускает подключение." to "Open a country to compare its routes. Select a route, then connect.",
            "быстрый выбор" to "Quick select", "выбрать" to "select", "ТРАНЗИТ" to "VIA",
            "Задержка" to "Ping", "Задержка: —" to "Ping: —", "Скорость: —" to "Speed: —",
            "Скорость: проверить" to "Test speed",
            "Последний замер загрузки. Нажмите, чтобы проверить маршруты." to "Last download test. Tap to compare routes.",
            "Войти через Telegram" to "Sign in with Telegram",
            "Быстрый QUIC-маршрут" to "QUIC transport",
            "Сброс ключей отключит выбранные подключения. Завершение сессии приложения отключает только доступ к аккаунту." to "Key reset disconnects the selected connections. Ending an app session only revokes account access."
        )
        additions[value]?.let { return it }
        additions[value.lowercase(Locale.ROOT)]?.let { return if (value == value.uppercase(Locale.ROOT)) it.uppercase(Locale.ROOT) else it }

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
        Regex("^AmneziaWG: ([0-9]+) · Happ: ([0-9]+)$").matchEntire(value)?.let {
            return "AmneziaWG: ${it.groupValues[1]} · Happ: ${it.groupValues[2]}"
        }
        Regex("^Раскрыть выходы (.+); откроется проверка HTTP маршрутов$").matchEntire(value)?.let {
            return "Expand ${english(it.groupValues[1])} exits to start the HTTP route check"
        }
        Regex("^Качество маршрута: (.+)$").matchEntire(value)?.let {
            return "Route quality: ${it.groupValues[1].replaceFirstChar(Char::uppercase)}"
        }
        Regex("^Использовано (.+) из (.+)$").matchEntire(value)?.let {
            return "${it.groupValues[1]} used of ${it.groupValues[2]}"
        }
        Regex("^Передано (.+) · без установленного лимита$").matchEntire(value)?.let {
            return "${it.groupValues[1]} transferred · no limit set"
        }
        Regex("^(.+) · ([0-9]+) устройств · ([0-9]+) мес\\. · ([0-9]+) ₽$").matchEntire(value)?.let {
            return "${it.groupValues[1]} · ${it.groupValues[2]} devices · ${it.groupValues[3]} months · ₽${it.groupValues[4]}"
        }
        Regex("^Обращение №([0-9]+) · открыто$").matchEntire(value)?.let {
            return "Ticket #${it.groupValues[1]} · open"
        }
        Regex("^Действие отзовёт текущие ключи (.+) и может временно отключить устройства\\. Продолжить\\?$")
            .matchEntire(value)?.let {
                return "This revokes the current ${it.groupValues[1]} keys and may temporarily disconnect devices. Continue?"
            }
        Regex("^Сбросить (.+)\\?$").matchEntire(value)?.let { return "Reset ${english(it.groupValues[1])}?" }
        Regex("^([0-9]+) мес\\.$").matchEntire(value)?.let { return "${it.groupValues[1]} months" }
        Regex("^Скачиваем обновление · ([0-9]+)%$").matchEntire(value)?.let {
            return "Downloading update · ${it.groupValues[1]}%"
        }
        Regex("^Файл с GitHub будет проверен по имени пакета и подписи приложения(?: · ([0-9.]+ MB))?\\.$")
            .matchEntire(value)?.let {
                val size = it.groupValues[1].takeIf(String::isNotBlank)?.let { suffix -> " · $suffix" }.orEmpty()
                return "The GitHub file will be checked against the app package and signing certificate$size."
            }
        Regex("^Доступно обновление (.+)$").matchEntire(value)?.let { return "Update ${it.groupValues[1]} is available" }
        Regex("^Доступна версия (.+) · ([0-9.]+ MB)$").matchEntire(value)?.let {
            return "Version ${it.groupValues[1]} is available · ${it.groupValues[2]}"
        }
        Regex("^Скачать версию (.+)\\?$").matchEntire(value)?.let { return "Download version ${it.groupValues[1]}?" }
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
        "Изменить точку выхода?" to "Change exit?",
            "Текущее соединение завершится. После выбора подключитесь снова, чтобы применить новый маршрут." to "Your current connection will end. Connect again after selecting the new route.",
            "Оставить подключение" to "Keep connection",
            "Остановить и сменить" to "Stop and change",
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
        "Проверка через выход AmneziaWG пока недоступна" to "Checks through AmneziaWG exits are not available yet",
        "в очереди" to "queued",
        "измеряем задержку…" to "measuring latency…",
        "измеряем скорость…" to "measuring speed…",
        "Telegram Stars" to "Telegram Stars",
        "Войдите в Telegram, чтобы загрузить подписку и маршруты." to "Sign in to Telegram to load your subscription and routes.",
        "Подключить подписку" to "Connect subscription",
        "Подключить аккаунт Telegram" to "Link Telegram account",
        "Обновить подписку" to "Refresh subscription",
        "Повторить загрузку" to "Retry loading",
        "Проверяем подписку в Telegram…" to "Checking subscription in Telegram…",
        "Активная подписка не найдена. Оформите её в Telegram, затем повторите загрузку." to "No active subscription found. Start one in Telegram, then try loading again.",
        "Не удалось загрузить подписку. Проверьте соединение и попробуйте ещё раз." to "Could not load the subscription. Check your connection and try again.",
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
        "LTE./белые списки" to "LTE./whitelist",
        "аккаунт · подписка · устройства" to "account · subscription · devices",
        "Подключить Telegram" to "Link Telegram",
        "Войти по username, чтобы открыть подписку и устройства" to "Sign in with your username to view the subscription and devices",
        "Профиль привязан к этому устройству" to "Account linked to this device",
        "подписка" to "SUBSCRIPTION",
        "Подключите Telegram, чтобы загрузить тариф и срок действия." to "Link Telegram to load your plan and expiry.",
        "Загружаем данные подписки…" to "Loading subscription…",
        "Активный доступ" to "Active access",
        "Нет активной подписки" to "No active subscription",
        "без срока" to "no expiry",
        "срок не указан" to "expiry not available",
        "Тарифы и оплата" to "Plans and payment",
        "Сумму подтвердит сервер перед оформлением" to "The server confirms the amount before checkout",
        "устройства и сессии" to "DEVICES AND SESSIONS",
        "Подключите аккаунт, чтобы увидеть устройства." to "Link your account to view devices.",
        "Загружаем список…" to "Loading devices…",
        "Сбросить ключи" to "Reset keys",
        "Отозвать действующие ключи выбранного типа" to "Revoke active keys for a selected protocol",
        "Список AWG-конфигураций не отображается. Здесь видны только общие счётчики и устройства Happ; сброс требует отдельного подтверждения." to "AWG configs are not shown. Only aggregate counts and Happ devices appear here; key reset requires confirmation.",
        "помощь и документы" to "HELP AND DOCUMENTS",
        "Диалог с командой DEYTT" to "Chat with the DEYTT team",
        "Условия использования" to "Terms of service",
        "Политика конфиденциальности" to "Privacy policy",
        "Не удалось загрузить подписку." to "Could not load the subscription.",
        "Не удалось загрузить данные аккаунта." to "Could not load account data.",
        "Проверьте подключение и откройте профиль снова." to "Check your connection and open the profile again.",
        "Устройство Happ" to "Happ device",
        "Пока нет зарегистрированных устройств Happ." to "No Happ devices are registered yet.",
        "Что сбросить?" to "What should be reset?",
        "Все ключи" to "All keys",
        "все ключи" to "all keys",
        "Сбросить" to "Reset",
        "Сбрасываем ключи…" to "Resetting keys…",
        "Ключи сброшены. Обновляем аккаунт…" to "Keys reset. Refreshing account…",
        "Загружаем доступные планы…" to "Loading available plans…",
        "Продлить подписку" to "Extend subscription",
        "Выберите план" to "Choose a plan",
        "Сейчас планы недоступны. Попробуйте позже." to "Plans are unavailable right now. Try again later.",
        "Подтвердите сумму" to "Confirm the amount",
        "Подписка" to "Subscription",
        "Оплатить картой" to "Pay by card",
        "Готовим безопасный счёт…" to "Preparing secure checkout…",
        "Платёж ожидает подтверждения · нажмите, чтобы проверить" to "Payment pending · tap to check",
        "Счёт готов" to "Checkout is ready",
        "Откройте защищённую страницу оплаты. Приложение не запрашивает данные карты." to "Open the secure payment page. The app never asks for card details.",
        "Проверить статус" to "Check status",
        "Открыть оплату" to "Open payment",
        "Платёжная ссылка недоступна." to "Payment link is unavailable.",
        "Проверяем платёж…" to "Checking payment…",
        "Оплата подтверждена" to "Payment confirmed",
        "Платёж ещё не подтверждён. Проверьте позже." to "Payment is not confirmed yet. Check again later.",
        "DEYTT · SUPPORT" to "DEYTT · SUPPORT",
        "Загружаем переписку…" to "Loading conversation…",
        "Новое обращение создастся после первого сообщения." to "A new ticket will be created when you send the first message.",
        "Обращение закрыто. Новое сообщение откроет новое обращение." to "Ticket closed. A new message will open another ticket.",
        "Сообщение команде" to "Message the team",
        "Закрыть обращение" to "Close ticket",
        "Отправить" to "Send",
        "Напишите сообщение минимум из пяти символов." to "Write a message of at least five characters.",
        "Отправляем сообщение…" to "Sending message…",
        "Закрыть обращение?" to "Close this ticket?",
        "Новые ответы не будут приниматься. При необходимости вы сможете создать новое обращение." to "New replies will stop. You can create another ticket whenever you need.",
        "Закрыть" to "Close",
        "Позже" to "Later",
        "Обновление" to "Update",
        "Доступно обновление" to "Update available",
        "Скачать официальный APK сейчас? Позже его можно будет открыть в настройках." to "Download the official APK now? You can install it later from Settings.",
        "Страница релиза" to "Release page",
        "Обновить сейчас" to "Update now",
        "Скачать" to "Download",
        "Файл с GitHub будет проверен по имени пакета и подписи приложения." to "The GitHub file will be checked against the app package and signing certificate.",
        "Скачиваем…" to "Downloading…",
        "Скачиваем и проверяем официальный APK…" to "Downloading and verifying the official APK…",
        "APK принадлежит другому приложению. Установка отменена." to "The APK belongs to another app. Installation cancelled.",
        "Подпись APK не совпадает с установленной версией. Установка отменена." to "The APK signature does not match the installed app. Installation cancelled.",
        "Не удалось загрузить или проверить APK. Попробуйте позже." to "Could not download or verify the APK. Try again later.",
        "Повторить загрузку" to "Retry download",
        "APK проверен. Установка начнётся только после подтверждения Android." to "APK verified. Android will ask before installing it.",
        "Обновление готово" to "Update ready",
        "Android попросит подтвердить установку. Приложение перезапустится после её завершения." to "Android will ask you to confirm the install. The app will restart when it finishes.",
        "Установить сейчас" to "Install now",
        "Установить обновление" to "Install update",
        "Разрешите установку обновлений" to "Allow app installs",
        "Android откроет настройки разрешения для этого приложения. Вернитесь и нажмите «Установить обновление»." to "Android will open this app's install permission. Return and tap “Install update”.",
        "Открыть настройки" to "Open settings",
        "Не удалось открыть системный установщик Android." to "Could not open the Android package installer.",
        "Скачать обновление" to "Download update",
        "Открыть официальный релиз" to "Open official release",
        "примерно по IP после подключения" to "approx. by IP after connecting",
        "Фактический выход, регион определён примерно по IP" to "Observed exit, region estimated by IP",
        "выход · примерно по IP · только в памяти" to "exit · approx. by IP · memory only",
        "определяем регион выхода…" to "Locating exit region…",
        "регион выхода по IP недоступен" to "Exit region by IP is unavailable",
        "выход · примерно по IP" to "exit · approx. by IP",
        "регион выхода появится после подключения" to "Exit region appears after connecting",
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
