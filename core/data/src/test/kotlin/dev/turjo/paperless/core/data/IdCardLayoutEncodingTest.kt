package dev.turjo.paperless.core.data

import dev.turjo.paperless.core.imaging.CardPlacement
import dev.turjo.paperless.core.imaging.IdCardLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class IdCardLayoutEncodingTest {
    @Test
    fun multiplePlacementsAndReorderRoundTrip() {
        val placements = listOf(
            CardPlacement(0.2f, 0.15f, 0.4f),
            CardPlacement(0.2f, 0.4f, 0.4f),
            null,
            CardPlacement(0.2f, 0.7f, 0.4f),
        )
        val layout = IdCardLayout().withPlacements(placements).moveCard(3, 1, placements.size)
        assertEquals(listOf(0, 3, 2, 1), layout.sourceOrder(placements.size))
        assertEquals(placements[1], layout.placements(placements.size)[3])
        assertEquals(layout, parseIdCardLayout(layout.encode()))
        val preset = layout.resetPlacement()
        assertEquals(preset, parseIdCardLayout(preset.encode()))
        assertEquals("new-front,,new-third", "front,deleted,third".remapIdSources(
            mapOf("front" to "new-front", "third" to "new-third"),
        ))
    }

    @Test
    fun legacyRecordsKeepTheirPresets() {
        val encoded = "SIDE_BY_SIDE,LARGE,15,true"
        val layout = IdCardLayout(IdCardLayout.Arrangement.SIDE_BY_SIDE, IdCardLayout.Size.LARGE, 15, true)
        assertEquals(layout, parseIdCardLayout(encoded))
        assertEquals(encoded, layout.encode())
    }

    @Test
    fun placementsAndSwappedOrderRoundTrip() {
        val preset = IdCardLayout(gapMm = 0).swapCards()
        val front = CardPlacement(0.123f, 0.25f, 0.6f)
        val back = CardPlacement(0.5f, 0.7f, 0.3f)
        for (layout in listOf(preset, preset.copy(front = front), preset.copy(back = back),
            preset.copy(front = front, back = back))) {
            assertEquals(layout, parseIdCardLayout(layout.encode()))
        }
    }
}
