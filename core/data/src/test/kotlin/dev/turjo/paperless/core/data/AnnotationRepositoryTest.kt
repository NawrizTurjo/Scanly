package dev.turjo.paperless.core.data

import android.content.Context
import android.graphics.PointF
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.turjo.paperless.core.model.Annotation
import dev.turjo.paperless.core.model.AnnotationType
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.PdfKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnnotationRepositoryTest {
    private val key = PdfKey("doc:one")

    @Test
    fun roundTripsInkQuadsAndUnicodeNotes() {
        val ink = Annotation(
            "ink", key, 3, AnnotationType.INK, 0xFF215DC8.toInt(), 2.75f,
            points = listOf(PointF(1.125f, 2.25f), PointF(94.75f, 107.5f)),
        )
        val highlight = ink.copy(
            id = "highlight", type = AnnotationType.HIGHLIGHT, points = emptyList(),
            rects = listOf(PageRect(24.5f, 40f, 100f, 51f), PageRect(24f, 52f, 88f, 63f)),
        )
        val note = ink.copy(id = "note", type = AnnotationType.NOTE, note = "Café \"quote\"\nবাংলা")
        for (mark in listOf(ink, highlight, note)) {
            assertEquals(mark, AnnotationPayload.decode(AnnotationPayload.encode(mark, 1)))
        }
    }

    @Test
    fun batchReplacementDeleteAndClearOnlyAffectTheRequestedLayers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Room.inMemoryDatabaseBuilder(context, PaperlessDatabase::class.java).build().withDatabase { database ->
            val repository = AnnotationRepository(database)
            val mark = Annotation("a", key, 0, AnnotationType.INK, 0xFF252525.toInt(), 3f)
            val second = mark.copy(id = "b")
            val other = mark.copy(id = "c", pdfKey = PdfKey("doc:two"))
            repository.saveBatch(key, listOf(mark, second))
            repository.saveBatch(other.pdfKey, listOf(other))
            assertEquals(listOf(mark, second), repository.observe(key).first())
            repository.saveBatch(key, listOf(second))
            assertEquals(listOf(second), repository.observe(key).first())
            repository.delete(second.id)
            assertEquals(emptyList<Annotation>(), repository.observe(key).first())
            assertEquals(listOf(other), repository.observe(other.pdfKey).first())
            repository.clear(other.pdfKey)
            assertEquals(emptyList<Annotation>(), repository.observe(other.pdfKey).first())
        }
    }
}
