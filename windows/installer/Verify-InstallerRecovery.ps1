param([Parameter(Mandatory=$true)][string]$MsiPath, [string]$ActionsAssemblyPath)
$ErrorActionPreference='Stop'
$MsiPath=(Resolve-Path -LiteralPath $MsiPath).Path
$installer=New-Object -ComObject WindowsInstaller.Installer
$database=$installer.OpenDatabase((Resolve-Path -LiteralPath $MsiPath).Path,0)
function Row([string]$Query) {
 $view=$database.OpenView($Query); $null=$view.Execute(); $record=$view.Fetch()
 if (!$record) { throw "Missing MSI row: $Query" }
 return @{ Text=$record.StringData(1); Number=$record.IntegerData(1); Second=$record.IntegerData(2) }
}
function QueryRows([string]$Query) {
 $view=$database.OpenView($Query); $null=$view.Execute(); $values=@()
 while ($record=$view.Fetch()) { $values+=,$record.StringData(1) }
 $null=$view.Close()
 return $values
}
function Controls([string]$Dialog) {
 $view=$database.OpenView("SELECT ``Control``,``Type``,``X``,``Y``,``Width``,``Height``,``Text`` FROM ``Control`` WHERE ``Dialog_``='$Dialog'")
 $null=$view.Execute(); $values=@()
 while ($record=$view.Fetch()) {
  $values+=,[pscustomobject]@{
   Id=$record.StringData(1); Type=$record.StringData(2)
   X=$record.IntegerData(3); Y=$record.IntegerData(4)
   Width=$record.IntegerData(5); Height=$record.IntegerData(6)
   Text=$record.StringData(7)
  }
 }
 $null=$view.Close()
 return $values
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
Check ($startShortcut.Text -eq '[INSTALLFOLDER]DeyttConnect.Windows.exe') 'Start Menu shortcut must directly target the installed app executable'
$shortcutComponent=Row "SELECT ``Component_`` FROM ``Shortcut`` WHERE ``Shortcut``='UiStartMenuShortcut'"
Check ($shortcutComponent.Text -eq 'UiStartMenuShortcutComponent') 'Start Menu shortcut must be isolated from the machine executable component'
$shortcutRegistry=Row "SELECT ``Root`` FROM ``Registry`` WHERE ``Component_``='UiStartMenuShortcutComponent' AND ``Name``='StartMenuShortcut'"
Check ($shortcutRegistry.Number -eq 1) 'Start Menu shortcut component must use an HKCU registry keypath'
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
foreach($dialog in @('SetupWelcome','SetupReady','SetupProgress','SetupComplete','SetupMaintenance','SetupRemoveConfirm')) {
 $null=Row "SELECT ``Dialog`` FROM ``Dialog`` WHERE ``Dialog``='$dialog'"
}
$dialogNames=@('SetupWelcome','SetupMaintenance','SetupRemoveConfirm','SetupReady','SetupProgress','SetupComplete')
foreach($dialog in $dialogNames) {
 $size=Row "SELECT ``Width``,``Height`` FROM ``Dialog`` WHERE ``Dialog``='$dialog'"
 Check ($size.Number -eq 568 -and $size.Second -eq 348) "$dialog dimensions changed"
 $brand=Row "SELECT ``Text`` FROM ``Control`` WHERE ``Dialog_``='$dialog' AND ``Control``='Brand'"
 Check ($brand.Text -eq '{\SetupBrand}./connect') "$dialog must use the single-line ./connect wordmark"
 $brandControl=Controls $dialog | Where-Object Id -eq 'Brand'
 Check (($brandControl.X + $brandControl.Width/2) -eq 112 -and
        ($brandControl.Y + $brandControl.Height/2) -eq 174) "$dialog wordmark must be centered in the sidebar"
 $art=Row "SELECT ``X``,``Y`` FROM ``Control`` WHERE ``Dialog_``='$dialog' AND ``Control``='BrandArt'"
 $artSize=Row "SELECT ``Width``,``Height`` FROM ``Control`` WHERE ``Dialog_``='$dialog' AND ``Control``='BrandArt'"
 Check ($art.Number -eq 0 -and $art.Second -eq 0 -and $artSize.Number -eq 224 -and $artSize.Second -eq 348) "$dialog brand art dimensions changed"
 foreach($captionId in @('Caption','BrandCaption','BrandCaptionSecondLine')) {
  Check ((QueryRows "SELECT ``Control`` FROM ``Control`` WHERE ``Dialog_``='$dialog' AND ``Control``='$captionId'").Count -eq 0) "$dialog must not add the removed tagline"
 }
 foreach($control in (Controls $dialog)) {
  Check ($control.X -ge 0 -and $control.Y -ge 0 -and $control.Width -ge 0 -and $control.Height -ge 0 -and
         $control.X + $control.Width -le 568 -and $control.Y + $control.Height -le 348) "$dialog.$($control.Id) extends outside its dialog"
 }
}
$mainControls=@(
 @('SetupWelcome','Step'),@('SetupWelcome','Title'),@('SetupWelcome','UpdateTitle'),@('SetupWelcome','Intro'),@('SetupWelcome','UpdateIntro'),@('SetupWelcome','FeatureOne'),@('SetupWelcome','FeatureOneBody'),@('SetupWelcome','Version'),
 @('SetupMaintenance','Step'),@('SetupMaintenance','Title'),@('SetupMaintenance','Intro'),
 @('SetupRemoveConfirm','Step'),@('SetupRemoveConfirm','Title'),@('SetupRemoveConfirm','Intro'),@('SetupRemoveConfirm','Account'),
 @('SetupReady','Step'),@('SetupReady','Title'),@('SetupReady','UpdateTitle'),@('SetupReady','Details'),@('SetupReady','PathLabel'),@('SetupReady','Path'),
 @('SetupProgress','Step'),@('SetupProgress','Title'),@('SetupProgress','Intro'),@('SetupProgress','Action'),
 @('SetupComplete','Step'),@('SetupComplete','Title'),@('SetupComplete','RemovedTitle'),@('SetupComplete','InstalledText'),@('SetupComplete','RemovedText'),@('SetupComplete','ManualLaunch'),@('SetupComplete','Error'),@('SetupComplete','Reboot')
)
foreach($item in $mainControls) {
 $control=Row "SELECT ``X``,``Width`` FROM ``Control`` WHERE ``Dialog_``='$($item[0])' AND ``Control``='$($item[1])'"
 Check ($control.Number -eq 252 -and $control.Second -eq 288) "Main-column layout changed for $($item[0]).$($item[1])"
}
$bottomButtons=@(
 @('SetupWelcome','Cancel',340),@('SetupWelcome','Next',448),
 @('SetupMaintenance','Cancel',340),@('SetupMaintenance','Remove',448),
 @('SetupRemoveConfirm','Cancel',340),@('SetupRemoveConfirm','Remove',448),
 @('SetupReady','Back',252),@('SetupReady','Cancel',344),@('SetupReady','Install',448),
 @('SetupProgress','Cancel',448),@('SetupComplete','Finish',448)
)
foreach($item in $bottomButtons) {
 $position=Row "SELECT ``X``,``Y`` FROM ``Control`` WHERE ``Dialog_``='$($item[0])' AND ``Control``='$($item[1])'"
 Check ($position.Number -eq $item[2] -and $position.Second -eq 316) "Bottom button alignment changed for $($item[0]).$($item[1])"
}
$progressSequence=Row 'SELECT `Sequence` FROM `InstallUISequence` WHERE `Action`=''ProgressDlg'''
$customProgressSequence=Row 'SELECT `Sequence` FROM `InstallUISequence` WHERE `Action`=''SetupProgress'''
$executeSequence=Row 'SELECT `Sequence` FROM `InstallUISequence` WHERE `Action`=''ExecuteAction'''
Check ($progressSequence.Number -lt $customProgressSequence.Number -and $customProgressSequence.Number -lt $executeSequence.Number) 'Branded progress dialog must follow stock ProgressDlg and precede ExecuteAction'
$actionTextSubscriptions=@(QueryRows 'SELECT `Attribute` FROM `EventMapping` WHERE `Dialog_`=''SetupProgress'' AND `Control_`=''Action'' AND `Event`=''ActionText''')
Check ($actionTextSubscriptions.Count -eq 1 -and $actionTextSubscriptions[0] -eq 'Text') 'SetupProgress.Action must subscribe once to ActionText.Text'
$progressSubscriptions=QueryRows 'SELECT `Event` FROM `EventMapping` WHERE `Dialog_`=''SetupProgress'' AND `Control_`=''Action'''
Check ($progressSubscriptions.Count -eq 1) 'SetupProgress.Action must not overlap a second stock status subscription'
$setupProgressControls=Controls 'SetupProgress'
$actionControl=$setupProgressControls | Where-Object Id -eq 'Action'
$overlappingActionControls=$setupProgressControls | Where-Object { $_.Id -ne 'Action' -and $_.X -lt ($actionControl.X+$actionControl.Width) -and ($_.X+$_.Width) -gt $actionControl.X -and $_.Y -lt ($actionControl.Y+$actionControl.Height) -and ($_.Y+$_.Height) -gt $actionControl.Y }
Check ($overlappingActionControls.Count -eq 0) 'SetupProgress.ActionText area overlaps another control'
$null=Row 'SELECT `Control` FROM `Control` WHERE `Dialog_`=''SetupComplete'' AND `Control`=''Desktop'''
$launchView=$database.OpenView('SELECT `Control` FROM `Control` WHERE `Dialog_`=''SetupComplete'' AND `Control`=''Launch''')
$null=$launchView.Execute()
Check ([bool]$launchView.Fetch()) 'Finish dialog must contain the optional launch checkbox'
$launchProperty=Row 'SELECT `Property` FROM `Control` WHERE `Dialog_`=''SetupComplete'' AND `Control`=''Launch'''
Check ($launchProperty.Text -eq 'LAUNCH_CONNECT') 'Launch checkbox must control the launch action'
$removal=Row 'SELECT `Condition` FROM `InstallExecuteSequence` WHERE `Action`=''RemoveDesktopShortcut'''
Check ($removal.Text -match 'NOT UPGRADINGPRODUCTCODE') 'Upgrade would remove the desktop shortcut'
$packageVersion=(Row 'SELECT `Value` FROM `Property` WHERE `Property`=''ProductVersion''').Text
$productCode=(Row 'SELECT `Value` FROM `Property` WHERE `Property`=''ProductCode''').Text
$expectedCode=& "$PSScriptRoot/Get-ProductCode.ps1" -ProductVersion $packageVersion
Check ($productCode -eq $expectedCode) 'ProductCode must be stable for the same release'
$upgradeCode=(Row 'SELECT `Value` FROM `Property` WHERE `Property`=''UpgradeCode''').Text
Check ($upgradeCode -eq '{405BFF99-D3EE-5F7F-BF2D-FC11478F37B5}') 'UpgradeCode changed; installed products would not be detected'
$modeClassifier=$actionsAssembly.GetType('DeyttConnect.Setup.SetupActions').GetMethod('ClassifySetupMode',[Reflection.BindingFlags]'Static,NonPublic')
$modeCases=@(
 @($false,$false,$null,[Version]'0.8.40','install'),
 @($true,$false,[Version]'0.8.40',[Version]'0.8.40','maintenance'),
 @($false,$true,[Version]'0.8.39',[Version]'0.8.40','update'),
 @($false,$true,$null,[Version]'0.8.40','update'),
 @($false,$true,[Version]'0.8.41',[Version]'0.8.40','install')
)
foreach($case in $modeCases) {
 Check ($modeClassifier.Invoke($null,@($case[0],$case[1],$case[2],$case[3])) -eq $case[4]) 'Setup mode classification failed'
}
$upgradeGuard=Row 'SELECT `Condition` FROM `LaunchCondition` WHERE `Condition`=''NOT WIX_DOWNGRADE_DETECTED'''
$session.Property('WIX_DOWNGRADE_DETECTED')='{00000000-0000-0000-0000-000000000001}'
Check ($session.EvaluateCondition($upgradeGuard.Text) -eq 0) 'Newer installed versions must block downgrade'
$session.Property('WIX_DOWNGRADE_DETECTED')=''
Check ($session.EvaluateCondition($upgradeGuard.Text) -eq 1) 'Downgrade guard blocks normal install'
$launchClassifier=$actionsAssembly.GetType('DeyttConnect.Setup.SetupActions').GetMethod('ShouldLaunchConnect',[Reflection.BindingFlags]'Static,NonPublic')
$launchCases=@(@('','1','',$false),@('1','1','',$true),@('1','','',$false),@('1','1','1',$false),@('0','1','',$false))
foreach($case in $launchCases) {
 Check ($launchClassifier.Invoke($null,@($case[0],$case[1],$case[2])) -eq $case[3]) 'Unchecked launch or unavailable shell/reboot must not launch the client'
}
Write-Host 'MSI identity, version modes, downgrade, progress layout, brand, launch, SID, service and wizard checks passed; no installation performed. Real install/update/removal remains manual acceptance.'
