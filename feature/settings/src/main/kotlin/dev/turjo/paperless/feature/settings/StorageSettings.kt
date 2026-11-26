package dev.turjo.paperless.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.OriginalShrinkJob
import dev.turjo.paperless.core.data.OriginalShrinkResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface ShrinkStatus {
    data object Ready : ShrinkStatus
    data object Running : ShrinkStatus
    data object Failed : ShrinkStatus
    data class Done(val result: OriginalShrinkResult) : ShrinkStatus
}

@HiltViewModel
internal class StorageViewModel @Inject constructor(private val shrinkJob: OriginalShrinkJob) : ViewModel() {
    private val mutableStatus = MutableStateFlow<ShrinkStatus>(ShrinkStatus.Ready)
    val status = mutableStatus.asStateFlow()

    fun shrinkNow() {
        if (mutableStatus.value == ShrinkStatus.Running) return
        mutableStatus.value = ShrinkStatus.Running
        viewModelScope.launch {
            try {
                mutableStatus.value = ShrinkStatus.Done(shrinkJob.run())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableStatus.value = ShrinkStatus.Failed
            }
        }
    }
}

@Composable
internal fun StorageSettings(days: Int?, onSelect: (Int?) -> Unit, model: StorageViewModel = hiltViewModel()) {
    val status by model.status.collectAsStateWithLifecycle()
    SettingsGroup(R.drawable.ic_group_storage, R.string.storage_group) {
        SettingChoices(R.string.shrink_old_photos, listOf(null, 30, 90, 365), days, onSelect) {
            stringResource(
                when (it) {
                    30 -> R.string.shrink_age_30
                    90 -> R.string.shrink_age_90
                    365 -> R.string.shrink_age_365
                    else -> R.string.shrink_age_off
                },
            )
        }
        Text(
            stringResource(R.string.shrink_explanation),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = model::shrinkNow,
            enabled = days != null && status != ShrinkStatus.Running,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.shrink_now))
        }
        val message = when (val current = status) {
            ShrinkStatus.Ready -> null
            ShrinkStatus.Running -> stringResource(R.string.shrink_running)
            ShrinkStatus.Failed -> stringResource(R.string.shrink_error)
            is ShrinkStatus.Done -> if (current.result.pages == 0) {
                stringResource(R.string.shrink_empty)
            } else {
                val size = Formatter.formatShortFileSize(LocalContext.current, current.result.bytes)
                pluralStringResource(R.plurals.shrink_saved, current.result.pages, size, current.result.pages)
            }
        }
        if (message != null) Text(message, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    }
}
