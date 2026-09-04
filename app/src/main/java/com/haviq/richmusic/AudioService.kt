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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession

class AudioService : Service() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.haviq.richmusic.PLAY_PAUSE"
        const val ACTION_STOP = "com.haviq.richmusic.STOP"
        const val CHANNEL_ID = "rich_music_playback"
        const val NOTIF_ID = 1

        var player: ExoPlayer? = null
            private set
        var session: MediaSession? = null
            private set

        var onTick: ((state: Int, posSec: Long, durSec: Long) -> Unit)? = null
        var onEnded: (() -> Unit)? = null
        var onError: ((String) -> Unit)? = null

        @Volatile var playGen = 0
        @Volatile var mediaGen = 0
        val endedFired = java.util.concurrent.atomic.AtomicBoolean(false)

        fun pushTick(state: Int, posSec: Long, durSec: Long) {
            if (playGen != mediaGen) return
            onTick?.invoke(state, posSec, durSec)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
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
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun acquireWakeLock() {
        wakeLock?.acquire(6 * 60 * 60 * 1000L /* 6h */)
    }

    private fun updateNotification() {
        val p = player ?: return
        val mi = p.currentMediaItem
        val title = mi?.mediaMetadata?.title?.toString() ?: "Rythmix Music"
        val text = mi?.mediaMetadata?.artist?.toString() ?: ""
        val notif = buildNotification(title, text)
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, notif)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val playPauseIntent = PendingIntent.getService(
            this, 0, Intent(this, AudioService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, AudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isPlaying = player?.isPlaying == true

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Rythmix Music Playback", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
