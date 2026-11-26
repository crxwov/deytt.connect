@echo off
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0app\Uninstall-VpnService.ps1" -RemoveAppData
if errorlevel 1 (
  echo Removal did not complete. Review the message above.
  pause
  exit /b 1
)
echo Service and local app data removed. You may delete this portable folder.
pause
