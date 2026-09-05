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

    // videoId -> resolved url, expire ~5.5h (YT signed URL valid ~6h)
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val CACHE_MS = 5L * 60 * 60 * 1000
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
            val it = obj.keys()
            while (it.hasNext()) {
                val id = it.next()
                val arr = obj.optJSONArray(id) ?: continue
                val url = arr.optString(0); val ts = arr.optLong(1)
                if (url.isNotBlank() && ts > 0 && cache.size < MAX_ENTRIES) cache[id] = url to ts
            }
        } catch (_: Exception) {}
    }

    private fun persist() {
        try {
            val obj = JSONObject()
            for ((id, v) in cache) {
                if (v.first.isBlank()) continue
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
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
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

    // muxed MP4 video streams (progressive: audio+video satu file — bisa buat playback & download)
    private fun viaNewPipeVideo(videoId: String, resolution: Int): String {
        ensureInit()
        val yt = ServiceList.YouTube
        val info = NPStreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$videoId")
        // ponytail: VideoStream gak punya flag muxed; M4A = container audio+video (progressive itag 18/22/37).
        val muxed = info.videoStreams.filter {
            it.format == org.schabi.newpipe.extractor.MediaFormat.M4A && it.resolution.contains(Regex("\\d+p"))
        }
        if (muxed.isEmpty()) throw IllegalStateException("no muxed video")
        val want = if (resolution <= 0) muxed.maxByOrNull { it.resolution.filter(Char::isDigit).toIntOrNull() ?: 0 }
                   else muxed.minByOrNull { kotlin.math.abs((it.resolution.filter(Char::isDigit).toIntOrNull() ?: 9999) - resolution) }
        return (want ?: muxed.first()).content
    }

    suspend fun resolveVideo(videoId: String, resolution: Int = 0): String = withContext(Dispatchers.IO) {
        val key = "v$videoId@$resolution"
        val now = System.currentTimeMillis()
        val hit = cache[key]
        if (hit != null && now - hit.second < CACHE_MS) return@withContext hit.first
        var fresh: String? = null
        var last: Exception? = null
        repeat(3) {
            try { fresh = viaNewPipeVideo(videoId, resolution); return@repeat } catch (e: Exception) { last = e; kotlinx.coroutines.delay(300) }
        }
        val f = fresh ?: throw (last ?: IllegalStateException("video resolve failed"))
        cache[key] = f to now
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

    suspend fun resolve(videoId: String, preferredTitle: String = "", preferredArtist: String = ""): StreamInfo =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val hit = cache[videoId]
            val url = if (hit != null && now - hit.second < CACHE_MS) hit.first else {
                // fast retry: cold NewPipe can flake (signature fetch); retry 3x quickly
                var fresh: String? = null
                var last: Exception? = null
                repeat(3) {
                    try { fresh = viaNewPipe(videoId); return@repeat } catch (e: Exception) { last = e; kotlinx.coroutines.delay(250) }
                }
                val f = fresh ?: throw (last ?: IllegalStateException("resolve failed"))
                cache[videoId] = f to now
                if (cache.size > MAX_ENTRIES) {
                    // evict oldest (drop up to 50)
                    cache.entries.sortedBy { it.value.second }.take(50).forEach { cache.remove(it.key) }
                }
                persist()
                f
            }
            StreamInfo(url, preferredTitle.ifBlank { videoId }, preferredArtist, 0L)
        }

    // background pre-warm: fill cache for upcoming queue items so clicking next is instant
    fun prewarm(videoId: String) {
        if (videoId.isBlank()) return
        val now = System.currentTimeMillis()
        val hit = cache[videoId]
        if (hit != null && now - hit.second < CACHE_MS) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { resolve(videoId) } catch (_: Exception) {}
        }
    }
}
