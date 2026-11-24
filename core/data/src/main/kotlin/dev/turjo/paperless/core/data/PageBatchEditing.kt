package dev.turjo.paperless.core.data

import dev.turjo.paperless.core.model.BatchCrop
import dev.turjo.paperless.core.model.PageBatchEdits
import dev.turjo.paperless.core.model.Quad

suspend fun PageProcessing.applyBatchEdits(
    documents: DocumentRepository,
    pageIds: List<String>,
    edits: PageBatchEdits,
) {
    val rendering = mutableListOf<String>()
    for (id in pageIds.distinct()) {
        awaitIdle(listOf(id))
        val page = documents.getPage(id) ?: continue
        // Card composites are regenerated when their source pages render.
        if (page.isIdComposite) continue
        val quad = when (edits.crop) {
            BatchCrop.AUTO_DETECT -> detectQuad(id)
            BatchCrop.FULL_PHOTO -> page.quad?.takeIf { it == Quad.FULL }
            null -> page.quad
        }
        val updated = page.copy(
            filter = edits.filter ?: page.filter,
            deskew = edits.deskew ?: page.deskew,
            shadowFix = edits.shadowFix ?: page.shadowFix,
            glareFix = edits.glareFix ?: page.glareFix,
            quad = quad,
        )
        if (updated == page) continue
        documents.updatePage(updated)
        enqueueRender(id)
        rendering += id
    }
    if (rendering.isNotEmpty()) awaitIdle(rendering)
}
