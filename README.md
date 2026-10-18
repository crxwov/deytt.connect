<div align="center">
  <img src="assets/deytt-connect-banner.png" alt="deytt./connect: маршруты и подключение для Android и Windows" width="100%">
  <p>
    <a href="#скачать"><img src="assets/readme-nav-download.svg" width="160" alt="скачать"></a>
    &nbsp;
    <a href="#как-начать"><img src="assets/readme-nav-start.svg" width="160" alt="как начать"></a>
    &nbsp;
    <a href="#возможности"><img src="assets/readme-nav-features.svg" width="160" alt="возможности"></a>
    &nbsp;
    <a href="#поддержка"><img src="assets/readme-nav-support.svg" width="160" alt="поддержка"></a>
  </p>
</div>

## Скачать

<table width="100%" align="center">
  <tbody>
    <tr>
      <td width="50%" align="center" valign="top">
        <h3>android</h3>
        <p><sub>android 7+ &nbsp;·&nbsp; универсальный APK</sub></p>
        <a href="https://github.com/crxwov/deytt.connect/releases/latest"><img src="assets/download-android-outline.svg" width="230" alt="скачать APK для Android"></a>
        <p><sub><a href="https://github.com/crxwov/deytt.connect/releases/latest">APK и SHA-256 в последнем релизе</a><br>приложения нет в google play</sub></p>
      </td>
      <td width="50%" align="center" valign="top">
        <h3>windows</h3>
        <p><sub>windows 10 (1809+) &nbsp;·&nbsp; x64 &nbsp;·&nbsp; v0.8.40</sub></p>
        <a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.40"><img src="assets/download-windows-outline.svg" width="230" alt="скачать MSI или portable ZIP для Windows"></a>
        <p><sub><a href="https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40-portable.zip">portable ZIP</a> &nbsp;·&nbsp; <a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.40">SHA-256 и релиз</a><br>сборки не подписаны</sub></p>
      </td>
    </tr>
  </tbody>
</table>

<p align="center"><sub>доступ выдаёт <a href="https://t.me/deyttbot">telegram-бот</a> &nbsp;·&nbsp; телефон и компьютер используют отдельные слоты</sub></p>

## Как начать

1. Получите доступ через [Telegram-бота](https://t.me/deyttbot) и установите приложение.
2. Войдите через Telegram и выберите «Авто» или нужный маршрут.
3. Нажмите **«Подключить»** и подтвердите запрос VPN на устройстве.

### Android

Установите APK из [последнего релиза](https://github.com/crxwov/deytt.connect/releases/latest). Если Android запросит разрешение на установку из этого источника, подтвердите его.

> **Если Android сообщает о несовпадении подписи:** старую версию нельзя обновить поверх, прежний ключ подписи утерян. Выйдите из аккаунта в старом приложении, удалите его и установите новый APK. Настройки могут удалиться, а для подключения потребуется снова войти. Это разовая миграция; следующие обновления устанавливаются поверх. Если старое приложение недоступно или слот не освободился, обратитесь в [поддержку](https://t.me/deyttbot).

### Windows

Установите [MSI](https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40.msi) или распакуйте [portable ZIP](https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40-portable.zip) целиком и запустите `deyttconnect.exe`. При установке VPN-службы подтвердите запрос администратора Windows.

Нужен [Microsoft Edge WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/). Если компонента нет, MSI может скачать его.

## Возможности

<table width="100%">
  <tbody>
    <tr>
      <td width="50%" valign="top"><strong>маршруты</strong><br><sub>автоматический выбор или конкретный регион</sub></td>
      <td width="50%" valign="top"><strong>подключение</strong><br><sub>один аккаунт Telegram на Android и Windows</sub></td>
    </tr>
    <tr>
      <td width="50%" valign="top"><strong>диагностика</strong><br><sub>состояние соединения и доступность маршрутов</sub></td>
      <td width="50%" valign="top"><strong>регион Android</strong><br><sub>определяется по IP, не по GPS</sub></td>
    </tr>
  </tbody>
</table>

### Протоколы

Доступность зависит от платформы и конфигурации маршрута.

| Протокол | Android | Windows |
|:--|:--:|:--:|
| VLESS | ✓ | ✓ |
| Trojan | ✓ | ✓ |
| Hysteria 2 | ✓ | ✓ |
| AmneziaWG 3.1 | ✓ | ✓ |

[Условия и конфиденциальность](https://deytt.space/info/)

## Для разработчиков

Склонируйте репозиторий вместе с подмодулями:

```bash
git clone --recurse-submodules https://github.com/crxwov/deytt.connect.git
cd deytt.connect
```

- **Android**: JDK 17, Android SDK 35, NDK 26.1, CMake 3.22.1. Сборка: `./gradlew assembleDebug`
- **Windows**: Windows SDK, .NET 10 SDK, Go и подмодули. Исходники: [`windows/`](windows/). [Состав MSI](windows/installer/README.md).

Перед публикацией Android-приложения прочитайте [инструкцию по безопасной сборке и подписи](docs/release-security.md).

[Документация](docs/README.md) &nbsp;·&nbsp; [Стиль проекта](docs/brand-guide.md) &nbsp;·&nbsp; [Участие в проекте](CONTRIBUTING.md)

## Поддержка

<table width="100%">
  <tbody>
    <tr>
      <td width="50%" valign="top"><strong>аккаунт и приложение</strong><br><sub><a href="https://t.me/deyttbot">telegram-бот</a> &nbsp;·&nbsp; <a href="https://github.com/crxwov/deytt.connect/issues">ошибка приложения</a></sub></td>
      <td width="50%" valign="top"><strong>безопасность</strong><br><sub><a href="https://github.com/crxwov/deytt.connect/security/advisories/new">сообщить об уязвимости</a> &nbsp;·&nbsp; <a href="SECURITY.md">политика безопасности</a></sub></td>
    </tr>
  </tbody>
</table>

Issues публичны. Не публикуйте коды входа, ссылки подписки, VPN-ключи, конфигурации, IP-адреса и данные аккаунта.

Исходный код: [GNU GPL-3.0-or-later](LICENSE) &nbsp;·&nbsp; материалы сторонних авторов: [уведомления](THIRD-PARTY-NOTICES.md) и [атрибуции](THIRD_PARTY_NOTICES.md).
