param([Parameter(Mandatory=$true)][string]$MsiPath, [string]$ActionsAssemblyPath)
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
$startShortcut=Row "SELECT ``Target`` FROM ``Shortcut`` WHERE ``Shortcut``='UiStartMenuShortcut'"
Check ($startShortcut.Text -eq 'MainFeature') 'Start Menu shortcut must be advertised by the app component'
$webView=Row "SELECT ``File`` FROM ``File`` WHERE ``File``='WebViewBootstrapper'"
Check ($webView.Text -eq 'WebViewBootstrapper') 'Signed WebView2 bootstrapper not packaged'
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
$guard=Row 'SELECT `Condition` FROM `LaunchCondition` WHERE `Condition`=''NOT EXISTING_SERVICE_PATH OR CONNECT_SERVICE_COMPATIBLE = "1" OR REMOVE~="ALL"'''
if (!$ActionsAssemblyPath) { $ActionsAssemblyPath=Join-Path (Split-Path $MsiPath) 'net472/DeyttConnect.SetupActions.dll' }
$null=[Reflection.Assembly]::LoadFrom((Join-Path (Split-Path $ActionsAssemblyPath) 'WixToolset.Dtf.WindowsInstaller.dll'))
$actionsAssembly=[Reflection.Assembly]::LoadFrom($ActionsAssemblyPath)
$classifier=$actionsAssembly.GetType('DeyttConnect.Setup.SetupActions').GetMethod('IsConnectService')
$cases=@(
 @('"C:\Program Files\DEYTT\Connect\DeyttConnect.Windows.Service.exe" --service',$true),
 @('"D:\Portable\service\DeyttConnect.Windows.Service.exe" --service',$true),
 @('C:\Stale\DeyttConnect.Windows.Service.exe',$true),
 @('C:\Program Files\DEYTT\DeyttConnect.Windows.Service.exe --service',$false),
 @('C:\Other\Other.exe --args C:\DeyttConnect.Windows.Service.exe --service',$false),
 @('"C:\Other\OtherService.exe" --service',$false),
 @('"C:\Other\OtherService.exe" "DeyttConnect.Windows.Service.exe"',$false),
 @('"C:\Other\FakeDeyttConnect.Windows.Service.exe"',$false),
 @('"C:\Other\DeyttConnect.Windows.Service.exe" --unexpected',$false)
)
foreach($case in $cases) {
 $session.Property('EXISTING_SERVICE_PATH')=$case[0]
 $session.Property('CONNECT_SERVICE_COMPATIBLE')=if($classifier.Invoke($null,@([string]$case[0]))) {'1'} else {''}
 Check (($session.EvaluateCondition($guard.Text) -eq 1) -eq $case[1]) ('Service classification failed: '+$case[0])
}
foreach($dialog in @('SetupWelcome','SetupReady','SetupComplete','SetupMaintenance','SetupRemoveConfirm')) {
 $null=Row "SELECT ``Dialog`` FROM ``Dialog`` WHERE ``Dialog``='$dialog'"
}
$null=Row 'SELECT `Control` FROM `Control` WHERE `Dialog_`=''SetupComplete'' AND `Control`=''Desktop'''
$null=Row 'SELECT `Control` FROM `Control` WHERE `Dialog_`=''SetupComplete'' AND `Control`=''Launch'''
$removal=Row 'SELECT `Condition` FROM `InstallExecuteSequence` WHERE `Action`=''RemoveDesktopShortcut'''
Check ($removal.Text -match 'NOT UPGRADINGPRODUCTCODE') 'Upgrade would remove the desktop shortcut'
Write-Host 'MSI SID, service classifier and wizard metadata checks passed; no installation performed. Custom DLL actions need full installer-context acceptance.'
