package dev.turjo.paperless.core.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.turjo.paperless.core.pdf.PdfExporter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupResult(val location: String, val documents: Int, val skipped: Int = 0)

@Singleton
class BackupService internal constructor(
    private val database: PaperlessDatabase,
    private val context: Context,
    private val files: FileStore,
    private val writePdf: suspend (DocumentEntity, File) -> Long,
) {
    @Inject
    constructor(
        database: PaperlessDatabase,
        @ApplicationContext context: Context,
        files: FileStore,
        processing: PageProcessing,
    ) : this(database, context, files, scanPdfWriter(database.documentDao(), processing, PdfExporter(context)))

    private val documents = database.documentDao()
    private val folders = database.folderDao()
    private val tags = database.tagDao()
    private val annotations = database.annotationDao()

    suspend fun backup(treeUri: Uri, folderIds: Set<String>?, progress: (Float) -> Unit): BackupResult =
        backup(SafBackupTree(context.contentResolver, treeUri), folderIds, progress)

    suspend fun restore(treeUri: Uri, progress: (Float) -> Unit): BackupResult =
        restore(SafBackupTree(context.contentResolver, treeUri), progress)

    internal suspend fun backup(tree: BackupTree, folderIds: Set<String>?, progress: (Float) -> Unit): BackupResult =
        withContext(Dispatchers.IO) {
            val availableFolders = folders.observeFolders().first().map { it.folder }
            val selected = folderIds?.toMutableSet()
            if (selected != null) {
                availableFolders.filter { it.parentId in folderIds }.forEach { selected.add(it.id) }
            }
            val included = availableFolders.filter { selected == null || it.id in selected }
            val parents = included.mapNotNull { it.parentId }.toSet()
            val folderRows = availableFolders.filter { it in included || it.id in parents }
            val rows = documents.observeLibrary(null, false).first().mapNotNull { documents.document(it.id) }
                .filter { !it.isDraft && it.deletedAt == null && (selected == null || it.folderId in selected) }

            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT))
            val name = generateSequence(1) { it + 1 }
                .map { if (it == 1) "Scanly-backup-$stamp" else "Scanly-backup-$stamp ($it)" }
                .first { tree.child(it) == null }
            val destination = tree.directory(name)
            val folderNames = Names()
            val names = folderRows.associate { it.id to folderNames.take(it.name) }
            val unfiled = folderNames.take("Unfiled")
            val directories = mutableMapOf<String, BackupTree>()
            val documentNames = mutableMapOf<String, Names>()
            val result = JSONArray()
            progress(0f)
            for ((index, document) in rows.withIndex()) {
                currentCoroutineContext().ensureActive()
                val folderName = names[document.folderId] ?: unfiled
                val title = documentNames.getOrPut(folderName, ::Names).take(document.title)
                val directory = directories.getOrPut(folderName) { destination.directory(folderName) }.directory(title)
                writeDocument(directory, document, title, result, folderName)
                progress((index + 1f) / (rows.size + 1))
            }

            val documentIds = rows.mapTo(hashSetOf(), DocumentEntity::id)
            val (tagRows, links) = database.withTransaction {
                val links = tags.links().filter { it.documentId in documentIds }
                val linkedTags = links.mapTo(hashSetOf(), DocumentTagEntity::tagId)
                tags.all().filter { selected == null || it.id in linkedTags } to links
            }

            // The manifest is written last so an interrupted export cannot look complete.
            val json = JSONObject()
                .put("schemaVersion", 1)
                .put("folders", JSONArray(folderRows.map(FolderEntity::backupJson)))
                .put("tags", JSONArray(tagRows.map(TagEntity::backupJson)))
                .put("documentTags", JSONArray(links.map {
                    JSONObject().put("documentId", it.documentId).put("tagId", it.tagId)
                }))
                .put("documents", result)
            destination.write("library.json", "application/json") { it.write(json.toString(2).toByteArray()) }
            progress(1f)
            BackupResult(name, rows.size)
        }

    private suspend fun writeDocument(
        directory: BackupTree,
        document: DocumentEntity,
        title: String,
        result: JSONArray,
        folderName: String,
    ) {
        val temp = files.newTempFile(".pdf")
        val revision: Long
        try {
            val restored = restoredReaderPdf(context, document.id, document.updatedAt)
            revision = if (restored != null) {
                directory.copyIn("$title.pdf", "application/pdf", restored)
                document.updatedAt
            } else {
                writePdf(document, temp).also { directory.copyIn("$title.pdf", "application/pdf", temp) }
            }
        } finally {
            temp.delete()
        }
        val latest = checkNotNull(documents.document(document.id))
        val pages = documents.pages(document.id)
        check(latest.updatedAt == revision && !latest.isDraft && latest.deletedAt == null) {
            "Document changed during backup: ${document.title}"
        }
        val originals = directory.directory("orig")
        val processed = directory.directory("proc")
        for ((index, page) in pages.withIndex()) {
            val filename = "${index + 1}.jpg"
            originals.copyIn(filename, "image/jpeg", File(page.originalPath))
            processed.copyIn(filename, "image/jpeg", File(checkNotNull(page.processedPath)))
        }
        check(documents.document(document.id)?.updatedAt == revision) {
            "Document changed during backup: ${document.title}"
        }
        result.put(latest.backupJson()
            .put("folderDirectory", folderName)
            .put("documentDirectory", title)
            .put("pages", JSONArray(pages.map(PageEntity::backupJson)))
            .put("annotations", JSONArray(annotations.all("doc:${document.id}").map(AnnotationEntity::backupJson))))
    }

    internal suspend fun restore(tree: BackupTree, progress: (Float) -> Unit): BackupResult =
        withContext(Dispatchers.IO) {
            val manifest = tree.required("library.json").read().bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.opt("schemaVersion") == 1) {
                "Unsupported backup schema version: ${manifest.opt("schemaVersion") ?: "missing"}"
            }
            val folderRows = manifest.getJSONArray("folders").objects().map { it.readFolder() }
            val items = manifest.getJSONArray("documents").objects().map(::readBackupDocument)
            require(folderRows.map { it.id }.distinct().size == folderRows.size) { "Duplicate folder ID in backup" }
            require(items.map { it.document.id }.distinct().size == items.size) { "Duplicate document ID in backup" }
            val tagRows = manifest.optJSONArray("tags")?.objects().orEmpty().map { it.readTag() }
            val links = manifest.optJSONArray("documentTags")?.objects().orEmpty().map {
                DocumentTagEntity(it.getString("documentId"), it.getString("tagId"))
            }
            val tagIds = tagRows.mapTo(hashSetOf(), TagEntity::id)
            val documentIds = items.mapTo(hashSetOf()) { it.document.id }
            require(tagIds.size == tagRows.size) { "Duplicate tag ID in backup" }
            require(links.all { it.tagId in tagIds && it.documentId in documentIds }) { "Invalid document tag link" }
            val linksByDocument = links.groupBy(DocumentTagEntity::documentId)
            progress(0f)
            val restoredTagIds = database.withTransaction {
                for (folder in folderRows) {
                    if (folders.folder(folder.id) == null) folders.insert(folder)
                }
                tagRows.associate { tag ->
                    val existing = tags.byName(tag.name)
                    val id = existing?.id ?: run {
                        val availableId = if (tags.byId(tag.id) == null) tag.id else UUID.randomUUID().toString()
                        tags.insert(tag.copy(id = availableId))
                        availableId
                    }
                    tag.id to id
                }
            }
            var skipped = 0
            for ((index, item) in items.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (documents.document(item.document.id) != null) {
                    skipped++
                } else {
                    val restoredLinks = linksByDocument[item.document.id].orEmpty()
                        .map { it.copy(tagId = restoredTagIds.getValue(it.tagId)) }.distinct()
                    files.pageCreationLock.withLock { restoreDocument(tree, item, restoredLinks) }
                }
                progress((index + 1f) / items.size)
            }
            progress(1f)
            BackupResult("", items.size - skipped, skipped)
        }

    private data class BackupDocument(
        val document: DocumentEntity,
        val folderName: String,
        val title: String,
        val pages: List<PageEntity>,
        val annotations: List<AnnotationEntity>,
    )

    private fun readBackupDocument(json: JSONObject): BackupDocument {
        val document = json.readDocument()
        document.id.checkedName()
        val pages = json.getJSONArray("pages").objects().map { it.readPage(document.id) }
        val annotations = json.getJSONArray("annotations").objects().map { it.readAnnotation() }
        require(pages.map { it.index } == pages.indices.toList()) { "Invalid page order for ${document.title}" }
        require(annotations.all { it.pdfKey == "doc:${document.id}" }) { "Invalid annotation document" }
        return BackupDocument(
            document, json.getString("folderDirectory").checkedName(),
            json.getString("documentDirectory").checkedName(), pages, annotations,
        )
    }

    private suspend fun restoreDocument(
        tree: BackupTree,
        item: BackupDocument,
        links: List<DocumentTagEntity>,
    ) {
        val document = item.document
        val directory = tree.required(item.folderName).required(item.title)
        val originals = directory.required("orig")
        val processed = directory.required("proc")
        val ids = remapPageIds(item.pages)
        require(ids.size == item.pages.size) { "Duplicate page ID in backup" }
        val archive = restoredReaderPdfPath(context, document.id, document.updatedAt)
        var committed = false
        try {
            val restored = item.pages.mapIndexed { index, page ->
                val id = ids.getValue(page.id)
                val original = files.originalFile(id)
                val processedFile = files.processedFile(id)
                originals.required("${index + 1}.jpg").copyOut(original)
                processed.required("${index + 1}.jpg").copyOut(processedFile)
                page.copy(
                    id = id, originalPath = original.path, processedPath = processedFile.path,
                    idFrontId = ids[page.idFrontId],
                )
            }
            directory.required("${item.title}.pdf").copyOut(archive)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                database.withTransaction {
                    documents.insertDocument(document.copy(coverPageId = ids[document.coverPageId]))
                    restored.forEach { documents.insertPage(it) }
                    tags.insertLinks(links)
                    if (item.annotations.isNotEmpty()) annotations.insert(item.annotations)
                }
                committed = true
            }
        } finally {
            if (!committed) {
                archive.delete()
                ids.values.forEach(files::deletePageFiles)
            }
        }
    }
}

internal fun restoredReaderPdfDir(context: Context, id: String): File {
    val name = UUID.nameUUIDFromBytes(id.toByteArray())
    return File(context.filesDir, "backup/restored-pdfs/$name")
}

private fun restoredReaderPdfPath(context: Context, id: String, revision: Long): File =
    File(restoredReaderPdfDir(context, id), "$revision.pdf")

internal fun restoredReaderPdf(context: Context, id: String, revision: Long): File? =
    restoredReaderPdfPath(context, id, revision).takeIf { it.isFile }

private fun String.checkedName(): String {
    require(isNotEmpty() && this != "." && this != ".." && '/' !in this && '\\' !in this && '\u0000' !in this) {
        "Invalid path in backup"
    }
    return this
}

private fun BackupTree.required(name: String): BackupTree =
    child(name.checkedName()) ?: throw IOException("Backup entry missing: $name")

private suspend fun BackupTree.copyIn(name: String, mime: String, file: File) {
    val job = currentCoroutineContext()
    write(name, mime) { output -> file.inputStream().use { it.copyCancellable(output, job::ensureActive) } }
}

private suspend fun BackupTree.copyOut(file: File) {
    val job = currentCoroutineContext()
    file.parentFile?.mkdirs()
    read().use { input -> file.outputStream().use { input.copyCancellable(it, job::ensureActive) } }
}

private fun InputStream.copyCancellable(output: OutputStream, check: () -> Unit) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        check()
        val bytes = read(buffer)
        if (bytes < 0) return
        output.write(buffer, 0, bytes)
    }
}

private class Names {
    private val used = hashSetOf("library.json")

    fun take(title: String): String {
        val cleaned = title.map { char ->
            if (char in "<>:\"/\\|?*" || char.isISOControl()) '_' else char
        }.joinToString("").take(64).trim(' ', '.').ifBlank { "Untitled" }
        val stem = if (Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\.|$)").containsMatchIn(cleaned)) {
            "_$cleaned"
        } else cleaned
        return generateSequence(1) { it + 1 }.map { if (it == 1) stem else "$stem ($it)" }
            .first { used.add(it.lowercase(Locale.ROOT)) }
    }
}

