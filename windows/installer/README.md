# deytt./connect for Windows

The x64 MSI installs the complete self-contained Windows app, AmneziaWG VPN
engine, named-pipe service, offline route-map assets, and Start Menu shortcut.
The application needs Windows 10 version 1809 or newer. The MSI runs elevated
once to install and maintain its Windows VPN service.

If the shared Microsoft Edge WebView2 Runtime is missing, the MSI installs it
from Microsoft's signed Evergreen bootstrapper. An internet connection is
needed for that first WebView2 download. The embedded app and VPN engine do not
need a separate .NET or Go install.

The Russian wizard repairs or removes an existing installation and recovers
the known DEYTTConnectVpn service left by older Connect installers. It checks
the service executable before replacing it, preserves the allowed Windows
account across upgrades, and rejects a service belonging to another program.
The completion screen offers independent desktop-shortcut and launch checkboxes.
Launch occurs only when selected, through the signed-in Windows shell. It never starts with
the elevated installation token. Uninstall stops and removes the managed VPN
service. MSI-owned files live in Program Files; account data under
`%LOCALAPPDATA%` is retained for later reinstall.

Windows release artifacts are currently unsigned by the publisher, so Windows may show a publisher warning. Download installers from the official project release and compare the published SHA-256 file. The Microsoft WebView2 bootstrapper is signature-checked during the Windows build workflow.

## Build and verify

Use Windows 10 version 1809 or newer, the .NET 10 SDK, and an already-built,
self-contained package directory. The package must contain the x64 app and
service, VPN engine, and `Assets/route-map/index.html`.

```powershell
.\Build-Msi.ps1 `
  -ProductVersion 0.8.40 `
  -PackageStageDir C:\path\to\windows-x64-package `
  -BuildRoot D:\build\deytt-connect
```

The build downloads the official Microsoft WebView2 bootstrapper, checks its
Authenticode signer, runs WiX ICE validation, executes read-only MSI metadata
and service-classification checks, and prints the SHA-256 of the MSI.
`Verify-InstallerRecovery.ps1` and the native lifecycle test harness cover
service recovery, first install, repair, upgrade, downgrade rejection,
uninstall, and fresh reinstall. No test command silently installs the MSI.

ProductCode is stable per numeric release version. Reopening the same version
enters maintenance and offers removal; older installed releases enter the update
flow. Payload changes must increment the release version. Never reuse a version
for a different public release. Get-ProductCode.ps1 is the identity implementation
used by both PowerShell and Bash packaging. MajorUpgrade preserves the existing
UpgradeCode and blocks downgrades.

All six custom wizard dialogs share a 224-dialog-unit navy-to-teal gradient panel and
the single-line `./connect` wordmark. The panel contains no globe or decorative image.
Text, buttons, checkboxes, and progress indicators use native MSI controls.
