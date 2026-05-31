package com.drejo.androidvideoplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Helper around the All Files Access permission (`MANAGE_EXTERNAL_STORAGE`).
 * Only the F-Droid flavor declares the permission in its manifest; the Play
 * flavor's manifest does not include it, so [isGranted] always returns false
 * there and SAF remains the only path to user content.
 */
object AllFilesAccess {

  /** True when this build can use direct filesystem access without SAF. */
  val isGranted: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
      runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

  /**
   * Opens the per-app "All files access" settings page so the user can flip
   * the toggle. Falls back to the global page on devices that do not handle
   * the per-package intent. Safe no-op on Android 10 (where the permission
   * does not exist yet).
   */
  fun openSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val perAppIntent = Intent(
      Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
      Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(perAppIntent) }.isSuccess) return
    val globalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(globalIntent) }
  }
}
