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
    private val releases = listOf(
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

    fun shouldShow(lastVersion: String?, currentVersion: String, previouslyUpdated: Boolean): Boolean =
        lastVersion?.let { it != currentVersion } ?: previouslyUpdated
}
