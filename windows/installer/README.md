# Windows MSI prototype

This is an unsigned x64 MSI slice. It packages the existing self-contained UI,
Windows VPN service, and engine; uses Windows Installer service actions for
install, stop, start, and removal; and stores the allowed interactive user SID
in the registry value consumed by the service's named-pipe ACL. Per-user
application data remains under `%LOCALAPPDATA%` and is outside the MSI install
tree. The UI executable is an explicit MSI component and receives a Start Menu
shortcut; Windows Installer provides the standard Add/Remove Programs entry.

Build on a machine with the .NET 10 SDK, Go, the pinned Amnezia Box source build
dependencies, and network access for NuGet restore:

```bash
windows/installer/build-msi.sh 1.2.3 /path/to/DEYTTConnect-Windows-x64.msi
```

Double-click the MSI from the interactive Windows account that will use the VPN.
The installer defaults the named-pipe owner to Windows Installer's UserSID;
no SID entry is required. Major upgrades preserve the existing AllowedUserSid.
Administrators can explicitly supply ALLOWEDUSERSID when installing for another
account.

A leftover service pointing to the standard DEYTT\Connect service executable is
stopped and recreated through Windows Installer service actions. A service at
an unexpected path is rejected. Uninstall removes the managed service; per-user
account and installation identity data remains in LOCALAPPDATA so reinstalling
on the same device retains its identity.

This prototype has not been installed, upgraded, or removed on Windows 10. It
is not signed or ready for public distribution: trusted signing credentials
and native Windows 10 install/upgrade/uninstall QA are still required. Service
startup and the existing service ACL implementation remain part of acceptance.
