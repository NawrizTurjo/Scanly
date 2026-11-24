package dev.turjo.paperless.feature.tools

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class RecentOutput(
    val name: String,
    val tool: Tool,
    val time: Long,
    val sourcePath: String,
    val path: String = sourcePath,
    val uri: String? = null,
)

internal data class ToolsHistory(
    val pins: List<Tool> = emptyList(),
    val recent: List<RecentOutput> = emptyList(),
)

internal interface ToolsPreferences {
    fun read(key: String): String
    fun write(key: String, value: String)
}

internal class ToolsHistoryStore(private val preferences: ToolsPreferences) {
    constructor(context: Context) : this(object : ToolsPreferences {
        private val prefs by lazy { context.getSharedPreferences("tools_prefs", Context.MODE_PRIVATE) }

        override fun read(key: String) = prefs.getString(key, "").orEmpty()
        override fun write(key: String, value: String) = prefs.edit { putString(key, value) }
    })

    companion object {
        private var instance: ToolsHistoryStore? = null

        @Synchronized
        fun shared(context: Context): ToolsHistoryStore =
            instance ?: ToolsHistoryStore(context.applicationContext).also { instance = it }
    }

    private val mutableHistory = MutableStateFlow(ToolsHistory())
    val history = mutableHistory.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val pins = preferences.read("pins").split(',').mapNotNull { name ->
            Tool.entries.find { it.name == name }
        }.distinct()
        val recent = try {
            val array = JSONArray(preferences.read("recent").ifEmpty { "[]" })
            List(array.length()) { index -> array.getJSONObject(index) }.mapNotNull { item ->
                val tool = Tool.entries.find { it.name == item.getString("tool") } ?: return@mapNotNull null
                RecentOutput(
                    item.getString("name"), tool, item.getLong("time"), item.getString("source"),
                    item.getString("path"), item.optString("uri").ifEmpty { null },
                )
            }.take(8)
        } catch (_: JSONException) {
            emptyList()
        }
        mutableHistory.value = ToolsHistory(pins, recent)
        loaded = true
    }

    @Synchronized
    fun setPinned(tool: Tool, pinned: Boolean) {
        load()
        val current = history.value
        val pins = if (pinned) (current.pins + tool).distinct() else current.pins - tool
        preferences.write("pins", pins.joinToString(",") { it.name })
        mutableHistory.value = current.copy(pins = pins)
    }

    @Synchronized
    fun record(outputs: List<RecentOutput>) {
        load()
        var recent = history.value.recent
        for (entry in outputs) {
            val matching = recent.filter {
                it.sourcePath == entry.sourcePath || it.path == entry.path ||
                    (entry.uri != null && it.uri == entry.uri)
            }
            val savedUri = entry.uri ?: matching.firstOrNull { it.uri != null }?.uri
            recent = (listOf(entry.copy(uri = savedUri)) + (recent - matching.toSet())).take(8)
        }
        writeRecent(recent)
    }

    @Synchronized
    fun remove(entry: RecentOutput) {
        load()
        writeRecent(history.value.recent - entry)
    }

    private fun writeRecent(recent: List<RecentOutput>) {
        val json = JSONArray(recent.map {
            JSONObject().put("name", it.name).put("tool", it.tool.name).put("time", it.time)
                .put("source", it.sourcePath).put("path", it.path).put("uri", it.uri.orEmpty())
        })
        preferences.write("recent", json.toString())
        mutableHistory.value = history.value.copy(recent = recent)
    }
}
