package dev.turjo.paperless.feature.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import dev.turjo.paperless.core.designsystem.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.pdf.QualityPreset
import dev.turjo.paperless.core.pdf.tools.NumberPosition
import dev.turjo.paperless.core.pdf.tools.NumberStyle
import kotlin.math.abs

@Composable
internal fun PdfPipelineSection(pipeline: PdfPipeline, enabled: Boolean, model: ExportViewModel) {
    var expanded by remember { mutableStateOf(pipeline.steps != PdfPipeline().steps) }
    var expandedStep by remember { mutableStateOf<StepKind?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val steps = pipeline.steps
    val order by rememberUpdatedState(steps.map(PdfStep::kind))
    val heights = remember { mutableMapOf<StepKind, Int>() }
    TextButton(onClick = { expanded = !expanded }, enabled = enabled) {
        Column {
            Text(stringResource(R.string.export_apply))
            if (!expanded) {
                val names = steps.map { stringResource(it.kind.title) }
                Text(names.joinToString(" → ").ifEmpty { stringResource(R.string.export_none) })
            }
        }
    }
    if (!expanded) return

    Column {
        steps.forEachIndexed { index, step ->
            val kind = step.kind
            key(kind) {
                val title = stringResource(kind.title)
                val dragLabel = stringResource(R.string.export_drag_step, title)
                val removeLabel = stringResource(R.string.export_remove_step, title)
                Column(Modifier.onSizeChanged { heights[kind] = it.height }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier.size(48.dp)
                                .semantics { contentDescription = dragLabel }
                                .pointerInput(kind, enabled, model) {
                                    if (!enabled || kind == StepKind.SEARCHABLE_TEXT) return@pointerInput
                                    var rows = mutableListOf<StepKind>()
                                    var offset = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            rows = order.toMutableList()
                                            offset = 0f
                                        },
                                    ) { change, amount ->
                                        change.consume()
                                        offset += amount.y
                                        val from = rows.indexOf(kind)
                                        val direction = if (offset >= 0f) 1 else -1
                                        var by = 0
                                        var to = from + direction
                                        val ownHeight = heights[kind] ?: return@detectDragGesturesAfterLongPress
                                        while (from >= 0 && to in rows.indices && rows[to] != StepKind.SEARCHABLE_TEXT) {
                                            val nextHeight = heights[rows[to]] ?: break
                                            if (abs(offset) < (ownHeight + nextHeight) / 2f) break
                                            offset -= direction * nextHeight
                                            by += direction
                                            to += direction
                                        }
                                        if (by != 0) {
                                            rows.add(from + by, rows.removeAt(from))
                                            model.moveStep(kind, by)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (kind != StepKind.SEARCHABLE_TEXT) Text("≡")
                        }
                        Column(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .clickable(enabled = enabled) { expandedStep = if (expandedStep == kind) null else kind }
                                .padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(title)
                            Text(
                                stepSummary(step),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = {
                                if (expandedStep == kind) expandedStep = null
                                model.removeStep(kind)
                            },
                            enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = removeLabel },
                        ) { Text("×", style = MaterialTheme.typography.titleLarge) }
                    }
                    if (expandedStep == kind) {
                        StepOptions(step, enabled, model::updateStep)
                        if (kind != StepKind.SEARCHABLE_TEXT) Row {
                            TextButton({ model.moveStep(kind, -1) }, enabled = enabled && index > 0 &&
                                steps[index - 1] != PdfStep.SearchableText) {
                                Text(stringResource(R.string.export_move_up))
                            }
                            TextButton({ model.moveStep(kind, 1) }, enabled = enabled && index < steps.lastIndex) {
                                Text(stringResource(R.string.export_move_down))
                            }
                        }
                    }
                }
            }
        }
    }

    val available = StepKind.entries.filter { it !in order }
    Box {
        OutlinedButton(onClick = { menuOpen = true }, enabled = enabled && available.isNotEmpty()) {
            Text(stringResource(R.string.export_add_step))
        }
        DropdownMenu(expanded = menuOpen && enabled && available.isNotEmpty(), onDismissRequest = { menuOpen = false }) {
            available.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(stringResource(kind.title)) },
                    onClick = {
                        menuOpen = false
                        model.addStep(kind)
                    },
                )
            }
        }
    }
    PasswordOptions(pipeline.password, enabled, model::setPassword)
    Text(stringResource(R.string.export_pipeline_order), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun stepSummary(step: PdfStep): String = when (step) {
    PdfStep.SameWidth -> stringResource(R.string.export_same_width_hint)
    PdfStep.Grayscale -> stringResource(R.string.export_grayscale_hint)
    is PdfStep.Compress -> stringResource(step.preset.exportLabel)
    is PdfStep.PageNumbers -> stringResource(
        R.string.export_number_summary, stringResource(step.style.label), stringResource(step.position.label),
    )
    is PdfStep.Layout -> stringResource(step.layout.label)
    PdfStep.CleanMetadata -> stringResource(R.string.export_clean_metadata_hint)
    PdfStep.SearchableText -> stringResource(R.string.export_searchable_text_hint)
}

@Composable
private fun StepOptions(step: PdfStep, enabled: Boolean, update: (PdfStep) -> Unit) {
    when (step) {
        is PdfStep.Compress -> StepChoices(QualityPreset.entries, step.preset, enabled, { it.exportLabel }) {
            update(step.copy(preset = it))
        }
        is PdfStep.PageNumbers -> {
            StepChoices(NumberStyle.entries, step.style, enabled, { it.label }) { update(step.copy(style = it)) }
            StepChoices(numberPositions, step.position, enabled, { it.label }) { update(step.copy(position = it)) }
        }
        is PdfStep.Layout -> {
            StepChoices(PrintLayout.entries, step.layout, enabled, { it.label }) { update(step.copy(layout = it)) }
            val hint = when (step.layout) {
                PrintLayout.TWO -> R.string.export_layout_two_hint
                PrintLayout.FOUR -> R.string.export_layout_four_hint
                PrintLayout.BOOKLET -> R.string.export_layout_booklet_hint
                PrintLayout.CUT_AND_STACK -> R.string.export_layout_cut_hint
            }
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
        }
        else -> Text(stepSummary(step), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> StepChoices(values: List<T>, selected: T, enabled: Boolean, label: (T) -> Int, change: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(value == selected, { change(value) }, label = { Text(stringResource(label(value))) }, enabled = enabled)
        }
    }
}

@Composable
private fun PasswordOptions(password: String?, enabled: Boolean, change: (String?) -> Unit) {
    val title = stringResource(R.string.export_password)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(
            checked = password != null,
            onCheckedChange = { change(if (it) "" else null) },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
    if (password != null) {
        var visible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = password,
            onValueChange = change,
            label = { Text(title) },
            supportingText = { Text(stringResource(R.string.export_password_hint)) },
            trailingIcon = {
                TextButton(onClick = { visible = !visible }, enabled = enabled) {
                    Text(stringResource(if (visible) R.string.export_hide_password else R.string.export_show_password))
                }
            },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val StepKind.title get() = when (this) {
    StepKind.SAME_WIDTH -> R.string.export_same_width
    StepKind.GRAYSCALE -> R.string.export_grayscale
    StepKind.COMPRESS -> R.string.export_compress
    StepKind.PAGE_NUMBERS -> R.string.export_page_numbers
    StepKind.LAYOUT -> R.string.export_print_layout
    StepKind.CLEAN_METADATA -> R.string.export_clean_metadata
    StepKind.SEARCHABLE_TEXT -> R.string.export_searchable_text
}

internal val QualityPreset.exportLabel get() = when (this) {
    QualityPreset.HIGH -> R.string.export_high
    QualityPreset.MEDIUM -> R.string.export_medium
    QualityPreset.SMALL -> R.string.export_small
}

private val NumberStyle.label get() = when (this) {
    NumberStyle.NUMBER -> R.string.export_number_style_number
    NumberStyle.FRACTION -> R.string.export_number_style_fraction
    NumberStyle.PAGE_NUMBER -> R.string.export_number_style_page
}

private val numberPositions = listOf(
    NumberPosition.BOTTOM_LEFT, NumberPosition.BOTTOM_CENTER, NumberPosition.BOTTOM_RIGHT, NumberPosition.TOP_RIGHT,
)

private val NumberPosition.label get() = when (this) {
    NumberPosition.BOTTOM_LEFT -> R.string.export_bottom_left
    NumberPosition.BOTTOM_CENTER -> R.string.export_bottom_center
    NumberPosition.BOTTOM_RIGHT -> R.string.export_bottom_right
    NumberPosition.TOP_RIGHT -> R.string.export_top_right
    NumberPosition.TOP_LEFT -> R.string.export_top_left
    NumberPosition.TOP_CENTER -> R.string.export_top_center
}

private val PrintLayout.label get() = when (this) {
    PrintLayout.TWO -> R.string.export_layout_two
    PrintLayout.FOUR -> R.string.export_layout_four
    PrintLayout.BOOKLET -> R.string.export_layout_booklet
    PrintLayout.CUT_AND_STACK -> R.string.export_layout_cut
}
