[CmdletBinding()]
param(
    [string]$Serial,
    [string]$TestClass = "com.projectnuke.keplernightlab.HardwareE2EInstrumentationTest",
    [switch]$Install,
    [switch]$CleanLogcat
)

$ErrorActionPreference = "Stop"
$packageName = "com.projectnuke.keplernightlab"
$testPackageName = "${packageName}.test"
$instrumentation = "${testPackageName}/androidx.test.runner.AndroidJUnitRunner"
$repoRoot = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactDir = Join-Path $repoRoot "artifacts/hardware-e2e/$stamp"
$result = "HARNESS_ERROR"
$resultCode = 2

function Invoke-AdbCapture {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = @(
        if ($Serial) { & adb -s $Serial @Arguments 2>&1 } else { & adb @Arguments 2>&1 }
    )
    [pscustomobject]@{
        Output = ($output -join [Environment]::NewLine)
        ExitCode = $LASTEXITCODE
    }
}

function Invoke-AdbRequired {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $completed = Invoke-AdbCapture $Arguments
    if ($completed.ExitCode -ne 0) {
        throw "$Description failed (exit $($completed.ExitCode)): $($completed.Output)"
    }
    return $completed.Output
}

function Write-BestEffortAdbFile {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    try {
        (Invoke-AdbCapture $Arguments).Output | Set-Content -Path (Join-Path $artifactDir $Name)
    } catch {
        "best-effort collection failed: $($_.Exception.Message)" | Set-Content -Path (Join-Path $artifactDir $Name)
    }
}

New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

try {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        throw "adb was not found on PATH. Install Android platform-tools first."
    }
    $deviceList = Invoke-AdbRequired @("devices") "adb device discovery"
    $authorized = @(
        $deviceList -split "`r?`n" |
            Where-Object { $_ -match "^(\S+)\s+device$" } |
            ForEach-Object { ($_ -split "\s+")[0] }
    )
    if ($Serial) {
        if ($authorized -notcontains $Serial) {
            throw "Requested serial '$Serial' is not an authorized device. Connected authorized devices: $($authorized -join ', ')"
        }
    } elseif ($authorized.Count -ne 1) {
        if ($authorized.Count -eq 0) {
            throw "No authorized adb device is connected."
        }
        throw "Multiple authorized adb devices are connected. Re-run with -Serial <device-serial>."
    } else {
        $Serial = $authorized[0]
    }

    @(
        "serial=$Serial"
        "model=$(Invoke-AdbRequired @("shell", "getprop", "ro.product.model") "device model")"
        "manufacturer=$(Invoke-AdbRequired @("shell", "getprop", "ro.product.manufacturer") "device manufacturer")"
        "android=$(Invoke-AdbRequired @("shell", "getprop", "ro.build.version.release") "Android version")"
        "sdk=$(Invoke-AdbRequired @("shell", "getprop", "ro.build.version.sdk") "Android SDK")"
        "fingerprint=$(Invoke-AdbRequired @("shell", "getprop", "ro.build.fingerprint") "build fingerprint")"
    ) | Set-Content -Path (Join-Path $artifactDir "device.txt")

    if ($Install) {
        $targetApk = Join-Path $repoRoot "app/build/outputs/apk/debug/app-debug.apk"
        $testApks = @(Get-ChildItem -Path (Join-Path $repoRoot "app/build/outputs/apk") -Recurse -File -Filter "*.apk" |
            Where-Object { $_.Name -match "androidTest" })
        if (-not (Test-Path -LiteralPath $targetApk)) {
            throw "Debug APK not found at '$targetApk'. Build assembleDebug first."
        }
        if ($testApks.Count -ne 1) {
            throw "Expected exactly one debug androidTest APK under app/build/outputs/apk; found $($testApks.Count). Build assembleDebugAndroidTest and remove ambiguity."
        }
        Invoke-AdbRequired @("install", "-r", $targetApk) "debug APK installation" | Out-Null
        Invoke-AdbRequired @("install", "-r", $testApks[0].FullName) "androidTest APK installation" | Out-Null
    }

    $instrumentations = Invoke-AdbRequired @("shell", "pm", "list", "instrumentation") "instrumentation preflight"
    if ($instrumentations -notmatch [regex]::Escape($instrumentation)) {
        throw "Expected instrumentation '$instrumentation' is not installed. Build/install app-debug.apk and the matching androidTest APK, or rerun with -Install."
    }

    if ($CleanLogcat) {
        Invoke-AdbRequired @("logcat", "-c") "logcat clear" | Out-Null
    }
    Invoke-AdbRequired @("shell", "pm", "grant", $packageName, "android.permission.CAMERA") "CAMERA permission grant" | Out-Null

    $instrumentationArgs = @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "kepler.hardwareE2E", "true",
        "-e", "class", $TestClass,
        $instrumentation
    )
    Write-Host "Running opt-in hardware instrumentation on $Serial ..."
    $harnessStartWallClock = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $instrumentationResult = Invoke-AdbCapture $instrumentationArgs
    $instrumentationResult.Output | Set-Content -Path (Join-Path $artifactDir "instrumentation.txt")
    $runMatch = [regex]::Match($instrumentationResult.Output, "HARDWARE_E2E_RUN_ID=([0-9a-fA-F-]+)")

    Write-BestEffortAdbFile "logcat.txt" @(
        "logcat", "-d", "-v", "threadtime",
        "KeplerCaptureStatus:I", "KeplerPipelineState:I", "KeplerPhysicalRoute:I",
        "KeplerYuvPipeline:I", "KeplerRawPipeline:I", "KeplerSuperResolution:I",
        "KeplerHardwareE2E:I", "*:S"
    )
    Write-BestEffortAdbFile "dumpsys-package.txt" @("shell", "dumpsys", "package", $packageName)
    Write-BestEffortAdbFile "process.txt" @("shell", "dumpsys", "activity", "processes")

    if ($instrumentationResult.ExitCode -ne 0) {
        $result = "FAIL"
        $resultCode = 1
        throw "Instrumentation failed (exit $($instrumentationResult.ExitCode)). See instrumentation.txt."
    }

    if ($runMatch.Success) {
        $runId = $runMatch.Groups[1].Value
        $reportText = Invoke-AdbRequired @("shell", "run-as", $packageName, "cat", "files/hardware-e2e/$runId.json") "exact hardware report retrieval"
        $reportText | Set-Content -Path (Join-Path $artifactDir "hardware-e2e-report.json")
        $report = $reportText | ConvertFrom-Json
        if ($report.runId -ne $runId) {
            throw "Retrieved report runId '$($report.runId)' does not match instrumentation runId '$runId'."
        }
        if ($report.status -ne "PASS") {
            $result = "FAIL"
            $resultCode = 1
            throw "Production hardware smoke did not PASS: status=$($report.status) reason=$($report.classificationReason). See hardware-e2e-report.json."
        }
        $result = "PASS"
        $resultCode = 0
    } else {
        $toleranceMs = 5000
        $latestJson = Invoke-AdbRequired @("shell", "run-as", $packageName, "cat", "files/hardware-e2e/latest.json") "latest.json retrieval for fail-closed fallback"
        $latestReport = $latestJson | ConvertFrom-Json
        $reportStart = $latestReport.runStartWallClockTimestamp
        $diff = [Math]::Abs($reportStart - $harnessStartWallClock)
        if ($diff -gt $toleranceMs -or [string]::IsNullOrWhiteSpace($latestReport.runId)) {
            throw "Instrumentation passed without HARDWARE_E2E_RUN_ID and latest.json is not from this harness invocation (runStartWallClockTimestamp=$reportStart, harnessStart=$harnessStartWallClock, diff=$diff ms, tolerance=$toleranceMs ms)."
        }
        $runId = $latestReport.runId
        $reportText = Invoke-AdbRequired @("shell", "run-as", $packageName, "cat", "files/hardware-e2e/$runId.json") "exact hardware report retrieval (fallback)"
        $reportText | Set-Content -Path (Join-Path $artifactDir "hardware-e2e-report.json")
        $report = $reportText | ConvertFrom-Json
        if ($report.runId -ne $runId) {
            throw "Retrieved report runId '$($report.runId)' does not match fallback runId '$runId'."
        }
        if ($report.status -ne "PASS") {
            $result = "FAIL"
            $resultCode = 1
            throw "Production hardware smoke did not PASS: status=$($report.status) reason=$($report.classificationReason). See hardware-e2e-report.json."
        }
        $result = "PASS"
        $resultCode = 0
    }
} catch {
    if ($result -eq "HARNESS_ERROR" -and $_.Exception.Message -match "Instrumentation failed") {
        $result = "FAIL"
        $resultCode = 1
    }
    Write-Error $_.Exception.Message
} finally {
    Write-Host "$result"
    Write-Host "Artifacts: $artifactDir"
}

exit $resultCode
