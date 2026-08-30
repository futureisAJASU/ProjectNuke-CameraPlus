$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $env:USERPROFILE ".android\debug.keystore"
$destination = Join-Path $repoRoot "app\local-debug.jks"
$keytool = (Get-Command keytool -ErrorAction Stop).Source

function Get-DebugCertificateFingerprint([string] $path) {
    $output = & $keytool -list -v -keystore $path -storepass android -alias androiddebugkey 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the Android debug certificate."
    }
    $match = $output | Select-String "SHA256:\s*(.+)$" | Select-Object -First 1
    if ($null -eq $match) {
        throw "Android debug certificate did not report a SHA-256 fingerprint."
    }
    return $match.Matches[0].Groups[1].Value.Trim()
}

if (Test-Path $destination) {
    $fingerprint = Get-DebugCertificateFingerprint $destination
    Write-Host "Local debug pin already exists; preserving its identity."
    Write-Host "Source category: developer-local pinned override"
    Write-Host "Certificate SHA-256: $fingerprint"
    exit 0
}

if (-not (Test-Path -Path $source -PathType Leaf)) {
    throw "Default Android debug keystore not found: $source"
}

$null = Get-DebugCertificateFingerprint $source
$destinationDirectory = Split-Path -Parent $destination
$temporaryDestination = Join-Path $destinationDirectory ("local-debug.jks.tmp-" + [System.IO.Path]::GetRandomFileName())
try {
    Copy-Item -LiteralPath $source -Destination $temporaryDestination
    $fingerprint = Get-DebugCertificateFingerprint $temporaryDestination
    if (Test-Path -Path $destination -PathType Leaf) {
        # A concurrent creator wins; never overwrite its identity.
        $fingerprint = Get-DebugCertificateFingerprint $destination
    } else {
        Move-Item -LiteralPath $temporaryDestination -Destination $destination
    }
} catch {
    if (Test-Path -LiteralPath $temporaryDestination -PathType Leaf) {
        Remove-Item -LiteralPath $temporaryDestination -Force -ErrorAction SilentlyContinue
    }
    throw
}
Write-Host "Created the developer-local debug pin; future runs preserve it."
Write-Host "Source category: developer-local pinned override"
Write-Host "Certificate SHA-256: $fingerprint"
