package com.prateek.datatoolkit.features.workflow

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.prateek.datatoolkit.R
import com.prateek.datatoolkit.core.cache.AppDatabase
import com.prateek.datatoolkit.core.cache.CacheManager
import com.prateek.datatoolkit.core.cache.ProcessedItem
import com.prateek.datatoolkit.core.cache.SavedWorkflow
import com.prateek.datatoolkit.databinding.ActivityWorkflowBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Workflow Builder: lets the user chain the app's existing tools into one
 * pipeline (e.g. Scrape URL -> Clean Data -> Export Excel), instead of
 * running each tool by hand and re-typing/re-picking the result into the
 * next screen. Every step's actual work is delegated to [WorkflowEngine],
 * which in turn reuses the same helpers each standalone screen already
 * calls - this screen is purely the chain-builder + runner UI around it.
 */
class WorkflowActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWorkflowBinding
    private lateinit var cache: CacheManager
    private lateinit var db: AppDatabase

    private val steps = mutableListOf<WorkflowStep>()
    private val exportResults = mutableListOf<ExportOutcome>()

    // Set when the current chain was loaded from a saved workflow, so a successful run can
    // stamp that saved workflow's lastRunAt. Cleared on Start Over / manual chain edits, since
    // at that point the chain no longer matches what was saved.
    private var loadedWorkflowId: Long? = null

    // True for the duration of an active run - guards every action that structurally mutates
    // [steps] (add/remove/replace) so a tap mid-run can't cause a ConcurrentModificationException
    // against the coroutine that's currently iterating that same list in runWorkflow().
    private var isRunning: Boolean = false

    // Shared pickers - which step is waiting for a result is tracked via the slots below,
    // set right before each launch() call (same "pendingAction" idea PdfActivity uses).
    private var pendingUriPick: ((Uri) -> Unit)? = null
    private var pendingMultiUriPick: ((List<Uri>) -> Unit)? = null
    private var pendingExportFile: File? = null

    private val pickSingle = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pendingUriPick?.invoke(uri)
    }
    private val pickMultiple = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) pendingMultiUriPick?.invoke(uris)
    }

    // Browse-to-save: one launcher per export format, mirroring every other screen in the app.
    private val saveCsvAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { copyPendingExportTo(it) }
    }
    private val saveXlsxAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> uri?.let { copyPendingExportTo(it) } }
    private val saveTxtAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { copyPendingExportTo(it) }
    }
    private val savePdfAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { copyPendingExportTo(it) }
    }
    private val saveDocxAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri -> uri?.let { copyPendingExportTo(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkflowBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cache = CacheManager(this)
        db = AppDatabase.get(this)

        binding.btnRunWorkflow.setOnClickListener { runWorkflow() }
        binding.btnResetWorkflow.setOnClickListener { resetWorkflow() }
        binding.btnSaveWorkflow.setOnClickListener { promptSaveWorkflow() }

        renderSteps()
        renderAddStepOptions()
        renderSavedWorkflows()
        renderHistory()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun colorOf(resId: Int) = ContextCompat.getColor(this, resId)

    /** What the *next* added step must accept, based on where the chain currently ends. */
    private fun projectedKind(): DataKind = if (steps.isEmpty()) DataKind.NONE else steps.last().kind.produces

    private fun addStep(kind: StepKind) {
        if (isRunning) return
        steps.add(WorkflowStep(kind))
        loadedWorkflowId = null
        renderSteps()
        renderAddStepOptions()
    }

    /** Removes the step at [index] and every step chained after it, since their input would
     *  no longer make sense once whatever fed them is gone. Asks for confirmation only when
     *  that would take more than just the one (last) step with it. */
    private fun confirmRemoveFrom(index: Int, trailingCount: Int) {
        if (isRunning) return
        if (trailingCount <= 0) {
            removeStepsFrom(index)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove ${trailingCount + 1} steps?")
            .setMessage("This removes step ${index + 1} and the $trailingCount step(s) chained after it.")
            .setPositiveButton("Remove") { _, _ -> removeStepsFrom(index) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeStepsFrom(index: Int) {
        if (index !in steps.indices) return
        while (steps.size > index) steps.removeAt(steps.size - 1)
        loadedWorkflowId = null
        renderSteps()
        renderAddStepOptions()
    }

    private fun resetWorkflow() {
        steps.clear()
        exportResults.clear()
        loadedWorkflowId = null
        binding.tvRunSummary.text = ""
        binding.resultsContainer.removeAllViews()
        renderSteps()
        renderAddStepOptions()
    }

    // ---- Rendering: the step chain ----------------------------------------------------------

    private fun renderSteps() {
        binding.stepsContainer.removeAllViews()
        binding.tvEmptyState.visibility = if (steps.isEmpty()) View.VISIBLE else View.GONE

        for ((index, step) in steps.withIndex()) {
            if (index > 0) binding.stepsContainer.addView(connectorView())
            binding.stepsContainer.addView(buildStepCard(step, index, isLast = index == steps.lastIndex))
        }
    }

    /** A short vertical line between step cards, just to read visually as "flow". */
    private fun connectorView(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(3), dp(16)).apply {
            marginStart = dp(20)
            topMargin = dp(2)
            bottomMargin = dp(2)
        }
        setBackgroundColor(colorOf(R.color.stroke))
    }

    private fun buildStepCard(step: WorkflowStep, index: Int, isLast: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ContextCompat.getDrawable(this@WorkflowActivity, R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        headerRow.addView(TextView(this).apply {
            text = "${step.kind.emoji}  ${index + 1}. ${step.kind.stepLabel}"
            setTextColor(colorOf(R.color.text_primary))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(statusBadge(step.status))
        card.addView(headerRow)

        card.addView(TextView(this).apply {
            text = when (step.status) {
                StepStatus.SUCCESS -> step.resultPreview.ifBlank { "Done" }
                StepStatus.FAILED -> "⚠ ${step.errorMessage ?: "Failed"}"
                StepStatus.SKIPPED -> "⤼ Skipped — an earlier step in this run failed"
                else -> step.kind.summary
            }
            setTextColor(if (step.status == StepStatus.FAILED) colorOf(R.color.error) else colorOf(R.color.text_secondary))
            textSize = 12.5f
            setPadding(0, dp(3), 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        // Inline input controls for source steps - the only ones that need something from
        // the user before a run, since every other step just consumes the previous one's output.
        when (step.kind) {
            StepKind.SCAN_IMAGES -> card.addView(pickChip(
                label = if (step.pickedUris.isEmpty()) "📷  Choose Photos" else "📷  ${step.pickedUris.size} photo(s) selected — change",
                onClick = {
                    pendingMultiUriPick = { uris -> step.pickedUris = uris; renderSteps() }
                    pickMultiple.launch("image/*")
                }
            ))
            StepKind.LOAD_PDF -> card.addView(pickChip(
                label = step.pickedUri?.let { "📄  ${displayNameOf(it)} — change" } ?: "📄  Choose PDF",
                onClick = {
                    pendingUriPick = { uri -> step.pickedUri = uri; renderSteps() }
                    pickSingle.launch("application/pdf")
                }
            ))
            StepKind.LOAD_SHEET -> card.addView(pickChip(
                label = step.pickedUri?.let { "📊  ${displayNameOf(it)} — change" } ?: "📊  Choose File",
                onClick = {
                    pendingUriPick = { uri -> step.pickedUri = uri; renderSteps() }
                    pickSingle.launch("*/*")
                }
            ))
            StepKind.SCRAPE_URL -> card.addView(inlineTextField(
                hint = "https://example.com", current = step.textInput, singleLine = true,
                onChange = { step.textInput = it }
            ))
            StepKind.PASTE_TEXT -> card.addView(inlineTextField(
                hint = "Type or paste text here", current = step.textInput, singleLine = false,
                onChange = { step.textInput = it }
            ))
            else -> { /* transforms and exports need no input from the user */ }
        }

        // Every step can be removed, not just the last one - removing an earlier step also
        // drops everything chained after it (their input would no longer make sense once
        // what feeds them is gone), so the label says exactly what will happen before it does.
        val trailingCount = steps.size - index - 1
        val removeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        }
        removeRow.addView(TextView(this).apply {
            text = if (isLast) "✕ Remove this step" else "✕ Remove this + $trailingCount step(s) after it"
            setTextColor(colorOf(R.color.error))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 11.5f
            alpha = if (isRunning) 0.4f else 1f
            isClickable = !isRunning
            isFocusable = !isRunning
            val outValue = TypedValue()
            this@WorkflowActivity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { confirmRemoveFrom(index, trailingCount) }
        })
        card.addView(removeRow)

        return card
    }

    private fun statusBadge(status: StepStatus): View = TextView(this).apply {
        text = when (status) {
            StepStatus.PENDING -> "PENDING"
            StepStatus.RUNNING -> "RUNNING…"
            StepStatus.SUCCESS -> "DONE"
            StepStatus.FAILED -> "FAILED"
            StepStatus.SKIPPED -> "SKIPPED"
        }
        textSize = 10f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.04f
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = ContextCompat.getDrawable(this@WorkflowActivity, R.drawable.bg_pill_muted)
        backgroundTintList = ContextCompat.getColorStateList(
            this@WorkflowActivity,
            when (status) {
                StepStatus.PENDING -> R.color.text_secondary
                StepStatus.RUNNING -> R.color.primary
                StepStatus.SUCCESS -> R.color.success
                StepStatus.FAILED -> R.color.error
                StepStatus.SKIPPED -> R.color.text_secondary
            }
        )
    }

    private fun pickChip(label: String, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        setTextColor(colorOf(R.color.primary))
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 13f
        background = ContextCompat.getDrawable(this@WorkflowActivity, R.drawable.bg_chip_outline)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun inlineTextField(hint: String, current: String, singleLine: Boolean, onChange: (String) -> Unit): View =
        EditText(this).apply {
            setText(current)
            this.hint = hint
            setTextColor(colorOf(R.color.text_primary))
            setHintTextColor(colorOf(R.color.text_secondary))
            textSize = 13.5f
            background = ContextCompat.getDrawable(this@WorkflowActivity, R.drawable.bg_input_field_focused)
            isSingleLine = singleLine
            if (!singleLine) {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            doOnTextChanged { text, _, _, _ -> onChange(text?.toString().orEmpty()) }
        }

    private fun displayNameOf(uri: Uri): String {
        var name = uri.lastPathSegment ?: "file"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
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

    // ---- Rendering: "add a step" chips -------------------------------------------------------

    private fun renderAddStepOptions() {
        binding.addStepChipsContainer.removeAllViews()
        val kind = projectedKind()
        val options = StepKind.values().filter { kind in it.accepts }
        val groups = options.groupBy { it.category }

        for ((groupIndex, category) in StepCategory.values().withIndex()) {
            val stepsInGroup = groups[category] ?: continue

            binding.addStepChipsContainer.addView(TextView(this).apply {
                text = category.label.uppercase()
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_App_Eyebrow)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(if (groupIndex == 0) 2 else 14) }
            })

            var row: LinearLayout? = null
            for ((i, stepKind) in stepsInGroup.withIndex()) {
                if (i % 2 == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = dp(8) }
                    }
                    binding.addStepChipsContainer.addView(row)
                }
                // Within the export group the header already says "finish by exporting", so
                // "Export as CSV" on the chip itself would just repeat it - show "CSV" instead.
                // stepLabel itself stays untouched since the pipeline list and progress text
                // (elsewhere in this file) need the full "Export as ..." phrase to read clearly
                // on their own.
                val label = if (category == StepCategory.EXPORT) stepKind.stepLabel.removePrefix("Export as ") else stepKind.stepLabel
                val chip = TextView(this).apply {
                    text = "${stepKind.emoji}  $label"
                    setTextColor(colorOf(R.color.text_primary))
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    background = ContextCompat.getDrawable(this@WorkflowActivity, R.drawable.bg_chip_outline)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    alpha = if (isRunning) 0.4f else 1f
                    isClickable = !isRunning
                    isFocusable = !isRunning
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i % 2 == 0) marginEnd = dp(6) else marginStart = dp(6)
                    }
                    setOnClickListener { addStep(stepKind) }
                }
                row?.addView(chip)
            }
        }
    }

    // ---- Running the workflow -----------------------------------------------------------------

    private fun validateBeforeRun(): String? {
        for ((index, step) in steps.withIndex()) {
            val problem = when (step.kind) {
                StepKind.SCAN_IMAGES -> if (step.pickedUris.isEmpty()) "Step ${index + 1}: pick at least one photo" else null
                StepKind.LOAD_PDF -> if (step.pickedUri == null) "Step ${index + 1}: pick a PDF" else null
                StepKind.LOAD_SHEET -> if (step.pickedUri == null) "Step ${index + 1}: pick a file" else null
                StepKind.SCRAPE_URL -> if (step.textInput.isBlank()) "Step ${index + 1}: enter a URL" else null
                StepKind.PASTE_TEXT -> if (step.textInput.isBlank()) "Step ${index + 1}: paste some text" else null
                else -> null
            }
            if (problem != null) return problem
        }
        return null
    }

    private fun runWorkflow() {
        if (steps.isEmpty()) {
            Toast.makeText(this, "Add at least one step first", Toast.LENGTH_SHORT).show()
            return
        }
        val problem = validateBeforeRun()
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }

        setRunning(true)
        exportResults.clear()
        binding.resultsContainer.removeAllViews()
        binding.tvRunSummary.text = "Running…"
        binding.progressBar.max = steps.size
        binding.progressBar.progress = 0
        binding.tvProgressLabel.text = "Step 1 of ${steps.size} (0%)"

        lifecycleScope.launch {
            var current: WorkflowData = WorkflowData.Empty
            var okCount = 0
            var failCount = 0
            var skipCount = 0
            var stopped = false
            val start = System.currentTimeMillis()

            for ((index, step) in steps.withIndex()) {
                // Once something upstream has failed, the rest of the chain has nothing valid
                // to work with - running them anyway just produces a wall of confusing "needs
                // a table but got nothing" errors. Mark them SKIPPED instead and move on.
                if (stopped) {
                    step.status = StepStatus.SKIPPED
                    step.errorMessage = null
                    skipCount++
                    binding.progressBar.progress = index + 1
                    continue
                }

                step.status = StepStatus.RUNNING
                step.errorMessage = null
                renderSteps()
                binding.tvProgressLabel.text =
                    "Step ${index + 1} of ${steps.size} (${(index * 100) / steps.size}%) — ${step.kind.stepLabel}"
                try {
                    val result = WorkflowEngine.runStep(applicationContext, step, current)
                    step.status = StepStatus.SUCCESS
                    step.resultPreview = result.preview
                    current = result.data
                    okCount++
                    result.exportedFile?.let { file -> exportResults.add(ExportOutcome(step.kind, file)) }
                } catch (e: Throwable) {
                    // Throwable, not just Exception: a bad input file (e.g. a corrupt
                    // spreadsheet in a Load Excel/CSV step) can surface as a java.lang.Error,
                    // which would otherwise crash the whole app instead of just failing this step.
                    // A CancellationException is the one Throwable this must NOT treat as a
                    // failed step, though - it means lifecycleScope itself was cancelled (e.g.
                    // the user left this screen mid-run), and needs to keep propagating so the
                    // coroutine actually stops instead of plowing through the remaining steps.
                    if (e is CancellationException) throw e
                    step.status = StepStatus.FAILED
                    step.errorMessage = e.message ?: "Unknown error"
                    current = WorkflowData.Empty
                    failCount++
                    stopped = true
                }
                renderSteps()
                binding.progressBar.progress = index + 1
                binding.tvProgressLabel.text = "Step ${index + 1} of ${steps.size} (${((index + 1) * 100) / steps.size}%)"
            }
            if (skipCount > 0) renderSteps()

            val totalMs = System.currentTimeMillis() - start
            binding.tvRunSummary.text = "Finished in ${formatDuration(totalMs)}  •  $okCount step(s) succeeded" +
                (if (failCount > 0) ", $failCount failed" else "") +
                (if (skipCount > 0) ", $skipCount skipped" else "")

            renderResults()
            recordRun(okCount, failCount, totalMs)
            loadedWorkflowId?.let { id ->
                withContext(Dispatchers.IO) { db.savedWorkflowDao().markRun(id, System.currentTimeMillis()) }
                renderSavedWorkflows()
            }
            renderHistory()
            setRunning(false)
        }
    }

    private fun renderResults() {
        binding.resultsContainer.removeAllViews()
        if (exportResults.isEmpty()) return

        binding.resultsContainer.addView(TextView(this).apply {
            text = "FILES READY"
            setTextColor(colorOf(R.color.primary))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 11.5f
            letterSpacing = 0.06f
            setPadding(0, 0, 0, dp(2))
        })

        for (outcome in exportResults) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
            }
            row.addView(TextView(this).apply {
                text = "${outcome.kind.emoji}  ${outcome.file.name}"
                setTextColor(colorOf(R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "💾 Save As…"
                setTextColor(colorOf(R.color.primary))
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 12.5f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                isFocusable = true
                val outValue = TypedValue()
                this@WorkflowActivity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { launchSaveAs(outcome) }
            })
            binding.resultsContainer.addView(row)
        }
    }

    private fun launchSaveAs(outcome: ExportOutcome) {
        pendingExportFile = outcome.file
        when (outcome.kind) {
            StepKind.EXPORT_CSV -> saveCsvAs.launch(outcome.file.name)
            StepKind.EXPORT_XLSX -> saveXlsxAs.launch(outcome.file.name)
            StepKind.EXPORT_TXT -> saveTxtAs.launch(outcome.file.name)
            StepKind.EXPORT_PDF -> savePdfAs.launch(outcome.file.name)
            StepKind.EXPORT_DOCX -> saveDocxAs.launch(outcome.file.name)
            else -> { /* not an export step - nothing to save */ }
        }
    }

    private fun copyPendingExportTo(uri: Uri) {
        val file = pendingExportFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not open destination for writing")
                }
                Toast.makeText(this@WorkflowActivity, "Saved", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@WorkflowActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setRunning(running: Boolean) {
        isRunning = running
        binding.btnRunWorkflow.isEnabled = !running
        binding.btnResetWorkflow.isEnabled = !running
        binding.btnSaveWorkflow.isEnabled = !running
        binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
        binding.tvProgressLabel.visibility = if (running) View.VISIBLE else View.GONE
        renderSteps()
        renderAddStepOptions()
    }

    private fun formatDuration(ms: Long): String = if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)

    private suspend fun recordRun(okCount: Int, failCount: Int, totalMs: Long) {
        val label = "${steps.size}-step workflow"
        val preview = steps.joinToString(" → ") { step ->
            "${step.kind.emoji}${
                when (step.status) {
                    StepStatus.SUCCESS -> "✓"
                    StepStatus.FAILED -> "✗"
                    StepStatus.SKIPPED -> "⤼"
                    else -> "•"
                }
            }"
        }
        val quality = if (steps.isEmpty()) 0 else (okCount * 100 / steps.size)
        cache.record(
            feature = "WORKFLOW",
            inputText = steps.joinToString(",") { it.kind.name } + "_" + System.currentTimeMillis(),
            inputLabel = label,
            outputPreview = preview,
            outputPath = null,
            qualityScore = quality,
            status = if (failCount == 0) "SUCCESS" else if (okCount == 0) "FAILED" else "PARTIAL",
            durationMs = totalMs
        )
    }

    // ---- Saved workflows: persist / reload / delete a named step chain -----------------------

    private fun promptSaveWorkflow() {
        if (steps.isEmpty()) {
            Toast.makeText(this, "Add at least one step before saving", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "e.g. \"Scrape → Clean → Excel\""
            setPadding(dp(20), dp(16), dp(20), dp(4))
        }
        AlertDialog.Builder(this)
            .setTitle("Save this workflow")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Untitled workflow" }
                lifecycleScope.launch {
                    val id = withContext(Dispatchers.IO) {
                        db.savedWorkflowDao().insert(
                            SavedWorkflow(
                                name = name,
                                stepsJson = WorkflowStorage.encode(steps),
                                stepCount = steps.size
                            )
                        )
                    }
                    loadedWorkflowId = id
                    Toast.makeText(this@WorkflowActivity, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
                    renderSavedWorkflows()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderSavedWorkflows() {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { db.savedWorkflowDao().all() }
            binding.savedWorkflowsContainer.removeAllViews()
            binding.tvSavedEmptyState.visibility = if (saved.isEmpty()) View.VISIBLE else View.GONE
            for (workflow in saved) {
                binding.savedWorkflowsContainer.addView(buildSavedWorkflowRow(workflow))
            }
        }
    }

    private fun buildSavedWorkflowRow(workflow: SavedWorkflow): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@WorkflowActivity, R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        val steppedPreview = WorkflowStorage.previewOf(WorkflowStorage.decode(workflow.stepsJson))
        row.addView(TextView(this).apply {
            text = workflow.name
            setTextColor(colorOf(R.color.text_primary))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13.5f
        })
        row.addView(TextView(this).apply {
            val runInfo = workflow.lastRunAt?.let { "  •  last run ${dateLabel(it)}" } ?: ""
            text = "$steppedPreview  •  ${workflow.stepCount} step(s)  •  saved ${dateLabel(workflow.createdAt)}$runInfo"
            setTextColor(colorOf(R.color.text_secondary))
            textSize = 11.5f
            setPadding(0, dp(2), 0, 0)
        })
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        }
        actionsRow.addView(actionLabel("🗑 Delete", R.color.error) { deleteSavedWorkflow(workflow) })
        actionsRow.addView(actionLabel("↻ Load", R.color.primary) { loadSavedWorkflow(workflow) }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(16)
        })
        row.addView(actionsRow)
        return row
    }

    private fun actionLabel(label: String, colorRes: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(colorOf(colorRes))
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 12f
        isClickable = true
        isFocusable = true
        val outValue = TypedValue()
        this@WorkflowActivity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        setBackgroundResource(outValue.resourceId)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun loadSavedWorkflow(workflow: SavedWorkflow) {
        if (isRunning) {
            Toast.makeText(this, "Wait for the current run to finish first", Toast.LENGTH_SHORT).show()
            return
        }
        fun apply() {
            steps.clear()
            steps.addAll(WorkflowStorage.decode(workflow.stepsJson))
            loadedWorkflowId = workflow.id
            exportResults.clear()
            binding.resultsContainer.removeAllViews()
            binding.tvRunSummary.text = ""
            renderSteps()
            renderAddStepOptions()
            val needsPicks = steps.any {
                it.kind == StepKind.SCAN_IMAGES || it.kind == StepKind.LOAD_PDF || it.kind == StepKind.LOAD_SHEET
            }
            Toast.makeText(
                this,
                if (needsPicks) "Loaded \"${workflow.name}\" — pick any file(s)/photo(s) it needs, then Run."
                else "Loaded \"${workflow.name}\"",
                Toast.LENGTH_LONG
            ).show()
        }
        if (steps.isEmpty()) {
            apply()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Replace current pipeline?")
                .setMessage("Loading \"${workflow.name}\" will replace the steps you have now.")
                .setPositiveButton("Load") { _, _ -> apply() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun deleteSavedWorkflow(workflow: SavedWorkflow) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${workflow.name}\"?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.savedWorkflowDao().delete(workflow) }
                    if (loadedWorkflowId == workflow.id) loadedWorkflowId = null
                    renderSavedWorkflows()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Run history: this screen's own slice of the shared ProcessedItem log ----------------

    private fun renderHistory() {
        lifecycleScope.launch {
            val recent = withContext(Dispatchers.IO) { db.processedItemDao().recentByFeature("WORKFLOW", 10) }
            binding.historyContainer.removeAllViews()
            binding.tvHistoryEmptyState.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
            for (item in recent) {
                binding.historyContainer.addView(buildHistoryRow(item))
            }
        }
    }

    private fun buildHistoryRow(item: ProcessedItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = ContextCompat.getDrawable(this@WorkflowActivity, R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        headerRow.addView(TextView(this).apply {
            text = item.outputPreview.ifBlank { item.inputLabel }
            setTextColor(colorOf(R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = item.status
            setTextColor(colorOf(if (item.status == "SUCCESS") R.color.success else if (item.status == "FAILED") R.color.error else R.color.accent))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 10.5f
        })
        row.addView(headerRow)
        row.addView(TextView(this).apply {
            text = "${dateLabel(item.timestamp)}  •  ${formatDuration(item.durationMs)}"
            setTextColor(colorOf(R.color.text_secondary))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    private fun dateLabel(millis: Long): String =
        SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(millis))
}
