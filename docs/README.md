# Deytt Connect — Индекс технической документации

Добро пожаловать в техническую документацию клиентской экосистемы **deytt./connect** (клиенты для Android и Windows, нативные туннельные ядра, протоколы и конвейер доставки).

---

## Основные разделы

| Документ | Описание | Ключевые темы |
| :--- | :--- | :--- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | **Глобальная архитектура системы** | Топология флота, архитектура Android и Windows, разделение привилегий, Canary Health |
| [MODULES.md](MODULES.md) | **Спецификация модулей и пакетов** | Декомпозиция Android (`:app`, `:awg-tunnel`, `:openflux-tunnel`), Windows (`DeyttConnect.*`), зависимости |
| [PROTOCOLS.md](PROTOCOLS.md) | **Протоколы и туннелирование** | AmneziaWG 1.5/3.1 (обфускация `Jc`, `S1`..`S4`, `H1`..`H4`), VLESS Reality, Trojan, Hysteria 2, CDN LTE XHTTP, Telegram Pairing |
| [IPC_AND_SERVICES.md](IPC_AND_SERVICES.md) | **IPC и системные службы** | Windows Named Pipe (`\\.\pipe\DEYTT.Connect.v1`, SDDL, фрейминг) и Android `VpnService` lifecycle |
| [BUILD_GUIDE.md](BUILD_GUIDE.md) | **Руководство по сборке** | Сборка и подпись Android Universal Release APK (`bb39...`), сборка Windows Self-Contained, WiX MSI и Portable ZIP |
| [UPDATER_PIPELINE.md](UPDATER_PIPELINE.md) | **Конвейер обновлений** | Прямое зеркало `deytt.space/connect/releases/`, Nginx `directio`, 4-поточный параллельный загрузчик, `update.json` |

---

## Специализированные спецификации и отчеты

- [release-security.md](release-security.md) — Политика подписи релизов, защита ключей и правила ротации сертификатов.
- [desktop-android-parity.md](desktop-android-parity.md) — Матрица паритета интерфейса и функционала между Android и Windows.
- [split-tunneling.md](split-tunneling.md) — Механика раздельного туннелирования доменов и приложений.
- [subscription-reliability.md](subscription-reliability.md) — Обеспечение надежности загрузки и атомарного сохранения подписки.
- [windows-10-native-acceptance.md](windows-10-native-acceptance.md) — Программа приемочных испытаний десктопного клиента на Windows 10/11.
