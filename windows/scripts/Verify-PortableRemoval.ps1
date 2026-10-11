param([Parameter(Mandatory)][string]$ArtifactRoot)
$ErrorActionPreference = 'Stop'
$artifactPath = [IO.Path]::GetFullPath($ArtifactRoot).TrimEnd('\')
if ($artifactPath -eq [IO.Path]::GetPathRoot($artifactPath).TrimEnd('\')) { throw 'use a task artifact directory, not a drive root.' }
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $artifactPath ('portable-removal-fixture-' + [Guid]::NewGuid().ToString('N'))))
if (-not $fixtureRoot.StartsWith($artifactPath + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'unsafe fixture root.' }

# Load only pure path/cache helpers. Service and registry mutations are never run.
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Uninstall-VpnService.ps1'), [ref]$null, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) { throw 'uninstaller has parsing errors.' }
foreach ($name in @('Test-PlainPath', 'Remove-OwnedCache', 'Get-ManagedFilePaths')) {
    $functionAst = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    if ($null -eq $functionAst) { throw "missing helper: $name" }
    Invoke-Expression $functionAst.Extent.Text
}
function Check([bool]$Value, [string]$Message) { if (-not $Value) { throw $Message } }
$managed = Join-Path $fixtureRoot 'managed'
$personal = Join-Path $fixtureRoot 'personal'
$junction = Join-Path $managed 'linked'
try {
    New-Item -ItemType Directory -Path $managed,$personal -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $personal 'keep.txt') -Value 'Personal fixture'
    $paths = @(Get-ManagedFilePaths $managed @('engine.exe', 'service\runtime.dll'))
    Check ($paths.Count -eq 2 -and $paths[0] -eq (Join-Path $managed 'engine.exe')) 'valid manifest resolution failed.'
    foreach ($unsafe in @('..\personal\keep.txt', (Join-Path $personal 'keep.txt'), '')) {
        $rejected = $false
        try { $null = Get-ManagedFilePaths $managed @($unsafe) } catch { $rejected = $true }
        Check $rejected 'unsafe manifest path was accepted.'
    }
    New-Item -ItemType Junction -Path $junction -Target $personal | Out-Null
    $rejected = $false
    try { $null = Get-ManagedFilePaths $managed @('linked\keep.txt') } catch { $rejected = $true }
    Check $rejected 'manifest followed a junction.'
    Remove-OwnedCache $junction
    Check (Test-Path -LiteralPath (Join-Path $personal 'keep.txt')) 'cache cleanup followed a junction.'
    Remove-Item -LiteralPath $junction -Force
    $cache = Join-Path $managed 'updates'
    New-Item -ItemType Directory -Path (Join-Path $cache 'download') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $cache 'download\cached.bin') -Value 'Cache fixture'
    Remove-OwnedCache $cache
    Check (-not (Test-Path -LiteralPath $cache)) 'owned cache was not removed.'
    Check (Test-Path -LiteralPath (Join-Path $personal 'keep.txt')) 'personal fixture was removed.'
    Write-Output 'Portable path allowlist, traversal rejection, junction preservation and cache cleanup passed; no service or registry mutations.'
} finally {
    if (Test-Path -LiteralPath $junction) { Remove-Item -LiteralPath $junction -Force }
    if (Test-Path -LiteralPath $fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force }
}
