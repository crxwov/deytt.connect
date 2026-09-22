# deytt./connect

Открытый Android-клиент DEYTTT: единый UI поверх системного `VpnService` и
общего sing-box core.

Репозиторий: <https://github.com/crxwov/deytt.connect>

## Текущий MVP

- принимает HTTPS-ссылку DEYTTT на подписку;
- переводит её в серверный формат `?format=singbox`;
- проверяет наличие TUN, маршрута и сетевого outbound;
- сохраняет профиль атомарно и оставляет предыдущую рабочую копию;
- запускает настоящий Android `VpnService` через `libbox`;
- предлагает маршрут до запуска и подтверждает соединение внешней HTTPS-проверкой;
- останавливает сетевое ядро при любой ошибке, чтобы следующий запуск не
  блокировался занятым cache-file.

На этом этапе не заявляются split-tunnel UI, kill switch, импорт сырых
`vless://`/`trojan://`/`hy2://` ссылок, AmneziaWG 3.1 и production-ready
reconnect после смены сети. Это следующие этапы, а не скрытый fallback.

## Сборка

Нужны JDK 17, Android SDK с platform 35 и доступ к Maven Central/JitPack:

```bash
./gradlew assembleDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

Перед публикацией необходимо проверить лицензионный пакет GPL-компонентов,
подписывать релиз собственным ключом вне репозитория и приложить checksum.

## Лицензия

Код проекта распространяется под GNU GPL v3.0-or-later. Ядро `libbox` и
связанные материалы имеют собственные уведомления в
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). Название и брендинг
SagerNet/sing-box не используются как обозначение официального приложения.
