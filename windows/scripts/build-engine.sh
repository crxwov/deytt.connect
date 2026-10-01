#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
commit="16724484cfa00d9b2c14d8280dc055564e2e12ba"
source_dir="${AMNEZIA_BOX_SOURCE:-$repo_root/.toolchain/amnezia-box}"
output_dir="${1:-$repo_root/windows/artifacts/win-x64}"
engine_url="https://github.com/amnezia-vpn/amnezia-box.git"
build_tags="with_gvisor,with_quic,with_dhcp,with_wireguard,with_utls,with_acme,with_clash_api,with_tailscale,with_awg"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/deytt-amnezia-box.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

if ! command -v go >/dev/null 2>&1; then
  echo "Go is required to build the pinned Amnezia Box engine." >&2
  exit 1
fi

if [[ ! -d "$source_dir/.git" ]]; then
  if [[ -e "$source_dir" ]]; then
    echo "Source path exists but is not a Git checkout: $source_dir" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$source_dir")"
  git clone --filter=blob:none --no-checkout "$engine_url" "$source_dir"
fi

if ! git -C "$source_dir" cat-file -e "$commit^{commit}" 2>/dev/null; then
  git -C "$source_dir" fetch --quiet --depth 1 origin "$commit"
fi
git -C "$source_dir" checkout --quiet --detach "$commit"
if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$commit" ]]; then
  echo "The VPN engine checkout does not match the pinned source commit." >&2
  exit 1
fi
if [[ -n "$(git -C "$source_dir" status --porcelain)" ]]; then
  echo "The pinned VPN engine source tree has local modifications." >&2
  exit 1
fi

mkdir -p "$output_dir/source"
git -C "$source_dir" archive --format=tar "$commit" | tar -xf - -C "$work_dir"
(cd "$work_dir" && go mod vendor)
(cd "$work_dir" && GOOS=windows GOARCH=amd64 CGO_ENABLED=0 \
  go build -mod=vendor -trimpath -buildvcs=false -tags "$build_tags" \
  -ldflags '-s -w -buildid=' -o "$output_dir/DeyttVpnEngine.exe" ./cmd/sing-box)

tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -cf - -C "$work_dir" . \
  | gzip -n -9 > "$output_dir/source/vpn-engine-$commit-with-vendor.tar.gz"
cp "$source_dir/LICENSE" "$output_dir/source/VPN-ENGINE-LICENSE.txt"
cat > "$output_dir/source/VPN-ENGINE-SOURCE.txt" <<EOF
Component: third-party VPN engine source
Source project: Amnezia Box
Source commit: $commit
Source URL: https://github.com/amnezia-vpn/amnezia-box/tree/$commit
Build tags: $build_tags
Target: windows/amd64, CGO_ENABLED=0
Go toolchain: $(go version)
The source archive includes the vendored Go module sources used by this build.
DEYTT Connect is independently branded and is not endorsed by or affiliated with the upstream authors.
EOF
sha256sum "$output_dir/DeyttVpnEngine.exe" > "$output_dir/DeyttVpnEngine.sha256"
echo "Built $output_dir/DeyttVpnEngine.exe from $commit"
