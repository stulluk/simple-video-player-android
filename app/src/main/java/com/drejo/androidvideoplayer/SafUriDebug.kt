package com.drejo.androidvideoplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/** Logs incoming content URIs to diagnose SAF folder-picker initial location. */
object SafUriDebug {
  private const val TAG = "DREJO_URI"

  fun logOpenWith(context: Context, fileUri: Uri, hints: List<Uri>) {
    val docId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull()
    val isDoc = runCatching { DocumentsContract.isDocumentUri(context, fileUri) }.getOrDefault(false)
    Log.i(
      TAG,
      "openWith uri=$fileUri scheme=${fileUri.scheme} authority=${fileUri.authority} " +
        "isDocument=$isDoc documentId=$docId path=${fileUri.path}",
    )
    hints.forEachIndexed { i, hint ->
      Log.i(TAG, "  hint[$i]=$hint")
    }
    if (hints.isEmpty()) Log.w(TAG, "  no EXTRA_INITIAL_URI hints (picker will use system default)")
  }
}
