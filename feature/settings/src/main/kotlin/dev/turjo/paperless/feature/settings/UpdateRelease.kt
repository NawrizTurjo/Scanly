package dev.turjo.paperless.feature.settings

import org.json.JSONObject

internal data class UpdateRelease(
    val version: String,
    val notes: String,
    val url: String,
    val size: Long,
    /** A delta patch from the installed version, when the release has one. */
    val patch: UpdatePatch? = null,
)

internal data class UpdatePatch(val url: String, val size: Long)

/** Release file name of the patch that turns [installed] into [version]. */
internal fun patchAssetName(version: String, installed: String) = "Scanly-$version-from-$installed.patch"

internal class UpdateException(val messageResource: Int) : Exception()

internal fun parseUpdateRelease(json: String, installed: String? = null): UpdateRelease {
    val release = JSONObject(json)
    val version = release.getString("tag_name").removePrefix("v")
    require(versionParts(version) != null)
    val assets = release.getJSONArray("assets")
    val patchName = installed?.let { patchAssetName(version, it) }
    var apk: UpdateRelease? = null
    var patch: UpdatePatch? = null
    for (index in 0 until assets.length()) {
        val asset = assets.getJSONObject(index)
        val name = asset.getString("name")
        val size = asset.getLong("size")
        if (apk == null && name.endsWith("-arm64.apk")) {
            require(size > 0)
            apk = UpdateRelease(
                version, if (release.isNull("body")) "" else release.getString("body"),
                asset.getString("browser_download_url"), size,
            )
        } else if (name == patchName && size > 0) {
            patch = UpdatePatch(asset.getString("browser_download_url"), size)
        }
    }
    return apk?.copy(patch = patch) ?: throw UpdateException(R.string.update_no_asset)
}

/** "https://github.com/o/r/releases/tag/v1.2.3" -> "1.2.3"; null for anything else. */
internal fun tagFromReleaseLocation(location: String): String? {
    val tag = location.substringAfter("/releases/tag/", "").substringBefore('?').substringBefore('#')
    val version = tag.removePrefix("v")
    return version.takeIf { tag.startsWith("v") && versionParts(it) != null }
}

private fun versionParts(version: String): List<Int>? {
    val parts = version.removePrefix("v").substringBefore('+').split('.')
    if (parts.size != 3) return null
    return parts.map { part ->
        if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
        part.toIntOrNull() ?: return null
    }
}

internal fun isNewerVersion(candidate: String, installed: String): Boolean {
    val next = versionParts(candidate) ?: return false
    val current = versionParts(installed) ?: versionParts(installed.substringBefore('-')) ?: return false
    return next.zip(current).firstOrNull { (left, right) -> left != right }
        ?.let { (left, right) -> left > right }
        ?: ('-' in installed.substringBefore('+'))
}

internal fun shouldAutoCheck(enabled: Boolean, lastCheck: Long?, now: Long): Boolean =
    enabled && (lastCheck == null || now - lastCheck >= 24 * 60 * 60 * 1000L)

internal fun shouldShowRelease(version: String, skippedVersion: String?): Boolean = version != skippedVersion
