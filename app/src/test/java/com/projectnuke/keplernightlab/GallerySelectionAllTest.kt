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
        assertEquals(setOf("a", "b", "c"), gallerySelectAllSelection(visible, setOf("b")))
    }

    @Test
    fun selectAll_whenEverythingVisibleIsSelected_becomesDeselectAll() {
        val visible = listOf("a", "b")
        assertEquals(emptySet<String>(), gallerySelectAllSelection(visible, setOf("a", "b")))
        assertEquals(setOf("hidden"), gallerySelectAllSelection(visible, setOf("a", "b", "hidden")))
    }

    @Test
    fun selectAllLabel_togglesBetweenSelectAndDeselect() {
        assertEquals("전체 선택", gallerySelectAllLabel(listOf("a"), emptySet()))
        assertEquals("전체 선택", gallerySelectAllLabel(listOf("a", "b"), setOf("a")))
        assertEquals("전체 선택 해제", gallerySelectAllLabel(listOf("a", "b"), setOf("a", "b")))
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

    @Test
    fun selectAll_fromEmptySelection_addsAllVisibleIds() {
        val visible = listOf("x", "y", "z")
        assertEquals(setOf("x", "y", "z"), gallerySelectAllSelection(visible, emptySet()))
    }

    @Test
    fun selectAll_fromPartialSelection_completesToFull() {
        val visible = listOf("a", "b", "c")
        assertEquals(setOf("a", "b", "c"), gallerySelectAllSelection(visible, setOf("a", "c")))
    }

    @Test
    fun selectAll_fromFullSelection_clearsOnlyVisible() {
        val visible = listOf("a", "b")
        val crossTab = setOf("a", "b", "sourceOnly1")
        assertEquals(setOf("sourceOnly1"), gallerySelectAllSelection(visible, crossTab))
    }

    @Test
    fun infoTabSelectAll_canSelectSourceOnlyJobsHiddenFromPhotos() {
        val allJobs = listOf("photo1", "sourceOnly1")
        val result = gallerySelectAllSelection(allJobs, emptySet())
        assertEquals(setOf("photo1", "sourceOnly1"), result)
    }

    @Test
    fun photosTabSelectAll_neverSelectsInfoOnlyHiddenJobs() {
        val photosVisible = listOf("photo1", "photo2")
        val infoOnlyIds = listOf("sourceOnly1", "failedJob1")
        val result = gallerySelectAllSelection(photosVisible, emptySet())
        assertEquals(setOf("photo1", "photo2"), result)
        infoOnlyIds.forEach { assertFalse("Hidden id $it must not leak into Photos selection", result.contains(it)) }
    }

    @Test
    fun selection_afterRefresh_intersectsWithRemainingJobs() {
        val selectedBefore = setOf("a", "b", "c")
        val remaining = listOf("a", "c")
        val afterRefresh = selectedBefore.intersect(remaining.toSet())
        assertEquals(setOf("a", "c"), afterRefresh)
    }
}
