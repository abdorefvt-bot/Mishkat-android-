package com.mishkat.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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

    inner class NativeNotificationsBridge {
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

            addJavascriptInterface(NativeNotificationsBridge(), "MishkatNativeNotifications")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (!url.startsWith("https://mishkatt.framer.website")) return
                }
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
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
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
