#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
artifact_dir="${1:-$repo_root/windows/artifacts/portable-win-x64}"
zip_path="${2:-}"
temp_dir="$(mktemp -d "$repo_root/.toolchain/windows-portable.XXXXXX")"
trap 'rm -rf "$temp_dir"' EXIT
flat_dir="$temp_dir/flat"
stage_dir="$temp_dir/portable"

if [[ -e "$artifact_dir" || ( -n "$zip_path" && -e "$zip_path" ) ]]; then
  echo "Refusing to overwrite an existing portable package or ZIP." >&2
  exit 1
fi
if ! command -v go >/dev/null 2>&1; then
  echo "Go is required to build the portable launcher." >&2
  exit 1
fi
if [[ -n "$zip_path" ]] && ! command -v zip >/dev/null 2>&1; then
  echo "zip is required to create the portable archive." >&2
  exit 1
fi

"$repo_root/windows/scripts/build-windows-package.sh" "$flat_dir"
mkdir -p "$stage_dir/app" "$stage_dir/vpn" "$stage_dir/docs"
mv "$flat_dir/service" "$stage_dir/service"
mv "$flat_dir/DeyttVpnEngine.exe" "$flat_dir/DeyttVpnEngine.sha256" "$stage_dir/vpn/"
(cd "$stage_dir/vpn" && sha256sum DeyttVpnEngine.exe > DeyttVpnEngine.sha256)
mv "$flat_dir/source" "$stage_dir/docs/source"
mv "$flat_dir/WINDOWS-README.txt" "$stage_dir/docs/BUILD-README.txt"
mv "$flat_dir/DEYTT-LICENSE.txt" "$flat_dir/THIRD-PARTY-NOTICES.md" "$stage_dir/docs/"
rm "$flat_dir/SHA256SUMS.txt"
find "$flat_dir" "$stage_dir/service" -type f -name '*.pdb' -delete
shopt -s dotglob nullglob
mv "$flat_dir"/* "$stage_dir/app/"
shopt -u dotglob nullglob

GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -buildvcs=false \
  -ldflags '-s -w -H windowsgui -buildid=' \
  -o "$stage_dir/deyttconnect.exe" "$repo_root/windows/launcher/main_windows.go"

cat > "$stage_dir/docs/README.txt" <<'EOF'
DEYTT Connect for Windows 10 x64 — portable package

Open deyttconnect.exe in the package root. Keep the app/, service/, vpn/, and
docs/ folders beside it; moving only the launcher will not start the app.
The app runs without administrator rights. Installing the VPN service from
Settings requests Windows administrator approval. Microsoft Edge WebView2
Runtime is required for the interactive route map.

The VPN engine source and license are in docs/source/. See
docs/THIRD-PARTY-NOTICES.md for attribution. This portable package is unsigned.
EOF

(cd "$stage_dir" && find . -type f ! -path './docs/SHA256SUMS.txt' -print0 \
  | sort -z | xargs -0 sha256sum > docs/SHA256SUMS.txt)
mkdir -p "$(dirname "$artifact_dir")"
mv "$stage_dir" "$artifact_dir"
echo "Portable Windows package created at $artifact_dir"

if [[ -n "$zip_path" ]]; then
  mkdir -p "$(dirname "$zip_path")"
  zip_path="$(realpath -m "$zip_path")"
  (cd "$artifact_dir" && zip -q -r "$zip_path" .)
  (cd "$(dirname "$zip_path")" && sha256sum "$(basename "$zip_path")" > "$(basename "$zip_path").sha256")
  echo "Portable Windows ZIP created at $zip_path"
fi
