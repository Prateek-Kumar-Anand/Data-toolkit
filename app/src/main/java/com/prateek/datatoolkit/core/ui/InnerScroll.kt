package com.prateek.datatoolkit.core.ui

import android.annotation.SuppressLint
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.widget.TextView

/** Lets a fixed-height text view scroll its own content inside a parent ScrollView. */
@SuppressLint("ClickableViewAccessibility")
fun TextView.enableInnerScroll() {
    isVerticalScrollBarEnabled = true
    if (this !is android.widget.EditText && movementMethod == null) movementMethod = ScrollingMovementMethod()
    setOnTouchListener { v, ev ->
        if (v.canScrollVertically(-1) || v.canScrollVertically(1)) {
            v.parent.requestDisallowInterceptTouchEvent(ev.action != MotionEvent.ACTION_UP && ev.action != MotionEvent.ACTION_CANCEL)
        }
        false
    }
}
