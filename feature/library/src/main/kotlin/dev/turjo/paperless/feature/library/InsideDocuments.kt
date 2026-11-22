package dev.turjo.paperless.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.ContentHit
import dev.turjo.paperless.core.data.ContentSearchRepository
import javax.inject.Inject
import kotlinx.coroutines.delay

@HiltViewModel
internal class ContentSearchViewModel @Inject constructor(val repository: ContentSearchRepository) : ViewModel()

@Composable
internal fun insideDocumentHits(query: String): List<ContentHit> {
    if (query.trim().length < 2) return emptyList()
    val model: ContentSearchViewModel = hiltViewModel()
    var hits by remember(query) { mutableStateOf(emptyList<ContentHit>()) }
    LaunchedEffect(query, model) {
        delay(150)
        model.repository.observeCounts().collect { hits = model.repository.search(query) }
    }
    return hits
}

@Composable
internal fun InsideDocumentHit(hit: ContentHit, onClick: (() -> Unit)?) {
    val modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    val snippet = remember(hit.snippet) {
        buildAnnotatedString {
            var end = 0
            for (match in highlight.findAll(hit.snippet)) {
                append(hit.snippet.substring(end, match.range.first))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
                end = match.range.last + 1
            }
            append(hit.snippet.substring(end))
        }
    }
    ListItem(
        headlineContent = { Text(hit.title) },
        overlineContent = { Text(stringResource(R.string.inside_documents_page, hit.pageIndex + 1)) },
        supportingContent = { Text(snippet, Modifier.padding(bottom = 4.dp)) },
        modifier = modifier,
    )
}

private val highlight = Regex("\\[([^\\[\\]]+)]")

