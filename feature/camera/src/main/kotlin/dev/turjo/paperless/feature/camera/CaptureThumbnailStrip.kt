package dev.turjo.paperless.feature.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ThumbnailHeight = 44.dp
private val MaxThumbnailWidth = 60.dp

@Composable
internal fun CaptureThumbnailStrip(model: CameraViewModel, state: CameraState) {
    val busyIds by model.busyPageIds.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val minWidthPx = with(density) { 32.dp.roundToPx() }
    val maxWidthPx = with(density) { MaxThumbnailWidth.roundToPx() }
    val heightPx = with(density) { ThumbnailHeight.roundToPx() }
    val cache = remember(model, maxWidthPx, heightPx) { LruCache<Pair<String, Boolean>, Bitmap>(24) }
    var previousIds by remember(model) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.pageIds) {
        val addedIndex = state.pageIds.indexOfLast { it !in previousIds }
        previousIds = state.pageIds.toSet()
        if (addedIndex >= 0) listState.animateScrollToItem(addedIndex)
    }
    LaunchedEffect(cache, busyIds) {
        busyIds.forEach { pageId ->
            cache.remove(pageId to false)
            cache.remove("book-right-$pageId" to false)
        }
    }
    if (state.pageIds.isEmpty()) return

    val enabled = state.canEdit && !state.idBack
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        userScrollEnabled = enabled,
    ) {
        itemsIndexed(state.pageIds, key = { _, pageId -> pageId }) { index, pageId ->
            // Both book halves are rendered under the left page's processing job.
            val busy = pageId in busyIds ||
                (pageId.startsWith("book-right-") && pageId.removePrefix("book-right-") in busyIds)
            val cacheKey = pageId to busy
            var thumbnail by remember(cache, cacheKey) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(cache, cacheKey) {
                if (!busy) {
                    thumbnail = cache.get(cacheKey) ?: withContext(Dispatchers.IO) {
                        decodeThumbnail(model.thumbnailFile(pageId).path, minWidthPx, maxWidthPx, heightPx)
                    }?.also { cache.put(cacheKey, it) }
                }
            }
            CaptureThumbnail(
                number = index + 1,
                thumbnail = thumbnail,
                busy = busy,
                enabled = enabled,
                onReview = model::review,
                onDelete = { model.requestPageDeletion(pageId) },
            )
        }
    }
}

@Composable
private fun CaptureThumbnail(
    number: Int,
    thumbnail: Bitmap?,
    busy: Boolean,
    enabled: Boolean,
    onReview: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(enabled) { mutableStateOf(false) }
    val description = stringResource(R.string.camera_page_number, number)
    val width = thumbnail?.let {
        (ThumbnailHeight * (it.width.toFloat() / it.height)).coerceIn(32.dp, MaxThumbnailWidth)
    } ?: 44.dp
    Box {
        Box(
            modifier = Modifier.widthIn(min = 48.dp).height(48.dp)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onReview,
                    onLongClick = { menuOpen = true },
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(width, ThumbnailHeight)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.DarkGray)
                    .clearAndSetSemantics {},
            ) {
                if (thumbnail != null) {
                    Image(thumbnail.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                }
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(14.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
                Badge(Modifier.align(Alignment.BottomEnd).padding(2.dp)) {
                    Text(number.toString())
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.camera_delete_page)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

private fun decodeThumbnail(path: String, minWidth: Int, maxWidth: Int, height: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null

    // Decode enough pixels to fill the thumbnail crop at the current display density.
    val width = (height * (options.outWidth.toFloat() / options.outHeight)).toInt().coerceIn(minWidth, maxWidth)
    var sample = 1
    while (options.outWidth / (sample * 2) >= width && options.outHeight / (sample * 2) >= height) {
        sample *= 2
    }
    options.inSampleSize = sample
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(path, options)
}
