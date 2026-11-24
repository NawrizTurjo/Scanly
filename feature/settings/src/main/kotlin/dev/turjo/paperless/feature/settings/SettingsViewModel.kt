package dev.turjo.paperless.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.SettingsRepository
import dev.turjo.paperless.core.model.AppLock
import dev.turjo.paperless.core.model.AutoCaptureSensitivity
import dev.turjo.paperless.core.model.AccentColor
import dev.turjo.paperless.core.model.ThemeMode
import dev.turjo.paperless.core.model.OcrLanguage
import dev.turjo.paperless.core.model.UserSettings
import dev.turjo.paperless.core.model.PageFilter
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(val settings: UserSettings = UserSettings())

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = repository.settings
        .map(::SettingsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setDefaultScanFilter(filter: PageFilter) {
        viewModelScope.launch { repository.setDefaultScanFilter(filter) }
    }

    fun setDefaultScanDeskew(enabled: Boolean) {
        viewModelScope.launch { repository.setDefaultScanDeskew(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setAutomaticOcr(enabled: Boolean) {
        viewModelScope.launch { repository.setAutomaticOcr(enabled) }
    }

    fun setOcrLanguage(language: OcrLanguage) {
        viewModelScope.launch { repository.setOcrLanguage(language) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColor(enabled) }
    }

    fun setAmoledBlack(enabled: Boolean) {
        viewModelScope.launch { repository.setAmoledBlack(enabled) }
    }

    fun setAccent(accent: AccentColor) {
        viewModelScope.launch { repository.setAccent(accent) }
    }

    fun setAutoNamePattern(pattern: String) {
        viewModelScope.launch { repository.setAutoNamePattern(pattern) }
    }

    fun setAppLock(mode: AppLock) {
        viewModelScope.launch { repository.setAppLock(mode) }
    }

    fun setLockAfterMinutes(minutes: Int) {
        viewModelScope.launch { repository.setLockAfterMinutes(minutes) }
    }

    fun setAutoCaptureDelayMs(delayMs: Int) {
        viewModelScope.launch { repository.setAutoCaptureDelayMs(delayMs) }
    }

    fun setAutoCaptureSensitivity(sensitivity: AutoCaptureSensitivity) {
        viewModelScope.launch { repository.setAutoCaptureSensitivity(sensitivity) }
    }
}
