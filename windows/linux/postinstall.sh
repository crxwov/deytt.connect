#!/bin/sh
set -eu
systemd-sysusers /usr/lib/sysusers.d/deytt-connect.conf
if [ -d /run/systemd/system ]; then
    systemctl daemon-reload
    systemctl enable --now deytt-connect.socket
    systemctl try-restart deytt-connect.service
fi
