package dev.turjo.paperless.feature.editor

import dev.turjo.paperless.core.model.InkRemoval
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.Quad

internal data class PageEdits(
    val quad: Quad?,
    val rotation: Int,
    val filter: PageFilter,
    val brightness: Float,
    val contrast: Float,
    val shadowFix: Boolean,
    val glareFix: Boolean,
    val deskew: Boolean,
    val inkRemoval: InkRemoval?,
) {
    constructor(page: Page) : this(
        page.quad, page.rotation, page.filter, page.brightness, page.contrast, page.shadowFix, page.glareFix,
        page.deskew, page.inkRemoval,
    )

    fun applyTo(page: Page) = page.copy(
        quad = quad, rotation = rotation, filter = filter, brightness = brightness,
        contrast = contrast, shadowFix = shadowFix, glareFix = glareFix, deskew = deskew, inkRemoval = inkRemoval,
    )
}
