# Third-party notices

## sing-box / libbox

The Android app links against the pinned `com.github.singbox-android:libbox:1.14.1`
artifact. It is based on sing-box and is distributed under GNU GPL v3.0-or-later.

- Upstream: <https://github.com/SagerNet/sing-box>
- Android library: <https://github.com/singbox-android/libbox>
- License: GNU GPL v3.0-or-later

The corresponding source and license notices must remain available to users of
any binary distributed from this repository. DEYTTT is an independent project
and is not affiliated with, endorsed by, or an official SagerNet application.

## AmneziaWG Android tunnel

The app vendors the official `amnezia-vpn/amneziawg-android` repository as a
pinned Git submodule and compiles its `tunnel` sources and native userspace
backend. The Android tunnel module is distributed under Apache License 2.0;
its nested native dependencies retain their own upstream notices.
The build keeps the upstream submodule unchanged and generates a small
DEYTTT-owned source overlay for the Android foreground-service lifecycle;
the original Apache-2.0 copyright and license header are retained in the
generated source.

- Upstream: <https://github.com/amnezia-vpn/amneziawg-android>
- Protocol backend: <https://github.com/amnezia-vpn/amneziawg-go>
- License: Apache License 2.0 (Android tunnel module)

DEYTTT is an independent project and is not an official Amnezia application.
