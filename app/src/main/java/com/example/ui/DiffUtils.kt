package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class DiffLineType { ADDED, REMOVED, UNCHANGED }

data class DiffLine(val text: String, val type: DiffLineType)

/**
 * Small line-level LCS diff -- no external dependency, deliberately not a full
 * Myers diff. Good enough to show real added/removed lines for an agent's proposed
 * file change instead of the old unreadable "--- ORIGINAL ---\n...--- PROPOSED ---\n..."
 * text-dump.
 */
fun computeSimpleLineDiff(original: String, proposed: String): List<DiffLine> {
    // "".split("\n") returns [""] (one empty-string element), not an empty list -- guard
    // explicitly so an empty original/proposed is treated as zero lines, not one blank line.
    val a = if (original.isEmpty()) emptyList() else original.split("\n")
    val b = if (proposed.isEmpty()) emptyList() else proposed.split("\n")
    val n = a.size
    val m = b.size

    // Standard LCS length table.
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }

    val result = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                result += DiffLine(a[i], DiffLineType.UNCHANGED)
                i++; j++
            }
            lcs[i + 1][j] >= lcs[i][j + 1] -> {
                result += DiffLine(a[i], DiffLineType.REMOVED)
                i++
            }
            else -> {
                result += DiffLine(b[j], DiffLineType.ADDED)
                j++
            }
        }
    }
    while (i < n) { result += DiffLine(a[i], DiffLineType.REMOVED); i++ }
    while (j < m) { result += DiffLine(b[j], DiffLineType.ADDED); j++ }
    return result
}

@Composable
fun DiffView(diffLines: List<DiffLine>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(diffLines) { line ->
            val (background, prefix, textColor) = when (line.type) {
                DiffLineType.ADDED -> Triple(Color(0xFF4CAF50).copy(alpha = 0.15f), "+ ", Color(0xFF4ADE80))
                DiffLineType.REMOVED -> Triple(Color(0xFFEF4444).copy(alpha = 0.15f), "- ", Color(0xFFF87171))
                DiffLineType.UNCHANGED -> Triple(Color.Transparent, "  ", Color(0xFFECEFF1))
            }
            Column(modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp, vertical = 1.dp)) {
                Text(
                    text = prefix + line.text,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, color = textColor, fontSize = 10.sp)
                )
            }
        }
    }
}
