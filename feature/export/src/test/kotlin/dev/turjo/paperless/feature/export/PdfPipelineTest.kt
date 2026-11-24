package dev.turjo.paperless.feature.export

import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfPipelineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun appliesStepsInListOrderAndKeepsOnlyTheResult() = runTest {
        val steps = StepKind.entries.map(StepKind::defaultStep)
        for (permutation in listOf(steps, steps.reversed(), steps.drop(2) + steps.take(2), emptyList())) {
            for (password in listOf(null, "secret")) {
                val directory = temp.newFolder()
                val input = File(directory, "input.pdf").apply { writeText("pdf") }
                val ops = RecordingPipelineOps()
                val result = applyPipeline(input, PdfPipeline(permutation, password), directory, ops)
                val expected = permutation.map { it.kind.name } + listOfNotNull(password?.let { "encrypt" })

                assertEquals(expected, ops.calls)
                assertEquals(password, ops.password)
                assertEquals((listOf("pdf") + expected).joinToString(" "), result.readText())
                assertEquals("pdf", input.readText())
                assertEquals(setOf(input, result), directory.listFiles().orEmpty().toSet())
                if (expected.isEmpty()) assertSame(input, result)
            }
        }
    }

    @Test
    fun layoutLabelsDependOnNumbersAnywhereInTheList() = runTest {
        val input = temp.newFile("input.pdf")
        for (printLayout in PrintLayout.entries) {
            val layout = PdfStep.Layout(printLayout)
            val numbers = PdfStep.PageNumbers()
            for (steps in listOf(listOf(layout), listOf(numbers, layout), listOf(layout, numbers))) {
                val ops = RecordingPipelineOps()
                applyPipeline(input, PdfPipeline(steps), temp.root, ops)
                assertEquals(layout, ops.steps.single { it is PdfStep.Layout })
                assertEquals(listOf(numbers !in steps), ops.layoutLabels)
            }
        }
    }

    @Test
    fun rejectsDuplicateStepTypesWithDifferentOptions() {
        val duplicates = listOf(PdfStep.Layout(PrintLayout.TWO), PdfStep.Layout(PrintLayout.FOUR))
        assertThrows(IllegalArgumentException::class.java) { PdfPipeline(duplicates) }
    }

    @Test
    fun removesIntermediatesAndPartialOutputOnFailure() = runTest {
        val input = temp.newFile("input.pdf").apply { writeText("pdf") }
        val failure = IllegalStateException("Failed to lay out PDF")
        val ops = RecordingPipelineOps { if (it == "LAYOUT") throw failure }
        val pipeline = PdfPipeline(listOf(PdfStep.Grayscale, PdfStep.Layout()), "secret")
        val caught = runCatching { applyPipeline(input, pipeline, temp.root, ops) }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(listOf("GRAYSCALE", "LAYOUT"), ops.calls)
        assertEquals(listOf(input), temp.root.listFiles().orEmpty().toList())
    }

    @Test
    fun removesIntermediatesOnCancellation() = runTest {
        val input = temp.newFile("input.pdf")
        val ops = RecordingPipelineOps { if (it == "PAGE_NUMBERS") awaitCancellation() }
        val job = launch {
            val pipeline = PdfPipeline(listOf(PdfStep.Grayscale, PdfStep.PageNumbers()), "secret")
            applyPipeline(input, pipeline, temp.root, ops)
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("GRAYSCALE", "PAGE_NUMBERS"), ops.calls)
        assertEquals(listOf(input), temp.root.listFiles().orEmpty().toList())
    }
}

internal class RecordingPipelineOps(private val afterWrite: suspend (String) -> Unit = {}) : PipelineOps {
    val calls = mutableListOf<String>()
    val steps = mutableListOf<PdfStep>()
    val layoutLabels = mutableListOf<Boolean>()
    var password: String? = null
        private set

    override suspend fun apply(input: File, output: File, step: PdfStep, pageLabels: Boolean) {
        steps += step
        if (step is PdfStep.Layout) layoutLabels += pageLabels
        record(step.kind.name, input, output)
    }

    override suspend fun encrypt(input: File, output: File, password: String) {
        this.password = password
        record("encrypt", input, output)
    }

    private suspend fun record(step: String, input: File, output: File) {
        assertFalse("Output must start absent", output.exists())
        calls += step
        output.writeText(input.readText() + " " + step)
        afterWrite(step)
    }
}
