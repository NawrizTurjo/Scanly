package dev.turjo.paperless.core.data

import android.graphics.PointF
import androidx.room.withTransaction
import dev.turjo.paperless.core.model.Annotation
import dev.turjo.paperless.core.model.AnnotationType
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.PdfKey
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AnnotationRepository @Inject constructor(private val database: PaperlessDatabase) {
    private val dao get() = database.annotationDao()

    fun observe(pdfKey: PdfKey) = dao.observe(pdfKey.value)
        .map { rows -> rows.map(AnnotationPayload::decode) }
        .flowOn(Dispatchers.Default)

    suspend fun saveBatch(pdfKey: PdfKey, annotations: List<Annotation>) = withContext(Dispatchers.Default) {
        require(annotations.all { it.pdfKey == pdfKey })
        val rows = annotations.mapIndexed { index, annotation ->
            AnnotationPayload.encode(annotation, index.toLong())
        }
        database.withTransaction {
            dao.deleteAll(pdfKey.value)
            dao.insert(rows)
        }
    }

    suspend fun delete(id: String) = dao.delete(listOf(id))

    suspend fun clear(pdfKey: PdfKey) = dao.deleteAll(pdfKey.value)
}

internal object AnnotationPayload {
    fun encode(annotation: Annotation, createdAt: Long): AnnotationEntity = with(annotation) {
        val json = JSONObject()
            .put("points", JSONArray(points.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) }))
            .put("rects", JSONArray(rects.map {
                JSONArray(listOf(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble()))
            }))
            .put("note", note ?: JSONObject.NULL)
        AnnotationEntity(id, pdfKey.value, page, type.name, json.toString(), color, width, createdAt)
    }

    fun decode(row: AnnotationEntity): Annotation {
        val json = JSONObject(row.payloadJson)
        val points = json.optJSONArray("points").tuples { PointF(number(0), number(1)) }
        val rects = json.optJSONArray("rects").tuples { PageRect(number(0), number(1), number(2), number(3)) }
        return Annotation(
            row.id, PdfKey(row.pdfKey), row.pageIndex, AnnotationType.valueOf(row.type), row.color, row.width,
            points, rects, if (json.isNull("note")) null else json.getString("note"),
        )
    }

    private fun JSONArray.number(index: Int) = getDouble(index).toFloat()

    private fun <T> JSONArray?.tuples(read: JSONArray.() -> T): List<T> =
        if (this == null) emptyList() else List(length()) { getJSONArray(it).read() }
}
