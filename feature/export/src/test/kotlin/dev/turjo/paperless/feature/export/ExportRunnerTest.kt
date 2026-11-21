package dev.turjo.paperless.feature.export

import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.model.*
import dev.turjo.paperless.core.pdf.ExportPage
import dev.turjo.paperless.core.pdf.QualityPreset
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ExportRunnerTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var cache: File
    private lateinit var original: File
    private lateinit var processed: File
    private val repository = FakeRepository()
    private val processing = FakeProcessing()
    private lateinit var document: DocumentWithPages
    private var encoded = 0
    private var pdfSources: List<File> = emptyList()

    @Before
    fun setup() {
        cache = temp.newFolder("exports")
        original = temp.newFile("original.jpg").apply { writeBytes(ByteArray(200)) }
        processed = temp.newFile("processed.jpg").apply { writeBytes(ByteArray(100)) }
        val first = page("one", 0, original.path).copy(processedPath = processed.path, processedHash = "stale")
        val second = page("two", 1, original.path)
        document = DocumentWithPages(Document("doc", "Receipt", null, false, false, 0, 0, null, null), listOf(second, first))
        repository.pages.putAll(document.pages.associateBy(Page::id))
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun waitsForRendersAndUsesProcessedThenOriginalInPageOrder() = runTest {
        val first = repository.pages.getValue("one")
        repository.pages["one"] = first.copy(processedPath = null)
        runner().quickShare(document) { files, mime ->
            assertEquals(listOf("Receipt.pdf"), files.map(File::getName))
            assertEquals(PDF_MIME, mime)
            assertTrue(files.single().isFile)
        }
        assertEquals(listOf(processed, original), pdfSources)
        assertEquals(listOf("idle:one,two", "render:one", "render:two", "idle:one,two"), processing.calls)
    }

    @Test
    fun bothSharesPdfAndNumberedJpegs() = runTest {
        runner().share(document, ExportOptions(format = ExportFormat.BOTH), "A/B") { files, mime ->
            assertEquals(listOf("A_B.pdf", "A_B_1.jpg", "A_B_2.jpg"), files.map(File::getName))
            assertEquals("*/*", mime)
        }
    }

    @Test
    fun estimatesFromFirstPageRatioAndCachesPerQuality() = runTest {
        val runner = runner()
        assertEquals(300L, runner.estimate(document, QualityPreset.HIGH))
        assertEquals(150L, runner.estimate(document, QualityPreset.MEDIUM))
        assertEquals(150L, runner.estimate(document, QualityPreset.MEDIUM))
        assertEquals(1, encoded)
        assertEquals(150L, runner.estimate(document, QualityPreset.SMALL))
        assertEquals(2, encoded)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationRemovesPartialOutputsAndSkipsShare() = runTest {
        val started = CompletableDeferred<Unit>()
        val runner = runner { _, _, output, _ ->
            output.writeText("partial")
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch {
            runner.quickShare(document) { _, _ -> error("Chooser launched after cancellation") }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun sanitizesPathSeparatorsAndReservedFileNames() {
        assertEquals("Scan", sanitizeExportName(" ... "))
        assertEquals("A_B_C_", sanitizeExportName("A/B\\C?"))
        assertEquals("_con.txt", sanitizeExportName("con.txt"))
        assertEquals(60, sanitizeExportName("a".repeat(100)).length)
    }

    private fun runner(
        writePdf: suspend (List<ExportPage>, ExportOptions, File, String) -> Unit =
            { sources, _, file, _ ->
                pdfSources = sources.map(ExportPage::jpeg)
                file.writeText("pdf")
            },
    ) = ExportRunner(
        repository, processing, { cache }, { 100 to 200 },
        { _, _, _, file -> encoded++; file.writeBytes(ByteArray(50)) },
        writePdf,
        { sources, _, folder, name ->
            sources.mapIndexed { index, _ -> File(folder, "${name}_${index + 1}.jpg").apply { writeText("jpg") } }
        },
        { _, _ -> error("Unexpected MediaStore call") },
    )

    private inner class FakeProcessing : PageProcessing {
        val calls = mutableListOf<String>()
        override val busyPageIds = MutableStateFlow(emptySet<String>())
        override fun enqueueNewPage(pageId: String, filter: PageFilter) = error("Unexpected capture")
        override fun enqueueRender(pageId: String) {
            calls += "render:$pageId"
        }
        override suspend fun awaitIdle(pageIds: Collection<String>) {
            calls += "idle:${pageIds.joinToString(",")}"
            if (calls.size > 1) {
                val page = repository.pages.getValue("one")
                repository.pages["one"] = page.copy(
                    processedPath = processed.path,
                    processedHash = page.paramsHash(),
                )
            }
        }
        override suspend fun detectQuad(pageId: String) = error("Unexpected detection")
        override suspend fun originalBitmap(pageId: String, maxSide: Int) = error("Unexpected decode")
        override suspend fun previewBitmap(page: Page, maxSide: Int) = error("Unexpected preview")
        override suspend fun uprightSize(pageId: String) = error("Unexpected lookup")
    }

    private class FakeRepository : DocumentRepository {
        val pages = mutableMapOf<String, Page>()
        override suspend fun getPage(id: String) = pages[id]
        override fun observeLibrary() = unsupported()
        override fun observeDocument(id: String) = unsupported()
        override suspend fun updatePage(page: Page) = unsupported()
        override suspend fun addPage(documentId: String, originalFile: File, width: Int, height: Int, at: Int?) =
            unsupported()
        override suspend fun reorderPages(documentId: String, orderedPageIds: List<String>) = unsupported()
        override suspend fun deletePage(pageId: String) = unsupported()
        override suspend fun rotatePage(pageId: String, delta: Int) = unsupported()
        override suspend fun rename(id: String, title: String) = unsupported()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = unsupported()
        override suspend fun softDelete(id: String) = unsupported()
        override suspend fun restore(id: String) = unsupported()
        override suspend fun commitDraft(draftId: String, title: String, targetDocumentId: String?, insertAt: Int?) =
            unsupported()
        private fun unsupported(): Nothing = error("Unexpected repository call")
    }
}

private fun page(id: String, index: Int, original: String = "unused") = Page(
    id, "doc", index, PageMode.NORMAL, original, 100, 200, null, 0,
    PageFilter.DOCUMENT, 0f, 0f, false, false, null, null, false, 0,
)
