package dev.turjo.paperless.feature.settings

import dev.turjo.paperless.core.designsystem.tone
import dev.turjo.paperless.core.designsystem.seedColorScheme
import dev.turjo.paperless.core.designsystem.SeedColor
import dev.turjo.paperless.core.designsystem.AlertDialog
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.designsystem.colorSchemeFor
import dev.turjo.paperless.core.model.AccentColor

@Composable
internal fun AccentChoices(
    selected: AccentColor,
    custom: SeedColor,
    enabled: Boolean,
    onSelect: (AccentColor) -> Unit,
    onCustom: (hue: Float, chroma: Float) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        CustomAccentDialog(custom, onDismiss = { editing = false }) {
            editing = false
            onCustom(it.hue, it.chroma)
        }
    }
    ListItem(
        supportingContent = if (enabled) null else {
            { Text(stringResource(R.string.accent_dynamic_hint)) }
        },
    ) { Text(stringResource(R.string.accent)) }

    FlowRow(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        for (accent in AccentColor.entries) {
            val chosen = accent == selected
            val label = stringResource(when (accent) {
                AccentColor.BLUE -> R.string.accent_blue
                AccentColor.TEAL -> R.string.accent_teal
                AccentColor.GREEN -> R.string.accent_green
                AccentColor.ORANGE -> R.string.accent_orange
                AccentColor.ROSE -> R.string.accent_rose
                AccentColor.CUSTOM -> R.string.accent_custom
            })
            val colors = colorSchemeFor(dark, accent, amoled = false, custom = custom)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .selectable(chosen, enabled = enabled, role = Role.RadioButton) {
                        // Custom always opens the picker, so a chosen custom colour can be changed.
                        if (accent == AccentColor.CUSTOM) editing = true else onSelect(accent)
                    }
                    .semantics { contentDescription = label }
                    .alpha(if (enabled) 1f else 0.38f),
                contentAlignment = Alignment.Center,
            ) {
                val outline = if (chosen) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(4.dp)
                } else Modifier.padding(4.dp)
                Box(
                    modifier = Modifier.size(40.dp).then(outline).background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) {
                        Icon(
                            painterResource(R.drawable.ic_accent_check),
                            contentDescription = null,
                            tint = colors.onPrimary,
                        )
                    } else if (accent == AccentColor.CUSTOM) {
                        Text("+", color = colors.onPrimary, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

/** Pick a custom accent: hue and vividness set the whole scheme; a hex colour fills both in. */
@Composable
private fun CustomAccentDialog(initial: SeedColor, onDismiss: () -> Unit, onSave: (SeedColor) -> Unit) {
    var hue by remember { mutableFloatStateOf(initial.hue) }
    var chroma by remember { mutableFloatStateOf(initial.chroma.coerceIn(MIN_CHROMA, MAX_CHROMA)) }
    var hex by remember { mutableStateOf(hexOf(SeedColor(hue, chroma).toColor())) }
    val seed = SeedColor(hue, chroma)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val preview = remember(seed, dark) { seedColorScheme(seed, dark) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_accent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (color in listOf(preview.primary, preview.primaryContainer, preview.secondary, preview.tertiary)) {
                        Box(Modifier.size(40.dp).background(color, CircleShape))
                    }
                }
                Text(stringResource(R.string.custom_accent_hue), style = MaterialTheme.typography.labelLarge)
                val rainbow = remember(chroma) {
                    Brush.horizontalGradient((0..12).map { SeedColor(it * 30f, chroma).tone(55) })
                }
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp).clip(CircleShape).background(rainbow))
                    Slider(
                        value = hue,
                        onValueChange = {
                            hue = it
                            hex = hexOf(SeedColor(hue, chroma).toColor())
                        },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            thumbColor = preview.primary,
                        ),
                    )
                }
                Text(stringResource(R.string.custom_accent_vividness), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = chroma,
                    onValueChange = {
                        chroma = it
                        hex = hexOf(SeedColor(hue, chroma).toColor())
                    },
                    valueRange = MIN_CHROMA..MAX_CHROMA,
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text
                        parseHex(text)?.let { color ->
                            val picked = SeedColor.of(color)
                            hue = picked.hue
                            chroma = picked.chroma.coerceIn(MIN_CHROMA, MAX_CHROMA)
                        }
                    },
                    label = { Text(stringResource(R.string.custom_accent_hex)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(seed) }) { Text(stringResource(R.string.custom_accent_use)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private const val MIN_CHROMA = 16f
private const val MAX_CHROMA = 70f

private fun hexOf(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

/** "#3366CC" or "3366cc" to a colour; null while the text isn't a full hex colour yet. */
internal fun parseHex(text: String): Color? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6 || digits.any { it !in "0123456789abcdefABCDEF" }) return null
    return Color(0xFF000000 or digits.toLong(16))
}
