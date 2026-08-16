$p = "C:\Users\junyo\AndroidStudioProjects\KeplerNightLab\app\src\main\java\com\projectnuke\keplernightlab\KeplerGalleryReprocess.kt"
$nl = [string][char]13 + [string][char]10
$t = [System.IO.File]::ReadAllText($p)
$old = 'val activeOperationId = KeplerJobMetadata.read(jobDir)' + $nl + '                        .optString(ACTIVE_OPERATION_ID)' + $nl + '                        .takeIf { it.isNotBlank() }' + $nl + '                    val cleared = activeOperationId?.let {' + $nl + '                        KeplerJobMetadata.clearActiveOperation(jobDir, it, operationLease)' + $nl + '                    } ?: true' + $nl + '                    if (cleared || activeOperationId == null ||' + $nl + '                        !KeplerJobMetadata.isCurrentActiveOperation(jobDir, activeOperationId)' + $nl + '                    ) {' + $nl + '                        operationLease.releaseIfProcessingSettled()' + $nl + '                    }'
$new = 'settleReprocessTerminalOwner(jobDir, operationLease)' + $nl + '                    operationLease.releaseIfProcessingSettled()'
$count = ([regex]::Matches($t, [regex]::Escape($old))).Count
Write-Host "idempotent matches=$count"
$t = $t.Replace($old, $new)
[System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "done"
