package com.haviq.richmusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private var serviceBound = false
    private var audioService: AudioService? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunning = false

    private lateinit var webViewContainer: FrameLayout
    private var uiWebView: WebView? = null
    private var playerView: androidx.media3.ui.PlayerView? = null
    private lateinit var jsBridge: JSBridge
    private var videoMode = false
    private var adView: com.google.android.gms.ads.AdView? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            audioService = (binder as AudioService.LocalBinder).getService()
            serviceBound = true

            // Engine ticks → UI WebView
            AudioService.onTick = { state, posSec, durSec ->
                pushToJs("window.__rmNativeUpdate && window.__rmNativeUpdate($state, $posSec, $durSec)")
            }
            AudioService.onEnded = {
                pushToJs("window.__rmNativeUpdate && window.__rmNativeUpdate(0, 0, ${AudioService.player?.duration?.div(1000) ?: 0})")
            }
            AudioService.onError = { msg ->
                pushToJs("window.__rmOnError && window.__rmOnError(${jsQuote(msg)})")
            }
            AudioService.onUiCommand = { js -> pushToJs(js) }
            playerView?.player = AudioService.player
            startProgressTicker()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            audioService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webViewContainer = findViewById(R.id.webview_container)

        // AdMob banner (test unit — swap in real unit ID before release)
        com.google.android.gms.ads.MobileAds.initialize(this) {}
        adView = findViewById(R.id.ad_banner)
        adView?.loadAd(com.google.android.gms.ads.AdRequest.Builder().build())

        ensureNotificationPermission()
        StreamResolver.init(applicationContext)

        // RECORD_AUDIO is a runtime permission — required for android.media.audiofx.Visualizer
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4242)
        }

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            overlayPermLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        createUiWebView()

        val intent = Intent(this, AudioService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createUiWebView() {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString.replace(
                Regex("[;,]\\s*wv\\b", RegexOption.IGNORE_CASE), ""
            )
        }
        wv.setBackgroundColor(0xFF0A0A0A.toInt())
        wv.webViewClient = buildUiWebViewClient()
        jsBridge = JSBridge()
        wv.addJavascriptInterface(jsBridge, "RichMusicBridge")
        webViewContainer.addView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        uiWebView = wv

        // v1.4.1: inflate from XML — surface_type=texture_view (SurfaceView loses Z-order to WebView → black)
        val pv = layoutInflater.inflate(R.layout.player_view, webViewContainer, false) as androidx.media3.ui.PlayerView
        pv.visibility = android.view.View.GONE
        pv.setBackgroundColor(0xFF000000.toInt())
        webViewContainer.addView(pv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        playerView = pv
        AudioService.player?.let { pv.player = it }

        wv.loadUrl("https://rythmix-music.vercel.app")
    }

    private fun buildUiWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val u = url ?: return false
            if (u.startsWith("http://") || u.startsWith("https://")) return false
            return true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            val host = request.url.host ?: ""
            val path = request.url.path ?: ""
            if (host.endsWith("doubleclick.net") || host.endsWith("googlesyndication.com") ||
                host.endsWith("google-analytics.com") || host.endsWith("googletagmanager.com") ||
                host.endsWith("scorecardresearch.com") || host.endsWith("adservice.google.com") ||
                host.endsWith("imasdk.googleapis.com") || host.endsWith("googletagservices.com") ||
                host.endsWith("2mdn.net") || host.endsWith("admob.com") || host.endsWith("ads.yimg.com") ||
                host.startsWith("ad.") || host.startsWith("ads.") || host.endsWith("googleadservices.com") ||
                host.contains("pagead") || host.contains("/api/stats/ads") ||
                (host.endsWith("youtube.com") && (path.startsWith("/api/stats/ads") || path.startsWith("/pagead/") ||
                    path.startsWith("/ptracking") || path.contains("get_midroll_info") ||
                    path.contains("/ad_break") || path.startsWith("/api/stats/qoe") ||
                    (path == "/generate_204" && url.contains("adformat")))) ||
                (host.endsWith("googlevideo.com") && (url.contains("&adformat") || url.contains("ad_type=") ||
                    url.contains("oadt=") || url.contains("adunit") || url.contains("&ctier=")))) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // v1.6: mic granted → attach EQ/Visualizer SEKARANG (dulu tak ada callback → viz mati selamanya)
        if (requestCode == 4242 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            AudioService.player?.let { AudioService.attachAudioFx(it.audioSessionId) }
            pushToJs("window.__rmMicGranted && window.__rmMicGranted()")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (videoMode || jsBridge.engineVideoActive) { // back exits video first, keeps playing audio
            setVideoModeUi(false)
            jsBridge.engineVideoActive = false
            AudioService.showEngineVideo(false)
            pushToJs("window.__rmVideoToggle && window.__rmVideoToggle(false)")
            return
        }
        val wv = uiWebView
        if (wv != null && wv.canGoBack()) wv.goBack()
        else moveTaskToBack(true) // hide, don't destroy
    }

    private fun setVideoModeUi(on: Boolean) {
        videoMode = on
        runOnUiThread {
            playerView?.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            if (!on) uiWebView?.bringToFront()
        }
    }

    override fun onResume() {
        super.onResume()
        uiWebView?.onResume()
        adView?.resume()
    }

    override fun onPause() {
        super.onPause()
        adView?.pause()
        // NO webView.onPause() — app hidden, engine keeps playing
    }

    override fun onDestroy() {
        progressRunning = false
        progressHandler.removeCallbacksAndMessages(null)
        adView?.destroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun startProgressTicker() {
        progressRunning = true
        progressHandler.post(object : Runnable {
            override fun run() {
                if (!progressRunning) return
                val p = AudioService.player
                if (p != null) {
                    AudioService.pushTick(
                        AudioService.youtubeState(p),
                        p.currentPosition / 1000,
                        p.duration / 1000
                    )
                }
                progressHandler.postDelayed(this, 250)
            }
        })
    }

    fun pushToJs(script: String) {
        runOnUiThread { uiWebView?.evaluateJavascript(script, null) }
    }

    /** OTA update: DownloadManager fetches the APK, then opens the system installer. */
    fun startApkInstall(url: String) {
        try {
            val req = android.app.DownloadManager.Request(Uri.parse(url))
                .setTitle("Rythmix update")
                .setDescription("Mengunduh pembaruan Rythmix…")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "rythmix-update.apk")
                .setMimeType("application/vnd.android.package-archive")
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val id = dm.enqueue(req)
            // poll until done, then hand off to installer (bail out after 10 min stuck)
            val h = Handler(Looper.getMainLooper())
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
            fun poll() {
                if (System.currentTimeMillis() > deadline) {
                    pushToJs("toast('⚠️ Update lambat, cek notifikasi download')"); return
                }
                val q = android.app.DownloadManager.Query().setFilterById(id)
                dm.query(q)?.use { c ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                        if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                            val uri = dm.getUriForDownloadedFile(id)
                            val i = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { startActivity(i) } catch (e: Exception) {}
                            return
                        }
                        if (status == android.app.DownloadManager.STATUS_FAILED) {
                            pushToJs("toast('❌ Update gagal diunduh')"); return
                        }
                    }
                }
                h.postDelayed(::poll, 1500)
            }
            poll()
        } catch (e: Exception) {
            pushToJs("toast('❌ Update gagal: ${e.message?.replace("'", "") ?: "error"}')")
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val overlayPermLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    // JS bridge — exposed as window.RichMusicBridge on the UI WebView.
    // Playback happens in AudioService (ExoPlayer resolved via StreamResolver);
    // engine WebView only used when ExoPlayer path unavailable.
    inner class JSBridge {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        @Volatile private var lastVideoId: String? = null
        @Volatile private var lastTitle: String? = null
        @Volatile private var lastArtist: String? = null
        @Volatile private var playRequested = false
        // v1.7: engine YT-iframe video fallback aktif — kontrol native di-route ke engine.
        // v2.1: setter sinkronkan mirror flag ke AudioService (toggle notif ikut engine).
        @Volatile var engineVideoActive = false
            set(v) { field = v; AudioService.syncEngineFlag(v) }

        @JavascriptInterface
        fun appVersion(): Int = BuildConfig.VERSION_CODE

        @JavascriptInterface
        fun versionName(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun installApk(url: String) = startApkInstall(url)

        @JavascriptInterface
        fun play(videoId: String, title: String, artist: String, startSeconds: Double) {
            scope.launch {
                try {
                    val p0 = AudioService.player
                    if (p0 != null && AudioService.playGen == AudioService.mediaGen &&
                        p0.currentMediaItem?.localConfiguration?.uri != null) {
                        val cur = lastVideoId
                        if (cur == videoId && (p0.isPlaying || p0.playbackState == androidx.media3.common.Player.STATE_BUFFERING)) return@launch
                    }
                    lastVideoId = videoId
                    lastTitle = title; lastArtist = artist
                    AudioService.notifTitle = title; AudioService.notifArtist = artist
                    // v4.0.2: stop old item NOW + align gens so stray resume()/stale ticks
                    // can't resurrect the previous song (pause/resume blink on track switch)
                    AudioService.playGen++
                    AudioService.mediaGen = AudioService.playGen
                    AudioService.player?.stop()
                    val targetGen = AudioService.playGen
                    val info = StreamResolver.resolve(videoId, title, artist)
                    val p = AudioService.player ?: return@launch
                    if (targetGen != AudioService.playGen) return@launch
                    p.setMediaItem(
                        androidx.media3.common.MediaItem.Builder()
                            .setUri(Uri.parse(info.url))
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(info.title)
                                    .setArtist(info.artist)
                                    .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg"))
                                    .build()
                            )
                            .build(),
                        (startSeconds * 1000).toLong()
                    )
                    p.prepare()
                    p.play()
                    AudioService.mediaGen = targetGen
                    engineVideoActive = false
                } catch (e: Exception) {
                    // ExoPlayer path failed → try engine WebView IFrame (works in background via overlay)
                    engineVideoActive = true
                    AudioService.pushToAudioJs("window.player && mkPlayer ? mkPlayer(${jsQuote(videoId)}, $startSeconds) : null")
                    pushToJs("window.__rmEngineMode && window.__rmEngineMode()")
                }
            }
        }

        @JavascriptInterface
        fun playUrl(url: String, title: String, artist: String, startSeconds: Double) {
            scope.launch {
                try {
                    AudioService.notifTitle = title; AudioService.notifArtist = artist
                    AudioService.playGen++
                    val targetGen = AudioService.playGen
                    val p = AudioService.player ?: return@launch
                    if (targetGen != AudioService.playGen) return@launch
                    p.setMediaItem(
                        androidx.media3.common.MediaItem.Builder()
                            .setUri(Uri.parse(url))
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(artist)
                                    .build()
                            )
                            .build(),
                        (startSeconds * 1000).toLong()
                    )
                    p.prepare()
                    p.play()
                    AudioService.mediaGen = targetGen
                    val dur = p.duration / 1000
                    pushToJs("window.__rmNativeUpdate && window.__rmNativeUpdate(1, ${(startSeconds * 1000).toLong() / 1000}, $dur)")
                } catch (e: Exception) {
                    AudioService.onError?.invoke(e.message ?: "play failed")
                }
            }
        }

        @JavascriptInterface
        fun prepare(videoId: String, title: String, artist: String, startSeconds: Double) {
            scope.launch {
                try {
                    playRequested = false
                    AudioService.playGen++
                    val targetGen = AudioService.playGen
                    val info = StreamResolver.resolve(videoId, title, artist)
                    val p = AudioService.player ?: return@launch
                    if (targetGen != AudioService.playGen) return@launch
                    lastVideoId = videoId
                    lastTitle = title; lastArtist = artist
                    p.setMediaItem(
                        androidx.media3.common.MediaItem.Builder()
                            .setUri(Uri.parse(info.url))
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(info.title)
                                    .setArtist(info.artist)
                                    .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg"))
                                    .build()
                            )
                            .build(),
                        (startSeconds * 1000).toLong()
                    )
                    p.prepare()
                    AudioService.mediaGen = targetGen
                    if (playRequested) { playRequested = false; p.play() }
                } catch (e: Exception) {
                    AudioService.pushToAudioJs("window.player && mkPlayer ? mkPlayer(${jsQuote(videoId)}, $startSeconds) : null")
                }
            }
        }

        @JavascriptInterface fun seekTo(seconds: Double) {
            if (engineVideoActive) { AudioService.engineSeek(seconds); return }
            scope.launch { AudioService.player?.seekTo((seconds * 1000).toLong()) }
        }

        @JavascriptInterface fun pause() {
            if (engineVideoActive) { AudioService.enginePause(); return }
            scope.launch {
                AudioService.player?.pause()
                val p = AudioService.player ?: return@launch
                AudioService.pushTick(AudioService.youtubeState(p), p.currentPosition / 1000, p.duration / 1000)
            }
        }

        @JavascriptInterface fun resume() {
            if (engineVideoActive) { AudioService.enginePlay(); return }
            scope.launch {
                val p = AudioService.player ?: return@launch
                // v4.0.2: gens out of sync = old song's resume racing a fresh play() — drop it
                if (AudioService.playGen != AudioService.mediaGen) return@launch
                if (p.playbackState == androidx.media3.common.Player.STATE_ENDED) return@launch
                if (p.currentMediaItem == null) {
                    // v4.1.2: prepared-resolve failed earlier (dead cued track) → tap play
                    // must actually play: re-run the full play path for the last song
                    playRequested = false
                    val vid = lastVideoId
                    if (vid != null) play(vid, lastTitle ?: "", lastArtist ?: "", 0.0)
                    return@launch
                }
                playRequested = false
                AudioService.player?.play()
            }
        }

        @JavascriptInterface fun setVolume(v: Double) {
            scope.launch { AudioService.player?.volume = v.toFloat().coerceIn(0f, 1f) }
        }

        // ---- Equalizer ----
        @JavascriptInterface fun eqBands(): String {
            val e = AudioService.eq ?: return "[]"
            return try {
                val bands = (0 until e.numberOfBands).map { i ->
                    val r = e.getBandLevelRange()
                    """{"lo":${r[0]},"hi":${r[1]},"cur":${e.getBandLevel(i.toShort())}}"""
                }
                """{"min":${e.getBandLevelRange()[0]},"max":${e.getBandLevelRange()[1]},"bands":[${bands.joinToString(",")}]}"""
            } catch (e2: Exception) { "[]" }
        }
        @JavascriptInterface fun setEqBand(i: Int, level: Int) {
            try { AudioService.eq?.setBandLevel(i.toShort(), level.toShort()) } catch (_: Exception) {}
        }
        @JavascriptInterface fun eqEnabled(): Boolean = try { AudioService.eq?.enabled == true } catch (_: Exception) { false }
        @JavascriptInterface fun setEqEnabled(on: Boolean) {
            try { AudioService.eq?.enabled = on } catch (_: Exception) {}
        }

        // ---- Visualizer wave ----
        @JavascriptInterface fun vizOn(on: Boolean) {
            if (on) {
                // v1.4: ensure viz object exists & enabled (permission may have arrived after first READY)
                AudioService.player?.let { AudioService.attachAudioFx(it.audioSessionId) }
                try { AudioService.viz?.enabled = true } catch (_: Exception) {}
                // v1.8: session mungkin belum siap saat toggle pertama — retry sekali setelah 1.2s
                if (AudioService.viz == null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        AudioService.player?.let { AudioService.attachAudioFx(it.audioSessionId) }
                        try { AudioService.viz?.enabled = true } catch (_: Exception) {}
                    }, 1200)
                }
            } else {
                try { AudioService.viz?.enabled = false } catch (_: Exception) {}
            }
            AudioService.onWave = if (on) { wave ->
                val sb = StringBuilder("[")
                // downsample 1024 -> 24 bars
                val step = wave.size / 24
                for (i in 0 until 24) {
                    if (i > 0) sb.append(',')
                    sb.append((wave[i * step].toInt() and 0xFF) - 128)
                }
                sb.append(']')
                pushToJs("window.__rmWave && window.__rmWave($sb)")
            } else null
        }
        @JavascriptInterface fun vizReady(): Boolean = try { AudioService.viz?.enabled == true } catch (_: Exception) { false }
        /** v1.4.1: JS asks to (re)prompt the Mic permission — viz needs it on Android 6+. */
        @JavascriptInterface fun requestMic() {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4242) }
            }
        }

        // ---- Video mode (v1.4) ----
        @JavascriptInterface fun setVideoMode(on: Boolean) {
            videoMode = on
            runOnUiThread {
                val pv = playerView ?: return@runOnUiThread
                if (on) {
                    pv.visibility = android.view.View.VISIBLE
                    pv.bringToFront()
                } else {
                    pv.visibility = android.view.View.GONE
                    uiWebView?.bringToFront()
                }
            }
            // v1.7: engine-video fallback aktif? setVideoMode(false) = user matikan video → sembunyikan iframe
            if (!on) {
                engineVideoActive = false
                AudioService.showEngineVideo(false)
                runOnUiThread { pushToJs("window.__rmEngineVideoMode && window.__rmEngineVideoMode(false)") }
            }
        }

        /** Play muxed MP4 (audio+video) — JS calls this when videoMode is on. */
        @JavascriptInterface fun playVideo(videoId: String, title: String, artist: String, startSeconds: Double) {
            scope.launch {
                try {
                    AudioService.notifTitle = title; AudioService.notifArtist = artist
                    AudioService.playGen++
                    val targetGen = AudioService.playGen
                    val pair = StreamResolver.resolveVideo(videoId, 0)
                    val p = AudioService.player ?: return@launch
                    if (targetGen != AudioService.playGen) return@launch
                    val vItem = androidx.media3.common.MediaItem.fromUri(Uri.parse(pair.video))
                    if (pair.audio != null) {
                        // v1.6: adaptive DASH — video-only + audio-only digabung (muxed sering gak ada)
                        val aItem = androidx.media3.common.MediaItem.fromUri(Uri.parse(pair.audio))
                        val vSrc = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                            androidx.media3.datasource.DefaultDataSource.Factory(this@MainActivity)
                        ).createMediaSource(vItem)
                        val aSrc = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                            androidx.media3.datasource.DefaultDataSource.Factory(this@MainActivity)
                        ).createMediaSource(aItem)
                        val merged = androidx.media3.exoplayer.source.MergingMediaSource(vSrc, aSrc)
                        p.setMediaSource(merged, (startSeconds * 1000).toLong())
                    } else {
                        p.setMediaItem(
                            androidx.media3.common.MediaItem.Builder()
                                .setUri(Uri.parse(pair.video))
                                .setMediaMetadata(
                                    androidx.media3.common.MediaMetadata.Builder()
                                        .setTitle(title).setArtist(artist).build()
                                )
                                .build(),
                            (startSeconds * 1000).toLong()
                        )
                    }
                    p.prepare()
                    p.play()
                    AudioService.mediaGen = targetGen
                    // v1.4.1: show surface only AFTER resolve succeeded — no black hole on failure
                    setVideoModeUi(true)
                } catch (e: Exception) {
                    // v1.7: NewPipe video resolve kena PO-token block → jangan drop ke audio.
                    // Pakai engine YT IFrame fullscreen (player YouTube resmi, resolusi selalu ada).
                    setVideoModeUi(false)
                    try {
                        engineVideoActive = true
                        AudioService.playGen++
                        AudioService.mediaGen = AudioService.playGen
                        AudioService.player?.stop()
                        AudioService.showEngineVideo(true)
                        AudioService.pushToAudioJs("window.player && mkPlayer ? mkPlayer(${jsQuote(videoId)}, $startSeconds) : null")
                        pushToJs("window.__rmEngineVideoMode && window.__rmEngineVideoMode(true)")
                    } catch (e2: Exception) {
                        engineVideoActive = false
                        // overlay tidak tersedia → benar-benar fallback audio
                        pushToJs("window.__rmVideoToggle && window.__rmVideoToggle(false)")
                        play(videoId, title, artist, startSeconds)
                        AudioService.onError?.invoke("Video gak tersedia — lanjut audio")
                    }
                }
            }
        }

        /** Download muxed MP4 at chosen resolution (0 = best) straight to Movies/Rythmix. */
        @JavascriptInterface fun downloadVideo(videoId: String, title: String, resolution: Int) {
            scope.launch {
                try {
                    val url = StreamResolver.resolveVideo(videoId, resolution, muxedOnly = true).video
                    val safe = title.replace(Regex("[^\\w \\-]"), "").trim().ifBlank { videoId }
                    val req = android.app.DownloadManager.Request(Uri.parse(url))
                        .setTitle("$safe (${resolution}p)")
                        .setDestinationInExternalPublicDir(
                            android.os.Environment.DIRECTORY_MOVIES, "Rythmix/$safe-${resolution}p.mp4")
                        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    getSystemService(Context.DOWNLOAD_SERVICE).let {
                        (it as android.app.DownloadManager).enqueue(req)
                    }
                    pushToJs("toast('⬇️ Download video $resolution" + "p dimulai')")
                } catch (e: Exception) {
                    pushToJs("toast('❌ Video download gagal: ${e.message?.replace("'", "")?.take(60) ?: "error"}')")
                }
            }
        }

        // ---- Local file playback (MP4/MP3/M4A) ----
        @JavascriptInterface fun playFile(path: String, title: String, artist: String) {
            scope.launch {
                try {
                    AudioService.playGen++
                    val targetGen = AudioService.playGen
                    val p = AudioService.player ?: return@launch
                    if (targetGen != AudioService.playGen) return@launch
                    val uri = if (path.startsWith("content://") || path.startsWith("file://")) Uri.parse(path)
                              else Uri.fromFile(java.io.File(path))
                    p.setMediaItem(
                        androidx.media3.common.MediaItem.Builder()
                            .setUri(uri)
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(artist)
                                    .build()
                            )
                            .build(),
                        0L
                    )
                    p.prepare()
                    p.play()
                    AudioService.mediaGen = targetGen
                } catch (e: Exception) {
                    AudioService.onError?.invoke(e.message ?: "play file failed")
                }
            }
        }

        @JavascriptInterface fun setRate(r: Double) {
            scope.launch { AudioService.player?.playbackParameters = androidx.media3.common.PlaybackParameters(r.toFloat(), 1f) }
        }

        @JavascriptInterface fun stop() { AudioService.player?.stop() }
        @JavascriptInterface fun isPlaying(): Boolean = AudioService.player?.isPlaying == true
        @JavascriptInterface fun getCurrentTime(): Double = (AudioService.player?.currentPosition ?: 0L) / 1000.0
        @JavascriptInterface fun getDuration(): Double = (AudioService.player?.duration ?: 0L) / 1000.0

        @JavascriptInterface fun prewarm(videoId: String) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { StreamResolver.prewarm(videoId) } catch (_: Exception) {}
            }
        }
    }

    private fun jsQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
