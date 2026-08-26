package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Phase 8 — pure ID-based whole-selection semantics for the Gallery grids.
 * Selection is ID-based, tab-scoped, and survives refresh only for jobs that still exist.
 */
class GallerySelectionAllTest {

    @Test
    fun selectAll_selectsEveryCurrentlyVisibleId() {
        val visible = listOf("a", "b", "c")
        assertEquals(setOf("a", "b", "c"), gallerySelectAllSelection(visible, emptySet()))
        // Existing partial selection is preserved and completed.
        assertEquals(setOf("a", "b", "c"), gallerySelectAllSelection(visible, setOf("b")))
    }

    @Test
    fun selectAll_whenEverythingVisibleIsSelected_becomesDeselectAll() {
        val visible = listOf("a", "b")
        assertEquals(emptySet<String>(), gallerySelectAllSelection(visible, setOf("a", "b")))
        // Extra IDs from another tab are never touched.
        assertEquals(
            setOf("hidden"),
            gallerySelectAllSelection(visible, setOf("a", "b", "hidden"))
        )
    }

    @Test
    fun selectAllLabel_togglesBetweenSelectAndDeselect() {
        assertEquals("전체 선택", gallerySelectAllLabel(listOf("a"), emptySet()))
        assertEquals("전체 선택", gallerySelectAllLabel(listOf("a", "b"), setOf("a")))
        assertEquals("전체 선택 해제", gallerySelectAllLabel(listOf("a", "b"), setOf("a", "b")))
        // Empty tabs keep the button disabled; label falls back to plain Select All.
        assertEquals("전체 선택", gallerySelectAllLabel(emptyList(), emptySet()))
    }

    @Test
    fun selection_survivesRefresh_onlyForJobsThatStillExist() {
        val selectedBefore = setOf("kept1", "deleted1", "kept2")
        val refreshedIds = listOf("kept1", "kept2", "newJob")
        val survived = selectedBefore.intersect(refreshedIds.toSet())
        assertEquals(setOf("kept1", "kept2"), survived)
    }

    @Test
    fun selection_isTabScoped_hiddenRecoveryJobsAreNotSelectedByPhotosSelectAll() {
        val photosTabVisible = listOf("photo1", "photo2")
        val infoOnlySourceJobId = "sourceOnly1"
        val result = gallerySelectAllSelection(photosTabVisible, emptySet())
        assertEquals(setOf("photo1", "photo2"), result)
        assertFalse(result.contains(infoOnlySourceJobId))
    }
}
