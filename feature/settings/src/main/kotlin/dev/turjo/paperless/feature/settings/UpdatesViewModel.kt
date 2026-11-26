package dev.turjo.paperless.feature.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class UpdatesState(
    val automatic: Boolean? = null,
    /** Nightlies and other pre-releases are offered too. */
    val testBuilds: Boolean? = null,
    val checking: Boolean = false,
    val installedVersion: String? = null,
    val release: UpdateRelease? = null,
    val skippedVersion: String? = null,
    val showCard: Boolean = false,
    val showDialog: Boolean = false,
    val progress: Float? = null,
    val awaitingPermission: Boolean = false,
    val error: Int? = null,
)

class UpdatesViewModel(private val app: Application) : AndroidViewModel(app) {
    private val mutableState = MutableStateFlow(UpdatesState())
    internal val state = mutableState.asStateFlow()
    private val preferencesDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val preferences by lazy { app.getSharedPreferences("updates", 0) }
    private val directory get() = File(app.cacheDir, "updates")
    private var check: Job? = null
    private var download: Job? = null
    private var apk: File? = null
    private var installation: Job? = null

    private val initialize = viewModelScope.launch(Dispatchers.IO) {
        directory.listFiles()?.forEach { it.delete() }
        withContext(preferencesDispatcher) {
            val automatic = preferences.getBoolean("automatic", true)
            val testBuilds = preferences.getBoolean("test_builds", false)
            mutableState.update { it.copy(automatic = automatic, testBuilds = testBuilds) }
        }
    }

    fun checkOnStartup() = checkForUpdate(automatic = true)

    internal fun checkForUpdate(automatic: Boolean = false) {
        if (check?.isActive == true || state.value.showDialog) return
        check = viewModelScope.launch {
            if (!automatic) mutableState.update { it.copy(checking = true, error = null) }
            try {
                val proceed = withContext(preferencesDispatcher) {
                    val now = System.currentTimeMillis()
                    val last = preferences.getLong("last_check", -1).takeUnless { it == -1L }
                    val enabled = preferences.getBoolean("automatic", true)
                    if (automatic && !shouldAutoCheck(enabled, last, now)) return@withContext false
                    preferences.edit { putLong("last_check", now) }
                    true
                }
                if (!proceed) return@launch
                mutableState.update { it.copy(checking = true, error = null) }
                initialize.join()
                val testBuilds = state.value.testBuilds == true
                val (installed, newer) = withContext(Dispatchers.IO) {
                    val installed = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
                    val latest = try {
                        if (testBuilds) {
                            request(RELEASES_URL) { connection ->
                                parseNewestRelease(connection.inputStream.bufferedReader().use { it.readText() }, installed)
                            }
                        } else {
                            request(RELEASE_URL) { connection ->
                                parseUpdateRelease(connection.inputStream.bufferedReader().use { it.readText() }, installed)
                            }
                        }
                    } catch (limited: UpdateException) {
                        // The API allows 60 anonymous requests an hour per IP, which a shared network (campus Wi-Fi,
                        // carrier NAT) can use up. The website has no such limit: /releases/latest redirects to the
                        // newest stable tag, and releases.atom lists nightlies too.
                        if (limited.messageResource != R.string.update_rate_limited) throw limited
                        if (testBuilds) newestFromWebsite(installed) else latestFromWebsite(installed)
                    }
                    installed to latest.takeIf { isNewerVersion(it.version, installed) }
                }
                val skipped = withContext(preferencesDispatcher) {
                    preferences.getString("skipped_version", null)
                }
                val release = newer?.takeIf { shouldShowRelease(it.version, skipped) }
                mutableState.update {
                    val sameRelease = it.release?.version == release?.version
                    it.copy(
                        installedVersion = installed, release = release,
                        skippedVersion = newer?.version?.takeIf { release == null },
                        showCard = release != null && (automatic || (sameRelease && it.showCard)),
                        showDialog = release != null && (!automatic || (sameRelease && it.showDialog)),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                setError(failure, R.string.update_check_failed)
            } finally {
                mutableState.update { it.copy(checking = false) }
            }
        }
    }

    internal fun setAutomatic(enabled: Boolean) {
        mutableState.update { it.copy(automatic = enabled) }
        viewModelScope.launch(preferencesDispatcher) {
            preferences.edit { putBoolean("automatic", enabled) }
        }
    }

    internal fun setTestBuilds(enabled: Boolean) {
        mutableState.update { it.copy(testBuilds = enabled) }
        viewModelScope.launch {
            withContext(preferencesDispatcher) { preferences.edit { putBoolean("test_builds", enabled) } }
            checkForUpdate()
        }
    }

    internal fun showRelease() = mutableState.update {
        if (it.checking) it else it.copy(showDialog = true, error = null)
    }

    internal fun dismissCard() = mutableState.update { it.copy(showCard = false) }

    internal fun dismissDialog() {
        discardPendingInstall()
        mutableState.update { it.copy(showDialog = false, awaitingPermission = false, error = null) }
    }

    internal fun skipRelease() {
        val version = state.value.release?.version ?: return
        dismissDialog()
        mutableState.update { it.copy(release = null, showCard = false, skippedVersion = version) }
        viewModelScope.launch(preferencesDispatcher) {
            preferences.edit { putString("skipped_version", version) }
        }
    }

    internal fun startDownload() {
        val release = state.value.release ?: return
        if (download?.isActive == true || installation?.isActive == true) return
        if (apk != null) {
            mutableState.update { it.copy(awaitingPermission = true, error = null) }
            return
        }
        download = viewModelScope.launch {
            mutableState.update { it.copy(progress = 0f, error = null) }
            var temporaryFile: File? = null
            try {
                initialize.join()
                apk = withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    val file = File.createTempFile("scanly-", ".apk", directory)
                    temporaryFile = file
                    // A patch rebuilds the APK from the installed one, so only the changes download.
                    // Anything wrong with it (missing, damaged, different installed build) falls back
                    // to the full APK.
                    val patched = release.patch?.let { patch ->
                        val patchFile = File.createTempFile("scanly-", ".patch", directory)
                        try {
                            download(patch.url, patchFile, patch.size)
                            ApkPatch.apply(File(app.applicationInfo.sourceDir), patchFile, file) { ensureActive() }
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            mutableState.update { it.copy(progress = 0f) }
                            false
                        } finally {
                            patchFile.delete()
                        }
                    } ?: false
                    if (!patched) download(release.url, file, release.size)
                    val manager = app.packageManager
                    val archive = manager.getPackageArchiveInfo(file.path, 0)
                    val installed = manager.getPackageInfo(app.packageName, 0)
                    if (file.length() != release.size || archive?.packageName != app.packageName ||
                        archive.longVersionCode <= installed.longVersionCode
                    ) throw UpdateException(R.string.update_verify_failed)
                    file
                }
                mutableState.update { it.copy(awaitingPermission = !app.packageManager.canRequestPackageInstalls()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                setError(failure, R.string.update_download_failed)
            } finally {
                if (apk == null) withContext(NonCancellable + Dispatchers.IO) { temporaryFile?.delete() }
                mutableState.update { it.copy(progress = null) }
            }
        }
    }

    /** Streams [url] into [file], reporting progress against the expected [size]. */
    private suspend fun download(url: String, file: File, size: Long) = withContext(Dispatchers.IO) {
        request(url) { connection ->
            connection.inputStream.use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytes = 0L
                    var percent = 0
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        bytes += count
                        if (bytes > size) throw UpdateException(R.string.update_verify_failed)
                        output.write(buffer, 0, count)
                        val nextPercent = (100.0 * bytes / size).toInt()
                        if (nextPercent != percent) {
                            percent = nextPercent
                            mutableState.update { it.copy(progress = percent / 100f) }
                        }
                    }
                }
            }
        }
        if (file.length() != size) throw UpdateException(R.string.update_verify_failed)
    }

    internal fun cancelDownload() = download?.cancel()

    internal fun hasPendingInstall(): Boolean = apk != null

    internal fun permissionIntent() = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.fromParts("package", app.packageName, null),
    )

    internal fun onPermissionReturn(launch: (Intent) -> Unit) {
        if (app.packageManager.canRequestPackageInstalls()) install(launch)
        else if (apk != null) mutableState.update { it.copy(error = R.string.update_permission_denied) }
    }

    internal fun install(launch: (Intent) -> Unit) {
        val file = apk ?: return
        if (installation?.isActive == true) return
        installation = viewModelScope.launch {
            try {
                if (!app.packageManager.canRequestPackageInstalls()) {
                    mutableState.update { it.copy(awaitingPermission = true) }
                    return@launch
                }
                val uri = withContext(Dispatchers.IO) {
                    FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                }
                if (apk != file) return@launch
                launch(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                apk = null
                mutableState.update { it.copy(awaitingPermission = false, showDialog = false, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                installPermissionUnavailable()
            }
        }
    }

    internal fun installPermissionUnavailable() {
        discardPendingInstall()
        mutableState.update { it.copy(awaitingPermission = false, error = R.string.update_install_failed) }
    }

    private fun discardPendingInstall() {
        val file = apk ?: return
        apk = null
        viewModelScope.launch(Dispatchers.IO) { file.delete() }
    }

    private fun setError(failure: Exception, fallback: Int) {
        val message = when (failure) {
            is UpdateException -> failure.messageResource
            is UnknownHostException, is SocketException, is SocketTimeoutException -> R.string.update_connection_failed
            is IOException -> if (fallback == R.string.update_check_failed) R.string.update_connection_failed else fallback
            else -> fallback
        }
        mutableState.update { it.copy(error = message) }
    }
}

private const val RELEASE_URL = "https://api.github.com/repos/NawrizTurjo/Scanly/releases/latest"
private const val RELEASES_URL = "https://api.github.com/repos/NawrizTurjo/Scanly/releases?per_page=20"
private const val RELEASES_PAGE = "https://github.com/NawrizTurjo/Scanly/releases"

/** Latest stable release without the API: the tag from the website redirect, the APK by its release file name. */
private fun latestFromWebsite(installed: String): UpdateRelease {
    val connection = URL("$RELEASES_PAGE/latest").openConnection() as HttpURLConnection
    val location = try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Scanly-updater")
        if (connection.responseCode !in 300..399) throw UpdateException(R.string.update_server_failed)
        connection.getHeaderField("Location") ?: throw UpdateException(R.string.update_server_failed)
    } finally {
        connection.disconnect()
    }
    val version = tagFromReleaseLocation(location) ?: throw UpdateException(R.string.update_not_found)
    return releaseFromWebsite(version, installed)
}

/** Newest release including nightlies, without the API: the tag list from releases.atom. */
private fun newestFromWebsite(installed: String): UpdateRelease {
    val feed = request("$RELEASES_PAGE.atom") { connection -> connection.inputStream.bufferedReader().use { it.readText() } }
    val version = newestTagFromAtom(feed) ?: throw UpdateException(R.string.update_not_found)
    return releaseFromWebsite(version, installed)
}

/** The APK (and a patch from [installed], if published) by their release file names. */
private fun releaseFromWebsite(version: String, installed: String): UpdateRelease {
    val url = "$RELEASES_PAGE/download/v$version/Scanly-$version-arm64.apk"
    val size = request(url) { it.contentLengthLong }
    if (size <= 0) throw UpdateException(R.string.update_no_asset)
    val patchUrl = "$RELEASES_PAGE/download/v$version/${patchAssetName(version, installed)}"
    val patch = try {
        request(patchUrl) { it.contentLengthLong }.takeIf { it > 0 }?.let { UpdatePatch(patchUrl, it) }
    } catch (_: Exception) {
        null // No patch for this installed version (or GitHub hiccuped): download the full APK.
    }
    return UpdateRelease(version, "", url, size, patch)
}

private inline fun <T> request(url: String, read: (HttpURLConnection) -> T): T {
    var destination = URL(url)
    repeat(5) {
        if (destination.protocol != "https" || destination.host !in setOf(
                "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
            )
        ) throw UpdateException(R.string.update_server_failed)
        val connection = destination.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Scanly-updater")
            if (destination.host == "api.github.com") {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
            }
            when (connection.responseCode) {
                200 -> return read(connection)
                301, 302, 303, 307, 308 -> destination = URL(
                    destination,
                    connection.getHeaderField("Location") ?: throw UpdateException(R.string.update_server_failed),
                )
                404 -> throw UpdateException(R.string.update_not_found)
                403 -> throw UpdateException(
                    if (connection.getHeaderField("X-RateLimit-Remaining") == "0" ||
                        connection.getHeaderField("Retry-After") != null
                    ) R.string.update_rate_limited else R.string.update_server_failed,
                )
                429 -> throw UpdateException(R.string.update_rate_limited)
                else -> throw UpdateException(R.string.update_server_failed)
            }
        } finally {
            connection.disconnect()
        }
    }
    throw UpdateException(R.string.update_server_failed)
}
