package dev.turjo.paperless.core.data

import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeParseException
import java.util.Locale

internal data class LibrarySearch(
    val text: String,
    val dates: Set<LocalDate>,
    val months: Set<Month>,
) {
    fun matchesDate(date: LocalDate): Boolean =
        (dates.isEmpty() || date in dates) && (months.isEmpty() || date.month in months)
}

private val isoDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val monthsByName = Month.entries.associateBy { it.name.lowercase(Locale.ROOT) }

internal fun parseLibrarySearch(query: String, today: LocalDate): LibrarySearch {
    val dates = mutableSetOf<LocalDate>()
    val months = mutableSetOf<Month>()
    val text = query.trim().split(Regex("\\s+")).filterNot { token ->
        val keyword = token.lowercase(Locale.ROOT)
        when {
            keyword == "today" -> { dates.add(today); true }
            keyword == "yesterday" -> { dates.add(today.minusDays(1)); true }
            keyword in monthsByName -> { months.add(monthsByName.getValue(keyword)); true }
            isoDate.matches(token) -> try {
                dates.add(LocalDate.parse(token))
                true
            } catch (_: DateTimeParseException) {
                false
            }
            else -> false
        }
    }
    return LibrarySearch(text.joinToString(" "), dates, months)
}
