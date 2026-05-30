package com.stulluk.simpleplayer

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile

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

  private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

  /**
   * Parent-folder URIs for [DocumentsContract.EXTRA_INITIAL_URI], best first.
   * [ACTION_OPEN_DOCUMENT_TREE] on Samsung/Android 16 often needs a **tree** URI,
   * not only a document URI; several candidates are returned when possible.
   */
  fun initialFolderHints(context: Context, fileUri: Uri): List<Uri> {
    val out = linkedSetOf<Uri>()
    // Works for many third-party file managers (CX, EX, etc.) when manual doc-id parsing fails.
    runCatching {
      DocumentFile.fromSingleUri(context, fileUri)?.parentFile?.uri
    }.getOrNull()?.let { parent ->
      out += parent
      if (DocumentsContract.isDocumentUri(context, parent)) {
        val authority = parent.authority ?: return@let
        val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrNull()
          ?: return@let
        if (authority == EXTERNAL_STORAGE_AUTHORITY) {
          runCatching {
            DocumentsContract.buildTreeDocumentUri(authority, parentId)
          }.getOrNull()?.let { out += it }
        }
      }
    }
    parentDocumentId(context, fileUri)?.let { (authority, parentId) ->
      if (authority == EXTERNAL_STORAGE_AUTHORITY) {
        runCatching {
          DocumentsContract.buildTreeDocumentUri(authority, parentId)
        }.getOrNull()?.let { out += it }
      }
      runCatching { DocumentsContract.buildDocumentUri(authority, parentId) }
        .getOrNull()?.let { out += it }
      if (authority == EXTERNAL_STORAGE_AUTHORITY) {
        runCatching {
          DocumentsContract.buildTreeDocumentUri(authority, "$parentId/")
        }.getOrNull()?.let { out += it }
      }
    }
    return out.toList()
  }

  /** Preferred hint for the folder picker (first candidate, if any). */
  fun initialFolderHint(context: Context, fileUri: Uri): Uri? =
    initialFolderHints(context, fileUri).firstOrNull()

  /** Resolves the parent storage document id for [fileUri], when possible. */
  private fun parentDocumentId(context: Context, fileUri: Uri): Pair<String, String>? =
    when (fileUri.scheme) {
      "content" -> parentIdFromContentUri(context, fileUri)
      "file" -> parentIdFromFileUri(fileUri)
      else -> null
    }

  private fun parentIdFromContentUri(context: Context, fileUri: Uri): Pair<String, String>? {
    if (DocumentsContract.isTreeUri(fileUri)) {
      val treeId = runCatching { DocumentsContract.getTreeDocumentId(fileUri) }.getOrNull()
      if (!treeId.isNullOrEmpty()) {
        return (fileUri.authority ?: EXTERNAL_STORAGE_AUTHORITY) to treeId
      }
    }
    val docId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull()
      ?: parentIdFromEncodedPath(fileUri)
      ?: return null
    val parentId = docIdParentId(docId) ?: return null
    val authority = fileUri.authority ?: EXTERNAL_STORAGE_AUTHORITY
    return authority to parentId
  }

  /** Some file managers use non-standard content URIs; parse path segments. */
  private fun parentIdFromEncodedPath(uri: Uri): String? {
    val path = uri.path ?: return null
    val decoded = Uri.decode(path)
    val primary = primaryDocIdFromRelativePath(decoded) ?: return null
    return primary
  }

  private fun parentIdFromFileUri(fileUri: Uri): Pair<String, String>? {
    val path = fileUri.path ?: return null
    val relative = pathToPrimaryRelative(path) ?: return null
    val parentId = docIdParentId("primary:$relative") ?: return null
    return EXTERNAL_STORAGE_AUTHORITY to parentId
  }

  private fun docIdParentId(docId: String): String? {
    val slash = docId.lastIndexOf('/')
    if (slash <= 0) return null
    return docId.substring(0, slash)
  }

  private fun pathToPrimaryRelative(absolutePath: String): String? {
    val prefixes = listOf(
      Environment.getExternalStorageDirectory().absolutePath,
      "/storage/emulated/0",
      "/sdcard",
    )
    for (prefix in prefixes) {
      if (absolutePath.startsWith(prefix)) {
        val rel = absolutePath.removePrefix(prefix).trimStart('/')
        if (rel.isNotEmpty()) return rel
      }
    }
    return null
  }

  private fun primaryDocIdFromRelativePath(path: String): String? {
    val trimmed = path.trimStart('/')
    if (trimmed.isEmpty()) return null
    val rel = when {
      trimmed.startsWith("document/") -> {
        val after = trimmed.removePrefix("document/")
        Uri.decode(after)
      }
      trimmed.startsWith("tree/") -> return null
      else -> trimmed
    }
    return if (rel.contains(':')) rel else "primary:$rel"
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
