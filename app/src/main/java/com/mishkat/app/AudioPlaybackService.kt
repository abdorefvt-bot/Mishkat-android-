package com.mishkat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

class AudioPlaybackService : Service() {
    private var mediaSession: MediaSessionCompat? = null
    private var playbackWakeLock: PowerManager.WakeLock? = null
    private var backgroundPlayer: MediaPlayer? = null
    private var initialPositionMs: Long = 0L
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(AudioManager::class.java)
        val powerManager = getSystemService(PowerManager::class.java)
        playbackWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mishkat:AudioPlayback").apply {
            setReferenceCounted(false)
            acquire()
        }
        mediaSession = MediaSessionCompat(this, "MishkatAudio").apply {
            setCallback(object : MediaSessionCompat.Callback() {})
            isActive = true
        }
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "مِشكاة"
        if (intent?.action == ACTION_BACKGROUND) {
            val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("https://") }
            if (url.isNullOrBlank()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            initialPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L).coerceAtLeast(0L)
            startForeground(NOTIFICATION_ID, buildNotification(title))
            startBackgroundPlayer(url, initialPositionMs)
            return START_NOT_STICKY
        }

        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "مِشكاة")
                .build()
        )
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        startForeground(NOTIFICATION_ID, buildNotification(title))
        return START_NOT_STICKY
    }

    private fun startBackgroundPlayer(url: String, positionMs: Long) {
        releasePlayer()
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
                if (positionMs > 0L) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) it.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
                    else it.seekTo(positionMs.toInt())
                }
                requestAudioFocus()
                it.start()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
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
                    if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) backgroundPlayer?.pause()
                    else if (change == AudioManager.AUDIOFOCUS_GAIN && backgroundPlayer?.isPlaying == false) {
                        try { backgroundPlayer?.start() } catch (_: Exception) {}
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

    private fun updatePlaybackState(state: Int) {
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun releasePlayer() {
        try { backgroundPlayer?.release() } catch (_: Exception) {}
        backgroundPlayer = null
    }

    private fun buildNotification(title: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText("تشغيل التلاوة · مِشكاة")
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(MediaStyle().setMediaSession(mediaSession?.sessionToken))
            .build()
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PLAY = "com.mishkat.app.action.PLAY"
        const val ACTION_BACKGROUND = "com.mishkat.app.action.BACKGROUND"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        private const val CHANNEL_ID = "mishkat_audio_playback"
        private const val NOTIFICATION_ID = 2401

        @Volatile private var activeInstance: AudioPlaybackService? = null

        fun currentPositionMs(): Long {
            val service = activeInstance ?: return -1L
            val player = service.backgroundPlayer ?: return service.initialPositionMs
            return try {
                if (player.isPlaying || player.currentPosition >= 0) player.currentPosition.toLong()
                else service.initialPositionMs
            } catch (_: Exception) {
                service.initialPositionMs
            }
        }
    }
}
