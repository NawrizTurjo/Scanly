package dev.turjo.paperless.core.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.turjo.paperless.core.model.PdfKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentSearchTest {
    @Test
    fun matchesTruthPagesAndSkipsUnchangedFilesAndImageOnlyPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Room.inMemoryDatabaseBuilder(context, PaperlessDatabase::class.java).build().withDatabase { db ->
            val indexer = TextIndexer(db, context)
            val search = ContentSearchRepository(db)
            val truth = JSONObject(fixture("truth.json").readText()).getJSONObject("files")
            val names = listOf("multipage-15.pdf", "text-vector.pdf", "image-only-scan.pdf")
            for ((index, name) in names.withIndex()) {
                val file = fixture(name)
                val uri = Uri.fromFile(file)
                val key = PdfKey("fixture-$index")
                db.readerDao().insertExternal(ExternalPdfEntity(key.value, uri.toString(), name, persistedPermission = false))
                assertTrue(indexer.index(key, uri, file.lastModified()))
                assertFalse(indexer.index(key, uri, file.lastModified()))
                val expected = truth.getJSONObject(name)
                val indexed = requireNotNull(db.pageTextDao().indexed(key.value))
                assertEquals(expected.getInt("page_count"), indexed.pageCount)
                assertEquals(expected.getBoolean("has_text"), indexed.hasText)
            }
            for (name in names.take(2)) {
                val words = truth.getJSONObject(name).getJSONObject("search")
                for (word in words.keys()) {
                    val hits = search.search(word).filter { it.title == name }
                    val expected = words.getJSONObject(word)
                    val pages = expected.getJSONArray("pages")
                    assertEquals("$name: $word", pages.length(), hits.size)
                    assertEquals(List(pages.length()) { pages.getInt(it) - 1 }, hits.map { it.pageIndex })
                    assertTrue(hits.all { '[' in it.snippet && ']' in it.snippet })
                }
            }
            assertEquals(18, search.search("momen").size)
            assertTrue(search.search("fixture").isEmpty())
            assertEquals(IndexCounts(2, 18), search.observeCounts().first())
        }
    }
}

class ContentMatchTest {
    @Test
    fun quotesOperatorsAndKeepsPrefixOnLastToken() {
        assertEquals("\"alpha\" \"OR\" \"NEAR\" \"beta*\"", contentMatch("alpha OR NEAR(beta*)"))
        assertEquals("\"text\" \"quoted\" \"Café*\"", contentMatch("text:\"quoted\" - Café"))
        assertNull(contentMatch("  *:\"-()"))
    }
}

