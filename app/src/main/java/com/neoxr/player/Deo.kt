package com.neoxr.player

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

data class VideoItem(
    val title: String,
    val thumb: String?,
    val videoUrl: String?,
    val lengthSec: Int,
    val raw: String,
    // Listing-level metadata — present only on feeds that ship FULL items (most use
    // the documented short form: title/thumb/length/video_url). Drives which filters
    // the list screen offers, so unknown must stay distinguishable from a real value.
    /** Best source height, 0 = unknown. */
    val maxHeight: Int = 0,
    /** Projection width in degrees, -1 = unknown. */
    val angleDeg: Int = -1,
    /** Eye layout, null = unknown. */
    val stereo: String? = null
)
data class Scene(val name: String, val videos: List<VideoItem>)
data class VideoInfo(
    val title: String,
    val url: String,
    /** Projection: 0 = screen-locked flat, else an equirect dome this many degrees wide. */
    val angleDeg: Int,
    /** Eye layout: "sbs" | "tb" | "off" (mono). */
    val stereo: String,
    /** All selectable sources (height → url) from the preferred codec, best first. */
    val sources: List<Pair<Int, String>>
)

object Deo {

    /** Server answered but refused us — the site itself is alive (browsable). */
    class HttpException(code: Int, url: String) : Exception("HTTP $code: $url")

    // ponytail: decoder cap — highest source height we auto-pick; raise if the phone chews 8K HEVC
    const val MAX_HEIGHT = 3200

    // OkHttp, not HttpURLConnection: falls back across routes (broken IPv6 → IPv4)
    // like a browser and retries GETs on connect/read stalls — HttpURLConnection
    // just dies with "read timed out" on networks with a half-dead IPv6 path
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // Always send a real browser UA. Homemade product UAs earn a bad bot score at
    // CDN edges, which then tarpit the whole source IP (connections accepted, no
    // bytes → read timeouts on every site). For the same reason, callers must pace
    // their requests: no per-item bursts, use a small fixed worker pool.
    const val UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    fun httpGet(url: String): String =
        http.newCall(
            Request.Builder().url(url).header("User-Agent", UA).build()
        ).execute().use { resp ->
            if (resp.code >= 400) throw HttpException(resp.code, url)
            resp.body!!.string()
        }

    /** Adds https:// if missing; the path is kept exactly as the user typed it. */
    fun normalizeSiteUrl(input: String): String {
        var u = input.trim()
        require(u.isNotEmpty()) { "empty url" }
        if (!u.contains("://")) u = "https://$u"
        URL(u) // validate
        return u
    }

    fun absolute(base: String, url: String): String = URL(URL(base), url).toString()

    /** Multi-video listing JSON: {"scenes":[{"name":..,"list":[{title,thumbnailUrl,video_url|encodings,..}]}]} */
    fun parseScenes(baseUrl: String, json: String): List<Scene> {
        val scenes = JSONObject(json).optJSONArray("scenes") ?: JSONArray()
        return (0 until scenes.length()).map { i ->
            val s = scenes.getJSONObject(i)
            val list = s.optJSONArray("list") ?: JSONArray()
            Scene(
                s.optString("name", "Scene ${i + 1}"),
                (0 until list.length()).map { j ->
                    val v = list.getJSONObject(j)
                    val full = v.has("screenType") || v.has("viewAngle") || v.has("is3d")
                    VideoItem(
                        v.optString("title"),
                        v.optString("thumbnailUrl").ifEmpty { null }?.let { absolute(baseUrl, it) },
                        v.optString("video_url").ifEmpty { null }?.let { absolute(baseUrl, it) },
                        v.optInt("videoLength"),
                        v.toString(),
                        listSources(v).maxOfOrNull { it.first } ?: 0,
                        if (full) feedAngle(v) else -1,
                        if (full) feedStereo(v) else null
                    )
                }
            )
        }
    }

    /** Single-video JSON: picks best source from encodings[].videoSources[]. */
    fun parseVideo(json: String): VideoInfo {
        val v = JSONObject(json)
        val sources = listSources(v)
        val picked = pickFrom(sources) ?: throw IllegalArgumentException(
            if (v.optBoolean("isPremium")) "Premium video — subscription required"
            else "No video sources in JSON"
        )
        return VideoInfo(v.optString("title"), picked, feedAngle(v), feedStereo(v), sources)
    }

    /**
     * Real feeds send more than the documented screenType values: screenType null
     * with viewAngle 120/360, stereoMode "sbs2l"/"mono", an is3d flag. Anything
     * unrecognized maps to the DeoVR defaults (180 dome, sbs) — the dominant stereo
     * format in the wild — instead of silently rendering with the wrong projection.
     */
    private fun feedAngle(v: JSONObject): Int {
        val screen = v.optString("screenType").lowercase()
        val viewAngle = v.optInt("viewAngle")
        return when {
            screen == "flat" -> 0
            screen == "sphere" || screen == "360" || viewAngle >= 270 -> 360
            viewAngle in 60 until 270 -> viewAngle // partial domes, fisheye190/200 spans
            else -> 180 // dome default; fisheye/mkx200/rf52 approximated as 180 dome
        }
    }

    private fun feedStereo(v: JSONObject): String {
        val s = v.optString("stereoMode").lowercase()
        return when {
            s.startsWith("sbs") || s == "lr" -> "sbs"
            s.startsWith("tb") || s == "ou" -> "tb"
            s == "mono" || s == "off" -> "off"
            s.isEmpty() -> if (v.has("is3d") && !v.optBoolean("is3d")) "off" else "sbs"
            else -> "sbs"
        }
    }

    /**
     * Best-effort format guess for raw stream URLs with no DeoVR metadata (streams
     * intercepted by the in-app browser). Filename tokens first — the cross-player
     * convention (DeoVR/HereSphere/Skybox): _VR180_, _360_, _SBS_, _TB_, MKX200… —
     * then frame aspect once the decoder reports it, else the wide-SBS default of
     * intercepted cam streams. Returns angleDeg (0 = flat) to stereo ("sbs"|"tb"|"off").
     */
    fun guessFormat(url: String, width: Int = 0, height: Int = 0): Pair<Int, String> {
        // tokens must be whole delimiter-separated words: kills "3600x1800"/"h360p" false hits
        val parts = url.substringBefore('?').substringAfterLast('/').uppercase()
            .split('_', '-', '.', ' ', '(', ')').filterTo(HashSet()) { it.isNotEmpty() }
        fun has(vararg tokens: String) = tokens.any { it in parts }

        val stereo = when {
            has("TB", "BT", "OU", "3DV", "TB180", "180TB", "TB360", "360TB", "OVERUNDER",
                "FTB", "TBF") -> "tb"
            has("SBS", "LR", "RL", "3DH", "3D", "FSBS", "SBSF", "LRF", "SBS180", "180SBS",
                "LR180", "180LR", "LR360", "360LR", "180X180", "VR180", "FISHEYE", "F180",
                "MKX200", "MKX220", "MKX22", "VRCA220", "RF52") -> "sbs"
            has("MONO", "2D") -> "off"
            else -> null
        }
        val angle = when {
            has("360", "MONO360", "TB360", "360TB", "LR360", "360LR") -> 360
            has("180", "VR180", "180X180", "F180", "180F", "TB180", "180TB", "SBS180",
                "180SBS", "LR180", "180LR", "FISHEYE", "FISHEYE190", "MKX200", "MKX220",
                "MKX22", "VRCA220", "RF52") -> 180
            else -> null
        }
        if (angle != null) return angle to (stereo ?: if (angle >= 360) "off" else "sbs")
        if (stereo != null) return 0 to stereo // stereo tag alone = flat 3D rip

        if (width > 0 && height > 0) {
            val r = width.toFloat() / height
            return when {
                r > 3.4f -> 0 to "sbs"            // two 16:9 eyes side by side
                r in 1.9f..2.1f -> 180 to "sbs"   // classic 180 SBS equirect (1:1 per eye)
                r in 0.95f..1.05f -> 180 to "sbs" // legacy anamorphic half-width 180 SBS
                r < 0.6f -> 180 to "tb"
                r in 1.7f..1.85f -> 90 to "sbs"   // 16:9 web streams: near-always baked wide-SBS
                else -> 0 to "off"                // 4:3 & friends: plain 2D video
            }
        }
        return 90 to "sbs" // no signal yet: the intercepted-stream prior
    }

    /**
     * Fetches a feed from the exact URL; for bare domains falls back to the /deovr
     * discovery convention. Throws when there is no feed — the caller then opens
     * the site in the in-app browser. User-typed paths are never rewritten.
     */
    fun fetchScenes(url: String): List<Scene> {
        try {
            return parseScenes(url, httpGet(url))
        } catch (first: Exception) {
            val path = URL(url).path
            if (path.isEmpty() || path == "/") {
                val alt = url.trimEnd('/') + "/deovr"
                try {
                    return parseScenes(alt, httpGet(alt))
                } catch (_: Exception) {
                }
            }
            throw first
        }
    }

    fun formatDuration(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Premium videos keep their videoSources but with blank urls until subscribed. */
    fun isPremiumLocked(json: String): Boolean {
        val v = JSONObject(json)
        return v.optBoolean("isPremium") && pickSource(v) == null
    }

    /** All sources (height → url) from the most preferred codec that has any, best first. */
    fun listSources(v: JSONObject): List<Pair<Int, String>> {
        val encodings = v.optJSONArray("encodings") ?: JSONArray()
        val preference = listOf("h265", "hevc", "h264", "avc")
        val ordered = (0 until encodings.length()).map { encodings.getJSONObject(it) }
            .sortedBy { enc ->
                preference.indexOf(enc.optString("name").lowercase()).let { if (it == -1) preference.size else it }
            }
        for (enc in ordered) {
            val sources = enc.optJSONArray("videoSources") ?: continue
            val all = (0 until sources.length()).map { sources.getJSONObject(it) }
                .mapNotNull { s -> s.optString("url").ifEmpty { null }?.let { u -> s.optInt("resolution") to u } }
            if (all.isNotEmpty()) return all.distinctBy { it.first }.sortedByDescending { it.first }
        }
        return emptyList()
    }

    /** Highest source not above MAX_HEIGHT, else the smallest available. */
    private fun pickFrom(all: List<Pair<Int, String>>): String? =
        (all.firstOrNull { it.first <= MAX_HEIGHT } ?: all.lastOrNull())?.second

    private fun pickSource(v: JSONObject): String? = pickFrom(listSources(v))
}
