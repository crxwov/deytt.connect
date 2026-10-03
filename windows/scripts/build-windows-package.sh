#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dotnet_bin="${DOTNET:-$repo_root/.toolchain/dotnet/dotnet}"
artifact_dir="${1:-$repo_root/windows/artifacts/win-x64}"
engine_source="${AMNEZIA_BOX_SOURCE:-$repo_root/.toolchain/amnezia-box}"
temp_dir="$(mktemp -d "$repo_root/.toolchain/windows-package.XXXXXX")"
stage_dir="$temp_dir/package"
trap 'rm -rf "$temp_dir"' EXIT

if [[ ! -x "$dotnet_bin" ]]; then
  echo "The .NET SDK executable was not found: $dotnet_bin" >&2
  exit 1
fi
if [[ -e "$artifact_dir" ]]; then
  echo "Refusing to overwrite an existing package directory: $artifact_dir" >&2
  exit 1
fi
mkdir -p "$stage_dir"
mkdir -p "$temp_dir/app" "$temp_dir/service"

"$dotnet_bin" publish "$repo_root/windows/DeyttConnect.Windows/DeyttConnect.Windows.csproj" \
  -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false -p:NuGetAudit=false \
  -o "$temp_dir/app" --nologo
"$dotnet_bin" publish "$repo_root/windows/DeyttConnect.Windows.Service/DeyttConnect.Windows.Service.csproj" \
  -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false -p:NuGetAudit=false \
  -o "$temp_dir/service" --nologo

atlas_source="$repo_root/app/src/main/assets/route-map"
atlas_output="$temp_dir/app/Assets/route-map"
for asset in atlas-init.js index.html network-atlas.css network-atlas.js; do
  if [[ ! -s "$atlas_output/$asset" ]] || ! cmp -s "$atlas_source/$asset" "$atlas_output/$asset"; then
    echo "The published DEYTT atlas asset is missing or differs from Android: $asset" >&2
    exit 1
  fi
done
if ! cmp -s "$repo_root/app/src/main/assets/world-land.json" "$atlas_output/world-land.json"; then
  echo "The published DEYTT atlas topology differs from Android." >&2
  exit 1
fi

AMNEZIA_BOX_SOURCE="$engine_source" "$repo_root/windows/scripts/build-engine.sh" "$stage_dir"
cp -a "$temp_dir/app/." "$stage_dir/"
mkdir -p "$stage_dir/service"
cp -a "$temp_dir/service/." "$stage_dir/service/"
cp "$repo_root/windows/scripts/Install-VpnService.ps1" "$stage_dir/Install-VpnService.ps1"
cp "$repo_root/windows/scripts/Uninstall-VpnService.ps1" "$stage_dir/Uninstall-VpnService.ps1"

cat > "$stage_dir/WINDOWS-README.txt" <<'EOF'
DEYTT Connect for Windows x64

Run DeyttConnect.Windows.exe. The first VPN connection requires installing the
privileged tunnel service from Settings; Windows will show an administrator
approval prompt. The desktop application itself runs without elevation.

The interactive map uses the same bundled offline DEYTT atlas as Android and
requires Microsoft Edge WebView2 Runtime. Map geography is bundled; map tiles
are not downloaded.

The VPN engine source archive and its license are in source/. See
THIRD-PARTY-NOTICES.md for component and attribution details.
EOF

if [[ -f "$repo_root/LICENSE" ]]; then
  cp "$repo_root/LICENSE" "$stage_dir/DEYTT-LICENSE.txt"
fi
if [[ -f "$repo_root/windows/THIRD-PARTY-NOTICES.md" ]]; then
  cp "$repo_root/windows/THIRD-PARTY-NOTICES.md" "$stage_dir/THIRD-PARTY-NOTICES.md"
fi
(cd "$stage_dir" && find . -type f ! -name SHA256SUMS.txt -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS.txt)
if [[ -e "$artifact_dir" ]]; then
  echo "Refusing to overwrite an existing package directory: $artifact_dir" >&2
  exit 1
fi
mkdir -p "$(dirname "$artifact_dir")"
mv "$stage_dir" "$artifact_dir"
echo "Windows package created at $artifact_dir"
