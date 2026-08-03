package com.neoxr.player

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Feed browser: sections (feed scenes), title search, sort and filters.
 *
 * The DeoVR protocol defines no categories, filters, search or pagination — a feed is
 * a flat list — so everything here operates on the already loaded list. Filters that
 * depend on per-video fields (resolution, projection, 3D) are offered ONLY when the
 * feed ships full items; feeds using the documented short form get none, because a
 * filter over unknown metadata would silently lie.
 *
 * The Browse panel is a plain in-layout View, not a Spinner or dialog: those render in
 * a separate window, which the SBS wrapper cannot duplicate, so in glasses mode their
 * popups are invisible.
 */
class VideoListActivity : AppCompatActivity() {

    // videoUrl -> true if the video is premium-locked (no playable sources without a subscription)
    private val premiumLocked = ConcurrentHashMap<String, Boolean>()
    private val checking = Collections.synchronizedSet(HashSet<String>())
    private val checkPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    private var scenes: List<Scene> = emptyList()
    private var sceneIdx = 0
    private var sort = 0 // 0 = feed order, 1 = longest, 2 = shortest, 3 = A-Z
    private var query = ""
    private val flags = HashSet<String>() // active filter keys, see filtered()
    private var adapter: VideoAdapter? = null

    private lateinit var browse: TextView
    private lateinit var panel: View
    private lateinit var rows: LinearLayout
    private lateinit var grid: GridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // findViewById via wrap: with glasses attached the content lives on THEM;
        // the controller strip gets a close button (in-content ✕ works too, via cursor)
        val wrap = SbsFrameLayout.attach(
            this, R.layout.activity_videos,
            listOf(GlassesOut.stripButton(this, "✕") { finish() })
        )
        val url = intent.getStringExtra("url")!!

        wrap.findViewById<TextView>(R.id.siteTitle).text = try {
            URL(url).host.removePrefix("www.")
        } catch (e: Exception) {
            url
        }

        grid = wrap.findViewById(R.id.grid)
        browse = wrap.findViewById(R.id.btnBrowse)
        panel = wrap.findViewById(R.id.browsePanel)
        rows = wrap.findViewById(R.id.browseRows)
        val loading = wrap.findViewById<ProgressBar>(R.id.loading)
        val empty = wrap.findViewById<TextView>(R.id.empty)

        browse.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        wrap.findViewById<TextView>(R.id.btnClose).setOnClickListener { finish() }
        wrap.findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                query = s.toString().trim()
                refilter()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        thread {
            try {
                val loaded = Deo.fetchScenes(url)
                runOnUiThread {
                    loading.visibility = View.GONE
                    scenes = loaded
                    if (loaded.sumOf { it.videos.size } == 0) {
                        empty.visibility = View.VISIBLE
                        return@runOnUiThread
                    }
                    buildPanel()
                    refilter()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (e is org.json.JSONException || e is Deo.HttpException) {
                        // the site responded but serves no DeoVR feed (unparsable body,
                        // or a 4xx/5xx aimed at our client, e.g. TLS-fingerprint bot
                        // filters that a real browser passes) — fall back to the browser
                        startActivity(Intent(this, WebViewActivity::class.java).putExtra("url", url))
                        finish()
                    } else {
                        // timeout/DNS/connection failure: must stay visible and must NOT
                        // be masked by the browser fallback, which would show a blank
                        // page over a network fault. Reported in-layout so SBS mode
                        // duplicates it (Toasts are separate windows, invisible in
                        // glasses)
                        loading.visibility = View.GONE
                        empty.text = e.message ?: "Load error"
                        empty.visibility = View.VISIBLE
                    }
                }
            }
        }

        grid.setOnItemClickListener { _, _, pos, _ ->
            val v = (grid.adapter as VideoAdapter).videos[pos]
            val i = Intent(this, PlayerActivity::class.java)
            if (v.videoUrl != null) i.putExtra("videoUrl", v.videoUrl) else i.putExtra("json", v.raw)
            startActivity(i)
        }
    }

    /** Current scene, narrowed by search + filters and ordered by the chosen sort. */
    private fun filtered(): List<VideoItem> {
        var out = scenes.getOrNull(sceneIdx)?.videos ?: return emptyList()
        if (query.isNotEmpty()) out = out.filter { it.title.contains(query, ignoreCase = true) }
        for (f in flags) out = when (f) {
            "free" -> out.filter { premiumLocked[it.videoUrl] != true }
            "4k" -> out.filter { it.maxHeight >= 2160 }
            "8k" -> out.filter { it.maxHeight >= 4320 }
            "360" -> out.filter { it.angleDeg >= 300 }
            "180" -> out.filter { it.angleDeg in 100..299 }
            "flat" -> out.filter { it.angleDeg == 0 }
            "3d" -> out.filter { it.stereo != null && it.stereo != "off" }
            "2d" -> out.filter { it.stereo == "off" }
            else -> out
        }
        return when (sort) {
            1 -> out.sortedByDescending { it.lengthSec }
            2 -> out.sortedBy { it.lengthSec }
            3 -> out.sortedBy { it.title.lowercase() }
            else -> out
        }
    }

    /** [toTop] false for background refreshes (premium checks) so scrolling survives. */
    private fun refilter(toTop: Boolean = true) {
        val videos = filtered()
        val a = adapter ?: VideoAdapter().also { adapter = it; grid.adapter = it }
        a.videos = videos
        a.notifyDataSetChanged()
        if (toTop) grid.setSelection(0)
        browse.text = "${scenes.getOrNull(sceneIdx)?.name ?: "Browse"}  ${videos.size} ▾"
    }

    /**
     * Panel rows: sections (only when the feed has several), sort, and the filters the
     * loaded data can actually answer. A filter that would match everything or nothing
     * is not shown — a dead toggle is worse than a missing one.
     */
    private fun buildPanel() {
        rows.removeAllViews()
        val all = scenes.flatMap { it.videos }
        if (scenes.size > 1) {
            header("Sections")
            scenes.forEachIndexed { i, s ->
                row("${s.name} · ${s.videos.size}", sceneIdx == i, radio = true) {
                    sceneIdx = i
                    buildPanel()
                    refilter()
                }
            }
        }
        header("Sort")
        listOf("Feed order", "Longest first", "Shortest first", "Title A–Z")
            .forEachIndexed { i, label ->
                row(label, sort == i, radio = true) {
                    sort = i
                    buildPanel()
                    refilter()
                }
            }
        header("Filters")
        val available = buildList {
            add("free" to "Free only")
            if (all.any { it.maxHeight >= 2160 }) add("4k" to "4K+")
            if (all.any { it.maxHeight >= 4320 }) add("8k" to "8K")
            if (all.any { it.angleDeg >= 300 }) add("360" to "360°")
            if (all.any { it.angleDeg in 100..299 }) add("180" to "180°")
            if (all.any { it.angleDeg == 0 }) add("flat" to "Flat")
            if (all.any { it.stereo != null && it.stereo != "off" }) add("3d" to "3D")
            if (all.any { it.stereo == "off" }) add("2d" to "2D")
        }
        for ((key, label) in available) {
            row(label, key in flags, radio = false) {
                if (!flags.remove(key)) flags.add(key)
                buildPanel()
                refilter()
            }
        }
    }

    private fun header(text: String) {
        rows.addView(TextView(this).apply {
            this.text = text.uppercase()
            textSize = 8f
            letterSpacing = 0.14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(getColor(R.color.on_surface_variant))
            val p = (18 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 4)
        })
    }

    /**
     * 38dp row — type is sized for the glasses, where the panel is magnified, but the
     * row stays tall enough to hit in the halved SBS copies. The selected one carries
     * the brand beam on its leading edge; multi-select filters keep an explicit box,
     * since a checkbox is what tells the eye "several of these can be on at once".
     */
    private fun row(label: String, on: Boolean, radio: Boolean, onClick: () -> Unit) {
        rows.addView(TextView(this).apply {
            text = if (radio) label else "${if (on) "☑" else "☐"}   $label"
            textSize = 10f
            typeface = android.graphics.Typeface.create(
                if (on) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL
            )
            gravity = android.view.Gravity.CENTER_VERTICAL
            setTextColor(getColor(if (on) R.color.on_surface else R.color.on_surface_variant))
            setBackgroundResource(if (on) R.drawable.bg_row_active else 0)
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (38 * resources.displayMetrics.density).toInt()
            ).apply {
                marginStart = p / 2; marginEnd = p / 2; topMargin = p / 8
            }
            setOnClickListener { onClick() }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        checkPool.shutdownNow()
    }

    inner class VideoAdapter : BaseAdapter() {

        var videos: List<VideoItem> = emptyList()

        private val corner = (14 * resources.displayMetrics.density).toInt() // matches bg_thumb

        override fun getCount() = videos.size
        override fun getItem(pos: Int) = videos[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            val view = convert ?: layoutInflater.inflate(R.layout.item_video, parent, false)
            val v = videos[pos]
            view.findViewById<TextView>(R.id.videoTitle).text = v.title
            view.findViewById<TextView>(R.id.videoDuration).apply {
                visibility = if (v.lengthSec > 0) View.VISIBLE else View.GONE
                text = Deo.formatDuration(v.lengthSec)
            }
            val thumb = view.findViewById<ImageView>(R.id.videoThumb)
            Glide.with(this@VideoListActivity).load(v.thumb)
                .transform(CenterCrop(), RoundedCorners(corner))
                .into(thumb)

            // ponytail: listings carry no premium flag, so "Free only" resolves lazily —
            // one GET per item as it scrolls into view, and locked items drop out of the
            // grid once known. Requests go through a small fixed pool on purpose: a
            // thread-per-item burst looks like an attack to edge/anti-bot layers and
            // gets the client IP throttled site-wide. Upgrade path: a batch endpoint,
            // if any feed ever offers one.
            val url = v.videoUrl
            if ("free" in flags && url != null && !premiumLocked.containsKey(url) && checking.add(url)) {
                checkPool.execute {
                    val locked = try {
                        Deo.isPremiumLocked(Deo.httpGet(url))
                    } catch (e: Exception) {
                        false
                    }
                    premiumLocked[url] = locked
                    if (locked) runOnUiThread { refilter(toTop = false) }
                }
            }
            return view
        }
    }
}
