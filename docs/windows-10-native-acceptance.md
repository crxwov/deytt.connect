# Windows 10 native acceptance handoff

Status: **not executed**. This is a user-run checklist for the later Windows 10 x64 session. No MSI install/upgrade/uninstall, live login, payment, WebView2, native tunnel, sleep-recovery, or egress acceptance has been performed. The MSI signing certificate is not available, so there is no signed release candidate yet. Do not treat an unsigned prototype or synthetic QA fixture as release acceptance.

## Entry conditions

- Windows 10 x64 QA machine with a restore point or disposable QA profile, administrator access, current WebView2 Runtime, and network access.
- A trusted, signed MSI built from the reviewed commit, with its SHA-256 recorded. Confirm its version and signature before launch; stop if Windows reports an untrusted or invalid signature.
- A QA account and subscription that expose the full route catalog: NL, RU, DE, and FI with VLESS, Trojan, and Hysteria 2, plus each available AmneziaWG 3.1 profile. Do not record the Telegram code, session, token, subscription URL, or account identity.
- Save evidence in a private local directory. Redact usernames, email/phone, SID, public IP, profile links, codes, tokens, and payment identifiers before sharing. Record only pass/fail, app/MSI version, Windows build, route label/protocol, HTTP status, and whether egress hashes matched.

## 1. First install, UAC, and service

1. Sign in as the intended interactive QA account. Open PowerShell as administrator for that same account and capture its SID in that elevated session:

   ```powershell
   $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
   ```

2. In that elevated session, install the signed MSI. Substitute its local path; keep the log private because Windows Installer logs can contain machine/user details.

   ```powershell
   msiexec.exe /i "C:\QA\DEYTTConnect.Windows.Installer.msi" "ALLOWEDUSERSID=$sid" /L*v "$env:TEMP\deytt-install.log"
   ```

3. Confirm the expected UAC prompt appears, installation completes, the Start Menu entry opens the app without an elevation prompt, and Add/Remove Programs shows the installed version.
4. In `services.msc`, confirm `DEYTTConnectVpn` exists, is configured for Automatic start, and reaches Running. Check that the service executable is under the DEYTT install directory. Verify access from the intended standard user; do not copy the SID or service logs into shared evidence.
5. In the app, confirm the embedded route atlas loads in WebView2 (not a fallback/error state). Drag, zoom, select a route, resize the window, and confirm the view recovers after navigation away and back.

## 2. Upgrade and uninstall

1. Record app version, sign-in state, and selected route as non-identifying notes. Install a newer signed MSI over the existing version from an elevated session.
2. Confirm upgrade succeeds without downgrade, preserves the configured interactive-user access and app preferences, leaves one service registration, and the app and service start normally. A previously selected route may legitimately be unavailable after refresh; record the recovery behavior.
3. Uninstall through Windows Settings → Apps. Confirm the service stops and is removed, the app and Start Menu shortcut are removed, and Add/Remove Programs no longer lists it. Confirm the installer log reports success. Do not delete user data manually during this check.

## 3. Login and subscription import

1. Reinstall the candidate MSI for the QA account, launch as the standard user, and complete Telegram sign-in with the QA account. Never capture or paste the one-time code into evidence.
2. Wait for subscription import to finish. Confirm the route catalog contains NL/RU/DE/FI and VLESS/Trojan/Hysteria 2 entries, plus the AWG profiles available to this account. Verify each route label and protocol; do not export or share profile data.
3. Confirm a failed or interrupted import offers a clear retry and does not claim the account is ready before routes load.

## 4. Real tunnel and HTTPS egress matrix

Run the following for every listed route. Start disconnected, connect one route, wait for the UI to report Connected, open an HTTPS page in the system browser, run the canary below, then disconnect and confirm the UI returns to disconnected. Do not count a green UI state alone as tunnel proof.

| Region | Required transports |
| --- | --- |
| NL | VLESS, Trojan, Hysteria 2 |
| RU | VLESS, Trojan, Hysteria 2 |
| DE | VLESS, Trojan, Hysteria 2 |
| FI | VLESS, Trojan, Hysteria 2 |
| Each available AWG profile | AmneziaWG 3.1 |

Before connecting, run this PowerShell canary once while disconnected and once while each route is connected. It prints the HTTPS status and a one-way SHA-256 digest of the public egress IP; it never prints the IP itself. Compare direct and connected digests locally and record only “same/different” plus HTTPS status. A successful request with a different digest supports tunnel egress; if the digest stays the same, times out, or the browser fails, mark that route failed and retain only redacted diagnostics.

```powershell
$response = Invoke-WebRequest -UseBasicParsing -Uri 'https://api.ipify.org' -TimeoutSec 15
$ip = $response.Content.Trim()
$sha = [Security.Cryptography.SHA256]::Create()
$digest = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($ip))).Replace('-', '').ToLowerInvariant()
$sha.Dispose()
"HTTPS=$([int]$response.StatusCode) EGRESS_SHA256=$digest"
```

Also confirm unrelated ordinary browsing remains usable. Do not record the IP, copy it to a ticket, or use a payment action as a connectivity check.

## 5. Sleep and recovery

For one route of each transport family (VLESS, Trojan, Hysteria 2, and AWG 3.1), establish the tunnel and complete the HTTPS canary, put Windows to Sleep for at least one minute, then wake it. Confirm the app/service return to a truthful state and HTTPS succeeds again through the tunnel. If recovery fails, record the visible state and sanitized timestamp, disconnect/reconnect once, and record whether recovery then succeeds. Do not call a manual reconnect automatic recovery.

## 6. Payment flow without a charge

Use only a QA account. Open a plan, request its current quote, and advance only as far as the payment provider's hosted checkout or the last pre-payment confirmation screen. Do not enter payment details, approve a bank/app prompt, scan a payment QR, or confirm payment. Close/cancel checkout, return to the app, and confirm no paid entitlement or successful payment is shown. If the only available path could create a charge or the cancellation boundary is unclear, stop before opening it and mark this section not run. Do not use production payment credentials or create a real transaction.

## 7. Window sizes and accessibility

At 720×520, 1240×820, and 1800×1000, visit Home, Routes, Profile, and Settings. Confirm primary actions remain visible/reachable, content does not overlap or clip, and resizing preserves the selected page and route. At each size, use Tab/Shift+Tab and Enter/Space through navigation, route selection, connect/disconnect, dialogs, and retry; confirm visible focus and logical order. Check 150% Windows text scaling and high-contrast mode for readable text and usable controls. Restore the original display settings after the check.

## 8. Evidence and disposition

- Keep original MSI, checksum, raw installer logs, screenshots, and crash dumps private. Share only redacted copies after reviewing them manually.
- For each matrix row record: route label/protocol, connect/disconnect result, HTTPS status, direct-vs-tunnel digest comparison, browser result, sleep recovery where applicable, and sanitized failure detail.
- Record Windows build, app/MSI version, signature result, WebView2 Runtime version, and test date. Never include account identifiers, SID, public IP, codes, tokens, profile/subscription URLs, or payment references.
- Acceptance remains open until the signed package lifecycle, login/import, every route, HTTPS egress, sleep recovery, payment cancellation boundary, and accessibility/window-size checks have actual Windows evidence and any failures are resolved.
