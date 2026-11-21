#!/bin/sh
set -eu
case "${1:-}" in
    upgrade|failed-upgrade) exit 0 ;;
esac
if [ "${1:-0}" -gt 0 ] 2>/dev/null; then
    exit 0
fi
if [ -d /run/systemd/system ]; then
    systemctl stop deytt-connect.service deytt-connect.socket
    systemctl disable deytt-connect.socket
fi
