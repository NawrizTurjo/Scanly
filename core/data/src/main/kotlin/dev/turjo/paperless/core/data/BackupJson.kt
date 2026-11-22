package dev.turjo.paperless.core.data

import dev.turjo.paperless.core.model.InkRemoval
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.Quad
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.objects() = List(length()) { getJSONObject(it) }

private fun JSONObject.optionalString(key: String) = if (isNull(key)) null else getString(key)

internal fun FolderEntity.backupJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("parentId", parentId)
    put("createdAt", createdAt)
    put("isPrivate", isPrivate)
}

internal fun JSONObject.readFolder() = FolderEntity(
    id = getString("id"),
    name = getString("name"),
    parentId = optionalString("parentId"),
    createdAt = getLong("createdAt"),
    isPrivate = getBoolean("isPrivate"),
)

internal fun DocumentEntity.backupJson() = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("folderId", folderId)
    put("isFavorite", isFavorite)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    put("coverPageId", coverPageId)
    put("lastOpenedAt", lastOpenedAt)
    put("lastPage", lastPage)
    put("pageCount", pageCount)
}

internal fun JSONObject.readDocument() = DocumentEntity(
    id = getString("id"),
    title = getString("title"),
    folderId = optionalString("folderId"),
    isFavorite = getBoolean("isFavorite"),
    createdAt = getLong("createdAt"),
    updatedAt = getLong("updatedAt"),
    coverPageId = optionalString("coverPageId"),
    lastOpenedAt = getLong("lastOpenedAt"),
    lastPage = getInt("lastPage"),
    pageCount = getInt("pageCount"),
    isDraft = false,
    deletedAt = null,
)

internal fun PageEntity.backupJson() = JSONObject().apply {
    put("id", id)
    put("index", index)
    put("mode", mode.name)
    put("originalWidth", originalWidth)
    put("originalHeight", originalHeight)
    put("quad", quad?.toArray()?.map(Float::toDouble)?.let(::JSONArray))
    put("rotation", rotation)
    put("filter", filter.name)
    put("brightness", brightness.toDouble())
    put("contrast", contrast.toDouble())
    put("shadowFix", shadowFix)
    put("glareFix", glareFix)
    put("processedHash", processedHash)
    put("glareWarning", glareWarning)
    put("createdAt", createdAt)
    put("deskew", deskew)
    put("inkRemoval", inkRemoval?.name)
}

internal fun JSONObject.readPage(documentId: String) = PageEntity(
    id = getString("id"),
    index = getInt("index"),
    mode = PageMode.valueOf(getString("mode")),
    originalWidth = getInt("originalWidth"),
    originalHeight = getInt("originalHeight"),
    quad = optJSONArray("quad")?.let { points -> Quad.fromArray(FloatArray(8) { points.getDouble(it).toFloat() }) },
    rotation = getInt("rotation"),
    filter = PageFilter.valueOf(getString("filter")),
    brightness = getDouble("brightness").toFloat(),
    contrast = getDouble("contrast").toFloat(),
    shadowFix = getBoolean("shadowFix"),
    glareFix = getBoolean("glareFix"),
    processedHash = optionalString("processedHash"),
    glareWarning = getBoolean("glareWarning"),
    createdAt = getLong("createdAt"),
    deskew = getBoolean("deskew"),
    inkRemoval = optionalString("inkRemoval")?.let(InkRemoval::valueOf),
    documentId = documentId,
    originalPath = "",
    processedPath = null,
)

internal fun AnnotationEntity.backupJson() = JSONObject().apply {
    put("id", id)
    put("pdfKey", pdfKey)
    put("pageIndex", pageIndex)
    put("type", type)
    put("payloadJson", payloadJson)
    put("color", color)
    put("width", width.toDouble())
    put("createdAt", createdAt)
}

internal fun JSONObject.readAnnotation() = AnnotationEntity(
    id = getString("id"),
    pdfKey = getString("pdfKey"),
    pageIndex = getInt("pageIndex"),
    type = getString("type"),
    payloadJson = getString("payloadJson"),
    color = getInt("color"),
    width = getDouble("width").toFloat(),
    createdAt = getLong("createdAt"),
)

