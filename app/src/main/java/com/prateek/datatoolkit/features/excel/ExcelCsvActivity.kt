package com.prateek.datatoolkit.features.excel

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.R
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.quality.QualityScorer
import com.prateek.datatoolkit.core.storage.OutputStorage
import com.prateek.datatoolkit.core.storage.StoragePermissionHelper
import com.prateek.datatoolkit.databinding.ActivityExcelCsvBinding
import com.prateek.datatoolkit.features.excel.sheet.CellRef
import com.prateek.datatoolkit.features.excel.sheet.SheetCell
import com.prateek.datatoolkit.features.excel.sheet.SheetData
import com.prateek.datatoolkit.features.excel.sheet.SheetsWorkbook
import com.prateek.datatoolkit.features.excel.sheet.formula.SheetRecalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * A real (if deliberately scoped-down - see SpreadsheetGridView's doc comment, and
 * ExcelCsvHelper's readWorkbook/writeWorkbook) spreadsheet grid: open an .xlsx or .csv, tap any
 * cell to select it, edit its value or formula through the docked formula bar, switch between
 * sheets via the tabs row, and save back to Downloads/Output/Excel/ as .xlsx (multi-sheet,
 * formulas preserved as live formulas) or .csv (active sheet's computed values only - CSV has
 * no concept of a formula or a second sheet).
 */
class ExcelCsvActivity : AppCompatActivity(), SpreadsheetGridView.Listener {

    private lateinit var binding: ActivityExcelCsvBinding
    private lateinit var cache: CacheManager

    // Auto-save (Downloads/Output/Excel/) needs WRITE_EXTERNAL_STORAGE on API 24-28 only; see
    // StoragePermissionHelper.
    private val storagePermission = StoragePermissionHelper(this)

    private var workbook: SheetsWorkbook = SheetsWorkbook()

    // One recalculator per sheet, created (and fully recalculated) the first time that sheet is
    // actually shown - see recalculatorFor. SheetRecalculator has no cross-sheet awareness (see
    // its own doc comment), so each sheet's formulas are entirely independent of every other's.
    private val recalculators = HashMap<SheetData, SheetRecalculator>()

    // Which (sheet, cell) the formula bar's current on-screen text belongs to - tracked
    // separately from workbook.activeSheet/spreadsheetGrid.selected rather than read from them
    // at commit time, because switching sheets or tapping a new cell changes *those* before the
    // still-pending edit for the *previous* cell has been committed; see commitFormulaBar and
    // onCellSelected.
    private var formulaBarSheet: SheetData? = null
    private var formulaBarRef: CellRef = CellRef(0, 0)

    private val pickXlsx = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadXlsx(it) }
    }
    private val pickCsv = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadCsv(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExcelCsvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)

        binding.spreadsheetGrid.listener = this

        binding.btnPickXlsx.setOnClickListener {
            // */* rather than the exact OOXML MIME type: many document providers (Downloads,
            // some cloud providers) report .xlsx as application/octet-stream, which makes the
            // file unselectable if we filter on the precise spreadsheet MIME type. Same pattern
            // already used for file picking in DataCleaningActivity and WorkflowActivity.
            pickXlsx.launch("*/*")
        }
        binding.btnPickCsv.setOnClickListener { pickCsv.launch("*/*") }
        binding.btnExportCsv.setOnClickListener { export(asXlsx = false) }
        binding.btnExportXlsx.setOnClickListener { export(asXlsx = true) }
        binding.btnBold.setOnClickListener { toggleBold() }

        binding.formulaBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitFormulaBar()
                binding.formulaBar.clearFocus()
                hideKeyboard()
                true
            } else {
                false
            }
        }
        // Covers every OTHER way focus can leave the bar (tapping a cell, switching sheets,
        // exporting - all of which call commitFormulaBar() explicitly too, so this is really a
        // backstop for any path that doesn't) - commitFormulaBar's own no-op guard makes calling
        // it redundantly harmless.
        binding.formulaBar.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitFormulaBar() }

        bindWorkbookToUi(workbook) // shows the initial blank Sheet1 - not a file open, so no cache.record
    }

    // ---- SpreadsheetGridView.Listener ----------------------------------------------------------

    override fun onCellSelected(ref: CellRef, cell: SheetCell) {
        commitFormulaBar() // whatever was pending for the *previous* selection, before this overwrites the bar
        formulaBarSheet = workbook.activeSheet
        formulaBarRef = ref
        binding.tvCellRef.text = ref.toString()
        binding.formulaBar.setText(cell.input)
        updateBoldButton(cell.bold)
    }

    override fun onEditRequested(ref: CellRef, cell: SheetCell) {
        binding.formulaBar.requestFocus()
        binding.formulaBar.setSelection(binding.formulaBar.text?.length ?: 0)
        showKeyboard(binding.formulaBar)
    }

    // ---- editing --------------------------------------------------------------------------------

    /** Writes whatever's currently in the formula bar back to the exact (sheet, cell) it
     *  belongs to - see [formulaBarSheet]/[formulaBarRef] - and recalculates. Safe (and cheap)
     *  to call whenever there's *any* chance of a pending edit, even if there turns out not to
     *  be one: a no-op if the text hasn't actually changed, and a no-op entirely before the
     *  first cell has ever been selected ([formulaBarSheet] still null). */
    private fun commitFormulaBar() {
        val sheet = formulaBarSheet ?: return
        val ref = formulaBarRef
        val newInput = binding.formulaBar.text?.toString() ?: ""
        val cell = sheet.cellAt(ref)
        if (cell.input == newInput) return
        cell.input = newInput
        sheet.ensureRoomFor(ref)
        recalculatorFor(sheet).recalculate(ref)
        if (sheet === workbook.activeSheet) binding.spreadsheetGrid.refresh()
    }

    private fun recalculatorFor(sheet: SheetData): SheetRecalculator =
        recalculators.getOrPut(sheet) { SheetRecalculator(sheet).also { it.recalculateAll() } }

    private fun toggleBold() {
        val ref = binding.spreadsheetGrid.selected
        val cell = workbook.activeSheet.cellAt(ref)
        cell.bold = !cell.bold
        binding.spreadsheetGrid.refresh()
        updateBoldButton(cell.bold)
    }

    /** Reflects whether the *currently selected* cell is bold on the toggle button itself -
     *  reusing the same tinted-pill look the sheet tabs use for "this one's active", so a
     *  bold cell's selected state reads the same way a selected sheet tab does. */
    private fun updateBoldButton(isBold: Boolean) {
        binding.btnBold.setBackgroundResource(if (isBold) R.drawable.bg_sheet_tab_active else R.drawable.bg_pill_muted)
        binding.btnBold.setTextColor(ContextCompat.getColor(this, if (isBold) R.color.accent_excel else R.color.text_secondary))
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.formulaBar.windowToken, 0)
    }

    // ---- opening files --------------------------------------------------------------------------

    private fun loadXlsx(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Opening spreadsheet..."
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("in_", ".xlsx", cacheDir)
                    contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(file).use { o -> i.copyTo(o) } }
                        ?: throw IllegalStateException("Could not open the selected file")
                    val wb = ExcelCsvHelper.readWorkbook(file)
                    file.delete()
                    wb
                }
                openWorkbook(loaded, uri.lastPathSegment ?: "sheet.xlsx", start)
            } catch (e: Throwable) {
                // Throwable, not just Exception: a bad/corrupt spreadsheet can surface as a
                // java.lang.Error (e.g. a StAX factory error) rather than a normal Exception,
                // which would otherwise crash the whole app instead of showing this message.
                binding.tvStatus.text = "Failed to open: ${e.message}"
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun loadCsv(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Opening CSV..."
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                    val rows = com.prateek.datatoolkit.features.datacleaning.DataCleaner.parseCsvText(text)
                    rowsToWorkbook(rows)
                }
                openWorkbook(loaded, uri.lastPathSegment ?: "data.csv", start)
            } catch (e: Exception) {
                binding.tvStatus.text = "Failed to open: ${e.message}"
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun rowsToWorkbook(rows: List<List<String>>): SheetsWorkbook {
        val sheet = SheetData("Sheet1")
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, value ->
                if (value.isNotEmpty()) {
                    val ref = CellRef(r, c)
                    // A CSV cell that happens to start with '=' is just data, never an intended
                    // formula (CSV has no such concept) - see SheetCell.isFormula's doc comment
                    // for why this matters beyond just correctness.
                    sheet.cellAt(ref).input = if (value.startsWith("=")) "'$value" else value
                    sheet.ensureRoomFor(ref)
                }
            }
        }
        return SheetsWorkbook(mutableListOf(sheet))
    }

    /** Wires a workbook up to the grid/tabs - used both for a real file open and for the
     *  initial blank sheet in onCreate, which is why this doesn't touch cache.record itself
     *  (only an actual file open should show up in the app's history - see [openWorkbook]). */
    private fun bindWorkbookToUi(loaded: SheetsWorkbook) {
        recalculators.clear()
        workbook = loaded
        recalculatorFor(workbook.activeSheet).recalculateAll()
        binding.spreadsheetGrid.sheet = workbook.activeSheet // fires onCellSelected for its A1, wiring up the formula bar
        renderSheetTabs()
    }

    private fun openWorkbook(loaded: SheetsWorkbook, label: String, start: Long) {
        bindWorkbookToUi(loaded)

        var totalCells = 0
        var formulaCells = 0
        workbook.sheets.forEach { s ->
            s.allCells().values.forEach { if (!it.isBlank()) { totalCells++; if (it.isFormula) formulaCells++ } }
        }
        val formulaNote = if (formulaCells > 0) ", $formulaCells formula(s)" else ""
        val statusText = if (workbook.sheets.size > 1)
            "Opened ${workbook.sheets.size} sheets, $totalCells cell(s)$formulaNote"
        else
            "Opened $totalCells cell(s)$formulaNote"
        binding.tvStatus.text = statusText

        lifecycleScope.launch {
            cache.record(
                feature = "EXCEL_CSV",
                inputText = label + totalCells,
                inputLabel = label,
                outputPreview = statusText,
                outputPath = null,
                qualityScore = QualityScorer.scoreTable(activeSheetAsRows()),
                status = "SUCCESS",
                durationMs = System.currentTimeMillis() - start
            )
        }
    }

    /** The active sheet's used range flattened to plain text - only for the two things that
     *  still genuinely want a flat table: CSV export (CSV has no richer shape to preserve) and
     *  the history dashboard's quality score (which scores arbitrary tabular text generically
     *  across every module, not just this one). Nothing else in this Activity uses this. */
    private fun activeSheetAsRows(): List<List<String>> {
        val sheet = workbook.activeSheet
        val used = sheet.usedRange() ?: return emptyList()
        return (used.minRow..used.maxRow).map { row ->
            (used.minCol..used.maxCol).map { col -> sheet.existingCellAt(CellRef(row, col))?.displayText() ?: "" }
        }
    }

    // ---- sheet tabs -----------------------------------------------------------------------------

    private fun renderSheetTabs() {
        binding.sheetTabsContainer.removeAllViews()
        workbook.sheets.forEachIndexed { index, sheet ->
            val active = index == workbook.activeSheetIndex
            binding.sheetTabsContainer.addView(buildTabView(sheet.name, active, R.drawable.bg_sheet_tab_active) { switchToSheet(index) })
        }
        binding.sheetTabsContainer.addView(buildTabView("＋", active = false, R.drawable.bg_pill_muted) { addSheet() })
    }

    private fun buildTabView(label: String, active: Boolean, activeBg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ExcelCsvActivity, if (active) R.color.accent_excel else R.color.text_secondary))
            typeface = Typeface.create(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
            setBackgroundResource(if (active) activeBg else R.drawable.bg_pill_muted)
            val hPad = dpToPx(12)
            val vPad = dpToPx(7)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = dpToPx(8)
            }
            setOnClickListener { onClick() }
        }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun switchToSheet(index: Int) {
        commitFormulaBar()
        workbook.activeSheetIndex = index
        binding.spreadsheetGrid.sheet = workbook.activeSheet
        renderSheetTabs()
    }

    private fun addSheet() {
        commitFormulaBar()
        workbook.addSheet()
        binding.spreadsheetGrid.sheet = workbook.activeSheet
        renderSheetTabs()
    }

    // ---- export ---------------------------------------------------------------------------------

    /** Auto-saves into Downloads/Output/Excel/ (auto-created, collision-proof name) instead of
     *  prompting the user to browse to a destination - .xlsx keeps every sheet and every live
     *  formula (see ExcelCsvHelper.writeWorkbook); .csv can only ever hold the active sheet's
     *  current computed values, which is a limitation of the CSV format itself rather than
     *  something this app chooses to leave out. */
    private fun export(asXlsx: Boolean) {
        commitFormulaBar()
        val hasAnyContent = workbook.sheets.any { it.usedRange() != null }
        if (!hasAnyContent) {
            Toast.makeText(this, "Nothing to save yet - open a file or type into a cell first", Toast.LENGTH_SHORT).show()
            return
        }
        storagePermission.runWithPermission { doExport(asXlsx) }
    }

    private fun doExport(asXlsx: Boolean) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Exporting..."
        lifecycleScope.launch {
            try {
                val name = "export_${System.currentTimeMillis()}.${if (asXlsx) "xlsx" else "csv"}"
                val mimeType = if (asXlsx)
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else
                    "text/csv"
                val saved = withContext(Dispatchers.IO) {
                    val tempFile = File.createTempFile("export_", if (asXlsx) ".xlsx" else ".csv", cacheDir)
                    if (asXlsx) ExcelCsvHelper.writeWorkbook(workbook, tempFile)
                    else ExcelCsvHelper.writeCsv(activeSheetAsRows(), tempFile)
                    OutputStorage.saveFile(this@ExcelCsvActivity, OutputStorage.Module.EXCEL, tempFile, name, mimeType).also {
                        tempFile.delete()
                    }
                }
                val multiSheetNote = if (!asXlsx && workbook.sheets.size > 1) " (active sheet only - CSV can't hold more than one)" else ""
                Toast.makeText(this@ExcelCsvActivity, "Saved to ${saved.humanPath}$multiSheetNote", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@ExcelCsvActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }
}
