package dev.turjo.paperless.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PageParamsTest {
    private val page = Page(
        "page", "document", 0, PageMode.NORMAL, "orig.jpg", 960, 480, null, 0, PageFilter.ORIGINAL,
        0f, 0f, false, false, null, null, false, 123L,
    )
    private val crop = Quad(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun quadRoundTripsInCornerOrder() {
        val points = floatArrayOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
        assertArrayEquals(points, Quad.fromArray(points).toArray(), 0f)
    }

    @Test
    fun hashChangesForEveryProcessingParameter() {
        val baseline = page.paramsHash()
        val variants = listOf(
            page.copy(quad = crop), page.copy(rotation = 90), page.copy(filter = PageFilter.AUTO),
            page.copy(brightness = 0.25f), page.copy(contrast = -0.25f), page.copy(shadowFix = true),
            page.copy(glareFix = true),
        )

        variants.forEach { assertNotEquals(baseline, it.paramsHash()) }
        crop.toArray().indices.forEach { i ->
            val altered = crop.toArray().apply { this[i] += 0.05f }
            assertNotEquals(page.copy(quad = crop).paramsHash(), page.copy(quad = Quad.fromArray(altered)).paramsHash())
        }
    }

    @Test
    fun hashChangesWithSignatureImageAndBounds() {
        val placement = SignaturePlacement("first", PageRect(0.25f, 0.5f, 0.5f, 0.625f))
        val signed = page.copy(signatures = listOf(placement))
        assertNotEquals(page.paramsHash(), signed.paramsHash())
        assertNotEquals(signed.paramsHash(), signed.copy(signatures = listOf(placement.copy(signatureId = "second"))).paramsHash())
        val moved = placement.copy(bounds = PageRect(0.5f, 0.5f, 0.75f, 0.625f))
        assertNotEquals(signed.paramsHash(), signed.copy(signatures = listOf(moved)).paramsHash())
    }

    @Test
    fun hashIgnoresMetadataAndTreatsFullQuadAsUncropped() {
        val hash = page.paramsHash()
        val moved = page.copy(
            id = "different", documentId = "target", index = 3, quad = Quad.FULL,
            processedPath = "proc.jpg", processedHash = hash, glareWarning = true,
        )

        assertEquals(hash, page.copy().paramsHash())
        assertEquals(hash, moved.paramsHash())
    }
}
