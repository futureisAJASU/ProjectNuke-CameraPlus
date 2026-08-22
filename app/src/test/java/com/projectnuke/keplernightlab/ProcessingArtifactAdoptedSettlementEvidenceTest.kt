package com.projectnuke.keplernightlab

import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADOPTED-path settlement evidence contract: when post-adoption cleanup
 * cannot delete a resource, the report must record DELETE_FAILED truthfully
 * and must never append a synthetic ABSENT claim for the same resource,
 * because no second successful deletion proved absence.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessingArtifactAdoptedSettlementEvidenceTest {

    private fun writeBytes(file: File, payload: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(payload)
            output.flush()
            output.fd.sync()
        }
    }

    @Test
    fun adoptedCleanupDeleteFailure_doesNotAlsoClaimSyntheticAbsent() {
        val dir = createTempDirectory("kepler-adopted-evidence").toFile()
        try {
            // Pre-existing final artifact forces the prior-backup path so the
            // ADOPTED catch block runs while the injected delete Error fires.
            val finalFile = File(dir, "result.bin")
            writeBytes(finalFile, "prior".toByteArray())

            val injected = AssertionError("injected prior backup cleanup delete failure")
            var reports: List<ProcessingArtifactSettlementReport> = emptyList()

            val thrown = assertThrows(AssertionError::class.java) {
                commitProcessingArtifact(
                    finalFile = finalFile,
                    writeTemp = { temp -> writeBytes(temp, "current".toByteArray()) },
                    verifyFinal = {
                        processingArtifactDeleteErrorForTest = injected
                    },
                    onSettlement = { report -> reports += report }
                )
            }

            processingArtifactDeleteErrorForTest = null

            assertSame(injected, thrown)
            assertEquals(1, reports.size)
            val report = reports.single()
            assertEquals(ProcessingArtifactState.CLEANUP_FAILED, report.state)
            assertNotNull(report.cleanupFailure)
            assertSame(injected, report.cleanupFailure)

            val tempRecords = report.settlements.filter { it.role == ProcessingArtifactResourceRole.TEMPORARY }
            assertTrue(
                "DELETE_FAILED must be recorded for the temp resource: ${report.settlements}",
                tempRecords.any { it.status == ProcessingArtifactSettlementStatus.DELETE_FAILED }
            )
            assertTrue(
                "no synthetic ABSENT may be claimed without a proven deletion: ${report.settlements}",
                tempRecords.none { it.status == ProcessingArtifactSettlementStatus.ABSENT }
            )

            // ADOPTED ownership semantics are unchanged: the new final stays
            // adopted (no rollback) and the journal is retained.
            assertTrue(finalFile.isFile)
            assertEquals(7L, finalFile.length())
            val journals = ProcessingArtifactJournal.scan(dir).validJournals
            assertTrue(journals.any { it.second.state == ProcessingArtifactJournalState.ADOPTED })
        } finally {
            processingArtifactDeleteErrorForTest = null
            dir.deleteRecursively()
        }
    }
}
