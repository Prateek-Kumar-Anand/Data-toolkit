package com.prateek.datatoolkit.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.prateek.datatoolkit.MainActivity
import com.prateek.datatoolkit.R
import com.prateek.datatoolkit.features.batch.BatchProcessingActivity
import com.prateek.datatoolkit.features.workflow.WorkflowActivity

/** Floating bottom tab bar shared by the home and stats screens. */
object BottomNav {
    const val TOOLS = 0
    const val BATCH = 1
    const val WORKFLOW = 2
    const val STATS = 3

    fun bind(activity: Activity, selected: Int) {
        val items = listOf(R.id.navTools, R.id.navBatch, R.id.navWorkflow, R.id.navStats)
        val targets = listOf<Class<*>>(
            MainActivity::class.java, BatchProcessingActivity::class.java,
            WorkflowActivity::class.java, DashboardActivity::class.java
        )
        items.forEachIndexed { i, id ->
            val item = activity.findViewById<ViewGroup>(id)
            val active = i == selected
            val color = ContextCompat.getColor(activity, if (active) R.color.primary else R.color.text_secondary)
            (item.getChildAt(0)).visibility = if (active) View.VISIBLE else View.INVISIBLE
            (item.getChildAt(1) as ImageView).setColorFilter(color)
            (item.getChildAt(2) as TextView).apply {
                setTextColor(color)
                setTypeface(typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            item.setOnClickListener {
                if (active) return@setOnClickListener
                val intent = Intent(activity, targets[i])
                if (i == TOOLS) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                activity.startActivity(intent)
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }
    }
}
