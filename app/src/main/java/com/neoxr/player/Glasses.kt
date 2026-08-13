package com.neoxr.player

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.webkit.WebView
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The glasses over USB-C DP alt mode are a real external display. */
object Glasses {

    private const val PREF_DISPLAY = "outputDisplay" // user's manual pick, by name

    /**
     * Vendor names XR glasses report through [Display.getName]. Many devices never
     * expose one (Android substitutes a generic localized "HDMI screen" instead),
     * which is why [find] also falls back to panel shape.
     */
    private val KNOWN = Regex("xreal|nreal|viture|rokid|rayneo|inmo|even ?realities", RegexOption.IGNORE_CASE)

    fun isKnownGlasses(name: String) = KNOWN.containsMatchIn(name)

    /**
     * Every display that can host content: non-default and carrying
     * [Display.FLAG_PRESENTATION]. The flag separates an attached screen from a
     * second built-in panel, but it does not exclude one: foldables and dual-screen
     * handhelds expose their extra internal panel this way too, which is why the
     * choice below is more than "take the first".
     */
    fun candidates(activity: Activity): List<Display> =
        (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays
            .filter {
                it.displayId != Display.DEFAULT_DISPLAY &&
                        (it.flags and Display.FLAG_PRESENTATION) != 0 &&
                        // a stale entry still lists itself but rejects windows with
                        // InvalidDisplayException once shown
                        it.isValid
            }

    /**
     * The display to render on: the user's manual pick if it is still attached,
     * otherwise the best guess — a display whose name identifies XR glasses, then
     * one with a 16:9 landscape panel (what glasses report), then the first
     * candidate.
     */
    fun find(activity: Activity): Display? {
        val all = candidates(activity)
        if (all.isEmpty()) return null
        val saved = activity.getSharedPreferences("neoxr", Context.MODE_PRIVATE)
            .getString(PREF_DISPLAY, null)
        all.firstOrNull { it.name == saved }?.let { return it }
        all.firstOrNull { KNOWN.containsMatchIn(it.name ?: "") }?.let { return it }
        all.firstOrNull { d ->
            val m = d.mode ?: return@firstOrNull false
            val w = m.physicalWidth
            val h = m.physicalHeight
            w > h && Math.abs(w.toFloat() / h - 16f / 9f) < 0.05f
        }?.let { return it }
        return all.first()
    }

    /** Remembers a manual choice (by display name) for the next launch. */
    fun remember(activity: Activity, display: Display) {
        activity.getSharedPreferences("neoxr", Context.MODE_PRIVATE)
            .edit().putString(PREF_DISPLAY, display.name).apply()
    }

    /** Moves the manual pick to the next candidate; null when there is nothing to switch to. */
    fun cycle(activity: Activity): Display? {
        val all = candidates(activity)
        if (all.size < 2) return null
        val current = find(activity) ?: return null
        val next = all[(all.indexOfFirst { it.displayId == current.displayId } + 1) % all.size]
        remember(activity, next)
        return next
    }
}

/**
 * Hosts a content-bearing [SbsFrameLayout] on the glasses display and drives a
 * cursor over it from the phone-side touchpad. Content screens (menu, video list,
 * browser) go to the glasses in their native resolution; the phone shows only the
 * controller. Clicks/scrolls are synthesized into the content in layout coords via
 * [SbsFrameLayout.inject], so they land the same with SBS on or off.
 */
class GlassesOut(
    private val activity: Activity,
    private val glassesDisplay: Display,
    val wrap: SbsFrameLayout
) {

    private val presentation = Presentation(activity, glassesDisplay)

    /** True when the display refused the presentation — the caller should stay on the phone. */
    var failed = false
        private set

    /**
     * Label for the display in use, shown on the remote so a wrong auto-pick is
     * visible. Vendor names only appear on some devices — elsewhere Android reports
     * a generic, localized "HDMI screen", so the resolution is the useful part.
     */
    val displayName: String
        get() {
            val name = glassesDisplay.name.orEmpty()
            if (Glasses.isKnownGlasses(name)) return name
            val m = glassesDisplay.mode
            return if (m != null) "GLASSES · ${m.physicalWidth}×${m.physicalHeight}"
            else "GLASSES CONNECTED"
        }

    // The densest mode the glasses offer, fastest refresh among equals (the panels
    // are natively 120 Hz). Phone/glasses links commonly top out at 1920x1080 with
    // no 3840x1080 full-SBS mode, which hard-caps stereo at 960 columns per eye
    // regardless of what the renderer does; 2D output stays pixel-perfect.
    private val bestMode = glassesDisplay.supportedModes.maxWithOrNull(
        compareBy({ it.physicalWidth.toLong() * it.physicalHeight }, { it.refreshRate })
    )

    // A drawn pointer, not a font glyph: it points up-left like a desktop cursor and
    // its TIP sits exactly at the view's top-left corner, which is the click hotspot
    // (cx, cy). A glyph would put its ink somewhere in the middle of the box and
    // clicks would land off the visible point.
    private val cursor = object : View(activity) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = activity.getColor(R.color.primary)
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            if (path.isEmpty) {
                val s = width.toFloat()
                path.moveTo(0f, 0f)
                path.lineTo(0.9f * s, 0.38f * s)
                path.lineTo(0.52f * s, 0.52f * s)
                path.lineTo(0.38f * s, 0.9f * s)
                path.close()
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, edge)
        }
    }.apply {
        isClickable = false
        val s = (16 * activity.resources.displayMetrics.density).toInt()
        // explicit params are required: FrameLayout generates MATCH_PARENT by
        // default, which would make the cursor a full-screen view
        layoutParams = FrameLayout.LayoutParams(s, s)
    }
    private var cx = 0f
    private var cy = 0f

    init {
        // a Presentation uses the default dialog theme, whose window background is
        // white; the app's screens assume the dark backdrop
        wrap.setBackgroundColor(activity.getColor(R.color.backdrop))
        wrap.addView(cursor)
        bestMode?.let { m ->
            presentation.window?.let { w ->
                w.attributes = w.attributes.apply { preferredDisplayModeId = m.modeId }
            }
        }
        presentation.setContentView(wrap)
        // Showing can fail even for a display the system just listed — it may have
        // gone away in between, or be one the app is not allowed to draw on. Report
        // it instead of crashing so the caller can keep the content on the phone.
        try {
            presentation.show()
        } catch (e: Exception) {
            android.util.Log.w("NeoXR", "presentation refused by display: $e")
            failed = true
            // hand the wrap back unattached and cursor-free, so the caller can put
            // it on the phone exactly as if no glasses had been found
            wrap.removeView(cursor)
            (wrap.parent as? android.view.ViewGroup)?.removeView(wrap)
        }

        if (!failed) {
            wrap.post {
                cx = wrap.width / 2f
                cy = wrap.height / 2f
                place()
            }
            // system dismisses the presentation when the display goes away — fall back
            presentation.setOnDismissListener { if (!activity.isDestroyed) activity.recreate() }
            // tie our window to the activity's: dismiss when it is torn down
            activity.window.decorView.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) {
                        presentation.setOnDismissListener(null)
                        presentation.dismiss()
                    }
                })
        }
    }

    private fun place() {
        cursor.translationX = cx
        cursor.translationY = cy
    }

    fun move(dx: Float, dy: Float) {
        cx = (cx + dx).coerceIn(0f, wrap.width - 1f)
        cy = (cy + dy).coerceIn(0f, wrap.height - 1f)
        place()
    }

    fun click() {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
        wrap.inject(down)
        down.recycle()
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cx, cy, 0)
        wrap.inject(up)
        up.recycle()
        // a click that lands in a text box opens a phone-side editor: a presentation
        // window is not an IME target, the activity window is. The hit test must
        // check that the click is INSIDE the box — a field can keep focus after an
        // unrelated click, so "something is focused" would reopen the editor forever.
        wrap.post {
            when (val f = wrap.findFocus()) {
                is EditText -> {
                    val r = Rect()
                    f.getDrawingRect(r)
                    wrap.offsetDescendantRectToMyCoords(f, r)
                    if (r.contains(cx.toInt(), cy.toInt())) {
                        prompt(f.text.toString(), f.hint, f.inputType) { f.setText(it) }
                    }
                }
                // web pages: inputs are DOM elements, not EditTexts — ask the page
                // what got focused (after its own focus handlers have run)
                is WebView -> wrap.postDelayed({ webPrompt(f) }, 200)
                else -> {}
            }
        }
    }

    /** Opens the phone-side editor for a focused INPUT/TEXTAREA on a web page. */
    private fun webPrompt(web: WebView) {
        web.evaluateJavascript(
            "(function(){var e=document.activeElement;if(!e)return null;" +
                    "var t=e.tagName;if(t=='INPUT'||t=='TEXTAREA'||e.isContentEditable)" +
                    "return (e.value!==undefined?e.value:e.textContent)||'';return null;})()"
        ) { res ->
            if (res == null || res == "null") return@evaluateJavascript
            val current = org.json.JSONTokener(res).nextValue() as? String ?: return@evaluateJavascript
            prompt(current, null, android.text.InputType.TYPE_CLASS_TEXT) { value ->
                // set the value AND fire input/change: framework-driven forms
                // (React and friends) ignore a bare .value write
                web.evaluateJavascript(
                    "(function(v){var e=document.activeElement;if(!e)return;" +
                            "if(e.value!==undefined){e.value=v;" +
                            "e.dispatchEvent(new Event('input',{bubbles:true}));" +
                            "e.dispatchEvent(new Event('change',{bubbles:true}));}" +
                            "else e.textContent=v;})(" + org.json.JSONObject.quote(value) + ")",
                    null
                )
            }
        }
    }

    /** Phone-side text dialog with the soft keyboard; [onDone] gets the entered text. */
    private fun prompt(initial: String, hint: CharSequence?, type: Int, onDone: (String) -> Unit) {
        val d = activity.resources.displayMetrics.density
        val input = EditText(activity).apply {
            setText(initial)
            setSelection(text.length)
            this.hint = hint
            if (type != 0) inputType = type
        }
        val box = FrameLayout(activity).apply {
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
            addView(input)
        }
        val dlg = android.app.AlertDialog.Builder(activity)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
        dlg.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        dlg.show()
        input.requestFocus()
    }

    // Two-finger scroll is a real synthesized drag at the cursor, so lists, grids and
    // web pages all scroll natively (no per-view scrollBy special cases).
    private var dragT0 = 0L
    private var dragY = 0f
    private var dragging = false

    fun scroll(dy: Float) {
        val t = SystemClock.uptimeMillis()
        if (!dragging) {
            dragging = true
            dragT0 = t
            dragY = cy
            val down = MotionEvent.obtain(dragT0, t, MotionEvent.ACTION_DOWN, cx, dragY, 0)
            wrap.inject(down)
            down.recycle()
        }
        dragY += dy
        val mv = MotionEvent.obtain(dragT0, t, MotionEvent.ACTION_MOVE, cx, dragY, 0)
        wrap.inject(mv)
        mv.recycle()
    }

    fun scrollEnd() {
        if (!dragging) return
        dragging = false
        val t = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(dragT0, t, MotionEvent.ACTION_UP, cx, dragY, 0)
        wrap.inject(up)
        up.recycle()
    }

    fun toggleSbs(): Boolean {
        wrap.sbs = !wrap.sbs
        return wrap.sbs
    }

    companion object {
        /** A controller button; activities add their own via [controller]'s extras. */
        fun stripButton(activity: Activity, label: String, onClick: () -> Unit) =
            TextView(activity).apply {
                text = label
                textSize = 15f
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL
                )
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(activity.getColor(R.color.on_surface))
                setBackgroundResource(R.drawable.bg_input)
                setOnClickListener { onClick() }
            }

        /**
         * Phone-side controller, laid out as an instrument panel: a status line
         * (pulsing dot = the link is live), a grid of buttons in uniform cells (at
         * most 4 per row, so nothing is pushed off screen), and a large trackpad
         * plate whose puck jumps under the finger and springs back to center.
         * [extras] are appended to the button grid by the hosting activity.
         *
         * Known limitation: text fields shown on the glasses cannot raise the soft
         * keyboard, because a presentation window is not an IME target; the
         * phone-side editor dialog opened from [click] covers that case.
         */
        fun controller(activity: Activity, out: GlassesOut, extras: List<View> = emptyList()): View {
            val d = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()

            val root = LinearLayout(activity)
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundColor(activity.getColor(R.color.backdrop))
            root.keepScreenOn = true
            root.setPadding(dp(14), dp(12), dp(14), dp(12))

            // ── status line: ● GLASSES CONNECTED ─────────────────────────────
            val status = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), dp(10))
            }
            val dot = View(activity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(activity.getColor(R.color.primary))
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
                    .apply { marginEnd = dp(8) }
            }
            android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f).apply {
                duration = 1100
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
            status.addView(dot)
            // name the display in use: on dual-screen devices the auto-pick can land
            // on the wrong panel, and this is what tells the user it did
            status.addView(TextView(activity).apply {
                // the wrap is not attached to its window yet, so ask the display the
                // presentation was built with — wrap.display would still be null here
                text = out.displayName.uppercase().take(28)
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                letterSpacing = 0.18f
                setTextColor(activity.getColor(R.color.on_surface_variant))
            })
            root.addView(status)

            // ── button grid: Back, SBS and the caller's extras in uniform cells ──
            val sbsBtn = stripButton(activity, "SBS") {}
            fun paintSbs() = sbsBtn.setTextColor(
                activity.getColor(if (out.wrap.sbs) R.color.primary else R.color.on_surface)
            )
            sbsBtn.setOnClickListener {
                val on = out.toggleSbs()
                activity.getSharedPreferences("neoxr", Context.MODE_PRIVATE)
                    .edit().putBoolean("menuSbs", on).apply()
                paintSbs()
            }
            paintSbs()

            // "Screen" appears only when there is another display to move to — on a
            // dual-screen handheld the auto-pick may choose the built-in second panel
            val screenBtn = if (Glasses.candidates(activity).size > 1) {
                stripButton(activity, "Screen") {
                    Glasses.cycle(activity)?.let { activity.recreate() }
                }
            } else null

            val buttons =
                listOf(stripButton(activity, "← Back") { activity.onBackPressed() }, sbsBtn) +
                        extras + listOfNotNull(screenBtn)
            for (row in buttons.chunked(4)) {
                val line = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                for (b in row) {
                    line.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                        marginStart = dp(4); marginEnd = dp(4)
                    })
                }
                root.addView(line, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })
            }

            // ── the trackpad plate ───────────────────────────────────────────
            root.addView(TouchpadView(activity, out), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(4) })

            val padHint = TextView(activity).apply {
                text = "drag — pointer · tap — click · two fingers — scroll"
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
                setTextColor(activity.getColor(R.color.on_surface_variant))
                setPadding(0, dp(10), 0, 0)
            }
            root.addView(padHint)
            return root
        }
    }
}

/**
 * Trackpad plate with a live puck. The puck idles at the plate's center inside a
 * faint crosshair; on touch it jumps under the finger, tracks it 1:1 (while the
 * glasses cursor moves with its own gain) and springs back on release. Gesture
 * semantics: drag moves the cursor, a short still tap clicks, any second finger
 * scrolls.
 */
private class TouchpadView(activity: Activity, private val out: GlassesOut) : View(activity) {

    private val d = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = activity.getColor(R.color.primary)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = activity.getColor(R.color.surface_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 1 * d
    }
    private val stroke = activity.getColor(R.color.surface_stroke)
    private val outline = activity.getColor(R.color.outline)
    private val beamStart = activity.getColor(R.color.beam_start)
    private val beamEnd = activity.getColor(R.color.beam_end)
    private val glow = activity.getColor(R.color.primary)

    private var px = 0f
    private var py = 0f
    private var active = false
    private var scrolling = false
    private var homeAnim: android.animation.ValueAnimator? = null

    init {
        setBackgroundResource(R.drawable.bg_pad)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        px = w / 2f
        py = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // crosshair: a faint guide circle + four ticks, quiet instrument furniture
        hairPaint.color = stroke
        canvas.drawCircle(cx, cy, Math.min(width, height) * 0.30f, hairPaint)
        val t1 = 6 * d
        val t2 = 14 * d
        canvas.drawLine(cx, cy - t2, cx, cy - t1, hairPaint)
        canvas.drawLine(cx, cy + t1, cx, cy + t2, hairPaint)
        canvas.drawLine(cx - t2, cy, cx - t1, cy, hairPaint)
        canvas.drawLine(cx + t1, cy, cx + t2, cy, hairPaint)

        val r = 26 * d
        val alpha = if (active) 255 else 140
        // soft glow only while touched — the "powered" state
        if (active) {
            glowPaint.shader = android.graphics.RadialGradient(
                px, py, r * 2.2f,
                (glow and 0x00FFFFFF) or (0x38 shl 24), glow and 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawCircle(px, py, r * 2.2f, glowPaint)
        }
        ringPaint.color = if (active) outline else stroke
        ringPaint.alpha = alpha
        canvas.drawCircle(px, py, r, ringPaint)
        // the beam arc: the one gradient on this screen, a 100° sweep on the ring
        beamPaint.shader = android.graphics.LinearGradient(
            px - r, py - r, px + r, py + r, beamStart, beamEnd,
            android.graphics.Shader.TileMode.CLAMP
        )
        beamPaint.alpha = alpha
        canvas.drawArc(px - r, py - r, px + r, py + r, -160f, 100f, false, beamPaint)
        corePaint.alpha = alpha
        canvas.drawCircle(px, py, 5 * d, corePaint)
        // two-finger mode: scroll chevrons above and below the puck
        if (scrolling) {
            ringPaint.color = outline
            val ch = 8 * d
            for (s in intArrayOf(-1, 1)) {
                val by = py + s * (r + 14 * d)
                canvas.drawLine(px - ch, by, px, by + s * ch * 0.7f, ringPaint)
                canvas.drawLine(px, by + s * ch * 0.7f, px + ch, by, ringPaint)
            }
        }
    }

    private fun snapHome() {
        homeAnim?.cancel()
        homeAnim = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 240
            interpolator = android.view.animation.DecelerateInterpolator()
            val sx = px
            val sy = py
            addUpdateListener {
                val f = it.animatedValue as Float
                px = width / 2f + (sx - width / 2f) * f
                py = height / 2f + (sy - height / 2f) * f
                invalidate()
            }
            start()
        }
    }

    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var travelled = 0f
    private var multi = false // any second finger during the gesture = scroll, never a click

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                homeAnim?.cancel()
                lastX = ev.x; lastY = ev.y
                px = ev.x; py = ev.y
                downTime = ev.eventTime
                travelled = 0f
                multi = false
                active = true
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                scrolling = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastX
                val dy = ev.y - lastY
                travelled += Math.abs(dx) + Math.abs(dy)
                if (ev.pointerCount >= 2) out.scroll(dy) else out.move(dx * 1.6f, dy * 1.6f)
                lastX = ev.x; lastY = ev.y
                px = ev.x.coerceIn(0f, width.toFloat())
                py = ev.y.coerceIn(0f, height.toFloat())
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                out.scrollEnd()
                scrolling = false
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                out.scrollEnd()
                if (ev.actionMasked == MotionEvent.ACTION_UP && !multi &&
                    ev.eventTime - downTime < 250 && travelled < 12 * d
                ) out.click()
                active = false
                scrolling = false
                snapHome()
            }
        }
        return true
    }
}
