package dev.turjo.paperless.feature.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.AtomicFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class BatchStatus { DONE, SKIPPED, FAILED }
internal data class BatchItem(
    val name: String,
    val status: BatchStatus,
    val reason: String? = null,
    val outputs: List<Output> = emptyList(),
)

internal fun encodeResults(results: List<BatchItem>): String = JSONArray(results.map { item ->
    JSONObject().put("name", item.name).put("status", item.status.name)
        .put("reason", item.reason ?: JSONObject.NULL)
        .put("outputs", JSONArray(item.outputs.map {
            JSONObject().put("path", it.file.path).put("note", it.note ?: JSONObject.NULL).put("bytes", it.bytes)
        }))
}).toString()

private fun JSONObject.optional(key: String) = if (isNull(key)) null else getString(key)
private fun <T> JSONArray.read(block: (JSONObject) -> T) = List(length()) { block(getJSONObject(it)) }

internal fun decodeResults(json: String): List<BatchItem> = JSONArray(json).read { item ->
    BatchItem(item.getString("name"), BatchStatus.valueOf(item.getString("status")), item.optional("reason"),
        item.getJSONArray("outputs").read { Output(File(it.getString("path")), it.optional("note"), it.getLong("bytes")) })
}

internal class BatchStore(context: Context, id: String) {
    private val root = File(context.filesDir, "batch")
    val directory = File(root, id)
    private val results = AtomicFile(File(root, "$id.json"))
    private val request = AtomicFile(File(directory, "request.json"))
    private val stop = File(directory, "cancel")
    private val finished = File(directory, "finished")

    fun prepare(job: ToolRequest) {
        check(directory.mkdirs() || directory.isDirectory)
        request.writeText(JSONObject()
            .put("tool", job.tool.name)
            .put("inputs", JSONArray(job.inputs.map {
                JSONObject().put("path", it.file.path).put("name", it.name)
            }))
            .put("options", JSONObject(job.options)).toString())
        save(emptyList())
    }

    fun request(): ToolRequest {
        val job = JSONObject(request.readFully().decodeToString())
        val options = job.getJSONObject("options")
        return ToolRequest(Tool.valueOf(job.getString("tool")),
            job.getJSONArray("inputs").read { Input(File(it.getString("path")), it.getString("name")) },
            options.keys().asSequence().associateWith { options.getString(it) })
    }

    fun finished() = finished.exists()
    fun finish() {
        finished.createNewFile()
        clearRequest()
        File(directory, "in").deleteRecursively()
    }

    fun clearRequest() = request.delete()
    fun results() = synchronized(resultLock) { decodeResults(results.readFully().decodeToString()) }
    fun save(items: List<BatchItem>) = synchronized(resultLock) { results.writeText(encodeResults(items)) }
    fun cancel() { stop.createNewFile() }
    fun cancelled() = stop.exists()

    private companion object {
        val resultLock = Any()
    }

    private fun AtomicFile.writeText(text: String) {
        val stream = startWrite()
        try {
            stream.write(text.encodeToByteArray())
            finishWrite(stream)
        } catch (error: Exception) {
            failWrite(stream)
            throw error
        }
    }
}

internal suspend fun runBatch(
    request: ToolRequest,
    ops: ToolOps,
    completed: List<BatchItem>,
    cancelled: () -> Boolean,
    outputDirectory: (Int) -> File,
    publish: suspend (List<BatchItem>) -> Unit,
) {
    val inputs = request.inputs
    if (request.tool == Tool.MERGE) {
        if (completed.size == inputs.size) return
        val sorted = inputs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val result = if (cancelled()) BatchItem("", BatchStatus.SKIPPED, "Cancelled") else
            runBatchItem(request.copy(inputs = sorted), ops, outputDirectory(0))
        publish(inputs.mapIndexed { index, input ->
            result.copy(name = input.name, outputs = if (index == 0) result.outputs else emptyList())
        })
        return
    }

    val results = completed.toMutableList()
    for (index in completed.size until inputs.size) {
        val input = inputs[index]
        results += if (cancelled()) BatchItem(input.name, BatchStatus.SKIPPED, "Cancelled")
        else runBatchItem(request.copy(inputs = listOf(input)), ops, outputDirectory(index))
        publish(results.toList())
    }
}

private suspend fun runBatchItem(request: ToolRequest, ops: ToolOps, directory: File): BatchItem {
    val name = request.inputs.first().name
    return try {
        directory.deleteRecursively()
        check(directory.mkdirs())
        val outputs = ops.run(request, directory)
        val note = outputs.mapNotNull { it.note }.joinToString(" · ").ifBlank { null }
        val status = if (outputs.singleOrNull()?.note in setOf("Already uniform", "Already compact")) {
            BatchStatus.SKIPPED
        } else BatchStatus.DONE
        BatchItem(name, status, note, outputs)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        BatchItem(name, BatchStatus.FAILED, error.toolMessage(request.options["Action"] == "Remove"))
    }
}

class BatchWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = BatchStore(applicationContext, id.toString())
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        try {
            if (store.finished()) {
                store.finish()
                return@withContext Result.success()
            }
            val request = store.request()
            val completed = store.results()
            val total = request.inputs.size
            setForeground(progressNotification(notifications, completed.size, total))
            val ops = PdfToolOps(applicationContext)
            runBatch(request, ops, completed, store::cancelled, { index -> File(store.directory, "out/$index") }) {
                store.save(it)
                setProgress(workDataOf("processed" to it.size))
                setForeground(progressNotification(notifications, it.size, total))
            }
            store.finish()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            store.clearRequest()
            Result.failure()
        }
    }

    private fun progressNotification(manager: NotificationManager, done: Int, total: Int): ForegroundInfo {
        val context = applicationContext
        val channel = "batch_jobs"
        manager.createNotificationChannel(NotificationChannel(channel, "Batch jobs", NotificationManager.IMPORTANCE_LOW))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Processing PDFs")
            .setContentText("$done of $total files")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done, false)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
