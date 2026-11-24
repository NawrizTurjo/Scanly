package dev.turjo.paperless.feature.editor

import dev.turjo.paperless.core.imaging.CardRect
import org.junit.Assert.assertEquals
import org.junit.Test

class IdPlacementGeometryTest {
    @Test
    fun snapsToPageCenterAndBothCardEdges() {
        val other = CardRect(400, 100, 530, 300)
        val moving = CardRect(993, 100, 500, 300)
        val centered = snapCard(moving, other, 10f)
        assertEquals(990, centered.rect.left)
        assertEquals(1240f, centered.guideX)
        assertEquals(400f, snapCard(moving.copy(left = 403), other, 10f).guideX)
        val right = snapCard(moving.copy(left = 426), other, 10f)
        assertEquals(other.right, right.rect.right)
        assertEquals(other.right.toFloat(), right.guideX)
    }

    @Test
    fun snapsToSixMillimetersAboveOrBelow() {
        val other = CardRect(400, 500, 530, 300)
        val moving = CardRect(760, 875, 500, 300)
        assertEquals(871, snapCard(moving, other, 10f).rect.top)
        assertEquals(429, snapCard(moving.copy(top = 133), other, 10f).rect.bottom)
        assertEquals(CardSnap(moving, null, null), snapCard(moving, other, 2f))
    }
}
