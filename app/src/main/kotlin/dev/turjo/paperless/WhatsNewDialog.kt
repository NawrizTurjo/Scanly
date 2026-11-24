package dev.turjo.paperless

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.turjo.paperless.feature.settings.R as SettingsR
import kotlinx.coroutines.launch

@Composable
internal fun WhatsNewDialog(version: String, release: WhatsNewRelease?, onDismiss: () -> Unit) {
    val cards = release?.cards.orEmpty()
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()
    val lastPage = cards.isEmpty() || pager.currentPage == cards.lastIndex
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                Modifier.heightIn(max = 520.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    Text(
                        stringResource(SettingsR.string.whats_new_title),
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        stringResource(R.string.whats_new_version, version),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (cards.isEmpty()) {
                    Text(stringResource(R.string.whats_new_unavailable), Modifier.weight(1f, fill = false))
                } else {
                    HorizontalPager(pager, Modifier.weight(1f), verticalAlignment = Alignment.Top) { page ->
                        val card = cards[page]
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(card.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Text(
                                stringResource(card.title),
                                Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(stringResource(card.body), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    val progress = stringResource(R.string.whats_new_progress, pager.currentPage + 1, cards.size)
                    Row(
                        Modifier.align(Alignment.CenterHorizontally).semantics { contentDescription = progress },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        repeat(cards.size) { index ->
                            val color = if (index == pager.currentPage) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                            Box(Modifier.size(8.dp).background(color, CircleShape))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (!lastPage) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.whats_new_skip)) }
                    }
                    Button(onClick = {
                        if (lastPage) onDismiss()
                        else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }, enabled = !pager.isScrollInProgress) {
                        Text(stringResource(if (lastPage) R.string.whats_new_done else R.string.whats_new_next))
                    }
                }
            }
        }
    }
}
