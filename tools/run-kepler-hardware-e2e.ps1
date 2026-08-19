[CmdletBinding()]
param(
    [string]$Serial,
    [string]$TestClass = "com.projectnuke.keplernightlab.HardwareE2EInstrumentationTest",
    [switch]$CleanLogcat
)

$ErrorActionPreference = "Stop"
$packageName = "com.projectnuke.keplernightlab"
$instrumentation = "${packageName}.test/androidx.test.runner.AndroidJUnitRunner"
$repoRoot = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactDir = Join-Path $repoRoot "artifacts/hardware-e2e/$stamp"

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    if ($Serial) {
        & adb -s $Serial @Arguments
    } else {
        & adb @Arguments
    }
}

function Write-AdbFile {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $path = Join-Path $artifactDir $Name
    Invoke-Adb $Arguments 2>&1 | Tee-Object -FilePath $path
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb was not found on PATH. Install Android platform-tools first."
}

New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
$devices = @(adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\S+\s+device$" })
if ($devices.Count -eq 0) {
    throw "No authorized adb device is connected."
}
if (-not $Serial -and $devices.Count -ne 1) {
    throw "Multiple adb devices are connected. Re-run with -Serial <device-serial>."
}
if (-not $Serial) {
    $Serial = ($devices[0] -split "\s+")[0]
}

@(
    "serial=$Serial"
    "model=$(Invoke-Adb @("shell", "getprop", "ro.product.model"))"
    "manufacturer=$(Invoke-Adb @("shell", "getprop", "ro.product.manufacturer"))"
    "android=$(Invoke-Adb @("shell", "getprop", "ro.build.version.release"))"
    "sdk=$(Invoke-Adb @("shell", "getprop", "ro.build.version.sdk"))"
    "fingerprint=$(Invoke-Adb @("shell", "getprop", "ro.build.fingerprint"))"
) | Set-Content -Path (Join-Path $artifactDir "device.txt")

if ($CleanLogcat) {
    Invoke-Adb @("logcat", "-c")
}

try {
    Invoke-Adb @("shell", "pm", "grant", $packageName, "android.permission.CAMERA")
} catch {
    Write-Warning "CAMERA permission grant was not accepted; the app may prompt interactively. $($_.Exception.Message)"
}

$instrumentationArgs = @(
    "shell", "am", "instrument", "-w", "-r",
    "-e", "kepler.hardwareE2E", "true",
    "-e", "class", $TestClass,
    $instrumentation
)
Write-Host "Running opt-in hardware instrumentation on $Serial ..."
Write-AdbFile -Name "instrumentation.txt" -Arguments $instrumentationArgs

Write-AdbFile -Name "logcat.txt" -Arguments @(
    "logcat", "-d", "-v", "threadtime",
    "KeplerCaptureStatus:I", "KeplerPipelineState:I", "KeplerPhysicalRoute:I",
    "KeplerYuvPipeline:I", "KeplerRawPipeline:I", "KeplerSuperResolution:I",
    "KeplerHardwareE2E:I", "*:S"
)
Write-AdbFile -Name "dumpsys-package.txt" -Arguments @("shell", "dumpsys", "package", $packageName)
Write-AdbFile -Name "process.txt" -Arguments @("shell", "dumpsys", "activity", "processes")

$reportPath = Join-Path $artifactDir "hardware-e2e-report.json"
Invoke-Adb @("shell", "run-as", $packageName, "cat", "files/hardware-e2e/latest.json") 2>&1 |
    Tee-Object -FilePath $reportPath

Write-Host "Hardware E2E artifacts: $artifactDir"
