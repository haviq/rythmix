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
import java.net.HttpURLConnection
import java.net.URL
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
    // v4.5: resolusi video pilihan (0 = auto terbaik); di-persist biar lintas sesi
    private var videoResolution = 0
    private var adView: com.google.android.gms.ads.AdView? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            audioService = (binder as AudioService.LocalBinder).getService()
            serviceBound = true

            // Engine ticks → UI WebView
            // v3.7: tick di-tag lastVideoId — JS membuang tick milik lagu lama
            // (stale ticker 250ms setelah ganti lagu = progress/lirik "ikut lagu pertama").
            // Tag videoId, bukan counter epoch: sinkron alami, tidak mungkin drift.
            AudioService.onTick = { state, posSec, durSec ->
                pushToJs("window.__rmNativeUpdate && window.__rmNativeUpdate($state, $posSec, $durSec, '${tickVideoId ?: ""}')")
            }
            AudioService.onEnded = {
                pushToJs("window.__rmNativeUpdate && window.__rmNativeUpdate(0, 0, ${AudioService.player?.duration?.div(1000) ?: 0}, '${tickVideoId ?: ""}')")
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

        // v4.6: AdMob lazy — MobileAds.init blocking main thread saat cold start;
        // tunda 2.5s, iklan tetap muncul beberapa detik kemudian.
        adView = findViewById(R.id.ad_banner)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { com.google.android.gms.ads.MobileAds.initialize(this) {} } catch (_: Exception) {}
            try { adView?.loadAd(com.google.android.gms.ads.AdRequest.Builder().build()) } catch (_: Exception) {}
        }, 2500)

        ensureNotificationPermission()
        StreamResolver.init(applicationContext)
        // v4.5: restore resolusi video pilihan
        try { videoResolution = getSharedPreferences("rm_prefs", 0).getInt("video_res", 0) } catch (_: Exception) {}

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

    /** v2.7: posisi video = kotak thumbnail di Now Playing (bukan fullscreen).
        JS kirim rect dari elemen #np-video tiap layout berubah. */
    private fun applyVideoRect(x: Int, y: Int, w: Int, h: Int) {
        val pv = playerView ?: return
        lastVideoRect[0] = x; lastVideoRect[1] = y; lastVideoRect[2] = w; lastVideoRect[3] = h
        if (w <= 0 || h <= 0) { // NP minimize / tab lain → video ikut hilang, audio jalan terus
            pv.visibility = android.view.View.GONE
            return
        }
        if (videoMode) pv.visibility = android.view.View.VISIBLE
        pv.translationX = x.toFloat()
        pv.translationY = y.toFloat()
        pv.layoutParams = (pv.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(0, 0)).let { lp ->
            lp.width = w; lp.height = h; lp
        }
        pv.requestLayout()
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
            // v2.7: SATU video tampil — playerView vs overlay engine jangan berebut.
            // ExoPlayer on → overlay engine harus sembunyi total (dulu ketumpuk hitam).
            if (on) AudioService.showEngineVideo(false)
            if (on) {
                playerView?.visibility = android.view.View.VISIBLE
                playerView?.bringToFront()
                // rect terakhir dari JS (kotak thumbnail); 0 = belum dikirim → fullscreen sementara
                if (lastVideoRect[2] > 0) applyVideoRect(lastVideoRect[0], lastVideoRect[1], lastVideoRect[2], lastVideoRect[3])
            } else {
                playerView?.visibility = android.view.View.GONE
                uiWebView?.bringToFront()
            }
        }
    }
    private val lastVideoRect = intArrayOf(0, 0, 0, 0)

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

    // v3.7: tag videoId untuk tick — diset sync di play/prepare/playVideo (sama saat
    // JSBridge.lastVideoId sync-diset). Tick & onEnded memakainya agar JS bisa
    // membuang tick milik lagu lama. Tag videoId, bukan counter: tidak mungkin drift.
    @Volatile var tickVideoId: String? = null

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
    private val overlayPermLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // v2.5: user kembali dari Settings overlay — coba attach ulang biar mode video bisa jalan
        AudioService.retryOverlayAttach()
    }

    // JS bridge — exposed as window.RichMusicBridge on the UI WebView.
    // Playback happens in AudioService (ExoPlayer resolved via StreamResolver);
    // engine WebView only used when ExoPlayer path unavailable.

    // v3.1: ExoPlayer error → evict dead URL dari StreamResolver cache supaya
    // retry (startCurrent 2x + fallback) resolve fresh, bukan nyangkut URL busuk.
    companion object {
        @Volatile private var lastErrorVideoId: String? = null
        fun onPlaybackError() {
            val vid = lastErrorVideoId ?: return
            StreamResolver.evict(vid)
        }
        fun trackErrorCandidate(videoId: String?) { lastErrorVideoId = videoId }
    }

    inner class JSBridge {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        @Volatile private var lastVideoId: String? = null
        // v3.3: id lagu yang BENAR2 dimuat di player (bukan yang diminta) —
        // lastVideoId sekarang sync-diset di play() utk resume(), jadi gak boleh
        // dipakai guard same-song lagi (v3.2 regesi: guard selalu true → switch diabaikan).
        @Volatile private var playingVideoId: String? = null
        @Volatile private var lastTitle: String? = null
        @Volatile private var lastArtist: String? = null
        @Volatile private var playRequested = false
        // v2.9: true = play()/prepare() sedang resolve lagu (2-7s). resume()/pause()
        // mid-resolve tidak boleh nyentuh player — dulu pause() nyetel state lagu lama,
        // resume() pas STATE_ENDED replay item lama → "ganti musik tetap lagu lama".
        @Volatile var resolving = false
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

            // v4.7: SUDUT BARU — ExoPlayer langsung disuruh mainkan URL /api/stream.m4a
            // (server yang resolve, 302 ke file final) ATAU URL cache lokal kalau fresh.
            // Tidak ada resolve di path play → TIDAK ADA race/antrean → lagu salah
            // ketumpuk jadi mustahil (URL mengandung videoId yang diminta).

            playRequested = false
            resolving = true
            // v4.0: reset playingVideoId seketika — jangan nyangkut ke lagu lama
            playingVideoId = null
            lastVideoId = videoId
            tickVideoId = videoId
            lastTitle = title; lastArtist = artist
            AudioService.notifTitle = title; AudioService.notifArtist = artist
            AudioService.playGen++
            AudioService.mediaGen = AudioService.playGen

            try {
                AudioService.player?.stop()
                AudioService.player?.clearMediaItems()
            } catch (_: Exception) {}


            val targetGen = AudioService.playGen
            val localUrl = StreamResolver.cachedUrl(videoId)
            val mediaUrl = localUrl ?: "https://rythmix-music.vercel.app/api/stream.m4a?videoId=$videoId"
            val p = AudioService.player ?: run { resolving = false; return }
            // v4.7b: resolve via endpoint polling di sini — 202=tunggu, 302=URL final.
            // Dilakukan DI LUUIR ExoPlayer supaya media item = file final (bukan proxy).
            val effectiveUrl = if (localUrl != null) localUrl else {
                var final: String? = null
                var attempt = 0
                while (final == null && attempt < 14) {
                    try {
                        val conn = URL("https://rythmix-music.vercel.app/api/stream.m4a?videoId=$videoId&wait=1").openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 5_000
                        conn.readTimeout = 55_000
                        val code = conn.responseCode
                        if (code in 301..399) {
                            final = conn.getHeaderField("Location")
                            conn.disconnect(); break
                        }
                        conn.disconnect()
                        Thread.sleep(3000)
                    } catch (e: Exception) {
                        try { Thread.sleep(2500) } catch (_: Exception) {}

                    }
                    attempt++
                }
                final ?: "https://rythmix-music.vercel.app/api/stream.m4a?videoId=$videoId&wait=1"
            }
            p.setMediaItem(
                androidx.media3.common.MediaItem.Builder()
                    .setUri(Uri.parse(effectiveUrl))
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(title.ifBlank { videoId })
                            .setArtist(artist)
                            .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg"))
                            .build()
                    )
                    .build(),
                (startSeconds * 1000).toLong()
            )
            p.prepare()
            p.play()
            playingVideoId = videoId
            AudioService.mediaGen = targetGen
            if (targetGen == AudioService.playGen) {
                resolving = false
                engineVideoActive = false
            }
            AudioService.player?.let { AudioService.attachAudioFx(it.audioSessionId) }
            // panaskan cache lokal utk lagu ini juga (next session = URL direct instan)
            StreamResolver.prewarm(videoId)
        }

        fun playUrl(url: String, title: String, artist: String, startSeconds: Double) {
            playRequested = false
            resolving = true // v3.0: sync guard
            
            AudioService.notifTitle = title; AudioService.notifArtist = artist
            AudioService.playGen++
            // SYNCHRONOUSLY stop & clear ON UI THREAD
            AudioService.player?.stop()
            AudioService.player?.clearMediaItems()
            
            scope.launch {
                val targetGen = AudioService.playGen
                try {
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
                    playingVideoId = lastVideoId // v3.3: fallback path — lastVideoId diset sync di play()
                    AudioService.mediaGen = targetGen
                    if (targetGen == AudioService.playGen) resolving = false // v3.4: clear guard juga di path sukses (dulu nyangkut → pause/resume mati)
                    // v2.4: re-attach EQ/viz (playUrl path juga ganti media item)
                    AudioService.player?.let { AudioService.attachAudioFx(it.audioSessionId) }
                    val dur = p.duration / 1000
                    pushToJs("window.__rmNativeUpdate && window.__rmNativeUpdate(1, ${(startSeconds * 1000).toLong() / 1000}, $dur, '${lastVideoId ?: ""}')")
                } catch (e: Exception) {
                    if (targetGen != AudioService.playGen) return@launch // v3.5: abaikan catch
                    resolving = false // v3.0
                    AudioService.onError?.invoke("playUrl failed: ${e.message ?: "unknown"}")
                }
            }
        }

        @JavascriptInterface
        fun prepare(videoId: String, title: String, artist: String, startSeconds: Double) {
            // v2.7: videoMode aktif → cued track juga pakai jalur video (resolve muxed+prepare, no play).
            // (dulu: cue selalu audio → resume nyalain audio padahal mode video)
            if (videoMode) { prepareVideo(videoId, title, artist, startSeconds); return }
            playRequested = false
            resolving = true
            lastVideoId = videoId
            tickVideoId = videoId
            lastTitle = title; lastArtist = artist
            // v4.7: instant cue — media item langsung dipasang (stream proxy / cache lokal).
            // Tidak ada resolve coroutine → resume() instan, tanpa state race.
            AudioService.player?.stop()
            AudioService.player?.clearMediaItems()
            playRequested = false
            val targetGen = ++AudioService.playGen
            AudioService.mediaGen = targetGen
            val localUrl = StreamResolver.cachedUrl(videoId)
            val effectiveUrl = localUrl ?: "https://rythmix-music.vercel.app/api/stream.m4a?videoId=$videoId&wait=1"
            val p = AudioService.player ?: run { resolving = false; return }
            p.setMediaItem(
                androidx.media3.common.MediaItem.Builder()
                    .setUri(Uri.parse(effectiveUrl))
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(title.ifBlank { videoId })
                            .setArtist(artist)
                            .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg"))
                            .build()
                    )
                    .build(),
                (startSeconds * 1000).toLong()
            )
            p.prepare()
            playingVideoId = videoId
            resolving = false
            if (playRequested) { playRequested = false; p.play() }
        }

        @JavascriptInterface fun seekTo(seconds: Double) {
            if (resolving) return // v2.9: seek mid-resolve = target item lama
            if (engineVideoActive) { AudioService.engineSeek(seconds); return }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                AudioService.player?.seekTo((seconds * 1000).toLong())
            }
        }

        @JavascriptInterface fun pause() {
            if (resolving) return // v2.9: pause mid-resolve = state lagu lama bocor ke UI
            if (engineVideoActive) { AudioService.enginePause(); return }
            scope.launch {
                AudioService.player?.pause()
                val p = AudioService.player ?: return@launch
                AudioService.pushTick(AudioService.youtubeState(p), p.currentPosition / 1000, p.duration / 1000)
            }
        }

        /** v2.7: prepare jalur video — resolve video (tanpa play) untuk cued track. */
        fun prepareVideo(videoId: String, title: String, artist: String, startSeconds: Double) {
            scope.launch {
                AudioService.player?.stop()
                AudioService.player?.clearMediaItems()
                playRequested = false
                resolving = true // v2.9
                AudioService.playGen++
                val targetGen = AudioService.playGen
                try {
                    val pair = StreamResolver.resolveVideo(videoId, videoResolution)
                    val p = AudioService.player ?: run { resolving = false; return@launch }
                    if (targetGen != AudioService.playGen) run { resolving = false; return@launch }
                    lastVideoId = videoId; lastTitle = title; lastArtist = artist
                    val vItem = androidx.media3.common.MediaItem.fromUri(Uri.parse(pair.video))
                    if (pair.audio != null) {
                        val aItem = androidx.media3.common.MediaItem.fromUri(Uri.parse(pair.audio))
                        val f = androidx.media3.datasource.DefaultDataSource.Factory(this@MainActivity)
                        val merged = androidx.media3.exoplayer.source.MergingMediaSource(
                            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(f).createMediaSource(vItem),
                            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(f).createMediaSource(aItem)
                        )
                        p.setMediaSource(merged, (startSeconds * 1000).toLong())
                    } else {
                        p.setMediaItem(
                            androidx.media3.common.MediaItem.Builder().setUri(Uri.parse(pair.video))
                                .setMediaMetadata(
                                    androidx.media3.common.MediaMetadata.Builder()
                                        .setTitle(title).setArtist(artist)
                                        .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")).build()
                                ).build(),
                            (startSeconds * 1000).toLong()
                        )
                    }
                    p.prepare()
                    AudioService.mediaGen = targetGen
                    playingVideoId = videoId // v3.3: prepareVideo — cued video dimuat
                    if (targetGen == AudioService.playGen) resolving = false // v2.9
                    if (playRequested) { playRequested = false; p.play() } // v2.9: resume mid-resolve → play
                } catch (e: Exception) {
                    if (targetGen != AudioService.playGen) return@launch // v3.5
                    // video gagal → tetap prepare audio; videoMode tetap ON (lagu berikut coba video lagi)
                    resolving = false
                    playRequested = false
                    play(videoId, title, artist, startSeconds)
                }
            }
        }

        @JavascriptInterface fun resume() {
            if (engineVideoActive) { AudioService.enginePlay(); return }
            if (resolving) { playRequested = true; return } // v2.9: resume mid-resolve = play setelah prepare, jangan replay item lama
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
        @JavascriptInterface fun setSpeed(speed: Double) {
            scope.launch { AudioService.player?.playbackParameters = androidx.media3.common.PlaybackParameters(speed.toFloat(), 1f) }
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
        @JavascriptInterface fun vizReady(): Boolean = try { AudioService.viz != null } catch (_: Exception) { false }
        // v2.4: JS butuh tahu audio jalan di mana — EQ/viz Android mati saat engine mode.
        @JavascriptInterface fun engineMode(): Boolean = try { engineVideoActive } catch (_: Exception) { false }
        /** v1.4.1: JS asks to (re)prompt the Mic permission — viz needs it on Android 6+. */
        @JavascriptInterface fun requestMic() {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4242) }
            }
        }

        // ---- Video mode (v1.4) ----
        // v2.7: JS kirim rect kotak #np-video (CSS px) — video dirender TEPAT di
        // kotak thumbnail Now Playing, bukan fullscreen.
        @JavascriptInterface fun setVideoRect(x: Int, y: Int, w: Int, h: Int) {
            val den = resources.displayMetrics.density
            runOnUiThread { applyVideoRect((x * den).toInt(), (y * den).toInt(), (w * den).toInt(), (h * den).toInt()) }
        }
        @JavascriptInterface fun setVideoMode(on: Boolean) {
            videoMode = on
            runOnUiThread {
                val pv = playerView ?: return@runOnUiThread
                if (on) {
                    // v2.6: engine overlay harus mati total (dulu ketumpuk → video ga kelihatan)
                    jsBridge.engineVideoActive = false
                    AudioService.showEngineVideo(false)
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

        /** v2.5: JS minta coba attach overlay lagi (dipanggil sebelum playVideo). */
        @JavascriptInterface fun retryOverlay(): Boolean = AudioService.retryOverlayAttach()

        /** Play muxed MP4 (audio+video) — JS calls this when videoMode is on. */
        @JavascriptInterface fun playVideo(videoId: String, title: String, artist: String, startSeconds: Double) {
            // v3.2: identitas sinkron juga di jalur video (sama dgn play/prepare)
            lastVideoId = videoId
            tickVideoId = videoId
            lastTitle = title; lastArtist = artist
            
            AudioService.notifTitle = title; AudioService.notifArtist = artist
            AudioService.playGen++
            AudioService.mediaGen = AudioService.playGen
            // SYNCHRONOUSLY stop & clear ON UI THREAD
            AudioService.player?.stop()
            AudioService.player?.clearMediaItems()

            scope.launch {
                playRequested = false
                resolving = true
                val targetGen = AudioService.playGen
                try {
                    val pair = StreamResolver.resolveVideo(videoId, videoResolution)
                    val p = AudioService.player ?: run { resolving = false; return@launch }
                    if (targetGen != AudioService.playGen) run { resolving = false; return@launch }
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
                    playingVideoId = videoId // v3.3: video path — item benar2 dimuat
                    AudioService.mediaGen = targetGen
                    resolving = false
                    // v1.4.1: show surface only AFTER resolve succeeded — no black hole on failure
                    setVideoModeUi(true)
                } catch (e: Exception) {
                    resolving = false
                    // v2.7: resolve video gagal (PO-token block dll) → lanjut AUDIO,
                    // tapi videoMode TETAP ON — lagu berikutnya otomatis coba video lagi.
                    // (dulu: lempar ke engine overlay fullscreen → ngeblok layar)
                    try {
                        AudioService.notifTitle = title; AudioService.notifArtist = artist
                        AudioService.playGen++
                        AudioService.mediaGen = AudioService.playGen
                        engineVideoActive = false
                        AudioService.player?.stop(); AudioService.player?.clearMediaItems()
                        play(videoId, title, artist, startSeconds)
                        AudioService.onError?.invoke("Video gak tersedia — lanjut audio, mode video tetap on")
                    } catch (e2: Exception) {
                        AudioService.onError?.invoke("Video gak tersedia")
                    }
                }
            }
        }

        /** Download muxed MP4 at chosen resolution (0 = best) straight to Movies/Rythmix. */

        // v4.5: resolusi video eksplisit (0=auto terbaik, 1080/720/480/360). Tersimpan,
        // langsung reload video yang sedang diputar tanpa kehilangan posisi.
        @JavascriptInterface fun setVideoResolution(height: Int) {
            videoResolution = height
            try { getSharedPreferences("rm_prefs", 0).edit().putInt("video_res", height).apply() } catch (_: Exception) {}
            val vid = playingVideoId
            val title = lastTitle; val artist = lastArtist
            val pos = AudioService.player?.currentPosition?.div(1000.0) ?: 0.0
            if (videoMode && !vid.isNullOrBlank()) {
                scope.launch {
                    try {
                        resolving = true
                        val pair = StreamResolver.resolveVideo(videoId = vid, resolution = height)
                        // evict entri lama dulu — resolveVideo cache by key, height beda = key beda, aman
                        val p = AudioService.player ?: run { resolving = false; return@launch }
                        val vItem = androidx.media3.common.MediaItem.fromUri(Uri.parse(pair.video))
                        if (pair.audio != null) {
                            val aItem = androidx.media3.common.MediaItem.fromUri(Uri.parse(pair.audio))
                            val vSrc = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                                androidx.media3.datasource.DefaultDataSource.Factory(this@MainActivity)
                            ).createMediaSource(vItem)
                            val aSrc = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                                androidx.media3.datasource.DefaultDataSource.Factory(this@MainActivity)
                            ).createMediaSource(aItem)
                            p.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(vSrc, aSrc), (pos * 1000).toLong())
                        } else {
                            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.parse(pair.video)), (pos * 1000).toLong())
                        }
                        p.prepare(); p.play()
                        resolving = false
                    } catch (e: Exception) {
                        resolving = false
                        AudioService.onError?.invoke("Resolusi ${height}p gak tersedia")
                    }
                }
            }
        }
        @JavascriptInterface fun getVideoResolution(): Int = videoResolution
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

        @JavascriptInterface fun stop() {
            AudioService.stopPlayer()
            playingVideoId = null
            resolving = false
            playRequested = false
        }
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
