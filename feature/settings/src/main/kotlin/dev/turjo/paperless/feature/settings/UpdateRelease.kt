package dev.turjo.paperless.feature.settings

import org.json.JSONArray
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

internal fun parseUpdateRelease(json: String, installed: String? = null): UpdateRelease =
    releaseFrom(JSONObject(json), installed) ?: throw UpdateException(R.string.update_no_asset)

/**
 * Test builds: the newest release in the API's list, pre-releases (nightlies) included. Drafts and
 * releases without an APK are skipped.
 */
internal fun parseNewestRelease(json: String, installed: String? = null): UpdateRelease {
    val releases = JSONArray(json)
    return (0 until releases.length())
        .map(releases::getJSONObject)
        .filter { !it.optBoolean("draft") && parseVersion(it.getString("tag_name")) != null }
        .mapNotNull { releaseFrom(it, installed) }
        .maxWithOrNull { left, right -> compareVersions(left.version, right.version) }
        ?: throw UpdateException(R.string.update_no_asset)
}

private fun releaseFrom(release: JSONObject, installed: String?): UpdateRelease? {
    val version = release.getString("tag_name").removePrefix("v")
    require(parseVersion(version) != null)
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
    return apk?.copy(patch = patch)
}

private val ATOM_TAG = Regex("/releases/tag/(v[^\"<?#\\s]+)")

/** Newest release tag in GitHub's releases.atom feed (lists pre-releases too, no API limit). */
internal fun newestTagFromAtom(xml: String): String? =
    ATOM_TAG.findAll(xml)
        .map { it.groupValues[1].removePrefix("v") }
        .filter { parseVersion(it) != null }
        .maxWithOrNull(::compareVersions)

/** "https://github.com/o/r/releases/tag/v1.2.3" -> "1.2.3" (pre-release tags too); null for anything else. */
internal fun tagFromReleaseLocation(location: String): String? {
    val tag = location.substringAfter("/releases/tag/", "").substringBefore('?').substringBefore('#')
    val version = tag.removePrefix("v")
    return version.takeIf { tag.startsWith("v") && parseVersion(it) != null }
}

/** Semantic version: `1.2.3`, `2.0.0-nightly.2`; build metadata after `+` is ignored. */
internal data class Version(val core: List<Int>, val preRelease: List<String>)

internal fun parseVersion(text: String): Version? {
    val version = text.removePrefix("v").substringBefore('+')
    val core = version.substringBefore('-').split('.')
    if (core.size != 3 || core.any { part -> part.isEmpty() || part.any { it !in '0'..'9' } }) return null
    val numbers = core.map { it.toIntOrNull() ?: return null }
    val preRelease = if ('-' in version) version.substringAfter('-').split('.') else emptyList()
    if (preRelease.any { id -> id.isEmpty() || id.any { !it.isLetterOrDigit() && it != '-' } }) return null
    return Version(numbers, preRelease)
}

/** Semver precedence: a release beats its pre-releases; `nightly.10` beats `nightly.9`. Unparsable sorts first. */
internal fun compareVersions(left: String, right: String): Int {
    val a = parseVersion(left)
    val b = parseVersion(right)
    if (a == null || b == null) return compareValues(a != null, b != null)
    a.core.zip(b.core).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> return x.compareTo(y) }
    if (a.preRelease.isEmpty() || b.preRelease.isEmpty()) {
        return compareValues(a.preRelease.isEmpty(), b.preRelease.isEmpty())
    }
    for ((x, y) in a.preRelease.zip(b.preRelease)) {
        val xNumber = x.toIntOrNull()
        val yNumber = y.toIntOrNull()
        val order = when {
            xNumber != null && yNumber != null -> xNumber.compareTo(yNumber)
            xNumber != null -> -1
            yNumber != null -> 1
            else -> x.compareTo(y)
        }
        if (order != 0) return order
    }
    return a.preRelease.size.compareTo(b.preRelease.size)
}

internal fun isNewerVersion(candidate: String, installed: String): Boolean =
    parseVersion(candidate) != null && parseVersion(installed) != null && compareVersions(candidate, installed) > 0

internal fun shouldAutoCheck(enabled: Boolean, lastCheck: Long?, now: Long): Boolean =
    enabled && (lastCheck == null || now - lastCheck >= 24 * 60 * 60 * 1000L)

internal fun shouldShowRelease(version: String, skippedVersion: String?): Boolean = version != skippedVersion
