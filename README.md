<!--
  deytt./connect — README для GitHub.
  Графика кнопок уже находится в assets/ репозитория.
  Баннер намеренно не используется: оформление самодостаточно и без него.
-->

<div align="center">

  <sub>DEYTT. / OPEN SOURCE / ANDROID + WINDOWS</sub>

  <h1>deytt./connect</h1>

  <p>
    <strong>Открой. Выбери маршрут. Подключись.</strong><br>
    Приложение deytt. для Android и Windows — с входом через Telegram,<br>
    выбором регионов и диагностикой соединения.
  </p>

  <p>
    <a href="#скачать-приложение">скачать</a> &nbsp;·&nbsp;
    <a href="#как-подключиться">начать работу</a> &nbsp;·&nbsp;
    <a href="#возможности">возможности</a> &nbsp;·&nbsp;
    <a href="#протоколы">протоколы</a> &nbsp;·&nbsp;
    <a href="#сборка-из-исходников">для разработчиков</a>
  </p>

  <p>
    <a href="https://deytt.space/">сайт ↗</a> &nbsp;·&nbsp;
    <a href="https://t.me/deyttbot">Telegram ↗</a> &nbsp;·&nbsp;
    <a href="https://github.com/crxwov/deytt.connect/releases">все релизы ↗</a>
  </p>

</div>

---

## Скачать приложение

<sub>01 / DOWNLOAD</sub>

Версии выпускаются отдельно для каждой платформы. Выберите устройство — загрузка начнётся с **GitHub Releases**.

<table>
  <tr>
    <td width="50%" valign="top">
      <p><sub>01 / MOBILE</sub></p>
      <h3>Android</h3>
      <p><strong>v0.8.30</strong> · Android 7.0+ · ARM64</p>
      <p>APK для большинства современных Android-смартфонов с 64-битным ARM-процессором.</p>
      <p>
        <a href="https://github.com/crxwov/deytt.connect/releases/download/v0.8.30/deytt-connect-0.8.30.apk">
          <img src="assets/download-android.svg" width="224" alt="Скачать Android APK v0.8.30">
        </a>
      </p>
      <p><a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30">Описание релиза и SHA-256 ↗</a></p>
      <p><sub>Установка из APK. В Google Play приложения нет.</sub></p>
    </td>
    <td width="50%" valign="top">
      <p><sub>02 / DESKTOP</sub></p>
      <h3>Windows</h3>
      <p><strong>v0.8.40</strong> · Windows 10 (1809+) · x64</p>
      <p>Установщик MSI для обычной установки или ZIP для портативного запуска.</p>
      <p>
        <a href="https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40.msi">
          <img src="assets/download-windows.svg" width="224" alt="Скачать Windows MSI v0.8.40">
        </a>
      </p>
      <p><a href="https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40-portable.zip">Portable ZIP ↗</a> &nbsp;·&nbsp; <a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.40">SHA-256 и релиз ↗</a></p>
      <p><sub>Сборки Windows не подписаны сертификатом издателя.</sub></p>
    </td>
  </tr>
</table>

> [!IMPORTANT]
> **Нужен активный доступ deytt.** Телефон и компьютер занимают **разные слоты подписки** — установка Windows не расходует слот телефона. Управлять доступом можно через [Telegram-бота](https://t.me/deyttbot).

## Как подключиться

<sub>02 / GET STARTED</sub>

<table>
  <tr>
    <td width="33%" valign="top">
      <sub>ШАГ 01</sub>
      <h3>Получить доступ</h3>
      <p>Откройте <a href="https://t.me/deyttbot">бота deytt.</a> и проверьте активную подписку.</p>
    </td>
    <td width="34%" valign="top">
      <sub>ШАГ 02</sub>
      <h3>Войти</h3>
      <p>Установите приложение и авторизуйтесь через Telegram.</p>
    </td>
    <td width="33%" valign="top">
      <sub>ШАГ 03</sub>
      <h3>Подключиться</h3>
      <p>Выберите «Авто» или регион и нажмите «Подключить».</p>
    </td>
  </tr>
</table>

<details>
<summary><strong>Android — подробная установка и обновление</strong></summary>

1. Скачайте [APK v0.8.30](https://github.com/crxwov/deytt.connect/releases/download/v0.8.30/deytt-connect-0.8.30.apk) для **ARM64**.
2. Откройте APK и разрешите установку из этого источника, если Android запросит подтверждение.
3. Запустите приложение и войдите через Telegram.
4. Выберите «Авто» или доступный маршрут, нажмите «Подключить» и подтвердите стандартный запрос Android на создание VPN-подключения.

**Если установлена версия `0.8.17` со старой подписью:** обычный APK может не установиться поверх неё. В опубликованном релизе `v0.8.30` отдельного APK с суффиксом `legacy` нет. Перед удалением старой версии проверьте [заметки к релизу](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30) и уточните порядок обновления у [поддержки](https://t.me/deyttbot).

</details>

<details>
<summary><strong>Windows — MSI или portable ZIP</strong></summary>

**MSI / обычная установка**

1. Скачайте [`deytt-connect-0.8.40.msi`](https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40.msi).
2. Запустите установщик и подтвердите системный запрос на установку VPN-службы.
3. Откройте deytt./connect, войдите через Telegram и выберите маршрут.

**ZIP / портативная версия**

1. Скачайте [`deytt-connect-0.8.40-portable.zip`](https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40-portable.zip).
2. **Полностью распакуйте** архив и запустите `deyttconnect.exe`.
3. При установке VPN-службы подтвердите запрос администратора Windows.

Для работы приложения требуется **Microsoft Edge WebView2 Runtime**. Если компонента нет в системе, установщик MSI может загрузить его с сайта Microsoft. Подробнее — в [руководстве по установщику](windows/installer/README.md).

</details>

## Возможности

<sub>03 / EXPERIENCE</sub>

<table>
  <tr>
    <td width="50%" valign="top">
      <sub>01 / АВТОРИЗАЦИЯ</sub>
      <h3>Вход через Telegram</h3>
      Маршруты загружаются из активной подписки. Не нужно вводить конфигурации вручную.
    </td>
    <td width="50%" valign="top">
      <sub>02 / МАРШРУТЫ</sub>
      <h3>Авто или свой выбор</h3>
      Автоматический выбор маршрута и список доступных регионов.
    </td>
  </tr>
  <tr>
    <td valign="top">
      <sub>03 / ДИАГНОСТИКА</sub>
      <h3>Контроль соединения</h3>
      Проверка доступности, задержки и скорости маршрутов.
    </td>
    <td valign="top">
      <sub>04 / ОБНОВЛЕНИЯ</sub>
      <h3>Новые версии</h3>
      Проверка обновлений приложения и переход к опубликованным релизам.
    </td>
  </tr>
</table>

## Протоколы

<sub>04 / COMPATIBILITY</sub>

Поддержка зависит от платформы. Конкретные маршруты определяются вашей подпиской.

| Протокол | Android | Windows |
| :--- | :---: | :---: |
| **VLESS** | ✓ | ✓ |
| **Trojan** | ✓ | ✓ |
| **Hysteria 2** | ✓ | ✓ |
| **AmneziaWG 1.5** | ✓ | — |
| **AmneziaWG 3.1** | ✓ | ✓ |

> [!NOTE]
> **Как определяется регион.** На Android карта регионов ориентируется на IP-адрес, а не на GPS. Для определения региона приложение обращается к [ipinfo.io](https://ipinfo.io). Подробнее — в [условиях и политике конфиденциальности](https://deytt.space/info/).

## Сборка из исходников

<sub>05 / DEVELOPMENT</sub>

Проект распространяется с открытым исходным кодом. Для клонирования понадобятся **Git-подмодули**.

<details>
<summary><strong>Android · JDK 17 / Android SDK 35 / NDK 26.1 / CMake 3.22.1</strong></summary>

```bash
git clone --recurse-submodules https://github.com/crxwov/deytt.connect.git
cd deytt.connect
./gradlew assembleDebug
```

ARM64 APK будет находиться по пути:

```text
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Перед публикацией приложения изучите [инструкцию по безопасной сборке и подписи](docs/release-security.md). Отладочная сборка не заменяет релизную.

</details>

<details>
<summary><strong>Windows · Windows SDK / .NET 10 SDK / Go</strong></summary>

Для сборки необходимы **Windows SDK**, **.NET 10 SDK**, **Go** и подмодули репозитория. Исходники и сценарии сборки и упаковки находятся в каталоге [`windows/`](windows/).

Подробности о составе MSI, установке службы и упаковке доступны в [руководстве по установщику](windows/installer/README.md).

</details>

**Материалы для разработчиков:** [документация](docs/README.md) · [бренд-гайд](docs/brand-guide.md) · [вклад в проект](CONTRIBUTING.md)

## Поддержка и безопасность

<sub>06 / SUPPORT</sub>

<table>
  <tr>
    <td width="50%" valign="top">
      <sub>АККАУНТ И ПОДКЛЮЧЕНИЕ</sub>
      <h3><a href="https://t.me/deyttbot">Telegram-бот ↗</a></h3>
      Вход, подписка, слоты устройств, доступ и маршруты.
    </td>
    <td width="50%" valign="top">
      <sub>ОШИБКА ПРИЛОЖЕНИЯ</sub>
      <h3><a href="https://github.com/crxwov/deytt.connect/issues">GitHub Issues ↗</a></h3>
      Укажите платформу, версию приложения и ОС, модель устройства и шаги воспроизведения.
    </td>
  </tr>
</table>

> [!CAUTION]
> **GitHub Issues публичные.** Не прикладывайте логины, коды входа, ссылки подписки, VPN-ключи, конфигурации, IP-адреса и снимки экрана с данными аккаунта.

Уязвимости следует сообщать **приватно**, через [GitHub Security Advisories](https://github.com/crxwov/deytt.connect/security/advisories/new), а не в публичном Issue. Подробности — в [политике безопасности](SECURITY.md).

---

<div align="center">

  <h3>deytt./connect</h3>

  <p><sub>одно приложение. две платформы. ваш маршрут.</sub></p>

  <p>
    <a href="https://deytt.space/">deytt.space</a> &nbsp;·&nbsp;
    <a href="https://t.me/deyttbot">поддержка</a> &nbsp;·&nbsp;
    <a href="https://github.com/crxwov/deytt.connect/releases">релизы</a> &nbsp;·&nbsp;
    <a href="LICENSE">лицензия</a>
  </p>

  <sub>
    Исходный код — <a href="LICENSE">GNU GPL-3.0-or-later</a>.<br>
    Сторонние компоненты и графические материалы: <a href="THIRD-PARTY-NOTICES.md">уведомления</a> · <a href="THIRD_PARTY_NOTICES.md">атрибуции</a>.
  </sub>

</div>
