package dev.turjo.paperless

import android.content.Context
import android.content.Intent
import dev.turjo.paperless.core.model.AppLock
import dev.turjo.paperless.core.model.PdfKey
import kotlinx.coroutines.CancellationException

internal const val PDF_MIME_TYPE = "application/pdf"

internal fun acceptsFileIntent(action: String?, mimeType: String?): Boolean = when (action) {
    Intent.ACTION_VIEW -> mimeType == PDF_MIME_TYPE
    Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE ->
        mimeType == PDF_MIME_TYPE || mimeType?.startsWith("image/") == true
    else -> false
}

internal suspend fun <T : Any> pdfEntryEvents(
    action: String?,
    mimeType: String?,
    data: T?,
    streams: List<T>,
    scheme: (T) -> String?,
    register: suspend (T) -> Pair<PdfKey, String>,
): List<EntryEvent> {
    if (mimeType != PDF_MIME_TYPE || !acceptsFileIntent(action, mimeType)) return emptyList()
    val sources = if (action == Intent.ACTION_VIEW) listOfNotNull(data) else streams
    val uris = sources.distinct().filter {
        val uriScheme = scheme(it)
        uriScheme == "content" || uriScheme == "file"
    }
    if (uris.isEmpty()) return listOf(EntryEvent(message = R.string.pdf_open_error))
    return uris.map { uri ->
        try {
            val (key, title) = register(uri)
            EntryEvent(pdfKey = key, pdfTitle = title)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EntryEvent(message = R.string.pdf_open_error)
        }
    }
}

internal const val ROUTE_EXTRA = "dev.turjo.paperless.ROUTE"
internal const val CAMERA_BATCH = "camera_batch"
internal const val TOOLS_ENTRY = "tools"
private const val DOCUMENT_PREFIX = "document:"

internal fun entryIntent(context: Context, route: String) =
    Intent(Intent.ACTION_VIEW, null, context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(ROUTE_EXTRA, route)

internal fun documentEntry(id: String) = "$DOCUMENT_PREFIX$id"

internal fun startBackStack(value: String?): List<Route> {
    val destination = when {
        value == TOOLS_ENTRY -> ToolsRoute
        value == CAMERA_BATCH -> CameraRoute()
        value == "camera_single" -> CameraRoute(single = true)
        value != null && value.startsWith(DOCUMENT_PREFIX) -> value.removePrefix(DOCUMENT_PREFIX)
            .takeIf { it.isNotBlank() }?.let(::DocumentRoute)
        else -> null
    }
    return when (destination) {
        null -> listOf(HomeRoute)
        ToolsRoute -> listOf(HomeRoute, ToolsRoute)
        else -> listOf(LibraryRoute, destination)
    }
}

internal fun shouldLock(
    mode: AppLock,
    minutes: Int,
    backgroundedAtMs: Long?,
    nowMs: Long,
    coldStart: Boolean,
): Boolean {
    if (mode == AppLock.OFF) return false
    if (coldStart) return true
    if (backgroundedAtMs == null) return false
    return mode == AppLock.ON_OPEN || nowMs - backgroundedAtMs >= minutes * 60_000L
}
