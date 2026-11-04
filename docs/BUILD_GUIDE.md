# Deytt Connect — Руководство по сборке и упаковке

Документ описывает требования к окружению, процедуру компиляции, подписи и верификации артефактов для **Android** и **Windows**.

---

## 1. Сборка Android-клиента

### 1.1. Системные требования и инструментарий
- **ОС**: Windows 10/11, macOS или Linux x64
- **Java Development Kit**: Eclipse Adoptium OpenJDK 17 (LTS)
- **Android SDK**: Compile SDK 35, Build-Tools 35.0.0, Platform API 35
- **Android NDK**: Версия `26.1.10909125` (зафиксирована в `app/build.gradle.kts`)
- **CMake**: `3.22.1`
- **Go Toolchain**: Go 1.25.14+ (для компиляции AmneziaWG Go backend)
- **Python**: 3.10+ (для скрипта подготовки подписи `scripts/release.py`)

### 1.2. Подготовка зависимостей и AAR
Перед первой сборкой убедитесь, что подмодули инициализированы и бинарные AAR присутствуют:

```bash
git submodule update --init --recursive
```

Если отсутствует AAR-библиотека `libXray`:
```bash
./scripts/fetch-libxray.sh android
```

### 1.3. Сборка отладочной версии (Debug APK)
```powershell
cd deytt.connect
.\gradlew assembleDebug
```
Собранный APK будет находиться по пути:
`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (или универсальный debug APK).

### 1.4. Сборка и подпись релизной версии (Universal Release APK)

> [!IMPORTANT]
> Согласно политике проекта, сборка релизного APK происходит строго в два этапа: компиляция неподписанного APK средствами Gradle и последующая верифицированная подпись каноническим ключом через утилиту `scripts/release.py`. Использование отладочного ключа для релизных файлов запрещено!

**Шаг 1: Компиляция неподписанного Release APK**
```powershell
.\gradlew :app:assembleRelease --no-daemon --console=plain
```
Артефакт будет сгенерирован в `app/build/outputs/apk/release/app-release-unsigned.apk`.

**Шаг 2: Подпись и выравнивание универсального пакета**
```powershell
python scripts/release.py sign `
  app/build/outputs/apk/release/app-release-unsigned.apk `
  --output output/release/app-universal-release.apk `
  --keystore "$env:USERPROFILE\Downloads\AyuGram Desktop\release.p12" `
  --password-file "$env:USERPROFILE\Downloads\AyuGram Desktop\release.password"
```

**Шаг 3: Верификация подписи и SHA-256**
Скрипт проверяет сертификат по отпечатку `bb39...` для всех целевых уровней API (24+, 28+, 36) и создает рядом контрольную сумму:
`output/release/app-universal-release.apk.sha256`

---

## 2. Сборка Windows-клиента

### 2.1. Системные требования и инструментарий
- **ОС**: Windows 10 (1809+) или Windows 11 x64
- **.NET SDK**: .NET 10 SDK (или локальный SDK `.tmp-dotnet-sdk-10.0.401`)
- **Go Toolchain**: Go 1.24+ для компиляции `DeyttVpnEngine.exe`
- **WiX Toolset**: WiX v3.14 или v4 для генерации MSI-пакетов
- **PowerShell**: PowerShell 7+ (`pwsh`) или Windows PowerShell 5.1

### 2.2. Компиляция нативных ядер (sing-box и xray)
Если требуется пересобрать движки:
```powershell
# Компиляция кастомного sing-box ядра
cd windows/scripts
./build-engine.sh
```

### 2.3. Сборка приложения и системной службы (Self-Contained)
Компиляция Avalonia GUI и службы в режиме единого бинарного каталога без внешних зависимостей от системного рантайма .NET:

```powershell
$DOTNET = ".\.tmp-dotnet-sdk-10.0.401\dotnet.exe"

# 1. Сборка системной службы
& $DOTNET publish windows/DeyttConnect.Windows.Service/DeyttConnect.Windows.Service.csproj `
    -c Release -r win-x64 --self-contained -o windows/artifacts/service

# 2. Сборка десктопного GUI
& $DOTNET publish windows/DeyttConnect.Windows/DeyttConnect.Windows.csproj `
    -c Release -r win-x64 --self-contained -o windows/artifacts/app
```

### 2.4. Сборка MSI-инсталлятора
Сборка инсталлятора выполняется вызовом PowerShell-скрипта:

> [!CAUTION]
> Никогда не передавайте `Build-Msi.ps1` в команду `dotnet`! Скрипт вызывается напрямую из оболочки PowerShell.

```powershell
powershell -ExecutionPolicy Bypass -File windows/installer/Build-Msi.ps1 `
    -Version "0.11.112" `
    -ArtifactsDir "windows/artifacts" `
    -OutputDir "windows/release"
```

### 2.5. Сборка портативного архива (Portable ZIP)
```powershell
powershell -ExecutionPolicy Bypass -File windows/scripts/package-windows-portable.ps1 `
    -Version "0.11.112" `
    -OutputDir "windows/release"
```
Результатом сборки будут файлы:
- `windows/release/deytt-connect-windows-x64.msi` + `.sha256`
- `windows/release/deytt-connect-windows-x64.zip` + `.sha256`
