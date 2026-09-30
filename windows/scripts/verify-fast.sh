#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
PROTOCOL_TESTS="$ROOT_DIR/windows/DeyttConnect.Protocol.Tests/DeyttConnect.Protocol.Tests.csproj"
WINDOWS_TESTS="$ROOT_DIR/windows/DeyttConnect.Windows.Tests/DeyttConnect.Windows.Tests.csproj"
WINDOWS_HEADLESS_TESTS="$ROOT_DIR/windows/DeyttConnect.Windows.HeadlessTests/DeyttConnect.Windows.HeadlessTests.csproj"
WINDOWS_APP="$ROOT_DIR/windows/DeyttConnect.Windows/DeyttConnect.Windows.csproj"
WINDOWS_SERVICE="$ROOT_DIR/windows/DeyttConnect.Windows.Service/DeyttConnect.Windows.Service.csproj"

if [[ -n "${DOTNET:-}" ]]; then
  DOTNET_BIN="$DOTNET"
elif [[ -x "$ROOT_DIR/.toolchain/dotnet/dotnet" ]]; then
  DOTNET_BIN="$ROOT_DIR/.toolchain/dotnet/dotnet"
elif command -v dotnet >/dev/null 2>&1; then
  DOTNET_BIN="$(command -v dotnet)"
else
  echo "error: .NET SDK 10 was not found (set DOTNET or install dotnet)." >&2
  exit 127
fi

phase() {
  local label="$1"
  shift
  printf '\n==> %s\n' "$label"
  "$DOTNET_BIN" "$@"
  printf '    PASS: %s\n' "$label"
}

phase "Restore Windows app test graph" restore "$WINDOWS_TESTS" --nologo
phase "Restore Windows headless UI test graph" restore "$WINDOWS_HEADLESS_TESTS" --nologo
phase "Restore Windows service" restore "$WINDOWS_SERVICE" --nologo
phase "Protocol tests" test "$PROTOCOL_TESTS" --no-restore --configuration Release --nologo
phase "Windows app tests" test "$WINDOWS_TESTS" --no-restore --configuration Release --nologo
phase "Windows headless UI tests" test "$WINDOWS_HEADLESS_TESTS" --no-restore --configuration Release --nologo
phase "Windows app Release build" build "$WINDOWS_APP" --no-restore --configuration Release --nologo
phase "Windows service Release build" build "$WINDOWS_SERVICE" --no-restore --configuration Release --nologo

printf '\nFast Windows client verification passed.\n'
