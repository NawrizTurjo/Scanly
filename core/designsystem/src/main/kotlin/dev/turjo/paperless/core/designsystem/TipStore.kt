package dev.turjo.paperless.core.designsystem

import android.content.Context

internal interface TipPreferences {
    fun seen(key: String): Boolean
    fun markSeen(key: String)
    fun clear()
}

class TipStore internal constructor(private val preferences: TipPreferences) {
    constructor(context: Context) : this(object : TipPreferences {
        private val prefs = context.applicationContext.getSharedPreferences("tips", Context.MODE_PRIVATE)

        override fun seen(key: String): Boolean = prefs.getBoolean(key, false)
        override fun markSeen(key: String) = prefs.edit().putBoolean(key, true).apply()
        override fun clear() = prefs.edit().clear().apply()
    })

    fun seen(key: String): Boolean = preferences.seen(key)
    fun markSeen(key: String) = preferences.markSeen(key)
    fun resetAll() = preferences.clear()
}
