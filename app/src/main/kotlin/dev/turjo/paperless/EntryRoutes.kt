package dev.turjo.paperless

import android.content.Context
import android.content.Intent
import dev.turjo.paperless.core.model.AppLock

internal const val ROUTE_EXTRA = "dev.turjo.paperless.ROUTE"
internal const val CAMERA_BATCH = "camera_batch"
private const val DOCUMENT_PREFIX = "document:"

internal fun entryIntent(context: Context, route: String) =
    Intent(Intent.ACTION_VIEW, null, context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(ROUTE_EXTRA, route)

internal fun documentEntry(id: String) = "$DOCUMENT_PREFIX$id"

internal fun startBackStack(value: String?): List<Route> {
    val destination = when {
        value == CAMERA_BATCH -> CameraRoute()
        value == "camera_single" -> CameraRoute(single = true)
        value != null && value.startsWith(DOCUMENT_PREFIX) -> value.removePrefix(DOCUMENT_PREFIX)
            .takeIf { it.isNotBlank() }?.let(::DocumentRoute)
        else -> null
    }
    return listOfNotNull(LibraryRoute, destination)
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
