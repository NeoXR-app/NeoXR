package com.neoxr.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * In-app browser for sites that expose no DeoVR feed: real engine, JS players, logins.
 *
 * Two escape hatches for VR-gated pages: a "View" switch (the User-Agent the site is
 * told about — it drives bot filters and mobile/headset/desktop layouts), and stream
 * sniffing — any m3u8/mp4 the page loads can be handed to the built-in VR player,
 * which sidesteps the site's headset detection entirely. WebXR is NOT available in
 * Android WebView, so in-page "enter VR" flows cannot complete regardless of the UA
 * claimed; the sniffer is the only route to stereo playback here.
 *
 * With glasses connected the page renders on THEM (GlassesOut) and the phone acts as
 * a touchpad controller: on-page overlay buttons are hidden (a cursor reaches them
 * poorly and they clutter the page) — Play VR / View / close move to the controller
 * strip instead.
 */
class WebViewActivity : Activity() {

    private companion object {
        // what the site is told it is talking to (User-Agent); labelled "View" in the UI
        // because the visible effect is which version of the site comes back
        val UA_NAMES = listOf("Phone", "Quest 3", "Desktop")
        val UA_STRINGS = listOf(
            null, // WebView default
            "Mozilla/5.0 (X11; Linux x86_64; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "OculusBrowser/35.4.0.13.161.577564510 SamsungBrowser/4.0 " +
                    "Chrome/130.0.6723.140 VR Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/130.0.0.0 Safari/537.36"
        )
        val STREAM = Regex("\\.(m3u8|mpd|mp4)(\\?|$)", RegexOption.IGNORE_CASE)

        /**
         * Recognises a playable media URL in page traffic. Besides the plain
         * `.m3u8/.mpd/.mp4` case, some video platforms serve extension-less
         * googlevideo `videoplayback` URLs. Byte-range params are stripped before the
         * URL reaches the player, otherwise it would "play" a single chunk. Some of
         * those requests carry no `mime=` at all (SABR/UMP transport) — accept them
         * too, dropping `ump`/`srfvp` so a plain GET returns a classic progressive
         * stream; only an explicit `mime=audio` is rejected.
         */
        fun sniffStream(u: String): String? = when {
            STREAM.containsMatchIn(u) -> u
            u.contains("videoplayback") && !u.contains("mime=audio") ->
                u.replace(Regex("&(range|rn|rbuf|ump|srfvp)=[^&]*"), "")
            else -> null
        }

        /**
         * Page scale, in percent. Smaller fits more on screen (the glasses magnify
         * everything) but costs sharpness: 3D-SBS halves the horizontal resolution, so
         * tiny glyphs end up with sub-pixel strokes. Exposed as a button rather than a
         * fixed value — the right trade-off differs per site and per eyesight.
         */
        val ZOOMS = listOf(35, 50, 65, 80, 100)
    }

    private lateinit var web: WebView
    private lateinit var btnUa: TextView
    private lateinit var btnVr: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnFwd: TextView
    private lateinit var btnZoom: TextView
    private lateinit var leftBar: LinearLayout
    private lateinit var rightBar: LinearLayout
    private var ctrlVr: TextView? = null // controller-strip twin of btnVr (glasses mode)
    private var glassesOut: GlassesOut? = null
    private var zoom = 50
    private val prefs by lazy { getSharedPreferences("neoxr", Context.MODE_PRIVATE) }
    // Quest 3 by default: VR sites serve their headset layout to it. Deliberately not
    // persisted across launches — a silently remembered fake UA can break every site at
    // once with no obvious cause, while a fixed known default is one tap from
    // Phone/Desktop.
    private var uaIndex = 1
    @Volatile private var stream: String? = null

    /**
     * Builds a script that rewrites the page's viewport meta to a layout width wide
     * enough that, at [pct] zoom, the page fills the window: the site reflows
     * responsively instead of being cropped. The width is computed here in Kotlin, not
     * from `screen.width` in the page — that value already reflects the applied
     * viewport, so re-running the script would shrink it again (2x -> 4x -> 8x).
     */
    private fun shrinkJs(pct: Int): String {
        val cssWidth = (resources.displayMetrics.widthPixels /
                resources.displayMetrics.density * (100f / pct)).toInt()
        return """
        (function() {
          var m = document.querySelector('meta[name=viewport]');
          if (!m) {
            m = document.createElement('meta');
            m.setAttribute('name', 'viewport');
            (document.head || document.documentElement).appendChild(m);
          }
          m.setAttribute('content', 'width=$cssWidth, initial-scale=1, user-scalable=yes');
        })();
        """
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fullscreenOverCutout()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true // pinch to fine-tune on top of the shrink
            settings.displayZoomControls = false
            settings.userAgentString = UA_STRINGS[uaIndex]
            zoom = prefs.getInt("webZoom", 50)
            // Zoom needs both mechanisms. This one sets the starting scale so a page
            // opens already fitted; the viewport rewrite below only takes effect after
            // the first layout, which would otherwise leave the page visibly zoomed in.
            setInitialScale(zoom)
            webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    view?.evaluateJavascript(shrinkJs(zoom), null) // at first paint
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(shrinkJs(zoom), null) // and again once settled
                    updateNav()
                }

                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    val u = request?.url?.toString()?.let { sniffStream(it) }
                    if (u != null) {
                        // audio and video requests interleave on some CDNs; never let a
                        // mime-less candidate displace an already confirmed video URL
                        val cur = stream
                        if (cur == null || u.contains("mime=video") ||
                            !cur.contains("mime=video")
                        ) stream = u
                        runOnUiThread {
                            btnVr.visibility = View.VISIBLE
                            ctrlVr?.visibility = View.VISIBLE
                        }
                    }
                    return null // sniff only, let the WebView load it
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(
                    view: WebView?, errorCode: Int, description: String?, failingUrl: String?
                ) {
                    // a silently black page is undiagnosable — surface main-frame failures
                    // only, sub-resource errors are noise
                    if (failingUrl == view?.url) {
                        Toast.makeText(this@WebViewActivity, "Page error: $description", Toast.LENGTH_LONG).show()
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    // glasses mode: the root lives on the presentation, so swapping the
                    // ACTIVITY content would crash on restore ("view already has a
                    // parent"); in-page fullscreen is pointless there anyway — stereo
                    // playback goes through ▶ VR
                    if (glassesOut != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    setContentView(view)
                    fullscreenOverCutout()
                }

                override fun onHideCustomView() {
                    if (glassesOut != null) return
                    setContentView(rootView())
                    fullscreenOverCutout()
                }
            }
        }
        val glasses = Glasses.find(this)
        if (glasses != null) {
            // with glasses attached the page lives on THEM at native resolution and the
            // phone becomes a touchpad controller with a cursor. The on-page overlay
            // buttons are dead weight there — hide them and move their jobs to the
            // controller strip.
            val out = GlassesOut(this, glasses, rootView())
            glassesOut = out
            leftBar.visibility = View.GONE
            rightBar.visibility = View.GONE
            ctrlVr = GlassesOut.stripButton(this, "▶︎ Play VR") {
                stream?.let {
                    startActivity(Intent(this, PlayerActivity::class.java).putExtra("stream", it))
                }
            }.apply {
                visibility = View.GONE
                // the call to action on this screen — the one azure text in the grid
                setTextColor(getColor(R.color.primary))
            }
            // label is the mode alone — "View: Quest 3" overflows a uniform grid cell
            val ctrlView = GlassesOut.stripButton(this, UA_NAMES[uaIndex]) {}
            ctrlView.setOnClickListener {
                cycleUa()
                ctrlView.text = UA_NAMES[uaIndex]
            }
            // ✕ first: the strip scrolls sideways and the close button must never be
            // the one pushed off the phone screen
            setContentView(GlassesOut.controller(this, out, listOf(
                GlassesOut.stripButton(this, "✕") { finish() },
                ctrlVr!!, ctrlView,
                GlassesOut.stripButton(this, "−") { stepZoom(-1) }, // page scale
                GlassesOut.stripButton(this, "+") { stepZoom(+1) }
            )))
        } else {
            setContentView(rootView())
        }
        web.loadUrl(intent.getStringExtra("url")!!)
    }

    private var root: SbsFrameLayout? = null

    /** The whole browser lives in an SBS wrapper, like the menus: long press toggles it. */
    private fun rootView(): SbsFrameLayout = root ?: SbsFrameLayout(this).also { frame ->
        root = frame
        frame.addView(web)

        // History arrows are the most-used control here and a mis-tap sends you the
        // wrong way, so they are the biggest targets on screen and sit far apart.
        btnBack = bar("←", wide = true) { if (web.canGoBack()) web.goBack(); updateNav() }
        btnFwd = bar("→", wide = true) { if (web.canGoForward()) web.goForward(); updateNav() }
        // one gap value for the whole strip: enough that a miss lands on nothing,
        // small enough to read as one group
        val gap = (16 * resources.displayMetrics.density).toInt()
        (btnFwd.layoutParams as LinearLayout.LayoutParams).marginStart = gap
        btnZoom = bar("$zoom%") { cycleZoom() }
        (btnZoom.layoutParams as LinearLayout.LayoutParams).marginStart = gap
        leftBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
            addView(btnBack)
            addView(btnFwd)
            addView(btnZoom)
        }
        frame.addView(leftBar)

        btnVr = bar("▶ VR") {
            stream?.let {
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("stream", it))
            }
        }
        btnVr.visibility = View.GONE
        btnUa = bar("View: ${UA_NAMES[uaIndex]}") {
            cycleUa()
            btnUa.text = "View: ${UA_NAMES[uaIndex]}"
        }
        rightBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
            addView(btnVr)
            addView(btnUa)
            addView(bar("✕") { finish() }) // corner: back to the site list
        }
        frame.addView(rightBar)

        frame.sbs = prefs.getBoolean("menuSbs", false)
        frame.onLongPressToggle = {
            frame.sbs = !frame.sbs
            prefs.edit().putBoolean("menuSbs", frame.sbs).apply()
        }
        updateNav()
    }

    private fun cycleUa() {
        uaIndex = (uaIndex + 1) % UA_STRINGS.size
        web.settings.userAgentString = UA_STRINGS[uaIndex]
        btnUa.text = "View: ${UA_NAMES[uaIndex]}"
        Toast.makeText(this, "Loading the ${UA_NAMES[uaIndex]} version", Toast.LENGTH_SHORT).show()
        web.reload()
    }

    /**
     * Re-fits the page at [new] zoom without reloading: the viewport tag sets the new
     * layout width, and zoomBy moves the current zoom by the same ratio — a viewport
     * change on its own keeps the old scale and leaves the page zoomed in.
     * setInitialScale carries the choice to the next page.
     */
    private fun setZoomPct(new: Int) {
        if (new == zoom) return
        val was = zoom
        zoom = new
        prefs.edit().putInt("webZoom", zoom).apply()
        btnZoom.text = "$zoom%"
        web.setInitialScale(zoom)
        web.evaluateJavascript(shrinkJs(zoom), null)
        web.zoomBy(zoom.toFloat() / was)
        Toast.makeText(this, "Scale $zoom%", Toast.LENGTH_SHORT).show()
    }

    private fun cycleZoom() =
        setZoomPct(ZOOMS[(ZOOMS.indexOf(zoom).takeIf { it >= 0 }?.plus(1) ?: 0) % ZOOMS.size])

    private fun stepZoom(dir: Int) {
        val i = (ZOOMS.indexOf(zoom).takeIf { it >= 0 } ?: 1) + dir
        setZoomPct(ZOOMS[i.coerceIn(0, ZOOMS.size - 1)])
    }

    private fun updateNav() {
        btnBack.alpha = if (web.canGoBack()) 1f else 0.35f
        btnFwd.alpha = if (web.canGoForward()) 1f else 0.35f
    }

    /** Sized like the rest of the app: small type in a box still big enough to hit. */
    private fun bar(label: String, wide: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = if (wide) 15f else 10f
        gravity = Gravity.CENTER
        minWidth = ((if (wide) 60 else 34) * resources.displayMetrics.density).toInt()
        minHeight = ((if (wide) 44 else 32) * resources.displayMetrics.density).toInt()
        setTextColor(getColor(R.color.on_surface))
        setBackgroundResource(R.drawable.bg_input)
        val p = (10 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, p / 2)
        (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )).also { it.marginStart = p / 2; it.topMargin = p / 2; layoutParams = it }
        setOnClickListener { onClick() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        web.destroy()
    }
}
