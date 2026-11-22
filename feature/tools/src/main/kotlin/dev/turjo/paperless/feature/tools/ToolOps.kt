package dev.turjo.paperless.feature.tools

import android.content.Context
import android.net.Uri
import dev.turjo.paperless.core.pdf.PageSizeOption
import dev.turjo.paperless.core.pdf.PdfBox
import dev.turjo.paperless.core.pdf.QualityPreset
import dev.turjo.paperless.core.pdf.tools.*
import java.io.File

internal enum class Tool(val title: Int) {
    MERGE(R.string.tool_merge), SPLIT(R.string.tool_split), PAGES(R.string.tool_page_edits),
    COMPRESS(R.string.tool_compress), WIDTH(R.string.tool_same_width), PRINT(R.string.tool_print_layout),
    NUMBERS(R.string.tool_page_numbers), PASSWORD(R.string.tool_password), METADATA(R.string.tool_metadata),
    SANITIZE(R.string.tool_sanitize), GRAYSCALE(R.string.tool_grayscale), FROM_IMAGES(R.string.tool_images_to_pdf),
    TO_IMAGES(R.string.tool_pdf_to_images), BATCH(R.string.tool_batch)
}

internal data class Input(val file: File, val name: String, val photoUri: Uri? = null)
internal data class Output(val file: File, val note: String? = null, val bytes: Long = file.length())
internal typealias Options = Map<String, String>

private val pageRangePattern = Regex("""\s*(\d+)\s*(?:-\s*(\d+)\s*)?""")

internal fun parseRanges(text: String, pageCount: Int = Int.MAX_VALUE): List<IntRange> =
    text.split(',').map { part ->
        val match = pageRangePattern.matchEntire(part)
        val start = match?.groups?.get(1)?.value?.toIntOrNull()
        val last = match?.groups?.get(2)?.value
        val end = if (last == null) start else last.toIntOrNull()
        require(start != null && end != null && start in 1..pageCount && end in start..pageCount) {
            "Enter valid page ranges such as 1-3, 5"
        }
        (start - 1)..(end - 1)
    }

internal fun Options.positiveInt(key: String, default: Int): Int =
    getOrDefault(key, default.toString()).toIntOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("Enter a positive whole number for $key")

private fun Options.measure(key: String, default: Float, allowZero: Boolean = false): Float =
    getOrDefault(key, default.toString()).toFloatOrNull()
        ?.takeIf { it.isFinite() && (it > 0f || allowZero && it == 0f) }
        ?: throw IllegalArgumentException("Enter a valid value for $key")

internal fun Options.layout() = NUpLayout.valueOf(getOrDefault("Layout", NUpLayout.CUT_EACH_SHEET.name)).let {
    if (it == NUpLayout.PLAIN_2) NUpLayout.valueOf("PLAIN_" + getOrDefault("Pages per side", "2")) else it
}
internal fun Options.flip() = Flip.valueOf(getOrDefault("Flip", Flip.LONG.name))

internal data class ToolRequest(
    val tool: Tool,
    val inputs: List<Input>,
    val options: Options = emptyMap(),
    val pages: List<PageOp> = emptyList(),
)

internal fun interface ToolOps {
    suspend fun run(request: ToolRequest, directory: File): List<Output>
}

internal class PdfToolOps(
    private val context: Context,
    private val onEnhancing: (Int, Int) -> Unit = { _, _ -> },
) : ToolOps {
    override suspend fun run(request: ToolRequest, directory: File): List<Output> {
        PdfBox.ensureInit(context)
        val (tool, inputs, options, pages) = request
        val source = inputs.first()
        val input = source.file
        fun out(suffix: String, item: Input = source, parent: File = directory): File {
            val stem = item.name.substringBeforeLast('.').take(120).ifBlank { "Document" }
            return File(parent, "$stem-$suffix.pdf")
        }
        fun ToolResult.output() = Output(output, note)
        val quality = QualityPreset.valueOf(options.getOrDefault("Quality", QualityPreset.entries.first().name))
        val password = options["Password"].orEmpty()
        return when (tool) {
            Tool.MERGE -> listOf(PdfMerge.merge(inputs.map { it.file }, File(directory, "Merged.pdf")).output())
            Tool.SPLIT -> {
                val results = if (options["Split by"] == "Every N pages") {
                    PdfSplit.everyN(input, options.positiveInt("Pages per file", 1), directory)
                } else {
                    PdfSplit.byRanges(input, parseRanges(options["Pages"].orEmpty()), directory)
                }
                results.map { it.output() }
            }
            Tool.PAGES -> listOf(PageEdits.apply(input, pages, out("edited")).output())
            Tool.COMPRESS -> listOf(PdfCompress.compress(input, quality, out("compressed")).output())
            Tool.WIDTH -> {
                val target = when (options["Target width"]) {
                    "Widest" -> WidthTarget.Max
                    "Narrowest" -> WidthTarget.Min
                    "A4" -> WidthTarget.A4
                    "Letter" -> WidthTarget.Letter
                    "Custom" -> WidthTarget.Custom(options.measure("Width in pt", 595f))
                    else -> WidthTarget.Common
                }
                inputs.mapIndexed { index, item ->
                    val parent = if (index == 0) directory else File(directory, index.toString()).apply {
                        check(mkdirs())
                    }
                    PageWidthNormalizer.normalize(item.file, target, out("fixed", item, parent)).output()
                }
            }
            Tool.PRINT -> {
                val layout = options.layout()
                val flip = options.flip()
                val result = if (options["Test sheet"] == "true") {
                    NUp.testSheet(layout, flip, out("print-test"))
                } else {
                    NUp.layout(input, layout, layout.perSide, flip, NUpOptions(
                        options.measure("Margin in pt", 18f, allowZero = true),
                        options.measure("Gap in pt", 12f, allowZero = true),
                        options["Cut line"].toBoolean(),
                        options["Scale to fit"] != "false",
                        pageLabels = options["Page labels for cutting"] != "false",
                    ), out("print"))
                }
                listOf(result.output())
            }
            Tool.NUMBERS -> listOf(PageNumbers.stamp(
                input,
                NumberStyle.valueOf(options.getOrDefault("Style", NumberStyle.NUMBER.name)),
                NumberPosition.valueOf(options.getOrDefault("Position", NumberPosition.BOTTOM_CENTER.name)),
                options.measure("Font size", 12f),
                options.positiveInt("Start at", 1),
                options["Page range"]?.takeIf(String::isNotBlank)?.let {
                    parseRanges(it).singleOrNull() ?: throw IllegalArgumentException("Enter one page range")
                },
                out("numbered"),
            ).output())
            Tool.PASSWORD -> listOf(
                if (options["Action"] == "Remove") PdfPassword.decrypt(input, password, out("unlocked")).output()
                else PdfPassword.encrypt(input, password, out("protected")).output(),
            )
            Tool.METADATA -> listOf(PdfMetadata.write(input, DocInfo(
                options["Title"], options["Author"], options["Subject"], options["Keywords"],
            ), options["Clear all"].toBoolean(), out("metadata")).output())
            Tool.SANITIZE -> listOf(PdfSanitize.sanitize(input, options["Remove XMP"] != "false", out("sanitized")).output())
            Tool.GRAYSCALE -> listOf(PdfGrayscale.convert(input, out("grayscale")).output())
            Tool.FROM_IMAGES -> listOf(imagesToPdf(
                context, inputs, options.imageEnhancement(),
                PageSizeOption.valueOf(options.getOrDefault("Page size", "AUTO")),
                quality, File(directory, "Images.pdf"), onEnhancing,
            ).output())
            Tool.TO_IMAGES -> PdfToImages.convert(
                input, options.positiveInt("DPI", 150),
                ImageFormat.valueOf(options.getOrDefault("Format", "JPEG")), directory,
            ).map { Output(it) }
            Tool.BATCH -> error("Choose a batch operation")
        }
    }
}

internal fun Exception.toolMessage(context: Context, removingPassword: Boolean = false): String = when (this) {
    is ToolError.Encrypted -> context.getString(
        if (removingPassword) R.string.tools_wrong_password else R.string.tools_password_protected,
    )
    is ToolError.Corrupt -> context.getString(R.string.tools_corrupt_pdf)
    is ToolError.Invalid -> message ?: context.getString(R.string.tools_check_options)
    is IllegalArgumentException -> context.getString(R.string.tools_check_options)
    is SecurityException -> context.getString(R.string.tools_access_error)
    else -> context.getString(R.string.tools_operation_error)
}
