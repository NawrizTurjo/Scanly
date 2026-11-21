package dev.turjo.paperless.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.turjo.paperless.core.model.ThemeMode
import dev.turjo.paperless.core.model.UserSettings
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
    fun defaultsAreSystemThemeAndDynamicColorEnabled() = runTest {
        val repository = repository(backgroundScope, settingsFile())

        assertEquals(UserSettings(ThemeMode.SYSTEM, true), repository.settings.first())
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

    private fun settingsFile() = File(temp.root, "settings.preferences_pb")

    private fun repository(scope: CoroutineScope, file: File) =
        DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
}
