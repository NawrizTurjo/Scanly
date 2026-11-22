package dev.turjo.paperless.feature.settings

import android.content.res.AssetManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

private data class Dependency(val name: String, val version: String, val licence: String, val url: String)

private val licenceIds = listOf("Apache-2.0", "MIT", "BSD-3-Clause")

@Composable
internal fun OpenSourceLicences(onDismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    val dependencies by produceState<Result<List<Dependency>>?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            try {
                val entries = JSONArray(assets.licenceAsset("dependencies.json"))
                Result.success(List(entries.length()) { index ->
                    val entry = entries.getJSONObject(index)
                    Dependency(
                        entry.getString("name"), entry.getString("version"),
                        entry.getString("license"), entry.getString("url"),
                    )
                })
            } catch (error: IOException) {
                Result.failure(error)
            } catch (error: JSONException) {
                Result.failure(error)
            }
        }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val licence = selected
    if (licence != null) {
        val title by produceState<Result<String>?>(null, assets, licence) {
            value = withContext(Dispatchers.IO) {
                try {
                    Result.success(assets.licenceAsset("$licence.txt"))
                } catch (error: IOException) {
                    Result.failure(error)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(licence) },
            text = {
                when {
                    title == null -> CircularProgressIndicator()
                    title?.isFailure == true -> Text(stringResource(R.string.licences_read_error))
                    else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        item { SelectionContainer { Text(title?.getOrNull().orEmpty()) } }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.licences_back)) }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.open_source_licences)) },
            text = {
                val result = dependencies
                when {
                    result == null -> CircularProgressIndicator()
                    result.isFailure -> Text(stringResource(R.string.licences_read_error))
                    else -> LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((id, entries) in result.getOrThrow().groupBy(Dependency::licence).toSortedMap()) {
                            item { Text(id, style = MaterialTheme.typography.titleMedium) }
                            items(entries) { dependency ->
                                Column {
                                    Text(dependency.name, style = MaterialTheme.typography.titleSmall)
                                    Text(dependency.version)
                                    SelectionContainer { Text(dependency.url) }
                                }
                            }
                        }
                        item {
                            Text(stringResource(R.string.licence_texts), style = MaterialTheme.typography.titleMedium)
                        }
                        items(licenceIds) { id ->
                            TextButton(onClick = { selected = id }) { Text(id) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.licences_close)) } },
        )
    }
}

private fun AssetManager.licenceAsset(name: String): String =
    open("licenses/$name").bufferedReader().use { it.readText() }
