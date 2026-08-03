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
     * Vertical FOV of the virtual camera, degrees — set via PlayerActivity.applyFov.
     * Projection stays rectilinear: it is the only mapping that keeps straight lines
     * straight, which matters on room-scale VR content (walls, furniture). A
     * stereographic projection removes the edge stretching but bends those lines,
     * so edge stretching is instead handled by zooming into a narrower frustum.
     */
    @Volatile var fovDeg = 72f

    /** Some sites publish the SBS halves in R|L order — swap which half goes to which eye. */
    @Volatile var swapEyes = false

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

    private var program = 0
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
    private val mvp = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUV = GLES20.glGetAttribLocation(program, "aUV")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uSt = GLES20.glGetUniformLocation(program, "uSt")
        uScale = GLES20.glGetUniformLocation(program, "uUVScale")
        uOffset = GLES20.glGetUniformLocation(program, "uUVOffset")

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
        val eyeW = width / 2
        val vw = (eyeW * widthScale).toInt().coerceAtLeast(1)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        for (eye in 0..1) {
            val x0 = eye * eyeW
            // scissor pins each eye to its half; the viewport shift moves the image
            // inside it for manual convergence tuning
            GLES20.glScissor(x0, 0, eyeW, height)
            GLES20.glViewport(
                x0 + (eyeW - vw) / 2 + if (eye == 0) eyeShiftPx else -eyeShiftPx,
                0, vw, height
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
            // fullscreen NDC quad for the screen-locked flat mode (see onDrawFrame)
            putMesh(
                floatArrayOf(
                    -1f, -1f, 0f, 0f, 0f,
                    1f, -1f, 0f, 1f, 0f,
                    -1f, 1f, 0f, 0f, 1f,
                    1f, 1f, 0f, 1f, 1f
                ),
                shortArrayOf(0, 1, 2, 2, 1, 3)
            )
            return
        }
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
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vUV;
            void main() { gl_FragColor = texture2D(uTex, vUV); }
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
