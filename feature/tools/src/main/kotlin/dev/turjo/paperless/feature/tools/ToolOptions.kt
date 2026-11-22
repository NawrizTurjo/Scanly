package dev.turjo.paperless.feature.tools

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.pdf.PageSizeOption
import dev.turjo.paperless.core.pdf.QualityPreset
import dev.turjo.paperless.core.pdf.tools.*

@Composable
internal fun ToolOptions(tool: Tool, values: MutableMap<String, String>, count: Int, batch: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (tool) {
            Tool.SPLIT -> {
                Pick("Split by", values, listOf("Ranges", "Every N pages", "Pick pages"))
                when (values["Split by"]) {
                    "Every N pages" -> Field("Pages per file", values, "1")
                    "Pick pages" -> Text("Select the pages to save as separate PDFs.")
                    else -> Field("Pages", values, label = "Page ranges (1-3, 5, 8-10)")
                }
            }
            Tool.COMPRESS -> PickEnum("Quality", values, QualityPreset.entries)
            Tool.WIDTH -> {
                Pick("Target width", values, listOf("Most common", "Widest", "Narrowest", "A4", "Letter", "Custom"))
                if (values["Target width"] == "Custom") Field("Width in pt", values, "595")
            }
            Tool.PRINT -> PrintOptions(values, count)
            Tool.NUMBERS -> {
                PickEnum("Style", values, NumberStyle.entries) {
                    when (it) {
                        NumberStyle.NUMBER -> "n"
                        NumberStyle.FRACTION -> "n / N"
                        NumberStyle.PAGE_NUMBER -> "Page n"
                    }
                }
                PickEnum("Position", values, NumberPosition.entries, default = NumberPosition.BOTTOM_CENTER)
                Field("Font size", values, "12")
                Field("Start at", values, "1")
                Field("Page range", values, label = "Page range (blank for all pages)")
            }
            Tool.PASSWORD -> {
                if (batch) Pick("Action", values, listOf("Add", "Remove"))
                Field("Password", values, secret = true)
            }
            Tool.METADATA -> {
                Toggle("Clear all", values, false)
                if (values["Clear all"] != "true") {
                    listOf("Title", "Author", "Subject", "Keywords").forEach { Field(it, values) }
                }
            }
            Tool.SANITIZE -> Toggle("Remove XMP", values, true)
            Tool.GRAYSCALE -> Text("Text and vector colours stay as they are")
            Tool.FROM_IMAGES -> {
                PickEnum("Page size", values, PageSizeOption.entries) { if (it == PageSizeOption.A4) "A4" else enumLabel(it) }
                PickEnum("Quality", values, QualityPreset.entries)
            }
            Tool.TO_IMAGES -> {
                Pick("DPI", values, listOf("72", "150", "300"), default = "150")
                PickEnum("Format", values, ImageFormat.entries) { it.name }
            }
            else -> Unit
        }
    }
}

@Composable
private fun PrintOptions(values: MutableMap<String, String>, count: Int) {
    PickEnum("Layout", values, listOf(NUpLayout.CUT_EACH_SHEET, NUpLayout.CUT_WHOLE_STACK, NUpLayout.BOOKLET, NUpLayout.PLAIN_2)) {
        when (it) {
            NUpLayout.CUT_EACH_SHEET -> "Duplex: cut each sheet"
            NUpLayout.CUT_WHOLE_STACK -> "Duplex: cut whole stack"
            NUpLayout.BOOKLET -> "Booklet"
            else -> "Plain N-up"
        }
    }
    if (values["Layout"] == NUpLayout.PLAIN_2.name) {
        Pick("Pages per side", values, listOf("2", "4", "6", "9"))
    }
    PickEnum("Flip", values, Flip.entries) { if (it == Flip.LONG) "Long edge" else "Short edge" }
    Field("Margin in pt", values, "18")
    Field("Gap in pt", values, "12")
    Toggle("Cut line", values, false)
    Toggle("Scale to fit", values, true)
    val layout = values.layout()
    val flip = values.flip()
    val sheet = remember(count, layout, flip) { NUp.impose(count, layout, flip).firstOrNull() }
    sheet?.let {
        val margin = values["Margin in pt"]?.toFloatOrNull() ?: 18f
        val gap = values["Gap in pt"]?.toFloatOrNull() ?: 12f
        val cutLine = values["Cut line"].toBoolean()
        Text("Sheet 1", style = MaterialTheme.typography.titleMedium)
        SheetSide("Front", it.front, margin, gap, cutLine)
        if (it.back.isNotEmpty()) SheetSide("Back", it.back, margin, gap, cutLine)
    }
}

@Composable
private fun SheetSide(label: String, pages: List<Int?>, margin: Float, gap: Float, cutLine: Boolean) {
    val columns = if (pages.size == 9) 3 else 2
    val points = if (pages.size == 2) 842f else 595f
    Text(label)
    BoxWithConstraints(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline)) {
        val inset = maxWidth * ((margin / points).takeIf(Float::isFinite) ?: 0f).coerceIn(0.01f, 0.2f)
        val spacing = maxWidth * ((gap / points).takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 0.12f)
        Column(Modifier.padding(inset), verticalArrangement = Arrangement.spacedBy(spacing)) {
            pages.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { page ->
                        Box(Modifier.weight(1f).height(40.dp).border(1.dp, MaterialTheme.colorScheme.outline),
                            contentAlignment = Alignment.Center) {
                            Text(page?.plus(1)?.toString() ?: "Blank")
                        }
                    }
                }
            }
        }
        if (cutLine && columns == 2) {
            VerticalDivider(Modifier.align(Alignment.Center).height((pages.size / columns * 40).dp))
        }
    }
}

@Composable
private fun Field(
    key: String, values: MutableMap<String, String>, default: String = "", label: String = key, secret: Boolean = false,
) {
    OutlinedTextField(values[key] ?: default, { values[key] = it }, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}

@Composable
private fun Toggle(key: String, values: MutableMap<String, String>, default: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(values[key]?.toBoolean() ?: default, { values[key] = it.toString() })
        Text(key)
    }
}

@Composable
private fun Pick(
    key: String, values: MutableMap<String, String>, choices: List<String>, default: String = choices.first(),
) {
    Choice(key, values[key] ?: default, choices, { it }) { values[key] = it }
}

@Composable
private fun <T : Enum<T>> PickEnum(
    key: String,
    values: MutableMap<String, String>,
    choices: List<T>,
    default: T = choices.first(),
    label: (T) -> String = { enumLabel(it) },
) {
    Choice(key, choices.firstOrNull { it.name == values[key] } ?: default, choices, label) { values[key] = it.name }
}

@Composable
internal fun <T> Choice(label: String, selected: T, choices: List<T>, title: (T) -> String, change: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(title(selected)) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(title(choice)) }, onClick = {
                        change(choice)
                        expanded = false
                    })
                }
            }
        }
    }
}

private fun enumLabel(value: Enum<*>) = value.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

