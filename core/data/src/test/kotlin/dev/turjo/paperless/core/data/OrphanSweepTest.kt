package dev.turjo.paperless.core.data

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class OrphanSweepTest {
    @Test
    fun selectsUnreferencedPagesStaleFilesAndDeletedDocumentPdfs() {
        val cutoff = 10_000L
        val now = cutoff + TimeUnit.HOURS.toMillis(24)
        val old = listOf("tmp/scan.jpg", "tools/in/job/input.pdf", "exports/output.pdf").map(::File)
        val deletedPdf = File("pdf/deleted.pdf")
        val listing = SweepListing(
            pageIds = listOf("saved", "draft", "orphan"),
            transientFiles = old.map { it to cutoff - 1 } + listOf(
                File("tmp/at-cutoff") to cutoff,
                File("tmp/fresh") to cutoff + 1,
                File("tmp/unknown") to 0L,
            ),
            cachedPdfs = listOf(File("pdf/live.pdf"), deletedPdf, File("pdf/scratch.tmp")),
        )

        assertEquals(
            SweepSelection(listOf("orphan"), old + deletedPdf),
            selectOrphans(listing, setOf("saved", "draft"), setOf("live"), now),
        )
    }
}
