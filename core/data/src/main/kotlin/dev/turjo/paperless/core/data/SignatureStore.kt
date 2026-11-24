package dev.turjo.paperless.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.SignaturePlacement
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SavedSignature(val id: String, val created: Long, val aspect: Float)

@Singleton
class SignatureStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val directory = File(context.filesDir, "signature-ink")
    private val index = File(directory, "index.json")

    fun placedFile(id: String): File = placedSignatureFile(context, id)

    private fun savedFile(id: String) = File(directory, signatureFileName(id))

    suspend fun bitmap(id: String, placed: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val image = if (placed) placedFile(id) else savedFile(id)
        BitmapFactory.decodeFile(image.path)
    }

    suspend fun list(): List<SavedSignature> = withContext(Dispatchers.IO) {
        lock.withLock { readIndex() }
    }

    suspend fun save(bitmap: Bitmap): SavedSignature = withContext(Dispatchers.IO) {
        lock.withLock {
            val entries = readIndex()
            check(entries.size < LIMIT)
            val signature = SavedSignature(
                UUID.randomUUID().toString(), System.currentTimeMillis(), bitmap.width.toFloat() / bitmap.height,
            )
            val output = savedFile(signature.id)
            try {
                output.writeAtomically { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                writeIndex(entries + signature)
            } catch (error: Exception) {
                output.delete()
                throw error
            }
            signature
        }
    }

    suspend fun retainForPlacement(id: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val destination = placedFile(id)
            if (!destination.isFile) {
                savedFile(id).inputStream().use { source ->
                    destination.writeAtomically { source.copyTo(it) }
                }
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val source = savedFile(id)
            writeIndex(readIndex().filterNot { it.id == id })
            Files.deleteIfExists(source.toPath())
        }
    }

    private fun readIndex(): List<SavedSignature> = index.readArray().objects {
        SavedSignature(getString("id"), getLong("created"), getDouble("aspect").toFloat())
    }

    private fun writeIndex(entries: List<SavedSignature>) = index.writeArray(entries.map {
        JSONObject().put("id", it.id).put("created", it.created).put("aspect", it.aspect.toDouble())
    })

    companion object {
        const val LIMIT = 5
        private val lock = Mutex()
    }
}

internal fun placedSignatureFile(context: Context, id: String) =
    File(context.filesDir, "signature-ink/placed/${signatureFileName(id)}")

private fun signatureFileName(id: String): String {
    require(UUID.fromString(id).toString() == id)
    return "$id.png"
}

internal fun File.readSignatures() = readArray().objects(JSONObject::toSignaturePlacement)

internal fun File.writeSignatures(entries: List<SignaturePlacement>) {
    if (entries.isEmpty()) Files.deleteIfExists(toPath())
    else writeArray(entries.map(SignaturePlacement::toJson))
}

internal fun SignaturePlacement.toJson() = JSONObject().put("id", signatureId).put(
    "bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map(Float::toDouble)),
)

internal fun JSONObject.toSignaturePlacement(): SignaturePlacement {
    val rect = getJSONArray("bounds")
    return SignaturePlacement(getString("id"), PageRect(
        rect.getDouble(0).toFloat(), rect.getDouble(1).toFloat(), rect.getDouble(2).toFloat(), rect.getDouble(3).toFloat(),
    ))
}

private fun File.readArray(): JSONArray = try {
    JSONArray(readText())
} catch (_: FileNotFoundException) {
    JSONArray()
}

private fun <T> JSONArray.objects(read: JSONObject.() -> T) = List(length()) { getJSONObject(it).read() }

private fun File.writeArray(entries: List<JSONObject>) =
    writeAtomically { it.write(JSONArray(entries).toString().toByteArray(Charsets.UTF_8)) }

private inline fun File.writeAtomically(block: (FileOutputStream) -> Unit) {
    val path = absoluteFile.toPath()
    Files.createDirectories(path.parent)
    val temp = Files.createTempFile(path.parent, "signature-", ".tmp")
    try {
        temp.toFile().outputStream().use { stream ->
            block(stream)
            stream.fd.sync()
        }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}
