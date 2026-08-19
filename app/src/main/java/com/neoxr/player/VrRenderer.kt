package com.neoxr.player

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the video projected on a flat quad (angle 0) or an equirect dome of any
 * angular width (90 = "wide" web-SBS wedge, 120/180 = partial/half dome, 360 = full
 * sphere), with the eye layout (SBS/TB/mono) as an independent axis. Output is
 * side-by-side stereo: left half of the screen = left eye. The glasses' native
 * "3D SBS" mode merges the halves into a stereo image.
 */
class VrRenderer(private val onSurfaceReady: (SurfaceTexture) -> Unit) :
    GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var yawDeg = 0f
    @Volatile var pitchDeg = 0f

    /** Manual stereo convergence: +N shifts eye images toward each other by N px. */
    @Volatile var eyeShiftPx = 0

    /** Manual horizontal squeeze (1 = full half): plain viewport narrowing, no warping. */
    @Volatile var widthScale = 1f

    /**
     * Manual vertical squeeze (1 = full height), the counterpart of [widthScale].
     * Some material is encoded with the wrong vertical scale and looks squashed or
     * stretched; shrinking the viewport around the half's center corrects it without
     * touching the projection.
     */
    @Volatile var heightScale = 1f

    /**
     * Ambient backdrop behind a flat or wide screen: the frame itself, blown up,
     * blurred and dimmed, so the black void around the picture picks up its colour.
     * Costs no assets — it samples the video texture that is already bound. Domes
     * (180/360) fill the view on their own, so it only applies below 180°.
     */
    @Volatile var ambientLevel = 0f
        set(value) {
            if ((field > 0f) != (value > 0f)) meshDirty = true // the inset changes the flat mesh
            field = value
        }

    private val ambient get() = ambientLevel > 0f

    /** How much of the panel the glow gets: smaller value = wider band of light. */
    @Volatile var ambientInset = AMBIENT_INSET
        set(value) {
            if (field != value) { field = value; meshDirty = true }
        }

    /**
     * Vertical FOV of the virtual camera, degrees — set via PlayerActivity.applyFov.
     * Projection stays rectilinear: it is the only mapping that keeps straight lines
     * straight, which matters on room-scale VR content (walls, furniture). A
     * stereographic projection removes the edge stretching but bends those lines,
     * so edge stretching is instead handled by zooming into a narrower frustum.
     */
    @Volatile var fovDeg = 72f

    /**
     * Draw one full-screen view instead of the stereo pair. Only useful when the
     * video plays on the phone itself: without glasses to merge the halves, a single
     * view is what you actually want to look at (and what makes a readable
     * screenshot).
     */
    @Volatile var monoOutput = false

    /** Some sites publish the SBS halves in R|L order — swap which half goes to which eye. */
    @Volatile var swapEyes = false

    /**
     * Aspect of one eye's image (frame aspect corrected for the stereo layout), or 0
     * while unknown. The flat screen uses it to keep the picture's proportions
     * instead of stretching it to the viewport — which also creates the surround the
     * ambient backdrop fills.
     */
    @Volatile private var videoAspect = 0f

    @Volatile private var screenAngle = 180
    @Volatile private var stereoMode = "sbs"
    @Volatile private var meshDirty = true
    @Volatile private var frameAvailable = false

    /** [angleDeg] 0 = screen-locked flat quad, else dome width; [stereo] "sbs"|"tb"|"off". */
    fun configure(angleDeg: Int, stereo: String) {
        screenAngle = angleDeg
        stereoMode = stereo
        meshDirty = true
    }

    /**
     * Decoder frame size, used to keep a flat screen's proportions.
     *
     * Only meaningful for mono content. Stereo material is anamorphic by convention:
     * a 1920x1080 SBS file holds two 960x1080 halves that are MEANT to be stretched
     * back to full width, so its half's pixel aspect says nothing about how it should
     * be displayed. Letterboxing by that number squashed 3D video into a small square
     * (SBS) or a flattened strip (OU).
     */
    fun setVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val a = if (stereoMode == "off") w.toFloat() / h else 0f
        if (Math.abs(a - videoAspect) > 0.001f) {
            videoAspect = a
            meshDirty = true
        }
    }

    private companion object {
        /**
         * How much the picture shrinks when the glow is on. Nothing can be drawn
         * outside the frame the phone sends to the glasses, so the room for a
         * surround has to come from inside it.
         */
        const val AMBIENT_INSET = 0.86f // default; overridden by ambientInset
    }

    // half-extents of the flat mesh in NDC (1 = full viewport), needed by the
    // backdrop to know where the picture ends
    @Volatile private var flatHalfX = 1f
    @Volatile private var flatHalfY = 1f

    private var program = 0
    private var bgProgram = 0
    private var bgAPos = 0
    private var bgUSt = 0
    private var bgUScale = 0
    private var bgUOffset = 0
    private var bgUDim = 0
    private var bgUHalf = 0
    private var bgQuad: FloatBuffer? = null
    private var aPos = 0
    private var aUV = 0
    private var uMvp = 0
    private var uSt = 0
    private var uScale = 0
    private var uOffset = 0
    private var texId = 0
    private var st: SurfaceTexture? = null
    private var vertexBuf: FloatBuffer? = null
    private var indexBuf: ShortBuffer? = null
    private var indexCount = 0
    private var width = 1
    private var height = 1
    private val stMatrix = FloatArray(16)
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private var uBlack = 0
    private var uBright = 0
    private var uContrast = 0
    private var uGamma = 0

    /**
     * Picture adjustment, applied to the video texture. Defaults are the identity —
     * a viewer who never opens the panel sees exactly what the file contains.
     */
    @Volatile var blackLevel = 0f   // -0.1..0.3, lifts (or crushes) the darkest level
    @Volatile var brightness = 0f   // -0.3..0.3
    @Volatile var contrast = 1f     // 0.7..1.6
    @Volatile var gamma = 1f        // 0.6..1.6, below 1 opens up shadows

    private val mvp = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUV = GLES20.glGetAttribLocation(program, "aUV")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uSt = GLES20.glGetUniformLocation(program, "uSt")
        uScale = GLES20.glGetUniformLocation(program, "uUVScale")
        uOffset = GLES20.glGetUniformLocation(program, "uUVOffset")
        uBlack = GLES20.glGetUniformLocation(program, "uBlack")
        uBright = GLES20.glGetUniformLocation(program, "uBright")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast")
        uGamma = GLES20.glGetUniformLocation(program, "uGamma")

        bgProgram = buildBgProgram()
        bgAPos = GLES20.glGetAttribLocation(bgProgram, "aPos")
        bgUSt = GLES20.glGetUniformLocation(bgProgram, "uSt")
        bgUScale = GLES20.glGetUniformLocation(bgProgram, "uUVScale")
        bgUOffset = GLES20.glGetUniformLocation(bgProgram, "uUVOffset")
        bgUDim = GLES20.glGetUniformLocation(bgProgram, "uDim")
        bgUHalf = GLES20.glGetUniformLocation(bgProgram, "uHalf")
        // full-viewport quad: position + UV, the UV covering the whole eye image
        bgQuad = ByteBuffer.allocateDirect(4 * 5 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f, 1f,
                    1f, -1f, 0f, 1f, 1f,
                    -1f, 1f, 0f, 0f, 0f,
                    1f, 1f, 0f, 1f, 0f
                )
            ).apply { position(0) }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        Matrix.setIdentityM(stMatrix, 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        st = SurfaceTexture(texId).also {
            it.setOnFrameAvailableListener(this)
            onSurfaceReady(it)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable = true
    }

    override fun onDrawFrame(gl: GL10?) {
        if (meshDirty) {
            buildMesh()
            meshDirty = false
        }
        if (frameAvailable) {
            frameAvailable = false
            st?.updateTexImage()
            st?.getTransformMatrix(stMatrix)
        }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

        val vb = vertexBuf ?: return
        vb.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vb)
        GLES20.glEnableVertexAttribArray(aPos)
        vb.position(3)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 20, vb)
        GLES20.glEnableVertexAttribArray(aUV)

        // The glasses in 3D-SBS mode split the frame at the EXACT center and stretch
        // each half to fill the whole 16:9 eye panel — the same geometry
        // SbsFrameLayout uses for menus, and renderer and UI must agree on it. Each
        // eye renders centered in its half; insetting the viewports inward separates
        // the eyes' image centers and breaks stereo fusion. widthScale narrows the
        // viewport around the half's center — a plain rectangular squeeze that leaves
        // black side bands, no geometric warping.
        val eyeW = if (monoOutput) width else width / 2
        val vw = (eyeW * widthScale).toInt().coerceAtLeast(1)
        val vh = (height * heightScale).toInt().coerceAtLeast(1)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        for (eye in 0..(if (monoOutput) 0 else 1)) {
            val x0 = eye * eyeW
            // scissor pins each eye to its half; the viewport shift moves the image
            // inside it for manual convergence tuning
            GLES20.glScissor(x0, 0, eyeW, height)

            // Backdrop covers the eye's WHOLE half, before the viewport is narrowed
            // for the picture: the bands that widthScale/heightScale leave — and the
            // surround a letterboxed screen sits in — are exactly what it must fill.
            // Drawing it inside the narrowed viewport would light only the area the
            // video already covers.
            if (ambient && screenAngle < 180) {
                GLES20.glViewport(x0, 0, eyeW, height)
                val e0 = if (swapEyes) 1 - eye else eye
                val bgUv = eyeUV(e0)
                GLES20.glUseProgram(bgProgram)
                bgQuad?.let { q ->
                    q.position(0)
                    GLES20.glVertexAttribPointer(bgAPos, 3, GLES20.GL_FLOAT, false, 20, q)
                    GLES20.glEnableVertexAttribArray(bgAPos)
                    GLES20.glUniformMatrix4fv(bgUSt, 1, false, stMatrix, 0)
                    GLES20.glUniform2f(bgUScale, bgUv[0], bgUv[1])
                    GLES20.glUniform2f(bgUOffset, bgUv[2], bgUv[3])
                    // where the picture sits inside this half, in the quad's own NDC:
                    // the narrowed viewport times the letterboxed mesh
                    GLES20.glUniform2f(
                        bgUHalf,
                        (vw.toFloat() / eyeW) * flatHalfX,
                        (vh.toFloat() / height) * flatHalfY
                    )
                    GLES20.glUniform1f(bgUDim, ambientLevel)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
                // restore the main program and its attribute pointers
                GLES20.glUseProgram(program)
                vb.position(0)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vb)
                GLES20.glEnableVertexAttribArray(aPos)
                vb.position(3)
                GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 20, vb)
                GLES20.glEnableVertexAttribArray(aUV)
            }

            // now the picture's own viewport, narrowed by the manual squeezes and
            // shifted for convergence
            GLES20.glViewport(
                x0 + (eyeW - vw) / 2 + if (eye == 0) eyeShiftPx else -eyeShiftPx,
                (height - vh) / 2, vw, vh
            )

            if (screenAngle == 0) {
                Matrix.setIdentityM(mvp, 0) // flat: screen-locked, no look-around
            } else {
                // aspect is the GLASSES PANEL's (16:9), not the viewport's: the panel
                // shows the stretched half, so using the viewport aspect here renders
                // every dome too wide (~1.6x on a 20:9 phone). Flat mode is immune —
                // there the frame fractions cancel the stretch exactly.
                // ponytail: 16:9 hardcoded for Xreal One; a setting if glasses differ.
                Matrix.perspectiveM(proj, 0, fovDeg, 16f / 9f, 0.1f, 100f)
                Matrix.setIdentityM(view, 0)
                Matrix.rotateM(view, 0, -pitchDeg, 1f, 0f, 0f)
                Matrix.rotateM(view, 0, -yawDeg, 0f, 1f, 0f)
                Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            }
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
            val e = if (swapEyes) 1 - eye else eye
            val uv = eyeUV(e)
            GLES20.glUniform2f(uScale, uv[0], uv[1])
            GLES20.glUniform2f(uOffset, uv[2], uv[3])
            GLES20.glUniform1f(uBlack, blackLevel)
            GLES20.glUniform1f(uBright, brightness)
            GLES20.glUniform1f(uContrast, contrast)
            GLES20.glUniform1f(uGamma, gamma)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuf)
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /** Returns [scaleU, scaleV, offsetU, offsetV] mapping mesh UV to this eye's part of the frame. */
    private fun eyeUV(eye: Int): FloatArray = when (stereoMode) {
        "tb" -> if (eye == 0) floatArrayOf(1f, 0.5f, 0f, 0.5f) else floatArrayOf(1f, 0.5f, 0f, 0f)
        "off" -> floatArrayOf(1f, 1f, 0f, 0f) // mono: full frame to both eyes
        // "sbs" and anything unrecognized: horizontal halves — the dominant VR layout
        else -> if (eye == 0) floatArrayOf(0.5f, 1f, 0f, 0f) else floatArrayOf(0.5f, 1f, 0.5f, 0f)
    }

    private fun buildMesh() {
        if (screenAngle == 0) {
            // Screen-locked flat quad in NDC. Sized to the frame's own aspect against
            // the 16:9 eye panel, so a 4:3 or scope picture keeps its proportions
            // instead of being stretched to fill; the leftover bands are what the
            // ambient backdrop lights up. Falls back to fullscreen until the decoder
            // reports a size.
            var sx = 1f
            var sy = 1f
            if (videoAspect > 0f) {
                val panel = 16f / 9f
                if (videoAspect > panel) sy = panel / videoAspect else sx = videoAspect / panel
            }
            if (ambient) { sx *= ambientInset; sy *= ambientInset }
            flatHalfX = sx
            flatHalfY = sy
            putMesh(
                floatArrayOf(
                    -sx, -sy, 0f, 0f, 0f,
                    sx, -sy, 0f, 1f, 0f,
                    -sx, sy, 0f, 0f, 1f,
                    sx, sy, 0f, 1f, 1f
                ),
                shortArrayOf(0, 1, 2, 2, 1, 3)
            )
            return
        }
        flatHalfX = 1f
        flatHalfY = 1f
        val rows = 48
        val cols = 96
        // Equirect sphere wedge of screenAngle degrees, full latitude; 360 is the
        // full sphere. 90 ("wide") packs non-anamorphic web-SBS 2x denser with no
        // edge stretching. The wedge converges to triangle tips at the poles — the
        // best-looking of the alternatives (a flat billboard stretches top/bottom
        // sideways near the FOV edges; a pole-capped wedge or cylinder bends lines).
        // ponytail: fisheye/mkx200/rf52 rendered as equirect dome — good enough until someone needs true fisheye
        val lonHalf = Math.toRadians(screenAngle / 2.0).toFloat()
        val verts = FloatArray((rows + 1) * (cols + 1) * 5)
        var i = 0
        for (r in 0..rows) {
            val lat = (-Math.PI / 2 + Math.PI * r / rows).toFloat()
            for (c in 0..cols) {
                val lon = -lonHalf + 2f * lonHalf * c / cols
                verts[i++] = cos(lat) * sin(lon)
                verts[i++] = sin(lat)
                verts[i++] = -cos(lat) * cos(lon)
                verts[i++] = c.toFloat() / cols
                verts[i++] = r.toFloat() / rows
            }
        }
        putMesh(verts, gridIndices(rows, cols))
    }

    private fun gridIndices(rows: Int, cols: Int): ShortArray {
        val idx = ShortArray(rows * cols * 6)
        var k = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i0 = (r * (cols + 1) + c).toShort()
                val i1 = (i0 + 1).toShort()
                val i2 = (i0 + cols + 1).toShort()
                val i3 = (i2 + 1).toShort()
                idx[k++] = i0; idx[k++] = i2; idx[k++] = i1
                idx[k++] = i1; idx[k++] = i2; idx[k++] = i3
            }
        }
        return idx
    }

    private fun putMesh(verts: FloatArray, idx: ShortArray) {
        vertexBuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(verts).apply { position(0) }
        indexBuf = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
            .asShortBuffer().put(idx).apply { position(0) }
        indexCount = idx.size
    }

    /**
     * Backdrop pass: a full-viewport quad showing an over-scaled, box-blurred and
     * dimmed copy of the frame. Nine taps is enough to lose all detail at this scale
     * while staying cheap; the alternative (render to a small FBO and upscale) buys
     * little on one quad.
     */
    private fun buildBgProgram(): Int {
        val vs = """
            attribute vec3 aPos;
            varying vec2 vNdc;
            void main() {
                gl_Position = vec4(aPos, 1.0);
                vNdc = aPos.xy;
            }
        """
        // Bias lighting, as on a TV with an LED strip: the border is split into a
        // FIXED number of zones, each averaging a piece of the picture's edge, and
        // the glow interpolates between neighbouring zones — then fades into that
        // edge's overall average as it travels outward.
        //
        // Two traps, both hit on the way here:
        //  - taps that slide with the pixel stay individually visible and fan out of
        //    the corners as streaks. Quantising into zones removes that.
        //  - varying the ZONE COUNT with distance makes floor() jump as you move
        //    outward, drawing bands parallel to the edge. The count must be constant;
        //    dissolution comes from blending towards the average instead.
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform mat4 uSt;
            uniform vec2 uUVScale;
            uniform vec2 uUVOffset;
            uniform vec2 uHalf;
            uniform float uDim;
            varying vec2 vNdc;

            const float ZONES = 12.0;

            vec3 texAt(vec2 ndc) {
                vec2 t = (clamp(ndc, -uHalf, uHalf) / uHalf) * 0.5 + 0.5;
                vec2 uv = t * uUVScale + uUVOffset;
                return texture2D(uTex, (uSt * vec4(uv, 0.0, 1.0)).xy).rgb;
            }

            // Films are often letterboxed inside the frame itself (IMAX rips change
            // aspect between scenes), and the frame's own edge is then a black bar
            // with nothing to light. Step inward until the picture actually starts —
            // the same letterbox detection physical Ambilight software does. Bounded
            // and small: this walks the border, never into the picture.
            vec3 tap(vec2 ndc) {
                vec2 inward = -sign(ndc) * uHalf;
                vec3 c = texAt(ndc);
                for (int i = 1; i <= 8; i++) {
                    if (c.r + c.g + c.b > 0.09) break;
                    c = texAt(ndc + inward * (float(i) * 0.018));
                }
                return c;
            }

            // p is 0..1 along the edge; averages a couple of points inside that zone
            vec3 zoneAt(vec2 fixedPart, vec2 along, float alongHalf, float p) {
                float w = 0.35 / ZONES;
                vec3 c = tap(fixedPart + along * ((p - w) * 2.0 - 1.0) * alongHalf);
                c += tap(fixedPart + along * ((p + w) * 2.0 - 1.0) * alongHalf);
                return c * 0.5;
            }

            // colour this edge contributes: zone-interpolated near it, averaged far out
            vec3 edgeGlow(vec2 fixedPart, vec2 along, float alongHalf,
                          float alongCoord, float t) {
                float p = clamp(alongCoord / alongHalf * 0.5 + 0.5, 0.0, 1.0);
                float zf = p * ZONES - 0.5;
                float i0 = floor(zf);
                vec3 near = mix(
                    zoneAt(fixedPart, along, alongHalf, (i0 + 0.5) / ZONES),
                    zoneAt(fixedPart, along, alongHalf, (i0 + 1.5) / ZONES),
                    smoothstep(0.0, 1.0, zf - i0)
                );
                vec3 far = (
                    zoneAt(fixedPart, along, alongHalf, 0.15) +
                    zoneAt(fixedPart, along, alongHalf, 0.5) +
                    zoneAt(fixedPart, along, alongHalf, 0.85)
                ) / 3.0;
                return mix(near, far, smoothstep(0.0, 1.0, t));
            }

            void main() {
                vec2 over = max(abs(vNdc) - uHalf, vec2(0.0));
                vec2 room = max(vec2(1.0) - uHalf, vec2(0.001));
                vec2 tv = over / room;
                float t = clamp(max(tv.x, tv.y), 0.0, 1.0);

                vec2 inset = uHalf * 0.995; // just inside the border

                vec3 h = edgeGlow(
                    vec2(0.0, sign(vNdc.y) * inset.y), vec2(1.0, 0.0),
                    uHalf.x, clamp(vNdc.x, -uHalf.x, uHalf.x), t
                );
                vec3 v = edgeGlow(
                    vec2(sign(vNdc.x) * inset.x, 0.0), vec2(0.0, 1.0),
                    uHalf.y, clamp(vNdc.y, -uHalf.y, uHalf.y), t
                );

                // corners: both edges in proportion to how far out each axis is
                float wx = tv.x / max(tv.x + tv.y, 0.0001);
                vec3 c = mix(h, v, wx);

                gl_FragColor = vec4(c * uDim * (1.0 - t), 1.0);
            }
        """
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun buildProgram(): Int {
        val vs = """
            attribute vec3 aPos;
            attribute vec2 aUV;
            uniform mat4 uMvp;
            uniform mat4 uSt;
            uniform vec2 uUVScale;
            uniform vec2 uUVOffset;
            varying vec2 vUV;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vec2 uv = aUV * uUVScale + uUVOffset;
                vUV = (uSt * vec4(uv, 0.0, 1.0)).xy;
            }
        """
        // Picture controls. Glasses panels differ wildly in how they render shadows —
        // OLED ones crush them, and there is no display menu to fix it — so the
        // adjustment belongs here, on the way to the texture.
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform float uBlack;      // lift or crush the darkest level
            uniform float uBright;
            uniform float uContrast;
            uniform float uGamma;
            varying vec2 vUV;
            void main() {
                vec3 c = texture2D(uTex, vUV).rgb;
                c = uBlack + c * (1.0 - uBlack);          // black level
                c = (c - 0.5) * uContrast + 0.5 + uBright;
                c = pow(clamp(c, 0.0, 1.0), vec3(uGamma));
                gl_FragColor = vec4(c, 1.0);
            }
        """
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader failed: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }
}
