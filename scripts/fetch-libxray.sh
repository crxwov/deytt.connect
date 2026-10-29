#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="v26.10.21"
tool_dir="$repo_root/.toolchain/libxray-$version"
mode="${1:-all}"
mkdir -p "$tool_dir"
temp_dir="$(mktemp -d "$tool_dir/.fetch.XXXXXX")"
trap 'rm -rf "$temp_dir"' EXIT

fetch_archive() {
  local name="$1" sha="$2" archive="$tool_dir/$1"
  if [[ -f "$archive" ]]; then
    printf '%s  %s\n' "$sha" "$archive" | sha256sum -c --status || {
      echo "Pinned libXray archive has an unexpected hash: $archive" >&2
      exit 1
    }
    return
  fi
  curl --fail --location --retry 2 --silent --show-error \
    "https://github.com/XTLS/libXray/releases/download/$version/$name" \
    -o "$temp_dir/$name"
  printf '%s  %s\n' "$sha" "$temp_dir/$name" | sha256sum -c --status || {
    echo "Downloaded libXray archive failed SHA-256 verification: $name" >&2
    exit 1
  }
  mv "$temp_dir/$name" "$archive"
}

extract_file() {
  local archive="$1" member="$2" destination="$3"
  local extracted="$temp_dir/$(basename "$destination")"
  mkdir -p "$(dirname "$destination")"
  unzip -p "$archive" "$member" > "$extracted"
  [[ -s "$extracted" ]] || { echo "Missing release asset: $member" >&2; exit 1; }
  if [[ -e "$destination" ]]; then
    cmp -s "$extracted" "$destination" || {
      echo "Refusing to overwrite a different local Xray artifact: $destination" >&2
      exit 1
    }
  else
    mv "$extracted" "$destination"
  fi
}

case "$mode" in
  all|android|windows) ;;
  *) echo "Usage: $0 [all|android|windows]" >&2; exit 2 ;;
esac

if [[ "$mode" == all || "$mode" == android ]]; then
  android_zip="libxray-android.zip"
  fetch_archive "$android_zip" "ddefb68bb784ec291f977f85200c1a89b0c0a3847412d14b0d0b84b864b0c7e7"
  extract_file "$tool_dir/$android_zip" "libxray-android/libXray.aar" \
    "$tool_dir/android/libxray-android/libXray.aar"
  python -c "
import zipfile, io, os
aar = '$tool_dir/android/libxray-android/libXray.aar'
tmp = aar + '.tmp'
with zipfile.ZipFile(aar, 'r') as zin, zipfile.ZipFile(tmp, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == 'classes.jar':
            with zin.open(item) as cjin, zipfile.ZipFile(io.BytesIO(cjin.read()), 'r') as jin:
                buf = io.BytesIO()
                with zipfile.ZipFile(buf, 'w', compression=zipfile.ZIP_DEFLATED) as jout:
                    for jitem in jin.infolist():
                        if not jitem.filename.startswith('go/'):
                            jout.writestr(jitem, jin.read(jitem))
                zout.writestr(item, buf.getvalue())
        else:
            zout.writestr(item, zin.read(item))
os.replace(tmp, aar)
"
fi

if [[ "$mode" == all || "$mode" == windows ]]; then
  windows_zip="libxray-windows-x64.zip"
  fetch_archive "$windows_zip" "b1a4cf0b33eb74083dec39d6d10f3c78d8929dc10b37e705044bfd4f590831d9"
  extract_file "$tool_dir/$windows_zip" "libxray-windows-x64/xray.exe" \
    "$tool_dir/windows/xray.exe"
fi

echo "libXray $version artifacts are ready for: $mode"
