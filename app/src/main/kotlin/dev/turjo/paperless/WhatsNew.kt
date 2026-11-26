package dev.turjo.paperless

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.turjo.paperless.core.designsystem.R as DesignR

internal data class WhatsNewCard(
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
)

internal data class WhatsNewRelease(val version: String, val cards: List<WhatsNewCard>)

internal object WhatsNew {
    /** Newest first. */
    private val releases = listOf(
        WhatsNewRelease("1.1.4", listOf(
            WhatsNewCard(R.drawable.ic_zoom_in, R.string.whats_new_sharp_title, R.string.whats_new_sharp_body),
            WhatsNewCard(DesignR.drawable.ic_tools, R.string.whats_new_slim_title, R.string.whats_new_slim_body),
            WhatsNewCard(DesignR.drawable.ic_settings, R.string.whats_new_patch_title, R.string.whats_new_patch_body),
        )),
        WhatsNewRelease("1.1.3", listOf(
            WhatsNewCard(R.drawable.ic_zoom_in, R.string.whats_new_zoom_title, R.string.whats_new_zoom_body),
            WhatsNewCard(DesignR.drawable.ic_settings, R.string.whats_new_notes_title, R.string.whats_new_notes_body),
            WhatsNewCard(DesignR.drawable.ic_tools, R.string.whats_new_size_title, R.string.whats_new_size_body),
        )),
        WhatsNewRelease("1.1.2", listOf(
            WhatsNewCard(R.drawable.ic_home, R.string.whats_new_home_title, R.string.whats_new_home_body),
            WhatsNewCard(DesignR.drawable.ic_scan, R.string.whats_new_scan_title, R.string.whats_new_scan_body),
            WhatsNewCard(R.drawable.ic_scan_board, R.string.whats_new_clean_title, R.string.whats_new_clean_body),
            WhatsNewCard(DesignR.drawable.ic_library, R.string.whats_new_text_title, R.string.whats_new_text_body),
            WhatsNewCard(R.drawable.ic_sign_pdf, R.string.whats_new_sign_title, R.string.whats_new_sign_body),
            WhatsNewCard(DesignR.drawable.ic_tools, R.string.whats_new_export_title, R.string.whats_new_export_body),
            WhatsNewCard(R.drawable.ic_scan_id, R.string.whats_new_id_title, R.string.whats_new_id_body),
            WhatsNewCard(DesignR.drawable.ic_reader, R.string.whats_new_reader_title, R.string.whats_new_reader_body),
            WhatsNewCard(DesignR.drawable.ic_settings, R.string.whats_new_defaults_title, R.string.whats_new_defaults_body),
            WhatsNewCard(DesignR.drawable.ic_settings, R.string.whats_new_updates_title, R.string.whats_new_updates_body),
        )),
    )

    fun releaseFor(version: String): WhatsNewRelease? = releases.find { it.version == version }

    /**
     * The tour after an update: cards from every release newer than [lastVersion] up to
     * [currentVersion], newest first, so skipping a version doesn't hide its changes. Installs
     * that predate the tour ([lastVersion] null) get every release. Falls back to the current
     * release (a downgrade, or the same version).
     */
    fun tourFor(lastVersion: String?, currentVersion: String): WhatsNewRelease? {
        val current = versionParts(currentVersion) ?: return releaseFor(currentVersion)
        val last = lastVersion?.let(::versionParts)
        val cards = releases.filter { release ->
            val parts = versionParts(release.version) ?: return@filter false
            compareParts(parts, current) <= 0 && (last == null || compareParts(parts, last) > 0)
        }.flatMap { it.cards }
        return if (cards.isEmpty()) releaseFor(currentVersion) else WhatsNewRelease(currentVersion, cards)
    }

    private fun versionParts(version: String): List<Int>? =
        version.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: return null }

    private fun compareParts(left: List<Int>, right: List<Int>): Int {
        for (index in 0 until maxOf(left.size, right.size)) {
            val difference = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }

    fun shouldShow(lastVersion: String?, currentVersion: String, previouslyUpdated: Boolean): Boolean =
        lastVersion?.let { it != currentVersion } ?: previouslyUpdated
}
