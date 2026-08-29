package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDeleteLogicTest {

    @Test
    fun deleteConfirmDialogLines_containsCountAndBytes() {
        val lines = deleteConfirmDialogLines(3, 1500L)
        assertEquals(5, lines.size)
        assertEquals("3개 작업", lines[0])
        assertEquals("현재 계산 기준 앱 내부 데이터 약 1.5 KB", lines[1])
        assertTrue(lines[2].contains("RAW/YUV"))
        assertTrue(lines[3].contains("시스템 갤러리"))
        assertTrue(lines[4].contains("되돌릴 수 없습니다"))
    }

    @Test
    fun deleteConfirmDialogLines_zeroCountStillFormats() {
        val lines = deleteConfirmDialogLines(0, 0L)
        assertEquals("0개 작업", lines[0])
        assertEquals("현재 계산 기준 앱 내부 데이터 약 0 B", lines[1])
    }

    @Test
    fun deleteConfirmDialogLines_doesNotPromiseExactFreedBytes() {
        val lines = deleteConfirmDialogLines(5, 9999L)
        assertFalse("Dialog must not promise exact freed bytes", lines.any { it.contains("확보") || it.contains("free") })
    }

    @Test
    fun gallerySelectAll_noneToAll_selectsVisible() {
        val visible = listOf("a", "b", "c", "d")
        assertEquals(visible.toSet(), gallerySelectAllSelection(visible, emptySet()))
    }

    @Test
    fun gallerySelectAll_partialToAll_addsMissing() {
        val visible = listOf("a", "b", "c")
        assertEquals(visible.toSet(), gallerySelectAllSelection(visible, setOf("a")))
    }

    @Test
    fun gallerySelectAll_allToNone_clearsVisibleOnly() {
        val visible = listOf("a", "b")
        val selection = setOf("a", "b", "hidden")
        assertEquals(setOf("hidden"), gallerySelectAllSelection(visible, selection))
    }

    @Test
    fun gallerySelectAllLabel_selectAllWhenNotAllSelected() {
        assertEquals("전체 선택", gallerySelectAllLabel(listOf("a", "b"), setOf("a")))
    }

    @Test
    fun gallerySelectAllLabel_deselectAllWhenAllSelected() {
        assertEquals("전체 선택 해제", gallerySelectAllLabel(listOf("a", "b"), setOf("a", "b")))
    }

    @Test
    fun batchDelete_selectionSettlement_preservesUnrelatedIds() {
        val currentSelection = setOf("kept1", "targetA", "targetB", "kept2")
        val targetedIds = setOf("targetA", "targetB")
        val unresolvedIds = setOf("targetB", "targetC")
        val remaining = (currentSelection - targetedIds) + unresolvedIds
        assertEquals(setOf<String>("kept1", "kept2", "targetB", "targetC"), remaining)
    }

    @Test
    fun batchDelete_selectionSettlement_successfulTargetDisappears() {
        val currentSelection = setOf("targetA", "targetB")
        val targetedIds = setOf("targetA", "targetB")
        val unresolvedIds = emptySet<String>()
        val remaining = (currentSelection - targetedIds) + unresolvedIds
        assertEquals(emptySet<String>(), remaining)
    }

    @Test
    fun batchDelete_selectionSettlement_unresolvedTargetRemainsSelected() {
        val currentSelection = setOf("targetA")
        val targetedIds = setOf("targetA")
        val unresolvedIds = setOf("targetA")
        val remaining = (currentSelection - targetedIds) + unresolvedIds
        assertEquals(setOf<String>("targetA"), remaining)
    }

    @Test
    fun batchDelete_selectionSettlement_failedJobDeleteAddsUnresolved() {
        val currentSelection = setOf("kept1")
        val targetedIds = setOf("failedA", "failedB")
        val unresolvedIds = setOf("failedA")
        val remaining = (currentSelection - targetedIds) + unresolvedIds
        assertEquals(setOf<String>("kept1", "failedA"), remaining)
    }
}
