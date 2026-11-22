package dev.turjo.paperless.core.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import dev.turjo.paperless.core.model.PageLink
import dev.turjo.paperless.core.model.PageRect
import java.io.InputStream

internal fun readInternalLinks(input: InputStream): Map<Int, List<PageLink>> =
    PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
        buildMap {
            for ((index, page) in document.pages.withIndex()) {
                val links = page.annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { link ->
                    val destination = link.destination ?: (link.action as? PDActionGoTo)?.destination
                    val target = when (destination) {
                        is PDNamedDestination -> document.documentCatalog.findNamedDestinationPage(destination)
                        is PDPageDestination -> destination
                        else -> null
                    }?.retrievePageNumber() ?: return@mapNotNull null
                    val rectangle = link.rectangle ?: return@mapNotNull null
                    if (target !in 0 until document.numberOfPages) return@mapNotNull null
                    PageLink(listOf(page.displayedRect(rectangle)), target, null)
                }
                if (links.isNotEmpty()) put(index, links)
            }
        }
    }

private fun PDPage.displayedRect(rect: PDRectangle): PageRect {
    val crop = cropBox
    val left = rect.lowerLeftX - crop.lowerLeftX
    val right = rect.upperRightX - crop.lowerLeftX
    val bottom = rect.lowerLeftY - crop.lowerLeftY
    val top = rect.upperRightY - crop.lowerLeftY
    return when (rotation) {
        90 -> PageRect(bottom, left, top, right)
        180 -> PageRect(crop.width - right, bottom, crop.width - left, top)
        270 -> PageRect(crop.height - top, crop.width - right, crop.height - bottom, crop.width - left)
        else -> PageRect(left, crop.height - top, right, crop.height - bottom)
    }
}
