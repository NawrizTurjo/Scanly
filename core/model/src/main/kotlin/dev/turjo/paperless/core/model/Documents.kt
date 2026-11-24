package dev.turjo.paperless.core.model

import java.nio.ByteBuffer
import java.security.MessageDigest

data class Quad(
    val tlx: Float, val tly: Float,
    val trx: Float, val try_: Float,
    val brx: Float, val bry: Float,
    val blx: Float, val bly: Float,
) {
    fun toArray(): FloatArray = floatArrayOf(tlx, tly, trx, try_, brx, bry, blx, bly)

    companion object {
        val FULL = Quad(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

        fun fromArray(points: FloatArray): Quad {
            require(points.size == 8)
            return Quad(
                points[0], points[1], points[2], points[3],
                points[4], points[5], points[6], points[7],
            )
        }
    }
}

enum class PageFilter { ORIGINAL, AUTO, DOCUMENT, BW, GRAY, WHITEBOARD }
enum class PageMode { NORMAL, ID_FRONT, ID_BACK, BOOK_L, BOOK_R, WHITEBOARD }
enum class InkRemoval { BLUE, RED, GREEN }

data class Page(
    val id: String,
    val documentId: String,
    val index: Int,
    val mode: PageMode,
    val originalPath: String,
    val originalWidth: Int,
    val originalHeight: Int,
    val quad: Quad?,
    val rotation: Int,
    val filter: PageFilter,
    val brightness: Float,
    val contrast: Float,
    val shadowFix: Boolean,
    val glareFix: Boolean,
    val processedPath: String?,
    val processedHash: String?,
    val glareWarning: Boolean,
    val createdAt: Long,
    val deskew: Boolean = true,
    val inkRemoval: InkRemoval? = null,
    val signatures: List<SignaturePlacement> = emptyList(),
)

fun Page.paramsHash(): String {
    val bytes = ByteBuffer.allocate(8 * Float.SIZE_BYTES + 3 * Int.SIZE_BYTES + 5)
    val crop = quad?.takeUnless { it == Quad.FULL }
    bytes.put(if (crop == null) 0.toByte() else 1.toByte())
    crop?.toArray()?.forEach { bytes.putFloat(it) }
    bytes.putInt(rotation)
    bytes.putInt(brightness.toBits())
    bytes.putInt(contrast.toBits())
    bytes.put(if (shadowFix) 1.toByte() else 0.toByte())
    bytes.put(if (glareFix) 1.toByte() else 0.toByte())
    bytes.put(if (deskew) 1.toByte() else 0.toByte())
    bytes.put((inkRemoval?.ordinal?.plus(1) ?: 0).toByte())
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(bytes.array(), 0, bytes.position())
    digest.update(filter.name.toByteArray(Charsets.UTF_8))
    for ((id, rect) in signatures) {
        bytes.clear()
        val key = id.toByteArray(Charsets.UTF_8)
        bytes.putInt(key.size).putFloat(rect.left).putFloat(rect.top).putFloat(rect.right).putFloat(rect.bottom)
        digest.update(bytes.array(), 0, bytes.position())
        digest.update(key)
    }
    return digest.digest().toHexString()
}

data class Document(
    val id: String,
    val title: String,
    val folderId: String?,
    val isFavorite: Boolean,
    val isDraft: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val coverPageId: String?,
    val deletedAt: Long?,
)

data class Folder(
    val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
    val isPrivate: Boolean,
)

data class ScanSession(
    val id: String,
    val draftDocumentId: String,
    val startedAt: Long,
    val targetDocumentId: String?,
    val insertAt: Int?,
)

data class DocumentSummary(
    val id: String,
    val title: String,
    val pageCount: Int,
    val coverThumbPath: String?,
    val updatedAt: Long,
    val isFavorite: Boolean,
    val tags: List<Tag> = emptyList(),
)

data class DocumentWithPages(val document: Document, val pages: List<Page>)

data class Tag(val id: String, val name: String, val colorIndex: Int, val createdAt: Long) {
    companion object {
        const val COLOR_COUNT = 8
    }
}

data class TagSummary(val tag: Tag, val documentCount: Int)

data class FolderSummary(val folder: Folder, val documentCount: Int)

data class DeletedDocument(val id: String, val title: String, val deletedAt: Long)

enum class LibrarySort { NAME, UPDATED_DESC, CREATED_DESC }

data class LibraryFilter(
    val folderId: String? = null,
    val favouritesOnly: Boolean = false,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.UPDATED_DESC,
    val tagId: String? = null,
)
