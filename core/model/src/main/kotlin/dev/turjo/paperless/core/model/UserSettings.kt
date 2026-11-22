package dev.turjo.paperless.core.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AppLock { OFF, ON_OPEN, AFTER_MINUTES }

enum class AutoCaptureSensitivity { LOW, NORMAL, HIGH }

val AUTO_CAPTURE_DELAYS_MS = listOf(400, 700, 1200)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AccentColor { BLUE, TEAL, GREEN, ORANGE, ROSE }

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val autoNamePattern: String = "Scan {date} {time}",
    val appLock: AppLock = AppLock.OFF,
    val lockAfterMinutes: Int = 5,
    val autoCaptureDelayMs: Int = 700,
    val autoCaptureSensitivity: AutoCaptureSensitivity = AutoCaptureSensitivity.NORMAL,
    val amoledBlack: Boolean = false,
    val accent: AccentColor = AccentColor.BLUE,
)

private val autoNameDate = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
private val autoNameTime = DateTimeFormatter.ofPattern("HH.mm", Locale.ROOT)

fun formatAutoName(pattern: String, now: LocalDateTime, n: Int): String =
    pattern.replace("{date}", now.format(autoNameDate))
        .replace("{time}", now.format(autoNameTime))
        .replace("{n}", n.toString())
