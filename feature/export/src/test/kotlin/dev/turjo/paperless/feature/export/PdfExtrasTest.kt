package dev.turjo.paperless.feature.export

import dev.turjo.paperless.core.pdf.tools.Flip
import dev.turjo.paperless.core.pdf.tools.NUpLayout
import dev.turjo.paperless.core.pdf.tools.NUpOptions
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfExtrasTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun appliesEveryCombinationInOrderAndKeepsOnlyTheResult() = runTest {
        for (mask in 0..7) {
            for (layout in listOf(null) + LayoutExtra.entries) {
                val directory = temp.newFolder()
                val input = File(directory, "input.pdf").apply { writeText("pdf") }
                val extras = PdfExtras(
                    grayscale = mask and 1 != 0,
                    pageNumbers = if (mask and 2 != 0) PageNumberExtra() else null,
                    layout = layout,
                    password = if (mask and 4 != 0) "secret" else null,
                )
                val ops = RecordingExtraOps()
                val result = applyExtras(input, extras, directory, ops)
                val expected = buildList {
                    if (extras.grayscale) add("grayscale")
                    if (extras.pageNumbers != null) add("number")
                    if (layout != null) add("layout")
                    if (extras.password != null) add("encrypt")
                }
                assertEquals(expected, ops.calls)
                assertEquals((listOf("pdf") + expected).joinToString(" "), result.readText())
                assertEquals("pdf", input.readText())
                assertEquals(setOf(input, result), directory.listFiles().orEmpty().toSet())
                if (layout != null) {
                    assertEquals(
                        Triple(
                            layout.layout,
                            Flip.LONG,
                            NUpOptions(cutLine = layout == LayoutExtra.CUT_AND_STACK,
                                pageLabels = extras.pageNumbers == null),
                        ),
                        ops.layoutArgs,
                    )
                }
            }
        }
    }

    @Test
    fun removesIntermediatesAndPartialOutputOnFailure() = runTest {
        val input = temp.newFile("input.pdf").apply { writeText("pdf") }
        val failure = IllegalStateException("Failed to lay out PDF")
        val ops = RecordingExtraOps { if (it == "layout") throw failure }
        val extras = PdfExtras(true, PageNumberExtra(), LayoutExtra.BOOKLET, "secret")
        val caught = runCatching { applyExtras(input, extras, temp.root, ops) }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(listOf("grayscale", "number", "layout"), ops.calls)
        assertEquals(listOf(input), temp.root.listFiles().orEmpty().toList())
    }

    @Test
    fun removesIntermediatesOnCancellation() = runTest {
        val input = temp.newFile("input.pdf")
        val ops = RecordingExtraOps { if (it == "number") awaitCancellation() }
        val job = launch {
            applyExtras(input, PdfExtras(true, PageNumberExtra(), password = "secret"), temp.root, ops)
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("grayscale", "number"), ops.calls)
        assertEquals(listOf(input), temp.root.listFiles().orEmpty().toList())
    }
}

internal class RecordingExtraOps(private val afterWrite: suspend (String) -> Unit = {}) : ExtraOps {
    val calls = mutableListOf<String>()
    var layoutArgs: Triple<NUpLayout, Flip, NUpOptions>? = null
        private set

    override suspend fun grayscale(input: File, output: File) = record("grayscale", input, output)

    override suspend fun number(input: File, output: File, options: PageNumberExtra) =
        record("number", input, output)

    override suspend fun layout(input: File, output: File, layout: NUpLayout, flip: Flip, options: NUpOptions) {
        layoutArgs = Triple(layout, flip, options)
        record("layout", input, output)
    }

    override suspend fun encrypt(input: File, output: File, password: String) = record("encrypt", input, output)

    private suspend fun record(step: String, input: File, output: File) {
        calls += step
        output.writeText(input.readText() + " " + step)
        afterWrite(step)
    }
}
