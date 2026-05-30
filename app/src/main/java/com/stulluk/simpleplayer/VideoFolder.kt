package com.stulluk.simpleplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/** A single playable video entry: its content [uri] and display [name]. */
data class VideoItem(val uri: Uri, val name: String)

/**
 * Helpers for enumerating videos inside a folder selected via the Storage
 * Access Framework (SAF). This is intentionally codec-agnostic: every file that
 * looks like a video (mp4, mkv, etc.) is included so that "next/previous"
 * navigation never skips a Matroska file the way most players do.
 */
object VideoFolder {

  private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "3g2", "ts", "m2ts",
    "mts", "flv", "wmv", "mpg", "mpeg", "ogv", "mxf", "vob",
  )

  /**
   * Lists every playable video inside the given SAF tree [treeUri], sorted by
   * file name using a human-friendly natural order (so "clip2" precedes
   * "clip10"). Returns an empty list if the tree cannot be read.
   */
  fun listVideos(context: Context, treeUri: Uri): List<VideoItem> {
    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
    val projection = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    val result = mutableListOf<VideoItem>()
    runCatching {
      context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (cursor.moveToNext()) {
          val mime = cursor.getString(mimeIdx) ?: ""
          if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
          val name = cursor.getString(nameIdx) ?: continue
          if (!isVideo(name, mime)) continue
          val docId = cursor.getString(idIdx) ?: continue
          val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
          result += VideoItem(docUri, name)
        }
      }
    }
    return result.sortedWith(compareBy(NaturalOrderComparator) { it.name })
  }

  /**
   * Best-effort parent-folder URI for [DocumentsContract.EXTRA_INITIAL_URI] when
   * opening the SAF tree picker after "Open with". Works for Storage Access
   * Framework document URIs (e.g. from CX / EX File Explorer).
   */
  fun initialFolderHint(context: Context, fileUri: Uri): Uri? {
    if (fileUri.scheme != "content") return null
    if (!DocumentsContract.isDocumentUri(context, fileUri)) return null
    val docId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull()
      ?: return null
    val slash = docId.lastIndexOf('/')
    if (slash <= 0) return null
    val parentId = docId.substring(0, slash)
    val authority = fileUri.authority ?: return null
    return runCatching {
      DocumentsContract.buildDocumentUri(authority, parentId)
    }.getOrNull()
  }

  /** Reads the display name of an arbitrary content/file [uri], or null. */
  fun displayNameOf(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.lastPathSegment
    return runCatching {
      context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
      )?.use { cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) cursor.getString(idx) else null
        } else {
          null
        }
      }
    }.getOrNull()
  }

  private fun isVideo(name: String, mime: String): Boolean {
    if (mime.startsWith("video/")) return true
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTENSIONS
  }
}

/** Compares strings so embedded numbers sort numerically ("2" before "10"). */
object NaturalOrderComparator : Comparator<String> {
  override fun compare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
      val ca = a[i]
      val cb = b[j]
      if (ca.isDigit() && cb.isDigit()) {
        var endA = i
        while (endA < a.length && a[endA].isDigit()) endA++
        var endB = j
        while (endB < b.length && b[endB].isDigit()) endB++
        val numA = a.substring(i, endA).trimStart('0').ifEmpty { "0" }
        val numB = b.substring(j, endB).trimStart('0').ifEmpty { "0" }
        if (numA.length != numB.length) return numA.length - numB.length
        val cmp = numA.compareTo(numB)
        if (cmp != 0) return cmp
        i = endA
        j = endB
      } else {
        val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
        if (cmp != 0) return cmp
        i++
        j++
      }
    }
    return (a.length - i) - (b.length - j)
  }
}
