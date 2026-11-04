# Deytt Connect — IPC и системные службы

Документ регламентирует межпроцессное взаимодействие (IPC), архитектуру привилегий и жизненный цикл системных служб на платформах **Windows** и **Android**.

---

## 1. Windows IPC: Протокол именованного канала (Named Pipe)

Связь между пользовательским интерфейсом (`DeyttConnect.Windows.exe`) и привилегированной системной службой (`DeyttConnect.Windows.Service.exe`) осуществляется через локальный именованный канал Windows.

### 1.1. Идентификация и параметры канала
- **Имя канала**: `\\.\pipe\DEYTT.Connect.v1`
- **Режим передачи**: Byte Stream (`PipeTransmissionMode.Byte`)
- **Ограничение клиентов**: Только локальные подключения (`PipeOptions.Asynchronous` + `PipeRejectRemoteClients` в Win32 API). Сетевые RPC-подключения блокируются ядром Windows.

### 1.2. Модель безопасности и SDDL (Security Descriptor)
Служба запускается от имени `NT AUTHORITY\SYSTEM` и создает канал со строгим списком управления доступом (DACL), разрешающим чтение и запись только авторизованному пользователю:

```sddl
D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;{allowedUserSid})
```
- `(A;;GA;;;SY)` — Полный доступ (Generic All) для локальной системы (`SYSTEM`).
- `(A;;GA;;;BA)` — Полный доступ для встроенной группы администраторов (`Builtin Administrators`).
- `(A;;GRGW;;;{allowedUserSid})` — Доступ только на чтение и запись (Generic Read + Write) для конкретного SID пользователя, запустившего инсталлятор.

Значение `{allowedUserSid}` сохраняется при установке или первом запуске службы в ветке реестра:
`HKLM\SOFTWARE\DEYTT\Connect\AllowedUserSid` (тип `REG_SZ`).

### 1.3. Бинарный фрейминг сообщений
Каждое сообщение (как запрос от GUI, так и ответ от службы) сериализуется с префиксом длины:

```
+-----------------------------+------------------------------------+
|  4 байта (uint32 Little-LE) |      N байт (UTF-8 JSON Payload)   |
+-----------------------------+------------------------------------+
```

- **Максимальный размер запроса**: 40 МиБ (`MaximumRequestBytes = 41_943_040`). Достаточно для передачи полных JSON-профилей sing-box и тяжелых списков Split Tunneling.
- **Максимальный размер ответа**: 1 МиБ (`MaximumResponseBytes = 1_048_576`).

### 1.4. Спецификация команд и полезной нагрузки

#### Команда `status`
Запрос текущего состояния туннеля и телеметрии.
- **Запрос**: `{"command": "status"}`
- **Ответ**:
  ```json
  {
    "ok": true,
    "state": "connected",
    "selected_route_tag": "route:NL:VLESS",
    "egress_ip": "185.xxx.xxx.xxx",
    "connected_at": "2026-11-04T12:00:00Z",
    "tx_bytes": 10485760,
    "rx_bytes": 52428800,
    "canary_health": "healthy",
    "engine_pid": 14208
  }
  ```

#### Команда `connect`
Запуск туннеля с переданной конфигурацией.
- **Запрос**:
  ```json
  {
    "command": "connect",
    "profile_json": "{ ... sing-box configuration ... }",
    "selected_route_tag": "route:DE:Trojan",
    "awg_config": "[Interface] ... [Peer] ...",
    "split_tunnel_mode": "vpn_only",
    "custom_bypass_domains": ["gosuslugi.ru", "sberbank.ru"],
    "custom_bypass_processes": ["steam.exe", "discord.exe"],
    "cdn_lte_socks_port": 10808
  }
  ```
- **Ответ**: `{"ok": true, "state": "connecting"}`

#### Команда `disconnect`
Остановка ядра туннеля, сброс DNS и удаление маршрутов.
- **Запрос**: `{"command": "disconnect"}`
- **Ответ**: `{"ok": true, "state": "idle"}`

#### Команды `probe`, `cancel-probe`, `probe-progress`
Управление фоновым измерением качества маршрутов:
- Запуск одновременного тестирования задержки, джиттера и HTTP-скорости без создания TUN-адаптера.
- Служба запускает временный loopback HTTP-proxy и возвращает потоковый прогресс в GUI.

---

## 2. Android: Жизненный цикл VpnService и изоляция процессов

### 2.1. Центральная служба `ConnectVpnService`
Является наследником системного класса `android.net.VpnService`:
- **Регистрация в AndroidManifest.xml**:
  ```xml
  <service
      android:name=".ConnectVpnService"
      android:exported="false"
      android:foregroundServiceType="systemExempted"
      android:permission="android.permission.BIND_VPN_SERVICE">
      <intent-filter>
          <action android:name="android.net.VpnService" />
      </intent-filter>
  </service>
  ```
- **Создание интерфейса**:
  - `Builder.setSession("deytt./connect")`
  - `Builder.setMtu(1360)` (с учетом заголовков инкапсуляции AWG/Reality)
  - `Builder.addAddress("172.19.0.1", 30)` и `Builder.addAddress("fdfe:dcba:9876::1", 126)`
  - `Builder.addRoute("0.0.0.0", 0)` и `Builder.addRoute("::", 0)`
  - `Builder.addDnsServer("172.19.0.2")`
  - `Builder.setBlocking(false)`
- **Взаимодействие с `libbox`**:
  Служба получает файловый дескриптор `ParcelFileDescriptor` от системного `Builder.establish()` и передает его в нативный рантайм sing-box через JNI.

### 2.2. Защита от убийства фоновой службы (Foreground Notification)
- Начиная с Android 8.0 (API 26), служба обязана публиковать несмахиваемое уведомление (`Notification.FLAG_ONGOING_EVENT`).
- На Android 14+ (API 34+) и Android 15 (API 35+) используется тип переднего плана `foregroundServiceType="systemExempted"` с каналом низкой важности (`NotificationManager.IMPORTANCE_LOW`), исключающим навязчивые звуковые сигналы при смене статуса.
- Уведомление содержит действие мгновенного отключения туннеля (`PendingIntent` к `ConnectVpnService` с action `space.deytt.connect.action.STOP`).

### 2.3. Изолированные процессы `:xray` и `:routeprobe`
- **Процесс `:xray` (`CdnLteXrayService`)**:
  - Изоляция предотвращает утечки памяти и сбои основного UI при работе ресурсоемкого Go-рантайма Xray Core.
  - Процесс запускается только при выборе маршрута `route:DE:LTE` и автоматически завершается при переключении на стандартный туннель.
- **Процесс `:routeprobe` (`RouteProbeVpnService`)**:
  - Выполняет фоновые замеры сетевой задержки, не вызывая дрожания кадров (jank/stutter) в основном интерфейсе приложения.
