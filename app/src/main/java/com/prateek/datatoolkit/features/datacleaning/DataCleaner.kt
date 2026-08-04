package com.prateek.datatoolkit.features.datacleaning

import org.json.JSONArray
import org.json.JSONObject

/** Case-normalization modes, usable both globally and as a per-column override. */
enum class CaseMode { NONE, LOWER, UPPER, TITLE }

/** The formats Data Cleaning can read from and export to. */
enum class DataFormat { CSV, XLSX, JSON, TXT }

/** Data types Data Cleaning can auto-detect a column as, and validate its cells against. */
enum class ColumnType { TEXT, NUMBER, EMAIL, PHONE, URL, DATE, CURRENCY, BOOLEAN }

/** What to do with a cell that fails validation (bad email, unparseable date, out-of-range
 * number, ...). FLAG never touches the data - it only shows up in the report. BLANK clears
 * just that cell. REMOVE_ROW drops the whole row. Defaults to FLAG everywhere so nothing is
 * ever changed or removed unless the user opts in. */
enum class InvalidAction { FLAG, BLANK, REMOVE_ROW }

/** How blank cells are handled. NONE leaves them blank. FILL uses the column's (or the
 * global) fill value. FLAG counts them in the report without changing anything. REMOVE_ROW
 * drops any row with a blank in a required column (or any column, if none are named). */
enum class MissingValueStrategy { NONE, FILL, FLAG, REMOVE_ROW }

/**
 * Per-column overrides. Any null field falls back to the matching global [CleaningOptions]
 * value, so a column only needs to specify what makes it different from the rest of the table.
 */
data class ColumnRule(
    val skip: Boolean = false, // leave this column completely untouched by every cleaning step
    val standardizeCase: CaseMode? = null,
    val fillMissingWith: String? = null,
    val expectedType: ColumnType? = null, // explicit type for validation; null = auto-detect
    val invalidAction: InvalidAction? = null, // overrides the global invalidAction for this column
    val numericMin: Double? = null, // overrides the built-in name-based range heuristic (e.g. "age")
    val numericMax: Double? = null
)

/** A user-defined search/replace rule, scoped to one column or every column. */
data class ReplaceRule(
    val find: String,
    val replaceWith: String,
    val isRegex: Boolean = false,
    val ignoreCase: Boolean = false,
    val column: String? = null // null = every column
)

/** A cell whose value didn't match its column's expected/detected type, or failed a
 * validation rule (out-of-range number, unparseable date, ...). */
data class InvalidCell(
    val rowIndex: Int, // index into the original input rows, before any cleaning
    val column: String,
    val value: String,
    val expectedType: ColumnType,
    val reason: String = ""
)

/** Options the user can toggle in the Data Cleaning screen. */
data class CleaningOptions(
    val trimWhitespace: Boolean = true,
    val collapseInnerSpaces: Boolean = true,
    val removeDuplicateRows: Boolean = true,
    val standardizeCase: CaseMode = CaseMode.NONE,
    val removeEmptyRows: Boolean = true,
    val removeUnwantedChars: Boolean = false,
    val fillMissingWith: String? = null, // used when missingValueStrategy == FILL

    // Smarter duplicate detection: empty = compare the whole row; otherwise only these
    // column(s) decide whether two rows are "the same". Three passes run in order when
    // removeDuplicateRows is on: exact match, then (if fuzzyDedupe) trim+lowercase-normalized
    // match, then (if fuzzySimilarityDedupe) Levenshtein-similarity near-duplicate match.
    val dedupeKeyColumns: List<String> = emptyList(),
    val fuzzyDedupe: Boolean = false,
    val fuzzySimilarityDedupe: Boolean = false,
    val fuzzySimilarityThreshold: Double = 0.92,

    // Per-column overrides, keyed by header name.
    val columnRules: Map<String, ColumnRule> = emptyMap(),

    // User-defined find/replace cleanup, applied first, per cell.
    val replaceRules: List<ReplaceRule> = emptyList(),

    // Type detection & validation: an explicit ColumnRule.expectedType overrides auto-detection.
    // Used both to decide which semantic cleaner (below) applies to a column, and to flag any
    // remaining type mismatches (currently just URL, the one type without its own cleaner).
    val flagInvalidCells: Boolean = true,

    // Semantic cleaning toggles - each defaults on, and each only ever touches columns whose
    // detected/declared type matches (an EMAIL column is never touched by phone rules, etc).
    val normalizeEmails: Boolean = true,
    val normalizePhones: Boolean = true,
    val standardizeDates: Boolean = true,
    val dateOrderHint: DateOrderHint = DateOrderHint.AUTO,
    val cleanCurrency: Boolean = true,
    val validateNumericRanges: Boolean = true,
    val normalizeBooleans: Boolean = true,
    val booleanOutputFormat: BooleanFormat = BooleanFormat.YES_NO,

    // What happens to a cell/row that fails validation (see [InvalidAction]). Per-column
    // ColumnRule.invalidAction overrides this for that column.
    val invalidAction: InvalidAction = InvalidAction.FLAG,

    // How blank cells are handled (see [MissingValueStrategy]). requiredColumns scopes
    // REMOVE_ROW to specific columns; empty = any column counts.
    val missingValueStrategy: MissingValueStrategy = MissingValueStrategy.FILL,
    val requiredColumns: List<String> = emptyList()
)

data class CleaningReport(
    val rowsIn: Int,
    val rowsOut: Int,
    val duplicatesRemoved: Int, // exact + normalized-fuzzy + similarity-fuzzy, combined
    val emptyRowsRemoved: Int,
    val cellsFilled: Int,

    // Duplicates (breakdown)
    val exactDuplicatesRemoved: Int = 0,
    val fuzzyDuplicatesRemoved: Int = 0, // normalized-equality + similarity matches, combined
    val similarityDedupeSkipped: Boolean = false, // true if the table was too large to run the O(n^2) pass

    val unwantedCharsRemoved: Int = 0,
    val replacementsMade: Int = 0,
    val detectedTypes: Map<String, ColumnType> = emptyMap(),
    val invalidCells: List<InvalidCell> = emptyList(),

    // Semantic cleaning results
    val invalidEmails: Int = 0,
    val invalidPhones: Int = 0,
    val phonesNormalized: Int = 0,
    val datesStandardized: Int = 0,
    val datesUnparseable: Int = 0,
    val currencyCellsCleaned: Int = 0,
    val invalidNumericValues: Int = 0,
    val booleansNormalized: Int = 0,

    // Missing values
    val missingValuesFound: Int = 0,
    val missingValuesFilled: Int = 0,
    val missingValuesFlagged: Int = 0,
    val rowsRemovedForMissingValues: Int = 0,
    val rowsRemovedForInvalidValues: Int = 0,

    // Human-readable per-cell change log, "Row N, Column: "before" -> "after" (reason)",
    // capped at MAX_CHANGE_LOG_ENTRIES so a huge table doesn't blow up memory/UI.
    val changeLog: List<String> = emptyList()
)

/** Lightweight, dependency-free detection/validation for common column data types. */
object TypeDetector {
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val URL_REGEX = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val DATE_REGEX = Regex(
        "^\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}$" +
            "|^\\d{1,2}[\\s-]+[A-Za-z]{3,9}[\\s,-]+\\d{2,4}$" +
            "|^[A-Za-z]{3,9}[\\s,]+\\d{1,2},?\\s+\\d{2,4}$",
        RegexOption.IGNORE_CASE
    )
    private val NUMBER_REGEX = Regex("^-?\\d+([.,]\\d+)?$")
    private val PHONE_REGEX = Regex("^\\+?[\\d\\s().-]{7,15}$")
    private val CURRENCY_REGEX = Regex(
        "^\\(?[₹$€£]\\s?-?\\d[\\d,]*(\\.\\d+)?\\)?$" +
            "|^\\(?-?\\d[\\d,]*(\\.\\d+)?\\)?\\s?(INR|USD|EUR|GBP|Rs\\.?)$",
        RegexOption.IGNORE_CASE
    )
    private val BOOLEAN_REGEX = Regex("^(yes|no|y|n|true|false|t|f)$", RegexOption.IGNORE_CASE)

    // Order matters: more specific patterns are checked before very permissive ones (e.g. a
    // date or boolean value should never get misread as a generic "number" or "phone").
    private val CHECKS = listOf(
        ColumnType.EMAIL to EMAIL_REGEX,
        ColumnType.URL to URL_REGEX,
        ColumnType.DATE to DATE_REGEX,
        ColumnType.BOOLEAN to BOOLEAN_REGEX,
        ColumnType.CURRENCY to CURRENCY_REGEX,
        ColumnType.NUMBER to NUMBER_REGEX,
        ColumnType.PHONE to PHONE_REGEX
    )

    /** Samples up to 50 non-blank values and picks the type at least 80% of them match. */
    fun detect(values: List<String>): ColumnType {
        val sample = values.map { it.trim() }.filter { it.isNotEmpty() }.take(50)
        if (sample.isEmpty()) return ColumnType.TEXT
        val best = CHECKS
            .map { (type, regex) -> type to sample.count { regex.matches(it) }.toDouble() / sample.size }
            .filter { it.second >= 0.8 }
            .maxByOrNull { it.second }
        return best?.first ?: ColumnType.TEXT
    }

    fun matches(type: ColumnType, value: String): Boolean = when (type) {
        ColumnType.TEXT -> true
        ColumnType.EMAIL -> EMAIL_REGEX.matches(value.trim())
        ColumnType.URL -> URL_REGEX.matches(value.trim())
        ColumnType.DATE -> DATE_REGEX.matches(value.trim())
        ColumnType.NUMBER -> NUMBER_REGEX.matches(value.trim())
        ColumnType.PHONE -> PHONE_REGEX.matches(value.trim())
        ColumnType.CURRENCY -> CURRENCY_REGEX.matches(value.trim())
        ColumnType.BOOLEAN -> BOOLEAN_REGEX.matches(value.trim())
    }
}

/**
 * Data Cleaning & Processing: operates on a simple table (list of rows, each a list of cell
 * strings) so the same pipeline works no matter which format (CSV, Excel, JSON, or plain TXT)
 * the data originally came from.
 *
 * Guiding rule throughout: never guess-and-overwrite. Every normalizer either produces a
 * value it's confident about, or leaves the original cell untouched and reports the problem -
 * so a messy/ambiguous value gets flagged for a human to look at instead of being silently
 * corrupted into something wrong.
 */
object DataCleaner {

    // Characters kept when "remove unwanted characters" is on: letters, digits, whitespace,
    // and everyday punctuation. Everything else (control chars, stray symbols, mojibake) is
    // stripped. Matches the printable-character notion QualityScorer already uses.
    private const val ALLOWED_PUNCTUATION = ".,;:!?()'\"-_/@%&$#*+"

    private const val MAX_CHANGE_LOG_ENTRIES = 500

    // Similarity-based near-duplicate detection is O(n^2) string comparisons; above this many
    // surviving rows it's skipped (reported via CleaningReport.similarityDedupeSkipped) rather
    // than risk freezing the UI on a large table.
    private const val MAX_SIMILARITY_DEDUPE_ROWS = 2000

    /** A row plus its position in the original input - carried through every stage so the
     * report can always say which source row a change/removal/flag came from, even after
     * earlier stages have removed other rows. */
    private data class WorkRow(val originalIndex: Int, val cells: MutableList<String>)

    fun clean(rows: List<List<String>>, options: CleaningOptions): Pair<List<List<String>>, CleaningReport> {
        if (rows.isEmpty()) return rows to CleaningReport(0, 0, 0, 0, 0)
        val header = rows.first()
        val rawBody = rows.drop(1)
        val rowsIn = rawBody.size
        if (rowsIn == 0) return rows to CleaningReport(0, 0, 0, 0, 0)

        fun ruleFor(colIndex: Int): ColumnRule? = header.getOrNull(colIndex)?.let { options.columnRules[it] }
        fun invalidActionFor(colIndex: Int): InvalidAction = ruleFor(colIndex)?.invalidAction ?: options.invalidAction

        // --- Stage 0: type detection, on the ORIGINAL values, before anything is touched -----
        // (an explicit ColumnRule.expectedType always wins over auto-detection)
        val detectedTypes = LinkedHashMap<String, ColumnType>()
        for (c in header.indices) {
            val colName = header[c]
            val rule = ruleFor(c)
            detectedTypes[colName] = when {
                rule?.skip == true -> ColumnType.TEXT
                rule?.expectedType != null -> rule.expectedType
                else -> TypeDetector.detect(rawBody.map { it.getOrElse(c) { "" } })
            }
        }

        var work: MutableList<WorkRow> = rawBody.mapIndexed { i, row -> WorkRow(i, row.toMutableList()) }.toMutableList()

        val changeLog = mutableListOf<String>()
        val invalidCells = mutableListOf<InvalidCell>()
        var cellsFilled = 0
        var unwantedCharsRemoved = 0
        var replacementsMade = 0
        var phonesNormalized = 0
        var datesStandardized = 0
        var datesUnparseable = 0
        var currencyCellsCleaned = 0
        var booleansNormalized = 0
        var invalidEmails = 0
        var invalidPhones = 0
        var invalidNumericValues = 0
        var missingValuesFound = 0
        var missingValuesFilled = 0
        var missingValuesFlagged = 0
        val rowsToRemoveForInvalid = HashSet<Int>()
        val rowsToRemoveForMissing = HashSet<Int>()

        fun logChange(originalIndex: Int, column: String, before: String, after: String, reason: String) {
            if (before != after && changeLog.size < MAX_CHANGE_LOG_ENTRIES) {
                changeLog += "Row ${originalIndex + 1}, $column: \"$before\" \u2192 \"$after\" ($reason)"
            }
        }

        fun markInvalid(originalIndex: Int, colIndex: Int, column: String, value: String, type: ColumnType, reason: String) {
            invalidCells += InvalidCell(originalIndex, column, value, type, reason)
            if (invalidActionFor(colIndex) == InvalidAction.REMOVE_ROW) rowsToRemoveForInvalid += originalIndex
        }

        // --- Stage 1: per-cell pass - replace rules, trim/collapse/unwanted/case, then the
        //     semantic cleaner matching that column's type ------------------------------------
        for (wr in work) {
            for (c in header.indices) {
                val rule = ruleFor(c)
                if (rule?.skip == true) continue
                val colName = header[c]
                val type = detectedTypes[colName] ?: ColumnType.TEXT
                var value = wr.cells.getOrElse(c) { "" }

                for (rr in options.replaceRules) {
                    if (rr.column != null && rr.column != colName) continue
                    val before = value
                    value = if (rr.isRegex) {
                        val opts = if (rr.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                        runCatching { value.replace(Regex(rr.find, opts), rr.replaceWith) }.getOrDefault(value)
                    } else {
                        value.replace(rr.find, rr.replaceWith, ignoreCase = rr.ignoreCase)
                    }
                    if (value != before) replacementsMade++
                }

                if (options.removeUnwantedChars) {
                    val before = value.length
                    value = value.filter { it.isLetterOrDigit() || it.isWhitespace() || it in ALLOWED_PUNCTUATION }
                    unwantedCharsRemoved += before - value.length
                }
                if (options.trimWhitespace) value = value.trim()
                if (options.collapseInnerSpaces) value = value.replace(Regex("\\s+"), " ")

                // Free-text case standardization only applies to plain TEXT columns - changing
                // the case of an email/date/currency/etc. could change its meaning or break
                // matching, so those are left to their own dedicated normalizer below instead.
                if (type == ColumnType.TEXT) {
                    value = applyCase(value, rule?.standardizeCase ?: options.standardizeCase)
                }

                if (value.isNotBlank()) {
                    when (type) {
                        ColumnType.EMAIL -> if (options.normalizeEmails) {
                            val cleaned = value.trim().lowercase()
                            if (TypeDetector.matches(ColumnType.EMAIL, cleaned)) {
                                if (cleaned != value) logChange(wr.originalIndex, colName, value, cleaned, "email normalized")
                                value = cleaned
                            } else {
                                invalidEmails++
                                markInvalid(wr.originalIndex, c, colName, value, type, "invalid email format")
                                if (invalidActionFor(c) == InvalidAction.BLANK) {
                                    logChange(wr.originalIndex, colName, value, "", "invalid email blanked")
                                    value = ""
                                }
                            }
                        }
                        ColumnType.PHONE -> if (options.normalizePhones) {
                            val normalized = PhoneNormalizer.normalize(value)
                            if (normalized != null) {
                                if (normalized != value) {
                                    phonesNormalized++
                                    logChange(wr.originalIndex, colName, value, normalized, "phone normalized to 10 digits")
                                }
                                value = normalized
                            } else {
                                invalidPhones++
                                markInvalid(wr.originalIndex, c, colName, value, type, "not a valid 10-digit phone number")
                                if (invalidActionFor(c) == InvalidAction.BLANK) {
                                    logChange(wr.originalIndex, colName, value, "", "invalid phone blanked")
                                    value = ""
                                }
                            }
                        }
                        ColumnType.DATE -> if (options.standardizeDates) {
                            val normalized = DateNormalizer.normalize(value, options.dateOrderHint)
                            if (normalized != null) {
                                if (normalized != value) {
                                    datesStandardized++
                                    logChange(wr.originalIndex, colName, value, normalized, "date standardized to YYYY-MM-DD")
                                }
                                value = normalized
                            } else {
                                datesUnparseable++
                                markInvalid(wr.originalIndex, c, colName, value, type, "unrecognized date format")
                                if (invalidActionFor(c) == InvalidAction.BLANK) {
                                    logChange(wr.originalIndex, colName, value, "", "unparseable date blanked")
                                    value = ""
                                }
                            }
                        }
                        ColumnType.CURRENCY -> if (options.cleanCurrency) {
                            val normalized = CurrencyNormalizer.normalize(value)
                            if (normalized != null) {
                                if (normalized != value) {
                                    currencyCellsCleaned++
                                    logChange(wr.originalIndex, colName, value, normalized, "currency cleaned to numeric")
                                }
                                value = normalized
                            } else {
                                invalidNumericValues++
                                markInvalid(wr.originalIndex, c, colName, value, type, "not a valid currency amount")
                            }
                        }
                        ColumnType.BOOLEAN -> if (options.normalizeBooleans) {
                            val normalized = BooleanNormalizer.normalize(value, options.booleanOutputFormat)
                            if (normalized != null) {
                                if (normalized != value) {
                                    booleansNormalized++
                                    logChange(wr.originalIndex, colName, value, normalized, "boolean normalized")
                                }
                                value = normalized
                            } else {
                                markInvalid(wr.originalIndex, c, colName, value, type, "unrecognized boolean value")
                            }
                        }
                        else -> {}
                    }

                    // Numeric range validation: NUMBER columns directly, and CURRENCY columns
                    // against their already-cleaned numeric value (so "-500" salary still gets
                    // caught). Only applies where a range is known - see numericRangeFor().
                    if ((type == ColumnType.NUMBER || type == ColumnType.CURRENCY) && options.validateNumericRanges) {
                        val num = value.toDoubleOrNull()
                        if (num != null) {
                            val (min, max) = numericRangeFor(colName, rule)
                            if ((min != null && num < min) || (max != null && num > max)) {
                                invalidNumericValues++
                                val range = "${min?.let { fmt(it) } ?: "-\u221e"}..${max?.let { fmt(it) } ?: "\u221e"}"
                                markInvalid(wr.originalIndex, c, colName, value, ColumnType.NUMBER, "out of expected range ($range)")
                            }
                        }
                    }
                }

                wr.cells[c] = value
            }
        }

        // --- Stage 2: missing values (blank cells) --------------------------------------------
        for (wr in work) {
            for (c in header.indices) {
                val rule = ruleFor(c)
                if (rule?.skip == true) continue
                if (wr.cells.getOrElse(c) { "" }.isNotBlank()) continue
                missingValuesFound++
                when (options.missingValueStrategy) {
                    MissingValueStrategy.FILL -> {
                        val fill = rule?.fillMissingWith ?: options.fillMissingWith
                        if (fill != null) {
                            wr.cells[c] = fill
                            cellsFilled++; missingValuesFilled++
                        }
                    }
                    MissingValueStrategy.FLAG -> missingValuesFlagged++
                    MissingValueStrategy.REMOVE_ROW -> {
                        val colName = header.getOrNull(c)
                        if (options.requiredColumns.isEmpty() || options.requiredColumns.contains(colName)) {
                            rowsToRemoveForMissing += wr.originalIndex
                        }
                    }
                    MissingValueStrategy.NONE -> {}
                }
            }
        }

        // --- Stage 3: remove fully empty rows (structural - every cell blank) -----------------
        var emptyRemoved = 0
        if (options.removeEmptyRows) {
            val before = work.size
            work = work.filter { wr -> wr.cells.any { it.isNotBlank() } }.toMutableList()
            emptyRemoved = before - work.size
        }

        // --- Stage 4: remove rows marked for removal by invalid-value / missing-value rules ---
        val presentIds = work.map { it.originalIndex }.toHashSet()
        val invalidToRemove = rowsToRemoveForInvalid.filterTo(HashSet()) { it in presentIds }
        val missingToRemove = rowsToRemoveForMissing.filterTo(HashSet()) { it in presentIds }
        val rowsRemovedForInvalid = invalidToRemove.size
        val rowsRemovedForMissing = (missingToRemove - invalidToRemove).size
        if (invalidToRemove.isNotEmpty() || missingToRemove.isNotEmpty()) {
            work = work.filter { it.originalIndex !in invalidToRemove && it.originalIndex !in missingToRemove }.toMutableList()
        }

        // --- Stage 5: de-duplication - exact, then normalized-fuzzy, then similarity-fuzzy ----
        var exactDuplicatesRemoved = 0
        var normalizedDuplicatesRemoved = 0
        var similarityDuplicatesRemoved = 0
        var similarityDedupeSkipped = false
        if (options.removeDuplicateRows) {
            val keyIndices = options.dedupeKeyColumns.mapNotNull { name -> header.indexOf(name).takeIf { it >= 0 } }
            fun rawKey(wr: WorkRow): List<String> = if (keyIndices.isNotEmpty()) keyIndices.map { wr.cells.getOrElse(it) { "" } } else wr.cells
            fun normKey(wr: WorkRow): List<String> = rawKey(wr).map { it.trim().lowercase() }

            run {
                val before = work.size
                val seen = HashSet<List<String>>()
                work = work.filter { seen.add(rawKey(it)) }.toMutableList()
                exactDuplicatesRemoved = before - work.size
            }

            if (options.fuzzyDedupe) {
                val before = work.size
                val seen = HashSet<List<String>>()
                work = work.filter { seen.add(normKey(it)) }.toMutableList()
                normalizedDuplicatesRemoved = before - work.size
            }

            if (options.fuzzySimilarityDedupe) {
                if (work.size > MAX_SIMILARITY_DEDUPE_ROWS) {
                    similarityDedupeSkipped = true
                } else {
                    val before = work.size
                    val kept = ArrayList<WorkRow>(work.size)
                    val keptKeys = ArrayList<String>(work.size)
                    for (wr in work) {
                        val key = normKey(wr).joinToString(" ")
                        val isDup = keptKeys.any { StringSimilarity.ratio(it, key) >= options.fuzzySimilarityThreshold }
                        if (!isDup) { kept += wr; keptKeys += key }
                    }
                    work = kept
                    similarityDuplicatesRemoved = before - work.size
                }
            }
        }

        // --- Stage 6: URL validation - the one detectable type without its own semantic cleaner
        if (options.flagInvalidCells) {
            for (c in header.indices) {
                val rule = ruleFor(c)
                if (rule?.skip == true) continue
                val colName = header[c]
                if (detectedTypes[colName] != ColumnType.URL) continue
                for (wr in work) {
                    val v = wr.cells.getOrElse(c) { "" }
                    if (v.isNotBlank() && !TypeDetector.matches(ColumnType.URL, v)) {
                        invalidCells += InvalidCell(wr.originalIndex, colName, v, ColumnType.URL, "invalid URL format")
                    }
                }
            }
        }

        val resultBody = work.map { it.cells.toList() }
        val result = listOf(header) + resultBody
        return result to CleaningReport(
            rowsIn = rowsIn,
            rowsOut = resultBody.size,
            duplicatesRemoved = exactDuplicatesRemoved + normalizedDuplicatesRemoved + similarityDuplicatesRemoved,
            exactDuplicatesRemoved = exactDuplicatesRemoved,
            fuzzyDuplicatesRemoved = normalizedDuplicatesRemoved + similarityDuplicatesRemoved,
            similarityDedupeSkipped = similarityDedupeSkipped,
            emptyRowsRemoved = emptyRemoved,
            cellsFilled = cellsFilled,
            unwantedCharsRemoved = unwantedCharsRemoved,
            replacementsMade = replacementsMade,
            detectedTypes = detectedTypes,
            invalidCells = invalidCells,
            invalidEmails = invalidEmails,
            invalidPhones = invalidPhones,
            phonesNormalized = phonesNormalized,
            datesStandardized = datesStandardized,
            datesUnparseable = datesUnparseable,
            currencyCellsCleaned = currencyCellsCleaned,
            invalidNumericValues = invalidNumericValues,
            booleansNormalized = booleansNormalized,
            missingValuesFound = missingValuesFound,
            missingValuesFilled = missingValuesFilled,
            missingValuesFlagged = missingValuesFlagged,
            rowsRemovedForMissingValues = rowsRemovedForMissing,
            rowsRemovedForInvalidValues = rowsRemovedForInvalid,
            changeLog = changeLog
        )
    }

    /** Built-in range heuristics by column name (e.g. "Age" -> 0..120), overridable per-column
     * via ColumnRule.numericMin/Max. Returns (null, null) - i.e. no check - for columns that
     * don't match any known pattern, so arbitrary numeric columns aren't flagged by surprise. */
    private fun numericRangeFor(columnName: String, rule: ColumnRule?): Pair<Double?, Double?> {
        if (rule?.numericMin != null || rule?.numericMax != null) return rule.numericMin to rule.numericMax
        val name = columnName.lowercase()
        return when {
            "age" in name -> 0.0 to 120.0
            "salary" in name || "income" in name || "wage" in name || "price" in name ||
                "amount" in name || "cost" in name || "fee" in name || "revenue" in name -> 0.0 to null
            "percent" in name || "%" in name -> 0.0 to 100.0
            "quantity" in name || "qty" in name || "count" in name || "stock" in name -> 0.0 to null
            else -> null to null
        }
    }

    private fun fmt(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    private fun applyCase(value: String, mode: CaseMode): String = when (mode) {
        CaseMode.LOWER -> value.lowercase()
        CaseMode.UPPER -> value.uppercase()
        CaseMode.TITLE -> value.split(" ").joinToString(" ") { w ->
            if (w.isEmpty()) w else w[0].uppercase() + w.drop(1).lowercase()
        }
        CaseMode.NONE -> value
    }

    // --- CSV ---------------------------------------------------------------------------------

    /** Parses raw pasted/loaded text as CSV (comma-separated, quote-aware for simple cases). */
    fun parseCsvText(text: String): List<List<String>> =
        text.lines().filter { it.isNotEmpty() }.map { line -> splitCsvLine(line) }

    fun toCsvText(rows: List<List<String>>): String =
        rows.joinToString("\n") { row -> row.joinToString(",") { escapeCsv(it) } }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(current.toString()); current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun escapeCsv(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            "\"" + value.replace("\"", "\"\"") + "\""
        else value

    // --- JSON (array of flat objects) ---------------------------------------------------------

    /** Parses a JSON array of objects into a table - column headers are the union of all keys seen. */
    fun parseJsonText(text: String): List<List<String>> {
        val arr = JSONArray(text)
        if (arr.length() == 0) return emptyList()

        val objects = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val keys = linkedSetOf<String>()
        objects.forEach { obj -> obj.keys().forEach { keys.add(it) } }
        val header = keys.toList()

        val rows = objects.map { obj ->
            header.map { key -> if (obj.has(key) && !obj.isNull(key)) obj.get(key).toString() else "" }
        }
        return listOf(header) + rows
    }

    /** Serializes a table back into a JSON array of objects, keyed by the header row. */
    fun toJsonText(rows: List<List<String>>): String {
        if (rows.isEmpty()) return "[]"
        val header = rows.first()
        val arr = JSONArray()
        rows.drop(1).forEach { row ->
            val obj = JSONObject()
            header.forEachIndexed { i, key -> obj.put(key, row.getOrElse(i) { "" }) }
            arr.put(obj)
        }
        return arr.toString(2)
    }

    // --- TXT (plain lines, no delimiter assumed) ------------------------------------------------

    /** Treats each non-empty line as one row in a single "Text" column. */
    fun parseTxtText(text: String): List<List<String>> =
        listOf(listOf("Text")) + text.lines().filter { it.isNotEmpty() }.map { listOf(it) }

    /** Generic plain-text export: tab-separated so multi-column tables stay readable. */
    fun toTxtText(rows: List<List<String>>): String =
        rows.joinToString("\n") { row -> row.joinToString("\t") }

    // --- Format-aware helpers ------------------------------------------------------------------

    fun parse(text: String, format: DataFormat): List<List<String>> = when (format) {
        DataFormat.CSV -> parseCsvText(text)
        DataFormat.JSON -> parseJsonText(text)
        DataFormat.TXT -> parseTxtText(text)
        DataFormat.XLSX -> emptyList() // XLSX is binary - read via ExcelCsvHelper.readXlsx instead
    }

    fun serialize(rows: List<List<String>>, format: DataFormat): String = when (format) {
        DataFormat.CSV -> toCsvText(rows)
        DataFormat.JSON -> toJsonText(rows)
        DataFormat.TXT -> toTxtText(rows)
        DataFormat.XLSX -> toCsvText(rows) // XLSX is binary - write via ExcelCsvHelper.writeXlsx instead
    }

    /** Guesses a format from a file name's extension; defaults to CSV. */
    fun formatFromFileName(name: String): DataFormat = when (name.substringAfterLast('.', "").lowercase()) {
        "xlsx", "xls" -> DataFormat.XLSX
        "json" -> DataFormat.JSON
        "txt" -> DataFormat.TXT
        else -> DataFormat.CSV
    }
}
