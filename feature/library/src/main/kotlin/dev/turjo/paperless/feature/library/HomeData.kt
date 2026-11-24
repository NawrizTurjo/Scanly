package dev.turjo.paperless.feature.library

import android.content.Context
import android.content.SharedPreferences
import dev.turjo.paperless.core.model.DocumentSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class HomeShortcut(val id: String, val title: Int, val icon: Int)

internal const val ALL_TOOLS = "all_tools"
internal const val SHORTCUTS_KEY = "shortcuts"
internal val defaultShortcuts = listOf(
    "scan", "import_images", "tool:FROM_IMAGES", "open_pdf", "tool:MERGE",
    "tool:COMPRESS", "id_card", "book", ALL_TOOLS,
)

internal fun decodeShortcuts(saved: String?, catalog: Set<String>): List<String> =
    (saved?.split(',') ?: defaultShortcuts)
        .filter { it != ALL_TOOLS && it in catalog }.distinct() + ALL_TOOLS

internal fun encodeShortcuts(ids: List<String>): String =
    (ids.filter { it != ALL_TOOLS }.distinct() + ALL_TOOLS).joinToString(",")

private const val OPENED_PREFIX = "opened:"
internal fun Context.homePreferences() = getSharedPreferences("home", Context.MODE_PRIVATE)

fun recordHomeDocumentOpen(context: Context, id: String, openedAt: Long) {
    context.homePreferences().edit().putLong(OPENED_PREFIX + id, openedAt).apply()
}

internal fun observeHomeDocumentOpens(context: Context) = callbackFlow {
    val preferences = context.homePreferences()
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith(OPENED_PREFIX) == true) trySend(preferences)
    }
    preferences.registerOnSharedPreferenceChangeListener(listener)
    trySend(preferences)
    awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
}.map { preferences ->
    preferences.all.mapNotNull { (key, value) ->
        if (key.startsWith(OPENED_PREFIX) && value is Long) key.removePrefix(OPENED_PREFIX) to value else null
    }.toMap()
}.flowOn(Dispatchers.IO)

internal data class RecentDocument(val document: DocumentSummary, val activityAt: Long)

internal fun recentDocuments(documents: List<DocumentSummary>, opened: Map<String, Long>): List<RecentDocument> =
    documents.map { RecentDocument(it, maxOf(it.updatedAt, opened[it.id] ?: 0L)) }
        .sortedWith(compareByDescending<RecentDocument> { it.activityAt }.thenBy { it.document.id })
        .take(6)
