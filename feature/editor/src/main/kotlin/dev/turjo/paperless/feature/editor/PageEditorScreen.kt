package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turjo.paperless.core.model.InkRemoval
import kotlinx.coroutines.delay

private enum class EditorTab(val label: Int) {
    Crop(R.string.editor_crop), Filter(R.string.editor_filter), Rotate(R.string.editor_rotate),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(pageId: String, onBack: () -> Unit) {
    val vm = hiltViewModel<PageEditorViewModel, PageEditorViewModel.Factory>(key = pageId) { it.create(pageId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(EditorTab.Crop) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val enabled = state.original != null && !state.loading && !state.saving && !state.detecting && !state.saved
    val back = {
        if (state.dirty) discard = true else onBack()
    }

    BackHandler(enabled = state.dirty || state.saving) { if (!state.saving) back() }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    EditorMessage(state.message, snackbar) { vm.message(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_title)) },
                navigationIcon = { SymbolButton("←", R.string.editor_back, !state.saving, back) },
                actions = {
                    SymbolButton("↶", R.string.editor_undo, enabled && state.canUndo, vm::undo)
                    SymbolButton("↷", R.string.editor_redo, enabled && state.canRedo, vm::redo)
                    TextButton(vm::done, enabled = enabled && state.edits != null) {
                        Text(stringResource(R.string.editor_done))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                EditorTab.entries.forEach { choice ->
                    Tab(
                        selected = tab == choice, enabled = enabled,
                        onClick = { vm.endGesture(); tab = choice },
                        text = { Text(stringResource(choice.label)) },
                    )
                }
            }
            val page = state.page
            val edits = state.edits
            if (state.loading || page == null || edits == null || state.original == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.loading) EditorProgress()
                    else Text(stringResource(R.string.editor_load_error))
                }
            } else {
                when (tab) {
                    EditorTab.Crop -> CropPanel(state, vm, enabled)
                    EditorTab.Filter, EditorTab.Rotate -> {
                        var rendered by remember { mutableStateOf<Bitmap?>(null) }
                        var rendering by remember { mutableStateOf(true) }
                        val working = edits.applyTo(page)
                        LaunchedEffect(working) {
                            rendering = true
                            delay(150)
                            vm.preview(working, 1200)?.let { rendered = it }
                            rendering = false
                        }
                        val comparisonPage = working.originalComparison()
                        val comparingEnabled = enabled && tab == EditorTab.Filter
                        var holding by remember { mutableStateOf(false) }
                        var comparison by remember { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(comparingEnabled, comparisonPage) {
                            comparison = null
                            if (comparingEnabled) comparison = vm.preview(comparisonPage, 1200)
                        }

                        Box(
                            Modifier.weight(1f).fillMaxWidth().padding(12.dp)
                                .pointerInput(comparingEnabled) {
                                    if (comparingEnabled) detectTapGestures(onPress = {
                                        holding = true
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            holding = false
                                        }
                                    })
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val bitmap = (if (holding) comparison else null) ?: rendered
                            if (bitmap != null) {
                                Image(
                                    bitmap.asImageBitmap(), stringResource(R.string.editor_preview),
                                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                                )
                            } else if (rendering) {
                                EditorProgress()
                            } else {
                                Text(stringResource(R.string.editor_preview_error))
                            }
                        }

                        if (tab == EditorTab.Filter) {
                            Text(
                                stringResource(R.string.editor_compare),
                                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                                FilterPanel(state, vm, enabled)
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                TextButton({ vm.edit { it.copy(rotation = (it.rotation + 270) % 360) } }, enabled = enabled) {
                                    Text(stringResource(R.string.editor_rotate_left))
                                }
                                TextButton({ vm.edit { it.copy(rotation = (it.rotation + 90) % 360) } }, enabled = enabled) {
                                    Text(stringResource(R.string.editor_rotate_right))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (discard) {
        ConfirmDialog(
            R.string.editor_discard_title, R.string.editor_discard_body, R.string.editor_discard,
            onDismiss = { discard = false },
        ) {
            discard = false
            onBack()
        }
    }
}

@Composable
private fun ColumnScope.CropPanel(state: EditorState, vm: PageEditorViewModel, enabled: Boolean) {
    val original = state.original
    val size = state.size
    val ready = original != null && size != null
    BoxWithConstraints(
        Modifier.weight(1f).fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (original != null && size != null && maxWidth > 0.dp && maxHeight > 0.dp) {
            CropCanvas(
                original, size, state.edits?.quad, enabled, Modifier.fillMaxSize(), vm::beginGesture, vm::endGesture,
            ) { crop ->
                val wholeImage = fullCrop(size.width.toFloat(), size.height.toFloat())
                vm.edit { it.copy(quad = crop.takeUnless { crop == wholeImage }) }
            }
        }
        if (state.detecting || state.loading) EditorProgress()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(vm::autoCrop, enabled = enabled && ready) { Text(stringResource(R.string.editor_auto)) }
        TextButton({ vm.edit { it.copy(quad = null) } }, enabled = enabled && ready) {
            Text(stringResource(R.string.editor_full))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FilterPanel(state: EditorState, vm: PageEditorViewModel, enabled: Boolean) {
    val page = requireNotNull(state.page)
    val edits = requireNotNull(state.edits)
    val presetPage = edits.applyTo(page).copy(brightness = 0f, contrast = 0f, shadowFix = false, glareFix = false)
    val skewPage = page.originalComparison().copy(quad = edits.quad, deskew = edits.deskew, inkRemoval = edits.inkRemoval)
    val angle by produceState<Double?>(null, skewPage) {
        value = null
        if (skewPage.deskew) value = vm.skewAngle(skewPage)
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        PresetCarousel(
            page = presetPage, selected = edits.filter, enabled = enabled,
            preview = { preset, filter -> vm.preview(preset.copy(filter = filter), 200) },
        ) { filter -> vm.edit { it.copy(filter = filter) } }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (page.glareWarning) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.editor_glare_warning), Modifier.padding(8.dp))
                }
            }
            AdjustmentSlider(
                R.string.editor_brightness, edits.brightness, enabled, vm::endGesture,
            ) { value ->
                vm.beginGesture()
                vm.edit { it.copy(brightness = value) }
            }
            AdjustmentSlider(
                R.string.editor_contrast, edits.contrast, enabled, vm::endGesture,
            ) { value ->
                vm.beginGesture()
                vm.edit { it.copy(contrast = value) }
            }
            AdjustmentSwitch(R.string.editor_shadow_fix, edits.shadowFix, enabled) { checked ->
                vm.edit { it.copy(shadowFix = checked) }
            }
            AdjustmentSwitch(R.string.editor_deskew, edits.deskew, enabled) { checked ->
                vm.edit { it.copy(deskew = checked) }
            }
            angle?.let { Text(stringResource(R.string.editor_straightened, kotlin.math.abs(it))) }
            Text(stringResource(R.string.editor_ink_removal))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    null to R.string.editor_ink_none,
                    InkRemoval.BLUE to R.string.editor_ink_blue,
                    InkRemoval.RED to R.string.editor_ink_red,
                    InkRemoval.GREEN to R.string.editor_ink_green,
                ).forEach { (ink, label) ->
                    FilterChip(
                        selected = edits.inkRemoval == ink,
                        onClick = { vm.edit { it.copy(inkRemoval = ink) } },
                        label = { Text(stringResource(label)) },
                        enabled = enabled,
                    )
                }
            }
            Text(stringResource(R.string.editor_ink_caption), style = MaterialTheme.typography.bodySmall)
            AdjustmentSwitch(R.string.editor_glare_fix, edits.glareFix, enabled) { checked ->
                vm.edit { it.copy(glareFix = checked) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustmentSlider(label: Int, value: Float, enabled: Boolean, finish: () -> Unit, change: (Float) -> Unit) {
    Text(stringResource(label))
    val slider = remember { SliderState(value, 0, -1f..1f) }
    SideEffect { slider.value = value }
    Slider(slider, change, enabled = enabled, onValueChangeFinished = finish)
}

@Composable
private fun AdjustmentSwitch(label: Int, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label))
        Switch(checked, change, enabled = enabled)
    }
}
