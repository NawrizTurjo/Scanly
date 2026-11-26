package dev.turjo.paperless.feature.settings

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import dev.turjo.paperless.core.designsystem.ScanlyScaffold
import android.app.KeyguardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turjo.paperless.core.model.AUTO_CAPTURE_DELAYS_MS
import dev.turjo.paperless.core.model.AppLock
import dev.turjo.paperless.core.model.AutoCaptureSensitivity
import dev.turjo.paperless.core.model.ThemeMode
import dev.turjo.paperless.core.model.OcrLanguage
import dev.turjo.paperless.core.model.formatAutoName
import dev.turjo.paperless.core.designsystem.pageFilterLabel
import dev.turjo.paperless.core.model.NEW_SCAN_FILTERS
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onWhatsNew: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) HelpScreen(onBack = { showHelp = false })

    var showLicences by rememberSaveable { mutableStateOf(false) }
    if (showLicences) OpenSourceLicences { showLicences = false }

    val updates = activityUpdatesModel()
    ScanlyScaffold(
        modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // Scrolls under the (glass) bars: the padding sits inside the scrolling column.
        Column(Modifier.consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(padding)) {
            val settings = state.settings
            var editedPattern by rememberSaveable { mutableStateOf<String?>(null) }
            val pattern = editedPattern ?: settings.autoNamePattern
            val previewTime = remember { LocalDateTime.now() }

            UpdatesCard(updates)
            SettingsGroup(R.drawable.ic_group_updates, R.string.updates) {
                UpdatesSection(updates)
            }
            SettingsGroup(R.drawable.ic_group_scanning, R.string.scanning) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        editedPattern = it
                        viewModel.setAutoNamePattern(it)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    label = { Text(stringResource(R.string.auto_name_pattern)) },
                    supportingText = {
                        Column {
                            Text(stringResource(R.string.auto_name_tokens))
                            Text(stringResource(R.string.auto_name_preview, formatAutoName(pattern, previewTime, 1)))
                        }
                    },
                    singleLine = true,
                )
                SettingChoices(
                    R.string.auto_capture_delay,
                    AUTO_CAPTURE_DELAYS_MS,
                    settings.autoCaptureDelayMs,
                    viewModel::setAutoCaptureDelayMs,
                ) { stringResource(R.string.delay_ms, it) }
                SettingChoices(
                    R.string.auto_capture_sensitivity,
                    AutoCaptureSensitivity.entries,
                    settings.autoCaptureSensitivity,
                    viewModel::setAutoCaptureSensitivity,
                ) {
                    stringResource(when (it) {
                        AutoCaptureSensitivity.LOW -> R.string.sensitivity_low
                        AutoCaptureSensitivity.NORMAL -> R.string.sensitivity_normal
                        AutoCaptureSensitivity.HIGH -> R.string.sensitivity_high
                    })
                }

                SettingLabel(R.string.new_scans)
                SettingChoices(
                    R.string.default_scan_filter,
                    NEW_SCAN_FILTERS,
                    settings.defaultScanFilter,
                    viewModel::setDefaultScanFilter,
                ) { pageFilterLabel(it) }
                ListItem(
                    trailingContent = { Switch(checked = settings.defaultScanDeskew, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        value = settings.defaultScanDeskew,
                        role = Role.Switch,
                        onValueChange = viewModel::setDefaultScanDeskew,
                    ),
                ) { Text(stringResource(R.string.default_scan_deskew)) }

            }
            SettingsGroup(R.drawable.ic_group_text, R.string.text_recognition) {
                ListItem(
                    trailingContent = { Switch(checked = settings.automaticOcr, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        value = settings.automaticOcr,
                        role = Role.Switch,
                        onValueChange = viewModel::setAutomaticOcr,
                    ),
                ) { Text(stringResource(R.string.automatic_ocr)) }
                SettingChoices(R.string.ocr_language, OcrLanguage.entries, settings.ocrLanguage, viewModel::setOcrLanguage) {
                    stringResource(when (it) {
                        OcrLanguage.ENGLISH -> R.string.ocr_english
                        OcrLanguage.BENGALI -> R.string.ocr_bengali
                        OcrLanguage.BOTH -> R.string.ocr_both
                    })
                }
            }
            SettingsGroup(R.drawable.ic_group_security, R.string.security) {
                val keyguard = LocalContext.current.getSystemService(KeyguardManager::class.java)
                var showCredentialMessage by rememberSaveable { mutableStateOf(false) }
                SettingChoices(R.string.app_lock, AppLock.entries, settings.appLock, { mode ->
                    showCredentialMessage = mode != AppLock.OFF && !keyguard.isDeviceSecure
                    if (!showCredentialMessage) viewModel.setAppLock(mode)
                }) {
                    stringResource(when (it) {
                        AppLock.OFF -> R.string.lock_off
                        AppLock.ON_OPEN -> R.string.lock_on_open
                        AppLock.AFTER_MINUTES -> R.string.lock_after_minutes
                    })
                }
                if (showCredentialMessage) {
                    Text(
                        stringResource(R.string.lock_needs_credential),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (settings.appLock == AppLock.AFTER_MINUTES) {
                    var editedMinutes by rememberSaveable { mutableStateOf<String?>(null) }
                    val minutes = editedMinutes ?: settings.lockAfterMinutes.toString()
                    val validMinutes = minutes.toIntOrNull()?.takeIf { it > 0 }
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { value ->
                            editedMinutes = value
                            value.toIntOrNull()?.takeIf { it > 0 }?.let(viewModel::setLockAfterMinutes)
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        label = { Text(stringResource(R.string.lock_minutes)) },
                        isError = validMinutes == null,
                        supportingText = {
                            if (validMinutes == null) {
                                Text(pluralStringResource(
                                    R.plurals.lock_minutes_saved,
                                    settings.lockAfterMinutes,
                                    settings.lockAfterMinutes,
                                ))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

            }
            BackupSettings()
            SettingsGroup(R.drawable.ic_group_appearance, R.string.appearance) {
                SettingChoices(R.string.theme, ThemeMode.entries, settings.themeMode, viewModel::setThemeMode) {
                    stringResource(when (it) {
                        ThemeMode.SYSTEM -> R.string.theme_system
                        ThemeMode.LIGHT -> R.string.theme_light
                        ThemeMode.DARK -> R.string.theme_dark
                    })
                }
                val amoledEnabled = settings.themeMode != ThemeMode.LIGHT
                ListItem(
                    supportingContent = { Text(stringResource(R.string.amoled_black_hint)) },
                    trailingContent = {
                        Switch(checked = settings.amoledBlack, onCheckedChange = null, enabled = amoledEnabled)
                    },
                    modifier = Modifier.toggleable(
                        value = settings.amoledBlack,
                        enabled = amoledEnabled,
                        role = Role.Switch,
                        onValueChange = viewModel::setAmoledBlack,
                    ),
                ) { Text(stringResource(R.string.amoled_black)) }
                ListItem(
                    supportingContent = { Text(stringResource(R.string.glass_effect_hint)) },
                    trailingContent = { Switch(checked = settings.glass, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        value = settings.glass,
                        role = Role.Switch,
                        onValueChange = viewModel::setGlass,
                    ),
                ) { Text(stringResource(R.string.glass_effect)) }
                ListItem(
                    trailingContent = {
                        Switch(checked = settings.dynamicColor, onCheckedChange = null)
                    },
                    modifier = Modifier.toggleable(
                        value = settings.dynamicColor,
                        role = Role.Switch,
                        onValueChange = viewModel::setDynamicColor,
                    ),
                ) { Text(stringResource(R.string.dynamic_color)) }
                AccentChoices(settings.accent, enabled = !settings.dynamicColor, onSelect = viewModel::setAccent)
            }
            SettingsGroup(R.drawable.ic_group_about, R.string.about) {
                ListItem(
                    supportingContent = {
                        val context = LocalContext.current
                        val version = remember(context) {
                            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                                .getOrNull().orEmpty()
                        }
                        Text(stringResource(R.string.about_summary, version))
                    },
                ) { Text(stringResource(R.string.about)) }
                ListItem(modifier = Modifier.clickable(onClick = onWhatsNew)) {
                    Text(stringResource(R.string.whats_new_title))
                }
                ListItem(modifier = Modifier.clickable { showLicences = true }) {
                    Text(stringResource(R.string.open_source_licences))
                }
                ListItem(
                    modifier = Modifier.clickable { showHelp = true },
                    supportingContent = { Text(stringResource(R.string.help_summary)) },
                ) { Text(stringResource(R.string.help_title)) }
            }
        }
    }
}

@Composable
private fun SettingLabel(label: Int) {
    Text(
        stringResource(label),
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun <T> SettingChoices(
    title: Int,
    choices: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
) {
    SettingLabel(title)
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice == selected,
                onClick = { onSelect(choice) },
                label = { Text(label(choice)) },
            )
        }
    }
}
