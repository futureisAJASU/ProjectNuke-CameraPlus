package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class MediaStoreExportRole {
    MAIN_IMAGE,
    RAW_DNG_SIDECAR
}

internal enum class MediaStoreExportState {
    PREPARED,
    ROW_INSERTED,
    CONTENT_WRITTEN,
    PUBLIC_COMMITTED,
    VERIFIED,
    /** Legacy on-disk value; new code never transitions to this state. */
    TERMINAL_PERSISTED,
    CLEANUP_REQUIRED
}

/** Direct-child, filename-only evidence for one MediaStore insert attempt. */
internal data class MediaStoreExportJournal(
    val exportAttemptId: String,
    val runtimeSessionId: String,
    val jobIdentity: String,
    val role: MediaStoreExportRole,
    val frameIndex: Int?,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val collectionUri: String,
    val uri: String?,
    val expectedSizeBytes: Long?,
    val expectedSha256: String?,
    val expectedWidth: Int?,
    val expectedHeight: Int?,
    val ownerOperationId: String? = null,
    val ownerRuntimeSessionId: String = runtimeSessionId,
    val terminalMetadataPersisted: Boolean = false,
    val terminalMetadataPersistedAt: Long? = null,
    val state: MediaStoreExportState,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun writeTo(jobDir: File): MediaStoreExportJournal {
        require(jobIdentity == jobDir.name) { "Export journal job identity mismatch" }
        KeplerJobMetadata.atomicWrite(fileFor(jobDir, exportAttemptId), toJson().toString(2))
        return this
    }

    fun transition(
        jobDir: File,
        next: MediaStoreExportState,
        uriOverride: String? = uri,
        expectedSha256Override: String? = expectedSha256
    ): MediaStoreExportJournal = copy(
        state = next,
        uri = uriOverride,
        expectedSha256 = expectedSha256Override,
        updatedAt = System.currentTimeMillis()
    ).writeTo(jobDir)

    fun markTerminalPersisted(jobDir: File): MediaStoreExportJournal = copy(
        terminalMetadataPersisted = true,
        terminalMetadataPersistedAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    ).writeTo(jobDir)

    fun deleteIfOwned(jobDir: File) {
        val file = fileFor(jobDir, exportAttemptId)
        if (file.isFile) file.delete()
    }

    private fun toJson(): JSONObject = JSONObject()
        .put("exportAttemptId", exportAttemptId)
        .put("runtimeSessionId", runtimeSessionId)
        .put("jobIdentity", jobIdentity)
        .put("role", role.name)
        .put("frameIndex", frameIndex ?: JSONObject.NULL)
        .put("displayName", displayName)
        .put("relativePath", relativePath)
        .put("mimeType", mimeType)
        .put("collectionUri", collectionUri)
        .put("uri", uri ?: JSONObject.NULL)
        .put("expectedSizeBytes", expectedSizeBytes ?: JSONObject.NULL)
        .put("expectedSha256", expectedSha256 ?: JSONObject.NULL)
        .put("expectedWidth", expectedWidth ?: JSONObject.NULL)
        .put("expectedHeight", expectedHeight ?: JSONObject.NULL)
        .put("ownerOperationId", ownerOperationId ?: JSONObject.NULL)
        .put("ownerRuntimeSessionId", ownerRuntimeSessionId)
        .put("terminalMetadataPersisted", terminalMetadataPersisted)
        .put("terminalMetadataPersistedAt", terminalMetadataPersistedAt ?: JSONObject.NULL)
        .put("state", state.name)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        private const val PREFIX = ".export_tx_"
        private const val SUFFIX = ".json"

        fun create(
            jobDir: File,
            role: MediaStoreExportRole,
            frameIndex: Int?,
            displayName: String,
            relativePath: String,
            mimeType: String,
            collectionUri: Uri,
            expectedSizeBytes: Long? = null,
            expectedSha256: String? = null,
            expectedWidth: Int? = null,
            expectedHeight: Int? = null
            ,ownerOperationId: String? = null
        ): MediaStoreExportJournal {
            require(NoFollowFileSystem.isRealDirectory(jobDir.toPath()))
            require(displayName.isNotBlank() && !displayName.contains('/') && !displayName.contains('\\'))
            require(relativePath.isNotBlank() && !relativePath.contains('\\'))
            val now = System.currentTimeMillis()
            return MediaStoreExportJournal(
                exportAttemptId = UUID.randomUUID().toString(),
                runtimeSessionId = KeplerRuntimeSession.id,
                jobIdentity = jobDir.name,
                role = role,
                frameIndex = frameIndex,
                displayName = displayName,
                relativePath = relativePath,
                mimeType = mimeType,
                collectionUri = collectionUri.toString(),
                uri = null,
                expectedSizeBytes = expectedSizeBytes,
                expectedSha256 = expectedSha256,
                expectedWidth = expectedWidth,
                expectedHeight = expectedHeight,
                ownerOperationId = ownerOperationId,
                ownerRuntimeSessionId = KeplerRuntimeSession.id,
                terminalMetadataPersisted = false,
                terminalMetadataPersistedAt = null,
                state = MediaStoreExportState.PREPARED,
                createdAt = now,
                updatedAt = now
            ).writeTo(jobDir)
        }

        fun fileFor(jobDir: File, attemptId: String): File {
            require(attemptId.matches(Regex("[A-Za-z0-9-]+")))
            return File(jobDir, "$PREFIX$attemptId$SUFFIX")
        }

        fun read(jobDir: File, file: File): MediaStoreExportJournal {
            require(file.parentFile?.canonicalFile == jobDir.canonicalFile)
            val json = JSONObject(NoFollowFileSystem.readTextVerified(file))
            val attemptId = json.getString("exportAttemptId")
            require(file.name == "$PREFIX$attemptId$SUFFIX")
            require(json.getString("jobIdentity") == jobDir.name)
            val role = MediaStoreExportRole.valueOf(json.getString("role"))
            val state = MediaStoreExportState.valueOf(json.getString("state"))
            return MediaStoreExportJournal(
                attemptId,
                json.getString("runtimeSessionId"),
                json.getString("jobIdentity"),
                role,
                json.optInt("frameIndex").takeIf { json.has("frameIndex") && !json.isNull("frameIndex") },
                json.getString("displayName"),
                json.getString("relativePath"),
                json.getString("mimeType"),
                json.getString("collectionUri"),
                json.optString("uri").takeIf { it.isNotBlank() && it != "null" },
                json.optLong("expectedSizeBytes").takeIf { json.has("expectedSizeBytes") && !json.isNull("expectedSizeBytes") },
                json.optString("expectedSha256").takeIf { it.isNotBlank() && it != "null" },
                json.optInt("expectedWidth").takeIf { json.has("expectedWidth") && !json.isNull("expectedWidth") },
                json.optInt("expectedHeight").takeIf { json.has("expectedHeight") && !json.isNull("expectedHeight") },
                json.optString("ownerOperationId").takeIf { it.isNotBlank() && it != "null" },
                json.optString("ownerRuntimeSessionId", json.optString("runtimeSessionId")),
                json.optBoolean("terminalMetadataPersisted", false) || json.optString("state") == "TERMINAL_PERSISTED",
                json.optLong("terminalMetadataPersistedAt").takeIf { json.has("terminalMetadataPersistedAt") && !json.isNull("terminalMetadataPersistedAt") },
                state,
                json.getLong("createdAt"),
                json.getLong("updatedAt")
            )
        }

        fun list(jobDir: File): List<MediaStoreExportJournal> =
            NoFollowFileSystem.requireDirectChildren(jobDir)
                .filter { it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
                .mapNotNull { file -> runCatching { read(jobDir, file) }.getOrNull() }

        /** Invalid journals remain evidence and must not disappear from recovery accounting. */
        fun invalidFiles(jobDir: File): List<File> =
            NoFollowFileSystem.requireDirectChildren(jobDir)
                .filter { it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
                .filter { file -> runCatching { read(jobDir, file) }.isFailure }
    }
}
