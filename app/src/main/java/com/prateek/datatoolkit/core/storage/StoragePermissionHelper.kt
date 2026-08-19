package com.prateek.datatoolkit.core.storage

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * The one runtime permission [OutputStorage]'s legacy (API 24-28) write path needs -
 * WRITE_EXTERNAL_STORAGE - wrapped behind a single [runWithPermission] call, so every feature
 * Activity that saves output doesn't duplicate its own request/callback plumbing. A pure
 * passthrough on API 29+, where OutputStorage never touches this permission at all.
 *
 * Must be constructed as a field on the Activity (never lazily/conditionally, never from inside
 * onCreate after the activity has started) - registerForActivityResult requires that, same as
 * every other ActivityResultLauncher already used throughout this app.
 */
class StoragePermissionHelper(private val activity: ComponentActivity) {

    private var pendingAction: (() -> Unit)? = null

    private val requestPermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Run the pending action either way: if the user denied it, OutputStorage's legacy
        // write will fail with a clear SecurityException, surfaced through the same
        // "Save failed: ..." toast every save path already shows on error - no separate
        // denial handling needed here.
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    /** Runs [action] right away if this device doesn't need the permission (API 29+) or
     *  already granted it; otherwise asks first and runs [action] once the user answers. */
    fun runWithPermission(action: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingAction = action
            requestPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }
}
