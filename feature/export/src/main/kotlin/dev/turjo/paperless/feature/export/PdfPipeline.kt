package dev.turjo.paperless.feature.export

import android.content.Context
import dev.turjo.paperless.core.pdf.PdfBox
import dev.turjo.paperless.core.pdf.QualityPreset
import dev.turjo.paperless.core.pdf.tools.*
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal data class PdfPipeline(
    val steps: List<PdfStep> = listOf(PdfStep.SameWidth),
    val password: String? = null,
) {
    init {
        require(steps.distinctBy(PdfStep::kind).size == steps.size)
    }
}

internal enum class StepKind {
    SAME_WIDTH, GRAYSCALE, COMPRESS, PAGE_NUMBERS, LAYOUT, CLEAN_METADATA, SEARCHABLE_TEXT;

    fun defaultStep(): PdfStep = when (this) {
        SAME_WIDTH -> PdfStep.SameWidth
        GRAYSCALE -> PdfStep.Grayscale
        COMPRESS -> PdfStep.Compress()
        PAGE_NUMBERS -> PdfStep.PageNumbers()
        LAYOUT -> PdfStep.Layout()
        CLEAN_METADATA -> PdfStep.CleanMetadata
        SEARCHABLE_TEXT -> PdfStep.SearchableText
    }
}

internal sealed class PdfStep(val kind: StepKind) {
    data object SameWidth : PdfStep(StepKind.SAME_WIDTH)
    data object Grayscale : PdfStep(StepKind.GRAYSCALE)
    data class Compress(val preset: QualityPreset = QualityPreset.MEDIUM) : PdfStep(StepKind.COMPRESS)
    data class PageNumbers(
        val style: NumberStyle = NumberStyle.FRACTION,
        val position: NumberPosition = NumberPosition.BOTTOM_CENTER,
    ) : PdfStep(StepKind.PAGE_NUMBERS)
    data class Layout(val layout: PrintLayout = PrintLayout.TWO) : PdfStep(StepKind.LAYOUT)
    data object CleanMetadata : PdfStep(StepKind.CLEAN_METADATA)
    data object SearchableText : PdfStep(StepKind.SEARCHABLE_TEXT)
}

internal enum class PrintLayout(val layout: NUpLayout) {
    TWO(NUpLayout.PLAIN_2),
    FOUR(NUpLayout.PLAIN_4),
    BOOKLET(NUpLayout.BOOKLET),
    CUT_AND_STACK(NUpLayout.CUT_WHOLE_STACK),
}

internal interface PipelineOps {
    suspend fun apply(input: File, output: File, step: PdfStep, pageLabels: Boolean)
    suspend fun encrypt(input: File, output: File, password: String)
}

internal class PdfPipelineOps(private val context: Context) : PipelineOps {
    override suspend fun apply(input: File, output: File, step: PdfStep, pageLabels: Boolean) {
        PdfBox.ensureInit(context)
        when (step) {
            PdfStep.SameWidth -> PageWidthNormalizer.normalize(input, WidthTarget.Common, output)
            PdfStep.Grayscale -> PdfGrayscale.convert(input, output)
            is PdfStep.Compress -> PdfCompress.compress(input, step.preset, output)
            is PdfStep.PageNumbers -> PageNumbers.stamp(input, step.style, step.position, 12f, 1, null, output)
            is PdfStep.Layout -> {
                val layout = step.layout.layout
                val options = NUpOptions(cutLine = step.layout == PrintLayout.CUT_AND_STACK, pageLabels = pageLabels)
                NUp.layout(input, layout, layout.perSide, Flip.LONG, options, output)
            }
            PdfStep.CleanMetadata -> PdfSanitize.sanitize(input, removeXmp = true, out = output)
            PdfStep.SearchableText -> error("Searchable text requires scanned page images")
        }
    }

    override suspend fun encrypt(input: File, output: File, password: String) {
        PdfBox.ensureInit(context)
        PdfPassword.encrypt(input, password, output)
    }
}

internal suspend fun applyPipeline(input: File, pipeline: PdfPipeline, workDir: File, ops: PipelineOps): File {
    var current = input
    val temps = mutableListOf<File>()
    suspend fun applyStep(apply: suspend (File, File) -> Unit) {
        coroutineContext.ensureActive()
        val output = File.createTempFile("step-", ".pdf", workDir)
        temps.add(output)
        // PdfCompress requires a destination that does not exist yet.
        Files.delete(output.toPath())
        apply(current, output)
        if (current != input) current.delete()
        current = output
    }

    var completed = false
    try {
        val pageLabels = pipeline.steps.none { it is PdfStep.PageNumbers }
        for (step in pipeline.steps) {
            applyStep { from, to -> ops.apply(from, to, step, pageLabels) }
        }
        pipeline.password?.let { password -> applyStep { from, to -> ops.encrypt(from, to, password) } }
        coroutineContext.ensureActive()
        completed = true
        return current
    } finally {
        temps.filter { !completed || it != current }.forEach { it.delete() }
    }
}
