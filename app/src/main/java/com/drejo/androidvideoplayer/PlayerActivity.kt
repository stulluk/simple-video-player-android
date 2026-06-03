package com.drejo.androidvideoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.drejo.androidvideoplayer.databinding.ActivityPlayerBinding
import java.io.File

/**
 * Single-screen video player. The activity is launched from three places:
 *  - the launcher (resumes the last folder if any);
 *  - "Open with" from a file manager;
 *  - the in-app folder picker (Play flavor only path that triggers SAF).
 *
 * The whole point of the app is reliable next/previous navigation across
 * mixed mp4 + mkv folders, which ExoPlayer's playlist handles natively.
 *
 * The build has two flavors:
 *  - **play** uses SAF exclusively (no dangerous permissions).
 *  - **fdroid** can additionally request `MANAGE_EXTERNAL_STORAGE`, which
 *    enables direct filesystem access and makes "Open with" navigation work
 *    for hidden / non-MediaStore folders without any further prompts.
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

  /** Current orientation override mode; persisted across launches. */
  private var orientationMode: Int = MODE_AUTO

  /** Accumulated seek amount (signed seconds) shown in the side overlay. */
  private var seekAccumSec: Long = 0L
  private var seekDirection: Int = 0
  private var lastSeekTimeMs: Long = 0L
  /** Number of consecutive same-direction double-taps within the accumulate
   *  window; used to escalate the seek step from 10s to 30s after a few taps. */
  private var consecutiveDoubleTaps: Int = 0
  private val seekHandler = Handler(Looper.getMainLooper())
  private val hideSeekIndicator = Runnable {
    listOf(binding.seekIndicatorLeft, binding.seekIndicatorRight).forEach { view ->
      view.animate()
        .alpha(0f)
        .setDuration(180L)
        .withEndAction {
          view.visibility = View.GONE
          view.alpha = 1f
        }
        .start()
    }
  }

  private val isFdroidFlavor: Boolean get() = BuildConfig.FLAVOR == "fdroid"

  /**
   * System folder picker with [DocumentsContract.EXTRA_INITIAL_URI]. Adds read
   * grant on the hint so DocumentsUI can open that folder when the OEM honours
   * the hint (Samsung's DocumentsUI does not, but other vendors do).
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
  ) { uri -> if (uri != null) onSafFolderPicked(uri) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityPlayerBinding.inflate(layoutInflater)
    setContentView(binding.root)
    prefs = getSharedPreferences("drejo", MODE_PRIVATE)

    binding.emptyPickButton.setOnClickListener { onEmptyStatePrimaryAction() }
    binding.folderButton.setOnClickListener { onFolderButtonClicked() }
    binding.rotateButton.setOnClickListener { cycleOrientationMode() }
    binding.folderGrantBanner.setOnClickListener { onBannerClicked() }
    setupDoubleTapSeek()

    orientationMode = prefs.getInt(KEY_ORIENT, MODE_AUTO)
    applyOrientationMode()
    refreshRotateIcon()

    binding.playerView.setControllerVisibilityListener(
      androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
        val hasContent = playlist.isNotEmpty() || pendingSingleUri != null
        val gated = if (hasContent) visibility else View.GONE
        binding.folderButton.visibility = gated
        binding.rotateButton.visibility = gated
        binding.titleText.visibility =
          if (hasContent && binding.titleText.text.isNotEmpty()) visibility else View.GONE
        binding.folderGrantBanner.visibility =
          if (showBanner()) visibility else View.GONE
      },
    )

    handleIntent(intent, fromNewIntent = false)
    maybePromptAllFilesAccessOnFirstRun()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent, fromNewIntent = true)
  }

  /** Decides what to play based on how the activity was launched. */
  private fun handleIntent(intent: Intent?, fromNewIntent: Boolean) {
    val data = intent?.data
    android.util.Log.i(
      "DREJO_URI",
      "handleIntent fromNewIntent=$fromNewIntent action=${intent?.action} " +
        "data=$data type=${intent?.type} extras=${intent?.extras?.keySet()}",
    )
    if (intent?.action == Intent.ACTION_VIEW && data != null) {
      openExternalVideo(data)
      return
    }
    if (fromNewIntent && playlist.isNotEmpty()) return

    if (isFdroidFlavor && AllFilesAccess.isGranted) {
      val savedDir = prefs.getString(KEY_FILE_FOLDER, null)?.let(::File)
      if (savedDir != null && savedDir.isDirectory) {
        val videos = VideoFolder.listVideosFromDirectory(savedDir)
        if (videos.isNotEmpty()) {
          startPlaylist(videos, 0, 0L)
          return
        }
      }
      showEmptyState()
      return
    }

    val saved = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
    if (saved != null && hasPersistedPermission(saved)) {
      loadSafFolder(saved, preferredName = null)
    } else {
      showEmptyState()
    }
  }

  /**
   * Handles an externally opened video. The behaviour depends on the flavor and
   * on whether we already hold a folder grant (SAF or all-files):
   *  - F-Droid + all-files: list the parent directory directly via [File].
   *  - SAF folder already granted that contains this filename: replay folder.
   *  - Otherwise: play just this file and offer a grant via the banner.
   */
  private fun openExternalVideo(uri: Uri) {
    val name = VideoFolder.displayNameOf(this, uri)

    if (isFdroidFlavor && AllFilesAccess.isGranted) {
      val absPath = VideoFolder.absolutePathFromUri(uri)
      val parentDir = absPath?.let(::File)?.parentFile
      if (parentDir != null && parentDir.isDirectory) {
        val videos = VideoFolder.listVideosFromDirectory(parentDir)
        if (videos.isNotEmpty()) {
          val index = videos.indexOfFirst { it.name == name }.coerceAtLeast(0)
          pendingSingleUri = null
          binding.folderGrantBanner.visibility = View.GONE
          prefs.edit().putString(KEY_FILE_FOLDER, parentDir.absolutePath).apply()
          startPlaylist(videos, index, 0L)
          return
        }
      }
    }

    val match = name?.let { findSafFolderContaining(it) }
    if (match != null) {
      pendingSingleUri = null
      startPlaylist(match.videos, match.index, 0L)
      return
    }

    val hints = VideoFolder.initialFolderHints(this, uri)
    SafUriDebug.logOpenWith(this, uri, hints)
    playSingleExternalVideo(uri, name)
  }

  /** Plays one file with no folder playlist (next/prev disabled until a grant). */
  private fun playSingleExternalVideo(uri: Uri, name: String?) {
    pendingSingleUri = uri
    playlist = emptyList()
    startIndex = 0
    startPositionMs = 0L
    binding.emptyState.visibility = View.GONE
    binding.folderButton.visibility = View.VISIBLE
    binding.titleText.text = name ?: ""
    updateFolderGrantBanner()
    initPlayer()
    player?.apply {
      setMediaItems(listOf(buildMediaItem(VideoItem(uri, name ?: ""))))
      prepare()
      playWhenReady = true
    }
    setupTransportOverrides()
  }

  private fun showBanner(): Boolean = pendingSingleUri != null && playlist.isEmpty()

  private fun updateFolderGrantBanner() {
    val uri = pendingSingleUri
    if (uri == null) {
      binding.folderGrantBanner.visibility = View.GONE
      return
    }
    val folderName = VideoFolder.folderDisplayNameFromUri(uri)
    val msg = if (isFdroidFlavor) {
      if (folderName != null) {
        getString(R.string.banner_fdroid_named, folderName)
      } else {
        getString(R.string.banner_fdroid_generic)
      }
    } else {
      if (folderName != null) {
        getString(R.string.banner_play_named, folderName)
      } else {
        getString(R.string.banner_play_generic)
      }
    }
    binding.folderGrantBanner.text = msg
    binding.folderGrantBanner.visibility = View.VISIBLE
  }

  private fun onBannerClicked() {
    if (isFdroidFlavor) {
      showAllFilesAccessDialog()
    } else {
      requestSafFolderGrant()
    }
  }

  private fun onEmptyStatePrimaryAction() {
    if (isFdroidFlavor && !AllFilesAccess.isGranted) {
      showAllFilesAccessDialog()
    } else {
      launchSafFolderPicker(null)
    }
  }

  private fun onFolderButtonClicked() {
    if (isFdroidFlavor && AllFilesAccess.isGranted) return
    val hints = pendingSingleUri?.let { VideoFolder.initialFolderHints(this, it) }.orEmpty()
    launchSafFolderPicker(hints.firstOrNull())
  }

  private fun maybePromptAllFilesAccessOnFirstRun() {
    if (!isFdroidFlavor) return
    if (AllFilesAccess.isGranted) return
    if (prefs.getBoolean(KEY_ALL_FILES_DIALOG_DISMISSED, false)) return
    showAllFilesAccessDialog()
  }

  private fun showAllFilesAccessDialog() {
    AlertDialog.Builder(this)
      .setTitle(R.string.all_files_dialog_title)
      .setMessage(R.string.all_files_dialog_message)
      .setPositiveButton(R.string.all_files_dialog_open) { _, _ ->
        AllFilesAccess.openSettings(this)
      }
      .setNegativeButton(R.string.all_files_dialog_skip) { dialog, _ ->
        prefs.edit().putBoolean(KEY_ALL_FILES_DIALOG_DISMISSED, true).apply()
        dialog.dismiss()
      }
      .setCancelable(true)
      .show()
  }

  private fun requestSafFolderGrant() {
    val uri = pendingSingleUri ?: return
    val hints = VideoFolder.initialFolderHints(this, uri)
    launchSafFolderPicker(hints.firstOrNull())
  }

  private fun launchSafFolderPicker(initialUri: Uri? = null) {
    runCatching { openFolder.launch(initialUri) }
      .onFailure { Toast.makeText(this, R.string.no_videos, Toast.LENGTH_SHORT).show() }
  }

  private data class FolderMatch(val videos: List<VideoItem>, val index: Int)

  /** Searches every persisted SAF folder for a video whose name matches [fileName]. */
  private fun findSafFolderContaining(fileName: String): FolderMatch? {
    for (perm in contentResolver.persistedUriPermissions) {
      if (!perm.isReadPermission) continue
      val videos = VideoFolder.listVideos(this, perm.uri)
      val idx = videos.indexOfFirst { it.name == fileName }
      if (idx >= 0) return FolderMatch(videos, idx)
    }
    return null
  }

  private fun onSafFolderPicked(uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
    prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
    val currentName = pendingSingleUri?.let { VideoFolder.displayNameOf(this, it) }
    loadSafFolder(uri, preferredName = currentName)
  }

  private fun loadSafFolder(treeUri: Uri, preferredName: String?) {
    val videos = VideoFolder.listVideos(this, treeUri)
    if (videos.isEmpty()) {
      Toast.makeText(this, R.string.no_videos, Toast.LENGTH_LONG).show()
      if (playlist.isEmpty() && pendingSingleUri == null) showEmptyState()
      return
    }
    pendingSingleUri = null
    binding.folderGrantBanner.visibility = View.GONE
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
    binding.folderGrantBanner.visibility = View.GONE
    binding.emptyPickButton.setText(
      if (isFdroidFlavor && !AllFilesAccess.isGranted) R.string.grant_all_files
      else R.string.pick_folder,
    )
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
    // Seek to the nearest keyframe instead of decoding up to the exact frame.
    // This trades a couple of seconds of accuracy for an essentially instant
    // double-tap seek, matching MX Player's behaviour.
    exo.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    binding.playerView.player = exo
    binding.playerView.setShowFastForwardButton(false)
    binding.playerView.setShowRewindButton(false)
    exo.addListener(object : Player.Listener {
      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        updateTitle()
      }

      override fun onPlayerError(error: PlaybackException) {
        Toast.makeText(this@PlayerActivity, R.string.playback_error, Toast.LENGTH_SHORT).show()
        val p = player ?: return
        if (p.hasNextMediaItem()) {
          p.seekToNextMediaItem()
          p.prepare()
        }
      }
    })
    player = exo
    setupTransportOverrides()
  }

  /**
   * Wires double-tap-to-seek on the left and right bands of the player view,
   * MX Player style. Tap the right side once to skip forward 10s; tap quickly
   * again on the same side and the on-screen indicator accumulates ("+20s",
   * "+30s", "+1m"). The middle 20% of the screen is a dead zone so accidental
   * taps near the play/pause row do not trigger a seek.
   */
  @SuppressLint("ClickableViewAccessibility")
  private fun setupDoubleTapSeek() {
    binding.playerView.controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
    // The Media3 controller paints a full-screen "scrim" view behind its
    // buttons that dims the video whenever it is shown. We keep the bar
    // itself (buttons + progress) but make that scrim transparent so the
    // picture never darkens when the controller appears.
    binding.playerView
      .findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)
      ?.setBackgroundColor(Color.TRANSPARENT)
    val detector = GestureDetector(
      this,
      object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        // Suppress the default PlayerView toggle on every tap. We will
        // perform the toggle ourselves only after a tap has been confirmed
        // as a single tap (i.e. no double-tap follows within the window).
        // This stops the controller from flickering during rapid consecutive
        // double-taps used for seeking.
        override fun onSingleTapUp(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
          with(binding.playerView) {
            if (isControllerFullyVisible) hideController() else showController()
          }
          return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
          val width = binding.playerView.width
          if (width <= 0) return false
          val frac = e.x / width.toFloat()
          val direction = when {
            frac < DEAD_ZONE_START -> -1
            frac > DEAD_ZONE_END -> +1
            else -> return false
          }
          handleDoubleTapSeek(direction)
          // Keep the controller visible across rapid consecutive seeks; its
          // scrim is transparent (see setupDoubleTapSeek) so the bar shows
          // without dimming the video. Its own timer hides it again
          // CONTROLLER_TIMEOUT_MS after the last tap.
          binding.playerView.showController()
          return true
        }

        // Consume the rest of the double-tap gesture (MOVE/UP) so PlayerView
        // does not toggle the controller on the second tap's UP event.
        override fun onDoubleTapEvent(e: MotionEvent): Boolean = true
      },
    )
    binding.playerView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
  }

  private fun handleDoubleTapSeek(direction: Int) {
    val p = player ?: return
    val now = SystemClock.uptimeMillis()
    val sameDirection = direction == seekDirection
    val withinWindow = now - lastSeekTimeMs <= SEEK_ACCUM_WINDOW_MS

    consecutiveDoubleTaps =
      if (sameDirection && withinWindow) consecutiveDoubleTaps + 1 else 1

    // Each consecutive same-direction tap grows the step linearly by the base
    // unit: 5s, 10s, 15s, 20s, ... The per-tap step is capped at 1/10 of the
    // total video length so it scales with content: short clips take small
    // steps, long movies allow large jumps. Falls back to an uncapped ramp
    // when the duration is unknown (e.g. live streams).
    val durationMs = p.duration
    val maxStepSec =
      if (durationMs > 0) (durationMs / 1000L / 10L).coerceAtLeast(SEEK_STEP_SEC)
      else Long.MAX_VALUE
    val stepSec = (consecutiveDoubleTaps * SEEK_STEP_SEC).coerceAtMost(maxStepSec)
    val deltaSec = direction * stepSec

    seekAccumSec =
      if (sameDirection && withinWindow) seekAccumSec + deltaSec else deltaSec
    seekDirection = direction
    lastSeekTimeMs = now

    val duration = durationMs.coerceAtLeast(0L)
    val rawTarget = p.currentPosition + deltaSec * 1000L
    if (direction > 0 && duration > 0 && rawTarget >= duration) {
      // The forward tap overshoots the end. A CLOSEST_SYNC seek to the end
      // snaps back to the previous keyframe (often 8-10s earlier), trapping
      // the user near the end. Seek EXACTLY to ~1s before the end instead so
      // the clip plays out and ExoPlayer auto-advances to the next video.
      val tail = (duration - END_TAIL_MS).coerceIn(0L, duration)
      p.setSeekParameters(SeekParameters.EXACT)
      p.seekTo(tail.coerceAtLeast(p.currentPosition))
      p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    } else {
      val target = rawTarget.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
      p.seekTo(target)
    }
    showSeekIndicator()
  }

  private fun showSeekIndicator() {
    val target = if (seekDirection >= 0) binding.seekIndicatorRight else binding.seekIndicatorLeft
    val other = if (seekDirection >= 0) binding.seekIndicatorLeft else binding.seekIndicatorRight
    other.visibility = View.GONE
    other.alpha = 1f
    target.text = formatSeek(seekAccumSec)
    target.alpha = 1f
    target.visibility = View.VISIBLE
    seekHandler.removeCallbacks(hideSeekIndicator)
    seekHandler.postDelayed(hideSeekIndicator, SEEK_HIDE_DELAY_MS)
  }

  private fun formatSeek(secondsSigned: Long): String {
    val sign = if (secondsSigned >= 0) "+" else "-"
    val abs = kotlin.math.abs(secondsSigned)
    val minutes = abs / 60
    val seconds = abs % 60
    return when {
      minutes == 0L -> "${sign}${seconds}s"
      seconds == 0L -> "${sign}${minutes}m"
      else -> "${sign}${minutes}m${seconds}s"
    }
  }

  /**
   * When only one file is open without a folder grant, next/previous opens the
   * grant flow (SAF picker or all-files dialog) instead of doing nothing.
   */
  private fun setupTransportOverrides() {
    binding.playerView.post {
      val next = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_next)
      val prev = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_prev)
      next?.setOnClickListener {
        if (showBanner()) onBannerClicked() else player?.seekToNext()
      }
      prev?.setOnClickListener {
        if (showBanner()) onBannerClicked() else player?.seekToPrevious()
      }
    }
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
    if (player == null && (playlist.isNotEmpty() || pendingSingleUri != null)) {
      if (playlist.isNotEmpty()) {
        startPlaylist(playlist, startIndex, startPositionMs)
      } else {
        pendingSingleUri?.let { uri ->
          // Re-evaluate now that we may have just been granted all-files access.
          openExternalVideo(uri)
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Returning from the all-files settings page may have flipped the toggle.
    if (isFdroidFlavor && AllFilesAccess.isGranted) {
      binding.folderGrantBanner.visibility = View.GONE
      pendingSingleUri?.let { openExternalVideo(it) }
    }
    // Refresh the empty-state primary button label (its text depends on the
    // current permission state).
    if (binding.emptyState.visibility == View.VISIBLE) showEmptyState()
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
    private const val KEY_FILE_FOLDER = "last_file_folder"
    private const val KEY_ORIENT = "orientation_mode"
    private const val KEY_ALL_FILES_DIALOG_DISMISSED = "all_files_dialog_dismissed"

    private const val MODE_AUTO = 0
    private const val MODE_LANDSCAPE = 1
    private const val MODE_PORTRAIT = 2

    /** Base seek unit. The Nth consecutive same-direction double-tap jumps
     *  N * SEEK_STEP_SEC seconds (5s, 10s, 15s, ...), capped at 1/10 of the
     *  total video duration. */
    private const val SEEK_STEP_SEC = 5L
    /** When a forward seek overshoots the end, land this far before the end
     *  (exact seek) so the clip plays out and auto-advances to the next. */
    private const val END_TAIL_MS = 1000L
    private const val SEEK_ACCUM_WINDOW_MS = 800L
    private const val SEEK_HIDE_DELAY_MS = 700L
    private const val DEAD_ZONE_START = 0.40f
    private const val DEAD_ZONE_END = 0.60f
    private const val CONTROLLER_TIMEOUT_MS = 3000
  }
}
