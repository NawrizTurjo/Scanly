package dev.turjo.paperless.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.turjo.paperless.core.model.AUTO_CAPTURE_DELAYS_MS
import dev.turjo.paperless.core.model.AppLock
import dev.turjo.paperless.core.model.AutoCaptureSensitivity
import dev.turjo.paperless.core.model.AccentColor
import dev.turjo.paperless.core.model.ThemeMode
import dev.turjo.paperless.core.model.UserSettings
import dev.turjo.paperless.core.model.OcrLanguage
import dev.turjo.paperless.core.model.NEW_SCAN_FILTERS
import dev.turjo.paperless.core.model.PageFilter
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setAmoledBlack(enabled: Boolean)
    suspend fun setGlass(enabled: Boolean)
    suspend fun setShrinkOriginalsAfterDays(days: Int?)
    suspend fun setOcrOnlyWhileCharging(enabled: Boolean)
    suspend fun setAccent(accent: AccentColor)
    suspend fun setCustomAccent(hue: Float, chroma: Float)
    suspend fun setAutoNamePattern(pattern: String)
    suspend fun setAppLock(mode: AppLock)
    suspend fun setLockAfterMinutes(minutes: Int)
    suspend fun setAutoCaptureDelayMs(delayMs: Int)
    suspend fun setAutoCaptureSensitivity(sensitivity: AutoCaptureSensitivity)
    suspend fun setAutomaticOcr(enabled: Boolean)
    suspend fun setOcrLanguage(language: OcrLanguage)
    suspend fun setDefaultScanFilter(filter: PageFilter)
    suspend fun setDefaultScanDeskew(enabled: Boolean)
}

@javax.inject.Singleton
class DataStoreSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val shrinkWorkContext: Context? = null,
) : SettingsRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>,
    ) : this(dataStore, context)

    private val shrinkSettingLock = Mutex()

    override val settings: Flow<UserSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            UserSettings(
                defaultScanFilter = NEW_SCAN_FILTERS.find { it.name == preferences[DEFAULT_SCAN_FILTER] }
                    ?: defaults.defaultScanFilter,
                defaultScanDeskew = preferences[DEFAULT_SCAN_DESKEW] ?: defaults.defaultScanDeskew,
                automaticOcr = preferences[AUTOMATIC_OCR] ?: defaults.automaticOcr,
                shrinkOriginalsAfterDays = preferences[SHRINK_ORIGINALS_AFTER_DAYS]?.takeIf { it in shrinkAges },
                ocrOnlyWhileCharging = preferences[OCR_ONLY_CHARGING] ?: defaults.ocrOnlyWhileCharging,
                ocrLanguage = OcrLanguage.entries.find { it.name == preferences[OCR_LANGUAGE] } ?: defaults.ocrLanguage,
                themeMode = ThemeMode.entries.find { it.name == preferences[THEME_MODE] }
                    ?: ThemeMode.SYSTEM,
                dynamicColor = preferences[DYNAMIC_COLOR] ?: true,
                amoledBlack = preferences[AMOLED_BLACK] ?: defaults.amoledBlack,
                glass = preferences[GLASS] ?: defaults.glass,
                accent = AccentColor.entries.find { it.name == preferences[ACCENT] } ?: defaults.accent,
                customAccentHue = preferences[CUSTOM_ACCENT_HUE] ?: defaults.customAccentHue,
                customAccentChroma = preferences[CUSTOM_ACCENT_CHROMA] ?: defaults.customAccentChroma,
                autoNamePattern = preferences[AUTO_NAME_PATTERN] ?: defaults.autoNamePattern,
                appLock = AppLock.entries.find { it.name == preferences[APP_LOCK] } ?: defaults.appLock,
                lockAfterMinutes = preferences[LOCK_AFTER_MINUTES]?.takeIf { it > 0 } ?: defaults.lockAfterMinutes,
                autoCaptureDelayMs = preferences[AUTO_CAPTURE_DELAY_MS]?.takeIf { it in AUTO_CAPTURE_DELAYS_MS }
                    ?: defaults.autoCaptureDelayMs,
                autoCaptureSensitivity = AutoCaptureSensitivity.entries.find {
                    it.name == preferences[AUTO_CAPTURE_SENSITIVITY]
                } ?: defaults.autoCaptureSensitivity,
            )
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setAmoledBlack(enabled: Boolean) {
        dataStore.edit { it[AMOLED_BLACK] = enabled }
    }

    override suspend fun setShrinkOriginalsAfterDays(days: Int?) {
        require(days == null || days in shrinkAges)
        shrinkSettingLock.withLock {
            withContext(NonCancellable) {
                dataStore.edit {
                    if (days == null) it.remove(SHRINK_ORIGINALS_AFTER_DAYS)
                    else it[SHRINK_ORIGINALS_AFTER_DAYS] = days
                }
                shrinkWorkContext?.let { scheduleOriginalShrink(it, enabled = days != null) }
            }
        }
    }

    override suspend fun setOcrOnlyWhileCharging(enabled: Boolean) {
        dataStore.edit { it[OCR_ONLY_CHARGING] = enabled }
    }

    override suspend fun setGlass(enabled: Boolean) {
        dataStore.edit { it[GLASS] = enabled }
    }

    override suspend fun setCustomAccent(hue: Float, chroma: Float) {
        dataStore.edit {
            it[CUSTOM_ACCENT_HUE] = hue
            it[CUSTOM_ACCENT_CHROMA] = chroma
            it[ACCENT] = AccentColor.CUSTOM.name
        }
    }

    override suspend fun setAccent(accent: AccentColor) {
        dataStore.edit { it[ACCENT] = accent.name }
    }

    override suspend fun setAutoNamePattern(pattern: String) {
        dataStore.edit { it[AUTO_NAME_PATTERN] = pattern }
    }

    override suspend fun setAppLock(mode: AppLock) {
        dataStore.edit { it[APP_LOCK] = mode.name }
    }

    override suspend fun setLockAfterMinutes(minutes: Int) {
        dataStore.edit { it[LOCK_AFTER_MINUTES] = minutes.coerceAtLeast(1) }
    }

    override suspend fun setAutoCaptureDelayMs(delayMs: Int) {
        dataStore.edit { it[AUTO_CAPTURE_DELAY_MS] = delayMs.also { require(it in AUTO_CAPTURE_DELAYS_MS) } }
    }

    override suspend fun setAutoCaptureSensitivity(sensitivity: AutoCaptureSensitivity) {
        dataStore.edit { it[AUTO_CAPTURE_SENSITIVITY] = sensitivity.name }
    }

    override suspend fun setAutomaticOcr(enabled: Boolean) {
        dataStore.edit { it[AUTOMATIC_OCR] = enabled }
    }

    override suspend fun setOcrLanguage(language: OcrLanguage) {
        dataStore.edit { it[OCR_LANGUAGE] = language.name }
    }

    override suspend fun setDefaultScanFilter(filter: PageFilter) {
        require(filter in NEW_SCAN_FILTERS)
        dataStore.edit { it[DEFAULT_SCAN_FILTER] = filter.name }
    }

    override suspend fun setDefaultScanDeskew(enabled: Boolean) {
        dataStore.edit { it[DEFAULT_SCAN_DESKEW] = enabled }
    }

    private companion object {
        val DEFAULT_SCAN_FILTER = stringPreferencesKey("default_scan_filter")
        val DEFAULT_SCAN_DESKEW = booleanPreferencesKey("default_scan_deskew")
        val AUTOMATIC_OCR = booleanPreferencesKey("automatic_ocr")
        val OCR_LANGUAGE = stringPreferencesKey("ocr_language")
        val defaults = UserSettings()
        val AUTO_NAME_PATTERN = stringPreferencesKey("auto_name_pattern")
        val APP_LOCK = stringPreferencesKey("app_lock")
        val LOCK_AFTER_MINUTES = intPreferencesKey("lock_after_minutes")
        val AUTO_CAPTURE_DELAY_MS = intPreferencesKey("auto_capture_delay_ms")
        val AUTO_CAPTURE_SENSITIVITY = stringPreferencesKey("auto_capture_sensitivity")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
        val GLASS = booleanPreferencesKey("glass")
        val shrinkAges = setOf(30, 90, 365)
        val SHRINK_ORIGINALS_AFTER_DAYS = intPreferencesKey("shrink_originals_after_days")
        val OCR_ONLY_CHARGING = booleanPreferencesKey("ocr_only_charging")
        val ACCENT = stringPreferencesKey("accent")
        val CUSTOM_ACCENT_HUE = floatPreferencesKey("custom_accent_hue")
        val CUSTOM_ACCENT_CHROMA = floatPreferencesKey("custom_accent_chroma")
    }
}
