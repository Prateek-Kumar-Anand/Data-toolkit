package com.prateek.datatoolkit.features.workflow

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.prateek.datatoolkit.core.export.DocxWriter
import com.prateek.datatoolkit.features.datacleaning.CleaningOptions
import com.prateek.datatoolkit.features.datacleaning.DataCleaner
import com.prateek.datatoolkit.features.email.EmailExtractor
import com.prateek.datatoolkit.features.excel.ExcelCsvHelper
import com.prateek.datatoolkit.features.ocr.OcrHelper
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
            StepKind.SCAN_IMAGES -> runOcr(context, step)
            StepKind.LOAD_PDF -> runLoadPdf(context, step)
            StepKind.LOAD_SHEET -> runLoadSheet(context, step)
            StepKind.SCRAPE_URL -> runScrapeUrl(step)
            StepKind.PASTE_TEXT -> runPasteText(step)
            StepKind.CLEAN_TABLE -> runCleanTable(input)
            StepKind.EXTRACT_EMAILS -> runExtractEmails(input)
            StepKind.EXPORT_CSV -> runExport(context, input, ExportFormat.CSV)
            StepKind.EXPORT_XLSX -> runExport(context, input, ExportFormat.XLSX)
            StepKind.EXPORT_TXT -> runExport(context, input, ExportFormat.TXT)
            StepKind.EXPORT_PDF -> runExport(context, input, ExportFormat.PDF)
            StepKind.EXPORT_DOCX -> runExport(context, input, ExportFormat.DOCX)
        }
    }

    // --- Sources -------------------------------------------------------------------------------

    private suspend fun runOcr(context: Context, step: WorkflowStep): StepResult {
        if (step.pickedUris.isEmpty()) throw IllegalStateException("No photos were picked for this step")
        val bitmaps = withContext(Dispatchers.IO) {
            step.pickedUris.mapNotNull { uri ->
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        }
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read the selected photo(s)")
        val results = OcrHelper.recognizeBatch(bitmaps)
        val text = results.joinToString("\n\n") { it.text }
        return StepResult(
            WorkflowData.Text(text),
            preview = "Recognized ${text.length} character(s) across ${bitmaps.size} photo(s)"
        )
    }

    private suspend fun runLoadPdf(context: Context, step: WorkflowStep): StepResult {
        val uri = step.pickedUri ?: throw IllegalStateException("No PDF was picked for this step")
        val text = withContext(Dispatchers.IO) {
            val temp = File.createTempFile("wf_pdf_", ".pdf", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(temp).use { o -> i.copyTo(o) } }
            val result = PdfHelper.extractText(temp)
            temp.delete()
            result
        }
        return StepResult(WorkflowData.Text(text), preview = "Extracted ${text.length} character(s) of text")
    }

    private suspend fun runLoadSheet(context: Context, step: WorkflowStep): StepResult {
        val uri = step.pickedUri ?: throw IllegalStateException("No file was picked for this step")
        val name = displayNameOf(context, uri)
        val rows = withContext(Dispatchers.IO) {
            val lower = name.lowercase()
            if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                DataCleaner.parseCsvText(text)
            } else {
                val temp = File.createTempFile("wf_sheet_", ".xlsx", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(temp).use { o -> i.copyTo(o) } }
                val result = ExcelCsvHelper.readXlsx(temp)
                temp.delete()
                result
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

    // --- Exports ---------------------------------------------------------------------------------

    private enum class ExportFormat { CSV, XLSX, TXT, PDF, DOCX }

    private suspend fun runExport(context: Context, input: WorkflowData, format: ExportFormat): StepResult {
        if (input is WorkflowData.Empty) throw IllegalStateException("There's nothing to export yet")
        val timestamp = System.currentTimeMillis()
        val file = withContext(Dispatchers.IO) {
            when (format) {
                ExportFormat.CSV -> File(context.cacheDir, "workflow_$timestamp.csv").also {
                    ExcelCsvHelper.writeCsv(asRows(input), it)
                }
                ExportFormat.XLSX -> File(context.cacheDir, "workflow_$timestamp.xlsx").also {
                    ExcelCsvHelper.writeXlsx(asRows(input), it, sheetName = "Workflow")
                }
                ExportFormat.TXT -> File(context.cacheDir, "workflow_$timestamp.txt").also {
                    it.writeText(asText(input))
                }
                ExportFormat.PDF -> File(context.cacheDir, "workflow_$timestamp.pdf").also {
                    PdfHelper.textToPdf(asText(input), it)
                }
                ExportFormat.DOCX -> File(context.cacheDir, "workflow_$timestamp.docx").also {
                    DocxWriter.writeText(asText(input), it)
                }
            }
        }
        return StepResult(WorkflowData.Empty, preview = "Saved as ${file.name} — ready to Save As…", exportedFile = file)
    }

    private fun asRows(input: WorkflowData): List<List<String>> = when (input) {
        is WorkflowData.Table -> input.rows
        is WorkflowData.Emails -> listOf(listOf("email")) + input.list.map { listOf(it) }
        is WorkflowData.Text -> listOf(listOf("Text")) + input.value.lines().map { listOf(it) }
        WorkflowData.Empty -> emptyList()
    }

    private fun asText(input: WorkflowData): String = when (input) {
        is WorkflowData.Text -> input.value
        is WorkflowData.Table -> DataCleaner.toCsvText(input.rows)
        is WorkflowData.Emails -> input.list.joinToString("\n")
        WorkflowData.Empty -> ""
    }

    private fun displayNameOf(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment ?: "file"
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { name = it }
                }
            }
        } catch (e: Exception) {
            // Fall back to the lastPathSegment already captured above.
        }
        return name
    }
}
