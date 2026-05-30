package com.stulluk.simpleplayer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.stulluk.simpleplayer.databinding.ActivityPlayerBinding

/**
 * Single-screen video player. It can be started three ways:
 *  - from the launcher (resumes the last picked folder, or asks to pick one);
 *  - via "Open with" from a file manager (plays the file and, if the folder is
 *    known, enables next/previous across every video in it);
 *  - via the in-app folder picker.
 *
 * The whole point of the app is reliable next/previous navigation across mixed
 * mp4 + mkv folders, which ExoPlayer's playlist handles natively.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

  private lateinit var binding: ActivityPlayerBinding
  private lateinit var prefs: SharedPreferences
  private var player: ExoPlayer? = null

  /** Current playlist and the index we should resume at when (re)creating the player. */
  private var playlist: List<VideoItem> = emptyList()
  private var startIndex: Int = 0
  private var startPositionMs: Long = 0L

  /** A single externally-opened video that has no known folder yet. */
  private var pendingSingleUri: Uri? = null

  /** True after we auto-opened the folder picker for the current pending file. */
  private var folderPickerAutoLaunched = false

  /** Current orientation override mode; persisted across launches. */
  private var orientationMode: Int = MODE_AUTO

  /**
   * System folder picker with [DocumentsContract.EXTRA_INITIAL_URI]. Adds read
   * grant on the hint so Samsung DocumentsUI can open that folder instead of
   * defaulting to DCIM/Camera.
   */
  private val openFolder = registerForActivityResult(
    object : ActivityResultContract<Uri?, Uri?>() {
      override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
          addCategory(Intent.CATEGORY_DEFAULT)
          input?.let { hint ->
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, hint)
            addFlags(
              Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
          }
        }

      override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == RESULT_OK }
    },
  ) { uri -> if (uri != null) onFolderPicked(uri) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityPlayerBinding.inflate(layoutInflater)
    setContentView(binding.root)
    prefs = getSharedPreferences("svp", MODE_PRIVATE)

    binding.emptyPickButton.setOnClickListener { launchFolderPicker(null) }
    binding.folderButton.setOnClickListener {
      val hints = pendingSingleUri?.let { VideoFolder.initialFolderHints(this, it) }.orEmpty()
      launchFolderPicker(hints.firstOrNull())
    }
    binding.rotateButton.setOnClickListener { cycleOrientationMode() }

    // Apply the persisted orientation mode before the window is laid out so the
    // system does not flash the wrong orientation on launch.
    orientationMode = prefs.getInt(KEY_ORIENT, MODE_AUTO)
    applyOrientationMode()
    refreshRotateIcon()

    binding.playerView.setControllerVisibilityListener(
      androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
        // Keep the overlay chrome (folder/rotate buttons + title) in sync with controls.
        val hasContent = playlist.isNotEmpty() || pendingSingleUri != null
        val gated = if (hasContent) visibility else View.GONE
        binding.folderButton.visibility = gated
        binding.rotateButton.visibility = gated
        binding.titleText.visibility =
          if (hasContent && binding.titleText.text.isNotEmpty()) visibility else View.GONE
      },
    )

    handleIntent(intent, fromNewIntent = false)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent, fromNewIntent = true)
  }

  /** Decides what to play based on how the activity was launched. */
  private fun handleIntent(intent: Intent?, fromNewIntent: Boolean) {
    val data = intent?.data
    if (intent?.action == Intent.ACTION_VIEW && data != null) {
      openExternalVideo(data)
      return
    }
    if (fromNewIntent && playlist.isNotEmpty()) return
    // Launcher start: resume the last folder if we still hold permission.
    val saved = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
    if (saved != null && hasPersistedPermission(saved)) {
      loadFolder(saved, preferredName = null)
    } else {
      showEmptyState()
    }
  }

  /**
   * Handles an externally opened video. If we already hold a SAF folder that
   * contains a file with the same name, we play the whole folder; otherwise we
   * play the file and open the SAF folder picker so the user can grant the
   * folder in one tap (next/previous then works for every video in it).
   */
  private fun openExternalVideo(uri: Uri, offerFolderGrant: Boolean = true) {
    val name = VideoFolder.displayNameOf(this, uri)
    val match = name?.let { findFolderContaining(it) }
    if (match != null) {
      pendingSingleUri = null
      folderPickerAutoLaunched = false
      startPlaylist(match.videos, match.index, 0L)
      return
    }
    if (pendingSingleUri != uri) folderPickerAutoLaunched = false
    playSingleExternalVideo(uri, name)
    if (offerFolderGrant && !folderPickerAutoLaunched) {
      folderPickerAutoLaunched = true
      val hints = VideoFolder.initialFolderHints(this, uri)
      SafUriDebug.logOpenWith(this, uri, hints)
      binding.playerView.post { launchFolderPicker(hints.firstOrNull()) }
    }
  }

  /** Plays one file with no folder playlist (next/prev disabled until SAF grant). */
  private fun playSingleExternalVideo(uri: Uri, name: String?) {
    pendingSingleUri = uri
    playlist = emptyList()
    startIndex = 0
    startPositionMs = 0L
    binding.emptyState.visibility = View.GONE
    binding.folderButton.visibility = View.VISIBLE
    binding.titleText.text = name ?: ""
    initPlayer()
    player?.apply {
      setMediaItems(listOf(buildMediaItem(VideoItem(uri, name ?: ""))))
      prepare()
      playWhenReady = true
    }
  }

  private data class FolderMatch(val videos: List<VideoItem>, val index: Int)

  /** Searches every persisted folder for a video whose name matches [fileName]. */
  private fun findFolderContaining(fileName: String): FolderMatch? {
    for (perm in contentResolver.persistedUriPermissions) {
      if (!perm.isReadPermission) continue
      val videos = VideoFolder.listVideos(this, perm.uri)
      val idx = videos.indexOfFirst { it.name == fileName }
      if (idx >= 0) return FolderMatch(videos, idx)
    }
    return null
  }

  private fun launchFolderPicker(initialUri: Uri? = null) {
    runCatching { openFolder.launch(initialUri) }
      .onFailure { Toast.makeText(this, R.string.no_videos, Toast.LENGTH_SHORT).show() }
  }

  private fun onFolderPicked(uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
    prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
    // If a single external file is currently playing, try to resume it within
    // the newly granted folder so navigation continues from the same video.
    val currentName = pendingSingleUri?.let { VideoFolder.displayNameOf(this, it) }
    loadFolder(uri, preferredName = currentName)
  }

  private fun loadFolder(treeUri: Uri, preferredName: String?) {
    val videos = VideoFolder.listVideos(this, treeUri)
    if (videos.isEmpty()) {
      Toast.makeText(this, R.string.no_videos, Toast.LENGTH_LONG).show()
      if (playlist.isEmpty() && pendingSingleUri == null) showEmptyState()
      return
    }
    pendingSingleUri = null
    folderPickerAutoLaunched = false
    val index = preferredName?.let { name -> videos.indexOfFirst { it.name == name } }
      ?.takeIf { it >= 0 } ?: 0
    startPlaylist(videos, index, 0L)
  }

  private fun startPlaylist(videos: List<VideoItem>, index: Int, positionMs: Long) {
    playlist = videos
    startIndex = index.coerceIn(0, videos.size - 1)
    startPositionMs = positionMs
    binding.emptyState.visibility = View.GONE
    binding.folderButton.visibility = View.VISIBLE
    initPlayer()
    val current = player ?: return
    current.setMediaItems(videos.map(::buildMediaItem), startIndex, startPositionMs)
    current.prepare()
    current.playWhenReady = true
    updateTitle()
  }

  private fun buildMediaItem(item: VideoItem): MediaItem =
    MediaItem.Builder()
      .setUri(item.uri)
      .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build())
      .build()

  private fun showEmptyState() {
    binding.emptyState.visibility = View.VISIBLE
    binding.folderButton.visibility = View.GONE
    binding.rotateButton.visibility = View.GONE
    binding.titleText.visibility = View.GONE
  }

  /**
   * Cycles through AUTO -> force landscape -> force portrait -> AUTO. AUTO uses
   * [ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR], which intentionally overrides
   * the user's system-wide rotation lock for this activity only, so a sideways
   * phone still produces a landscape video.
   */
  private fun cycleOrientationMode() {
    orientationMode = (orientationMode + 1) % 3
    prefs.edit().putInt(KEY_ORIENT, orientationMode).apply()
    applyOrientationMode()
    refreshRotateIcon()
  }

  private fun applyOrientationMode() {
    requestedOrientation = when (orientationMode) {
      MODE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      MODE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }
  }

  private fun refreshRotateIcon() {
    @DrawableRes val icon = when (orientationMode) {
      MODE_LANDSCAPE -> R.drawable.ic_orient_landscape
      MODE_PORTRAIT -> R.drawable.ic_orient_portrait
      else -> R.drawable.ic_orient_auto
    }
    binding.rotateButton.setImageResource(icon)
  }

  private fun initPlayer() {
    if (player != null) return
    val exo = ExoPlayer.Builder(this).build()
    binding.playerView.player = exo
    binding.playerView.setShowFastForwardButton(false)
    binding.playerView.setShowRewindButton(false)
    exo.addListener(object : Player.Listener {
      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        updateTitle()
      }

      override fun onPlayerError(error: PlaybackException) {
        Toast.makeText(this@PlayerActivity, R.string.playback_error, Toast.LENGTH_SHORT).show()
        // Skip a single broken file instead of getting stuck on it.
        val p = player ?: return
        if (p.hasNextMediaItem()) {
          p.seekToNextMediaItem()
          p.prepare()
        }
      }
    })
    player = exo
  }

  private fun updateTitle() {
    val title = player?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
    binding.titleText.text = title
    if (title.isNotEmpty() && binding.playerView.isControllerFullyVisible) {
      binding.titleText.visibility = View.VISIBLE
    }
  }

  private fun hasPersistedPermission(uri: Uri): Boolean =
    contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

  override fun onStart() {
    super.onStart()
    // Re-create the player when returning to the foreground.
    if (player == null && (playlist.isNotEmpty() || pendingSingleUri != null)) {
      if (playlist.isNotEmpty()) {
        startPlaylist(playlist, startIndex, startPositionMs)
      } else {
        pendingSingleUri?.let { uri ->
          val name = VideoFolder.displayNameOf(this, uri)
          playSingleExternalVideo(uri, name)
        }
      }
    }
  }

  override fun onStop() {
    super.onStop()
    releasePlayer()
  }

  private fun releasePlayer() {
    player?.let {
      startIndex = it.currentMediaItemIndex
      startPositionMs = it.currentPosition
      it.release()
    }
    player = null
    binding.playerView.player = null
  }

  companion object {
    private const val KEY_FOLDER = "last_folder_uri"
    private const val KEY_ORIENT = "orientation_mode"

    private const val MODE_AUTO = 0
    private const val MODE_LANDSCAPE = 1
    private const val MODE_PORTRAIT = 2
  }
}
