package dev.turjo.paperless

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LAST_VERSION = "last_version"

private class TourState(
    val version: String,
    private val preferences: SharedPreferences,
    pending: Boolean,
) {
    val release = WhatsNew.releaseFor(version)
    var pending by mutableStateOf(pending)
        private set

    fun markShown() {
        preferences.edit().putString(LAST_VERSION, version).apply()
        pending = false
    }
}

private fun loadTour(context: Context): TourState {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val version = checkNotNull(info.versionName)
    val preferences = context.getSharedPreferences("whats_new", Context.MODE_PRIVATE)
    val lastVersion = preferences.getString(LAST_VERSION, null)
    // Installs that predate the tour have no preference yet.
    val pending = WhatsNew.shouldShow(lastVersion, version, info.lastUpdateTime > info.firstInstallTime)
    return TourState(version, preferences, pending).also {
        if (lastVersion == null && !pending) it.markShown()
    }
}

@Composable
internal fun rememberWhatsNewTour(
    homeVisible: Boolean,
    settingsVisible: Boolean,
    canPrompt: Boolean,
): () -> Unit {
    val context = LocalContext.current.applicationContext
    var state by remember(context) { mutableStateOf<TourState?>(null) }
    var autoVersion by rememberSaveable { mutableStateOf<String?>(null) }
    var openedFromSettings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(context) {
        state = withContext(Dispatchers.IO) { loadTour(context) }
    }

    val tour = state
    if (tour != null) {
        val automatic = homeVisible && canPrompt && tour.release != null &&
            (autoVersion == tour.version || tour.pending)
        val manual = settingsVisible && openedFromSettings
        if (automatic || manual) {
            LaunchedEffect(tour, automatic) {
                if (automatic) autoVersion = tour.version
                if (tour.pending && tour.release != null) tour.markShown()
            }
            WhatsNewDialog(tour.version, tour.release) {
                autoVersion = null
                openedFromSettings = false
            }
        }
    }
    return { openedFromSettings = true }
}
