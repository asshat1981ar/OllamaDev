package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Runtime permission helpers for workspace storage access.
 *
 * - On Android 11+ (API 30+) broad file access is gated by MANAGE_EXTERNAL_STORAGE.
 * - On Android 10 and below the standard WRITE_EXTERNAL_STORAGE runtime permission is used.
 */
fun hasStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Requests the legacy WRITE_EXTERNAL_STORAGE runtime permission (API < 30 only -- there is
 * no equivalent broad-file-access runtime permission on API 30+, which uses
 * [requestAllFilesAccess]/MANAGE_EXTERNAL_STORAGE instead, matching [hasStoragePermission]'s
 * own branch). Callers must route by SDK level the same way [hasStoragePermission] does.
 */
fun requestStoragePermission(
    launcher: ManagedActivityResultLauncher<String, Boolean>
) {
    launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

/**
 * Opens the system settings screen so the user can grant MANAGE_EXTERNAL_STORAGE.
 */
fun requestAllFilesAccess(
    context: Context,
    launcher: ManagedActivityResultLauncher<Intent, ActivityResult>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        launcher.launch(intent)
    }
}
