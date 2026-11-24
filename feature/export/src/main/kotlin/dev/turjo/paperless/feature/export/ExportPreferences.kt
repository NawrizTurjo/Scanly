package dev.turjo.paperless.feature.export

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.turjo.paperless.core.pdf.QualityPreset
import dev.turjo.paperless.core.pdf.tools.NumberPosition
import dev.turjo.paperless.core.pdf.tools.NumberStyle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
internal class ExportPreferences(
    storedSteps: String? = null,
    legacySameWidth: Boolean = true,
    private val saveSteps: (String) -> Unit = {},
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences("export", Context.MODE_PRIVATE),
    )

    private constructor(store: SharedPreferences) : this(
        store.getString(STEPS, null),
        store.getBoolean(LEGACY_SAME_WIDTH, true),
        { steps -> store.edit().putString(STEPS, steps).remove(LEGACY_SAME_WIDTH).apply() },
    ) {
        if (!store.contains(STEPS) && store.contains(LEGACY_SAME_WIDTH)) {
            saveSteps(encodeSteps(options.value.pipeline.steps))
        }
    }

    private val selection = MutableStateFlow(ExportOptions(pipeline = PdfPipeline(
        steps = storedSteps?.let(::decodeSteps)
            ?: if (legacySameWidth) PdfPipeline().steps else emptyList(),
    )))
    val options = selection.asStateFlow()

    fun select(options: ExportOptions) {
        val steps = options.pipeline.steps
        if (steps != selection.value.pipeline.steps) saveSteps(encodeSteps(steps))
        selection.value = options.copy(pipeline = PdfPipeline(steps))
    }

    private companion object {
        const val STEPS = "pdfSteps"
        const val LEGACY_SAME_WIDTH = "sameWidth"
    }
}

private fun encodeSteps(steps: List<PdfStep>): String = steps.joinToString(",") { step ->
    val options = when (step) {
        is PdfStep.Compress -> listOf(step.preset.name)
        is PdfStep.PageNumbers -> listOf(step.style.name, step.position.name)
        is PdfStep.Layout -> listOf(step.layout.name)
        else -> emptyList()
    }
    (listOf(step.kind.name) + options).joinToString(":")
}

private fun decodeSteps(value: String): List<PdfStep> =
    value.split(',').mapNotNull { entry ->
        val parts = entry.split(':')
        runCatching {
            when (val kind = StepKind.valueOf(parts.first())) {
                StepKind.COMPRESS -> PdfStep.Compress(QualityPreset.valueOf(parts[1]))
                StepKind.PAGE_NUMBERS -> PdfStep.PageNumbers(
                    NumberStyle.valueOf(parts[1]), NumberPosition.valueOf(parts[2]),
                )
                StepKind.LAYOUT -> PdfStep.Layout(PrintLayout.valueOf(parts[1]))
                else -> kind.defaultStep()
            }
        }.getOrNull()
    }.distinctBy(PdfStep::kind).sortedBy { it != PdfStep.SearchableText }
