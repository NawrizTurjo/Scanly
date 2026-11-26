package dev.turjo.paperless.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.turjo.paperless.core.designsystem.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.BackupJobState
import dev.turjo.paperless.core.data.ContentSearchRepository
import dev.turjo.paperless.core.data.FolderRepository
import dev.turjo.paperless.core.data.IndexCounts
import dev.turjo.paperless.core.data.LibraryJobs
import javax.inject.Inject

@HiltViewModel
internal class BackupSettingsViewModel @Inject constructor(
    search: ContentSearchRepository,
    folders: FolderRepository,
) : ViewModel() {
    val counts = search.observeCounts()
    val folderList = folders.observeFolders()
}

private val backedUp = Regex("""Backed up (\d+) documents? to ([\s\S]*)""")
private val restored = Regex("""Restored (\d+) documents? · (\d+) already present""")

@Composable
private fun backupResultText(message: String): String {
    // LibraryJobs exposes a preformatted result, including for summaries persisted by older builds.
    backedUp.matchEntire(message)?.destructured?.let { (number, location) ->
        val count = number.toIntOrNull() ?: return message
        return pluralStringResource(R.plurals.backup_documents_backed_up, count, count, location)
    }
    restored.matchEntire(message)?.destructured?.let { (number, skippedNumber) ->
        val count = number.toIntOrNull() ?: return message
        val skipped = skippedNumber.toIntOrNull() ?: return message
        return pluralStringResource(R.plurals.backup_documents_restored, count, count, skipped)
    }
    return message
}

@Composable
internal fun BackupSettings() {
    val context = LocalContext.current
    val model: BackupSettingsViewModel = hiltViewModel()
    val prefs = remember(context) { context.getSharedPreferences("backup-settings", 0) }
    var destination by remember { mutableStateOf(prefs.getString("tree", null)?.let(Uri::parse)) }
    var error by remember { mutableStateOf<String?>(null) }
    var chooseFolders by rememberSaveable { mutableStateOf(false) }
    val backupFlow = remember(context) { LibraryJobs.observeBackup(context) }
    val backup by backupFlow.collectAsState(BackupJobState(false, null))
    val counts by model.counts.collectAsState(IndexCounts(0, 0))
    val folders by model.folderList.collectAsState(emptyList())
    val accessError = stringResource(R.string.backup_access_error)

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val chooseDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                prefs.edit { putString("tree", uri.toString()) }
                destination = uri
                error = null
                requestNotifications()
            } catch (_: SecurityException) {
                error = accessError
            }
        }
    }
    val chooseBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                error = null
                requestNotifications()
                LibraryJobs.backup(context, uri, folderIds = null, restore = true)
            } catch (_: SecurityException) {
                error = accessError
            }
        }
    }

    SettingsGroup(R.drawable.ic_group_backup, R.string.backup_section) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            destination?.lastPathSegment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !backup.active, onClick = { chooseDestination.launch(destination) }) {
                    Text(stringResource(R.string.backup_choose_folder))
                }
                TextButton(enabled = !backup.active && destination != null, onClick = { chooseFolders = true }) {
                    Text(stringResource(R.string.backup_now))
                }
                TextButton(enabled = !backup.active, onClick = { chooseBackup.launch(null) }) {
                    Text(stringResource(R.string.backup_restore))
                }
            }
            if (backup.active) {
                LinearProgressIndicator({ (backup.progress ?: 0) / 100f }, Modifier.fillMaxWidth())
                TextButton(onClick = { LibraryJobs.cancelBackup(context) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            backup.lastResult?.let { Text(backupResultText(it), style = MaterialTheme.typography.bodyMedium) }
        }
    }

    SettingsGroup(R.drawable.ic_group_search, R.string.search_index_section) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(pluralStringResource(R.plurals.search_index_pdfs, counts.pdfs, counts.pdfs))
            Text(pluralStringResource(R.plurals.search_index_pages, counts.pages, counts.pages))
            TextButton(onClick = { LibraryJobs.index(context, rebuild = true) }) {
                Text(stringResource(R.string.search_index_rebuild))
            }
        }
    }

    val tree = destination
    if (chooseFolders && tree != null) {
        var selectedIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
        AlertDialog(
            onDismissRequest = { chooseFolders = false },
            title = { Text(stringResource(R.string.backup_choose_contents)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    folders.forEach { summary ->
                        val folder = summary.folder
                        val parentSelected = folder.parentId in selectedIds
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = folder.id in selectedIds || parentSelected,
                                enabled = !parentSelected,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + folder.id else selectedIds - folder.id
                                },
                            )
                            Text(folder.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    chooseFolders = false
                    error = null
                    requestNotifications()
                    LibraryJobs.backup(context, tree, selectedIds.toSet().takeIf { it.isNotEmpty() }, restore = false)
                }) {
                    Text(stringResource(if (selectedIds.isEmpty()) R.string.backup_all else R.string.backup_selected))
                }
            },
            dismissButton = {
                TextButton(onClick = { chooseFolders = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

