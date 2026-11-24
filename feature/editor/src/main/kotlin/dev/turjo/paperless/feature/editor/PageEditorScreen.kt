package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.io.File
import dev.turjo.paperless.core.designsystem.FirstRunTip
import dev.turjo.paperless.core.designsystem.sharedPage
import dev.turjo.paperless.core.imaging.IdCardLayout
import dev.turjo.paperless.core.model.InkRemoval
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.data.SavedSignature
import dev.turjo.paperless.feature.reader.SignatureOverlay
import dev.turjo.paperless.feature.reader.SignaturePicker
import kotlinx.coroutines.delay

private enum class EditorTab(val label: Int) {
    Crop(R.string.editor_crop), Filter(R.string.editor_filter), Rotate(R.string.editor_rotate), Sign(R.string.editor_sign),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(pageId: String, onBack: () -> Unit) {
    val vm = hiltViewModel<PageEditorViewModel, PageEditorViewModel.Factory>(key = pageId) { it.create(pageId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(EditorTab.Crop) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val layout = state.idLayout
    val enabled = (state.original != null || layout != null) && !state.loading && !state.saving && !state.detecting && !state.saved
    val back = {
        if (state.dirty) discard = true else onBack()
    }

    BackHandler(enabled = state.dirty || state.saving) { if (!state.saving) back() }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    EditorMessage(state.message, snackbar) { vm.message(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (layout == null) R.string.editor_title else R.string.editor_id_layout)) },
                navigationIcon = { SymbolButton("←", R.string.editor_back, !state.saving, back) },
                actions = {
                    if (layout == null) {
                        SymbolButton("↶", R.string.editor_undo, enabled && state.canUndo, vm::undo)
                        SymbolButton("↷", R.string.editor_redo, enabled && state.canRedo, vm::redo)
                    }
                    TextButton(vm::done, enabled = enabled && (state.edits != null || layout != null)) {
                        Text(stringResource(R.string.editor_done))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (layout != null) {
                AsyncImage(
                    model = state.page?.originalPath?.let(::File),
                    contentDescription = stringResource(R.string.editor_id_layout),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                    contentScale = ContentScale.Fit,
                )
                if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    IdLayoutPanel(layout, enabled && state.canChangeIdLayout, state.canChangeIdLayout, vm)
                }
                return@Column
            }
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
                    EditorTab.Sign -> SignPanel(edits.applyTo(page), vm, enabled, Modifier.weight(1f).fillMaxWidth())
                    EditorTab.Crop -> CropPanel(page.id, state, vm, enabled, showTip = !discard)
                    EditorTab.Filter, EditorTab.Rotate -> {
                        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                            FilterPreview(
                                edits.applyTo(page), vm, enabled && tab == EditorTab.Filter,
                                Modifier.fillMaxSize(),
                            )
                            FirstRunTip(
                                "filter_zoom",
                                stringResource(R.string.editor_tip_filter),
                                visible = tab == EditorTab.Filter && enabled && !discard,
                            )
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
private fun IdLayoutPanel(
    layout: IdCardLayout,
    enabled: Boolean,
    hasSources: Boolean,
    vm: PageEditorViewModel,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        IdLayoutChoices(
            R.string.editor_id_arrangement,
            listOf(
                IdCardLayout.Arrangement.STACKED to R.string.editor_id_stacked,
                IdCardLayout.Arrangement.SIDE_BY_SIDE to R.string.editor_id_side_by_side,
            ),
            layout.arrangement, enabled,
        ) { choice -> vm.changeIdLayout { it.copy(arrangement = choice) } }
        IdLayoutChoices(
            R.string.editor_id_size,
            listOf(
                IdCardLayout.Size.ACTUAL to R.string.editor_id_actual,
                IdCardLayout.Size.LARGE to R.string.editor_id_large,
            ),
            layout.size, enabled,
        ) { choice -> vm.changeIdLayout { it.copy(size = choice) } }
        IdLayoutChoices(
            R.string.editor_id_gap,
            listOf(0 to R.string.editor_id_none, 6 to R.string.editor_id_small, 15 to R.string.editor_id_medium),
            layout.gapMm, enabled,
        ) { choice -> vm.changeIdLayout { it.copy(gapMm = choice) } }
        AdjustmentSwitch(R.string.editor_id_outline, layout.outline, enabled) { checked ->
            vm.changeIdLayout { it.copy(outline = checked) }
        }
        Text(
            stringResource(if (hasSources) R.string.editor_id_hint else R.string.editor_id_missing),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun <T> IdLayoutChoices(
    label: Int, choices: List<Pair<T, Int>>, selected: T, enabled: Boolean, choose: (T) -> Unit,
) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (value, text) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { choose(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
            ) { Text(stringResource(text)) }
        }
    }
}

@Composable
private fun FilterPreview(
    working: Page,
    vm: PageEditorViewModel,
    comparingEnabled: Boolean,
    modifier: Modifier,
) {
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var highResolution by remember { mutableStateOf(false) }
    val wantsDetail = scale > 1.5f
    LaunchedEffect(wantsDetail) {
        if (wantsDetail && !highResolution) {
            delay(180)
            highResolution = true
        }
    }
    // The preview source tops out at 1600 px. Request half-size first so zoom can reveal real detail.
    val maxSide = if (highResolution) 1600 else 800
    LaunchedEffect(working, maxSide) {
        rendering = true
        delay(150)
        vm.preview(working, maxSide)?.let { rendered = it }
        rendering = false
    }

    val comparisonPage = working.originalComparison()
    var holding by remember { mutableStateOf(false) }
    var comparison by remember(comparisonPage) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(comparingEnabled, comparisonPage, maxSide) {
        if (comparingEnabled) vm.preview(comparisonPage, maxSide)?.let { comparison = it }
    }

    val aspect by rememberUpdatedState(rendered?.let { it.width.toFloat() / it.height } ?: 1f)
    fun transform(viewport: Size, focal: Offset, pan: Offset, nextScale: Float) {
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val current = clampPreviewOffset(offset, scale, viewport, aspect)
        val ratio = nextScale / scale
        val anchor = focal - center
        offset = clampPreviewOffset((current - anchor) * ratio + anchor + pan, nextScale, viewport, aspect)
        scale = nextScale
    }

    Box(
        modifier.clipToBounds()
            .pointerInput(comparingEnabled) {
                detectTapGestures(
                    onPress = {
                        if (comparingEnabled) {
                            holding = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                holding = false
                            }
                        }
                    },
                    onDoubleTap = { tap ->
                        transform(size.toSize(), tap, Offset.Zero, if (scale == 1f) 3f else 1f)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    transform(size.toSize(), centroid, pan, (scale * zoom).coerceIn(1f, 6f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = (if (holding && comparingEnabled) comparison else null) ?: rendered
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(), stringResource(R.string.editor_preview),
                Modifier.sharedPage("page-${working.id}").fillMaxSize().graphicsLayer {
                    val position = clampPreviewOffset(offset, scale, size, aspect)
                    scaleX = scale
                    scaleY = scale
                    translationX = position.x
                    translationY = position.y
                },
                contentScale = ContentScale.Fit,
            )
        } else if (rendering) {
            EditorProgress()
        } else {
            Text(stringResource(R.string.editor_preview_error))
        }
    }
}

private fun clampPreviewOffset(offset: Offset, scale: Float, viewport: Size, aspect: Float): Offset {
    val width = minOf(viewport.width, viewport.height * aspect)
    val height = width / aspect
    val horizontal = ((width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val vertical = ((height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-horizontal, horizontal), offset.y.coerceIn(-vertical, vertical))
}

@Composable
private fun ColumnScope.CropPanel(
    pageId: String,
    state: EditorState,
    vm: PageEditorViewModel,
    enabled: Boolean,
    showTip: Boolean,
) {
    val original = state.original
    val size = state.size
    val ready = original != null && size != null
    BoxWithConstraints(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (original != null && size != null && maxWidth > 0.dp && maxHeight > 0.dp) {
            CropCanvas(
                original, size, state.edits?.quad, enabled,
                Modifier.sharedPage("page-$pageId").fillMaxSize(), vm::beginGesture, vm::endGesture,
                split = state.split, onSplit = vm::moveSplit,
            ) { crop ->
                val wholeImage = fullCrop(size.width.toFloat(), size.height.toFloat())
                vm.edit { it.copy(quad = crop.takeUnless { crop == wholeImage }) }
            }
        }
        if (state.detecting || state.loading) EditorProgress()
        FirstRunTip(
            "crop_handles",
            stringResource(R.string.editor_tip_crop),
            visible = enabled && ready && showTip && state.split == null,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 48.dp),
        )
    }
    if (state.split != null) {
        Text(
            stringResource(R.string.editor_split_hint),
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(vm::cancelSplit, enabled = !state.saving) { Text(stringResource(R.string.editor_split_cancel)) }
            Button(vm::confirmSplit, enabled = !state.saving && !state.saved) {
                Text(stringResource(R.string.editor_split_confirm))
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(vm::autoCrop, enabled = enabled && ready) { Text(stringResource(R.string.editor_auto)) }
            TextButton({ vm.edit { it.copy(quad = null) } }, enabled = enabled && ready) {
                Text(stringResource(R.string.editor_full))
            }
            TextButton(vm::startSplit, enabled = enabled && ready) { Text(stringResource(R.string.editor_split)) }
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

@Composable
private fun SignPanel(page: Page, vm: PageEditorViewModel, enabled: Boolean, modifier: Modifier) {
    val base = page.copy(signatures = emptyList())
    val bitmap by produceState<Bitmap?>(null, base) {
        value = vm.preview(base, 1600)
    }
    var picker by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SavedSignature?>(null) }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val preview = bitmap
            if (preview == null) {
                EditorProgress()
            } else {
                Box(Modifier.aspectRatio(preview.width.toFloat() / preview.height).clipToBounds()) {
                    Image(preview.asImageBitmap(), stringResource(R.string.editor_preview), Modifier.fillMaxSize())
                    SignatureOverlay(
                        placements = page.signatures,
                        pending = pending,
                        onChange = if (enabled) {
                            { index, placement ->
                                vm.edit { edits ->
                                    edits.copy(signatures = edits.signatures.toMutableList().apply {
                                        if (index == null) placement?.let { add(it) }
                                        else if (placement == null) removeAt(index)
                                        else set(index, placement)
                                    })
                                }
                                pending = null
                            }
                        } else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            stringResource(if (pending == null) R.string.editor_sign_edit else R.string.editor_sign_place),
            Modifier.padding(horizontal = 16.dp),
        )
        TextButton({ picker = true }, Modifier.padding(horizontal = 8.dp), enabled = enabled && bitmap != null) {
            Text(stringResource(R.string.editor_sign_choose))
        }
    }
    if (picker) SignaturePicker({ picker = false }) {
        pending = it
        picker = false
    }
}
