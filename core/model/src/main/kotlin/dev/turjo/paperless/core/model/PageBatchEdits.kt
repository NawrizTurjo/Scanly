package dev.turjo.paperless.core.model

enum class BatchCrop { AUTO_DETECT, FULL_PHOTO }

data class PageBatchEdits(
    val filter: PageFilter? = null,
    val deskew: Boolean? = null,
    val shadowFix: Boolean? = null,
    val glareFix: Boolean? = null,
    val crop: BatchCrop? = null,
)
