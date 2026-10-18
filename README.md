<div align="center">
  <img src="assets/deytt-connect-banner.png" alt="deytt./connect для android и windows" width="100%">
  <p><strong>vpn-клиент для android и windows</strong></p>
  <p><a href="#скачать">скачать</a> · <a href="#начало-работы">начало работы</a> · <a href="#поддержка-и-безопасность">поддержка</a></p>
</div>

## Скачать

| Платформа | Требования | Скачать |
|:--|:--|:--|
| Android | Android 7+, универсальный APK | [Последний APK и SHA-256](https://github.com/crxwov/deytt.connect/releases/latest) |
| Windows | Windows 10 (1809+), x64 | [MSI и SHA-256](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.40) · [Portable ZIP](https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40-portable.zip) |

Приложения нет в Google Play. Сборки Windows не подписаны.

## Начало работы

Получите доступ через [Telegram-бота](https://t.me/deyttbot), установите приложение, войдите через Telegram и выберите маршрут. Для подключения нажмите **«Подключить»**. Устройства используют отдельные слоты: установка Windows не занимает слот телефона.

### Android

Скачайте APK, откройте файл и разрешите установку, если Android запросит подтверждение. При первом подключении подтвердите создание VPN-подключения.

Если Android сообщает о несовпадении подписи, удалите старую версию и установите APK заново: прежний ключ подписи утерян, поэтому обновление поверх невозможно. Перед удалением выйдите из аккаунта в старом приложении, чтобы освободить слот телефона. Настройки могут удалиться; потребуется войти снова. Если старое приложение недоступно или слот не освободился, обратитесь в [поддержку](https://t.me/deyttbot). Это разовая миграция; дальнейшие обновления устанавливаются поверх.

### Windows

Запустите MSI и подтвердите установку VPN-службы. Для portable-версии полностью распакуйте ZIP, запустите `deyttconnect.exe` и подтвердите запрос администратора при установке службы.

Для работы нужен [Microsoft Edge WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/). Если его нет, MSI может загрузить компонент. Подробности есть в [руководстве по установщику](windows/installer/README.md).

## Возможности

- Вход через Telegram
- Автоматический или выбранный маршрут
- Диагностика состояния соединения
- Обновление приложения
- Регион Android определяется по IP, не по GPS

## Протоколы

Доступность зависит от платформы и конфигурации маршрута.

| Протокол | Android | Windows |
|:--|:--:|:--:|
| VLESS | ✓ | ✓ |
| Trojan | ✓ | ✓ |
| Hysteria 2 | ✓ | ✓ |
| AmneziaWG 3.1 | ✓ | ✓ |

[Условия и конфиденциальность](https://deytt.space/info/)

## Разработка

Клонируйте репозиторий вместе с подмодулями:

```bash
git clone --recurse-submodules https://github.com/crxwov/deytt.connect.git
cd deytt.connect
```

- **Android**: JDK 17, Android SDK 35, NDK 26.1, CMake 3.22.1. Сборка: `./gradlew assembleDebug`
- **Windows**: Windows SDK, .NET 10 SDK, Go и подмодули. Исходники и сценарии находятся в [`windows/`](windows/); состав MSI описан в [руководстве по установщику](windows/installer/README.md).

Перед публикацией Android-приложения прочитайте [инструкцию по безопасной сборке и подписи](docs/release-security.md).

[Документация](docs/README.md) · [Стиль проекта](docs/brand-guide.md) · [Участие в проекте](CONTRIBUTING.md)

## Поддержка и безопасность

- Аккаунт и подключение: [Telegram-бот](https://t.me/deyttbot)
- Ошибки приложения: [GitHub Issues](https://github.com/crxwov/deytt.connect/issues)
- Уязвимости: [сообщить конфиденциально](https://github.com/crxwov/deytt.connect/security/advisories/new) · [политика безопасности](SECURITY.md)

Issues публичны. Не публикуйте коды входа, ссылки подписки, VPN-ключи, конфигурации, IP-адреса и данные аккаунта.

Исходный код распространяется по [GNU GPL-3.0-or-later](LICENSE). Сторонние материалы: [уведомления](THIRD-PARTY-NOTICES.md) · [атрибуции](THIRD_PARTY_NOTICES.md).
