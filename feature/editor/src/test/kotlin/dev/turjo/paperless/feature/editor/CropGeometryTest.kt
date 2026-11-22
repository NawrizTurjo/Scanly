package dev.turjo.paperless.feature.editor

import dev.turjo.paperless.core.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropGeometryTest {
    @Test
    fun letterboxedFitMapsBetweenViewAndUprightImage() {
        val fit = CropFit(viewWidth = 300f, viewHeight = 300f, imageWidth = 600f, imageHeight = 300f)

        assertPoint(0f, 75f, fit.toView(CropPoint(0f, 0f)))
        assertPoint(300f, 225f, fit.toView(CropPoint(600f, 300f)))
        assertPoint(150f, 240f, fit.toImage(CropPoint(75f, 195f)))
    }

    @Test
    fun edgeMidpointDragMovesBothCornersAlongEdgeNormal() {
        val quad = Quad(20f, 20f, 60f, 50f, 60f, 90f, 20f, 90f)
        assertPoint(40f, 35f, quad.handles()[4])

        // The drag includes (8, 6) along the edge and (-6, 8) along its normal.
        val moved = requireNotNull(quad.moveHandle(4, CropPoint(2f, 14f), 100f, 100f))

        val corners = moved.points()
        assertPoint(14f, 28f, corners[0])
        assertPoint(54f, 58f, corners[1])
        assertEquals(quad.points().drop(2), corners.drop(2))
    }

    @Test
    fun cornerDragRejectsConcaveOrDegenerateQuad() {
        val quad = fullCrop(100f, 100f)

        assertNull(quad.moveHandle(0, CropPoint(65f, 65f), 100f, 100f))
        assertNull(quad.moveHandle(0, CropPoint(50f, 50f), 100f, 100f))
        assertPoint(
            15f, 15f,
            requireNotNull(quad.moveHandle(0, CropPoint(15f, 15f), 100f, 100f)).points().first(),
        )
    }

    @Test
    fun dragsPastTheBorderPinToIt() {
        val quad = fullCrop(100f, 100f)

        assertEquals(quad, quad.moveHandle(0, CropPoint(-1f, 0f), 100f, 100f))
        assertEquals(quad, quad.moveHandle(5, CropPoint(1f, 0f), 100f, 100f))
        assertPoint(0f, 20f, requireNotNull(quad.moveHandle(0, CropPoint(-30f, 20f), 100f, 100f)).points().first())
    }

    @Test
    fun cornersJustOutsideTheImageStillDrag() {
        // Detection scaled to full resolution can put a border corner a hair past the image (close-up photos).
        val quad = Quad(-0.0004f, 0f, 4000.0005f, 0f, 4000.0005f, 3000.0002f, 0f, 3000.0002f)

        val moved = requireNotNull(quad.moveHandle(0, CropPoint(120f, 80f), 4000f, 3000f))

        assertPoint(120f, 80f, moved.points()[0])
        assertPoint(4000f, 3000f, moved.points()[2])
    }

    @Test
    fun clampKeepsPointsOnImageBounds() {
        assertPoint(0f, 75f, CropPoint(-12f, 90f).clamp(100f, 75f))
        assertPoint(100f, 0f, CropPoint(110f, -5f).clamp(100f, 75f))
        assertPoint(40f, 30f, CropPoint(40f, 30f).clamp(100f, 75f))
    }

    private fun assertPoint(x: Float, y: Float, actual: CropPoint) {
        assertEquals(x, actual.x, 0.001f)
        assertEquals(y, actual.y, 0.001f)
    }

    @Test
    fun splitHalvesShareTheSplitLineAndKeepCornerOrder() {
        val quad = Quad(0f, 0f, 200f, 10f, 210f, 110f, 10f, 100f)

        val (left, right) = quad.splitHalves(SplitLine(0.5f, 0.25f))

        assertPoint(100f, 5f, left.points()[1])
        assertPoint(60f, 102.5f, left.points()[2])
        assertEquals(left.points()[1], right.points()[0])
        assertEquals(left.points()[2], right.points()[3])
        assertEquals(quad.points()[0], left.points()[0])
        assertEquals(quad.points()[2], right.points()[2])
    }

    @Test
    fun edgeFractionProjectsOntoTheEdgeAndAvoidsCorners() {
        val quad = fullCrop(200f, 100f)

        assertEquals(0.25f, quad.edgeFraction(CropPoint(50f, 40f), topEdge = true), 1e-4f)
        assertEquals(0.75f, quad.edgeFraction(CropPoint(150f, 60f), topEdge = false), 1e-4f)
        assertEquals(SplitLine.MIN, quad.edgeFraction(CropPoint(-30f, 0f), topEdge = true))
        assertEquals(SplitLine.MAX, quad.edgeFraction(CropPoint(500f, 100f), topEdge = false))
    }

    @Test
    fun detectedFoldBecomesTheStartingLineOrTheMiddle() {
        val quad = fullCrop(200f, 100f)

        assertEquals(SplitLine(0.45f, 0.55f), quad.splitLineFor(floatArrayOf(90f, 0f, 110f, 100f)))
        assertEquals(SplitLine.Middle, quad.splitLineFor(null))
    }
}
