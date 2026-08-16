package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReprocessVerificationDebtTest {
    @Test
    fun committedUnverifiedOutcomeCarriesExactDebtWarning() {
        assertEquals(
            "Public export committed; reprocess verification incomplete",
            reprocessVerificationDebtWarning(effectiveExportVerified = false, publicCommitted = true)
        )
    }

    @Test
    fun verifiedOutcomeCarriesNoDebtWarning() {
        assertNull(reprocessVerificationDebtWarning(effectiveExportVerified = true, publicCommitted = true))
        assertNull(reprocessVerificationDebtWarning(effectiveExportVerified = true, publicCommitted = false))
    }

    @Test
    fun uncommittedOutcomeCarriesNoDebtWarning() {
        assertNull(reprocessVerificationDebtWarning(effectiveExportVerified = false, publicCommitted = false))
    }
}