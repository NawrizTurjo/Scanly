package dev.turjo.paperless.core.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SweepListing(
    val pageIds: List<String>,
    val transientFiles: List<Pair<File, Long>>,
    val cachedPdfs: List<File>,
)

internal data class SweepSelection(val pageIds: List<String>, val cacheFiles: List<File>)

internal fun selectOrphans(
    listing: SweepListing,
    pageIds: Set<String>,
    liveDocumentIds: Set<String>,
    nowMs: Long,
): SweepSelection {
    val cutoff = nowMs - TimeUnit.HOURS.toMillis(24)
    return SweepSelection(
        listing.pageIds.filterNot(pageIds::contains),
        listing.transientFiles.filter { (_, modified) -> modified in 1 until cutoff }.map { it.first } +
            listing.cachedPdfs.filter { it.extension == "pdf" && it.nameWithoutExtension !in liveDocumentIds },
    )
}

fun enqueueOrphanSweep(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "orphan-sweep",
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<OrphanSweepWorker>().build(),
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OrphanSweepServices {
    fun database(): PaperlessDatabase
    fun files(): FileStore
}

class OrphanSweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val services = EntryPointAccessors.fromApplication(context, OrphanSweepServices::class.java)
        val files = services.files()
        val database = services.database()
        val cache = context.cacheDir
        val transientFiles = listOf("tmp", "tools/in", "exports").flatMap { path ->
            File(cache, path).walkTopDown().filter(File::isFile).map { it to it.lastModified() }.toList()
        }.toMap()
        files.pageCreationLock.withLock {
            // Page copies made inside Room transactions and staged copies must both finish before cleanup.
            database.withTransaction {
                val listing = SweepListing(
                    File(context.filesDir, "pages").listFiles().orEmpty().filter(File::isDirectory).map(File::getName),
                    transientFiles.toList(),
                    File(cache, "pdf").listFiles().orEmpty().filter(File::isFile),
                )
                val dao = database.documentDao()
                val selection = selectOrphans(
                    listing, dao.pageIds().toSet(), dao.liveDocumentIds().toSet(), System.currentTimeMillis(),
                )
                val pagesDeleted = selection.pageIds.map { files.pageDir(it).deleteRecursively() }.all { it }
                val cacheDeleted = selection.cacheFiles.map { file ->
                    val originalTimestamp = transientFiles[file]
                    (originalTimestamp != null && file.lastModified() != originalTimestamp) ||
                        file.delete() || !file.exists()
                }.all { it }
                if (pagesDeleted && cacheDeleted) Result.success() else Result.retry()
            }
        }
    }
}
