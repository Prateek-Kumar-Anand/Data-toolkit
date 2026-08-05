package com.prateek.datatoolkit.features.workflow

import android.net.Uri
import java.io.File

/**
 * What kind of data flows between workflow steps. Every step declares which
 * kind(s) it can accept from whatever ran before it, and which single kind
 * it hands to whatever runs next - see [StepKind].
 */
enum class DataKind(val label: String) {
    NONE("nothing"),
    TEXT("text"),
    TABLE("a table"),
    EMAILS("a list of emails")
}

/** The actual payload handed from one step to the next, tagged by [kind]. */
sealed class WorkflowData(val kind: DataKind) {
    object Empty : WorkflowData(DataKind.NONE)
    data class Text(val value: String) : WorkflowData(DataKind.TEXT)
    data class Table(val rows: List<List<String>>) : WorkflowData(DataKind.TABLE)
    data class Emails(val list: List<String>) : WorkflowData(DataKind.EMAILS)
}

/**
 * One kind of block a workflow can be built from - each one mirrors an
 * existing tool's engine (OCR, PDF, Excel/CSV, Scraper, EmailExtractor,
 * DataCleaner) so a workflow behaves exactly like running those tools by
 * hand, just chained together automatically.
 *
 * [accepts] is what the step can receive from whatever came right before it.
 * A source step only "accepts" [DataKind.NONE] - meaning it doesn't transform
 * anything, it starts a brand new branch (from a picked file, a typed URL, or
 * pasted text). [produces] is what the *next* step in the chain will receive.
 *
 * Every export produces [DataKind.NONE], which is what lets a fresh source
 * step legally follow one - so a single workflow run can chain several
 * independent source -> ... -> export branches back to back.
 */
enum class StepKind(
    val stepLabel: String,
    val emoji: String,
    val summary: String,
    val accepts: Set<DataKind>,
    val produces: DataKind
) {
    SCAN_IMAGES(
        "Scan Photos (OCR)", "📷", "Pick photos, recognize the text on them",
        setOf(DataKind.NONE), DataKind.TEXT
    ),
    LOAD_PDF(
        "Load a PDF", "📄", "Pick a PDF, pull out its text",
        setOf(DataKind.NONE), DataKind.TEXT
    ),
    LOAD_SHEET(
        "Load Excel / CSV", "📊", "Pick a spreadsheet to use as a table",
        setOf(DataKind.NONE), DataKind.TABLE
    ),
    SCRAPE_URL(
        "Scrape a URL", "🌐", "Fetch a page — auto-detects product/article cards",
        setOf(DataKind.NONE), DataKind.TABLE
    ),
    PASTE_TEXT(
        "Paste Text", "✍️", "Type or paste in the starting text",
        setOf(DataKind.NONE), DataKind.TEXT
    ),

    CLEAN_TABLE(
        "Clean the Data", "🧹", "Trim, de-duplicate, drop blank rows",
        setOf(DataKind.TABLE), DataKind.TABLE
    ),
    EXTRACT_EMAILS(
        "Extract Emails", "✉️", "Pull out valid, de-duplicated emails",
        setOf(DataKind.TEXT, DataKind.TABLE), DataKind.EMAILS
    ),

    EXPORT_CSV(
        "Export as CSV", "💾", "Save as a .csv file",
        setOf(DataKind.TABLE, DataKind.TEXT, DataKind.EMAILS), DataKind.NONE
    ),
    EXPORT_XLSX(
        "Export as Excel", "💾", "Save as an .xlsx file",
        setOf(DataKind.TABLE, DataKind.TEXT, DataKind.EMAILS), DataKind.NONE
    ),
    EXPORT_TXT(
        "Export as Text", "💾", "Save as a plain .txt file",
        setOf(DataKind.TEXT, DataKind.TABLE, DataKind.EMAILS), DataKind.NONE
    ),
    EXPORT_PDF(
        "Export as PDF", "💾", "Save as a .pdf file",
        setOf(DataKind.TEXT, DataKind.TABLE, DataKind.EMAILS), DataKind.NONE
    ),
    EXPORT_DOCX(
        "Export as Word", "💾", "Save as a .docx file",
        setOf(DataKind.TEXT, DataKind.TABLE, DataKind.EMAILS), DataKind.NONE
    )
}

/** SKIPPED means the run never attempted this step because an earlier step in the chain
 *  already failed - see [com.prateek.datatoolkit.features.workflow.WorkflowActivity.runWorkflow].
 *  Kept distinct from FAILED so the results/history don't read as "this step itself broke". */
enum class StepStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

/** One configured block in a user-built workflow, plus whatever it produced after running. */
class WorkflowStep(val kind: StepKind) {
    // User-supplied parameters - only the ones relevant to this step's kind are ever read.
    var pickedUri: Uri? = null
    var pickedUris: List<Uri> = emptyList()
    var textInput: String = ""

    // Filled in once the workflow runs.
    var status: StepStatus = StepStatus.PENDING
    var resultPreview: String = ""
    var errorMessage: String? = null
}

/** One export step's finished file, kept around so its "Save As…" button knows what to copy. */
data class ExportOutcome(val kind: StepKind, val file: File)
