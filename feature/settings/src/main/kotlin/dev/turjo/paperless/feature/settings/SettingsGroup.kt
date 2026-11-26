package dev.turjo.paperless.feature.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.designsystem.ProvideClearSurface
import dev.turjo.paperless.core.designsystem.glassContainer
import dev.turjo.paperless.core.designsystem.glassEdge

/** One Settings section as a card with an icon and a title; a glass card when glass is on. */
@Composable
internal fun SettingsGroup(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).glassEdge(shape),
        shape = shape,
        color = glassContainer(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            ProvideClearSurface { Column(content = content) }
        }
    }
}
