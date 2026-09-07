package com.haviq.richmusic

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession

class AudioService : Service() {

    companion object {
    const val ACTION_PLAY_PAUSE = "com.haviq.richmusic.PLAY_PAUSE"
    const val ACTION_NEXT = "com.haviq.richmusic.NEXT"
    const val ACTION_PREV = "com.haviq.richmusic.PREV"
    const val ACTION_STOP = "com.haviq.richmusic.STOP"
    const val ACTION_UI = "com.haviq.richmusic.UI_CMD" // broadcast → UI webview (loop/shuffle)
    const val EXTRA_CMD = "cmd"
    const val CHANNEL_ID = "rich_music_playback"
    const val NOTIF_ID = 1

        var player: ExoPlayer? = null
            private set
        var session: MediaSession? = null
            private set

        // equalizer + visualizer (android.media.audiofx, attached to ExoPlayer output session id)
        var eq: android.media.audiofx.Equalizer? = null
            private set
        var viz: android.media.audiofx.Visualizer? = null
            private set
        var onWave: ((ByteArray) -> Unit)? = null
        // v1.9: judul notifikasi dipaksa dari UI (metadata ExoPlayer kosong saat engine mode)
        @Volatile var notifTitle: String? = null
        @Volatile var notifArtist: String? = null
        // v2.1: mirror flag engine dari JSBridge — toggle notif tahu audio jalan di mana
        // (JSBridge.engineVideoActive inner class MainActivity; ditulis via syncEngineFlag).
        @Volatile var engineActive: Boolean = false
        // v2.1: asumsi state play engine — toggle notif butuh tahu mau play atau pause
        // (engine events opsional; default true = audio baru jalan = lagi play).
        @Volatile var enginePlaying: Boolean = true
        @JvmStatic fun syncEngineFlag(active: Boolean) { engineActive = active }
        var fxService: AudioService? = null

        var onTick: ((state: Int, posSec: Long, durSec: Long) -> Unit)? = null
        var onEnded: (() -> Unit)? = null
        var onError: ((String) -> Unit)? = null
        var onUiCommand: ((String) -> Unit)? = null

        @Volatile var playGen = 0
        @Volatile var mediaGen = 0
        val endedFired = java.util.concurrent.atomic.AtomicBoolean(false)

        fun pushTick(state: Int, posSec: Long, durSec: Long) {
            if (playGen != mediaGen) return
            onTick?.invoke(state, posSec, durSec)
        }

        // attach Equalizer + Visualizer to the player's audio session
        // v1.4: viz retried on every STATE_READY until it exists (permission may arrive late)
        // v1.7: track session id — if sink re-inits with a NEW id, release old eq/viz first
        @Volatile private var fxSessionId: Int = 0

        fun attachAudioFx(sessionId: Int) {
            val svc = fxService ?: return
            var sid = sessionId
            if (sid <= 0) sid = player?.audioSessionId ?: 0
            if (sid <= 0) return
            // v2.5: bandingkan dgn sid yg sudah di-resolve (bukan param mentah) —
            // play() manggil dgn audioSessionId yg kadang masih 0 (belum READY).
            if (fxSessionId != 0 && fxSessionId != sid) {
                // stale fx from a dead audio session — drop them
                try { eq?.release() } catch (_: Exception) {}
                try { viz?.release() } catch (_: Exception) {}
                eq = null; viz = null
            }
            fxSessionId = sid
            val recGranted = android.os.Build.VERSION.SDK_INT < 23 ||
                androidx.core.content.ContextCompat.checkSelfPermission(svc, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            try {
                if (eq == null) eq = android.media.audiofx.Equalizer(0, sid)
                // v1.4.1: Equalizer created DISABLED — band levels do nothing until enabled
                eq?.let { if (!it.enabled) it.enabled = true }
            } catch (e: Exception) { android.util.Log.w("RM_FX", "EQ attach failed", e) }
            try {
                if (viz == null && recGranted) {
                    viz = android.media.audiofx.Visualizer(sid)
                    viz?.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]
                    viz?.setDataCaptureListener(
                        object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, wave: ByteArray?, sampling: Int) {
                                if (wave != null) onWave?.invoke(wave)
                            }
                            override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, sampling: Int) {}
                        },
                        android.media.audiofx.Visualizer.getMaxCaptureRate(),
                        true,
                        false
                    )
                    viz?.enabled = true // start capture immediately; vizOn() toggles off
                }
            } catch (e: Exception) { viz = null; android.util.Log.w("RM_FX", "Viz attach failed", e) }
        }

        fun youtubeState(p: ExoPlayer?): Int {
            if (p == null) return -1
            if (p.playbackState == Player.STATE_ENDED) return 0
            if (p.playbackState == Player.STATE_BUFFERING) return 3
            if (p.playbackState != Player.STATE_READY) return -1
            return if (p.isPlaying) 1 else 2
        }

        // Audio engine WebView — loaded ONCE, attached ONCE, never re-parented
        var audioWebView: WebView? = null
            private set

        fun pushToAudioJs(script: String) {
            audioWebView?.post { audioWebView?.evaluateJavascript(script, null) }
        }

        // v1.7: companion delegates — instance fungsi diakses dari MainActivity tanpa binder
        @JvmStatic fun showEngineVideo(on: Boolean) { fxService?.engineShowVideo(on) }
        // v2.5: coba attach overlay lagi — true = nempel
        @JvmStatic fun retryOverlayAttach(): Boolean = fxService?.retryOverlay() == true
        @JvmStatic fun engineSeek(seconds: Double) { fxService?.engineDoSeek(seconds) }
        @JvmStatic fun enginePlay() { fxService?.engineDoPlay() }
        @JvmStatic fun enginePause() { fxService?.engineDoPause() }
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var windowManager: WindowManager? = null
    private var overlayAttached = false

    override fun onBind(intent: Intent?): IBinder = binder

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        fxService = this
        createChannel()

        // --- ExoPlayer (native playback via resolved stream URL) ---
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(60000)
            .setReadTimeoutMs(60000)
            .setAllowCrossProtocolRedirects(true)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .build()
        player = p

        // attach equalizer + visualizer once audio session is up
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && (eq == null || viz == null)) attachAudioFx(p.audioSessionId)
            }
            // v1.8: onWave tetap null saat attach sebelum vizOn(true) → re-attach tanpa duplikasi
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && viz != null && viz?.enabled == false) {
                    try { viz?.enabled = true } catch (_: Exception) {}
                }
            }
            // v1.7: sink re-init (focus loss, device change) → session id BARU; EQ/viz lama
            // menempel ke session mati = no output. Re-attach ke id terbaru.
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachAudioFx(audioSessionId)
            }
        })

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (endedFired.compareAndSet(false, true)) {
                        if (playGen == mediaGen) onEnded?.invoke()
                    }
                    updateNotification()
                } else {
                    endedFired.set(false)
                    pushTick(youtubeState(p), p.currentPosition / 1000, p.duration / 1000)
                    updateNotification()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pushTick(youtubeState(p), p.currentPosition / 1000, p.duration / 1000)
                updateNotification()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // v3.1: URL cached bisa dead (403/expired signature) — buang biar retry
                // path resolve fresh. Tanpa ini lagu gagal terus tiap play sampai cache expired.
                MainActivity.onPlaybackError()
                onError?.invoke("ExoPlayer: ${error.errorCodeName} - ${error.message}")
            }
        })

        session = MediaSession.Builder(this, p).build()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "richmusic:player")
            .apply { setReferenceCounted(false) }

        // --- Audio engine WebView (Service-owned, attached ONCE) ---
        createEngineWebView()

        startForeground(NOTIF_ID, buildNotification("Rythmix Music", "Player ready"))
        acquireWakeLock()
    }

    /**
     * v4.0 audio engine: tiny WebView loaded ONCE with local HTML hosting the YT IFrame.
     * Attached ONCE to an offscreen overlay — never re-parented → no surface breakage.
     * YT.Player events are forwarded to UI via window.__rmEngineEvent.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createEngineWebView() {
        val wv = WebView(applicationContext)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // UA tanpa "; wv" — YouTube IFrame API butuh browser-grade UA
            userAgentString = userAgentString.replace(
                Regex("[;,]\\s*wv\\b", RegexOption.IGNORE_CASE), ""
            )
        }
        wv.setBackgroundColor(0xFF0A0A0A.toInt())
        wv.addJavascriptInterface(EngineBridge(), "__rmEngine")
        wv.webViewClient = WebViewClient() // plain — engine only
        audioWebView = wv

        attachOverlay(wv)

        val html = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;padding:0;background:#0A0A0A;overflow:hidden}
            #p{position:absolute;left:-9999px;top:-9999px;width:200px;height:200px}</style>
            </head><body>
            <div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            var player = null;
            var ready = false;
            var currentId = null;
            var gen = 0;
            // v2.2: tick posisi 500ms — tanpa ini lirik macet saat engine mode
            // (ExoPlayer listener mati karena player di-stop; state iframe cuma
            // fire saat ganti state, bukan tiap detik).
            var tickTimer = null;
            function startTick(myGen) {
              stopTick();
              tickTimer = setInterval(function() {
                if (myGen !== gen || !player) { stopTick(); return; }
                try {
                  var st = player.getPlayerState ? player.getPlayerState() : -1;
                  if (st === YT.PlayerState.PLAYING)
                    __rmEngine.state(st, Math.round(player.getCurrentTime()), Math.round(player.getDuration()));
                } catch(e){}
              }, 500);
            }
            function stopTick() { if (tickTimer) { clearInterval(tickTimer); tickTimer = null; } }
            window.onYouTubeIframeAPIReady = function() { ready = true; };
            function mkPlayer(videoId, startSeconds) {
              gen++;
              var myGen = gen;
              currentId = videoId;
              if (player) { try { player.destroy(); } catch(e){} player = null; }
              player = new YT.Player('p', {
                videoId: videoId,
                playerVars: { autoplay:1, controls:0, disablekb:1, playsinline:1, start:startSeconds||0, rel:0 },
                events: {
                  onReady: function(e){ if(myGen!==gen) return; __rmEngine.log('ready'); e.target.playVideo(); },
                  onStateChange: function(e){
                    if(myGen!==gen) return;
                    if (e.data === YT.PlayerState.PLAYING) startTick(myGen);
                    else if (e.data === YT.PlayerState.PAUSED || e.data === YT.PlayerState.ENDED) stopTick();
                    __rmEngine.state(e.data, Math.round(e.target.getCurrentTime()), Math.round(e.target.getDuration()));
                  },
                  onError: function(e){ if(myGen!==gen) return; __rmEngine.err(String(e.data)); }
                }
              });
            }
            </script>
            </body></html>
        """.trimIndent().replace("__rmEngine", "__rmEngine") // noop keep
        wv.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
    }

    inner class EngineBridge {
        @JavascriptInterface
        fun state(s: Int, t: Long, d: Long) {
            if (playGen != mediaGen) return
            pushTick(s, t, d)
        }
        @JavascriptInterface
        fun err(code: String) { onError?.invoke("iframe: $code") }
        @JavascriptInterface
        fun log(msg: String) { android.util.Log.d("rm-engine", msg) }
    }

    /**
     * v1.7 video fallback: NewPipe video resolve kena PO-token block di device.
     * Saat videoMode aktif dan resolve NewPipe gagal → tampilkan engine YT IFrame
     * (player YouTube resmi, selalu ada resolusi) fullscreen via overlay window.
     * Overlay 200x200 di-resize; kembali ke -9999 saat audio-only.
     */
    fun engineShowVideo(on: Boolean) {
        // v1.9: bisa dipanggil dari JS binder thread — windowManager wajib main thread (crash fix)
        android.os.Handler(android.os.Looper.getMainLooper()).post { engineShowVideoInner(on) }
    }
    private fun engineShowVideoInner(on: Boolean) {
        if (!overlayAttached) {
            // v2.5: izin mungkin baru diberikan — coba attach ulang sekali
            audioWebView?.let { attachOverlay(it) }
        }
        if (!overlayAttached) {
            // v2.5: gagal diam-diam = "video ga bisa" — kasih tahu user + balikin flag JS
            if (on) {
                onUiCommand?.invoke("toast('❌ Mode video butuh izin overlay — aktifkan di Settings → Apps → Rythmix → Tampil di atas aplikasi')")
                onUiCommand?.invoke("window.__rmVideoToggle && window.__rmVideoToggle(false)")
            }
            return
        }
        val wv = audioWebView ?: return
        try {
            val wm = windowManager ?: return
            val params = wv.layoutParams as? android.view.WindowManager.LayoutParams ?: return
            if (on) {
                // v1.8: windowed 40% tinggi di ATAS — app + lirik tetap terlihat & touchable di bawah
                val dm = resources.displayMetrics
                params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                params.height = (dm.heightPixels * 0.40f).toInt()
                params.x = 0
                params.y = 0
                wm.updateViewLayout(wv, params)
                pushToAudioJs("(function(){var p=document.getElementById('p');if(p)p.style.cssText='position:fixed;left:0;top:0;width:100vw;height:40vh';var f=p&&p.querySelector('iframe');if(f)f.style.cssText='position:fixed;left:0;top:0;width:100vw;height:40vh';})()")
            } else {
                params.width = 200
                params.height = 200
                params.x = -9999
                params.y = -9999
                params.flags = params.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wm.updateViewLayout(wv, params)
                pushToAudioJs("(function(){var p=document.getElementById('p');if(p)p.style.cssText='position:absolute;left:-9999px;top:-9999px;width:200px;height:200px';var f=p&&p.querySelector('iframe');if(f)f.style.cssText='';})()")
            }
        } catch (_: Exception) {}
    }

    /** v1.7: engine video pakai iframe controls=0 — seek/pause via JS commands dari UI. */
    fun engineDoSeek(seconds: Double) {
        pushToAudioJs("window.player && player.seekTo(${seconds}, true)")
    }
    fun engineDoPlay() { enginePlaying = true; pushToAudioJs("window.player && player.playVideo()") }
    fun engineDoPause() { enginePlaying = false; pushToAudioJs("window.player && player.pauseVideo()") }

    private fun attachOverlay(wv: WebView) {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm
            val type = if (Build.VERSION.SDK_INT >= 26)
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            val params = android.view.WindowManager.LayoutParams(
                200, 200, type,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.x = -9999
            params.y = -9999
            wm.addView(wv, params)
            overlayAttached = true
        } catch (_: Exception) {
            // overlay denied — engine tetap hidup di memory, mungkin throttled
        }
    }

    /** v2.5: coba attach overlay lagi (user mungkin baru kasih izin). True = nempel. */
    fun retryOverlay(): Boolean {
        if (overlayAttached) return true
        val wv = audioWebView ?: return false
        attachOverlay(wv)
        return overlayAttached
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> pushUi("next")
            ACTION_PREV -> pushUi("prev")
            ACTION_UI -> pushUi(intent?.getStringExtra(EXTRA_CMD) ?: "")
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    /** notification control → UI webview (app.js owns queue/loop/shuffle logic).
     *  AudioService has no UI handle → MainActivity registers itself; service broadcasts via callback. */
    private fun pushUi(cmd: String) {
        if (cmd.isEmpty()) return
        val js = "window.__rmNotifCmd && window.__rmNotifCmd('${cmd.replace("'", "\\'")}')"
        onUiCommand?.invoke(js)
    }

    override fun onDestroy() {
        try { eq?.release(); eq = null } catch (_: Exception) {}
        try { viz?.release(); viz = null } catch (_: Exception) {}
        fxService = null
        session?.release()
        session = null
        player?.release()
        player = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        try {
            if (overlayAttached) {
                audioWebView?.let { windowManager?.removeView(it) }
                overlayAttached = false
            }
        } catch (_: Exception) {}
        audioWebView?.destroy()
        audioWebView = null
        super.onDestroy()
    }

    private fun togglePlayPause() {
        // v2.1: audio bisa jalan via engine (mode video / fallback) — ExoPlayer dipause percuma.
        if (engineActive) {
            enginePlaying = !enginePlaying
            if (enginePlaying) engineDoPlay() else engineDoPause()
            return
        }
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun acquireWakeLock() {
        wakeLock?.acquire(6 * 60 * 60 * 1000L /* 6h */)
    }

    private fun updateNotification() {
        val p = player ?: return
        val mi = p.currentMediaItem
        val title = notifTitle?.takeIf { it.isNotBlank() }
            ?: mi?.mediaMetadata?.title?.toString() ?: "Rythmix Music"
        val text = notifArtist?.takeIf { it.isNotBlank() }
            ?: mi?.mediaMetadata?.artist?.toString() ?: ""
        val notif = buildNotification(title, text)
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, notif)
    }

    private fun buildNotification(title: String, text: String): Notification {
        fun pi(action: String, code: Int): PendingIntent =
            PendingIntent.getService(this, code, Intent(this, AudioService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val contentIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // v2.1: state play ikut engine saat engine aktif (fallback video/audio via iframe).
        val isPlaying = if (engineActive) enginePlaying else player?.isPlaying == true

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            // v2.1: ikon tunjukkan AKSI berikut — play saat paused, pause saat playing.
            .setSmallIcon(if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // MediaStyle compact row = prev | play/pause | next
            .addAction(R.drawable.ic_notif_prev, "Previous", pi(ACTION_PREV, 4))
            .addAction(
                if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                if (isPlaying) "Pause" else "Play",
                pi(ACTION_PLAY_PAUSE, 0)
            )
            .addAction(R.drawable.ic_notif_next, "Next", pi(ACTION_NEXT, 3))
            // v1.9: shuffle & loop — expanded view only; commands relayed ke UI webview
            .addAction(R.drawable.ic_notif_shuffle, "Shuffle", pi(ACTION_UI, 6).let {
                PendingIntent.getService(this, 6,
                    Intent(this, AudioService::class.java)
                        .setAction(ACTION_UI).putExtra(EXTRA_CMD, "shuffle"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            })
            .addAction(R.drawable.ic_notif_repeat, "Loop", pi(ACTION_UI, 7).let {
                PendingIntent.getService(this, 7,
                    Intent(this, AudioService::class.java)
                        .setAction(ACTION_UI).putExtra(EXTRA_CMD, "loop"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            })
            .addAction(R.drawable.ic_notif_close, "Stop", pi(ACTION_STOP, 1))

        // v2.0: MediaStyle TANPA session token — Android 11+ menimpa judul & tombol dari
        // MediaSession metadata (judul "acak", tombol custom ilang). Tanpa token, judul/tombol
        // dari builder ini yang dipakai. (Artwork notif dikorbankan — konsistensi > gambar.)
        builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2))
        return builder.build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Rythmix Music Playback", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
