package com.prateek.datatoolkit.features.excel.sheet.formula

import com.prateek.datatoolkit.core.math.MathEngine
import com.prateek.datatoolkit.features.excel.sheet.CellRef
import com.prateek.datatoolkit.features.excel.sheet.ComputedResult
import com.prateek.datatoolkit.features.excel.sheet.SheetData

/**
 * Keeps every formula cell in a [SheetData] up to date as the user edits: recomputes exactly
 * the cells an edit could affect - the edited cell itself, plus anything that (transitively)
 * depends on it - in dependency order, and turns a formula that depends on itself into a
 * #CIRCULAR! error instead of an infinite loop. A plain non-formula cell never needs any of
 * this; only formula cells ever get a [ComputedResult].
 *
 * One recalculator instance is meant to live alongside its [SheetData] for as long as that
 * sheet is being edited (see the grid Activity) - not recreated per keystroke - since re-running
 * [recalculateAll] on every edit would be needless work on a large sheet.
 */
class SheetRecalculator(private val sheet: SheetData) {

    /** Raw typed result per formula cell, valid only *during* a single [recalculateAll]/
     *  [recalculate] pass - this is what lets `C1 = B1 + 1` see B1's actual number rather than
     *  re-parsing display text back into a number, and lets an error propagate as an error
     *  (rather than being mistaken for ordinary text) when a cell depends on one that's
     *  currently erroring. Cleared at the start of every pass since which cells even count as
     *  "upstream" of the edited one can itself change edit to edit. */
    private val typedResults = HashMap<CellRef, FormulaValue>()
    private val inProgress = HashSet<CellRef>()

    /** Recomputes every formula cell in the sheet from scratch - the right call right after
     *  opening a file, where every formula cell is new to this session. */
    fun recalculateAll() {
        typedResults.clear()
        inProgress.clear() // see the note on inProgress.remove(ref) in resolve() - this is a defensive backstop, not the primary guarantee
        formulaCellRefs().forEach { resolve(it) }
    }

    /** Recomputes [edited] and everything that (transitively) depends on it - the normal path
     *  after a single cell edit, so a big sheet doesn't re-run every formula on every
     *  keystroke. Safe to call even when [edited] itself isn't a formula cell (e.g. a plain
     *  number was typed) - it just means only the *dependents* actually get recomputed. */
    fun recalculate(edited: CellRef) {
        typedResults.clear()
        inProgress.clear()
        val affected = findDependents(edited) + edited
        affected.forEach { if (sheet.existingCellAt(it)?.isFormula == true) resolve(it) }
    }

    private fun formulaCellRefs(): List<CellRef> =
        sheet.allCells().filterValues { it.isFormula }.keys.toList()

    /** Every formula cell whose formula (directly, or through a chain of other formula cells)
     *  mentions [target] - e.g. if B1 references A1 and C1 references B1, both are dependents
     *  of A1. Deliberately simple - re-scans every formula cell's text on each call rather than
     *  maintaining a live reverse-dependency index - since this app's sheets are small enough
     *  that this stays effectively instant; a production spreadsheet engine would cache this. */
    private fun findDependents(target: CellRef): Set<CellRef> {
        val found = mutableSetOf<CellRef>()
        val frontier = ArrayDeque(listOf(target))
        val visited = mutableSetOf(target)
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            for ((ref, cell) in sheet.allCells()) {
                if (cell.isFormula && ref !in visited && referencesCell(cell.input, current)) {
                    found.add(ref)
                    visited.add(ref)
                    frontier.add(ref)
                }
            }
        }
        return found
    }

    private fun referencesCell(formulaInput: String, ref: CellRef): Boolean =
        try {
            referencedCells(formulaInput).contains(ref)
        } catch (_: Exception) {
            false // an unparsable formula can't meaningfully reference anything
        }

    /** Every cell a formula's expression mentions, including every cell inside any range it
     *  uses. Shared between dependency-tracking above and would-be circular-ref reasoning, so
     *  both agree on what "references" means. */
    private fun referencedCells(formulaInput: String): Set<CellRef> {
        val ast = parseExpression(formulaInput)
        val refs = mutableSetOf<CellRef>()
        fun walk(node: FormulaNode) {
            when (node) {
                is FormulaNode.CellRefNode -> refs.add(node.ref)
                is FormulaNode.RangeNode -> refs.addAll(node.range.cells())
                is FormulaNode.UnaryOp -> walk(node.operand)
                is FormulaNode.BinaryOp -> { walk(node.left); walk(node.right) }
                is FormulaNode.FunctionCall -> node.args.forEach { walk(it) }
                else -> {}
            }
        }
        walk(ast)
        return refs
    }

    private fun parseExpression(formulaInput: String): FormulaNode =
        FormulaParser(FormulaLexer(formulaInput.removePrefix("=")).tokenize()).parse()

    /** Resolves [ref] to a [FormulaValue] - for a formula cell, this means resolving whatever
     *  it depends on first (recursively, via [resolveForLookup]), which is the actual
     *  "evaluate in dependency order" step. Caches into [typedResults] and writes the
     *  display-facing [ComputedResult] onto the cell itself as a side effect. */
    private fun resolve(ref: CellRef): FormulaValue {
        typedResults[ref]?.let { return it }
        val cell = sheet.existingCellAt(ref) ?: return FormulaValue.EmptyValue
        if (!cell.isFormula) return literalValue(cell.input)

        if (ref in inProgress) {
            val circular = FormulaValue.ErrorValue("#CIRCULAR!")
            typedResults[ref] = circular
            cell.computed = toComputedResult(circular)
            return circular
        }

        inProgress.add(ref)
        // try/finally, not just try/catch: inProgress.remove(ref) must run even if something
        // *uncaught* escapes the try block, or that ref stays wrongly marked in-progress for
        // the rest of this SheetRecalculator's life - turning every future formula that
        // touches it into a false #CIRCULAR!, long after whatever actually went wrong here.
        val result = try {
            try {
                FormulaEvaluator(lookup = ::resolveForLookup).evaluate(parseExpression(cell.input))
            } catch (e: FormulaSyntaxException) {
                FormulaValue.ErrorValue("#NAME?")
            } catch (e: Throwable) {
                // Throwable, not just Exception: malformed/adversarial formula text can surface
                // as a java.lang.Error (e.g. StackOverflowError from a pathologically deep
                // parenthesization) rather than a normal Exception - same reasoning
                // ExcelCsvActivity.loadXlsx already applies to a bad spreadsheet file.
                FormulaValue.ErrorValue("#VALUE!")
            }
        } finally {
            inProgress.remove(ref)
        }

        typedResults[ref] = result
        cell.computed = toComputedResult(result)
        return result
    }

    /** What a referenced cell resolves to for another formula's use: a plain value cell parses
     *  its text as a literal, a formula cell resolves (recursively, via [resolve]) to its own
     *  result, and a cell nothing has ever touched is simply empty. */
    private fun resolveForLookup(ref: CellRef): FormulaValue {
        val cell = sheet.existingCellAt(ref) ?: return FormulaValue.EmptyValue
        return if (cell.isFormula) resolve(ref) else literalValue(cell.input)
    }

    private fun literalValue(input: String): FormulaValue = when {
        input.isBlank() -> FormulaValue.EmptyValue
        input.equals("TRUE", ignoreCase = true) -> FormulaValue.BoolValue(true)
        input.equals("FALSE", ignoreCase = true) -> FormulaValue.BoolValue(false)
        else -> MathEngine.parseNumber(input)?.let { FormulaValue.NumberValue(it) } ?: FormulaValue.TextValue(input)
    }

    private fun toComputedResult(value: FormulaValue): ComputedResult = ComputedResult(
        display = value.toDisplayString(),
        isNumeric = value is FormulaValue.NumberValue,
        isError = value.isError
    )
}
