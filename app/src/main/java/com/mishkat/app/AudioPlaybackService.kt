package com.mishkat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import java.io.BufferedInputStream
import java.net.URL
import java.util.concurrent.Executors

class AudioPlaybackService : Service() {
    private var mediaSession: MediaSessionCompat? = null
    private var playbackWakeLock: PowerManager.WakeLock? = null
    private var backgroundPlayer: MediaPlayer? = null
    private var initialPositionMs: Long = 0L
    private var durationMs: Long = 0L
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentTitle = "تلاوة قرآنية"
    private var currentArtist = "القرآن الكريم"
    private var artworkUrl: String? = null
    private var artwork: Bitmap? = null
    private var foregroundStarted = false
    private var pagePlaying = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(AudioManager::class.java)
        val powerManager = getSystemService(PowerManager::class.java)
        playbackWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mishkat:AudioPlayback").apply {
            setReferenceCounted(false)
        }
        mediaSession = MediaSessionCompat(this, "MishkatAudio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (backgroundPlayer != null) resumePlayer() else {
                        pagePlaying = true
                        nativeActionHandler?.invoke("play")
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                        updateNotification()
                    }
                }

                override fun onPause() {
                    if (backgroundPlayer != null) pausePlayer() else {
                        pagePlaying = false
                        nativeActionHandler?.invoke("pause")
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                        updateNotification()
                    }
                }

                override fun onSeekTo(pos: Long) {
                    if (backgroundPlayer != null) seekPlayer(pos) else nativeActionHandler?.invoke("seek:" + pos)
                }

                override fun onSkipToNext() {
                    nativeActionHandler?.invoke("next")
                }

                override fun onSkipToPrevious() {
                    nativeActionHandler?.invoke("previous")
                }

                override fun onStop() {
                    stopSelf()
                }
            })
            isActive = true
        }
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val receivedAction = intent?.action
        currentTitle = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: currentTitle
        currentArtist = intent?.getStringExtra(EXTRA_ARTIST)?.takeIf { it.isNotBlank() } ?: currentArtist
        val newArtworkUrl = intent?.getStringExtra(EXTRA_ARTWORK_URL)?.takeIf { it.startsWith("https://") }
        if (newArtworkUrl != null && newArtworkUrl != artworkUrl) {
            artworkUrl = newArtworkUrl
            artwork = null
            loadArtwork(newArtworkUrl)
        }
        updateMetadata()

        when (receivedAction) {
            ACTION_BACKGROUND -> {
                val url = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("https://") }
                if (url.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                initialPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L).coerceAtLeast(0L)
                pagePlaying = true
                startInForeground()
                startBackgroundPlayer(url, initialPositionMs)
            }
            ACTION_MEDIA_PLAY_PAUSE -> {
                if (backgroundPlayer != null) {
                    if (isPlayerPlaying()) pausePlayer() else resumePlayer()
                } else {
                    pagePlaying = !pagePlaying
                    nativeActionHandler?.invoke(if (pagePlaying) "play" else "pause")
                    updatePlaybackState(if (pagePlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
                    updateNotification()
                }
            }
            ACTION_PLAY, ACTION_MEDIA_PLAY -> {
                if (backgroundPlayer != null) {
                    resumePlayer()
                } else {
                    pagePlaying = true
                    mediaSession?.isActive = true
                    startInForeground()
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    updateNotification()
                }
            }
            ACTION_MEDIA_PAUSE -> {
                if (backgroundPlayer != null) pausePlayer() else {
                    pagePlaying = false
                    nativeActionHandler?.invoke("pause")
                    startInForeground()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    updateNotification()
                }
            }
            ACTION_MEDIA_PREVIOUS -> nativeActionHandler?.invoke("previous")
            ACTION_MEDIA_NEXT -> nativeActionHandler?.invoke("next")
            else -> {
                pagePlaying = true
                startInForeground()
                if (backgroundPlayer == null) updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
        foregroundStarted = true
    }

    private fun startBackgroundPlayer(url: String, positionMs: Long) {
        releasePlayer()
        durationMs = 0L
        try {
            val player = MediaPlayer()
            backgroundPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setOnPreparedListener {
                durationMs = it.duration.toLong().coerceAtLeast(0L)
                if (positionMs > 0L) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) it.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
                    else it.seekTo(positionMs.toInt())
                }
                requestAudioFocus()
                it.start()
                playbackWakeLock?.let { lock -> if (!lock.isHeld) lock.acquire() }
                updateMetadata()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                updateNotification()
            }
            player.setOnCompletionListener {
                initialPositionMs = it.duration.toLong()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                stopSelf()
            }
            player.setOnErrorListener { _, _, _ ->
                stopSelf()
                true
            }
            player.setOnSeekCompleteListener {
                updatePlaybackState(if (isPlayerPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
            }
            player.setDataSource(url)
            player.prepareAsync()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        pausePlayer()
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN && !isPlayerPlaying()) {
                        resumePlayer()
                    }
                }
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun resumePlayer() {
        val player = backgroundPlayer
        if (player == null) {
            pagePlaying = true
            nativeActionHandler?.invoke("play")
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            updateNotification()
            return
        }
        try {
            if (!player.isPlaying) player.start()
            pagePlaying = true
            playbackWakeLock?.let { if (!it.isHeld) it.acquire() }
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            updateNotification()
        } catch (_: Exception) { }
    }

    private fun pausePlayer() {
        val player = backgroundPlayer
        if (player == null) {
            pagePlaying = false
            nativeActionHandler?.invoke("pause")
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            updateNotification()
            return
        }
        try {
            if (player.isPlaying) player.pause()
            pagePlaying = false
            playbackWakeLock?.let { if (it.isHeld) it.release() }
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            updateNotification()
        } catch (_: Exception) { }
    }

    private fun seekPlayer(positionMs: Long) {
        val player = backgroundPlayer
        if (player == null) {
            nativeActionHandler?.invoke("seek:" + positionMs)
            return
        }
        try {
            val safePosition = positionMs.coerceIn(0L, if (durationMs > 0L) durationMs else Long.MAX_VALUE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) player.seekTo(safePosition, MediaPlayer.SEEK_CLOSEST)
            else player.seekTo(safePosition.toInt())
            initialPositionMs = safePosition
        } catch (_: Exception) { }
    }

    private fun isPlayerPlaying(): Boolean = try { backgroundPlayer?.isPlaying == true } catch (_: Exception) { false }
    private fun isNotificationPlaying(): Boolean = if (backgroundPlayer != null) isPlayerPlaying() else pagePlaying

    private fun updateMetadata() {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "القرآن الكريم")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        artwork?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) }
        mediaSession?.setMetadata(builder.build())
    }

    private fun updatePlaybackState(state: Int) {
        val position = try {
            backgroundPlayer?.currentPosition?.toLong() ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        } catch (_: Exception) {
            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        }
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
                .build()
        )
    }

    private fun loadArtwork(url: String) {
        artworkExecutor.execute {
            val bitmap = try {
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "Mishkat Android")
                }
                connection.getInputStream().use { raw ->
                    val input = BufferedInputStream(raw)
                    input.mark(2 * 1024 * 1024)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, bounds)
                    input.reset()
                    val sample = if (bounds.outWidth > 1024 || bounds.outHeight > 1024) 2 else 1
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeStream(input, null, options)
                }
            } catch (_: Exception) { null }
            if (bitmap != null) {
                mainHandler.post {
                    if (artworkUrl == url) {
                        artwork = bitmap
                        updateMetadata()
                        updateNotification()
                    } else {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) { }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val previous = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_previous, "التلاوة السابقة",
            servicePendingIntent(ACTION_MEDIA_PREVIOUS, 1)
        ).build()
        val playPause = NotificationCompat.Action.Builder(
            if (isNotificationPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isNotificationPlaying()) "إيقاف مؤقت" else "تشغيل",
            servicePendingIntent(ACTION_MEDIA_PLAY_PAUSE, 2)
        ).build()
        val next = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_next, "التلاوة التالية",
            servicePendingIntent(ACTION_MEDIA_NEXT, 3)
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mishkat)
            .setLargeIcon(artwork)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(previous)
            .addAction(playPause)
            .addAction(next)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مشغل التلاوة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "مشغل التلاوة القرآنية في الخلفية"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun releasePlayer() {
        try { backgroundPlayer?.release() } catch (_: Exception) { }
        backgroundPlayer = null
    }

    override fun onDestroy() {
        releasePlayer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
        audioFocusRequest = null
        audioManager = null
        activeInstance = null
        playbackWakeLock?.let { if (it.isHeld) it.release() }
        playbackWakeLock = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        foregroundStarted = false
        artworkExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "mishkat_media_playback_v2"
        private const val NOTIFICATION_ID = 4701

        const val ACTION_PLAY = "com.mishkat.app.action.PLAY"
        const val ACTION_BACKGROUND = "com.mishkat.app.action.BACKGROUND"
        const val ACTION_MEDIA_PLAY_PAUSE = "com.mishkat.app.action.MEDIA_PLAY_PAUSE"
        const val ACTION_MEDIA_PLAY = "com.mishkat.app.action.MEDIA_PLAY"
        const val ACTION_MEDIA_PAUSE = "com.mishkat.app.action.MEDIA_PAUSE"
        const val ACTION_MEDIA_PREVIOUS = "com.mishkat.app.action.MEDIA_PREVIOUS"
        const val ACTION_MEDIA_NEXT = "com.mishkat.app.action.MEDIA_NEXT"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_ARTWORK_URL = "extra_artwork_url"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_POSITION_MS = "extra_position_ms"

        @Volatile var nativeActionHandler: ((String) -> Unit)? = null
        @Volatile private var activeInstance: AudioPlaybackService? = null

        fun currentPositionMs(): Long {
            val service = activeInstance ?: return -1L
            val player = service.backgroundPlayer ?: return -1L
            return try {
                if (player.isPlaying) player.currentPosition.toLong() else -2L
            } catch (_: Exception) {
                -1L
            }
        }
    }
}
