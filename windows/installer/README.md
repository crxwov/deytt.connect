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

For the first installation, pass the SID of the interactive account that will
use the VPN. From that account's PowerShell session:

```powershell
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
msiexec.exe /i .\DEYTTConnect.Windows.Installer.msi "ALLOWEDUSERSID=$sid"
```

Subsequent major upgrades read and preserve the existing `AllowedUserSid`.
The MSI blocks installation over a service installed outside this MSI, so a
legacy service installation must first be removed with the matching existing
uninstaller. An explicit `ALLOWEDUSERSID` property can be supplied by an
administrator to intentionally change the allowed user.

This prototype has not been installed, upgraded, or removed on Windows 10. It
is not signed or ready for public distribution: trusted signing credentials
and native Windows 10 install/upgrade/uninstall QA are still required. Service
startup and the existing service ACL implementation remain part of acceptance.
