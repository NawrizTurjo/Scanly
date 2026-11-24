package dev.turjo.paperless.core.model

/** Bounds are fractions of the displayed page, measured from the top-left. */
data class SignaturePlacement(val signatureId: String, val bounds: PageRect)

fun PageRect.scaleFromUnit(page: PageRect) = PageRect(
    page.left + left * (page.right - page.left),
    page.top + top * (page.bottom - page.top),
    page.left + right * (page.right - page.left),
    page.top + bottom * (page.bottom - page.top),
)
