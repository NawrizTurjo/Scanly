package dev.turjo.paperless.feature.export

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import dev.turjo.paperless.core.designsystem.ModalBottomSheet
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turjo.paperless.core.pdf.PageSizeOption
import dev.turjo.paperless.core.pdf.QualityPreset
import dev.turjo.paperless.core.pdf.ShareIntents

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportSheet(documentId: String, onDismiss: () -> Unit) {
    val model = hiltViewModel<ExportViewModel, ExportViewModel.Factory>(key = documentId) {
        it.create(documentId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val options by model.options.collectAsStateWithLifecycle()
    val estimate by model.estimate.collectAsStateWithLifecycle()
    DisposableEffect(model) {
        onDispose { model.clearPassword() }
    }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val error = stringResource(R.string.export_error)
    var editedName by rememberSaveable(documentId) { mutableStateOf<String?>(null) }
    val name = editedName ?: state.document?.document?.title?.let(::sanitizeExportName).orEmpty()
    val enabled = !state.running && state.document != null
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    LaunchedEffect(state.failed) {
        if (state.failed) {
            snackbar.showSnackbar(error)
            model.clearError()
        }
    }
    LaunchedEffect(state.loaded, state.document) {
        if (state.loaded && state.document == null) {
            model.dismiss()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            model.dismiss()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.export_format))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { format ->
                    val label = when (format) {
                        ExportFormat.PDF -> R.string.export_pdf
                        ExportFormat.JPG -> R.string.export_jpg
                        ExportFormat.BOTH -> R.string.export_both
                        ExportFormat.PNG -> R.string.export_png
                    }
                    FilterChip(options.format == format, { model.select(options.copy(format = format)) },
                        label = { Text(stringResource(label)) }, enabled = enabled)
                }
            }
            if (options.format.pdf) {
                Text(stringResource(R.string.export_page_size))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageSizeOption.entries.forEach { size ->
                        val label = when (size) {
                            PageSizeOption.AUTO -> R.string.export_auto
                            PageSizeOption.A4 -> R.string.export_a4
                            PageSizeOption.LETTER -> R.string.export_letter
                        }
                        FilterChip(options.size == size, { model.select(options.copy(size = size)) },
                            label = { Text(stringResource(label)) }, enabled = enabled)
                    }
                }
            }
            Text(stringResource(R.string.export_quality))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityPreset.entries.forEach { quality ->
                    FilterChip(options.quality == quality, { model.select(options.copy(quality = quality)) },
                        label = { Text(stringResource(quality.exportLabel)) }, enabled = enabled)
                }
            }
            estimate?.let {
                val bytes = if (options.format == ExportFormat.BOTH) it * 2 else it
                Text(stringResource(R.string.export_estimate, Formatter.formatFileSize(context, bytes)))
            }
            OutlinedTextField(
                value = name,
                onValueChange = { editedName = it },
                label = { Text(stringResource(R.string.export_filename)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            if (options.format.pdf) {
                PdfPipelineSection(options.pipeline, enabled, model)
            }
            if (!state.loaded) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (state.running) {
                val ocr = state.ocrProgress
                if (ocr == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.export_ocr_progress, ocr.first + 1, ocr.second))
                    LinearProgressIndicator(progress = { ocr.first.toFloat() / ocr.second }, Modifier.fillMaxWidth())
                }
                TextButton(onClick = model::cancel) { Text(stringResource(R.string.export_cancel)) }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = enabled && options.canExport, onClick = {
                        model.export(name) { files, mime ->
                            context.startActivity(ShareIntents.forFiles(context, files, mime))
                        }
                    }) { Text(stringResource(R.string.export_share)) }
                    OutlinedButton(enabled = enabled && options.canExport, onClick = { model.export(name) }) {
                        Text(stringResource(R.string.export_save))
                    }
                }
            }
            state.saved?.let { saved ->
                val message = stringResource(when (saved.format) {
                    ExportFormat.PDF -> R.string.export_saved_pdf
                    ExportFormat.BOTH -> R.string.export_saved_both
                    else -> R.string.export_saved_images
                })
                Snackbar(action = {
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(saved.uri, saved.mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (_: android.content.ActivityNotFoundException) {
                            model.openFailed()
                        }
                    }) { Text(stringResource(R.string.export_open)) }
                }) { Text(message) }
            }
            SnackbarHost(snackbar)
            Spacer(Modifier.height(12.dp))
        }
    }
}
