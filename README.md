<p align="center">
  <img src="assets/readme-banner.svg" alt="deytt./connect — клиенты DEYTTT для Android и Windows" width="100%">
</p>

<h1 align="center">deytt./connect</h1>
<p align="center">
  VPN-клиенты DEYTTT для телефона и компьютера.<br>
  Выбирайте маршрут, подключайтесь и управляйте доступом через Telegram.
</p>

<p align="center">
  <a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30"><img src="https://img.shields.io/badge/Android-v0.8.30-3DDC84?logo=android&logoColor=white" alt="Android: стабильный релиз v0.8.30"></a>
  <a href="https://github.com/crxwov/deytt.connect/releases/latest"><img src="https://img.shields.io/badge/Windows-v0.8.40-0078D4?logo=windows&logoColor=white" alt="Windows: стабильный релиз v0.8.40"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--or--later-8B7CF6" alt="Лицензия GPL-3.0-or-later"></a>
</p>

<p align="center">
  <a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30">Android APK</a>
  · <a href="https://github.com/crxwov/deytt.connect/releases/latest">Windows MSI и portable</a>
  · <a href="https://t.me/deyttbot">Поддержка</a>
  · <a href="https://deytt.space/info/">Условия и конфиденциальность</a>
</p>

Для входа нужен активный доступ DEYTTT. Телефон и компьютер используют отдельные слоты подписки: установка Windows-клиента не занимает слот телефона.

## Скачать

| Платформа | Стабильная версия | Файл |
| --- | --- | --- |
| **Android** | [v0.8.30](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30) | Android 7.0+; APK для ARM64 подходит большинству телефонов. Для других устройств выберите сборку с подходящей архитектурой. |
| **Windows** | [v0.8.40](https://github.com/crxwov/deytt.connect/releases/latest) | Windows 10 1809+ · x64; установщик MSI или portable ZIP. |

Для MSI и portable ZIP опубликованы SHA-256. Сборки сейчас не подписаны сертификатом издателя, поэтому Windows может показать предупреждение. Сверьте SHA-256 с файлом из того же релиза. Android-клиент распространяется APK из GitHub и не опубликован в Google Play.

### Установка на Android

1. Скачайте APK для своей архитектуры из [стабильного релиза Android](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30). Для большинства устройств подходит ARM64.
2. Откройте APK в системном установщике Android и войдите через Telegram.
3. Выберите «Авто» или доступный маршрут, нажмите «Подключить» и подтвердите системный запрос Android на создание VPN-подключения.

Если на телефоне осталась версия 0.8.17 со старой подписью, используйте APK с суффиксом <code>legacy</code> и подходящей архитектурой. Для остальных поддерживаемых обновлений устанавливайте обычный APK; подробности указаны в заметках [релиза Android](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30).

### Установка на Windows

- **MSI:** установите <code>deytt-connect-0.8.40.msi</code>. Установщик попросит подтверждение Windows для службы VPN.
- **Portable:** распакуйте весь <code>deytt-connect-0.8.40-portable.zip</code> и запустите <code>deyttconnect.exe</code>. Для установки службы VPN также потребуется подтверждение администратора.

Нужен Microsoft Edge WebView2 Runtime. Установщик MSI может загрузить его с сайта Microsoft, если общего runtime ещё нет. Подробности — в [руководстве по установщику](windows/installer/README.md).

## Возможности

- Вход через Telegram и загрузка маршрутов активной подписки.
- Автоматический выбор маршрута, список доступных регионов и диагностика соединения.
- Проверка доступности, задержки и скорости маршрутов.
- Обновление приложения и проверка новых релизов из поддерживаемого клиента.

| Протокол | Android | Windows |
| --- | :---: | :---: |
| VLESS | ✓ | ✓ |
| Trojan | ✓ | ✓ |
| Hysteria 2 | ✓ | ✓ |
| AmneziaWG 1.5 | ✓ | — |
| AmneziaWG 3.1 | ✓ | ✓ |

На Android карта регионов использует IP-адрес через [ipinfo.io](https://ipinfo.io), а не GPS. Подробнее — в [условиях и политике конфиденциальности](https://deytt.space/info/).

## Сборка из исходников

Клонируйте проект вместе с подмодулями:

~~~bash
git clone --recurse-submodules https://github.com/crxwov/deytt.connect.git
cd deytt.connect
~~~

### Android

Нужны JDK 17, Android SDK 35, NDK 26.1 и CMake 3.22.1:

~~~bash
./gradlew assembleDebug
~~~

ARM64 APK появится в <code>app/build/outputs/apk/debug/app-arm64-v8a-debug.apk</code>. Для распространения используйте [инструкцию по безопасной сборке релиза](docs/release-security.md).

### Windows

Для компонентов Windows нужны Windows SDK, .NET 10 SDK, Go и рекурсивные подмодули. Сценарии сборки и упаковки находятся в <code>windows/</code>; детали MSI — в [руководстве по установщику](windows/installer/README.md).

## Поддержка и сообщения об ошибках

По вопросам входа, подписки и подключения напишите в [бот DEYTTT](https://t.me/deyttbot).

Ошибку клиента можно описать в [GitHub Issue](https://github.com/crxwov/deytt.connect/issues): укажите платформу, версию приложения и ОС, модель устройства и шаги воспроизведения. Issues публичные. Не прикладывайте username, коды входа, ссылки подписки, VPN-ключи, конфигурации, IP-адреса или снимки экрана с данными аккаунта.

О проблемах безопасности сообщайте приватно через [Security Advisories](https://github.com/crxwov/deytt.connect/security/advisories/new). Подробности — в [политике безопасности](SECURITY.md).

## Лицензия

Код приложения распространяется по **GNU GPL-3.0-or-later**. У сторонних компонентов и материалов отдельные условия: см. [уведомления о сторонних компонентах](THIRD-PARTY-NOTICES.md) и [атрибуции материалов](THIRD_PARTY_NOTICES.md).
