#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
signed_in="false"
state="disconnected"
tab="home"
output="/tmp/deytt-connect-qa.png"

while (($#)); do
    case "$1" in
        --signed-in) signed_in="true"; shift ;;
        --state) state="${2:?--state requires a value}"; shift 2 ;;
        --tab) tab="${2:?--tab requires a value}"; shift 2 ;;
        --output) output="${2:?--output requires a path}"; shift 2 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done

for command in xvfb-run xdotool import; do
    command -v "$command" >/dev/null || { printf 'Required command not found: %s\n' "$command" >&2; exit 1; }
done

mkdir -p "$(dirname "$output")"
xvfb-run -a --server-args='-screen 0 1600x1100x24' bash -c '
    set -euo pipefail
    root="$1"; out="$2"; signed="$3"; state="$4"; tab="$5"
    qa_args=(--state "$state" --tab "$tab")
    [[ "$signed" == true ]] && qa_args+=(--signed-in)
    "$root/.toolchain/dotnet/dotnet" run --no-build --project "$root/windows/DeyttConnect.Windows.QA/DeyttConnect.Windows.QA.csproj" -- "${qa_args[@]}" &
    app_pid=$!
    trap "kill $app_pid 2>/dev/null || true" EXIT
    window_id=""
    for _ in $(seq 1 80); do
        window_id="$(xdotool search --onlyvisible --name "QA fixture" 2>/dev/null | head -n1 || true)"
        [[ -n "$window_id" ]] && break
        sleep 0.25
    done
    if [[ -z "$window_id" ]]; then
        wait "$app_pid"
        printf "QA window did not open; inspect Avalonia output above.\n" >&2
        exit 1
    fi
    sleep 2
    import -window "$window_id" "$out"
    file "$out"
' _ "$repo_root" "$output" "$signed_in" "$state" "$tab"
