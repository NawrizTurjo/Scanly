package dev.turjo.paperless

import dev.turjo.paperless.feature.library.HomeShortcut
import dev.turjo.paperless.feature.tools.toolShortcuts
import dev.turjo.paperless.core.designsystem.R as DesignR
import dev.turjo.paperless.feature.camera.R as CameraR

private const val TOOL_PREFIX = "tool:"

internal val homeShortcuts = listOf(
    HomeShortcut("scan", R.string.scan_tile, DesignR.drawable.ic_scan),
    HomeShortcut("import_images", R.string.home_import_images, CameraR.drawable.camera_ic_gallery),
    HomeShortcut("open_pdf", R.string.home_open_pdf, DesignR.drawable.ic_reader),
    HomeShortcut("id_card", CameraR.string.camera_mode_id, R.drawable.ic_scan_id),
    HomeShortcut("book", CameraR.string.camera_mode_book, DesignR.drawable.ic_reader),
    HomeShortcut("whiteboard", CameraR.string.camera_mode_whiteboard, R.drawable.ic_scan_board),
) + toolShortcuts.map { HomeShortcut(TOOL_PREFIX + it.id, it.title, it.icon) }

internal fun homeShortcutRoute(id: String): Route? = when (id) {
    "scan", "id_card", "book", "whiteboard" -> CameraRoute()
    "all_tools" -> ToolsRoute
    else -> if (id.startsWith(TOOL_PREFIX)) ToolRoute(id.removePrefix(TOOL_PREFIX)) else null
}
