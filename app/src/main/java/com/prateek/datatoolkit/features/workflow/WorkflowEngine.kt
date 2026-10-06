package com.prateek.datatoolkit.features.workflow

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.prateek.datatoolkit.core.export.DocxWriter
import com.prateek.datatoolkit.core.image.SafeBitmap
import com.prateek.datatoolkit.core.io.FileGuards
import com.prateek.datatoolkit.core.io.displayNameOf
import com.prateek.datatoolkit.features.datacleaning.CleaningOptions
import com.prateek.datatoolkit.features.datacleaning.DataCleaner
import com.prateek.datatoolkit.features.email.EmailExtractor
import com.prateek.datatoolkit.features.excel.ExcelCsvHelper
import com.prateek.datatoolkit.features.ocr.OcrBook
import com.prateek.datatoolkit.features.ocr.OcrHelper
import com.prateek.datatoolkit.features.ocr.OcrLayout
import com.prateek.datatoolkit.features.pdf.PdfHelper
import com.prateek.datatoolkit.features.scraping.ItemExtractor
import com.prateek.datatoolkit.features.scraping.Scraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Executes one [WorkflowStep] against the data produced by the step before it, returning
 * both the data to hand to the *next* step and a short human-readable preview. Every branch
 * delegates to the same engine each standalone tool screen already uses - OcrHelper,
 * PdfHelper, ExcelCsvHelper, Scraper, EmailExtractor, DataCleaner, DocxWriter - so a workflow
 * behaves exactly like running those tools by hand, just chained together automatically.
 */
object WorkflowEngine {

    data class StepResult(
        val data: WorkflowData,
        val preview: String,
        val exportedFile: File? = null
    )

    suspend fun runStep(context: Context, step: WorkflowStep, input: WorkflowData): StepResult {
        val kind = step.kind
        if (input.kind !in kind.accepts) {
            val needs = kind.accepts.joinToString(" or ") { it.label }
            throw IllegalStateException("This step needs $needs, but the step before it produced ${input.kind.label}")
        }
        return when (kind) {
            StepKind.SCAN_IMAGES -> runOcr(context, step, tableOnly = false)
            StepKind.SCAN_TABLE -> runOcr(context, step, tableOnly = true)
            StepKind.LOAD_PDF -> runLoadPdf(context, step)
            StepKind.LOAD_SHEET -> runLoadSheet(context, step)
            StepKind.SCRAPE_URL -> runScrapeUrl(step)
            StepKind.PASTE_TEXT -> runPasteText(step)
            StepKind.CLEAN_TABLE -> runCleanTable(input)
            StepKind.EXTRACT_EMAILS -> runExtractEmails(input)
            StepKind.TEXT_TO_TABLE -> runTextToTable(step, input)
            StepKind.TABLE_TO_TEXT -> runTableToText(step, input)
            StepKind.CLEAN_TEXT -> runCleanText(input)
            StepKind.FIND_REPLACE -> runFindReplace(step, input)
            StepKind.FILTER_ROWS -> runFilterRows(step, input)
            StepKind.SORT_TABLE -> runSortTable(step, input)
            StepKind.KEEP_COLUMNS -> runKeepColumns(step, input)
            StepKind.EXPORT_CSV -> runExport(context, step, input, ExportFormat.CSV)
            StepKind.EXPORT_XLSX -> runExport(context, step, input, ExportFormat.XLSX)
            StepKind.EXPORT_TXT -> runExport(context, step, input, ExportFormat.TXT)
            StepKind.EXPORT_PDF -> runExport(context, step, input, ExportFormat.PDF)
            StepKind.EXPORT_DOCX -> runExport(context, step, input, ExportFormat.DOCX)
        }
    }

    // --- Sources -------------------------------------------------------------------------------

    /** OCR modes for the "Scan Photos" step: "layout" (default - tables become tab-separated
     *  rows, everything else is normal text), "book" (headings + paragraphs) or "plain". */
    private suspend fun runOcr(context: Context, step: WorkflowStep, tableOnly: Boolean): StepResult {
        if (step.pickedUris.isEmpty()) throw IllegalStateException("No photos were picked for this step")
        val bitmaps = withContext(Dispatchers.IO) {
            step.pickedUris.mapNotNull { uri ->
                SafeBitmap.decode(context, uri)
            }
        }
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read the selected photo(s)")
        val count = bitmaps.size
        val results = try { OcrHelper.recognizeBatch(bitmaps) } finally { bitmaps.forEach { if (!it.isRecycled) it.recycle() } }

        if (tableOnly) {
            val rows = OcrLayout.tableRows(results.joinToString("\n\n") { it.layoutText })
            if (rows.isEmpty()) throw IllegalStateException("No table was found in the photo(s) - try Scan Photos instead")
            return StepResult(WorkflowData.Table(rows), preview = "Found ${rows.size} row(s) in $count photo(s)")
        }

        val mode = step.opt("mode", "layout")
        val pages = results.map {
            when (mode) {
                "book" -> it.bookText
                "plain" -> it.text
                else -> it.layoutText
            }.ifBlank { it.text }
        }
        val text = if (mode == "book") OcrBook.joinPages(pages) else pages.joinToString("\n\n")
        return StepResult(
            WorkflowData.Text(text),
            preview = "Recognized ${text.length} character(s) across $count photo(s) (${mode} mode)"
        )
    }

    private suspend fun runLoadPdf(context: Context, step: WorkflowStep): StepResult {
        val uri = step.pickedUri ?: throw IllegalStateException("No PDF was picked for this step")
        val text = withContext(Dispatchers.IO) {
            val temp = FileGuards.copyToTemp(context, uri, "wf_pdf_", ".pdf")
            try { PdfHelper.extractText(temp) } finally { temp.delete() }
        }
        return StepResult(WorkflowData.Text(text), preview = "Extracted ${text.length} character(s) of text")
    }

    private suspend fun runLoadSheet(context: Context, step: WorkflowStep): StepResult {
        val uri = step.pickedUri ?: throw IllegalStateException("No file was picked for this step")
        val name = context.displayNameOf(uri)
        val rows = withContext(Dispatchers.IO) {
            val lower = name.lowercase()
            if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
                DataCleaner.parseCsvText(FileGuards.readText(context, uri))
            } else {
                val temp = FileGuards.copyToTemp(context, uri, "wf_sheet_", ".xlsx")
                try { ExcelCsvHelper.readXlsx(temp) } finally { temp.delete() }
            }
        }
        if (rows.isEmpty()) throw IllegalStateException("Could not find any rows in $name")
        return StepResult(WorkflowData.Table(rows), preview = "Loaded ${rows.size} row(s) from $name")
    }

    private suspend fun runScrapeUrl(step: WorkflowStep): StepResult {
        val raw = step.textInput.trim()
        if (raw.isBlank()) throw IllegalStateException("No URL was entered for this step")
        val url = if (!raw.startsWith("http://") && !raw.startsWith("https://")) "https://$raw" else raw
        val result = Scraper.scrape(url)
        return if (result.items.isNotEmpty()) {
            val rows = ItemExtractor.toRows(result.items, includeSourceColumn = false)
            StepResult(
                WorkflowData.Table(rows),
                preview = "Found ${result.items.size} item(s) on ${result.title.ifBlank { url }}"
            )
        } else {
            // No structured cards detected - fall back to a small [Field, Value] table built
            // from the page itself, so the chain still has a table to hand to the next step.
            val rows = listOf(
                listOf("Field", "Value"),
                listOf("Title", result.title),
                listOf("URL", result.url),
                listOf("Links found", result.links.size.toString()),
                listOf("Text preview", result.text.take(500))
            )
            StepResult(
                WorkflowData.Table(rows),
                preview = "No item cards detected — used a page summary for ${result.title.ifBlank { url }}"
            )
        }
    }

    private fun runPasteText(step: WorkflowStep): StepResult {
        if (step.textInput.isBlank()) throw IllegalStateException("No text was entered for this step")
        return StepResult(WorkflowData.Text(step.textInput), preview = "${step.textInput.length} character(s) ready")
    }

    // --- Transforms ------------------------------------------------------------------------------

    private suspend fun runCleanTable(input: WorkflowData): StepResult {
        val table = input as? WorkflowData.Table ?: throw IllegalStateException("No table data available to clean")
        val (cleaned, report) = withContext(Dispatchers.Default) { DataCleaner.clean(table.rows, CleaningOptions()) }
        return StepResult(
            WorkflowData.Table(cleaned),
            preview = "Rows ${report.rowsIn} → ${report.rowsOut}  •  ${report.duplicatesRemoved} duplicate(s) removed"
        )
    }

    private fun runExtractEmails(input: WorkflowData): StepResult {
        val text = when (input) {
            is WorkflowData.Text -> input.value
            is WorkflowData.Table -> input.rows.joinToString("\n") { it.joinToString(" ") }
            else -> throw IllegalStateException("No text or table data available to search for emails")
        }
        val result = EmailExtractor.extract(text)
        return StepResult(WorkflowData.Emails(result.emails), preview = "${result.emails.size} valid email(s) found")
    }

    private fun requireTable(input: WorkflowData): List<List<String>> =
        (input as? WorkflowData.Table)?.rows?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("There is no table data to work on")

    private fun requireText(input: WorkflowData): String =
        (input as? WorkflowData.Text)?.value ?: throw IllegalStateException("There is no text to work on")

    /** 1-based column number, or a header name (case-insensitive). */
    private fun columnIndex(header: List<String>, spec: String): Int? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { if (it in 1..header.size) return it - 1 }
        return header.indexOfFirst { it.trim().equals(s, ignoreCase = true) }.takeIf { it >= 0 }
    }

    private fun padRows(rows: List<List<String>>): List<List<String>> {
        val cols = rows.maxOfOrNull { it.size } ?: 0
        return rows.map { r -> r + List(cols - r.size) { "" } }
    }

    private fun runTextToTable(step: WorkflowStep, input: WorkflowData): StepResult {
        val text = requireText(input)
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw IllegalStateException("There is no text to split")
        val delimiter = step.opt("delimiter", "auto")
        val mode = if (delimiter != "auto") delimiter else when {
            lines.any { it.contains('\t') } -> "tab"
            lines.count { it.contains(';') } * 2 >= lines.size -> "semicolon"
            lines.count { it.contains(',') } * 2 >= lines.size -> "comma"
            else -> "spaces"
        }
        val rows = when (mode) {
            "tab" -> lines.map { it.split('\t') }
            "semicolon" -> lines.map { it.split(';') }
            "comma" -> DataCleaner.parseCsvText(lines.joinToString("\n"))
            else -> lines.map { it.trim().split(Regex("\\s{2,}")) }
        }.map { r -> r.map { it.trim() } }
        val padded = padRows(rows)
        return StepResult(
            WorkflowData.Table(padded),
            preview = "${padded.size} row(s) × ${padded.firstOrNull()?.size ?: 0} column(s) (split by $mode)"
        )
    }

    private fun runTableToText(step: WorkflowStep, input: WorkflowData): StepResult {
        val rows = requireTable(input)
        val sep = when (step.opt("separator", "tab")) {
            "comma" -> ", "
            "pipe" -> " | "
            else -> "\t"
        }
        val text = rows.joinToString("\n") { it.joinToString(sep) }
        return StepResult(WorkflowData.Text(text), preview = "${rows.size} row(s) written as text")
    }

    private fun runCleanText(input: WorkflowData): StepResult {
        val raw = requireText(input)
        val cleaned = raw.replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { line ->
            val t = line.trimEnd()
            if (t.contains('\t')) t else t.trim().replace(Regex(" {2,}"), " ")
        }.replace(Regex("\n{3,}"), "\n\n").trim()
        return StepResult(WorkflowData.Text(cleaned), preview = "${raw.length} → ${cleaned.length} character(s)")
    }

    private fun runFindReplace(step: WorkflowStep, input: WorkflowData): StepResult {
        val text = requireText(input)
        val find = step.opt("find")
        if (find.isEmpty()) throw IllegalStateException("Enter the text to find")
        val ignoreCase = step.opt("case", "ignore") == "ignore"
        var count = 0
        var from = 0
        while (true) {
            val i = text.indexOf(find, from, ignoreCase)
            if (i < 0) break
            count++
            from = i + find.length
        }
        val out = text.replace(find, step.opt("replace"), ignoreCase)
        return StepResult(WorkflowData.Text(out), preview = "$count replacement(s) made")
    }

    private fun runFilterRows(step: WorkflowStep, input: WorkflowData): StepResult {
        val rows = requireTable(input)
        val value = step.opt("value")
        if (value.isEmpty()) throw IllegalStateException("Enter the value to match")
        val header = rows.first()
        val spec = step.opt("column")
        val col = if (spec.isBlank()) null else columnIndex(header, spec)
            ?: throw IllegalStateException("Column \"$spec\" was not found in the header row")
        val rule = step.opt("rule", "contains")
        fun matches(cell: String): Boolean = when (rule) {
            "not" -> !cell.contains(value, ignoreCase = true)
            "equals" -> cell.trim().equals(value.trim(), ignoreCase = true)
            "starts" -> cell.trim().startsWith(value.trim(), ignoreCase = true)
            else -> cell.contains(value, ignoreCase = true)
        }
        val kept = rows.drop(1).filter { r ->
            if (col != null) matches(r.getOrElse(col) { "" })
            else if (rule == "not") r.all { matches(it) } else r.any { matches(it) }
        }
        return StepResult(WorkflowData.Table(listOf(header) + kept), preview = "Kept ${kept.size} of ${rows.size - 1} row(s)")
    }

    private fun runSortTable(step: WorkflowStep, input: WorkflowData): StepResult {
        val rows = requireTable(input)
        val spec = step.opt("column")
        if (spec.isBlank()) throw IllegalStateException("Enter the column to sort by")
        val header = rows.first()
        val col = columnIndex(header, spec) ?: throw IllegalStateException("Column \"$spec\" was not found in the header row")
        fun num(s: String) = s.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()
        val body = rows.drop(1)
        val numeric = body.isNotEmpty() && body.all { num(it.getOrElse(col) { "" }) != null || it.getOrElse(col) { "" }.isBlank() }
        var sorted = if (numeric) body.sortedBy { num(it.getOrElse(col) { "" }) ?: Double.NEGATIVE_INFINITY }
        else body.sortedBy { it.getOrElse(col) { "" }.lowercase() }
        val desc = step.opt("order", "asc") == "desc"
        if (desc) sorted = sorted.reversed()
        return StepResult(
            WorkflowData.Table(listOf(header) + sorted),
            preview = "Sorted ${body.size} row(s) by ${header.getOrElse(col) { "column ${col + 1}" }} (${if (desc) "Z→A / high→low" else "A→Z / low→high"})"
        )
    }

    private fun runKeepColumns(step: WorkflowStep, input: WorkflowData): StepResult {
        val rows = requireTable(input)
        val specs = step.opt("columns").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (specs.isEmpty()) throw IllegalStateException("List the columns to keep, e.g. 1, 3, Name")
        val header = rows.first()
        val idx = specs.map { columnIndex(header, it) ?: throw IllegalStateException("Column \"$it\" was not found in the header row") }
        val out = rows.map { r -> idx.map { r.getOrElse(it) { "" } } }
        return StepResult(WorkflowData.Table(out), preview = "Kept ${idx.size} of ${header.size} column(s)")
    }

    // --- Exports ---------------------------------------------------------------------------------

    private enum class ExportFormat(val ext: String) { CSV("csv"), XLSX("xlsx"), TXT("txt"), PDF("pdf"), DOCX("docx") }

    /** Custom file name from the step (letters, digits, space . _ - only), or a timestamped default. */
    private fun outputFile(context: Context, step: WorkflowStep, format: ExportFormat): File {
        val custom = step.opt("fileName").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trim('.').take(60)
            .removeSuffix("." + format.ext)
        val base = custom.ifBlank { "workflow_${System.currentTimeMillis()}" }
        // One folder per export, so two steps using the same name never overwrite each other.
        val dir = File(context.cacheDir, "workflow_out/${System.nanoTime()}").apply { mkdirs() }
        return File(dir, "$base.${format.ext}")
    }

    private suspend fun runExport(context: Context, step: WorkflowStep, input: WorkflowData, format: ExportFormat): StepResult {
        if (input is WorkflowData.Empty) throw IllegalStateException("There's nothing to export yet")
        val file = withContext(Dispatchers.IO) {
            val out = outputFile(context, step, format)
            when (format) {
                ExportFormat.CSV -> ExcelCsvHelper.writeCsv(asRows(input), out)
                ExportFormat.XLSX -> ExcelCsvHelper.writeXlsx(asRows(input), out, sheetName = "Workflow")
                ExportFormat.TXT -> out.writeText(asText(input))
                ExportFormat.PDF -> when (layoutStyle(step, input)) {
                    "book" -> PdfHelper.bookToPdf(layoutText(input), out)
                    "table" -> PdfHelper.textToPdfWithTables(layoutText(input), out)
                    else -> PdfHelper.textToPdf(asText(input), out)
                }
                ExportFormat.DOCX -> when (layoutStyle(step, input)) {
                    "book" -> DocxWriter.writeBookText(layoutText(input), out)
                    "table" -> DocxWriter.writeTextWithTables(layoutText(input), out)
                    else -> DocxWriter.writeText(asText(input), out)
                }
            }
            out
        }
        val kb = (file.length() + 512) / 1024
        return StepResult(WorkflowData.Empty, preview = "Saved ${file.name} (${if (kb == 0L) "<1" else kb.toString()} KB) — ready to Save As… or Share", exportedFile = file)
    }

    /** PDF / Word look: "auto" picks book layout for text with # headings, table layout for
     *  tables or tab-separated text, and plain text otherwise. */
    private fun layoutStyle(step: WorkflowStep, input: WorkflowData): String {
        val chosen = step.opt("style", "auto")
        if (chosen != "auto") return chosen
        return when (input) {
            is WorkflowData.Table -> "table"
            is WorkflowData.Text ->
                if (input.value.lines().any { it.startsWith("# ") || it.startsWith("## ") || it.startsWith("### ") }) "book"
                else if (input.value.contains('\t')) "table"
                else "plain"
            else -> "plain"
        }
    }

    /** Text for the book / table writers: tables become tab-separated lines. */
    private fun layoutText(input: WorkflowData): String = when (input) {
        is WorkflowData.Table -> input.rows.joinToString("\n") { it.joinToString("\t") }
        else -> asText(input)
    }

    private fun asRows(input: WorkflowData): List<List<String>> = when (input) {
        is WorkflowData.Table -> input.rows
        is WorkflowData.Emails -> listOf(listOf("email")) + input.list.map { listOf(it) }
        is WorkflowData.Text ->
            // Tab-separated text (e.g. OCR'd tables) keeps its columns; other text is one line per row.
            if (input.value.contains('\t')) padRows(input.value.lines().map { it.split('\t') })
            else listOf(listOf("Text")) + input.value.lines().map { listOf(it) }
        WorkflowData.Empty -> emptyList()
    }

    private fun asText(input: WorkflowData): String = when (input) {
        is WorkflowData.Text -> input.value
        is WorkflowData.Table -> DataCleaner.toCsvText(input.rows)
        is WorkflowData.Emails -> input.list.joinToString("\n")
        WorkflowData.Empty -> ""
    }
}
