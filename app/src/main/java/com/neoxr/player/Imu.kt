package com.neoxr.player

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/*
 * XREAL One / One Pro head tracking over the glasses' network IMU service.
 *
 * The One series exposes no HID IMU — its HID interfaces carry the MCU and buttons
 * and never stream motion data. The X1 chip instead brings up a USB CDC-NCM virtual
 * ethernet link (USB interface class 2.13), and the IMU is a plain TCP service on
 * the glasses: connect to 169.254.2.1:52998 and read. There is no handshake, init
 * sequence or enable command; frames start arriving immediately at roughly 1000 Hz.
 * The stream carries raw gyro and accelerometer samples only — no fused quaternion
 * and no magnetometer — so orientation fusion is done on this side (see [Madgwick]).
 * Protocol details match the XRLinuxDriver, xreal_one_driver and X-Stuff/opentrack
 * implementations.
 *
 * Glasses-side prerequisites: recent firmware, Stabilizer OFF and Follow mode (not
 * Anchor). With Anchor or Stabilizer on, the X1's own 3DoF image warp is applied on
 * top of this tracking and motion is applied twice.
 */

/**
 * One 134-byte IMU frame. Frames are self-delimited by a 6-byte magic at offset 0,
 * so a reader resyncs by scanning for it. Pure JVM, so the parser is unit-testable.
 */
object ImuFrame {
    const val SIZE = 134
    val MAGIC = byteArrayOf(0x28, 0x36, 0x00, 0x00, 0x00, 0x80.toByte())

    // gyro+accel frame marker at offset 78; other frame types share the port
    private val TYPE_GYRO_ACCEL =
        byteArrayOf(0x00, 0x40, 0x1f, 0x00, 0x00, 0x40)

    fun magicAt(b: ByteArray, off: Int): Boolean {
        if (off + SIZE > b.size) return false
        for (i in MAGIC.indices) if (b[off + i] != MAGIC[i]) return false
        return true
    }

    /**
     * Parses gyro (rad/s) + accel (m/s²) out of a frame known to start at [off]:
     * six little-endian floats at offset 34, ordered gx gy gz ax ay az. Returns
     * null for other frame types and for values outside sensor range, which is how
     * a false magic match is rejected.
     */
    fun parse(b: ByteArray, off: Int): FloatArray? {
        for (i in TYPE_GYRO_ACCEL.indices) {
            if (b[off + 78 + i] != TYPE_GYRO_ACCEL[i]) return null
        }
        val bb = ByteBuffer.wrap(b, off + 34, 24).order(ByteOrder.LITTLE_ENDIAN)
        val v = FloatArray(6) { bb.float } // gx gy gz ax ay az
        for (f in v) if (f.isNaN() || f.isInfinite()) return null
        if (abs(v[0]) > 35f || abs(v[1]) > 35f || abs(v[2]) > 35f) return null // ~2000°/s
        if (abs(v[3]) > 100f || abs(v[4]) > 100f || abs(v[5]) > 100f) return null
        return v
    }
}

/**
 * Madgwick AHRS, 6-axis (no magnetometer), as needed by a raw gyro+accel stream.
 * Gyro in rad/s; accel in any unit (normalized internally, and its correction is
 * skipped when the magnitude falls outside the plausible-gravity band, i.e. during
 * linear acceleration). Pure JVM, unit-testable.
 */
class Madgwick(private val beta: Float = 0.08f) {
    var q0 = 1f; var q1 = 0f; var q2 = 0f; var q3 = 0f

    fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, dt: Float) {
        var qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
        var qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
        var qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

        val norm = sqrt(ax * ax + ay * ay + az * az)
        if (norm in 5f..15f) { // plausible gravity → apply the accel correction
            val a1 = ax / norm; val a2 = ay / norm; val a3 = az / norm
            val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
            val _4q0 = 4f * q0; val _4q1 = 4f * q1; val _4q2 = 4f * q2
            val _8q1 = 8f * q1; val _8q2 = 8f * q2
            val q0q0 = q0 * q0; val q1q1 = q1 * q1; val q2q2 = q2 * q2; val q3q3 = q3 * q3
            var s0 = _4q0 * q2q2 + _2q2 * a1 + _4q0 * q1q1 - _2q1 * a2
            var s1 = _4q1 * q3q3 - _2q3 * a1 + 4f * q0q0 * q1 - _2q0 * a2 -
                    _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * a3
            var s2 = 4f * q0q0 * q2 + _2q0 * a1 + _4q2 * q3q3 - _2q3 * a2 -
                    _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * a3
            var s3 = 4f * q1q1 * q3 - _2q1 * a1 + 4f * q2q2 * q3 - _2q2 * a2
            val sn = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sn > 0f) {
                s0 /= sn; s1 /= sn; s2 /= sn; s3 /= sn
                qDot1 -= beta * s0; qDot2 -= beta * s1; qDot3 -= beta * s2; qDot4 -= beta * s3
            }
        }

        q0 += qDot1 * dt; q1 += qDot2 * dt; q2 += qDot3 * dt; q3 += qDot4 * dt
        val qn = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        q0 /= qn; q1 /= qn; q2 /= qn; q3 /= qn
    }

    val yawDeg: Float
        get() = Math.toDegrees(
            atan2(2.0 * (q1 * q2 + q0 * q3), (q0 * q0 + q1 * q1 - q2 * q2 - q3 * q3).toDouble())
        ).toFloat()
    val pitchDeg: Float
        get() = Math.toDegrees(
            asin((2.0 * (q0 * q2 - q1 * q3)).coerceIn(-1.0, 1.0))
        ).toFloat()
    val rollDeg: Float
        get() = Math.toDegrees(
            atan2(2.0 * (q0 * q1 + q2 * q3), (q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3).toDouble())
        ).toFloat()
}

/**
 * Connects to the glasses' IMU service and turns the raw stream into look angles.
 * [onStatus] gets connection-state messages (UI thread); [onOrientation] gets
 * yaw/pitch degrees, throttled to ~60 Hz, on the reader thread — the renderer's
 * volatile floats are safe to write from there.
 */
class HeadTracker(private val context: Context, private val onStatus: (String) -> Unit) {

    companion object {
        const val HOST = "169.254.2.1"
        const val PORT = 52998
        // One Pro (PID 0x0435/0x0436) mounts its IMU 35° off the optical axis, so a
        // level head reads as pitched; One / One S have no such offset. Flip the
        // constant's sign if the horizon tilts the wrong way on other hardware.
        private val PITCH_OFF_PIDS = setOf(0x0435, 0x0436)
        private const val PITCH_MOUNT_DEG = 35f
        // Signs that map fusion output into renderer axes — the empirical knob of
        // this file, set by live test rather than derivation. Note that after the
        // axis remap below a head nod shows up in the fusion's ROLL axis, which is
        // why pitch is taken from rollDeg.
        private const val YAW_SIGN = 1f
        private const val PITCH_SIGN = -1f
    }

    var onOrientation: ((yawDeg: Float, pitchDeg: Float) -> Unit)? = null

    /** Fires (main thread) when the tracker dies on its own — connect failure or a
     *  dropped stream. NOT fired on a deliberate [stop], so the caller can use it to
     *  auto-disable head mode and fall back to the phone gyro. */
    var onStopped: (() -> Unit)? = null

    @Volatile var yawDeg = 0f; private set
    @Volatile var pitchDeg = 0f; private set

    @Volatile private var running = false
    private var socket: Socket? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val fusion = Madgwick()
    private val bias = FloatArray(3)

    private val pitchMount: Float =
        if ((context.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values
                .any { it.vendorId == 0x3318 && it.productId in PITCH_OFF_PIDS }
        ) PITCH_MOUNT_DEG else 0f

    private fun status(s: String) {
        android.os.Handler(context.mainLooper).post { onStatus(s) }
    }

    fun start() {
        if (running) return
        running = true
        status("Head: connecting to glasses…")

        // the NCM link registers as an ethernet network; grab it so the socket pins
        // to that interface even if the phone has wifi/mobile up
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val gotNet = CountDownLatch(1)
        var net: Network? = null // countDown() after the write makes it safe to read post-await
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                net = network
                gotNet.countDown()
            }
        }
        netCallback = cb
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb
        )

        thread(name = "xreal-imu") {
            try {
                gotNet.await(3, TimeUnit.SECONDS)
                val target = InetSocketAddress(HOST, PORT)
                // Three connect paths, tried in order of robustness:
                //  1. a socket explicitly bound to a local NCM address — the only
                //     path that can escape a VPN's catch-all route, since binding
                //     picks the interface before routing does;
                //  2. the ethernet Network's socket factory — clean, but a
                //     non-bypassable VPN makes Network.bindSocket fail with EPERM
                //     while it is up;
                //  3. a plain socket — works only when nothing captures the
                //     169.254/16 link-local subnet, otherwise it routes into the tun.
                // The glasses can bring up more than one ethernet interface (one per
                // CDC function), so every local address is a candidate and the one
                // on the glasses' own subnet (169.254.2.x) is tried first — the
                // other one accepts the bind and then times out.
                val tries = mutableListOf<Pair<String, () -> Socket>>()
                for (la in ncmAddresses()) {
                    tries += "bind ${la.hostAddress}" to
                            { Socket().apply { bind(InetSocketAddress(la, 0)) } }
                }
                net?.let { n -> tries += "ethernet-net" to { n.socketFactory.createSocket() } }
                tries += "direct" to { Socket() }
                var s: Socket? = null
                for ((_, make) in tries) {
                    if (!running) return@thread
                    try {
                        s = make().apply { connect(target, 3000) }
                        break
                    } catch (_: Exception) {}
                }
                val sock = s ?: throw Exception("unreachable")
                socket = sock
                sock.tcpNoDelay = true
                status("Head tracking ON")
                readLoop(sock.getInputStream())
            } catch (e: Exception) {
                // by far the most common cause is a VPN that captures the glasses'
                // link-local subnet, so name the fix instead of the exception
                if (running) status(
                    "Head: can't reach the glasses.\n" +
                            "Turn the VPN off, or exclude 169.254.0.0/16 /\n" +
                            "allow app bypass in its settings (OpenVPN profile)."
                )
            } finally {
                stopNet()
            }
            // reader gone while nobody called stop() → died on its own
            if (running) {
                running = false
                val cb = onStopped
                if (cb != null) android.os.Handler(context.mainLooper).post { cb() }
            }
        }
    }

    // IPv4 candidates for an explicitly bound socket: link-local addresses
    // (169.254/16 — the glasses hand these out) and addresses on eth-/usb-/ncm-named
    // interfaces, the glasses' own subnet (169.254.2.x) sorted first.
    // Empty = the phone never brought the interface up.
    private fun ncmAddresses(): List<java.net.Inet4Address> = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { i ->
                i.inetAddresses.asSequence()
                    .filterIsInstance<java.net.Inet4Address>()
                    .map { i.name to it }
            }
            .filter { (n, a) ->
                a.isLinkLocalAddress ||
                        n.startsWith("eth") || n.startsWith("usb") || n.startsWith("ncm")
            }
            .sortedByDescending { (_, a) ->
                if (a.hostAddress?.startsWith("169.254.2.") == true) 1 else 0
            }
            .map { it.second }.distinct().toList()
    } catch (_: Exception) { emptyList() }


    private fun readLoop(input: InputStream) {
        val buf = ByteArray(ImuFrame.SIZE * 16)
        var have = 0
        val startNs = System.nanoTime()
        var lastNs = 0L
        var lastEmitNs = 0L
        while (running) {
            val r = input.read(buf, have, buf.size - have)
            if (r < 0) break
            have += r
            var off = 0
            while (have - off >= ImuFrame.SIZE) {
                if (!ImuFrame.magicAt(buf, off)) { off++; continue }
                val v = ImuFrame.parse(buf, off)
                off += ImuFrame.SIZE
                if (v == null) continue
                val now = System.nanoTime()
                val dt = if (lastNs == 0L) 0.001f
                else ((now - lastNs) / 1e9f).coerceIn(0f, 0.02f)
                lastNs = now

                // runtime gyro-bias: adapt only while nearly still (< ~2°/s)
                if (abs(v[0]) < 0.035f && abs(v[1]) < 0.035f && abs(v[2]) < 0.035f) {
                    for (i in 0..2) bias[i] += (v[i] - bias[i]) * 0.02f
                }
                // axis remap per XRLinuxDriver: (x,y,z) → (-x,-z,-y), same for accel
                fusion.update(
                    -(v[0] - bias[0]), -(v[2] - bias[2]), -(v[1] - bias[1]),
                    -v[3], -v[5], -v[4], dt
                )
                yawDeg = YAW_SIGN * fusion.yawDeg
                pitchDeg = PITCH_SIGN * fusion.rollDeg - pitchMount
                // first ~0.5 s the accel correction is still leveling the horizon —
                // don't steer the view with a converging pose
                if (now - lastEmitNs > 16_000_000 && now - startNs > 500_000_000) {
                    lastEmitNs = now
                    onOrientation?.invoke(yawDeg, pitchDeg)
                }
            }
            System.arraycopy(buf, off, buf, 0, have - off)
            have -= off
        }
        if (running) status("Head: connection to glasses lost")
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        stopNet()
    }

    private fun stopNet() {
        netCallback?.let {
            try {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        netCallback = null
    }
}
