package dev.turjo.paperless.feature.settings

import org.json.JSONObject

internal data class UpdateRelease(val version: String, val notes: String, val url: String, val size: Long)

internal class UpdateException(val messageResource: Int) : Exception()

internal fun parseUpdateRelease(json: String): UpdateRelease {
    val release = JSONObject(json)
    val version = release.getString("tag_name").removePrefix("v")
    require(versionParts(version) != null)
    val assets = release.getJSONArray("assets")
    for (index in 0 until assets.length()) {
        val asset = assets.getJSONObject(index)
        if (asset.getString("name").endsWith("-arm64.apk")) {
            val size = asset.getLong("size")
            require(size > 0)
            return UpdateRelease(
                version, if (release.isNull("body")) "" else release.getString("body"),
                asset.getString("browser_download_url"), size,
            )
        }
    }
    throw UpdateException(R.string.update_no_asset)
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
