package com.prateek.datatoolkit.features.excel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.prateek.datatoolkit.R
import com.prateek.datatoolkit.features.excel.sheet.CellRef
import com.prateek.datatoolkit.features.excel.sheet.SheetCell
import com.prateek.datatoolkit.features.excel.sheet.SheetData
import kotlin.math.max

/**
 * A scrollable, tappable spreadsheet grid: column letters (A, B, C...) and row numbers (1, 2,
 * 3...) frozen along the top/left edges while cell content scrolls in both directions
 * underneath them, entirely canvas-drawn (not one view per cell) so a sheet with thousands of
 * cells stays smooth to scroll.
 *
 * This view only *selects* cells - a confirmed single tap fires [Listener.onCellSelected], and
 * either a double-tap or a second tap on the already-selected cell also fires
 * [Listener.onEditRequested]. It doesn't host its own text input: the actual typing happens in
 * the host Activity's docked formula bar, the same way real spreadsheet apps hand off to a
 * formula bar once an expression gets too long to comfortably show in a narrow cell. All this
 * view needs to know is which cell is selected and how to draw it - see ExcelCsvActivity for
 * the formula-bar wiring.
 *
 * Deliberately simplified vs. real Excel in one way worth calling out: every column is the same
 * fixed width and every row the same fixed height - there's no column/row resize gesture. That
 * keeps the scroll/hit-testing math a single multiplication instead of a running sum over
 * arbitrary per-column widths, which matters more on a phone-sized viewport than the ability to
 * resize a column would.
 */
class SpreadsheetGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onCellSelected(ref: CellRef, cell: SheetCell)
        fun onEditRequested(ref: CellRef, cell: SheetCell)
    }

    var listener: Listener? = null

    /** The sheet currently on screen - swap this (e.g. the user switched sheet tabs) and the
     *  view resets scroll/selection back to A1 and redraws. */
    var sheet: SheetData? = null
        set(value) {
            field = value
            scrollXPx = 0f
            scrollYPx = 0f
            selected = CellRef(0, 0)
            value?.let { listener?.onCellSelected(selected, it.cellAt(selected)) }
            invalidate()
        }

    var selected: CellRef = CellRef(0, 0)
        private set

    /** Redraws without resetting scroll/selection - call after committing an edit (the cell's
     *  input/computed result changed) or after a recalculation touched other cells' displayed
     *  values, neither of which should jump the view back to A1 the way reassigning [sheet] does. */
    fun refresh() = invalidate()

    // ---- layout constants (px, converted from dp) ---------------------------------------------

    private val rowHeaderWidthPx = dp(44f)
    private val colHeaderHeightPx = dp(32f)
    private val colWidthPx = dp(96f)
    private val rowHeightPx = dp(36f)
    private val cellPaddingPx = dp(6f)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    // ---- scroll state ---------------------------------------------------------------------------

    private var scrollXPx = 0f
    private var scrollYPx = 0f

    // ---- paints (allocated once, mutated per-draw rather than reallocated - standard practice
    // for a canvas view redrawn every scroll frame) --------------------------------------------

    private val gridBg = Paint().apply { color = ContextCompat.getColor(context, R.color.surface) }
    private val headerBg = Paint().apply { color = ContextCompat.getColor(context, R.color.surface_muted) }
    private val headerBgSelected = Paint().apply { color = ContextCompat.getColor(context, R.color.accent_excel_bg) }
    private val gridLine = Paint().apply { color = ContextCompat.getColor(context, R.color.stroke); strokeWidth = dp(1f) }
    private val headerBorder = Paint().apply { color = ContextCompat.getColor(context, R.color.stroke); strokeWidth = dp(1.5f) }
    private val headerText = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = dp(13f)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val headerTextSelected = Paint(headerText).apply {
        color = ContextCompat.getColor(context, R.color.accent_excel)
        isFakeBoldText = true
    }
    private val cellText = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = dp(14f)
        isAntiAlias = true
    }
    private val cellTextBold = Paint(cellText).apply { isFakeBoldText = true }
    private val cellTextError = Paint(cellText).apply { color = ContextCompat.getColor(context, R.color.error) }
    private val selectionBorder = Paint().apply {
        color = ContextCompat.getColor(context, R.color.accent_excel)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }
    private val selectionFill = Paint().apply {
        color = ContextCompat.getColor(context, R.color.accent_excel_bg)
        alpha = 90
    }

    // ---- touch handling -------------------------------------------------------------------------

    private val scroller = OverScroller(context)
    private val gestureDetector = GestureDetector(context, GestureListener())

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.abortAnimation()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        // onSingleTapConfirmed, not onSingleTapUp: this view also handles onDoubleTap, and
        // onSingleTapUp can fire for the first tap of what turns out to be a double-tap before
        // the detector recognizes it as one - onSingleTapConfirmed is what waits out that
        // window, so a double-tap doesn't also spuriously select-then-edit through this path.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            handleTap(e.x, e.y, requestEdit = true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollByPx(dx, dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val (maxX, maxY) = scrollBounds() ?: return false
            scroller.fling(
                scrollXPx.toInt(), scrollYPx.toInt(), -vx.toInt(), -vy.toInt(),
                0, maxX.toInt(), 0, maxY.toInt()
            )
            postInvalidateOnAnimation()
            return true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestureDetector.onTouchEvent(event)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXPx = scroller.currX.toFloat()
            scrollYPx = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val (maxX, maxY) = scrollBounds() ?: return
        scrollXPx = scrollXPx.coerceIn(0f, maxX)
        scrollYPx = scrollYPx.coerceIn(0f, maxY)
    }

    private fun scrollBounds(): Pair<Float, Float>? {
        val s = sheet ?: return null
        val contentW = width - rowHeaderWidthPx
        val contentH = height - colHeaderHeightPx
        val maxX = max(0f, s.colCount * colWidthPx - contentW)
        val maxY = max(0f, s.rowCount * rowHeightPx - contentH)
        return maxX to maxY
    }

    private fun scrollByPx(dx: Float, dy: Float) {
        val (maxX, maxY) = scrollBounds() ?: return
        scrollXPx = (scrollXPx + dx).coerceIn(0f, maxX)
        scrollYPx = (scrollYPx + dy).coerceIn(0f, maxY)
        invalidate()
    }

    private fun handleTap(x: Float, y: Float, requestEdit: Boolean = false) {
        val s = sheet ?: return
        if (x < rowHeaderWidthPx || y < colHeaderHeightPx) return // header tap - no-op for now
        val col = (((x - rowHeaderWidthPx) + scrollXPx) / colWidthPx).toInt().coerceIn(0, s.colCount - 1)
        val row = (((y - colHeaderHeightPx) + scrollYPx) / rowHeightPx).toInt().coerceIn(0, s.rowCount - 1)
        val ref = CellRef(row, col)
        val alreadySelected = ref == selected
        selected = ref
        ensureVisible(ref)
        invalidate()
        val cell = s.cellAt(ref)
        listener?.onCellSelected(ref, cell)
        if (requestEdit || alreadySelected) listener?.onEditRequested(ref, cell)
    }

    /** Scrolls just enough that [ref] is fully inside the visible content area. */
    private fun ensureVisible(ref: CellRef) {
        val contentW = width - rowHeaderWidthPx
        val contentH = height - colHeaderHeightPx
        if (contentW <= 0 || contentH <= 0) return
        val cellLeft = ref.col * colWidthPx
        val cellTop = ref.row * rowHeightPx
        if (cellLeft < scrollXPx) scrollXPx = cellLeft
        else if (cellLeft + colWidthPx > scrollXPx + contentW) scrollXPx = cellLeft + colWidthPx - contentW
        if (cellTop < scrollYPx) scrollYPx = cellTop
        else if (cellTop + rowHeightPx > scrollYPx + contentH) scrollYPx = cellTop + rowHeightPx - contentH
        val (maxX, maxY) = scrollBounds() ?: return
        scrollXPx = scrollXPx.coerceIn(0f, maxX)
        scrollYPx = scrollYPx.coerceIn(0f, maxY)
    }

    /** Selects [ref] the same way a tap would (updates selection, scrolls it into view, fires
     *  [Listener.onCellSelected]) without needing real touch coordinates - used by the host for
     *  keyboard-driven navigation (see ExcelCsvActivity's formula bar IME-action handling). */
    fun selectProgrammatically(ref: CellRef) {
        val s = sheet ?: return
        val clamped = CellRef(ref.row.coerceIn(0, s.rowCount - 1), ref.col.coerceIn(0, s.colCount - 1))
        selected = clamped
        ensureVisible(clamped)
        invalidate()
        listener?.onCellSelected(clamped, s.cellAt(clamped))
    }

    // ---- drawing ------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val s = sheet ?: return
        val contentLeft = rowHeaderWidthPx
        val contentTop = colHeaderHeightPx
        val contentW = width - contentLeft
        val contentH = height - contentTop
        if (contentW <= 0 || contentH <= 0) return

        val firstCol = (scrollXPx / colWidthPx).toInt().coerceIn(0, max(0, s.colCount - 1))
        val lastCol = (((scrollXPx + contentW) / colWidthPx).toInt() + 1).coerceIn(0, s.colCount - 1)
        val firstRow = (scrollYPx / rowHeightPx).toInt().coerceIn(0, max(0, s.rowCount - 1))
        val lastRow = (((scrollYPx + contentH) / rowHeightPx).toInt() + 1).coerceIn(0, s.rowCount - 1)

        // 1. content cells, clipped to the content area so nothing scrolls under the frozen headers.
        canvas.save()
        canvas.clipRect(contentLeft, contentTop, width.toFloat(), height.toFloat())
        canvas.drawRect(contentLeft, contentTop, width.toFloat(), height.toFloat(), gridBg)
        for (row in firstRow..lastRow) {
            val top = contentTop + row * rowHeightPx - scrollYPx
            for (col in firstCol..lastCol) {
                val left = contentLeft + col * colWidthPx - scrollXPx
                drawCell(canvas, s, row, col, left, top)
            }
        }
        if (selected.row in firstRow..lastRow && selected.col in firstCol..lastCol) {
            val left = contentLeft + selected.col * colWidthPx - scrollXPx
            val top = contentTop + selected.row * rowHeightPx - scrollYPx
            canvas.drawRect(left, top, left + colWidthPx, top + rowHeightPx, selectionFill)
            canvas.drawRect(left + 1, top + 1, left + colWidthPx - 1, top + rowHeightPx - 1, selectionBorder)
        }
        canvas.restore()

        // 2. frozen column header row - x follows scrollX, y is always 0.
        canvas.drawRect(contentLeft, 0f, width.toFloat(), colHeaderHeightPx, headerBg)
        for (col in firstCol..lastCol) {
            val left = contentLeft + col * colWidthPx - scrollXPx
            val isSelectedCol = col == selected.col
            if (isSelectedCol) canvas.drawRect(left, 0f, left + colWidthPx, colHeaderHeightPx, headerBgSelected)
            canvas.drawText(
                CellRef.columnLabel(col), left + colWidthPx / 2f, colHeaderHeightPx / 2f + textVerticalOffset(headerText),
                if (isSelectedCol) headerTextSelected else headerText
            )
        }
        canvas.drawLine(contentLeft, colHeaderHeightPx, width.toFloat(), colHeaderHeightPx, headerBorder)

        // 3. frozen row header column - y follows scrollY, x is always 0.
        canvas.drawRect(0f, contentTop, rowHeaderWidthPx, height.toFloat(), headerBg)
        for (row in firstRow..lastRow) {
            val top = contentTop + row * rowHeightPx - scrollYPx
            val isSelectedRow = row == selected.row
            if (isSelectedRow) canvas.drawRect(0f, top, rowHeaderWidthPx, top + rowHeightPx, headerBgSelected)
            canvas.drawText(
                (row + 1).toString(), rowHeaderWidthPx / 2f, top + rowHeightPx / 2f + textVerticalOffset(headerText),
                if (isSelectedRow) headerTextSelected else headerText
            )
        }
        canvas.drawLine(rowHeaderWidthPx, contentTop, rowHeaderWidthPx, height.toFloat(), headerBorder)

        // 4. corner box, drawn last so it sits cleanly on top of both header strips.
        canvas.drawRect(0f, 0f, rowHeaderWidthPx, colHeaderHeightPx, headerBg)
        canvas.drawLine(0f, colHeaderHeightPx, rowHeaderWidthPx, colHeaderHeightPx, headerBorder)
        canvas.drawLine(rowHeaderWidthPx, 0f, rowHeaderWidthPx, colHeaderHeightPx, headerBorder)
    }

    private fun drawCell(canvas: Canvas, s: SheetData, row: Int, col: Int, left: Float, top: Float) {
        canvas.drawLine(left, top, left + colWidthPx, top, gridLine)
        canvas.drawLine(left, top, left, top + rowHeightPx, gridLine)
        val cell = s.existingCellAt(CellRef(row, col)) ?: return
        val text = cell.displayText()
        if (text.isEmpty()) return

        val paint = when {
            cell.computed?.isError == true -> cellTextError
            cell.bold -> cellTextBold
            else -> cellText
        }
        val isNumeric = cell.computed?.isNumeric == true || (!cell.isFormula && text.toDoubleOrNull() != null)
        val maxTextWidth = colWidthPx - cellPaddingPx * 2
        val clipped = clipToWidth(text, paint, maxTextWidth)
        val textY = top + rowHeightPx / 2f + textVerticalOffset(paint)
        if (isNumeric) {
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(clipped, left + colWidthPx - cellPaddingPx, textY, paint)
        } else {
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(clipped, left + cellPaddingPx, textY, paint)
        }
    }

    /** Truncates [text] with a trailing "…" if it's wider than [maxWidth] at [paint]'s current
     *  size, so a cell's content never visually spills into its neighbor - unlike the xlsx
     *  export's wrap-text handling for long values, wrapping isn't practical on a small,
     *  actively-scrolled grid, so this clips instead. */
    private fun clipToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) end--
        return text.substring(0, end) + ellipsis
    }

    /** Offset to add to a target vertical center so drawText's baseline lands there - the
     *  standard Android idiom for vertically centering text on a canvas. */
    private fun textVerticalOffset(paint: Paint): Float {
        val fm = paint.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }
}
