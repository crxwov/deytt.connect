<div align="center">
  <img src="assets/deytt-connect-banner.png" alt="deytt./connect: маршруты и подключение на android и windows" width="100%">
</div>

<p align="center"><a href="#скачать-приложение">скачать</a> &nbsp;·&nbsp; <a href="#как-начать">как начать</a> &nbsp;·&nbsp; <a href="#возможности">возможности</a> &nbsp;·&nbsp; <a href="#поддержка">поддержка</a></p>

<h3 align="center">скачать приложение</h3>

<p align="center">выберите устройство</p>

<p align="center"><strong>android</strong><br><sub>v0.8.30 &nbsp;·&nbsp; android 7+ &nbsp;·&nbsp; ARM64</sub></p>
<p align="center"><a href="https://github.com/crxwov/deytt.connect/releases/download/v0.8.30/deytt-connect-0.8.30.apk"><img src="assets/download-android-outline.svg" width="260" alt="скачать android APK v0.8.30"></a></p>
<p align="center"><sub><a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30">релиз и SHA-256</a> &nbsp;·&nbsp; приложения нет в google play</sub></p>

<p align="center"><strong>windows</strong><br><sub>v0.8.40 &nbsp;·&nbsp; windows 10 (1809+) &nbsp;·&nbsp; x64</sub></p>
<p align="center"><a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.40"><img src="assets/download-windows-outline.svg" width="260" alt="скачать windows MSI или portable ZIP v0.8.40"></a></p>
<p align="center"><sub><a href="https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40-portable.zip">portable ZIP</a> &nbsp;·&nbsp; <a href="https://github.com/crxwov/deytt.connect/releases/tag/v0.8.40">SHA-256 и релиз</a> &nbsp;·&nbsp; сборки не подписаны</sub></p>

<p align="center">доступ deytt. &nbsp;·&nbsp; отдельные слоты для телефона и компьютера<br><sub>установка windows не занимает слот телефона</sub></p>

<p align="center"><img src="assets/readme-route-flow.svg" width="520" alt="доступ соединяется с выбором маршрута и подключением"></p>
<p align="center">доступ &nbsp;·&nbsp; маршрут &nbsp;·&nbsp; соединение</p>

<h3>как начать</h3>

1. получите доступ через [telegram-бот](https://t.me/deyttbot).
2. скачайте приложение и войдите через telegram.
3. выберите «Авто» или маршрут и нажмите «Подключить».

<details>
<summary><strong>установка android</strong></summary>

1. скачайте APK для ARM64 из [релиза v0.8.30](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30).
2. откройте файл и подтвердите установку, если android запросит разрешение.
3. войдите через telegram, выберите маршрут и подтвердите создание vpn-подключения.

если установлена версия `0.8.17` со старой подписью, перед обновлением прочитайте [заметки к релизу](https://github.com/crxwov/deytt.connect/releases/tag/v0.8.30). в релизе `v0.8.30` нет APK с суффиксом `legacy`. если обновление не устанавливается, обратитесь в [поддержку](https://t.me/deyttbot), прежде чем удалять приложение.

</details>

<details>
<summary><strong>установка windows</strong></summary>

**MSI**

1. скачайте [`deytt-connect-0.8.40.msi`](https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40.msi).
2. запустите установщик и подтвердите установку vpn-службы.
3. откройте приложение и войдите через telegram.

**portable ZIP**

1. скачайте [`deytt-connect-0.8.40-portable.zip`](https://github.com/crxwov/deytt.connect/releases/download/v0.8.40/deytt-connect-0.8.40-portable.zip).
2. полностью распакуйте архив и запустите `deyttconnect.exe`.
3. подтвердите запрос администратора windows при установке vpn-службы.

для работы приложения нужен **microsoft edge webview2 runtime**. если компонента нет, MSI может загрузить его с сайта microsoft. подробнее — в [руководстве по установщику](windows/installer/README.md).

</details>

<h3 align="center">возможности</h3>

<p align="center">telegram-вход &nbsp;·&nbsp; авто-маршрут<br>диагностика &nbsp;·&nbsp; обновления</p>

<h3>протоколы</h3>

<p align="center">доступность зависит от платформы и конфигурации маршрута</p>

| протокол | android | windows |
| :--- | :---: | :---: |
| VLESS | ✓ | ✓ |
| Trojan | ✓ | ✓ |
| Hysteria 2 | ✓ | ✓ |
| AmneziaWG 1.5 | ✓ | — |
| AmneziaWG 3.1 | ✓ | ✓ |

<p align="center"><sub>на android регион определяется по ip, не по gps · <a href="https://deytt.space/info/">условия и конфиденциальность</a></sub></p>

<h3>для разработчиков</h3>

клонируйте репозиторий вместе с подмодулями:

```bash
git clone --recurse-submodules https://github.com/crxwov/deytt.connect.git
cd deytt.connect
```

<details>
<summary><strong>сборка android</strong></summary>

нужны JDK 17, android SDK 35, NDK 26.1 и CMake 3.22.1.

```bash
./gradlew assembleDebug
```

перед публикацией прочитайте [инструкцию по безопасной сборке и подписи](docs/release-security.md).

</details>

<details>
<summary><strong>сборка windows</strong></summary>

для сборки нужны windows SDK, .NET 10 SDK, Go и подмодули. исходники и сценарии находятся в каталоге [`windows/`](windows/). состав MSI описан в [руководстве по установщику](windows/installer/README.md).

</details>

<p align="center"><a href="docs/README.md">документация</a> &nbsp;·&nbsp; <a href="docs/brand-guide.md">стиль проекта</a> &nbsp;·&nbsp; <a href="CONTRIBUTING.md">участие в проекте</a></sub></p>

<h3 align="center">поддержка</h3>

<p align="center"><a href="https://t.me/deyttbot">доступ и подключение</a> &nbsp;·&nbsp; <a href="https://github.com/crxwov/deytt.connect/issues">ошибка приложения</a> &nbsp;·&nbsp; <a href="https://github.com/crxwov/deytt.connect/security/advisories/new">сообщить об уязвимости</a> &nbsp;·&nbsp; <a href="SECURITY.md">политика безопасности</a></p>

<p align="center"><sub>issues публичны. не публикуйте коды входа, ссылки подписки, vpn-ключи, конфигурации, ip-адреса и данные аккаунта</sub></p>

<p align="center"><sub>исходный код: <a href="LICENSE">GNU GPL-3.0-or-later</a> &nbsp;·&nbsp; сторонние материалы: <a href="THIRD-PARTY-NOTICES.md">уведомления</a> и <a href="THIRD_PARTY_NOTICES.md">атрибуции</a></sub></p>
