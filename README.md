<div align="center">
  <img src="assets/readme-banner.svg" alt="deytt./connect — open source Android client for DEYTTT" width="100%">

  <p><strong>Android-клиент DEYTTT для подключения к своей подписке и выбора VPN-маршрута.</strong></p>

  <p>
    <a href="https://github.com/crxwov/deytt.connect/releases/latest">Скачать APK для ARM64</a>
    · <a href="https://github.com/crxwov/deytt.connect/releases/latest">Релизы</a>
    · <a href="https://t.me/deyttbot">Поддержка</a>
    · <a href="https://deytt.space/info">Условия и конфиденциальность</a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 7.0 and newer">
    <img src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue" alt="GPL-3.0-or-later license">
    <img src="https://img.shields.io/badge/status-early%20access-f0b45b" alt="Early access">
  </p>
</div>

> Для использования нужен активный доступ DEYTTT. Сейчас опубликована ARM64-сборка для ручной установки; приложение не распространяется через Google Play.

## Подключение

**Android 7.0+ · ARM64 (`arm64-v8a`)**

1. Скачайте APK для ARM64 из [релизов](https://github.com/crxwov/deytt.connect/releases/latest) и откройте файл в системном установщике Android.
2. Войдите через Telegram: укажите username, запросите одноразовый код и подтвердите вход. Если код не приходит, откройте [бота DEYTTT](https://t.me/deyttbot) и запросите его ещё раз.
3. Выберите «Авто» или маршрут подписки, нажмите подключение и подтвердите системный запрос Android на создание VPN-подключения.
4. Разрешите уведомление о работающем VPN, если Android его запросит. Для отключения используйте кнопку в приложении.

> При переходе с 0.8.12 и более ранних версий на новую подпись откройте APK вручную: старый механизм обновления не понимает смену сертификата. Не удаляйте установленное приложение и не очищайте его данные. Если Android откажется обновлять сборку, напишите в поддержку.

## Что умеет приложение

- Войти через Telegram и загрузить маршруты активной подписки.
- Подключаться по VLESS, Trojan, Hysteria 2 и AmneziaWG 3.1 — если они доступны в подписке.
- Выбрать автоматический маршрут, отдельный регион или маршрут Russia → Germany.
- Проверить доступность, задержку и скорость маршрутов.
- Проверить новые GitHub-релизы и APK перед подтверждением установки.

Определение региона на карте можно включить в настройках. Для него используется IP-адрес через [ipinfo.io](https://ipinfo.io), а не GPS. Подробнее — в [политике конфиденциальности и условиях](https://deytt.space/info).

## Релизы и проверка файла

В [релизе `0.8.18-preview.1`](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.18-preview.1) находятся changelog и APK. Релизная сборка называется `app-arm64-v8a-release.apk`; её SHA-256 публикуется рядом с файлом. Старые версии распространялись как `debug.apk`. Сверяйте контрольную сумму именно выбранного релиза. APK с суффиксом `manual-reinstall` устанавливается только после удаления прежней версии: для него создан новый ключ подписи, а Android не принимает такую сборку поверх старой.

Release-сборки работают без отладки. Выпуск `0.8.18-preview.1` использует новый ключ, потому что прежний закрытый ключ утерян. Для установки нужно удалить старую версию и затем установить подходящий APK из релиза; локальные настройки могут удалиться, потребуется войти в аккаунт снова. Будущие APK будут обновляться поверх установленной версии только при сохранении нового ключа. Это не означает одобрение антивирусами или магазинами. О предупреждении защиты сообщите в поддержку, приложив его точный текст и версию приложения.

## Поддержка и сообщения об ошибках

По вопросам входа, подписки и подключения напишите в [бот DEYTTT](https://t.me/deyttbot). При ошибке загрузки подписки укажите код, показанный на экране: он не содержит ссылку подписки или данные аккаунта. Ошибку приложения можно описать в [GitHub Issue](https://github.com/crxwov/deytt.connect/issues): укажите версию приложения и Android, модель устройства и шаги воспроизведения.

Issues публичные. Не прикладывайте username, коды входа, ссылки подписки, VPN-ключи, конфигурации, IP-адреса или снимки экрана с данными аккаунта.

О проблемах безопасности сообщайте приватно через [Security Advisories](https://github.com/crxwov/deytt.connect/security/advisories/new). Подробности — в [политике безопасности](SECURITY.md).

## Сборка из исходников

Нужны JDK 17, Android SDK 35, NDK 26.1, CMake 3.22.1 и Git submodules:

```bash
git clone --recurse-submodules https://github.com/crxwov/deytt.connect.git
cd deytt.connect
./gradlew assembleDebug
```

Если репозиторий уже клонирован без подмодулей:

```bash
git submodule update --init --recursive
./gradlew assembleDebug
```

ARM64 APK появится в `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.

Для распространения используйте [сборку и проверку release APK](docs/release-security.md).

Проверки загрузки и изолированный тест на устройстве описаны в [subscription-reliability.md](docs/subscription-reliability.md).

## Лицензии

Код приложения распространяется по **GNU GPL-3.0-or-later**. Для сторонних компонентов и материалов действуют отдельные условия из [уведомлений о сторонних компонентах](THIRD-PARTY-NOTICES.md) и [атрибуций значков](THIRD_PARTY_NOTICES.md).

