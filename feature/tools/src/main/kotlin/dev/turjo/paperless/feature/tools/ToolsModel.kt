package dev.turjo.paperless.feature.tools

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.turjo.paperless.core.pdf.MediaStoreSaver
import dev.turjo.paperless.core.pdf.PdfBox
import dev.turjo.paperless.core.pdf.exportCacheDir
import dev.turjo.paperless.core.pdf.tools.DocInfo
import dev.turjo.paperless.core.pdf.tools.PageOp
import dev.turjo.paperless.core.pdf.tools.PageWidthNormalizer
import dev.turjo.paperless.core.pdf.tools.PdfMetadata
import dev.turjo.paperless.core.pdf.tools.PdfPassword
import dev.turjo.paperless.core.pdf.tools.ToolError
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class Details(val widths: List<Float>, val metadata: DocInfo? = null)
internal data class ToolsState(
    val tool: Tool? = null,
    val inputs: List<Input> = emptyList(),
    val details: List<Details> = emptyList(),
    val locked: Int? = null,
    val outputs: List<Output> = emptyList(),
    val inputBytes: Long? = null,
    val batchId: UUID? = null,
    val batchItems: List<BatchItem> = emptyList(),
    val batchRunning: Boolean = false,
    val stopping: Boolean = false,
    val busy: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val message: String? = null,
)

internal class ToolsModel(
    application: Application,
    ops: ToolOps? = null,
    private val inspectPdf: (File, Boolean) -> Details = { file, metadata ->
        PdfBox.ensureInit(application)
        Details(PageWidthNormalizer.widths(file), if (metadata) PdfMetadata.read(file) else null)
    },
) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val mutableState = MutableStateFlow(ToolsState())
    val state = mutableState.asStateFlow()
    private val ops = ops ?: PdfToolOps(application) { position, total ->
        val progress = application.getString(R.string.tools_enhancing_progress, position, total)
        mutableState.update { it.copy(progress = progress) }
    }
    private var task: Job? = null
    private var observer: Job? = null
    private val preferences get() = context.getSharedPreferences("pdf_tools", Context.MODE_PRIVATE)
    private val work get() = WorkManager.getInstance(context)
    private val current get() = state.value
    private fun set(next: ToolsState) { mutableState.value = next }

    fun select(tool: Tool) {
        set(ToolsState(tool = tool))
    }

    fun back() {
        if (current.busy) {
            task?.cancel()
            return
        }
        if (current.batchId != null) {
            observer?.cancel()
            select(Tool.BATCH)
        } else if (current.outputs.isNotEmpty()) {
            set(current.copy(outputs = emptyList(), error = null, message = null))
        } else {
            set(ToolsState())
        }
    }

    fun pick(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val photos = current.tool == Tool.FROM_IMAGES
        loadInputs { uris.map { uri -> copy(uri, photoUri = uri.takeIf { photos }) } }
    }

    fun pickFolder(tree: Uri) = loadInputs {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val pdfs = context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "application/pdf" || cursor.getString(2).orEmpty().endsWith(".pdf", true)) {
                        add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
                    }
                }
            }
        }.orEmpty()
        if (pdfs.isEmpty()) throw ToolError.Invalid(context.getString(R.string.tools_no_pdfs))
        pdfs.map { copy(it) }
    }

    private fun loadInputs(load: suspend () -> List<Input>) = launch {
        val inputs = withContext(Dispatchers.IO) { load() }
        set(current.copy(inputs = inputs, details = emptyList(), locked = null, outputs = emptyList()))
        inspect()
    }

    private suspend fun inspect() {
        val snapshot = current
        if (snapshot.tool in setOf(Tool.BATCH, Tool.FROM_IMAGES)) return
        val details = mutableListOf<Details>()
        for ((index, input) in snapshot.inputs.withIndex()) {
            val item = withContext(Dispatchers.IO) {
                try {
                    inspectPdf(input.file, snapshot.tool == Tool.METADATA)
                } catch (_: ToolError.Encrypted) {
                    null
                }
            }
            if (item == null) {
                set(current.copy(locked = index, details = details.toList()))
                return
            }
            details += item
        }
        set(current.copy(details = details, locked = null))
    }

    fun unlock(password: String) = launch(removingPassword = true) {
        val index = current.locked ?: return@launch
        val input = current.inputs[index]
        if (current.tool == Tool.PASSWORD) {
            val outputs = withContext(Dispatchers.IO) {
                ops.run(ToolRequest(Tool.PASSWORD, listOf(input), mapOf("Action" to "Remove", "Password" to password)),
                    uniqueDirectory(exportCacheDir(context)))
            }
            set(current.copy(outputs = outputs))
            return@launch
        }
        val decrypted = withContext(Dispatchers.IO) {
            val directory = uniqueDirectory(File(context.cacheDir, "tools/in"))
            PdfPassword.decrypt(input.file, password, File(directory, input.file.name)).output
        }
        val inputs = current.inputs.toMutableList().apply { this[index] = input.copy(file = decrypted) }
        set(current.copy(inputs = inputs))
        inspect()
    }

    fun move(index: Int, by: Int) {
        if (index + by !in current.inputs.indices) return
        val next = current.inputs.toMutableList().apply { add(index + by, removeAt(index)) }
        set(current.copy(inputs = next))
    }

    fun run(options: Options, pages: List<PageOp> = emptyList()) = launch(options["Action"] == "Remove") {
        val request = ToolRequest(checkNotNull(current.tool), current.inputs, options, pages)
        val (outputs, inputBytes) = withContext(Dispatchers.IO) {
            val inputBytes = request.inputs.first().file.length()
            ops.run(request, uniqueDirectory(exportCacheDir(context))) to inputBytes
        }
        set(current.copy(outputs = outputs, inputBytes = inputBytes))
    }

    fun insert(uri: Uri, position: Int, range: String) = launch {
        val snapshot = current
        val outputs = withContext(Dispatchers.IO) {
            val source = copy(uri)
            val count = PageWidthNormalizer.widths(source.file).size
            val pages = if (range.isBlank()) (0 until count).toList()
                else parseRanges(range, count).flatMap { it.toList() }
            ops.run(ToolRequest(Tool.PAGES, snapshot.inputs, pages =
                listOf(PageOp.InsertFrom(source.file, pages, position))), uniqueDirectory(exportCacheDir(context)))
        }
        set(current.copy(outputs = outputs))
    }

    fun save(outputs: List<Output>) = launch {
        val saver = MediaStoreSaver(context)
        outputs.forEach { output ->
            val mime = output.file.imageMime()
            if (mime == null) saver.savePdf(output.file, output.file.name)
            else saver.saveImage(output.file, output.file.name, mime)
        }
        set(current.copy(message = context.getString(R.string.tools_saved)))
    }

    fun shareable(outputs: List<Output>, share: (List<File>) -> Unit) = launch {
        val files = withContext(Dispatchers.IO) {
            val exports = exportCacheDir(context)
            outputs.map { output ->
                if (output.file.toPath().startsWith(exports.toPath())) output.file
                else output.file.copyTo(File(uniqueDirectory(exports), output.file.name))
            }
        }
        share(files)
    }

    fun startBatch(tool: Tool, options: Options) = launch {
        val request = OneTimeWorkRequestBuilder<BatchWorker>().build()
        val chosen = current.inputs
        withContext(Dispatchers.IO) {
            val store = BatchStore(context, request.id.toString())
            val inputs = chosen.mapIndexed { index, input ->
                ensureActive()
                val target = File(store.directory, "in/$index/${input.file.name}")
                input.file.copyTo(target)
                input.copy(file = target)
            }
            store.prepare(ToolRequest(tool, inputs, options))
            preferences.edit { putString("latest_batch", request.id.toString()) }
            work.enqueue(request).result.get()
        }
        observeBatch(request.id)
    }

    fun latestBatch() = launch {
        val id = withContext(Dispatchers.IO) { preferences.getString("latest_batch", null) }
        if (id == null) set(current.copy(message = context.getString(R.string.tools_no_batch_jobs)))
        else observeBatch(UUID.fromString(id))
    }

    private fun observeBatch(id: UUID) {
        observer?.cancel()
        set(current.copy(batchId = id, batchRunning = true))
        observer = viewModelScope.launch {
            try {
                work.getWorkInfoByIdFlow(id).collect { info ->
                    val store = BatchStore(context, id.toString())
                    val (items, stopped) = withContext(Dispatchers.IO) { store.results() to store.cancelled() }
                    val active = info != null && !info.state.isFinished
                    set(current.copy(batchItems = items, batchRunning = active, stopping = stopped,
                        error = if (info?.state == WorkInfo.State.FAILED) context.getString(R.string.tools_batch_failed) else null))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                set(current.copy(error = error.toolMessage(context), batchRunning = false))
            }
        }
    }

    fun stopBatch() = launch {
        val id = current.batchId ?: return@launch
        withContext(Dispatchers.IO) { BatchStore(context, id.toString()).cancel() }
        set(current.copy(stopping = true))
    }

    private suspend fun copy(uri: Uri, photoUri: Uri? = null): Input = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        val name = displayName.orEmpty().replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .take(160).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "Document"
        val directory = uniqueDirectory(File(context.cacheDir, "tools/in"))
        val file = File(directory, name)
        try {
            val source = resolver.openInputStream(uri) ?: throw IOException("Cannot open $name")
            source.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                }
            }
            Input(file, name, photoUri)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun launch(removingPassword: Boolean = false, action: suspend () -> Unit) {
        if (current.busy) return
        set(current.copy(busy = true, error = null, message = null))
        task = viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                set(current.copy(error = error.toolMessage(context, removingPassword)))
            } finally {
                set(current.copy(busy = false, progress = null))
            }
        }
    }
}

private fun uniqueDirectory(parent: File) = File(parent, UUID.randomUUID().toString()).apply {
    check(mkdirs()) { "Cannot create a tools folder" }
}

internal fun File.imageMime(): String? = when (extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    else -> null
}

private val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(2)

internal suspend fun thumbnail(file: File, index: Int): Bitmap = withContext(thumbnailDispatcher) {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(index).use { page ->
                val scale = 160f / maxOf(page.width, page.height)
                createBitmap((page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1)).apply {
                    eraseColor(android.graphics.Color.WHITE)
                    page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }
}
