package dev.turjo.paperless.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.model.BatchCrop
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageBatchEdits
import dev.turjo.paperless.core.model.PageFilter

@Composable
fun pageFilterLabel(filter: PageFilter): String = stringResource(when (filter) {
    PageFilter.DOCUMENT -> R.string.page_filter_document
    PageFilter.AUTO -> R.string.page_filter_colour
    PageFilter.ORIGINAL -> R.string.page_filter_original
    PageFilter.GRAY -> R.string.page_filter_gray
    PageFilter.BW -> R.string.page_filter_bw
    PageFilter.WHITEBOARD -> R.string.page_filter_whiteboard
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageBatchSheet(
    title: String,
    pages: List<Page>,
    onDismiss: () -> Unit,
    onApply: (PageBatchEdits) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf<PageFilter?>(null) }
    var deskew by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var shadowFix by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var glareFix by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var crop by rememberSaveable { mutableStateOf<BatchCrop?>(null) }
    val edits = PageBatchEdits(filter, deskew, shadowFix, glareFix, crop)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.batch_only_changed), Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.batch_filter), style = MaterialTheme.typography.titleSmall)
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageFilter.entries.forEach { option ->
                    FilterChip(
                        selected = option == filter,
                        onClick = { filter = option.takeUnless { it == filter } },
                        label = { Text(pageFilterLabel(option)) },
                    )
                }
            }
            BatchSwitch(R.string.batch_straighten, deskew, pages.all { it.deskew }) { deskew = it }
            BatchSwitch(R.string.batch_shadow, shadowFix, pages.all { it.shadowFix }) { shadowFix = it }
            BatchSwitch(R.string.batch_glare, glareFix, pages.all { it.glareFix }) { glareFix = it }
            Text(stringResource(R.string.batch_crop), style = MaterialTheme.typography.titleSmall)
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = crop == BatchCrop.AUTO_DETECT,
                    onClick = { crop = if (crop == BatchCrop.AUTO_DETECT) null else BatchCrop.AUTO_DETECT },
                    label = { Text(stringResource(R.string.batch_crop_auto)) },
                )
                FilterChip(
                    selected = crop == BatchCrop.FULL_PHOTO,
                    onClick = { crop = if (crop == BatchCrop.FULL_PHOTO) null else BatchCrop.FULL_PHOTO },
                    label = { Text(stringResource(R.string.batch_crop_full)) },
                )
            }
            Button(
                onClick = { onApply(edits) },
                enabled = edits != PageBatchEdits(),
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.batch_apply)) }
        }
    }
}

@Composable
private fun BatchSwitch(label: Int, edit: Boolean?, initial: Boolean, onChange: (Boolean) -> Unit) {
    val checked = edit ?: initial
    ListItem(
        modifier = Modifier.heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        supportingContent = if (edit == null) {
            { Text(stringResource(R.string.batch_unchanged)) }
        } else null,
        trailingContent = { Switch(checked, onCheckedChange = null) },
    ) { Text(stringResource(label)) }
}
