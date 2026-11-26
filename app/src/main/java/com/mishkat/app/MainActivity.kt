package com.mishkat.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    inner class NativeAudioBridge {
        @JavascriptInterface
        fun onPlay(title: String?, artist: String?, portrait: String?) {
            if (!isTrustedPage()) return
            val intent = Intent(this@MainActivity, AudioPlaybackService::class.java).apply {
                action = AudioPlaybackService.ACTION_PLAY
                putExtra(AudioPlaybackService.EXTRA_TITLE, title?.take(160) ?: "مِشكاة")
                putExtra(AudioPlaybackService.EXTRA_ARTIST, artist?.take(160) ?: "مِشكاة")
                putExtra(AudioPlaybackService.EXTRA_ARTWORK_URL, portrait?.take(4096) ?: "")
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun onBackground(title: String?, url: String?, positionSeconds: Double, artist: String?, portrait: String?): Boolean {
            if (!isTrustedPage()) return false
            val safeUrl = url?.takeIf { it.startsWith("https://") && it.length <= 4096 } ?: return false
            val intent = Intent(this@MainActivity, AudioPlaybackService::class.java).apply {
                action = AudioPlaybackService.ACTION_BACKGROUND
                putExtra(AudioPlaybackService.EXTRA_TITLE, title?.take(160) ?: "مِشكاة")
                putExtra(AudioPlaybackService.EXTRA_ARTIST, artist?.take(160) ?: "مِشكاة")
                putExtra(AudioPlaybackService.EXTRA_ARTWORK_URL, portrait?.take(4096) ?: "")
                putExtra(AudioPlaybackService.EXTRA_URL, safeUrl)
                putExtra(AudioPlaybackService.EXTRA_POSITION_MS, (positionSeconds.coerceAtLeast(0.0) * 1000.0).toLong())
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
                true
            } catch (_: Exception) { false }
        }

        @JavascriptInterface
        fun onForeground(): Long {
            if (!isTrustedPage()) return -1L
            val position = AudioPlaybackService.currentPositionMs()
            stopService(Intent(this@MainActivity, AudioPlaybackService::class.java))
            return position
        }

        @JavascriptInterface
        fun onPause() {
            if (!isTrustedPage()) return
            val intent = Intent(this@MainActivity, AudioPlaybackService::class.java).apply {
                action = AudioPlaybackService.ACTION_MEDIA_PAUSE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun onStop() {
            if (!isTrustedPage()) return
            stopService(Intent(this@MainActivity, AudioPlaybackService::class.java))
        }
    }

    inner class NativeNotificationsBridge {
        @JavascriptInterface
        fun schedulePrayerDay(date: String, timeZone: String, timesJson: String, completedJson: String) {
            if (!isTrustedPage()) return
            MishkatNotificationScheduler.setTimeZone(this@MainActivity, timeZone)
            MishkatNotificationScheduler.schedulePrayerDay(
                this@MainActivity, date, timeZone, timesJson, completedJson
            )
        }

        @JavascriptInterface
        fun updatePrayerStatuses(date: String, completedJson: String) {
            if (!isTrustedPage()) return
            MishkatNotificationScheduler.updatePrayerStatuses(this@MainActivity, date, completedJson)
        }

        @JavascriptInterface
        fun setAdhkarNotifications(enabled: Boolean) {
            if (!isTrustedPage()) return
            MishkatNotificationScheduler.setAdhkarEnabled(this@MainActivity, enabled)
        }
    }

    private fun isTrustedPage(): Boolean =
        ::webView.isInitialized && (webView.url?.startsWith("https://mishkatt.framer.website") == true)

    private fun requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2402)
            return
        }
        requestExactAlarmAccess()
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            val prefs = getSharedPreferences("mishkat_notifications", MODE_PRIVATE)
            if (!alarmManager.canScheduleExactAlarms() && !prefs.getBoolean("exact_alarm_prompted", false)) {
                prefs.edit().putBoolean("exact_alarm_prompted", true).apply()
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) { }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2402) requestExactAlarmAccess()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        MishkatNotificationScheduler.createChannels(this)
        MishkatNotificationScheduler.restoreSchedules(this)
        MishkatNotificationScheduler.setAdhkarEnabled(this, true)
        requestNotificationAccess()

        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.rgb(24, 18, 22))

            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val topInset = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.displayCutout()
                ).top
                view.setPadding(0, topInset, 0, 0)
                insets
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_DEFAULT
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = false
            }

            addJavascriptInterface(NativeAudioBridge(), "MishkatNativeAudio")
            addJavascriptInterface(NativeNotificationsBridge(), "MishkatNativeNotifications")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (!url.startsWith("https://mishkatt.framer.website")) return
                    view.evaluateJavascript(
                        """
                        (function() {
                          if (window.__mishkatNativeAudioHooked) return;
                          window.__mishkatNativeAudioHooked = true;

                          function currentMeta(media) {
                            var m = window.__mishkatNowPlaying || {};
                            return {
                              title: m.title || (media && (media.getAttribute('aria-label') || media.getAttribute('title'))) || document.title || 'تلاوة قرآنية',
                              artist: m.artist || 'مِشكاة',
                              portrait: m.portrait || ''
                            };
                          }

                          function activeMedia() {
                            return Array.prototype.find.call(
                              document.querySelectorAll('audio,video'),
                              function(el) { return !el.paused && !el.ended && el.currentSrc; }
                            );
                          }

                          function handoff(media) {
                            if (!media || !media.currentSrc || !window.MishkatNativeAudio) return false;
                            var m = currentMeta(media);
                            return window.MishkatNativeAudio.onBackground(
                              String(m.title).trim(), media.currentSrc, Number(media.currentTime || 0),
                              String(m.artist), String(m.portrait)
                            );
                          }

                          document.addEventListener('mishkat-audio-start', function(event) {
                            var d = event.detail || {};
                            window.__mishkatNowPlaying = {
                              audio: d.audio || null,
                              title: d.title || '',
                              artist: d.artist || '',
                              portrait: d.portrait || d.image || ''
                            };
                          }, true);

                          document.addEventListener('play', function(event) {
                            var el = event.target;
                            if (!(el instanceof HTMLMediaElement)) return;
                            window.setTimeout(function() {
                              var m = currentMeta(el);
                              if (document.visibilityState === 'hidden') {
                                handoff(el);
                              } else if (window.MishkatNativeAudio) {
                                window.MishkatNativeAudio.onPlay(String(m.title).trim(), String(m.artist), String(m.portrait));
                              }
                            }, 80);
                          }, true);

                          function notifyNativePause() {
                            window.setTimeout(function() {
                              if (document.visibilityState !== 'hidden' && !activeMedia() && window.MishkatNativeAudio) {
                                window.MishkatNativeAudio.onPause();
                              }
                            }, 220);
                          }

                          function notifyNativeStop() {
                            window.setTimeout(function() {
                              if (document.visibilityState !== 'hidden' && !activeMedia() && window.MishkatNativeAudio) {
                                window.MishkatNativeAudio.onStop();
                              }
                            }, 220);
                          }

                          function handleMediaPause() {
                            if (document.visibilityState === 'hidden') return;
                            notifyNativePause();
                          }

                          document.addEventListener('pause', handleMediaPause, true);
                          document.addEventListener('ended', notifyNativeStop, true);

                          document.addEventListener('visibilitychange', function() {
                            if (document.visibilityState === 'hidden') {
                              var media = activeMedia();
                              if (media) {
                                handoff(media);
                                media.pause();
                              }
                            } else {
                              var resumedAt = Number(window.MishkatNativeAudio.onForeground());
                              if (resumedAt >= 0) {
                                var media = window.__mishkatRequestedAudio || document.querySelector('audio,video');
                                if (media) {
                                  media.currentTime = resumedAt / 1000;
                                  media.play().catch(function() {});
                                }
                              }
                              notifyNativePause();
                            }
                          });
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }
        }

        AudioPlaybackService.nativeActionHandler = { action ->
            runOnUiThread {
                if (!::webView.isInitialized) return@runOnUiThread
                val script = when {
                    action == "next" || action == "previous" -> {
                        val direction = if (action == "next") 1 else -1
                        "(function(){var a=window.__mishkatRequestedAudio||window.__mishkatActiveAudio;if(a)window.dispatchEvent(new CustomEvent('mishkat-track-step',{detail:{audio:a,direction:$direction}}));})()"
                    }
                    action == "play" -> "(function(){var a=window.__mishkatRequestedAudio||window.__mishkatActiveAudio;if(a&&a.paused)a.play().catch(function(){});})()"
                    action == "pause" -> "(function(){var a=window.__mishkatRequestedAudio||window.__mishkatActiveAudio;if(a&&!a.paused)a.pause();})()"
                    action == "toggle" -> "(function(){var a=window.__mishkatRequestedAudio||window.__mishkatActiveAudio;if(a){if(a.paused)a.play().catch(function(){});else a.pause();}})()"
                    action.startsWith("seek:") -> {
                        val position = action.substringAfter("seek:").toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                        "(function(){var a=window.__mishkatRequestedAudio||window.__mishkatActiveAudio;if(a)a.currentTime=$position/1000;})()"
                    }
                    else -> ""
                }
                if (script.isNotBlank()) webView.evaluateJavascript(script, null)
            }
        }

        setContentView(webView)
        ViewCompat.requestApplyInsets(webView)

        if (savedInstanceState == null) {
            val cachePrefs = getSharedPreferences("mishkat_web_cache", MODE_PRIVATE)
            val installedVersion = packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            if (cachePrefs.getLong("version", -1L) != installedVersion) {
                webView.clearCache(true)
                cachePrefs.edit().putLong("version", installedVersion).apply()
            }
            webView.loadUrl("https://mishkatt.framer.website")
        } else webView.restoreState(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        MishkatNotificationScheduler.restoreSchedules(this)
        MishkatNotificationScheduler.setAppInBackground(this, false)
    }

    override fun onPause() {
        MishkatNotificationScheduler.setAppInBackground(this, true)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        AudioPlaybackService.nativeActionHandler = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
