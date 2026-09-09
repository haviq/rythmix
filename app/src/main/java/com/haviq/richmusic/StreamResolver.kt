package com.haviq.richmusic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo as NPStreamInfo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class StreamInfo(
    val url: String,
    val title: String,
    val artist: String,
    val duration: Long
)

object StreamResolver {
    // ponytail: fallback loader.to dihapus — JS-side resolveStreamUrl jadi fallback via __rmNativeFallback

    // NewPipe Downloader init (one-time)
    @Volatile private var newpipeReady = false

    // videoId -> (url, expireAtMillis). Expire ABSOLUT — URL loader.to berumur pendek,
    // tidak boleh ikut TTL 5.5h milik URL NewPipe (signed ~6h).
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val CACHE_MS = 5L * 60 * 60 * 1000
    private const val LOADER_TTL_MS = 10L * 60 * 1000
    private const val MAX_ENTRIES = 250

    // persisted cache survives app restarts → tracks played before start instantly (~<1s)
    private var prefs: android.content.SharedPreferences? = null

    // Load persisted cache from disk. Call once from MainActivity.onCreate.
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("stream_cache", Context.MODE_PRIVATE)
        try {
            val raw = prefs?.getString("data", null) ?: return
            val obj = JSONObject(raw)
            val now = System.currentTimeMillis()
            val it = obj.keys()
            while (it.hasNext()) {
                val id = it.next()
                val arr = obj.optJSONArray(id) ?: continue
                val url = arr.optString(0); val exp = arr.optLong(1)
                if (url.isNotBlank() && exp > now && cache.size < MAX_ENTRIES) cache[id] = url to exp
            }
        } catch (_: Exception) {}
    }

    private fun persist() {
        try {
            val obj = JSONObject()
            for ((id, v) in cache) {
                if (v.first.isBlank() || v.second <= System.currentTimeMillis()) continue
                obj.put(id, org.json.JSONArray().put(v.first).put(v.second))
            }
            prefs?.edit()?.putString("data", obj.toString())?.apply()
        } catch (_: Exception) {}
    }

    private fun ensureInit() {
        if (newpipeReady) return
        synchronized(this) {
            if (newpipeReady) return
            NewPipe.init(object : Downloader() {
                override fun execute(request: Request): Response {
                    val conn = URL(request.url()).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 8_000
                    conn.requestMethod = request.httpMethod()
                    request.headers().forEach { (k, vs) -> vs.forEach { conn.setRequestProperty(k, it) } }
                    request.dataToSend()?.let { conn.doOutput = true; conn.outputStream.use { o -> o.write(it) } }
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                    return Response(code, conn.responseMessage, conn.headerFields, body, request.url())
                }
            }, Localization("en", "US"))
            newpipeReady = true
        }
    }

    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        // m4a dulu (ExoPlayer native + kompatibel luas), opus/webm kedua
        val m4a = streams.filter { it.format == org.schabi.newpipe.extractor.MediaFormat.M4A }
        val pool = if (m4a.isNotEmpty()) m4a else streams
        return pool.maxByOrNull { it.averageBitrate }
    }

    // muxed progressive (itag 18/22/37) = format MPEG_4 di videoStreams (M4A = audio-only mp4, bukan video!)
    private fun resOf(s: org.schabi.newpipe.extractor.stream.VideoStream): Int =
        s.resolution.filter(Char::isDigit).toIntOrNull() ?: 0

    private fun viaNewPipeVideo(videoId: String, resolution: Int): VideoPair {
        ensureInit()
        val yt = ServiceList.YouTube
        val info = NPStreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$videoId")
        val muxed = info.videoStreams.filter {
            it.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 && resOf(it) > 0
        }
        if (muxed.isNotEmpty()) {
            val want = if (resolution <= 0) muxed.maxByOrNull { resOf(it) }
                       else muxed.minByOrNull { kotlin.math.abs(resOf(it) - resolution) }
            return VideoPair((want ?: muxed.first()).content, null)
        }
        // v1.6: fallback adaptive — video-only DASH (≤1080p) digabung audio via MergingMediaSource
        val vOnly = info.videoOnlyStreams.filter { resOf(it) > 0 }
        if (vOnly.isEmpty()) throw IllegalStateException("no video stream")
        val pool = vOnly.filter { resOf(it) <= 1080 }.ifEmpty { vOnly }
        val v = if (resolution <= 0) pool.maxByOrNull { resOf(it) } ?: pool.first()
                else pool.minByOrNull { kotlin.math.abs(resOf(it) - resolution) } ?: pool.first()
        val a = pickAudio(info.audioStreams) ?: throw IllegalStateException("no audio stream")
        return VideoPair(v.content, a.content)
    }

    /** video = muxed URL (audio=null), atau video-only + audio utk MergingMediaSource. */
    data class VideoPair(val video: String, val audio: String?)

    suspend fun resolveVideo(videoId: String, resolution: Int = 0, muxedOnly: Boolean = false): VideoPair =
        withContext(Dispatchers.IO) {
            val key = "${if (muxedOnly) "m" else "v"}$videoId@$resolution"
            val now = System.currentTimeMillis()
            val hit = cache[key]
            if (hit != null && now < hit.second) return@withContext VideoPair(hit.first, null)
            var fresh: VideoPair? = null
            var last: Exception? = null
            repeat(3) {
                try { fresh = viaNewPipeVideo(videoId, resolution); return@repeat } catch (e: Exception) { last = e; kotlinx.coroutines.delay(300) }
            }
            val f = fresh ?: throw (last ?: IllegalStateException("video resolve failed"))
            cache[key] = f.video to (now + CACHE_MS)
            persist()
            f
        }

    private fun viaNewPipe(videoId: String): String {
        ensureInit()
        val yt = ServiceList.YouTube
        val url = "https://www.youtube.com/watch?v=$videoId"
        val info = NPStreamInfo.getInfo(yt, url)
        val audio = pickAudio(info.audioStreams)
            ?: throw IllegalStateException("no audio stream")
        return audio.content
    }

    // v3.4: NewPipeExtractor v0.26.5 MATI (YouTube blok innertube visitor_id → balikin HTML).
    // Fallback native langsung: loader.to via server API (path terbukti jalan) — tanpa nunggu
    // JS watchdog, jadi lagu mulai dlm ~5-10s meski NewPipe mati.
    private fun viaLoader(videoId: String): String {
        val startUrl = URL("https://rythmix-music.vercel.app/api/download-start?videoId=$videoId")
        val st = (startUrl.openConnection() as HttpURLConnection).let { c ->
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.inputStream.bufferedReader().use { it.readText() }
        }
        val obj = JSONObject(st)
        val progressUrl = obj.optString("progressUrl")
        if (progressUrl.isBlank()) throw IllegalStateException("loader: no progress url")
        repeat(30) {
            Thread.sleep(1000)
            val body = (URL(progressUrl).openConnection() as HttpURLConnection).let { c ->
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.inputStream.bufferedReader().use { it.readText() }
            }
            val p = JSONObject(body)
            if (p.optInt("success") == 1 && p.optString("download_url").isNotBlank()) {
                return p.getString("download_url")
            }
            if (p.optString("text") == "error") throw IllegalStateException("loader: job error")
        }
        throw IllegalStateException("loader: timeout")
    }

    suspend fun resolve(videoId: String, preferredTitle: String = "", preferredArtist: String = ""): StreamInfo =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val hit = cache[videoId]
            if (hit != null && now < hit.second) {
                return@withContext StreamInfo(hit.first, preferredTitle.ifBlank { videoId }, preferredArtist, 0L)
            }
            // v4.6: SEQUENTIAL — loader.to dulu (path terbukti, 5-10s, tanpa racun PO-token),
            // NewPipe cuma fallback kalau loader gagal. Race v3.7 tetap membiarkan NewPipe
            // "berebut" dan menambah delay/hang di sebagian device.
            var fresh: String? = null
            var last: Exception? = null
            try { fresh = viaLoader(videoId) } catch (e: Exception) { last = e }
            if (fresh == null) {
                try { fresh = viaNewPipe(videoId) } catch (e: Exception) { last = e }
            }
            val f = fresh ?: throw (last ?: IllegalStateException("resolve failed"))
            val ttl = if (f.contains("googlevideo.com")) CACHE_MS else LOADER_TTL_MS
            cache[videoId] = f to (System.currentTimeMillis() + ttl)
            if (cache.size > MAX_ENTRIES) {
                // evict oldest (drop up to 50)
                cache.entries.sortedBy { it.value.second }.take(50).forEach { cache.remove(it.key) }
            }
            persist()
            StreamInfo(f, preferredTitle.ifBlank { videoId }, preferredArtist, 0L)
        }

    /** v4.6: panaskan URL lagu berikutnya di latar — next/autoplay instan.
        v4.7: kalau cache lokal miss → GET Range 0-1 ke /api/stream.m4a =
        server sekaligus resolve & cache; client cuma tarik 2 byte. */
    fun prewarm(videoId: String) {
        if (videoId.isBlank()) return
        val now = System.currentTimeMillis()
        val hit = cache[videoId]
        if (hit != null && now < hit.second) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("https://rythmix-music.vercel.app/api/stream.m4a?videoId=$videoId&wait=1").openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 5_000
                conn.readTimeout = 55_000
                conn.requestMethod = "GET"
                conn.responseCode // 302 = server cache warm; 202 = job jalan (worker lanjut)
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    // v3.1: playback error → URL di-cache kemungkinan dead (403/signature expired) —
    // evict supaya retry resolve fresh, bukan nyangkut URL busuk sampe 5.5 jam.
    fun evict(videoId: String) {
        if (videoId.isBlank()) return
        if (cache.remove(videoId) != null) persist()
    }

    /** v4.7: URL fresh dari cache lokal (tanpa resolve) atau null — dipakai play() langsung. */
    fun cachedUrl(videoId: String): String? {
        val now = System.currentTimeMillis()
        val hit = cache[videoId]
        return if (hit != null && now < hit.second && hit.first.isNotBlank()) hit.first else null
    }
}
