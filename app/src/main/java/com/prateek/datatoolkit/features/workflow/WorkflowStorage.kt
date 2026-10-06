package com.prateek.datatoolkit.features.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a workflow's step chain into a small JSON string (for [com.prateek.datatoolkit.core.cache.SavedWorkflow.stepsJson])
 * and back. Deliberately only persists what's safe to restore later - each step's [StepKind]
 * its [WorkflowStep.textInput] (a typed URL or pasted text, both plain strings that stay
 * valid forever) and its [WorkflowStep.options] (OCR mode, filter column, file name...). Picked file/photo Uris are never encoded - see the note on [com.prateek.datatoolkit.core.cache.SavedWorkflow]
 * for why re-running a loaded workflow asks the user to pick those again instead.
 */
object WorkflowStorage {

    const val MAX_STEPS = 40
    const val MAX_TEXT_CHARS = 20_000

    fun encode(steps: List<WorkflowStep>): String {
        val array = JSONArray()
        for (step in steps.take(MAX_STEPS)) {
            val obj = JSONObject()
            obj.put("kind", step.kind.name)
            obj.put("textInput", step.textInput.take(MAX_TEXT_CHARS))
            val opts = JSONObject()
            step.options.forEach { (k, v) -> opts.put(k, v.take(MAX_TEXT_CHARS)) }
            obj.put("options", opts)
            array.put(obj)
        }
        return array.toString()
    }

    /** Any step kind that no longer exists (e.g. after an app update) is silently skipped. */
    fun decode(json: String): List<WorkflowStep> {
        val steps = mutableListOf<WorkflowStep>()
        val array = try {
            JSONArray(json)
        } catch (_: Exception) {
            return emptyList()
        }
        for (i in 0 until minOf(array.length(), MAX_STEPS)) {
            val obj = array.optJSONObject(i) ?: continue
            val kindName = obj.optString("kind")
            val kind = try {
                StepKind.valueOf(kindName)
            } catch (_: IllegalArgumentException) {
                continue
            }
            val step = WorkflowStep(kind)
            step.textInput = obj.optString("textInput", "")
            obj.optJSONObject("options")?.let { o ->
                o.keys().forEach { k -> step.options[k] = o.optString(k, "") }
            }
            steps.add(step)
        }
        return steps
    }

    /** Short, human-readable summary shown under a saved workflow's name in the list. */
    fun previewOf(steps: List<WorkflowStep>): String =
        steps.joinToString(" → ") { it.kind.emoji }
}
