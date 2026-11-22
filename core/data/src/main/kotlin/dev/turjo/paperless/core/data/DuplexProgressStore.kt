package dev.turjo.paperless.core.data

import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class DuplexPhase { FRONTS, BACKS }

data class DuplexProgress(
    val phase: DuplexPhase = DuplexPhase.FRONTS,
    val enabled: Boolean = false,
    val sides: Map<String, DuplexPhase> = emptyMap(),
)

interface DuplexProgressStore {
    suspend fun read(sessionId: String): DuplexProgress
    suspend fun write(sessionId: String, progress: DuplexProgress)
}

class FileDuplexProgressStore @Inject constructor(private val files: FileStore) : DuplexProgressStore {
    override suspend fun read(sessionId: String): DuplexProgress = withContext(Dispatchers.IO) {
        val bytes = try {
            files.duplexFile(sessionId).readFully()
        } catch (_: FileNotFoundException) {
            return@withContext DuplexProgress()
        }
        val json = JSONObject(bytes.decodeToString())
        val sides = json.getJSONArray("sides")
        DuplexProgress(
            phase = DuplexPhase.valueOf(json.getString("phase")),
            enabled = json.getBoolean("enabled"),
            sides = buildMap {
                for (index in 0 until sides.length()) {
                    val side = sides.getJSONArray(index)
                    put(side.getString(0), DuplexPhase.valueOf(side.getString(1)))
                }
            },
        )
    }

    override suspend fun write(sessionId: String, progress: DuplexProgress): Unit = withContext(Dispatchers.IO) {
        val file = files.duplexFile(sessionId)
        if (progress == DuplexProgress()) {
            file.delete()
            return@withContext
        }
        val sides = JSONArray()
        for ((id, phase) in progress.sides) sides.put(JSONArray().put(id).put(phase.name))
        val json = JSONObject().put("phase", progress.phase.name).put("enabled", progress.enabled).put("sides", sides)
        val stream = file.startWrite()
        try {
            stream.write(json.toString().encodeToByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
