package dev.turjo.paperless.feature.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import dev.turjo.paperless.core.designsystem.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageTextSheet(pageId: String, model: PageViewerViewModel, dismiss: () -> Unit) {
    val text by remember(pageId) { model.observeText(pageId) }.collectAsStateWithLifecycle(null)
    val automatic by model.automaticText.collectAsStateWithLifecycle(null)
    var request by remember(pageId) { mutableStateOf(0) }
    var running by remember(pageId) { mutableStateOf(false) }
    var failed by remember(pageId) { mutableStateOf(false) }
    val context = LocalContext.current
    val title = stringResource(R.string.viewer_text)

    LaunchedEffect(pageId, automatic, request, text) {
        if (text != null || (automatic != true && request == 0)) return@LaunchedEffect
        running = true
        failed = false
        try {
            failed = !model.recognizeText(pageId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            running = false
        }
    }

    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            val recognized = text
            when {
                recognized != null -> {
                    if (recognized.isBlank()) {
                        Text(stringResource(R.string.viewer_no_text))
                    } else {
                        SelectionContainer {
                            Text(recognized, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                        }
                        Row {
                            TextButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText(title, recognized))
                            }) { Text(stringResource(R.string.viewer_copy_text)) }
                            TextButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, recognized)
                                context.startActivity(Intent.createChooser(send, null))
                            }) { Text(stringResource(R.string.viewer_share)) }
                        }
                    }
                }
                running || automatic == null || (automatic == true && !failed) -> {
                    Text(stringResource(R.string.viewer_recognizing))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                else -> {
                    if (failed) Text(stringResource(R.string.viewer_ocr_failed))
                    TextButton(onClick = { request++ }) { Text(stringResource(R.string.viewer_recognize_now)) }
                }
            }
        }
    }
}
