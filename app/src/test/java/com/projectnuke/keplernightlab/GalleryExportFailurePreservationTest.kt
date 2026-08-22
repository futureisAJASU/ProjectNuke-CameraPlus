package com.projectnuke.keplernightlab

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class GalleryExportFailurePreservationTest {

    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    @Test
    fun insertReturnsNull_preservesExplicitFailureReason() {
        mediaStoreInsertNullForTest = true
        try {
            val result = writeGalleryBitmap(
                context = RuntimeEnvironment.getApplication(),
                bitmap = bitmap(),
                displayName = "test",
                format = OutputFormat.JPEG,
                relativeAlbumPath = "Pictures/Kepler",
                quality = 92,
                fallbackUsed = false,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = null
            )
            assertEquals(1, result.candidateFailureReasons.size)
            assertTrue(result.candidateFailureReasons.single().contains("ContentResolver.insert returned null"))
            assertTrue(result.candidateFailureReasons.single().contains("displayName=test"))
        } finally {
            mediaStoreInsertNullForTest = false
        }
    }

    @Test
    fun pendingClearUpdateCountZero_preservesExplicitFailureReasonAndAllowsFallbackWhenProviderProvesNotPublic() {
        mediaStoreUpdateCountForTest = { 0 }
        mediaStorePublicCommitStateForTest = { false }
        try {
            val result = exportNightFusionBitmapToGallery(
                context = RuntimeEnvironment.getApplication(),
                bitmap = bitmap(),
                displayNameBase = "test",
                requestedFormat = OutputFormat.HEIF,
                relativeAlbumPath = "Pictures/Kepler",
                quality = 92,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = null
            )
            assertFalse(result.success)
            assertEquals(GalleryExportCommitState.NOT_COMMITTED, result.publicCommitState)
            assertTrue(result.attemptedFormats.size >= 2)
            assertTrue(result.candidateFailureReasons.any { it.contains("IS_PENDING clear updated 0 rows") })
        } finally {
            mediaStoreUpdateCountForTest = null
            mediaStorePublicCommitStateForTest = null
        }
    }

    @Test
    fun ordinaryOpenOutputStreamException_preservesClassAndMessage() {
        mediaStoreOpenOutputStreamFailureForTest = { IOException("stream closed") }
        try {
            val result = writeGalleryBitmap(
                context = RuntimeEnvironment.getApplication(),
                bitmap = bitmap(),
                displayName = "test",
                format = OutputFormat.JPEG,
                relativeAlbumPath = "Pictures/Kepler",
                quality = 92,
                fallbackUsed = false,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = null
            )
            assertEquals(1, result.candidateFailureReasons.size)
            assertTrue(result.candidateFailureReasons.single().contains("IOException"))
            assertTrue(result.candidateFailureReasons.single().contains("stream closed"))
        } finally {
            mediaStoreOpenOutputStreamFailureForTest = null
        }
    }

    @Test
    fun fallbacks_preserveDistinctPerFormatFailureReasons() {
        var callCount = 0
        mediaStoreInsertNullForTest = true
        try {
            val result = exportNightFusionBitmapToGallery(
                context = RuntimeEnvironment.getApplication(),
                bitmap = bitmap(),
                displayNameBase = "test",
                requestedFormat = OutputFormat.HEIF,
                relativeAlbumPath = "Pictures/Kepler",
                quality = 92,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = null
            )
            assertFalse(result.success)
            assertEquals(GalleryExportCommitState.NOT_COMMITTED, result.publicCommitState)
            assertTrue(result.attemptedFormats.contains(OutputFormat.HEIF))
            assertTrue(result.attemptedFormats.contains(OutputFormat.JPEG))
            assertTrue(result.candidateFailureReasons.any { it.contains("HEIF") })
            assertTrue(result.candidateFailureReasons.any { it.contains("JPEG") })
        } finally {
            mediaStoreInsertNullForTest = false
        }
    }

    @Test
    fun committedUnverifiedResult_keepsCurrentFormatFailureReason() {
        mediaStoreInsertNullForTest = false
        mediaStoreUpdateCountForTest = { 1 }
        mediaStorePublicCommitStateForTest = { true }
        galleryExportVerificationForTest = GalleryExportVerification.PermanentFailure("verification failed")
        try {
            val result = writeGalleryBitmap(
                context = RuntimeEnvironment.getApplication(),
                bitmap = bitmap(),
                displayName = "test",
                format = OutputFormat.JPEG,
                relativeAlbumPath = "Pictures/Kepler",
                quality = 92,
                fallbackUsed = false,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = null
            )
            assertFalse(result.success)
            assertEquals(GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED, result.publicCommitState)
            assertEquals(1, result.candidateFailureReasons.size)
            assertTrue(result.candidateFailureReasons.single().contains("verification failed"))
        } finally {
            mediaStoreUpdateCountForTest = null
            mediaStorePublicCommitStateForTest = null
            galleryExportVerificationForTest = null
        }
    }

    @Test
    fun unknownCommit_keepsCurrentFormatReasonAndDoesNotFallback() {
        var callCount = 0
        mediaStoreUpdateCountForTest = {
            callCount++
            if (callCount == 1) 0 else 1
        }
        mediaStorePublicCommitStateForTest = { null }
        try {
            val result = exportNightFusionBitmapToGallery(
                context = RuntimeEnvironment.getApplication(),
                bitmap = bitmap(),
                displayNameBase = "test",
                requestedFormat = OutputFormat.JPEG,
                relativeAlbumPath = "Pictures/Kepler",
                quality = 92,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = null
            )
            assertFalse(result.success)
            assertEquals(GalleryExportCommitState.UNKNOWN, result.publicCommitState)
            assertEquals(1, result.attemptedFormats.size)
            assertEquals(OutputFormat.JPEG, result.attemptedFormats.single())
            assertTrue(result.candidateFailureReasons.any { it.contains("public commit state unknown") })
        } finally {
            mediaStoreUpdateCountForTest = null
            mediaStorePublicCommitStateForTest = null
        }
    }
}
