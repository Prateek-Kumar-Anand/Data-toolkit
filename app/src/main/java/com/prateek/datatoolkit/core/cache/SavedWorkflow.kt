package com.prateek.datatoolkit.core.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One workflow the user has explicitly saved by name from the Workflow Builder screen.
 *
 * [stepsJson] holds the ordered chain as a JSON array of {kind, textInput} objects - see
 * WorkflowStorage.encode()/decode(). Deliberately NOT stored: picked file/photo Uris. Uris
 * handed back by the system picker (GetContent/GetMultipleContents) aren't guaranteed to
 * still resolve after the app restarts or the device reboots, so pretending to restore them
 * would be a silent, fake "restore" that breaks later. Re-running a saved workflow that has
 * a Scan Photos / Load PDF / Load Excel-CSV step simply asks the user to pick that file again
 * - everything else (the chain shape, any typed URL or pasted text) comes back exactly as saved.
 */
@Entity(tableName = "saved_workflows")
data class SavedWorkflow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val stepsJson: String,
    val stepCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null
)
