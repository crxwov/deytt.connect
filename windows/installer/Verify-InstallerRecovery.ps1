param([Parameter(Mandatory=$true)][string]$MsiPath)
$ErrorActionPreference='Stop'
$installer=New-Object -ComObject WindowsInstaller.Installer
$database=$installer.OpenDatabase((Resolve-Path -LiteralPath $MsiPath).Path,0)
function Row([string]$Query) {
 $view=$database.OpenView($Query); $null=$view.Execute(); $record=$view.Fetch()
 if (!$record) { throw "Missing MSI row: $Query" }
 return @{ Text=$record.StringData(1); Number=$record.IntegerData(1); Second=$record.IntegerData(2) }
}
function Check([bool]$Value,[string]$Message) { if (!$Value) { throw $Message } }
$action=Row "SELECT ``Action``, ``Type`` FROM ``CustomAction`` WHERE ``Source``='ALLOWEDUSERSID'"
Check ($action.Second -eq 51) 'SID action must be Type 51'
foreach ($table in @('InstallUISequence','InstallExecuteSequence')) {
 $default=Row "SELECT ``Sequence`` FROM ``$table`` WHERE ``Action``='$($action.Text)'"
 $search=Row "SELECT ``Sequence`` FROM ``$table`` WHERE ``Action``='AppSearch'"
 $launch=Row "SELECT ``Sequence`` FROM ``$table`` WHERE ``Action``='LaunchConditions'"
 Check ($search.Number -lt $default.Number -and $default.Number -lt $launch.Number) 'SID default sequence incorrect'
}
$service=Row "SELECT ``Event`` FROM ``ServiceControl`` WHERE ``Name``='DEYTTConnectVpn'"
Check (($service.Number -band 0x88) -eq 0x88) 'Service removal events missing'
$session=$installer.OpenPackage($MsiPath,1)
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
Check ($session.Property('UserSID') -eq $sid) 'Wrong invoking identity'
$session.Property('ALLOWEDUSERSID')=''
$condition=Row "SELECT ``Condition`` FROM ``InstallUISequence`` WHERE ``Action``='$($action.Text)'"
Check ($session.EvaluateCondition($condition.Text) -eq 1) 'Fresh install condition failed'
$null=$session.DoAction($action.Text)
Check ($session.Property('ALLOWEDUSERSID') -eq $sid) 'SID default failed'
Check ($session.EvaluateCondition($condition.Text) -eq 0) 'Existing owner would be overwritten'
$session.Property('Installed')=''; $session.Property('WIX_UPGRADE_DETECTED')=''
$guard=Row "SELECT ``Condition`` FROM ``LaunchCondition`` WHERE ``Description``='The DEYTT VPN service points to an unexpected executable. Contact support to recover this installation.'"
$session.Property('EXISTING_SERVICE_PATH')='"C:\Program Files\DEYTT\Connect\DeyttConnect.Windows.Service.exe" --service'
Check ($session.EvaluateCondition($guard.Text) -eq 1) 'Standard legacy service rejected'
$session.Property('EXISTING_SERVICE_PATH')='C:\Unexpected\OtherService.exe'
Check ($session.EvaluateCondition($guard.Text) -eq 0) 'Unexpected service allowed'
Write-Host 'Read-only MSI metadata and SID checks passed; no installation performed.'
