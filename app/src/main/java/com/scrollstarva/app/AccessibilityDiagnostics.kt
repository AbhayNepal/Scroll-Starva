package com.scrollstarva.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AccessibilityDiagnostic(
    val timestampMillis: Long,
    val details: String
)

object AccessibilityDiagnostics {
    private const val MAX_ENTRIES = 120
    private val entries = ArrayDeque<AccessibilityDiagnostic>()

    @Synchronized
    fun record(details: String) {
        entries.addFirst(AccessibilityDiagnostic(System.currentTimeMillis(), details))
        while (entries.size > MAX_ENTRIES) entries.removeLast()
    }

    @Synchronized
    fun recent(limit: Int = MAX_ENTRIES): List<AccessibilityDiagnostic> =
        entries.take(limit)

    @Synchronized
    fun clear() = entries.clear()

    fun format(items: List<AccessibilityDiagnostic>): String {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return items.joinToString("\n\n") { item ->
            "${formatter.format(Date(item.timestampMillis))}\n${item.details}"
        }
    }
}
