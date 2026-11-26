package dev.turjo.paperless.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.turjo.paperless.core.model.Quad
import dev.turjo.paperless.core.model.paramsHash
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OriginalShrinkResult(val pages: Int, val bytes: Long)

@Singleton
class OriginalShrinkJob internal constructor(
    private val database: PaperlessDatabase,
    private val files: FileStore,
    private val settings: SettingsRepository,
    private val whileIdle: suspend (String, suspend () -> Boolean) -> Boolean,
) {
    @Inject
    constructor(
        database: PaperlessDatabase,
        files: FileStore,
        settings: SettingsRepository,
        processing: QueuedPageProcessing,
    ) : this(database, files, settings, processing::shrinkWhileIdle)

    private val lock = Mutex()
    private val dao = database.documentDao()

    suspend fun run(now: Long = System.currentTimeMillis()): OriginalShrinkResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val days = settings.settings.first().shrinkOriginalsAfterDays
                ?: return@withLock OriginalShrinkResult(0, 0)
            val cutoff = now - TimeUnit.DAYS.toMillis(days.toLong())
            var pages = 0
            var bytes = 0L
            for (page in dao.originalShrinkCandidates(cutoff)) {
                if (settings.settings.first().shrinkOriginalsAfterDays != days) break
                if (page.toModel().isIdComposite || page.processedPath == page.originalPath) continue
                var saved = 0L
                if (whileIdle(page.id) {
                        saved = shrink(page, cutoff, days)
                        saved > 0
                    }
                ) {
                    pages++
                    bytes += saved
                }
            }
            OriginalShrinkResult(pages, bytes)
        }
    }

    private suspend fun shrink(page: PageEntity, cutoff: Long, days: Int): Long {
        val original = File(page.originalPath)
        if (!original.isFile) return 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= SHRUNK_SIDE) return 0
        val scale = SHRUNK_SIDE.toFloat() / longSide
        val width = (bounds.outWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (bounds.outHeight * scale).roundToInt().coerceAtLeast(1)
        val oldBytes = original.length()
        val temp = File.createTempFile("shrink-", ".jpg", original.parentFile)
        try {
            if (!encodeSmaller(original, temp, longSide, width, height)) return 0
            val saved = oldBytes - temp.length()
            if (saved <= 0) return 0
            return withContext(NonCancellable) {
                if (settings.settings.first().shrinkOriginalsAfterDays != days) return@withContext 0L
                replaceOriginal(page, original, temp, cutoff, width, height, scale, saved)
            }
        } finally {
            temp.delete()
        }
    }

    private suspend fun replaceOriginal(
        page: PageEntity, original: File, temp: File, cutoff: Long,
        width: Int, height: Int, scale: Float, saved: Long,
    ): Long {
        val backup = File(temp.path + ".bak").toPath()
        var replaced = false
        var committed = false
        try {
            val result = database.withTransaction {
                val current = dao.page(page.id) ?: return@withTransaction 0L
                val document = dao.document(current.documentId) ?: return@withTransaction 0L
                if (current != page || document.isDraft || document.deletedAt != null || current.createdAt >= cutoff ||
                    dao.originalPathReferences(page.originalPath) != 1
                ) return@withTransaction 0L

                val before = files.pageWithSignatures(current)
                val after = before.copy(quad = scaledOriginalQuad(before.quad, scale))
                // A matching processed hash remains valid because the crop still covers the same image region.
                val hash = if (before.processedHash == before.paramsHash()) after.paramsHash() else before.processedHash
                Files.createLink(backup, original.toPath())
                dao.updatePage(current.copy(
                    originalWidth = width, originalHeight = height, quad = after.quad, processedHash = hash,
                ))
                if (current.processedHash != null && hash != null && hash != current.processedHash &&
                    current.processedPath != null
                ) {
                    database.scanTextDao().rekeyProcessedHash(page.id, current.processedHash, hash, current.processedPath)
                }
                Files.move(temp.toPath(), original.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                replaced = true
                saved
            }
            committed = true
            return result
        } catch (error: Exception) {
            if (replaced) {
                Files.move(backup, original.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                replaced = false
            }
            throw error
        } finally {
            // If restoration itself fails, keep the original bytes available beside the replacement.
            if (!replaced || committed) {
                backup.toFile().delete()
            }
        }
    }

    private fun encodeSmaller(original: File, temp: File, longSide: Int, width: Int, height: Int): Boolean {
        val orientation = ExifInterface(original.path).getAttribute(ExifInterface.TAG_ORIENTATION)
        var sample = 1
        while (longSide / (sample * 2) >= SHRUNK_SIDE) sample *= 2
        // BitmapFactory decodes the stored axes. Preserve their orientation for EXIF-aware readers.
        val bitmap = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: return false
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
            try {
                temp.outputStream().use {
                    if (!resized.compress(Bitmap.CompressFormat.JPEG, 90, it)) return false
                }
            } finally {
                if (resized !== bitmap) resized.recycle()
            }
        } finally {
            bitmap.recycle()
        }
        if (orientation != null) {
            ExifInterface(temp.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
                saveAttributes()
            }
        }
        return true
    }
}

internal fun scaledOriginalQuad(quad: Quad?, scale: Float): Quad? =
    quad?.takeUnless { it == Quad.FULL }?.let { Quad.fromArray(it.toArray().map { value -> value * scale }.toFloatArray()) }

private const val SHRUNK_SIDE = 2400
private const val SHRINK_WORK = "shrink-old-originals"

internal suspend fun scheduleOriginalShrink(context: Context, enabled: Boolean) {
    val manager = WorkManager.getInstance(context)
    val operation = if (enabled) {
        val request = PeriodicWorkRequestBuilder<OriginalShrinkWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build())
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        manager.enqueueUniquePeriodicWork(SHRINK_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    } else {
        manager.cancelUniqueWork(SHRINK_WORK)
    }
    operation.await()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OriginalShrinkServices {
    fun originalShrinkJob(): OriginalShrinkJob
}

class OriginalShrinkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = EntryPointAccessors.fromApplication(applicationContext, OriginalShrinkServices::class.java)
        return try {
            services.originalShrinkJob().run()
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        }
    }
}
