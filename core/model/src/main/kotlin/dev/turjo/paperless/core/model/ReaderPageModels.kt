package dev.turjo.paperless.core.model

/** Bounds in PDF points, measured from the top-left of the displayed page after crop and rotation. */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class TextMatch(val page: Int, val rects: List<PageRect>)

data class PageLink(val bounds: List<PageRect>, val targetPage: Int?, val uri: String?)

data class OutlineNode(val title: String, val page: Int, val children: List<OutlineNode> = emptyList())
