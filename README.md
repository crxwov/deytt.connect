<p align="center">
  <img src="assets/readme-banner.svg" alt="deytt./connect: приложение для Android и Windows" width="100%">
</p>

## Скачать приложение

Выберите версию для своего устройства:

<table>
  <tr>
    <td width="50%" valign="top">
      <h3 align="center">Android</h3>
      <p align="center"><strong>v0.8.30</strong> · Android 7.0+</p>
      <p align="center">ARM64 подходит большинству телефонов. APK для других архитектур доступны в релизе.</p>
      <p align="center"><a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30"><img src="assets/download-android.svg" width="224" alt="Скачать APK"></a></p>
      <p align="center"><sub>APK загружается с GitHub. В Google Play приложения нет.</sub></p>
    </td>
    <td width="50%" valign="top">
      <h3 align="center">Windows</h3>
      <p align="center"><strong>v0.8.40</strong> · Windows 10 1809+ · x64</p>
      <p align="center">Установщик MSI или portable ZIP. Оба файла находятся на странице релиза.</p>
      <p align="center"><a href="https://github.com/crxwov/deytt.connect/releases/latest"><img src="assets/download-windows.svg" width="224" alt="Скачать MSI или portable ZIP"></a></p>
      <p align="center"><sub>Сборки не подписаны сертификатом издателя. SHA-256 есть на странице релиза.</sub></p>
    </td>
  </tr>
</table>

> Для входа нужен активный доступ deytt. Телефон и компьютер используют отдельные слоты подписки; установка приложения на Windows не занимает слот телефона.

## Установка

<details>
  <summary><strong>Установка на Android</strong></summary>

1. Скачайте APK для архитектуры устройства из [релиза Android](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30). Для большинства телефонов подходит ARM64.
2. Откройте APK и установите приложение. Затем войдите через Telegram.
3. Выберите «Авто» или доступный маршрут, нажмите «Подключить» и подтвердите системный запрос Android.

Если на телефоне установлена версия 0.8.17 со старой подписью, скачайте APK с суффиксом `legacy` для своей архитектуры. Для остальных поддерживаемых обновлений используйте обычный APK. Подробности есть в [заметках к релизу](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30).

</details>

<details>
  <summary><strong>Установка на Windows</strong></summary>

- **MSI:** скачайте `deytt-connect-0.8.40.msi` со [страницы релиза](https://github.com/crxwov/deytt.connect/releases/latest) и запустите установщик. Подтвердите установку службы VPN.
- **Portable:** скачайте и полностью распакуйте `deytt-connect-0.8.40-portable.zip`, затем запустите `deyttconnect.exe`. Для установки службы потребуется подтверждение администратора.

Приложению нужен Microsoft Edge WebView2 Runtime. Если его нет в системе, установщик MSI может загрузить runtime с сайта Microsoft. Подробнее — в [руководстве по установщику](windows/installer/README.md).

</details>

## Возможности

- Вход через Telegram и загрузка маршрутов активной подписки.
- Автоматический выбор маршрута, список регионов и диагностика соединения.
- Проверка доступности, задержки и скорости маршрутов.
- Обновление приложения и проверка новых релизов.

### Поддерживаемые протоколы

| Протокол | Android | Windows |
| --- | :---: | :---: |
| VLESS | ✓ | ✓ |
| Trojan | ✓ | ✓ |
| Hysteria 2 | ✓ | ✓ |
| AmneziaWG 1.5 | ✓ | — |
| AmneziaWG 3.1 | ✓ | ✓ |

На Android карта регионов использует IP-адрес, а не GPS. Для определения региона приложение обращается к [ipinfo.io](https://ipinfo.io). Подробнее — в [условиях и политике конфиденциальности](https://deytt.space/info/).

## Сборка из исходников

<details>
  <summary><strong>Требования и команды сборки</strong></summary>

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

ARM64 APK появится в `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. Перед публикацией соберите релиз по [инструкции по безопасной сборке](docs/release-security.md).

### Windows

Для сборки нужны Windows SDK, .NET 10 SDK, Go и рекурсивные подмодули. Сценарии сборки и упаковки находятся в `windows/`. Параметры MSI описаны в [руководстве по установщику](windows/installer/README.md).

</details>

## Поддержка и безопасность

По вопросам входа, подписки и подключения напишите в [бот deytt](https://t.me/deyttbot).

Инструкции для сборки и проверки собраны в [документации](docs/README.md).
Как сохранить единый стиль и предложить изменение — в [памятке по стилю](docs/brand-guide.md)
и [руководстве для участников](CONTRIBUTING.md).

Ошибку приложения можно описать в [GitHub Issues](https://github.com/crxwov/deytt.connect/issues). Укажите платформу, версию приложения и ОС, модель устройства и шаги воспроизведения. Issues публичные: не прикладывайте username, коды входа, ссылки подписки, VPN-ключи, конфигурации, IP-адреса или снимки экрана с данными аккаунта.

О проблемах безопасности сообщайте приватно через [Security Advisories](https://github.com/crxwov/deytt.connect/security/advisories/new). Подробности — в [политике безопасности](SECURITY.md).

## Лицензия

Код приложения распространяется по **GNU GPL-3.0-or-later**. У сторонних компонентов и материалов отдельные условия: см. [уведомления о сторонних компонентах](THIRD-PARTY-NOTICES.md) и [атрибуции материалов](THIRD_PARTY_NOTICES.md).
