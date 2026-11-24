package dev.turjo.paperless.feature.settings

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import dev.turjo.paperless.core.data.SettingsRepository
import dev.turjo.paperless.core.model.AppLock
import dev.turjo.paperless.core.model.AutoCaptureSensitivity
import dev.turjo.paperless.core.model.AccentColor
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


    @Test
    fun savesAndExposesScanningAndSecurityPreferences() = runTest(dispatcher) {
        viewModel.uiState.test {
            var expected = awaitItem().settings

            viewModel.setAutoNamePattern("{n} {date}")
            expected = expected.copy(autoNamePattern = "{n} {date}")
            assertEquals(SettingsUiState(expected), awaitItem())
            assertEquals(expected, repository.settings.value)

            viewModel.setAppLock(AppLock.AFTER_MINUTES)
            expected = expected.copy(appLock = AppLock.AFTER_MINUTES)
            assertEquals(SettingsUiState(expected), awaitItem())
            assertEquals(expected, repository.settings.value)

            viewModel.setLockAfterMinutes(12)
            expected = expected.copy(lockAfterMinutes = 12)
            assertEquals(SettingsUiState(expected), awaitItem())
            assertEquals(expected, repository.settings.value)

            viewModel.setAutoCaptureDelayMs(1200)
            expected = expected.copy(autoCaptureDelayMs = 1200)
            assertEquals(SettingsUiState(expected), awaitItem())
            assertEquals(expected, repository.settings.value)

            viewModel.setAutoCaptureSensitivity(AutoCaptureSensitivity.HIGH)
            expected = expected.copy(autoCaptureSensitivity = AutoCaptureSensitivity.HIGH)
            assertEquals(SettingsUiState(expected), awaitItem())
            assertEquals(expected, repository.settings.value)
        }
    }

    @Test
    fun savesAndExposesAppearancePreferences() = runTest(dispatcher) {
        viewModel.uiState.test {
            val initial = awaitItem().settings

            viewModel.setAmoledBlack(true)
            assertEquals(initial.copy(amoledBlack = true), awaitItem().settings)

            viewModel.setAccent(AccentColor.ROSE)
            val expected = initial.copy(amoledBlack = true, accent = AccentColor.ROSE)
            assertEquals(expected, awaitItem().settings)
            assertEquals(expected, repository.settings.value)
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        override suspend fun setAutomaticOcr(enabled: Boolean) = error("Unused")
        override suspend fun setOcrLanguage(language: dev.turjo.paperless.core.model.OcrLanguage) = error("Unused")
        override val settings = MutableStateFlow(UserSettings())

        override suspend fun setThemeMode(mode: ThemeMode) {
            settings.update { it.copy(themeMode = mode) }
        }

        override suspend fun setDynamicColor(enabled: Boolean) {
            settings.update { it.copy(dynamicColor = enabled) }
        }

        override suspend fun setAmoledBlack(enabled: Boolean) {
            settings.update { it.copy(amoledBlack = enabled) }
        }

        override suspend fun setAccent(accent: AccentColor) {
            settings.update { it.copy(accent = accent) }
        }

        override suspend fun setAutoNamePattern(pattern: String) {
            settings.update { it.copy(autoNamePattern = pattern) }
        }

        override suspend fun setAppLock(mode: AppLock) {
            settings.update { it.copy(appLock = mode) }
        }

        override suspend fun setLockAfterMinutes(minutes: Int) {
            settings.update { it.copy(lockAfterMinutes = minutes) }
        }

        override suspend fun setAutoCaptureDelayMs(delayMs: Int) {
            settings.update { it.copy(autoCaptureDelayMs = delayMs) }
        }

        override suspend fun setAutoCaptureSensitivity(sensitivity: AutoCaptureSensitivity) {
            settings.update { it.copy(autoCaptureSensitivity = sensitivity) }
        }
    }
}
