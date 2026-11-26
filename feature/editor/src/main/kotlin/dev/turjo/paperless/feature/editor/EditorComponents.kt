package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import dev.turjo.paperless.core.designsystem.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageFilter

internal fun PageFilter.label() = when (this) {
    PageFilter.ORIGINAL -> R.string.filter_original
    PageFilter.AUTO -> R.string.editor_auto
    PageFilter.DOCUMENT -> R.string.filter_document
    PageFilter.BW -> R.string.filter_bw
    PageFilter.GRAY -> R.string.filter_gray
    PageFilter.WHITEBOARD -> R.string.filter_whiteboard
}

@Composable
internal fun PresetCarousel(
    page: Page,
    selected: PageFilter?,
    enabled: Boolean,
    preview: suspend (Page, PageFilter) -> Bitmap?,
    onSelect: (PageFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PageFilter.entries, key = { it.name }) { filter ->
            val bitmap by produceState<Bitmap?>(null, page, filter) { value = preview(page, filter) }
            FilterChip(
                selected = selected == filter,
                enabled = enabled,
                onClick = { onSelect(filter) },
                label = {
                    Column(
                        Modifier.width(96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(84.dp, 56.dp), contentAlignment = Alignment.Center) {
                            bitmap?.let {
                                Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit)
                            }
                        }
                        Text(stringResource(filter.label()), Modifier.height(24.dp))
                    }
                },
            )
        }
    }
}

@Composable
internal fun SymbolButton(symbol: String, description: Int, enabled: Boolean = true, click: () -> Unit) {
    val label = stringResource(description)
    IconButton(click, Modifier.semantics { contentDescription = label }, enabled = enabled) {
        Text(symbol, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun ConfirmDialog(title: Int, body: Int, confirm: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
        confirmButton = { TextButton(onConfirm) { Text(stringResource(confirm)) } },
    )
}

@Composable
internal fun EditorMessage(message: Int?, host: SnackbarHostState, clear: () -> Unit) {
    val resources = LocalResources.current
    val text = message?.let(resources::getString)
    val dismiss = stringResource(R.string.editor_dismiss)
    LaunchedEffect(text, dismiss) {
        if (text != null) {
            host.showSnackbar(text, actionLabel = dismiss)
            clear()
        }
    }
}

@Composable
internal fun EditorProgress() {
    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
}
