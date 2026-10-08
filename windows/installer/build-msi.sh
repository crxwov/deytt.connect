#!/usr/bin/env bash
set -euo pipefail

if [[ "${OS:-}" != "Windows_NT" ]]; then
  echo "Build the MSI on Windows (DTF custom actions require the Windows packaging tool)." >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dotnet_bin="${DOTNET:-$repo_root/.toolchain/dotnet/dotnet}"

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <product-version> <output.msi>" >&2
  exit 2
fi

product_version="$1"
output_msi="$2"
if [[ ! "$product_version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Product version must be numeric major.minor.patch (for example 1.2.3)." >&2
  exit 2
fi
IFS=. read -r version_major version_minor version_build <<< "$product_version"
if ((10#$version_major > 255 || 10#$version_minor > 255 || 10#$version_build > 65535)); then
  echo "MSI version fields must be at most 255.255.65535." >&2
  exit 2
fi
if [[ ! -x "$dotnet_bin" ]]; then
  echo "The .NET SDK executable was not found: $dotnet_bin" >&2
  exit 1
fi
if [[ -e "$output_msi" ]]; then
  echo "Refusing to overwrite an existing MSI: $output_msi" >&2
  exit 1
fi

temp_dir="$(mktemp -d "$repo_root/.toolchain/windows-msi.XXXXXX")"
trap 'rm -rf "$temp_dir"' EXIT
package_dir="$temp_dir/package"

"$repo_root/windows/scripts/build-windows-package.sh" "$package_dir"

# The existing elevated service installer copies the service publish output into
# the application directory. Merge it the same way, but reject divergent
# filename collisions instead of silently replacing app payload files.
service_dir="$package_dir/service"
if [[ ! -d "$service_dir" ]]; then
  echo "Windows package is missing its service payload directory." >&2
  exit 1
fi
while IFS= read -r -d '' service_file; do
  relative_path="${service_file#"$service_dir"/}"
  if [[ -e "$package_dir/$relative_path" ]]; then
    if [[ ! -f "$package_dir/$relative_path" ]] || ! cmp -s "$service_file" "$package_dir/$relative_path"; then
      echo "App and service publish outputs contain a conflicting file: $relative_path" >&2
      exit 1
    fi
  else
    cp -a "$service_file" "$package_dir/$relative_path"
  fi
done < <(find "$service_dir" -mindepth 1 -maxdepth 1 -type f -print0)
if find "$service_dir" -mindepth 1 -maxdepth 1 ! -type f -print -quit | rg -q .; then
  echo "Unexpected nested service payload; refusing an incomplete MSI harvest." >&2
  exit 1
fi
rm -rf "$service_dir"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$repo_root/windows/installer/Get-WebView2Bootstrapper.ps1")" -Destination "$(cygpath -w "$package_dir/Prerequisites/MicrosoftEdgeWebview2Setup.exe")"
(cd "$package_dir" && find . -type f ! -name SHA256SUMS.txt -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS.txt)

product_code="$(powershell.exe -NoProfile -File "$(cygpath -w "$repo_root/windows/installer/Get-ProductCode.ps1")" -ProductVersion "$product_version" | tr -d '\r')"
"$dotnet_bin" build "$repo_root/windows/installer/DEYTTConnect.Windows.Installer.wixproj" \
  --configuration Release \
  -p:PackageStageDir="$package_dir" \
  -p:ProductVersion="$product_version" \
  -p:SetupProductCode="$product_code" \
  -p:InstallerBuildRoot="$temp_dir/wix-build" \
  --nologo

mapfile -t built_msis < <(find "$temp_dir/wix-build/output" -type f -name '*.msi' -print)
if [[ ${#built_msis[@]} -ne 1 ]]; then
  echo "Expected exactly one MSI build output, found ${#built_msis[@]}." >&2
  exit 1
fi
mkdir -p "$(dirname "$output_msi")"
cp -- "${built_msis[0]}" "$output_msi"
echo "Unsigned MSI created at $output_msi"
