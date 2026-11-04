# Deytt Connect — Конвейер обновлений и зеркало релизов

Документ описывает архитектуру распространения обновлений **deytt./connect**, двухуровневую модель доставки (Server Mirror + GitHub fallback), серверную оптимизацию Nginx и клиентский параллельный загрузчик.

---

## 1. Двухуровневая модель доставки обновлений

Для гарантированной доступности обновлений в условиях блокировок, DPI-фильтрации и ограничений GitHub API используется гибридный конвейер доставки:

```mermaid
flowchart TD
    Build["Сборка релиза (v0.11.112)"] --> Sign["Подпись релизных пакетов (bb39...)"]
    Sign --> GH["GitHub Release: connect./0.11.112"]

    GH --> Sync["Серверный синхронизатор (sync_connect_releases.py)"]
    Sync --> Mirror["Серверное зеркало: /var/www/html/connect/releases/"]
    Mirror --> Meta["update.json (хэши, размеры, ссылки)"]

    subgraph ClientDownloader["Клиентский загрузчик (Android / Windows)"]
        ReqMeta["Запрос метаданных"]
        Download["Параллельная загрузка (4 HTTP Range потока)"]
        Verify["Проверка SHA-256 и сигнатуры"]
        Install["Установка пакета"]
    end

    ReqMeta -->|1. Приоритет: Прямой сервер| Meta
    ReqMeta -.->|2. Fallback: GitHub API| GH

    Download -->|1. Приоритет: deytt.space (directio)| Mirror
    Download -.->|2. Fallback: GitHub Releases| GH

    Verify --> Download
    Install --> Verify
```

---

## 2. Серверное зеркало и манифест `update.json`

### 2.1. Структура каталога на сервере
На центральном сервере `deytt.space` релизные файлы размещаются в каталоге:
`/var/www/html/connect/releases/`

Содержимое каталога:
- `update.json` — основной мета-файл версий и хэшей
- `app-universal-release.apk` — универсальный Android APK для всех поддерживаемых архитектур
- `app-universal-release.apk.sha256` — контрольная сумма
- `deytt-connect-windows-x64.msi` — MSI-инсталлятор для Windows
- `deytt-connect-windows-x64.zip` — портативный архив для Windows

### 2.2. Спецификация `update.json`
```json
{
  "version": "0.11.112",
  "version_code": 142,
  "release_tag": "connect./0.11.112",
  "published_at": "2026-11-04T12:00:00Z",
  "changelog": "### что изменилось:\n- Ускорение скачивания обновлений параллельными потоками\n- Оптимизация сетевых буферов",
  "android": {
    "url": "https://deytt.space/connect/releases/app-universal-release.apk",
    "fallback_url": "https://github.com/crxwov/deytt.connect/releases/download/connect.%2F0.11.112/app-universal-release.apk",
    "sha256": "abcdef0123456789...",
    "size_bytes": 155189248
  },
  "windows": {
    "msi_url": "https://deytt.space/connect/releases/deytt-connect-windows-x64.msi",
    "msi_sha256": "1234567890abcdef...",
    "portable_url": "https://deytt.space/connect/releases/deytt-connect-windows-x64.zip",
    "portable_sha256": "fedcba0987654321..."
  }
}
```

### 2.3. Автоматическая синхронизация (`sync_connect_releases.py`)
Серверный демон или крон-задача выполняет скрипт `sync_connect_releases.py`:
- Опрашивает GitHub API на предмет новых тегов `connect./<version>`.
- Скачивает бинарные ассеты во временный каталог.
- Вычисляет точный SHA-256 и сверяет с сигнатурой.
- Атомарно перемещает файлы в `/var/www/html/connect/releases/`.
- Перегенерирует `update.json` с сохранением полей для Windows и Android.

---

## 3. Серверная оптимизация Nginx для отдачи тяжелых бинарных файлов

Для исключения перегрузки оперативной памяти и задержек при одновременном скачивании релизов сотнями пользователей хост `nl-site.conf` сконфигурирован по стандарту Direct I/O:

```nginx
location /connect/releases/ {
    alias /var/www/html/connect/releases/;
    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;

    # Исключение сжатия для готовых сжатых бинарников (APK, MSI, ZIP)
    gzip off;

    # Direct I/O для файлов больше 4 МБ (минует page cache ядра Linux)
    directio 4m;
    directio_alignment 512;
    output_buffers 4 512k;
    sendfile_max_chunk 2m;

    # Заголовки кеширования
    location ~* \.json$ {
        expires -1;
        add_header Cache-Control "no-store, no-cache, must-revalidate";
    }
    location ~* \.(apk|msi|zip)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }
}
```

---

## 4. Клиентский параллельный многопоточный загрузчик

На мобильных сетях с высокой задержкой до европейских дата-центров (RTT 80–150 мс) один TCP-поток не успевает разогнать окно перегрузки (congestion window) для быстрой скачки файла 150 МБ.

В Android-клиенте (`UpdateChecker.kt`) и Windows (`WindowsUpdateClient.cs`) реализован 4-поточный сегментированный загрузчик:

```
Файл 150 МБ (0 .. 157286399)
Поток 0: Range: bytes=0-39321599          -> .part0 (в памяти / tmp)
Поток 1: Range: bytes=39321600-78643199   -> .part1
Поток 2: Range: bytes=78643200-117964799  -> .part2
Поток 3: Range: bytes=117964800-157286399 -> .part3
```

- **Параллельные HTTP Range запросы**: Выполняется `HEAD` запрос для получения `Content-Length` и проверки заголовка `Accept-Ranges: bytes`.
- **Сегментация**: Файл делится на 4 равных диапазона, каждый из которых загружается отдельным воркером в пуле потоков.
- **Прогресс**: Потоки атомарно обновляют глобальный счетчик скачанных байт для плавного отображения прогресс-бара в UI.
- **Атомарная сборка**: По завершении всех 4 сегментов файл объединяется через NIO-каналы в целевой `.apk`, после чего рассчитывается контрольная сумма SHA-256.
- **Отказоустойчивость**: Если сервер возвращает `HTTP 200 OK` вместо `HTTP 206 Partial Content` (например, при устаревшем прокси), загрузчик прозрачно переключается в однопоточный режим.
