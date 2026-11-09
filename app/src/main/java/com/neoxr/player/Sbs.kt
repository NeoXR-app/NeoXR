package com.neoxr.player

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Fullscreen over the entire physical panel, including the camera cutout area.
 * Mandatory for every screen: the glasses' SBS merge splits the *physical* screen
 * in half, so any horizontal inset (cutout letterbox, nav bar) shifts our split
 * off the physical center and the eyes see misaligned halves.
 */
@Suppress("DEPRECATION")
fun Activity.fullscreenOverCutout() {
    if (Build.VERSION.SDK_INT >= 28) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
}

/**
 * Draws its content twice, squeezed into the left/right screen halves, so regular
 * Android UI stays readable while the glasses are in native 3D-SBS mode. Touches on
 * either half are mapped back to full-layout coordinates.
 *
 * ponytail: dialogs, spinner popups, toasts and the IME are separate windows and
 * stay non-SBS; render in-layout panels if that ever matters.
 */
class SbsFrameLayout(context: Context) : FrameLayout(context) {

    // The glasses split the frame at the EXACT center and stretch each half back to
    // the full eye panel — no cropping. So both copies must span a full half, and
    // neither may be inset: insetting displaces the eyes' image centers by far more
    // than stereo fusion tolerates. Same law applies to VrRenderer's viewports.
    var sbs = false
        set(value) {
            field = value
            invalidate()
        }

    /** When set, a long press anywhere (except editable text) toggles SBS. */
    var onLongPressToggle: (() -> Unit)? = null

    /** Zones (layout coords) where the long-press toggle must not fire, e.g. a touchpad. */
    var longPressExclude: ((x: Float, y: Float) -> Boolean)? = null

    // In-layout toast replacement: system Toasts are separate windows, so the
    // glasses' SBS merge never shows them — anything that must be readable in
    // stereo goes through flash() instead.
    private var flashView: TextView? = null
    private val hideFlash = Runnable { flashView?.visibility = GONE }

    fun flash(msg: String, ms: Long = 2500) {
        val tv = flashView ?: TextView(context).apply {
            textSize = 16f
            setTextColor(context.getColor(R.color.on_surface))
            setBackgroundResource(R.drawable.bg_input)
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply { bottomMargin = (120 * resources.displayMetrics.density).toInt() }
            flashView = this
            addView(this)
        }
        tv.text = msg
        tv.visibility = VISIBLE
        tv.bringToFront()
        tv.removeCallbacks(hideFlash)
        tv.postDelayed(hideFlash, ms)
    }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val toggle = onLongPressToggle ?: return
            if (isEditTextAt(this@SbsFrameLayout, mapX(e.x), e.y)) return // keep long-press paste
            if (longPressExclude?.invoke(mapX(e.x), e.y) == true) return
            cancelChildren() // swallow the press so the item under the finger doesn't also fire
            toggle()
        }
    })

    override fun dispatchDraw(canvas: Canvas) {
        if (!sbs) {
            super.dispatchDraw(canvas)
            return
        }
        for (eye in 0..1) {
            canvas.save()
            canvas.translate(eye * width / 2f, 0f)
            canvas.scale(0.5f, 1f)
            super.dispatchDraw(canvas)
            canvas.restore()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (onLongPressToggle != null) detector.onTouchEvent(ev)
        if (!sbs || width == 0) return super.dispatchTouchEvent(ev)
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(mapX(ev.x), ev.y)
        val handled = super.dispatchTouchEvent(copy)
        copy.recycle()
        return handled
    }

    private fun mapX(x: Float): Float =
        if (sbs && width > 0) (x % (width / 2f)) * 2f else x

    /** Dispatches a synthesized event in layout coords, bypassing the SBS touch remap. */
    fun inject(ev: MotionEvent): Boolean = super.dispatchTouchEvent(ev)

    private fun cancelChildren() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun isEditTextAt(v: View, x: Float, y: Float): Boolean {
        if (v === this) {
            for (i in 0 until childCount) if (isEditTextAt(getChildAt(i), x, y)) return true
            return false
        }
        if (x < v.left || x > v.right || y < v.top || y > v.bottom) return false
        if (v is EditText) return true
        if (v !is ViewGroup) return false
        val lx = x - v.left + v.scrollX
        val ly = y - v.top + v.scrollY
        for (i in 0 until v.childCount) if (isEditTextAt(v.getChildAt(i), lx, ly)) return true
        return false
    }

    companion object {
        /**
         * Sets [layoutRes] as the activity content wrapped in an [SbsFrameLayout].
         * Without glasses it goes to the phone screen (long press toggles menu SBS).
         * With glasses plugged in (USB-C external display) the content goes to THEM
         * at native resolution and the phone shows only a touchpad controller.
         * Callers must findViewById through the returned wrap, not the activity.
         * [controllerExtras] are appended to the phone controller strip in glasses
         * mode (ignored otherwise).
         */
        fun attach(
            activity: Activity, layoutRes: Int, controllerExtras: List<View> = emptyList()
        ): SbsFrameLayout {
            activity.fullscreenOverCutout()
            val prefs = activity.getSharedPreferences("neoxr", Context.MODE_PRIVATE)
            val wrap = SbsFrameLayout(activity)
            activity.layoutInflater.inflate(layoutRes, wrap, true)
            wrap.sbs = prefs.getBoolean("menuSbs", false)
            // phone-only gesture: on the glasses the wrap gets injected events only,
            // which bypass the long-press detector
            wrap.onLongPressToggle = {
                wrap.sbs = !wrap.sbs
                prefs.edit().putBoolean("menuSbs", wrap.sbs).apply()
            }
            // phone first; moves to the glasses if they take it. No glasses, or the
            // display refused the presentation: stay on the phone rather than crash
            activity.setContentView(wrap)
            if (!toGlasses(activity, wrap, controllerExtras)) {
                watchForGlasses(activity, wrap, controllerExtras)
            }
            return wrap
        }

        /** Moves [wrap] from the phone onto the glasses; false = it stays on the phone. */
        private fun toGlasses(activity: Activity, wrap: SbsFrameLayout, extras: List<View>): Boolean {
            val glasses = Glasses.find(activity) ?: return false
            (wrap.parent as? ViewGroup)?.removeView(wrap)
            val out = GlassesOut(activity, glasses, wrap)
            out.failure?.let {
                activity.setContentView(wrap)
                // said on the phone, where the content now is: the one clue to why,
                // held long enough to read (or photograph) the whole exception
                wrap.flash("Glasses refused this screen — $it", 12000)
                return false
            }
            activity.setContentView(GlassesOut.controller(activity, out, extras))
            return true
        }

        /**
         * Moves the content to the glasses when they get plugged in, or when a glasses
         * display that refused it changes (a link that blanked and came back), in place:
         * a recreate would refetch the feed on every display event.
         */
        private fun watchForGlasses(activity: Activity, wrap: SbsFrameLayout, extras: List<View>) {
            val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = retry()
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    // the phone's own panel changes often (refresh, rotation)
                    if (displayId != android.view.Display.DEFAULT_DISPLAY) retry()
                }

                fun retry() {
                    // a screen in the background must not grab the glasses from the
                    // one in front (e.g. the player opened on top of it)
                    if (activity.isDestroyed ||
                        activity.window.decorView.windowVisibility != View.VISIBLE
                    ) return
                    if (toGlasses(activity, wrap, extras)) {
                        dm.unregisterDisplayListener(this)
                    }
                }
            }
            dm.registerDisplayListener(listener, null)
            activity.window.decorView.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) =
                        dm.unregisterDisplayListener(listener)
                })
        }
    }
}
