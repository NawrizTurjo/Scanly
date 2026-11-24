package dev.turjo.paperless

import dev.turjo.paperless.feature.library.HomeShortcut
import dev.turjo.paperless.feature.tools.toolShortcuts
import dev.turjo.paperless.core.designsystem.R as DesignR
import dev.turjo.paperless.feature.camera.R as CameraR
import dev.turjo.paperless.feature.editor.R as EditorR

private const val TOOL_PREFIX = "tool:"

internal val homeShortcuts = listOf(
    HomeShortcut("scan", R.string.scan_tile, DesignR.drawable.ic_scan),
    HomeShortcut("import_images", R.string.home_import_images, CameraR.drawable.camera_ic_gallery),
    HomeShortcut("open_pdf", R.string.home_open_pdf, DesignR.drawable.ic_reader),
    HomeShortcut("id_card", CameraR.string.camera_mode_id, R.drawable.ic_scan_id),
    HomeShortcut("sign_pdf", R.string.home_sign_pdf, R.drawable.ic_sign_pdf),
    HomeShortcut("extract_text", R.string.home_extract_text, EditorR.drawable.ic_recognized_text),
    HomeShortcut("book", CameraR.string.camera_mode_book, DesignR.drawable.ic_reader),
    HomeShortcut("whiteboard", CameraR.string.camera_mode_whiteboard, R.drawable.ic_scan_board),
) + toolShortcuts.map { HomeShortcut(TOOL_PREFIX + it.id, it.title, it.icon) }

internal fun homeShortcutRoute(id: String): Route? = when (id) {
    "scan" -> CameraRoute()
    "id_card", "book", "whiteboard" -> CameraRoute(startMode = id)
    "extract_text" -> ChooseScanRoute
    "all_tools" -> ToolsRoute
    else -> if (id.startsWith(TOOL_PREFIX)) ToolRoute(id.removePrefix(TOOL_PREFIX)) else null
}
