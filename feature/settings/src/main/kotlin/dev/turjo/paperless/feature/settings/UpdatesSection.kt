package dev.turjo.paperless.feature.settings

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun activityUpdatesModel(): UpdatesViewModel {
    val activity = LocalActivity.current as ViewModelStoreOwner
    return remember(activity) { ViewModelProvider(activity)[UpdatesViewModel::class.java] }
}

@Composable
internal fun UpdatesCard(model: UpdatesViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val release = state.release ?: return
    if (!state.showCard) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.update_available, release.version))
            Row {
                TextButton(onClick = model::showRelease, enabled = !state.checking) {
                    Text(stringResource(R.string.update_details))
                }
                TextButton(onClick = model::dismissCard) { Text(stringResource(R.string.update_dismiss)) }
            }
        }
    }
}

@Composable
internal fun UpdatesSection(model: UpdatesViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.onPermissionReturn(context::startActivity)
    }
    val status = when {
        state.checking -> stringResource(R.string.update_checking)
        state.error != null && !state.showDialog -> stringResource(checkNotNull(state.error))
        state.release != null -> stringResource(R.string.update_available, checkNotNull(state.release).version)
        state.skippedVersion != null -> stringResource(R.string.update_skipped, checkNotNull(state.skippedVersion))
        state.installedVersion != null -> stringResource(R.string.update_current, checkNotNull(state.installedVersion))
        else -> null
    }
    ListItem(
        modifier = Modifier.clickable(enabled = !state.checking) { model.checkForUpdate() },
        supportingContent = status?.let { { Text(it) } },
    ) { Text(stringResource(R.string.update_check)) }
    val automatic = state.automatic
    ListItem(
        modifier = Modifier.toggleable(
            value = automatic ?: true, enabled = automatic != null,
            role = Role.Switch, onValueChange = model::setAutomatic,
        ),
        trailingContent = { Switch(automatic ?: true, null, enabled = automatic != null) },
    ) { Text(stringResource(R.string.update_auto)) }

    val release = state.release ?: return
    if (!state.showDialog) return
    val progress = state.progress
    LaunchedEffect(progress, state.awaitingPermission) {
        if (progress == null && !state.awaitingPermission && model.hasPendingInstall()) {
            model.install(context::startActivity)
        }
    }
    AlertDialog(
        onDismissRequest = {
            if (progress != null) model.cancelDownload()
            else model.dismissDialog()
        },
        title = { Text(stringResource(R.string.update_available, release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val fullSize = Formatter.formatFileSize(context, release.size)
                Text(
                    release.patch?.let {
                        stringResource(R.string.update_size_patch, Formatter.formatFileSize(context, it.size), fullSize)
                    } ?: fullSize,
                )
                if (release.notes.isNotBlank()) {
                    ReleaseNotes(release.notes, Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
                }
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (state.awaitingPermission) Text(stringResource(R.string.update_permission_explanation))
                if (progress != null) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            when {
                progress != null -> TextButton(onClick = { model.cancelDownload() }) {
                    Text(stringResource(R.string.update_cancel))
                }
                state.awaitingPermission -> TextButton(onClick = {
                    try {
                        permission.launch(model.permissionIntent())
                    } catch (_: ActivityNotFoundException) {
                        model.installPermissionUnavailable()
                    } catch (_: SecurityException) {
                        model.installPermissionUnavailable()
                    }
                }) { Text(stringResource(R.string.update_permission_open)) }
                else -> TextButton(onClick = model::startDownload) {
                    Text(stringResource(R.string.update_download))
                }
            }
        },
        dismissButton = {
            if (progress == null) {
                Column {
                    TextButton(onClick = model::dismissDialog) { Text(stringResource(R.string.update_later)) }
                    TextButton(onClick = model::skipRelease) { Text(stringResource(R.string.update_skip)) }
                }
            }
        },
    )
}

@Composable
private fun ReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { releaseNotesMarkdown(markdown) }
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            val top = when {
                index == 0 -> 0.dp
                block is NotesBlock.Heading -> 12.dp
                block.gapBefore -> 8.dp
                block is NotesBlock.Item -> 4.dp
                else -> 0.dp
            }
            when (block) {
                is NotesBlock.Heading -> Text(
                    block.text,
                    Modifier.padding(top = top),
                    style = if (block.level <= 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                is NotesBlock.Item -> Row(Modifier.padding(top = top)) {
                    Text(block.marker, Modifier.width(18.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(block.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                is NotesBlock.Paragraph -> Text(
                    block.text, Modifier.padding(top = top), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
