package dev.turjo.paperless.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoomTilesTest {
    private val viewport = Size(1000f, 2000f)
    private val page = IntSize(3000, 4000) // fits by width: 1000 / 3000

    @Test fun unzoomedPageFillsTheWidthAndIsCentred() {
        val placement = pagePlacement(viewport, page, scale = 1f, translation = Offset.Zero)
        assertEquals(1f / 3f, placement.pixelScale, 1e-6f)
        assertEquals(0f, placement.left, 1e-3f)
        assertEquals((2000f - 4000f / 3f) / 2f, placement.top, 1e-3f)
    }

    @Test fun zoomedInTheVisibleRegionShrinksAndTheSampleDrops() {
        val placement = pagePlacement(viewport, page, scale = 3f, translation = Offset.Zero)
        val (region, sample) = checkNotNull(visibleRegion(viewport, page, placement))
        // At 3x the page is 3000 px wide on screen: one image pixel per screen pixel, centred.
        assertEquals(1, sample)
        assertEquals(TileRegion(1000, 1000, 2000, 3000), region)
    }

    @Test fun moderateZoomStillSubsamples() {
        val placement = pagePlacement(viewport, page, scale = 1.2f, translation = Offset.Zero)
        val (_, sample) = checkNotNull(visibleRegion(viewport, page, placement))
        assertEquals(2, sample) // 0.4 screen px per image px: every second pixel is enough
    }

    @Test fun panningMovesTheRegionAndClampsAtTheEdge() {
        val placement = pagePlacement(viewport, page, scale = 3f, translation = Offset(1000f, 0f))
        val (region, _) = checkNotNull(visibleRegion(viewport, page, placement))
        assertEquals(0, region.left)
        assertEquals(1000, region.right)
    }

    @Test fun nothingVisibleGivesNoRegion() {
        val placement = pagePlacement(viewport, page, scale = 2f, translation = Offset(5000f, 0f))
        assertNull(visibleRegion(viewport, page, placement))
    }
}
