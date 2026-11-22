package dev.turjo.paperless.feature.export

import android.content.Context
import dev.turjo.paperless.core.pdf.PdfBox
import dev.turjo.paperless.core.pdf.tools.*
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class PdfExtras(
    val grayscale: Boolean = false,
    val pageNumbers: PageNumberExtra? = null,
    val layout: LayoutExtra? = null,
    val password: String? = null,
)

internal data class PageNumberExtra(
    val style: NumberStyle = NumberStyle.FRACTION,
    val position: NumberPosition = NumberPosition.BOTTOM_CENTER,
    val fontSize: Float = 12f,
    val startAt: Int = 1,
)

internal enum class LayoutExtra(val layout: NUpLayout) {
    TWO(NUpLayout.PLAIN_2),
    FOUR(NUpLayout.PLAIN_4),
    BOOKLET(NUpLayout.BOOKLET),
    CUT_AND_STACK(NUpLayout.CUT_WHOLE_STACK),
}

internal interface ExtraOps {
    suspend fun grayscale(input: File, output: File)
    suspend fun number(input: File, output: File, options: PageNumberExtra)
    suspend fun layout(input: File, output: File, layout: NUpLayout, flip: Flip, options: NUpOptions)
    suspend fun encrypt(input: File, output: File, password: String)
}

internal class PdfExtraOps(private val context: Context) : ExtraOps {
    override suspend fun grayscale(input: File, output: File) {
        PdfBox.ensureInit(context)
        PdfGrayscale.convert(input, output)
    }

    override suspend fun number(input: File, output: File, options: PageNumberExtra) {
        PdfBox.ensureInit(context)
        PageNumbers.stamp(input, options.style, options.position, options.fontSize, options.startAt, null, output)
    }

    override suspend fun layout(input: File, output: File, layout: NUpLayout, flip: Flip, options: NUpOptions) {
        PdfBox.ensureInit(context)
        NUp.layout(input, layout, layout.perSide, flip, options, output)
    }

    override suspend fun encrypt(input: File, output: File, password: String) {
        PdfBox.ensureInit(context)
        PdfPassword.encrypt(input, password, output)
    }
}

internal suspend fun applyExtras(input: File, extras: PdfExtras, workDir: File, ops: ExtraOps): File {
    var current = input
    val temps = mutableListOf<File>()
    suspend fun step(apply: suspend (File, File) -> Unit) {
        coroutineContext.ensureActive()
        val output = File.createTempFile("extra-", ".pdf", workDir)
        temps.add(output)
        apply(current, output)
        if (current != input) current.delete()
        current = output
    }

    var completed = false
    try {
        if (extras.grayscale) step(ops::grayscale)
        extras.pageNumbers?.let { numbers -> step { from, to -> ops.number(from, to, numbers) } }
        extras.layout?.let { layout ->
            val options = NUpOptions(
                cutLine = layout == LayoutExtra.CUT_AND_STACK,
                pageLabels = extras.pageNumbers == null,
            )
            step { from, to -> ops.layout(from, to, layout.layout, Flip.LONG, options) }
        }
        extras.password?.let { password -> step { from, to -> ops.encrypt(from, to, password) } }
        coroutineContext.ensureActive()
        completed = true
        return current
    } finally {
        temps.filter { !completed || it != current }.forEach { it.delete() }
    }
}
