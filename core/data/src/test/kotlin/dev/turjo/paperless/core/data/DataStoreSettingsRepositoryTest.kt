package dev.turjo.paperless.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.turjo.paperless.core.model.AppLock
import dev.turjo.paperless.core.model.AutoCaptureSensitivity
import dev.turjo.paperless.core.model.AccentColor
import dev.turjo.paperless.core.model.ThemeMode
import dev.turjo.paperless.core.model.UserSettings
import dev.turjo.paperless.core.model.PageFilter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun defaultsMatchUserSettings() = runTest {
        val repository = repository(backgroundScope, settingsFile())

        assertEquals(UserSettings(), repository.settings.first())
    }

    @Test
    fun themeModeCanBeChanged() = runTest {
        val repository = repository(backgroundScope, settingsFile())

        repository.setThemeMode(ThemeMode.DARK)

        assertEquals(UserSettings(ThemeMode.DARK, true), repository.settings.first())
    }

    @Test
    fun dynamicColorCanBeDisabled() = runTest {
        val repository = repository(backgroundScope, settingsFile())

        repository.setDynamicColor(false)

        assertEquals(UserSettings(ThemeMode.SYSTEM, false), repository.settings.first())
    }

    @Test
    fun settingsPersistAcrossDataStoreInstances() = runTest {
        val file = settingsFile()
        val writerJob = Job(backgroundScope.coroutineContext[Job])
        val writerScope = CoroutineScope(backgroundScope.coroutineContext + writerJob)
        // One write per instance: on Windows the JVM cannot rename DataStore's temp file over a file
        // another write still holds open (Android/Linux can), which only affects host-side tests.
        val writer = repository(writerScope, file)
        writer.setThemeMode(ThemeMode.LIGHT)
        writerJob.cancelAndJoin()

        val reader = repository(backgroundScope, file)
        assertEquals(UserSettings(ThemeMode.LIGHT, true), reader.settings.first())
    }


    @Test
    fun preferencesPersistAcrossDataStoreInstances() = runTest {
        val changes: List<Pair<UserSettings, suspend (SettingsRepository) -> Unit>> = listOf(
            UserSettings(defaultScanFilter = PageFilter.GRAY) to { it.setDefaultScanFilter(PageFilter.GRAY) },
            UserSettings(defaultScanDeskew = false) to { it.setDefaultScanDeskew(false) },
            UserSettings(amoledBlack = true) to { it.setAmoledBlack(true) },
            UserSettings(glass = true) to { it.setGlass(true) },
            UserSettings(ocrOnlyWhileCharging = true) to { it.setOcrOnlyWhileCharging(true) },
            UserSettings(accent = AccentColor.ROSE) to { it.setAccent(AccentColor.ROSE) },
            UserSettings(autoNamePattern = "{n} {date}") to { it.setAutoNamePattern("{n} {date}") },
            UserSettings(appLock = AppLock.AFTER_MINUTES) to { it.setAppLock(AppLock.AFTER_MINUTES) },
            UserSettings(lockAfterMinutes = 12) to { it.setLockAfterMinutes(12) },
            UserSettings(autoCaptureDelayMs = 1200) to { it.setAutoCaptureDelayMs(1200) },
            UserSettings(autoCaptureSensitivity = AutoCaptureSensitivity.HIGH) to { it.setAutoCaptureSensitivity(AutoCaptureSensitivity.HIGH) },
        )
        changes.forEachIndexed { index, (expected, write) ->
            val file = File(temp.root, "settings-$index.preferences_pb")
            val writerJob = Job(backgroundScope.coroutineContext[Job])
            val writer = repository(CoroutineScope(backgroundScope.coroutineContext + writerJob), file)
            write(writer)
            writerJob.cancelAndJoin()

            assertEquals(expected, repository(backgroundScope, file).settings.first())
        }
    }

    @Test
    fun unknownAccentUsesDefault() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { settingsFile() }
        dataStore.edit { it[stringPreferencesKey("accent")] = "UNKNOWN" }

        assertEquals(AccentColor.BLUE, DataStoreSettingsRepository(dataStore).settings.first().accent)
    }

    private fun settingsFile() = File(temp.root, "settings.preferences_pb")

    private fun repository(scope: CoroutineScope, file: File) =
        DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
}
