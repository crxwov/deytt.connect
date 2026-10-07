param([Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference = 'Stop'
$Destination = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path (Split-Path $Destination) -Force | Out-Null
if (!(Test-Path -LiteralPath $Destination)) {
    Invoke-WebRequest -Uri 'https://go.microsoft.com/fwlink/p/?LinkId=2124703' -OutFile $Destination
}
$signature = Get-AuthenticodeSignature -LiteralPath $Destination
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch '(^|, )CN=Microsoft Corporation(,|$)') {
    throw 'WebView2 bootstrapper must have a valid Microsoft Corporation Authenticode signature.'
}
Write-Host 'Microsoft WebView2 bootstrapper signature verified.'
