package dev.turjo.paperless.feature.export

import dev.turjo.paperless.core.pdf.QualityPreset
import dev.turjo.paperless.core.pdf.tools.NumberPosition
import dev.turjo.paperless.core.pdf.tools.NumberStyle
import org.junit.Assert.*
import org.junit.Test

class ExportPreferencesTest {
    @Test
    fun restoresOrderedStepsWithOptionsAndDiscardsPassword() {
        var stored: String? = null
        val preferences = ExportPreferences(saveSteps = { stored = it })
        val steps = listOf(
            PdfStep.CleanMetadata,
            PdfStep.PageNumbers(NumberStyle.PAGE_NUMBER, NumberPosition.TOP_RIGHT),
            PdfStep.Compress(QualityPreset.SMALL),
            PdfStep.Layout(PrintLayout.CUT_AND_STACK),
            PdfStep.Grayscale,
            PdfStep.SameWidth,
        )
        preferences.select(ExportOptions(pipeline = PdfPipeline(steps, "private-password")))

        val serialized = requireNotNull(stored)
        assertFalse(serialized.contains("private-password"))
        assertEquals(PdfPipeline(steps), preferences.options.value.pipeline)
        assertEquals(PdfPipeline(steps), ExportPreferences(storedSteps = serialized).options.value.pipeline)
    }

    @Test
    fun migratesLegacyWidthChoiceAndHonorsExplicitEmptyList() {
        assertEquals(listOf(PdfStep.SameWidth), ExportPreferences().options.value.pipeline.steps)
        assertEquals(emptyList<PdfStep>(), ExportPreferences(legacySameWidth = false).options.value.pipeline.steps)

        var stored: String? = null
        val preferences = ExportPreferences(saveSteps = { stored = it })
        preferences.select(ExportOptions(pipeline = PdfPipeline(emptyList())))
        val restored = ExportPreferences(storedSteps = requireNotNull(stored))
        assertTrue(restored.options.value.pipeline.steps.isEmpty())
    }
}
