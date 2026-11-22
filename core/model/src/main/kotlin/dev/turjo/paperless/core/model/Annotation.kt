package dev.turjo.paperless.core.model

import android.graphics.PointF

enum class AnnotationType { INK, HIGHLIGHT, UNDERLINE, STRIKE, NOTE }

/** Geometry is in displayed PDF points, measured from the top-left after crop and rotation. */
data class Annotation(
    val id: String,
    val pdfKey: PdfKey,
    val page: Int,
    val type: AnnotationType,
    val color: Int,
    val width: Float,
    val points: List<PointF> = emptyList(),
    val rects: List<PageRect> = emptyList(),
    val note: String? = null,
)
