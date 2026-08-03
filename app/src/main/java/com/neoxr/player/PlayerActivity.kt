package com.neoxr.player

import android.app.Activity
import android.app.Presentation
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlin.concurrent.thread

/**
 * Controls: drag = look around, single tap = show/hide the control bar (playback
 * toggles only via its ▶/⏸ button), double tap = toggle phone-gyro look,
 * long press = recenter the view.
 *
 * Output: with the glasses plugged in over USB-C (DisplayPort alt mode they are a
 * real external display), the video renders DIRECTLY on them via a [Presentation] —
 * full native frame, none of the mirror-path rescaling — and the phone screen
 * becomes a plain remote: same controls, regular size, no SBS doubling, gyro and
 * swipe still steer the view. Without glasses everything stays on the phone as
 * before. Plug/unplug mid-play recreates the activity, keeping the position.
 */
class PlayerActivity : Activity(), SensorEventListener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private var player: ExoPlayer? = null
    private var videoSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var streamUrl: String? = null
    private var presentation: Presentation? = null
    private var glassesDisplay: Display? = null
    private var resumePos = 0L // carried across output switches (plug/unplug recreate)

    // named sbsOverlay, not "overlay": inside any View.apply{} scope a bare `overlay`
    // silently resolves to View.getOverlay() instead of this field
    private lateinit var sbsOverlay: SbsFrameLayout
    private lateinit var controlsBar: View
    private lateinit var btnPlay: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btn3d: TextView
    private lateinit var timePos: TextView
    private lateinit var timeDur: TextView
    private lateinit var seek: SeekBar
    private lateinit var qualityList: LinearLayout
    // side columns with the format buttons (inside the SBS overlay, see buildSidePanels)
    private lateinit var leftPanel: LinearLayout
    private lateinit var rightPanel: LinearLayout
    private lateinit var btnClose: TextView
    private val screenBtns = HashMap<Int, TextView>()
    private val layoutBtns = HashMap<String, TextView>()
    private var sources: List<Pair<Int, String>> = emptyList()
    // fallback quality menu for raw intercepted streams (no feed JSON): the variant
    // heights ExoPlayer parsed out of the HLS/DASH manifest
    private var trackHeights: List<Int> = emptyList()
    private var qualityCap = 0 // 0 = adaptive
    private var screenAngle = 180 // projection: 0 = flat screen, else dome width in degrees
    private var stereo = "sbs" // eye layout: "sbs" | "tb" | "off"
    private var formatFromFeed = false // feed metadata set the format — never re-guess
    private var formatAuto = true // no manual override yet — may refine on first frame size
    private var eyeShiftDp = 0
    private var widthPct = 100
    private var zoomPct = 100 // camera magnification, in tan space
    private var seekDragging = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable {
        controlsBar.visibility = View.GONE
        qualityList.visibility = View.GONE
        leftPanel.visibility = View.GONE
        rightPanel.visibility = View.GONE
        btnClose.visibility = View.GONE
        findViewById<View>(R.id.eyePanel)?.visibility = View.GONE
    }
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private var gyroEnabled = true
    private var dragYaw = 0f
    private var dragPitch = 0f
    private var sensorYaw = 0f
    private var sensorPitch = 0f
    private var zeroYaw = 0f
    private var zeroPitch = 0f
    private var yawZeroed = false // first sensor sample zeroes YAW (and only yaw)
    // glasses-IMU head tracking (Imu.kt): when on, it feeds sensorYaw/sensorPitch
    // instead of the phone sensor — recenter/drag/gyro-toggle logic stays identical
    private var headTracker: HeadTracker? = null
    private var headMode = false
    private var btnHead: TextView? = null
    private var levelView: LevelView? = null // remote-mode gyro instrument
    // live value readouts inside the remote steppers ([−  W 100  +] rows)
    private var wValue: TextView? = null
    private var zValue: TextView? = null
    private val stepperRows = mutableListOf<LinearLayout>() // resized on rotation
    // No full auto-centering on the first sensor sample: recenter() also zeroes PITCH,
    // which would capture whatever pose the phone happens to be in at launch. Laying
    // the phone down flat afterwards then reads as -90° ("looking at the floor") and
    // the video goes black. Only a deliberate long-press recenter, made once the phone
    // is where it will stay, has a meaningful pitch reference. See onSensorChanged for
    // the yaw-only zeroing that IS safe.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fullscreenOverCutout()
        resumePos = savedInstanceState?.getLong("pos") ?: 0L
        // diagnostic pass: dump every display the system exposes
        for (d in (getSystemService(DISPLAY_SERVICE) as DisplayManager).displays) {
            android.util.Log.i(
                "NeoXR",
                "display id=${d.displayId} name=${d.name} flags=0x${d.flags.toString(16)} " +
                        "mode=${d.mode} state=${d.state} valid=${d.isValid}"
            )
        }
        glassesDisplay = findGlasses()
        android.util.Log.i("NeoXR", "glassesDisplay=${glassesDisplay?.displayId}")
        // remote mode: the phone is a controller, not a video surface — let it follow
        // the grip (portrait is the comfortable hold); the manifest landscape lock
        // stays for phone playback, where SBS geometry depends on it. configChanges
        // handles the turn, applyRemoteDims resizes.
        if (glassesDisplay != null) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
        }
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, handler)

        // when the glasses display exists, the GL view lives on IT (via Presentation),
        // so it must be built with the presentation's display context
        val ext = glassesDisplay
        if (ext != null) presentation = Presentation(this, ext)
        renderer = VrRenderer { st ->
            android.util.Log.i("NeoXR", "GL surface ready (onSurfaceReady)")
            runOnUiThread {
                surfaceTexture = st
                maybeStart()
            }
        }
        glView = GLSurfaceView(presentation?.context ?: this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            keepScreenOn = true
        }
        // Native-resolution output on the glasses: ask for their densest mode (in SBS
        // a 3840x1080 link gives a true 1920 per eye instead of 960) and pin the GL
        // buffer to physical pixels — sidesteps the scaled "logical" resolution some
        // OEM display stacks run external displays at.
        val bestMode = ext?.supportedModes?.maxWithOrNull(
            compareBy({ it.physicalWidth.toLong() * it.physicalHeight }, { it.refreshRate })
        )
        if (bestMode != null) {
            pickedModeId = bestMode.modeId
            presentation?.window?.let { w ->
                w.attributes = w.attributes.apply { preferredDisplayModeId = bestMode.modeId }
            }
            glView.holder.setFixedSize(bestMode.physicalWidth, bestMode.physicalHeight)
        }
        // controls overlay: drawn SBS on the phone-only path (so it merges in the
        // mirroring glasses); a plain single copy in remote mode — the phone screen
        // is just a controller there and nobody looks at it through lenses
        sbsOverlay = SbsFrameLayout(this).apply { sbs = ext == null }
        layoutInflater.inflate(R.layout.player_controls, sbsOverlay, true)
        val root = FrameLayout(this)
        root.keepScreenOn = true
        if (ext == null) {
            root.addView(glView)
        } else {
            // remote mode: the phone shows an instrument, not a text dump — a
            // crosshair level whose bubbles ride the live yaw/pitch (the gyro made
            // visible), with a mono readout and one line of gesture hints
            root.setBackgroundColor(getColor(R.color.backdrop))
            levelView = LevelView(this)
            root.addView(levelView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        root.addView(sbsOverlay)
        buildSidePanels()
        setContentView(root)
        presentation?.let {
            try {
                it.setContentView(glView)
                it.show()
                android.util.Log.i("NeoXR", "presentation shown on display ${ext?.displayId}")
            } catch (e: Exception) {
                android.util.Log.e("NeoXR", "presentation FAILED: $e")
                sbsOverlay.flash("Glasses output failed: ${e.message}")
            }
        }
        controlsBar = sbsOverlay.findViewById(R.id.controlsBar)
        btnPlay = sbsOverlay.findViewById(R.id.btnPlay)
        btnQuality = sbsOverlay.findViewById(R.id.btnQuality)
        timePos = sbsOverlay.findViewById(R.id.timePos)
        timeDur = sbsOverlay.findViewById(R.id.timeDur)
        seek = sbsOverlay.findViewById(R.id.seek)
        qualityList = sbsOverlay.findViewById(R.id.qualityList)

        btnPlay.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            showControls()
        }
        btnQuality.setOnClickListener {
            qualityList.visibility =
                if (qualityList.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            showControls()
        }
        // zoom/width/convergence are deliberately NOT persisted: they are per-content
        // corrections, so every video starts from the defaults
        applyEyeShift()
        applyFov()
        val eyePanel = sbsOverlay.findViewById<View>(R.id.eyePanel)
        btn3d = sbsOverlay.findViewById(R.id.btn3d)
        applyRemoteDims()
        btn3d.setOnClickListener {
            eyePanel.visibility =
                if (eyePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            showControls()
        }
        sbsOverlay.findViewById<TextView>(R.id.btnEyeMinus).setOnClickListener { adjustEyeShift(-2) }
        sbsOverlay.findViewById<TextView>(R.id.btnEyePlus).setOnClickListener { adjustEyeShift(2) }
        sbsOverlay.findViewById<TextView>(R.id.btnEyeSwap).setOnClickListener { btn ->
            // per-video toggle, deliberately not persisted: only some sources ship R|L
            renderer.swapEyes = !renderer.swapEyes
            (btn as TextView).setTextColor(
                getColor(if (renderer.swapEyes) R.color.primary else R.color.on_surface)
            )
            showControls()
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) timePos.text = Deo.formatDuration(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                seekDragging = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seekDragging = false
                player?.let {
                    // surface the reason instead of a silently dead slider
                    if (it.isCurrentMediaItemSeekable) it.seekTo(sb.progress * 1000L)
                    else sbsOverlay.flash("This video does not support seeking")
                }
                showControls()
            }
        })

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                dragYaw += dx * 0.12f
                dragPitch -= dy * 0.12f
                applyView()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // remote mode: the phone screen IS the controls — nothing to
                // unclutter, so a tap must not hide them
                if (glassesDisplay != null) return true
                // phone playback: controls visibility only — play/pause via ►/❙❙
                if (controlsBar.visibility == View.VISIBLE) {
                    handler.removeCallbacks(hideControls)
                    hideControls.run()
                } else {
                    showControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                gyroEnabled = !gyroEnabled
                recenter()
                sbsOverlay.flash(if (gyroEnabled) "Gyro ON" else "Gyro OFF")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                recenter()
            }
        })
        // look-around gestures: on the GL view itself when it is on the phone, on the
        // whole phone screen (acting as a touchpad) when the video is on the glasses
        (if (glassesDisplay == null) glView else root).setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            true
        }
        if (glassesDisplay != null) showControls() // a remote keeps its buttons visible

        // local video handed over by the system: a file manager's "Open with"
        // (ACTION_VIEW) or the share sheet (ACTION_SEND). Format is guessed from the
        // real file name (SAF content:// URIs are opaque — resolve DISPLAY_NAME),
        // then refined from the frame aspect like any intercepted stream.
        val extUri: android.net.Uri? = when (intent.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            else -> null
        }
        if (extUri != null) {
            val (angle, layout) = Deo.guessFormat(displayName(extUri) ?: extUri.toString())
            setFormat(angle, layout)
            streamUrl = extUri.toString()
            buildQualityList()
            maybeStart()
            return
        }

        // direct stream handed over from the in-app browser — no DeoVR JSON involved.
        // Guess the format from URL tokens now; refined once from the real frame size
        // when the decoder reports it (onVideoSizeChanged), unless overridden manually.
        intent.getStringExtra("stream")?.let { url ->
            val (angle, layout) = Deo.guessFormat(url)
            setFormat(angle, layout)
            streamUrl = url
            buildQualityList()
            maybeStart()
            return
        }

        thread {
            try {
                val videoUrl = intent.getStringExtra("videoUrl")
                // ponytail: some sites intermittently serve detail JSON without sources
                // (rate limiting?) and a repeat request succeeds — retry up to 3 fetches
                var info: VideoInfo? = null
                var lastErr: Exception? = null
                for (attempt in 0..2) {
                    try {
                        val body = if (attempt == 0) {
                            intent.getStringExtra("json") ?: Deo.httpGet(videoUrl!!)
                        } else {
                            Deo.httpGet(videoUrl!!)
                        }
                        info = Deo.parseVideo(body)
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        if (videoUrl == null || e.message?.contains("Premium") == true) break
                        Thread.sleep(600)
                    }
                }
                val vi = info ?: throw lastErr!!
                runOnUiThread {
                    formatFromFeed = true
                    setFormat(vi.angleDeg, vi.stereo)
                    streamUrl = vi.url
                    sources = vi.sources
                    buildQualityList()
                    maybeStart()
                }
            } catch (e: Exception) {
                // shown in-layout so it is readable in the glasses; Back exits
                runOnUiThread { sbsOverlay.flash("Error: ${e.message}") }
            }
        }
    }

    private fun findGlasses(): Display? = Glasses.find(this)

    /** Real file name behind a SAF content:// URI (they are opaque otherwise). */
    private fun displayName(uri: android.net.Uri): String? = try {
        contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    // Plugging/unplugging mid-play moves the whole pipeline (GL context, surface,
    // player) between screens — a clean recreate is the reliable way; the playback
    // position rides along in the saved state.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = maybeSwitchOutput()
        override fun onDisplayRemoved(displayId: Int) = maybeSwitchOutput()
        override fun onDisplayChanged(displayId: Int) {
            // the glasses' 3D/SBS menu toggle can change the advertised mode list on
            // the fly: re-pick the densest mode if a better one appeared. Whether a
            // full-width SBS mode is offered at all depends on the host's DP stack.
            if (displayId != glassesDisplay?.displayId) return
            val best = glassesDisplay?.supportedModes?.maxWithOrNull(
                compareBy({ it.physicalWidth.toLong() * it.physicalHeight }, { it.refreshRate })
            ) ?: return
            if (pickedModeId != 0 && best.modeId != pickedModeId) recreate()
        }
    }
    private var pickedModeId = 0 // display mode this session was built for

    private fun maybeSwitchOutput() {
        android.util.Log.i(
            "NeoXR",
            "display change: now=${findGlasses()?.displayId} was=${glassesDisplay?.displayId}"
        )
        if ((findGlasses() == null) != (glassesDisplay == null)) recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("pos", player?.currentPosition ?: resumePos)
    }

    private fun maybeStart() {
        android.util.Log.i(
            "NeoXR",
            "maybeStart: surface=${surfaceTexture != null} url=${streamUrl != null} player=${player != null}"
        )
        val st = surfaceTexture ?: return
        val url = streamUrl ?: return
        if (player != null) return
        videoSurface = Surface(st)
        player = ExoPlayer.Builder(this)
            // proper media-app citizenship: request audio focus (pause when another
            // app plays, duck for notifications) and stop on headphone unplug
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
            // local hi-res files often have sparse keyframes (5-10 s GOPs): an exact
            // seek decodes the whole GOP and looks frozen for seconds — snap to the
            // nearest sync frame instead (streams are unaffected, segments align)
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            setVideoSurface(videoSurface)
            setMediaItem(MediaItem.fromUri(url), resumePos)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("NeoXR", "player error: $error", error)
                    sbsOverlay.flash("Player error: ${error.message}")
                }

                override fun onTracksChanged(tracks: Tracks) {
                    // feed sources win: they are separate URLs, not manifest variants
                    if (sources.isNotEmpty()) return
                    trackHeights = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                        .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }
                        .filter { it > 0 }.distinct().sortedDescending()
                    buildQualityList()
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    // metadata-less streams only: refine the URL guess once with the
                    // real frame aspect, then leave the format alone (manual wins)
                    if (formatFromFeed || !formatAuto || size.width == 0) return
                    formatAuto = false
                    val (angle, layout) =
                        Deo.guessFormat(streamUrl ?: return, size.width, size.height)
                    if (angle != screenAngle || layout != stereo) setFormat(angle, layout)
                }
            })
            prepare()
            playWhenReady = true
        }
    }

    /** Applies projection + eye layout to the renderer and highlights the side buttons. */
    private fun setFormat(angle: Int, layout: String) {
        screenAngle = angle
        stereo = layout
        renderer.configure(angle, layout)
        for ((a, b) in screenBtns) mark(b, a == angle)
        for ((l, b) in layoutBtns) mark(b, l == layout)
    }

    /** Active format button wears the brand beam on its edge, same as the list rows. */
    private fun mark(b: TextView, on: Boolean) {
        b.setBackgroundResource(if (on) R.drawable.bg_row_active else R.drawable.bg_input)
        b.setTextColor(getColor(if (on) R.color.on_surface else R.color.on_surface_variant))
    }

    /**
     * Format buttons: two columns at the layout edges INSIDE the SBS overlay, so the
     * glasses show them to both eyes (drawn twice, like the control bar); anything
     * placed outside the overlay is invisible in stereo. One direct button per mode,
     * no cycling. Raised above the bottom bar so playback controls and the quality
     * list stay reachable.
     */
    private fun buildSidePanels() {
        val d = resources.displayMetrics.density
        // remote mode has the whole phone screen to itself — roomier cells, column
        // captions, medium type. The SBS-overlay path keeps the tight 36dp budget
        // (7 rows must clear the bar on a ~360dp-tall landscape screen).
        val remote = glassesDisplay != null
        fun column(gravity: Int, caption: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // bottom-anchored, both columns aligned just above the bar. NOT
            // CENTER_VERTICAL: FrameLayout shifts a centered child up by the FULL
            // bottomMargin (not half of it), which threw the columns off the top.
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity or Gravity.BOTTOM
            ).apply {
                marginStart = ((if (remote) 14 else 8) * d).toInt()
                marginEnd = ((if (remote) 14 else 8) * d).toInt()
                bottomMargin = (68 * d).toInt() // bar is ~60dp deep incl. its margin
            }
            if (remote) {
                addView(TextView(this@PlayerActivity).apply {
                    text = caption
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    letterSpacing = 0.22f
                    // setGravity: the column() parameter `gravity` shadows the property
                    setGravity(Gravity.CENTER)
                    setTextColor(getColor(R.color.on_surface_variant))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * d).toInt() })
            }
            visibility = View.GONE
            sbsOverlay.addView(this)
        }
        fun button(parent: LinearLayout, label: String, onClick: () -> Unit) =
            TextView(this).apply {
                text = label
                textSize = if (remote) 14f else 12f
                if (remote) typeface = android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL
                )
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.on_surface))
                setBackgroundResource(R.drawable.bg_input)
                layoutParams = LinearLayout.LayoutParams(
                    ((if (remote) 100 else 84) * d).toInt(),
                    ((if (remote) 44 else 36) * d).toInt()
                ).apply { topMargin = ((if (remote) 4 else 3) * d).toInt() }
                setOnClickListener { onClick() }
                parent.addView(this)
            }

        leftPanel = column(Gravity.START, "SCREEN")
        for ((angle, label) in listOf(0 to "Flat", 90 to "Wide", 180 to "180°", 360 to "360°")) {
            screenBtns[angle] = button(leftPanel, label) {
                formatAuto = false
                setFormat(angle, stereo)
                showControls()
            }
        }
        // head tracking needs the glasses anyway, so the 5th row only exists in
        // remote mode — the phone-SBS overlay keeps its 4-row budget
        if (glassesDisplay != null) {
            btnHead = button(leftPanel, "Head") { toggleHead() }
        }
        rightPanel = column(Gravity.END, "LAYOUT")
        for ((layout, label) in listOf("off" to "2D", "sbs" to "SBS", "tb" to "OU")) {
            layoutBtns[layout] = button(rightPanel, label) {
                formatAuto = false
                setFormat(screenAngle, layout)
                showControls()
            }
        }
        if (remote) {
            // steppers instead of four stacked buttons: [−  W 100  +] with a live
            // mono value. Cuts the right column to 5 rows — the same count as the
            // left one, so the SCREEN/LAYOUT captions align and the column fits the
            // ~353dp landscape height (7 rows of 48dp overflow it).
            fun stepper(prefix: String, step: (Int) -> Unit): TextView {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundResource(R.drawable.bg_input)
                }
                fun side(label: String, delta: Int) = TextView(this).apply {
                    text = label
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.on_surface_variant))
                    setOnClickListener { step(delta) }
                    row.addView(this, LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                    ))
                }
                side("−", -1)
                val value = TextView(this).apply {
                    text = "$prefix 100"
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.on_surface))
                    row.addView(this, LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f
                    ))
                }
                side("+", +1)
                rightPanel.addView(row, LinearLayout.LayoutParams(
                    (124 * d).toInt(), (44 * d).toInt()
                ).apply { topMargin = (4 * d).toInt() })
                stepperRows.add(row)
                return value
            }
            rightPanel.gravity = Gravity.CENTER_HORIZONTAL
            wValue = stepper("W") { adjustWidth(it * 5) }
            zValue = stepper("Z") { adjustZoom(it * 10) }
        } else {
            button(rightPanel, "W−") { adjustWidth(-5) }
            button(rightPanel, "W+") { adjustWidth(+5) }
            button(rightPanel, "Z−") { adjustZoom(-10) }
            button(rightPanel, "Z+") { adjustZoom(+10) }
        }

        // close: top center, clear of both side columns; inside the overlay so both
        // eyes get a copy; shows and hides with the rest of the controls
        btnClose = TextView(this).apply {
            text = "✕"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.on_surface_variant))
            setBackgroundResource(R.drawable.bg_input)
            layoutParams = FrameLayout.LayoutParams(
                (72 * d).toInt(), (36 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = (8 * d).toInt() }
            visibility = View.GONE
            setOnClickListener { finish() }
            sbsOverlay.addView(this)
        }
    }

    /**
     * Remote-mode sizes for both orientations, applied at build and on every
     * rotation (configChanges — no recreate). Portrait narrows the side columns
     * (76dp buttons / 112dp steppers vs 100/124 in landscape) so the central
     * look-around pad stays big; the 3D panel and quality list move ABOVE the
     * columns in portrait — side-by-side there is no width left for them.
     */
    private fun applyRemoteDims() {
        if (glassesDisplay == null) return
        val d = resources.displayMetrics.density
        val portrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        val btnW = ((if (portrait) 76 else 100) * d).toInt()
        val stepW = ((if (portrait) 112 else 124) * d).toInt()
        val txt = if (portrait) 13f else 14f
        for (b in screenBtns.values + layoutBtns.values + listOfNotNull(btnHead)) {
            b.textSize = txt
            b.layoutParams = (b.layoutParams as LinearLayout.LayoutParams)
                .apply { width = btnW }
        }
        for (r in stepperRows) {
            r.layoutParams = (r.layoutParams as LinearLayout.LayoutParams)
                .apply { width = stepW }
        }

        // two-storey bar in portrait: the seek row keeps the full width, while
        // play (center), 3D (left) and quality (right) move up to their own row
        val rowTop = sbsOverlay.findViewById<FrameLayout>(R.id.rowTop)
        val rowMain = sbsOverlay.findViewById<LinearLayout>(R.id.rowMain)
        fun frameLp(gravity: Int) = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity
        )
        if (portrait && btnPlay.parent === rowMain) {
            rowMain.removeView(btnPlay)
            rowMain.removeView(btn3d)
            rowMain.removeView(btnQuality)
            rowTop.addView(btn3d, frameLp(Gravity.START or Gravity.CENTER_VERTICAL))
            rowTop.addView(btnPlay, frameLp(Gravity.CENTER))
            rowTop.addView(btnQuality, frameLp(Gravity.END or Gravity.CENTER_VERTICAL))
            rowTop.visibility = View.VISIBLE
        } else if (!portrait && btnPlay.parent === rowTop) {
            rowTop.removeAllViews()
            rowTop.visibility = View.GONE
            fun rowLp() = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowMain.addView(btnPlay, 0, rowLp())
            rowMain.addView(btn3d, rowLp())
            rowMain.addView(btnQuality, rowLp())
        }

        // ✕: top-right in portrait (the front camera sits top-center there);
        // top-center in landscape, where the side columns own the corners
        (btnClose.layoutParams as FrameLayout.LayoutParams).let {
            it.gravity = Gravity.TOP or
                    (if (portrait) Gravity.END else Gravity.CENTER_HORIZONTAL)
            it.marginEnd = ((if (portrait) 12 else 0) * d).toInt()
        }
        btnClose.layoutParams = btnClose.layoutParams

        // the deeper portrait bar pushes the columns (and the panels above them) up
        val colBottom = if (portrait) 122 else 68
        for (col in listOf(leftPanel, rightPanel)) {
            (col.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                (colBottom * d).toInt()
            col.layoutParams = col.layoutParams
        }
        // columns: caption ~26dp + 5 rows of 48dp above the bar
        val colTop = colBottom + 26 + 5 * 48 + 8
        val eyePanel = sbsOverlay.findViewById<View>(R.id.eyePanel)
        (eyePanel.layoutParams as FrameLayout.LayoutParams).let {
            it.marginStart = ((if (portrait) 24 else 146) * d).toInt()
            it.marginEnd = ((if (portrait) 24 else 146) * d).toInt()
            it.bottomMargin = ((if (portrait) colTop else 76) * d).toInt()
        }
        eyePanel.layoutParams = eyePanel.layoutParams
        (qualityList.layoutParams as FrameLayout.LayoutParams).let {
            it.marginEnd = ((if (portrait) 14 else 146) * d).toInt()
            it.bottomMargin = ((if (portrait) colTop else 76) * d).toInt()
        }
        qualityList.layoutParams = qualityList.layoutParams
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyRemoteDims()
        fullscreenOverCutout() // insets shift on rotation
    }

    /**
     * Camera zoom in tan space — a true optical magnification, not an image scale:
     * fov = 2·atan(tan(36°)/zoom). VR content is mastered for ~110°-FOV headsets, so
     * on the glasses' ~57° panel it reads "too far" at 100% and needs roughly 130%.
     * Projection stays rectilinear at any zoom (only rectilinear keeps straight lines
     * straight, which matters for room-scale content); zooming in also shrinks the
     * frustum out of the range where rectilinear edge stretch is visible.
     */
    private fun applyFov() {
        val fov = 2 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(36.0)) / (zoomPct / 100.0)))
        renderer.fovDeg = fov.toFloat().coerceIn(5f, 150f)
    }

    /** Camera zoom; resets per video like the other tweaks. */
    private fun adjustZoom(delta: Int) {
        zoomPct = (zoomPct + delta).coerceIn(50, 300)
        applyFov()
        // remote steppers show the value inline; the SBS overlay has no readout — flash
        zValue?.also { it.text = "Z $zoomPct" } ?: sbsOverlay.flash("Zoom $zoomPct%")
        showControls()
    }

    /** Manual horizontal squeeze for content the glasses show too wide. */
    private fun adjustWidth(delta: Int) {
        widthPct = (widthPct + delta).coerceIn(50, 120)
        renderer.widthScale = widthPct / 100f
        wValue?.also { it.text = "W $widthPct" } ?: sbsOverlay.flash("Width $widthPct%")
        showControls()
    }

    /** Manual stereo convergence: shifts each eye's image toward (+) or apart (−). */
    private fun adjustEyeShift(d: Int) {
        eyeShiftDp = (eyeShiftDp + d).coerceIn(-30, 30)
        applyEyeShift()
        showControls()
    }

    private fun applyEyeShift() {
        renderer.eyeShiftPx = (eyeShiftDp * resources.displayMetrics.density).toInt()
        findViewById<TextView>(R.id.txtEye)?.text =
            if (eyeShiftDp == 0) "3D" else "3D%+d".format(eyeShiftDp)
    }

    private fun showControls() {
        controlsBar.visibility = View.VISIBLE
        leftPanel.visibility = View.VISIBLE
        rightPanel.visibility = View.VISIBLE
        btnClose.visibility = View.VISIBLE
        handler.removeCallbacks(hideControls)
        // auto-hide only when the controls sit over the video itself; the remote
        // screen is all theirs — hiding them there would just add taps
        if (glassesDisplay == null) handler.postDelayed(hideControls, 3000)
        updateProgress()
    }

    private fun updateProgress() {
        val p = player ?: return
        if (controlsBar.visibility != View.VISIBLE) return
        val durSec = (p.duration.coerceAtLeast(0) / 1000).toInt()
        val posSec = (p.currentPosition.coerceAtLeast(0) / 1000).toInt()
        seek.max = durSec
        if (!seekDragging) {
            seek.progress = posSec
            timePos.text = Deo.formatDuration(posSec)
        }
        timeDur.text = Deo.formatDuration(durSec)
        // U+2759 bars / U+25BA pointer: glyphs with NO emoji variant at all. U+23F8
        // has one, and some vendor fonts draw the colored emoji form (ignoring
        // textColor) even with the FE0E text-presentation selector appended.
        btnPlay.text = if (p.isPlaying) "❙❙" else "►"
    }

    /**
     * Quality menu from whichever source exists: the feed's separate source URLs, or
     * — for raw intercepted streams — the manifest variants ExoPlayer reported. The
     * button hides when there is genuinely nothing to pick (single-file mp4): a dead
     * "…" that opens an empty list is worse than no button.
     */
    private fun buildQualityList() {
        qualityList.removeAllViews()
        val padH = (16 * resources.displayMetrics.density).toInt()
        val padV = (8 * resources.displayMetrics.density).toInt()
        fun entry(label: String, active: Boolean, onClick: () -> Unit) {
            qualityList.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(padH, padV, padH, padV)
                setTextColor(getColor(if (active) R.color.primary else R.color.on_surface))
                setOnClickListener { onClick() }
            })
        }
        if (sources.isNotEmpty()) {
            for ((res, url) in sources) entry("${res}p", url == streamUrl) { switchQuality(url) }
            btnQuality.text =
                sources.firstOrNull { it.second == streamUrl }?.let { "${it.first}p" } ?: "…"
        } else {
            entry("Auto", qualityCap == 0) { capQuality(0) }
            for (h in trackHeights) entry("${h}p", qualityCap == h) { capQuality(h) }
            btnQuality.text = if (qualityCap == 0) "Auto" else "${qualityCap}p"
        }
        btnQuality.visibility =
            if (sources.size > 1 || trackHeights.size > 1) View.VISIBLE else View.GONE
    }

    /** Pins an HLS/DASH stream to a variant height (0 = let ABR choose). */
    private fun capQuality(h: Int) {
        qualityCap = h
        qualityList.visibility = View.GONE
        player?.let {
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, if (h == 0) Int.MAX_VALUE else h)
                .build()
        }
        buildQualityList()
        showControls()
    }

    private fun switchQuality(url: String) {
        qualityList.visibility = View.GONE
        if (url != streamUrl) {
            streamUrl = url
            player?.run {
                setMediaItem(MediaItem.fromUri(url), currentPosition)
                prepare()
                play()
            }
            buildQualityList()
        }
        showControls()
    }

    /**
     * Look source toggle: glasses IMU instead of the phone sensor. Requires, in the
     * glasses' own menu, Stabilizer OFF and Follow mode — their native anchoring
     * composites its own warp on top of ours. Long-press recenter works as usual.
     */
    private fun toggleHead() {
        if (headMode) {
            headMode = false
            headTracker?.stop()
            headTracker = null
            btnHead?.let { mark(it, false) }
            sbsOverlay.flash("Head tracking OFF")
        } else {
            headMode = true
            btnHead?.let { mark(it, true) }
            headTracker = HeadTracker(this) { s -> sbsOverlay.flash(s) }.apply {
                onOrientation = { yaw, pitch ->
                    // reader thread; renderer fields are volatile floats — safe
                    sensorYaw = yaw
                    sensorPitch = pitch
                    applyView()
                }
                // tracker died (no network / stream lost): drop back to the phone
                // gyro automatically, otherwise Head mode leaves the view frozen
                onStopped = {
                    if (headMode) {
                        headMode = false
                        headTracker?.stop()
                        headTracker = null
                        btnHead?.let { mark(it, false) }
                    }
                }
                start()
            }
        }
        showControls()
    }

    private fun applyView() {
        renderer.yawDeg = dragYaw + if (gyroEnabled) wrap(sensorYaw - zeroYaw) else 0f
        renderer.pitchDeg =
            (dragPitch + if (gyroEnabled) sensorPitch - zeroPitch else 0f).coerceIn(-89f, 89f)
        levelView?.postInvalidateOnAnimation()
    }

    /**
     * Remote-mode centerpiece: a crosshair level. Two hairline rails cross at the
     * screen center; an azure bubble rides each — yaw on the horizontal, pitch on
     * the vertical — so the "magic window" orientation is visible on the remote
     * itself. Mono readout below, gesture hints above: the instrument look shared
     * with the menu controller's trackpad.
     */
    private inner class LevelView(ctx: android.content.Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val hair = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = getColor(R.color.surface_stroke)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2 * d
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val notch = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = getColor(R.color.outline)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2 * d
        }
        private val bubble = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = getColor(R.color.primary)
        }
        private val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val mono = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = getColor(R.color.on_surface_variant)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11 * d
            textAlign = android.graphics.Paint.Align.CENTER
        }
        private val primary = getColor(R.color.primary)

        override fun onDraw(canvas: android.graphics.Canvas) {
            val cx = width / 2f
            // landscape: raised above center so the readout clears the bottom bar.
            // Portrait: the columns fill the lower half — center the cross in the
            // free zone between the top hints and the column tops.
            val portrait = height > width
            val cy = height * (if (portrait) 0.27f else 0.44f)
            val half = Math.min(width, height) * 0.24f

            // rails with end + center notches
            canvas.drawLine(cx - half, cy, cx + half, cy, hair)
            canvas.drawLine(cx, cy - half, cx, cy + half, hair)
            val n = 5 * d
            canvas.drawLine(cx - half, cy - n, cx - half, cy + n, notch)
            canvas.drawLine(cx + half, cy - n, cx + half, cy + n, notch)
            canvas.drawLine(cx - n, cy - half, cx + n, cy - half, notch)
            canvas.drawLine(cx - n, cy + half, cx + n, cy + half, notch)
            canvas.drawCircle(cx, cy, 3 * d, notch)

            // bubbles: yaw wraps ±90 into the rail, pitch clamps ±89
            val yaw = wrap(renderer.yawDeg)
            val pitch = renderer.pitchDeg
            val bx = cx + (yaw / 90f).coerceIn(-1f, 1f) * half
            val by = cy - (pitch / 90f).coerceIn(-1f, 1f) * half
            for ((x, y) in listOf(bx to cy, cx to by)) {
                glow.shader = android.graphics.RadialGradient(
                    x, y, 16 * d,
                    (primary and 0x00FFFFFF) or (0x50 shl 24), primary and 0x00FFFFFF,
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawCircle(x, y, 16 * d, glow)
                canvas.drawCircle(x, y, 5 * d, bubble)
            }

            mono.textSize = 11 * d
            canvas.drawText(
                "yaw %+04.0f°   pitch %+04.0f°".format(yaw, pitch),
                cx, cy + half + 26 * d, mono
            )
            // clears the ✕ button above it (top-center, 36dp tall plus its margin)
            mono.textSize = 10 * d
            canvas.drawText(
                "swipe — look · double tap — gyro · hold — recenter",
                cx, 60 * d, mono
            )
        }
    }

    private fun recenter() {
        zeroYaw = sensorYaw
        zeroPitch = sensorPitch
        dragYaw = 0f
        dragPitch = 0f
        applyView()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (headMode) return // the glasses' IMU owns sensorYaw/sensorPitch
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // "magic window": look where the back of the phone points (-Z device axis in
        // world coords). No Euler angles / remap — no gimbal lock in normal holds.
        // R rows = world east/north/up, columns = device x/y/z; back = -(column z).
        val east = -r[2]
        val north = -r[5]
        val up = -r[8]
        // negated so that turning the phone right pans the view right
        sensorYaw = -Math.toDegrees(Math.atan2(east.toDouble(), north.toDouble())).toFloat()
        sensorPitch = Math.toDegrees(Math.asin(up.coerceIn(-1f, 1f).toDouble())).toFloat()
        // Auto-center YAW ONLY on the first sample: the sensor's yaw reference is
        // arbitrary, so without this every video starts looking sideways. Pitch is
        // NOT zeroed — that would capture whatever pose the phone is in at the first
        // sample, and it needs no zero anyway: it is gravity-referenced and already
        // absolute.
        if (!yawZeroed) {
            yawZeroed = true
            zeroYaw = sensorYaw
        }
        applyView()
    }

    /** Wraps an angle difference to [-180, 180] so recentered yaw never jumps across ±180. */
    private fun wrap(a: Float) = ((a % 360f) + 540f) % 360f - 180f

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        glView.onResume()
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        handler.post(progressTick)
        fullscreenOverCutout()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(hideControls)
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        player?.pause()
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        headTracker?.stop()
        presentation?.dismiss()
        player?.release()
        videoSurface?.release()
        surfaceTexture?.release()
    }
}
