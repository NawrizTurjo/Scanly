package dev.turjo.paperless.core.pdf.tools

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface PageOp {
    data class Delete(val pages: Set<Int>) : PageOp
    data class Extract(val pages: List<Int>) : PageOp
    data class Duplicate(val page: Int) : PageOp
    data class InsertBlank(val at: Int) : PageOp
    data class InsertFrom(val source: File, val sourcePages: List<Int>, val at: Int) : PageOp
    data class Rotate(val pages: Set<Int>, val degrees: Int) : PageOp
    data class Reorder(val order: List<Int>) : PageOp
}

object PageEdits {
    suspend fun apply(input: File, ops: List<PageOp>, out: File): ToolResult = withContext(Dispatchers.IO) {
        openPdf(input).use { document ->
            var pages = document.pages.toList()
            val navigation = PageNavigation(document, pages)
            for (op in ops) {
                ensureActive()
                fun select(indices: Collection<Int>): List<PDPage> {
                    requireTool(indices.all { it in pages.indices }, "Page index out of bounds")
                    return indices.map { pages[it] }
                }
                fun checkInsertion(at: Int) = requireTool(at in 0..pages.size, "Invalid insertion index")

                when (op) {
                    is PageOp.Delete -> {
                        select(op.pages)
                        pages = pages.filterIndexed { index, _ -> index !in op.pages }
                    }
                    is PageOp.Extract -> {
                        requireTool(op.pages.distinct().size == op.pages.size, "Extract contains duplicate pages")
                        pages = select(op.pages)
                    }
                    is PageOp.Duplicate -> {
                        val original = select(listOf(op.page)).single()
                        val copy = importPages(document, listOf(original), navigation, pages).single()
                        pages = pages.toMutableList().apply { add(op.page + 1, copy) }
                    }
                    is PageOp.InsertBlank -> {
                        checkInsertion(op.at)
                        val neighbour = pages.getOrNull(op.at) ?: pages.lastOrNull()
                        val blank = PDPage(neighbour?.mediaBox ?: document.getPage(0).mediaBox)
                        pages = pages.toMutableList().apply { add(op.at, blank) }
                    }
                    is PageOp.InsertFrom -> {
                        checkInsertion(op.at)
                        val inserted = openPdf(op.source).use { source ->
                            val sourcePages = source.pages.toList()
                            requireTool(op.sourcePages.isNotEmpty() && op.sourcePages.all { it in sourcePages.indices },
                                "Invalid source pages")
                            importPages(document, op.sourcePages.map { sourcePages[it] },
                                PageNavigation(source, sourcePages))
                        }
                        pages = pages.toMutableList().apply { addAll(op.at, inserted) }
                    }
                    is PageOp.Rotate -> {
                        requireTool(op.degrees % 90 == 0, "Rotation must be a multiple of 90 degrees")
                        for (page in select(op.pages)) {
                            ensureActive()
                            page.rotation = Math.floorMod(page.rotation + op.degrees % 360, 360)
                        }
                    }
                    is PageOp.Reorder -> {
                        requireTool(op.order.size == pages.size && op.order.toSet().size == pages.size,
                            "Reorder requires a permutation of all pages")
                        pages = select(op.order)
                    }
                }
            }
            requireTool(pages.isNotEmpty(), "A PDF needs at least one page")
            if (pages != document.pages.toList()) {
                navigation.retain(pages)
                replacePages(document, pages)
            }
            writeNewFile(out, document::save)
            ToolResult(out, pages.size)
        }
    }
}

private val inheritedPageKeys = listOf(COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE, COSName.RESOURCES)

private fun materializePage(page: PDPage) {
    val dictionary = page.cosObject
    for (key in inheritedPageKeys) {
        if (!dictionary.containsKey(key)) {
            val value = PDPageTree.getInheritableAttribute(dictionary, key)
            if (value != null) dictionary.setItem(key, value)
        }
    }
}

internal suspend fun replacePages(document: PDDocument, pages: List<PDPage>) {
    val root = document.pages.cosObject
    val kids = COSArray()
    for (page in pages) {
        coroutineContext.ensureActive()
        materializePage(page)
        page.cosObject.setItem(COSName.PARENT, root)
        kids.add(page)
    }
    root.setItem(COSName.KIDS, kids)
    root.setInt(COSName.COUNT, pages.size)
}

internal class PageNavigation(private val document: PDDocument, pages: List<PDPage>) {
    private val indexedPages = pages.map { it.cosObject }

    internal class Target(val owner: COSDictionary, val key: COSName, val destination: COSArray, val page: COSDictionary) {
        fun writeTo(owner: COSDictionary, page: COSDictionary) {
            val array = COSArray()
            array.addAll(destination)
            array.set(0, page)
            owner.setItem(key, array)
        }
    }

    fun target(dictionary: COSDictionary, directKey: COSName = COSName.DEST): Target? {
        val action = dictionary.getCOSDictionary(COSName.A)
        val (owner, key) = when {
            dictionary.containsKey(directKey) -> dictionary to directKey
            action?.getNameAsString(COSName.S) == "GoTo" -> action to COSName.D
            else -> return null
        }
        return resolve(owner, key)
    }

    private fun resolve(owner: COSDictionary, key: COSName): Target? {
        val parsed = PDDestination.create(owner.getDictionaryObject(key))
        val destination = when (parsed) {
            is PDNamedDestination -> document.documentCatalog.findNamedDestinationPage(parsed)
            is PDPageDestination -> parsed
            else -> null
        } ?: return null
        val array = destination.cosObject
        val page = destinationPage(array) ?: return null
        return Target(owner, key, array, page)
    }

    private fun destinationPage(array: COSArray): COSDictionary? =
        when (val first = array.getObject(0)) {
            is COSNumber -> indexedPages.getOrNull(first.intValue())
            is COSDictionary -> first
            else -> null
        }

    suspend fun retain(pages: List<PDPage>) {
        val kept = pages.mapTo(HashSet()) { it.cosObject }
        for (page in pages) {
            coroutineContext.ensureActive()
            val annotations = page.annotations
            val filtered = annotations.filter { annotation ->
                val target = (annotation as? PDAnnotationLink)?.let { target(it.cosObject) }
                if (target != null && target.page in kept) target.writeTo(target.owner, target.page)
                target == null || target.page in kept
            }
            if (filtered.size != annotations.size) page.annotations = filtered
        }
        val catalog = document.documentCatalog.cosObject
        val open = catalog.getCOSDictionary(COSName.OPEN_ACTION)
        val openTarget = if (open?.getNameAsString(COSName.S) == "GoTo") resolve(open, COSName.D)
            else if (catalog.getDictionaryObject(COSName.OPEN_ACTION) is COSArray) resolve(catalog, COSName.OPEN_ACTION)
            else null
        if (openTarget != null) {
            if (openTarget.page in kept) openTarget.writeTo(openTarget.owner, openTarget.page)
            else catalog.removeItem(COSName.OPEN_ACTION)
        }
        document.documentCatalog.documentOutline?.let { outline ->
            rebuildOutline(outline, outline, this, { page -> page.takeIf { it in kept } })
        }
        retainNamedDestinations(kept)
    }

    private fun retainNamedDestinations(kept: Set<COSDictionary>) {
        val catalog = document.documentCatalog
        catalog.names?.let { names ->
            val tree = names.dests ?: return@let
            val retained = sortedMapOf<String, PDPageDestination>()
            fun collect(node: PDNameTreeNode<PDPageDestination>) {
                node.names?.forEach { (name, destination) ->
                    val page = destinationPage(destination.cosObject)
                    if (page != null && page in kept) {
                        destination.cosObject.set(0, page)
                        retained[name] = destination
                    }
                }
                node.kids?.forEach(::collect)
            }
            collect(tree)
            names.dests = if (retained.isEmpty()) null else PDDestinationNameTreeNode().apply {
                setNames(retained)
            }
        }
        val legacy = catalog.cosObject.getCOSDictionary(COSName.DESTS) ?: return
        for (key in legacy.keySet().toList()) {
            val array = when (val value = legacy.getDictionaryObject(key)) {
                is COSArray -> value
                is COSDictionary -> value.getCOSArray(COSName.D)
                else -> null
            } ?: continue
            val page = destinationPage(array)
            if (page != null && page in kept) array.set(0, page) else legacy.removeItem(key)
        }
    }
}

internal fun rebuildOutline(
    source: PDOutlineNode,
    destination: PDOutlineNode,
    navigation: PageNavigation,
    mapPage: (COSDictionary) -> COSDictionary?,
    cloner: PDFCloneUtility? = null,
) {
    val children = source.children().toList()
    val open = source.isNodeOpen
    if (source === destination) {
        for (key in listOf(COSName.FIRST, COSName.LAST, COSName.COUNT)) destination.cosObject.removeItem(key)
    }
    for (child in children) {
        val dictionary = COSDictionary(child.cosObject)
        for (key in listOf(COSName.PARENT, COSName.PREV, COSName.NEXT, COSName.FIRST, COSName.LAST, COSName.COUNT)) {
            dictionary.removeItem(key)
        }
        val target = navigation.target(child.cosObject)
        val mapped = target?.let { mapPage(it.page) }
        if (target != null) {
            if (target.owner === child.cosObject || mapped == null) {
                dictionary.removeItem(if (target.owner === child.cosObject) COSName.DEST else COSName.A)
            } else {
                val action = COSDictionary(target.owner)
                action.removeItem(COSName.D)
                dictionary.setItem(COSName.A, action)
            }
        }
        val copy = PDOutlineItem(cloner?.cloneForNewDocument(dictionary) as? COSDictionary ?: dictionary)
        if (target != null && mapped != null) {
            val owner = if (target.owner === child.cosObject) copy.cosObject else copy.cosObject.getCOSDictionary(COSName.A)
            target.writeTo(owner, mapped)
        }
        rebuildOutline(child, copy, navigation, mapPage, cloner)
        if (target == null || mapped != null || copy.hasChildren()) destination.addLast(copy)
    }
    if (open) {
        destination.openNode()
    } else {
        destination.closeNode()
    }
}

private suspend fun importPages(
    destination: PDDocument,
    originals: List<PDPage>,
    navigation: PageNavigation,
    existing: List<PDPage> = emptyList(),
): List<PDPage> {
    val imports = originals.map { original ->
        coroutineContext.ensureActive()
        materializePage(original)
        val dictionary = COSDictionary(original.cosObject)
        dictionary.removeItem(COSName.PARENT)
        dictionary.removeItem(COSName.ANNOTS)
        val cloner = PDFCloneUtility(destination)
        val copy = PDPage(cloner.cloneForNewDocument(dictionary) as COSDictionary)
        val annotations = original.annotations
        val targets = annotations.map { annotation ->
            (annotation as? PDAnnotationLink)?.let { navigation.target(it.cosObject) }
        }
        // Keep the annotation graph intact (for example a note and its popup) while detaching page references.
        val saved = mutableListOf<Triple<COSDictionary, COSName, COSBase>>()
        fun detach(owner: COSDictionary, key: COSName) {
            owner.getItem(key)?.let { saved += Triple(owner, key, it) }
            owner.removeItem(key)
        }
        for ((index, annotation) in annotations.withIndex()) {
            detach(annotation.cosObject, COSName.P)
            targets[index]?.let { detach(it.owner, it.key) }
        }
        try {
            original.cosObject.getItem(COSName.ANNOTS)?.let {
                copy.cosObject.setItem(COSName.ANNOTS, cloner.cloneForNewDocument(it))
            }
        } finally {
            saved.asReversed().forEach { (owner, key, value) -> owner.setItem(key, value) }
        }
        copy to targets
    }
    val mapping = existing.associate { it.cosObject to it.cosObject }.toMutableMap()
    val imported = originals.zip(imports)
    for ((original, pair) in imported.asReversed()) mapping[original.cosObject] = pair.first.cosObject
    for ((original, pair) in imported) {
        coroutineContext.ensureActive()
        val (copy, targets) = pair
        val annotations = copy.annotations
        if (annotations.isEmpty()) continue
        copy.annotations = annotations.filterIndexed { index, annotation ->
            annotation.page = copy
            val target = targets[index] ?: return@filterIndexed true
            val page = if (target.page === original.cosObject) copy.cosObject else mapping[target.page]
            if (page == null) return@filterIndexed false
            val owner = if (target.key == COSName.DEST) annotation.cosObject else annotation.cosObject.getCOSDictionary(COSName.A)
            target.writeTo(owner, page)
            true
        }
    }
    return imports.map { it.first }
}
