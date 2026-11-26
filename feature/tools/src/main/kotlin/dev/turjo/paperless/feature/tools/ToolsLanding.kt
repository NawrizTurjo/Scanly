package dev.turjo.paperless.feature.tools

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import android.content.ActivityNotFoundException
import android.text.format.DateUtils
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import dev.turjo.paperless.core.designsystem.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.launch

@Composable
internal fun ToolsLanding(model: ToolsModel, openPdf: (File) -> Unit) {
    val history by model.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openRecent = rememberOpenRecentOutput(model, openPdf, snackbar)
    val results = if (searching) ToolCardInfo.entries.filter {
        it.matches(query, stringResource(it.title), stringResource(it.description))
    } else emptyList()
    val pinCards = history.pins.map { ToolCardInfo.valueOf(it.name) }
    val pin: (Tool, Boolean) -> Unit = model::setPinned

    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text(stringResource(R.string.tools_search)) },
            singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.tools_search), contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }) {
                        Icon(painterResource(R.drawable.tools_clear), stringResource(R.string.tools_search_clear))
                    }
                }
            } else null,
        )
        // Touching the tools (scroll, tap, long-press) drops focus from the search field, so the keyboard and the
        // system autofill chip don't linger over the page.
        Box(Modifier.weight(1f).pointerInput(focus) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                focus.clearFocus()
            }
        }) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (searching) {
                    if (results.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(R.string.tools_no_matches),
                                Modifier.padding(vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(results, key = { "search-${it.name}" }, span = { GridItemSpan(maxLineSpan) }) { info ->
                        ToolCard(info, info.tool in history.pins, model::select, pin, compact = true)
                    }
                } else {
                    if (pinCards.isNotEmpty()) {
                        item(key = "pinned", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(R.string.tools_pinned) }
                        items(pinCards, key = { "pin-${it.name}" }) { info ->
                            ToolCard(info, true, model::select, pin)
                        }
                    }
                    if (history.recent.isNotEmpty()) {
                        item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SectionTitle(R.string.tools_recent)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(history.recent, key = { it.sourcePath }) { entry ->
                                        RecentCard(entry, { openRecent(entry) }) {
                                            scope.launch { model.removeRecent(entry) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ToolGroup.entries.forEach { group ->
                        item(key = "group-${group.name}", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(group.title) }
                        items(ToolCardInfo.entries.filter { it.group == group }, key = { it.name }) { info ->
                            ToolCard(info, info.tool in history.pins, model::select, pin)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        Modifier.padding(top = 4.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun ToolCard(
    info: ToolCardInfo,
    pinned: Boolean,
    select: (Tool) -> Unit,
    setPinned: (Tool, Boolean) -> Unit,
    compact: Boolean = false,
) {
    val title = stringResource(info.title)
    val description = stringResource(info.description)
    val label = stringResource(R.string.tools_card_label, title, description)
    val action = stringResource(if (pinned) R.string.tools_unpin else R.string.tools_pin)
    MenuCard(Modifier.fillMaxWidth(), { select(info.tool) }, action, { setPinned(info.tool, !pinned) }) {
        if (compact) {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolBadge(info)
                ToolText(title, description, label, Modifier.weight(1f))
                if (pinned) PinIcon()
            }
        } else {
            Column(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 124.dp).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ToolBadge(info)
                    if (pinned) PinIcon()
                }
                ToolText(title, description, label)
            }
        }
    }
}

@Composable
private fun ToolText(title: String, description: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clearAndSetSemantics { contentDescription = label }) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToolBadge(info: ToolCardInfo) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (info.group) {
        ToolGroup.ORGANIZE -> colors.primaryContainer to colors.onPrimaryContainer
        ToolGroup.SHRINK -> colors.secondaryContainer to colors.onSecondaryContainer
        ToolGroup.PRINT -> colors.tertiaryContainer to colors.onTertiaryContainer
        ToolGroup.PROTECT -> lerp(colors.surfaceContainerHigh, colors.primaryContainer, 0.55f) to colors.onSurface
        ToolGroup.CONVERT -> lerp(colors.secondaryContainer, colors.tertiaryContainer, 0.55f) to colors.onSurface
    }
    Surface(
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = background,
        contentColor = foreground,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(info.icon), contentDescription = null)
        }
    }
}

@Composable
private fun PinIcon() {
    Icon(
        painterResource(R.drawable.tools_pin), contentDescription = null,
        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RecentCard(entry: RecentOutput, open: () -> Unit, remove: () -> Unit) {
    val icon = if (File(entry.name).imageMime() == null) R.drawable.tools_document else R.drawable.tools_image
    val time = DateUtils.getRelativeTimeSpanString(entry.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    MenuCard(Modifier.width(184.dp), open, stringResource(R.string.tools_recent_remove), remove) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.tools_recent_detail, stringResource(ToolCardInfo.valueOf(entry.tool.name).title), time),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MenuCard(
    modifier: Modifier,
    open: () -> Unit,
    action: String,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            Modifier.fillMaxWidth().combinedClickable(
                role = Role.Button,
                onClick = open,
                onLongClickLabel = action,
                onLongClick = { expanded = true },
            )
        ) {
            content()
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(action) }, onClick = {
                    expanded = false
                    onAction()
                })
            }
        }
    }
}

@Composable
private fun rememberOpenRecentOutput(
    model: ToolsModel,
    openPdf: (File) -> Unit,
    snackbar: SnackbarHostState,
): (RecentOutput) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val missingFile = stringResource(R.string.tools_recent_missing)
    val noImageViewer = stringResource(R.string.tools_recent_no_viewer)
    return { entry ->
        scope.launch {
            try {
                val mime = File(entry.name).imageMime()
                if (mime == null) openPdf(model.recentPdf(entry))
                else context.startActivity(model.recentImageIntent(entry, mime))
            } catch (_: FileNotFoundException) {
                model.removeRecent(entry)
                snackbar.showSnackbar(missingFile)
            } catch (_: ActivityNotFoundException) {
                snackbar.showSnackbar(noImageViewer)
            } catch (error: IOException) {
                snackbar.showSnackbar(error.toolMessage(context))
            } catch (error: SecurityException) {
                snackbar.showSnackbar(error.toolMessage(context))
            }
        }
    }
}

@Composable
fun HomeToolOutputs(onOpenPdf: (File) -> Unit, snackbar: SnackbarHostState) {
    val model = toolsModel(key = "home-tool-outputs")
    val history by model.history.collectAsStateWithLifecycle()
    val open = rememberOpenRecentOutput(model, onOpenPdf, snackbar)
    if (history.recent.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tools_recent_outputs), style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history.recent, key = { it.sourcePath }) { entry ->
                SuggestionChip(
                    onClick = { open(entry) },
                    label = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.widthIn(max = 192.dp).heightIn(min = 48.dp),
                )
            }
        }
    }
}
