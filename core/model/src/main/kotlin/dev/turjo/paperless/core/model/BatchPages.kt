package dev.turjo.paperless.core.model

data class DuplexResult<T>(val pages: List<T>, val mismatch: Boolean)

fun <T> interleaveDuplex(fronts: List<T>, backsAsScanned: List<T>): DuplexResult<T> {
    if (fronts.size != backsAsScanned.size) return DuplexResult(fronts + backsAsScanned, true)
    return DuplexResult(buildList {
        for ((front, back) in fronts.zip(backsAsScanned.asReversed())) {
            add(front)
            add(back)
        }
    }, false)
}

data class PageBlankInfo(val id: String, val isBlank: Boolean)

data class PageGroup(val pageIds: List<String>, val droppedSeparatorIds: List<String>)

fun splitGroups(pages: List<PageBlankInfo>, duplex: Boolean): List<PageGroup> = buildList {
    val kept = mutableListOf<String>()
    val dropped = mutableListOf<String>()
    val separators = mutableListOf<String>()

    for (sheet in pages.chunked(if (duplex) 2 else 1)) {
        if (sheet.all { it.isBlank }) {
            if (kept.isNotEmpty()) separators.addAll(sheet.map { it.id })
            continue
        }
        if (separators.isNotEmpty()) {
            add(PageGroup(kept.toList(), dropped + separators))
            kept.clear()
            dropped.clear()
            separators.clear()
        }
        for (page in sheet) {
            if (page.isBlank) dropped.add(page.id) else kept.add(page.id)
        }
    }
    if (kept.isNotEmpty()) add(PageGroup(kept.toList(), dropped.toList()))
}
