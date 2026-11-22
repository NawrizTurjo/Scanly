package dev.turjo.paperless.feature.export

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.pdf.PageSizeOption
import dev.turjo.paperless.core.pdf.tools.NumberPosition
import dev.turjo.paperless.core.pdf.tools.NumberStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PdfExtrasSection(options: ExportOptions, enabled: Boolean, select: (PdfExtras) -> Unit) {
    val extras = options.extras
    val active = mutableListOf<String>()
    if (options.sameWidth && options.size == PageSizeOption.AUTO) active += stringResource(R.string.export_order_width)
    if (extras.grayscale) active += stringResource(R.string.export_order_gray)
    if (extras.pageNumbers != null) active += stringResource(R.string.export_order_numbers)
    if (extras.layout != null) active += stringResource(R.string.export_order_layout)
    if (extras.password != null) active += stringResource(R.string.export_order_password)
    val order = active.joinToString(stringResource(R.string.export_order_separator))
    var expanded by remember { mutableStateOf(extras != PdfExtras()) }
    TextButton(onClick = { expanded = !expanded }, enabled = enabled) {
        Column {
            Text(stringResource(R.string.export_apply))
            if (!expanded) Text(order.ifEmpty { stringResource(R.string.export_none) })
        }
    }
    if (!expanded) return

    ExtraSwitch(R.string.export_grayscale, extras.grayscale, enabled) {
        select(extras.copy(grayscale = it))
    }
    val numbers = extras.pageNumbers
    ExtraSwitch(R.string.export_page_numbers, numbers != null, enabled) {
        select(extras.copy(pageNumbers = if (it) PageNumberExtra() else null))
    }
    if (numbers != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberStyle.entries.forEach { style ->
                val label = when (style) {
                    NumberStyle.NUMBER -> R.string.export_number_style_number
                    NumberStyle.FRACTION -> R.string.export_number_style_fraction
                    NumberStyle.PAGE_NUMBER -> R.string.export_number_style_page
                }
                FilterChip(numbers.style == style, { select(extras.copy(pageNumbers = numbers.copy(style = style))) },
                    label = { Text(stringResource(label)) }, enabled = enabled)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val positions = listOf(
                NumberPosition.BOTTOM_LEFT to R.string.export_bottom_left,
                NumberPosition.BOTTOM_CENTER to R.string.export_bottom_center,
                NumberPosition.BOTTOM_RIGHT to R.string.export_bottom_right,
                NumberPosition.TOP_RIGHT to R.string.export_top_right,
            )
            positions.forEach { (position, label) ->
                FilterChip(numbers.position == position,
                    { select(extras.copy(pageNumbers = numbers.copy(position = position))) },
                    label = { Text(stringResource(label)) }, enabled = enabled)
            }
        }
    }
    Text(stringResource(R.string.export_print_layout))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf(null) + LayoutExtra.entries).forEach { layout ->
            val label = when (layout) {
                null -> R.string.export_layout_off
                LayoutExtra.TWO -> R.string.export_layout_two
                LayoutExtra.FOUR -> R.string.export_layout_four
                LayoutExtra.BOOKLET -> R.string.export_layout_booklet
                LayoutExtra.CUT_AND_STACK -> R.string.export_layout_cut
            }
            FilterChip(extras.layout == layout, { select(extras.copy(layout = layout)) },
                label = { Text(stringResource(label)) }, enabled = enabled)
        }
    }
    val hint = when (extras.layout) {
        null -> R.string.export_layout_off_hint
        LayoutExtra.TWO -> R.string.export_layout_two_hint
        LayoutExtra.FOUR -> R.string.export_layout_four_hint
        LayoutExtra.BOOKLET -> R.string.export_layout_booklet_hint
        LayoutExtra.CUT_AND_STACK -> R.string.export_layout_cut_hint
    }
    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
    val password = extras.password
    ExtraSwitch(R.string.export_password, password != null, enabled) {
        select(extras.copy(password = if (it) "" else null))
    }
    if (password != null) {
        var visible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = password,
            onValueChange = { select(extras.copy(password = it)) },
            label = { Text(stringResource(R.string.export_password)) },
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
    if (order.isNotEmpty()) {
        Text(stringResource(R.string.export_apply_order, order), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExtraSwitch(label: Int, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    val title = stringResource(label)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = change,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
