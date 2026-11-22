package dev.turjo.paperless.feature.tools

import android.net.Uri
import dev.turjo.paperless.core.pdf.tools.DocInfo
import dev.turjo.paperless.core.pdf.tools.PageOp
import dev.turjo.paperless.core.pdf.tools.ToolError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class RangeParserTest {
    @Test fun parsesOneBasedRanges() {
        assertEquals(listOf(0..2, 4..4, 7..9), parseRanges("1-3, 5, 8 - 10", 10))
    }

    @Test fun rejectsInvalidRanges() {
        listOf("", "0", "5-3", "1,,3", "1-", "a", "1-11", "1-2147483648").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { parseRanges(it, 10) }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ToolsModelTest {
    @Test fun mergeUsesReorderedFiles() = verify(Tool.MERGE, reorder = true)
    @Test fun splitPassesRanges() = verify(Tool.SPLIT, mapOf("Pages" to "1-2, 3"))
    @Test fun pageEditsPassSelectedOperations() = verify(Tool.PAGES, pages = listOf(PageOp.Rotate(setOf(1), 90)))
    @Test fun compressPassesQuality() = verify(Tool.COMPRESS, mapOf("Quality" to "SMALL"))
    @Test fun widthPassesTarget() = verify(Tool.WIDTH, mapOf("Target width" to "A4"))
    @Test fun printPassesTestSheetOption() = verify(Tool.PRINT, mapOf("Test sheet" to "true"))
    @Test fun numbersPassesRangeAndStart() = verify(Tool.NUMBERS, mapOf("Start at" to "3", "Page range" to "2-3"))
    @Test fun passwordPassesNewPassword() = verify(Tool.PASSWORD, mapOf("Password" to "demo"))
    @Test fun metadataPassesClearOption() = verify(Tool.METADATA, mapOf("Clear all" to "true"))
    @Test fun sanitizePassesXmpOption() = verify(Tool.SANITIZE, mapOf("Remove XMP" to "false"))
    @Test fun grayscalePublishesResult() = verify(Tool.GRAYSCALE)
    @Test fun imagesToPdfPassesPageSize() = verify(Tool.FROM_IMAGES, mapOf("Page size" to "LETTER"))
    @Test fun pdfToImagesPassesResolution() = verify(Tool.TO_IMAGES, mapOf("DPI" to "300", "Format" to "PNG"))

    @Test fun protectedInputPromptsForPassword() = modelTest(Tool.COMPRESS, inspect = { _, _ ->
        throw ToolError.Encrypted()
    }, ops = ToolOps { _, _ -> error("Operation should wait for the password") }) { model ->
        assertEquals(0, model.state.value.locked)
        assertTrue(model.state.value.outputs.isEmpty())
    }

    @Test fun passwordToolRemovesProtectionAfterPrompt() = modelTest(Tool.PASSWORD, inspect = { _, _ ->
        throw ToolError.Encrypted()
    }, ops = ToolOps { request, directory ->
        assertEquals(mapOf("Action" to "Remove", "Password" to "secret"), request.options)
        assertEquals(1, request.inputs.size)
        listOf(Output(File(directory, "unlocked.pdf"), bytes = 12))
    }) { model ->
        model.unlock("secret")
        assertEquals("unlocked.pdf", idle(model).outputs.single().file.name)
    }

    @Test fun operationErrorReturnsToOptions() = modelTest(Tool.WIDTH, ops = ToolOps { _, _ ->
        throw ToolError.Invalid("Choose a smaller width")
    }) { model ->
        model.run(emptyMap())
        val failed = idle(model)
        assertEquals("Choose a smaller width", failed.error)
        assertTrue(failed.outputs.isEmpty())
        assertEquals(2, failed.inputs.size)
    }

    private fun verify(tool: Tool, options: Options = emptyMap(), pages: List<PageOp> = emptyList(), reorder: Boolean = false) {
        var request: ToolRequest? = null
        modelTest(tool, ops = ToolOps { job, directory ->
            request = job
            listOf(Output(File(directory, "result.pdf").apply { writeText("output") }, "Tool complete"))
        }) { model ->
            val inputs = model.state.value.inputs
            if (reorder) model.move(1, -1)
            model.run(options, pages)
            val finished = idle(model)
            assertNull(finished.error)
            assertEquals("Tool complete", finished.outputs.single().note)
            assertEquals(ToolRequest(tool, if (reorder) inputs.reversed() else inputs, options, pages), request)
            model.back()
            assertTrue(model.state.value.outputs.isEmpty())
            assertEquals(tool, model.state.value.tool)
        }
    }

    private fun modelTest(
        tool: Tool,
        inspect: (File, Boolean) -> Details = { _, metadata ->
            Details(listOf(595f, 595f, 842f), if (metadata) DocInfo(title = "Source") else null)
        },
        ops: ToolOps,
        check: suspend (ToolsModel) -> Unit,
    ) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val context = RuntimeEnvironment.getApplication()
            val model = ToolsModel(context, ops, inspect)
            model.select(tool)
            val source = File(context.cacheDir, "source.pdf").apply { writeText("source") }
            model.pick(List(2) { Uri.fromFile(source) })
            val ready = idle(model)
            assertNull(ready.error)
            assertEquals(2, ready.inputs.size)
            check(model)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun idle(model: ToolsModel) = withTimeout(10_000) { model.state.first { !it.busy } }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BatchTest {
    @Test fun resultsJsonPreservesStatusesReasonsAndOutputs() {
        val items = listOf(
            BatchItem("One.pdf", BatchStatus.DONE, outputs = listOf(Output(File("/tmp/one.pdf"), "saved", 25))),
            BatchItem("Two.pdf", BatchStatus.SKIPPED, "Cancelled"),
            BatchItem("Three.pdf", BatchStatus.FAILED, "Invalid PDF"),
        )
        assertEquals(items, decodeResults(encodeResults(items)))
    }

    @Test fun failureDoesNotStopFollowingFiles() = runBlocking {
        val results = process(Tool.COMPRESS, ToolOps { request, _ ->
            if (request.inputs.single().name == "b.pdf") throw ToolError.Corrupt("Broken file")
            emptyList()
        })
        assertEquals(listOf(BatchStatus.FAILED, BatchStatus.DONE, BatchStatus.DONE), results.map { it.status })
        assertEquals(
            RuntimeEnvironment.getApplication().getString(R.string.tools_corrupt_pdf),
            results.first().reason,
        )
    }

    @Test fun cancelLetsCurrentFileFinishAndSkipsRemainingFiles() = runBlocking {
        var cancel = false
        val results = process(Tool.GRAYSCALE, ToolOps { _, _ ->
            cancel = true
            emptyList()
        }, cancelled = { cancel })
        assertEquals(listOf(BatchStatus.DONE, BatchStatus.SKIPPED, BatchStatus.SKIPPED), results.map { it.status })
        assertEquals("Cancelled", results.last().reason)
    }

    @Test fun mergeProcessesAllInputsInNameOrder() = runBlocking {
        var order = emptyList<String>()
        val results = process(Tool.MERGE, ToolOps { request, folder ->
            order = request.inputs.map { it.name }
            listOf(Output(File(folder, "Merged.pdf"), bytes = 25))
        })
        assertEquals(listOf("A.pdf", "b.pdf", "c.pdf"), order)
        assertEquals(1, results.flatMap { it.outputs }.size)
        assertTrue(results.all { it.status == BatchStatus.DONE })
    }

    private suspend fun process(tool: Tool, ops: ToolOps, cancelled: () -> Boolean = { false }): List<BatchItem> {
        val context = RuntimeEnvironment.getApplication()
        val inputs = listOf("b.pdf", "A.pdf", "c.pdf").map { Input(File(context.cacheDir, it), it) }
        var results = emptyList<BatchItem>()
        runBatch(context, ToolRequest(tool, inputs), ops, emptyList(), cancelled,
            { File(context.cacheDir, "batch-test/$it") }) { results = it }
        return results
    }
}
