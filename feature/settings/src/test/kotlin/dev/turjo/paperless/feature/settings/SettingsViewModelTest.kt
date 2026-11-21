package dev.turjo.paperless.feature.settings

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import dev.turjo.paperless.core.data.SettingsRepository
import dev.turjo.paperless.core.model.ThemeMode
import dev.turjo.paperless.core.model.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = FakeSettingsRepository()
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SettingsViewModel(repository)
        store.put("settings", viewModel)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun observesStoredPreferences() = runTest(dispatcher) {
        repository.setThemeMode(ThemeMode.LIGHT)
        repository.setDynamicColor(false)

        viewModel.uiState.test {
            assertEquals(SettingsUiState(), awaitItem())
            assertEquals(SettingsUiState(UserSettings(ThemeMode.LIGHT, false)), awaitItem())
        }
    }

    @Test
    fun savesThemeModeAndExposesIt() = runTest(dispatcher) {
        viewModel.uiState.test {
            assertEquals(SettingsUiState(), awaitItem())

            viewModel.setThemeMode(ThemeMode.DARK)

            assertEquals(SettingsUiState(UserSettings(ThemeMode.DARK, true)), awaitItem())
            assertEquals(ThemeMode.DARK, repository.settings.value.themeMode)
        }
    }

    @Test
    fun savesDynamicColorAndExposesIt() = runTest(dispatcher) {
        viewModel.uiState.test {
            assertEquals(SettingsUiState(), awaitItem())

            viewModel.setDynamicColor(false)

            assertEquals(SettingsUiState(UserSettings(ThemeMode.SYSTEM, false)), awaitItem())
            assertEquals(false, repository.settings.value.dynamicColor)
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val settings = MutableStateFlow(UserSettings())

        override suspend fun setThemeMode(mode: ThemeMode) {
            settings.update { it.copy(themeMode = mode) }
        }

        override suspend fun setDynamicColor(enabled: Boolean) {
            settings.update { it.copy(dynamicColor = enabled) }
        }
    }
}
