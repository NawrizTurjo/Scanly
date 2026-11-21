package dev.turjo.paperless.core.model

data class PdfKey(val value: String)

enum class ReaderViewMode { CONTINUOUS, SINGLE_PAGE }

data class TabState(
    val tabId: String,
    val pdfKey: PdfKey,
    val title: String,
    val page: Int = 0,
    val zoom: Float = 1f,
    val scrollOffsetPx: Int = 0,
    val viewMode: ReaderViewMode = ReaderViewMode.CONTINUOUS,
    val nightMode: Boolean = false,
)

data class RecentPdf(
    val pdfKey: PdfKey,
    val title: String,
    val lastOpenedAt: Long,
    val pageCount: Int,
    val lastPage: Int,
)
