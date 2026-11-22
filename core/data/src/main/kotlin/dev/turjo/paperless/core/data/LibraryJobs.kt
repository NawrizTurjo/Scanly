package dev.turjo.paperless.core.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class BackupJobState(val active: Boolean, val progress: Int?, val lastResult: String? = null)

object LibraryJobs {
    internal const val BACKUP = "library-backup"
    private const val INDEX = "library-index"
    private val resultVersion = MutableStateFlow(0L)

    fun backup(context: Context, tree: Uri, folderIds: Set<String>?, restore: Boolean) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(workDataOf(
                "tree" to tree.toString(),
                "restore" to restore,
                "folders" to folderIds?.toTypedArray(),
            ))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(BACKUP, ExistingWorkPolicy.KEEP, request)
    }

    fun index(context: Context, rebuild: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<TextIndexWorker>()
            .setInputData(workDataOf("rebuild" to rebuild))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(INDEX, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancelBackup(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(BACKUP)
    }

    fun observeBackup(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(BACKUP)
        .combine(resultVersion.map { lastBackupResult(context) }) { infos, lastResult ->
            val active = infos.firstOrNull { !it.state.isFinished }
            BackupJobState(active != null, active?.progress?.getInt("percent", 0), lastResult)
        }

    internal fun resultSaved() = resultVersion.update { it + 1 }

    private suspend fun lastBackupResult(context: Context): String? = withContext(Dispatchers.IO) {
        val file = summaryFile(context)
        try {
            val summary = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
            if (summary.optString("status") == "failed") context.getString(R.string.backup_job_failed)
            else summary.getString("message")
        } catch (_: java.io.FileNotFoundException) {
            null
        }
    }

    internal fun summaryFile(context: Context) = File(context.filesDir, "backup/last.json")
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibraryWorkerServices {
    fun backupService(): BackupService
    fun textIndexer(): TextIndexer
}

class TextIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val failed = workerServices(applicationContext).textIndexer().indexLibrary(inputData.getBoolean("rebuild", false))
        return Result.success(workDataOf("unreadablePdfs" to failed))
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val restore = inputData.getBoolean("restore", false)
    private val title = if (restore) "Restoring library" else "Backing up library"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tree = inputData.getString("tree")?.let(Uri::parse) ?: return@withContext Result.failure()
        try {
            manager.createNotificationChannel(NotificationChannel("backup", "Backup", NotificationManager.IMPORTANCE_LOW))
            setForeground(ForegroundInfo(NOTIFICATION_ID, notification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            val service = workerServices(applicationContext).backupService()
            var shown = 0
            val progress: (Float) -> Unit = { fraction ->
                val percent = (fraction * 100).toInt()
                if (percent != shown) {
                    shown = percent
                    manager.notify(NOTIFICATION_ID, notification(percent))
                    setProgressAsync(workDataOf("percent" to percent))
                }
            }
            val result = if (restore) {
                service.restore(tree, progress)
            } else {
                service.backup(tree, inputData.getNullableStringArray("folders")?.filterNotNull()?.toSet(), progress)
            }
            val message = if (restore) {
                "Restored ${result.documents} documents · ${result.skipped} already present"
            } else {
                "Backed up ${result.documents} documents to ${result.location}"
            }
            saveResult("success", message, result)
            if (restore) LibraryJobs.index(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                saveResult("cancelled", if (restore) "Restore cancelled" else "Backup cancelled")
            }
            throw cancelled
        } catch (error: Exception) {
            Log.w("BackupWorker", if (restore) "Restore failed" else "Backup failed", error)
            saveResult("failed", applicationContext.getString(R.string.backup_job_failed))
            Result.failure()
        }
    }

    private fun notification(percent: Int): Notification = Notification.Builder(applicationContext, "backup")
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(title)
        .setProgress(100, percent, false)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .addAction(Notification.Action.Builder(
            null, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        ).build())
        .build()

    private fun saveResult(status: String, message: String, result: BackupResult? = null) {
        val file = LibraryJobs.summaryFile(applicationContext)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val json = JSONObject().put("status", status).put("message", message)
            .put("time", System.currentTimeMillis()).put("documents", result?.documents)
            .put("skipped", result?.skipped).put("location", result?.location)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString(2).toByteArray())
            atomic.finishWrite(stream)
            LibraryJobs.resultSaved()
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 701
    }
}

private fun workerServices(context: Context): LibraryWorkerServices =
    EntryPointAccessors.fromApplication(context, LibraryWorkerServices::class.java)

