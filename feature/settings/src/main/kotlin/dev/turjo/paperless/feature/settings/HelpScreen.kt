package dev.turjo.paperless.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.turjo.paperless.core.designsystem.TipStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val toolDescriptions = listOf(
    R.string.help_tool_merge,
    R.string.help_tool_split,
    R.string.help_tool_page_edits,
    R.string.help_tool_compress,
    R.string.help_tool_same_width,
    R.string.help_tool_print_layout,
    R.string.help_tool_page_numbers,
    R.string.help_tool_password,
    R.string.help_tool_metadata,
    R.string.help_tool_sanitize,
    R.string.help_tool_grayscale,
    R.string.help_tool_images_to_pdf,
    R.string.help_tool_pdf_to_images,
    R.string.help_tool_batch,
)

@Composable
internal fun HelpScreen(onBack: () -> Unit) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            val window = (view.parent as DialogWindowProvider).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
        Surface(Modifier.fillMaxSize()) {
            HelpContent(onBack)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resetMessage = stringResource(R.string.help_tips_reset)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.help_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_help_back),
                        contentDescription = stringResource(R.string.help_back),
                    )
                }
            },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { HelpSection(R.string.help_scanning, R.string.help_scanning_body) }
            item { HelpSection(R.string.help_crop, R.string.help_crop_body) }
            item { HelpSection(R.string.help_saving, R.string.help_saving_body) }
            item { HelpSection(R.string.help_unsaved, R.string.help_unsaved_body) }
            item { HelpSection(R.string.help_images, R.string.help_images_body) }
            item { HelpSection(R.string.help_reader, R.string.help_reader_body) }
            item {
                HelpSection(R.string.help_tools, R.string.help_tools_body) {
                    toolDescriptions.forEach { Text(stringResource(it)) }
                }
            }
            item { HelpSection(R.string.help_library, R.string.help_library_body) }
            item { HelpSection(R.string.help_privacy, R.string.help_privacy_body) }
            item { HelpSection(R.string.updates, R.string.help_updates_body) }
            item {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { TipStore(context).resetAll() }
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(resetMessage)
                    }
                }) {
                    Text(stringResource(R.string.help_show_tips))
                }
            }
        }
        SnackbarHost(snackbar)
    }
}

@Composable
private fun HelpSection(title: Int, body: Int, details: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(body))
        details()
    }
}
