package dev.turjo.paperless.feature.settings

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
    enabled: Boolean,
    onSelect: (AccentColor) -> Unit,
) {
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
            })
            val colors = colorSchemeFor(dark, accent, amoled = false)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .selectable(chosen, enabled = enabled, role = Role.RadioButton) { onSelect(accent) }
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
                    }
                }
            }
        }
    }
}
