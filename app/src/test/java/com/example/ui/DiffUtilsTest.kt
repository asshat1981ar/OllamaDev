package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffUtilsTest {

    @Test
    fun computeSimpleLineDiff_identicalContent_allUnchanged() {
        val text = "line one\nline two\nline three"
        val diff = computeSimpleLineDiff(text, text)

        assertEquals(3, diff.size)
        assertEquals(listOf(DiffLineType.UNCHANGED, DiffLineType.UNCHANGED, DiffLineType.UNCHANGED), diff.map { it.type })
    }

    @Test
    fun computeSimpleLineDiff_pureAddition_appendsAddedLines() {
        val original = "line one\nline two"
        val proposed = "line one\nline two\nline three"

        val diff = computeSimpleLineDiff(original, proposed)

        assertEquals(3, diff.size)
        assertEquals(DiffLineType.UNCHANGED, diff[0].type)
        assertEquals(DiffLineType.UNCHANGED, diff[1].type)
        assertEquals(DiffLineType.ADDED, diff[2].type)
        assertEquals("line three", diff[2].text)
    }

    @Test
    fun computeSimpleLineDiff_pureRemoval_marksRemovedLines() {
        val original = "line one\nline two\nline three"
        val proposed = "line one\nline three"

        val diff = computeSimpleLineDiff(original, proposed)

        assertEquals(3, diff.size)
        assertEquals(DiffLineType.UNCHANGED, diff[0].type)
        assertEquals(DiffLineType.REMOVED, diff[1].type)
        assertEquals("line two", diff[1].text)
        assertEquals(DiffLineType.UNCHANGED, diff[2].type)
    }

    @Test
    fun computeSimpleLineDiff_reorderedLines_reportsAsRemoveAndAddNotUnchanged() {
        val original = "alpha\nbeta"
        val proposed = "beta\nalpha"

        val diff = computeSimpleLineDiff(original, proposed)

        // "alpha" and "beta" both survive as individual lines but out of order, so the LCS is
        // length 1 (either line alone) -- the diff must show a real removal + addition, not
        // silently treat the swap as two unchanged lines.
        assertEquals(1, diff.count { it.type == DiffLineType.UNCHANGED })
        assertEquals(1, diff.count { it.type == DiffLineType.REMOVED })
        assertEquals(1, diff.count { it.type == DiffLineType.ADDED })
    }

    @Test
    fun computeSimpleLineDiff_emptyOriginal_allAdded() {
        val diff = computeSimpleLineDiff("", "new content")
        assertEquals(1, diff.size)
        assertEquals(DiffLineType.ADDED, diff[0].type)
    }

    @Test
    fun computeSimpleLineDiff_emptyProposed_allRemoved() {
        val diff = computeSimpleLineDiff("old content", "")
        assertEquals(1, diff.size)
        assertEquals(DiffLineType.REMOVED, diff[0].type)
    }
}
