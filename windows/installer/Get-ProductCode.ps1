param([Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$ProductVersion)
# Stable MSI identity per release; changed payload must increment the version.
$hash = [Security.Cryptography.SHA256]::Create()
$normalizedVersion = ([Version]$ProductVersion).ToString(3)
try { $bytes = $hash.ComputeHash([Text.Encoding]::UTF8.GetBytes("DEYTT.Connect.Windows|win-x64|$normalizedVersion")) }
finally { $hash.Dispose() }
[byte[]]$guidBytes = $bytes[0..15]
$guidBytes[7] = ($guidBytes[7] -band 15) -bor 128
$guidBytes[8] = ($guidBytes[8] -band 63) -bor 128
[Guid]::new($guidBytes).ToString('B').ToUpperInvariant()
