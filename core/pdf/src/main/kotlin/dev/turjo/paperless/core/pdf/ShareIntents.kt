package dev.turjo.paperless.core.pdf

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

class ExportFileProvider : FileProvider()

object ShareIntents {
    fun forFiles(context: Context, files: List<File>, mime: String): Intent =
        forFiles(files, mime) { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

    internal fun forFiles(files: List<File>, mime: String, uriForFile: (File) -> Uri): Intent {
        require(files.isNotEmpty())
        val uris = files.map(uriForFile)
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
                Intent.EXTRA_STREAM, ArrayList(uris),
            )
        }
        send.type = mime
        send.clipData = ClipData(null, arrayOf(mime), ClipData.Item(uris.first())).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }
}

fun exportCacheDir(context: Context): File = File(context.cacheDir, "exports").apply {
    if (!mkdirs() && !isDirectory) throw IOException("Cannot create export cache directory")
}
