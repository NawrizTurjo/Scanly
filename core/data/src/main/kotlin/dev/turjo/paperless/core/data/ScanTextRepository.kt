package dev.turjo.paperless.core.data

import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.turjo.paperless.core.model.OcrLanguage
import dev.turjo.paperless.core.ocr.RecognizedText
import dev.turjo.paperless.core.ocr.RecognizedWord
import dev.turjo.paperless.core.ocr.TextRecognizer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Entity(
    tableName = "scan_text",
    foreignKeys = [ForeignKey(PageEntity::class, ["id"], ["pageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("pageId", unique = true)],
)
internal data class ScanTextEntity(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val pageId: String,
    val processedHash: String,
    val processedPath: String,
    val language: String,
    val text: String,
    val words: String,
)

@Fts4(contentEntity = ScanTextEntity::class)
@Entity(tableName = "scan_text_fts")
internal data class ScanTextFts(val text: String)

internal data class OcrSource(val id: String, val processedHash: String, val processedPath: String)

@Dao
internal interface ScanTextDao {
    @Query("SELECT id, processedHash, processedPath FROM pages WHERE id = :id AND processedHash IS NOT NULL AND processedPath IS NOT NULL")
    suspend fun source(id: String): OcrSource?

    @Query("""
        SELECT p.id, p.processedHash, p.processedPath FROM pages p
        JOIN documents d ON d.id = p.documentId
        LEFT JOIN scan_text t ON t.pageId = p.id
        WHERE p.processedHash IS NOT NULL AND p.processedPath IS NOT NULL AND d.deletedAt IS NULL
          AND (t.pageId IS NULL OR t.processedHash != p.processedHash
               OR t.processedPath != p.processedPath OR t.language != :language)
        ORDER BY p.createdAt
    """)
    fun pending(language: String): kotlinx.coroutines.flow.Flow<List<OcrSource>>

    @Query("SELECT * FROM scan_text WHERE pageId = :id")
    suspend fun get(id: String): ScanTextEntity?

    @Query("""
        SELECT t.text FROM scan_text t JOIN pages p ON p.id = t.pageId
        WHERE p.id = :id AND t.processedHash = p.processedHash AND t.processedPath = p.processedPath
            AND t.language = :language
    """)
    fun observe(id: String, language: String): Flow<String?>

    @Query("""
        UPDATE scan_text SET processedHash = :newHash
        WHERE pageId = :pageId AND processedHash = :oldHash AND processedPath = :path
    """)
    suspend fun rekeyProcessedHash(pageId: String, oldHash: String, newHash: String, path: String)

    @Upsert
    suspend fun put(row: ScanTextEntity)

    @Query("""
        SELECT 'doc:' || d.id AS pdfKey, d.title, p.`index` AS pageIndex,
            snippet(scan_text_fts, '[', ']', '…', 0, 12) AS snippet
        FROM scan_text_fts
        JOIN scan_text t ON t.rowid = scan_text_fts.rowid
        JOIN pages p ON p.id = t.pageId AND p.processedHash = t.processedHash AND p.processedPath = t.processedPath
        JOIN documents d ON d.id = p.documentId
        LEFT JOIN folders f ON f.id = d.folderId
        LEFT JOIN folders parent ON parent.id = f.parentId
        WHERE scan_text_fts MATCH :query AND d.isDraft = 0 AND d.deletedAt IS NULL
          AND COALESCE(f.isPrivate, 0) = 0 AND COALESCE(parent.isPrivate, 0) = 0
        ORDER BY d.id, p.`index`
        LIMIT 100
    """)
    fun search(query: String): Flow<List<TitledTextHit>>
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `scan_text` (`rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`pageId` TEXT NOT NULL, `processedHash` TEXT NOT NULL, `processedPath` TEXT NOT NULL, " +
                "`language` TEXT NOT NULL, `text` TEXT NOT NULL, `words` TEXT NOT NULL, " +
                "FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_text_pageId` ON `scan_text` (`pageId`)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `scan_text_fts` USING FTS4(`text` TEXT NOT NULL, content=`scan_text`)")
        for (operation in listOf("UPDATE", "DELETE")) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_scan_text_fts_BEFORE_$operation " +
                    "BEFORE $operation ON scan_text BEGIN DELETE FROM scan_text_fts WHERE docid = OLD.rowid; END",
            )
        }
        for (operation in listOf("UPDATE", "INSERT")) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_scan_text_fts_AFTER_$operation " +
                    "AFTER $operation ON scan_text BEGIN INSERT INTO scan_text_fts(docid, text) VALUES (NEW.rowid, NEW.text); END",
            )
        }
    }
}

interface ScanPageText {
    val automatic: Flow<Boolean>
    fun observe(pageId: String): Flow<String?>
    suspend fun recognize(pageId: String, image: File? = null, hash: String? = null): RecognizedText?
}

@Singleton
class ScanTextRepository @Inject constructor(
    private val database: PaperlessDatabase,
    private val settings: SettingsRepository,
    private val recognizer: TextRecognizer,
    @ApplicationScope private val scope: CoroutineScope,
    private val power: PowerState = PowerState.ALWAYS_CHARGING,
) : ScanPageText {
    private val dao = database.scanTextDao()
    private val lock = Mutex()
    private val language = settings.settings.map { it.ocrLanguage }.distinctUntilChanged()
    override val automatic = settings.settings.map { it.automaticOcr }.distinctUntilChanged()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observe(pageId: String) =
        language.flatMapLatest { dao.observe(pageId, it.code) }.distinctUntilChanged()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            // With "only while charging", pending pages wait until the phone is plugged in.
            combine(settings.settings, power.charging) { settings, charging ->
                settings.ocrLanguage.takeIf { settings.automaticOcr && (charging || !settings.ocrOnlyWhileCharging) }
            }
                .distinctUntilChanged()
                .flatMapLatest { language ->
                    if (language == null) flowOf(emptyList())
                    else dao.pending(language.code).map { pages -> pages.map { it to language } }
                }.conflate().collect { pending ->
                    for ((source, language) in pending) {
                        try {
                            process(source.id, language, automatic = true, expected = source)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w("ScanTextRepository", "Text recognition failed for ${source.id}", error)
                        }
                    }
                }
        }
    }

    override suspend fun recognize(pageId: String, image: File?, hash: String?): RecognizedText? =
        process(pageId, settings.settings.first().ocrLanguage, expected = if (image != null && hash != null) {
            OcrSource(pageId, hash, image.path)
        } else null)

    private suspend fun process(
        pageId: String,
        language: OcrLanguage,
        automatic: Boolean = false,
        expected: OcrSource? = null,
    ): RecognizedText? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (automatic && !settings.settings.first().let { it.automaticOcr && it.ocrLanguage == language } ||
                automatic && settings.settings.first().ocrOnlyWhileCharging && !power.charging.first()
            ) {
                return@withLock null
            }
            val source = dao.source(pageId)
            if (expected != null && expected != source) return@withLock null
            if (source == null) return@withLock null
            val existing = dao.get(pageId)
            if (existing != null && existing.processedHash == source.processedHash &&
                existing.processedPath == source.processedPath && existing.language == language.code
            ) {
                return@withLock RecognizedText(existing.text, decodeWords(existing.words))
            }
            val file = File(source.processedPath)
            if (!file.isFile) return@withLock null
            val modified = file.lastModified()
            val result = recognizer.recognize(file, language.code)
            database.withTransaction {
                if (dao.source(pageId) != source || file.lastModified() != modified) return@withTransaction null
                dao.put(ScanTextEntity(existing?.rowid ?: 0, pageId, source.processedHash,
                    source.processedPath, language.code, result.text, encodeWords(result.words)))
                result
            }
        }
    }
}

private fun encodeWords(words: List<RecognizedWord>): String = JSONArray().apply {
    for (word in words) {
        put(JSONArray(listOf(word.text, word.confidence, word.left, word.top, word.right, word.bottom)))
    }
}.toString()

private fun decodeWords(json: String): List<RecognizedWord> {
    val words = JSONArray(json)
    return List(words.length()) { index ->
        val word = words.getJSONArray(index)
        RecognizedWord(word.getString(0), word.getDouble(1).toFloat(), word.getDouble(2).toFloat(),
            word.getDouble(3).toFloat(), word.getDouble(4).toFloat(), word.getDouble(5).toFloat())
    }
}
