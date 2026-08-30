$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$buildFile = Join-Path $repoRoot "app\build.gradle.kts"
$git = Get-Command git -ErrorAction Stop
$keytool = Get-Command keytool -ErrorAction Stop
$localPath = Join-Path $repoRoot "app\local-debug.jks"
$pinnedPath = Join-Path $repoRoot "app\kepler-debug.jks"

function Invoke-Git([string[]] $arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    & $git.Source @arguments 2>$null | Out-Null
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    return $exitCode
}

function Get-DebugCertificateFingerprint([string] $path) {
    $output = & $keytool.Source -list -v -keystore $path -storepass android -alias androiddebugkey 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the selected debug certificate."
    }
    $match = $output | Select-String "SHA256:\s*(.+)$" | Select-Object -First 1
    if ($null -eq $match) {
        throw "Selected debug certificate did not report a SHA-256 fingerprint."
    }
    return $match.Matches[0].Groups[1].Value.Trim()
}

$buildSource = Get-Content -Raw $buildFile
if ((Invoke-Git @("check-ignore", "--quiet", "--", "app/local-debug.jks")) -ne 0) {
    throw "app/local-debug.jks is not ignored by Git."
}
if ((Invoke-Git @("ls-files", "--error-unmatch", "app/local-debug.jks")) -eq 0) {
    throw "Developer-local app/local-debug.jks is tracked."
}
if ((Invoke-Git @("ls-files", "--error-unmatch", "app/kepler-debug.jks")) -ne 0) {
    throw "Project-pinned app/kepler-debug.jks is not tracked."
}
if (-not (Test-Path -Path $pinnedPath -PathType Leaf)) {
    throw "Project-pinned fallback debug keystore is missing."
}
$signingSource = $buildSource.Substring(0, $buildSource.IndexOf("defaultConfig"))
if ($signingSource -match "\.android[\\/]debug\.keystore|debug\.keystore") {
    throw "Build signing references a machine-default debug keystore."
}
if ($buildSource -notmatch "app/local-debug\.jks" -or $buildSource -notmatch "app/kepler-debug\.jks") {
    throw "Build signing does not reference both intended project keystore paths."
}

$selectedPath = if (Test-Path -Path $localPath -PathType Leaf) { $localPath } else { $pinnedPath }
$selectedCategory = if ($selectedPath -eq $localPath) {
    "developer-local pinned override"
} else {
    "checked-in project fallback"
}
Write-Host "Debug signing safety: PASS"
Write-Host "Selected source category: $selectedCategory"
Write-Host "Local pin present: $([bool](Test-Path -Path $localPath -PathType Leaf))"
Write-Host "Certificate SHA-256: $(Get-DebugCertificateFingerprint $selectedPath)"
