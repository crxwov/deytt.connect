<div align="center">
  <img src="assets/deytt-connect-banner.png" alt="deytt./connect: маршруты и подключение на Android и Windows" width="100%">
</div>

<p align="center"><a href="#скачать-приложение">Скачать</a> · <a href="#начать-работу">Начать работу</a> · <a href="#возможности">Возможности</a> · <a href="#разработка">Разработка</a></p>

## Скачать приложение

Выберите версию для своего устройства. Сборки публикуются в GitHub Releases.

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>Android</h3>
      <p><strong>v0.8.30</strong> · Android 7.0+ · ARM64</p>
      <p>APK для телефонов с ARM64.</p>
      <p align="center"><a href="https://github.com/crxwov/deytt.connect/releases/download/v0.8.30/deytt-connect-0.8.30.apk"><img src="assets/download-android-outline.svg" width="260" alt="Скачать Android APK v0.8.30"></a></p>
      <p><a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30">Релиз и SHA-256</a> · APK устанавливается вручную, приложения нет в Google Play.</p>
    </td>
    <td width="50%" valign="top">
      <h3>Windows</h3>
      <p><strong>v0.8.40</strong> · Windows 10 (1809+) · x64</p>
      <p>Установщик MSI или portable ZIP.</p>
      <p align="center"><a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.40"><img src="assets/download-windows-outline.svg" width="260" alt="Скачать Windows MSI или portable ZIP v0.8.40"></a></p>
      <p><a href="https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40-portable.zip">Скачать portable ZIP</a> · <a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.40">SHA-256 и заметки к релизу</a></p>
      <p><sub>Сборки Windows не подписаны сертификатом издателя.</sub></p>
    </td>
  </tr>
</table>

Для входа нужен активный доступ deytt. Android и Windows используют отдельные слоты подписки. Установка Windows не занимает слот телефона. Управляйте доступом через [Telegram-бота](https://t.me/deyttbot).

## Начать работу

1. [Получите доступ через Telegram-бота](https://t.me/deyttbot).
2. Скачайте и установите приложение для своего устройства.
3. Войдите через Telegram, выберите «Авто» или маршрут и нажмите «Подключить».

<details>
<summary><strong>Установка и обновление Android</strong></summary>

1. Скачайте APK для ARM64 из [релиза v0.8.30](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30).
2. Откройте APK и подтвердите установку, если Android запросит разрешение.
3. Запустите приложение и войдите через Telegram.
4. Выберите маршрут и подтвердите системный запрос на создание VPN-подключения.

Если у вас установлена версия `0.8.17` со старой подписью, сначала прочитайте [заметки к релизу](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30). В релизе `v0.8.30` нет отдельного APK с суффиксом `legacy`. Если обновление не устанавливается, обратитесь в [поддержку](https://t.me/deyttbot), прежде чем удалять приложение.

</details>

<details>
<summary><strong>Установка Windows</strong></summary>

**MSI**

1. Скачайте [`deytt-connect-0.8.40.msi`](https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40.msi).
2. Запустите установщик и подтвердите запрос на установку VPN-службы.
3. Откройте приложение и войдите через Telegram.

**Portable ZIP**

1. Скачайте [`deytt-connect-0.8.40-portable.zip`](https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40-portable.zip).
2. Полностью распакуйте архив и запустите `deyttconnect.exe`.
3. Подтвердите запрос администратора Windows при установке VPN-службы.

Для работы приложения нужен **Microsoft Edge WebView2 Runtime**. Если компонента нет, установщик MSI может загрузить его с сайта Microsoft. Подробнее — в [руководстве по установщику](windows/installer/README.md).

</details>

## Возможности

- Вход через Telegram и маршруты активной подписки
- Автоматический выбор или ручной выбор маршрута
- Проверка доступности, задержки и скорости соединения
- Карта регионов на Android и уведомления об обновлениях

## Поддерживаемые протоколы

Доступность протокола зависит от платформы и конфигурации маршрута.

| Протокол | Android | Windows |
| :--- | :---: | :---: |
| VLESS | ✓ | ✓ |
| Trojan | ✓ | ✓ |
| Hysteria 2 | ✓ | ✓ |
| AmneziaWG 1.5 | ✓ | — |
| AmneziaWG 3.1 | ✓ | ✓ |

На Android карта регионов использует IP-адрес, а не GPS. Для определения региона приложение обращается к [ipinfo.io](https://ipinfo.io). Подробнее — в [условиях и политике конфиденциальности](https://deytt.space/info/).

## Разработка

Для сборки клонируйте репозиторий вместе с подмодулями:

```bash
git clone --recurse-submodules https://github.com/crxwov/deytt.connect.git
cd deytt.connect
```

<details>
<summary><strong>Сборка Android</strong></summary>

Нужны JDK 17, Android SDK 35, NDK 26.1 и CMake 3.22.1.

```bash
./gradlew assembleDebug
```

ARM64 APK появится в `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. Перед публикацией прочитайте [инструкцию по безопасной сборке и подписи](docs/release-security.md).

</details>

<details>
<summary><strong>Сборка Windows</strong></summary>

Для сборки нужны Windows SDK, .NET 10 SDK, Go и подмодули репозитория. Исходники и сценарии сборки находятся в каталоге [`windows/`](windows/). Состав MSI и требования к установке описаны в [руководстве по установщику](windows/installer/README.md).

</details>

Другие материалы: [документация](docs/README.md) · [памятка по стилю](docs/brand-guide.md) · [участие в проекте](CONTRIBUTING.md)

## Поддержка и безопасность

Куда обратиться:

- **Доступ и подключение:** [Telegram-бот](https://t.me/deyttbot)
- **Ошибка приложения:** [GitHub Issues](https://github.com/crxwov/deytt.connect/issues)

Issues публичные. Не публикуйте коды входа, ссылки подписки, VPN-ключи, конфигурации, IP-адреса и снимки экрана с данными аккаунта.

О проблемах безопасности сообщайте приватно через [GitHub Security Advisories](https://github.com/crxwov/deytt.connect/security/advisories/new). Подробнее — в [политике безопасности](SECURITY.md).

Исходный код распространяется по лицензии [GNU GPL-3.0-or-later](LICENSE). Условия для сторонних компонентов и графики собраны в [уведомлениях](THIRD-PARTY-NOTICES.md) и [атрибуциях](THIRD_PARTY_NOTICES.md).
