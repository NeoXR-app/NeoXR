package com.neoxr.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.concurrent.thread

/*
 * XREAL Air-series head tracking.
 *
 * The Air series carries its motion sensor on a USB HID interface, unlike the One
 * series, which serves it over a TCP link (see Imu.kt). The two share nothing but
 * the fusion step, so the transport lives here and only the pose feeds back into
 * HeadTracker.
 *
 * Protocol, from the community reverse-engineering in wheaney/nrealAirLinuxDriver:
 * claim the IMU interface, send a "start stream" command, then read 64-byte reports.
 * Each report carries gyro, accelerometer and magnetometer as 24-bit values with a
 * per-axis multiplier/divisor pair, so the scaling is read from the packet rather
 * than hard-coded.
 */
object AirImu {

    const val VENDOR = 0x3318

    /** IMU HID interface per product — the Air series does not use a single number. */
    private val IMU_INTERFACE = mapOf(
        0x0424 to 3, // Air
        0x0428 to 3, // Air 2
        0x0432 to 3, // Air 2 Pro
        0x0426 to 2, // Air 2 Ultra
        0x0440 to 1, // XBX A01
        0x0442 to 1  // XBX A01 Plus
    )

    private const val MSG_START_IMU = 0x19
    private const val ACTION_PERMISSION = "com.neoxr.player.USB_PERMISSION"

    fun findDevice(context: Context): UsbDevice? =
        (context.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values
            .firstOrNull { it.vendorId == VENDOR && IMU_INTERFACE.containsKey(it.productId) }

    /**
     * Asks for USB permission if needed, then calls [onReady] with the granted device
     * (or [onFail] with a reason). Android only allows claiming an interface after
     * the user has approved access to that device.
     */
    fun requestAccess(
        context: Context, device: UsbDevice,
        onReady: (UsbDevice) -> Unit, onFail: (String) -> Unit
    ) {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (um.hasPermission(device)) return onReady(device)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                context.unregisterReceiver(this)
                if (i?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) == true) {
                    onReady(device)
                } else {
                    onFail("Head: USB access to the glasses was denied")
                }
            }
        }
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        um.requestPermission(
            device,
            PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE // the system fills in the result extras
            )
        )
    }

    /** One decoded sample: angular rate in rad/s, acceleration in m/s². */
    class Sample(val gx: Float, val gy: Float, val gz: Float,
                 val ax: Float, val ay: Float, val az: Float)

    /**
     * Opens the IMU interface and streams samples to [onSample] until [stopped]
     * returns true. Blocking — call it on its own thread. Returns an error message,
     * or null when the stream ended normally.
     */
    fun stream(
        context: Context, device: UsbDevice,
        stopped: () -> Boolean, onSample: (Sample) -> Unit
    ): String? {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val ifaceIndex = IMU_INTERFACE[device.productId]
            ?: return "Head: unsupported XREAL model"
        val iface: UsbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == ifaceIndex && it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: return "Head: the glasses' sensor interface is missing"

        val conn: UsbDeviceConnection = um.openDevice(device)
            ?: return "Head: cannot open the glasses over USB"
        try {
            if (!conn.claimInterface(iface, true)) {
                return "Head: the sensor interface is busy (another app may hold it)"
            }
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = inEp ?: ep
                else outEp = outEp ?: ep
            }
            val input = inEp ?: return "Head: the sensor has no data endpoint"

            if (!sendStart(conn, iface, outEp, start = true)) {
                return "Head: the glasses refused to start the sensor"
            }

            val buf = ByteArray(64)
            // Acceleration arrives in g on this hardware, but the packet carries its
            // own scaling, so detect from the first plausible magnitude rather than
            // assuming: ~1 means g, ~9.8 means m/s².
            var accelToMs2 = 0f
            var idle = 0
            while (!stopped()) {
                val n = conn.bulkTransfer(input, buf, buf.size, 250)
                if (n <= 0) {
                    if (++idle > 40) return "Head: the sensor stopped sending data"
                    continue
                }
                idle = 0
                val s = parse(buf, n) ?: continue
                if (accelToMs2 == 0f) {
                    val mag = Math.sqrt(
                        (s.ax * s.ax + s.ay * s.ay + s.az * s.az).toDouble()
                    ).toFloat()
                    if (mag > 0.2f) accelToMs2 = if (mag < 3f) 9.80665f else 1f
                    else continue
                }
                onSample(
                    Sample(
                        s.gx, s.gy, s.gz,
                        s.ax * accelToMs2, s.ay * accelToMs2, s.az * accelToMs2
                    )
                )
            }
            sendStart(conn, iface, outEp, start = false)
            return null
        } finally {
            try { conn.releaseInterface(iface) } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
    }

    /**
     * Sends the stream on/off command. The frame is: 0xAA, CRC32 of everything that
     * follows the checksum field, length, message id, payload — all little-endian.
     * HID devices without an interrupt OUT endpoint take it as a control SET_REPORT.
     */
    private fun sendStart(
        conn: UsbDeviceConnection, iface: UsbInterface, out: UsbEndpoint?, start: Boolean
    ): Boolean {
        val payload = byteArrayOf(if (start) 1 else 0)
        val bodyLen = 3 + payload.size // length(2) + msgid(1) + payload
        val body = ByteBuffer.allocate(bodyLen).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(bodyLen.toShort())
        body.put(MSG_START_IMU.toByte())
        body.put(payload)

        val crc = CRC32().apply { update(body.array()) }.value.toInt()
        val frame = ByteBuffer.allocate(5 + bodyLen).order(ByteOrder.LITTLE_ENDIAN)
        frame.put(0xAA.toByte())
        frame.putInt(crc)
        frame.put(body.array())

        val data = frame.array()
        if (out != null) {
            return conn.bulkTransfer(out, data, data.size, 500) == data.size
        }
        // 0x21 = host-to-device, class, interface; 0x09 = SET_REPORT; 0x0200 = output
        return conn.controlTransfer(0x21, 0x09, 0x0200, iface.id, data, data.size, 500) >= 0
    }

    /**
     * Decodes one report. Signature 0x01 0x02 marks a sensor sample; 0xAA 0x53 is the
     * stream's init packet and anything else is a frame type we don't consume.
     * Values are 24-bit little-endian, scaled by a 16-bit multiplier over a 32-bit
     * divisor that travel in the same packet.
     */
    internal fun parse(b: ByteArray, n: Int): Sample? {
        if (n < 64) return null
        if ((b[0].toInt() and 0xFF) != 0x01 || (b[1].toInt() and 0xFF) != 0x02) return null

        fun u(i: Int) = b[i].toInt() and 0xFF
        fun i16(o: Int) = (u(o) or (u(o + 1) shl 8)).toShort().toInt()
        fun i32(o: Int) = u(o) or (u(o + 1) shl 8) or (u(o + 2) shl 16) or (u(o + 3) shl 24)
        fun i24(o: Int): Int {
            val v = u(o) or (u(o + 1) shl 8) or (u(o + 2) shl 16)
            return if (v and 0x800000 != 0) v or 0xFF000000.toInt() else v
        }

        // layout: signature[2] temperature[2] timestamp[8], then per-sensor blocks of
        // multiplier[2] divisor[4] x[3] y[3] z[3]
        val g = 12
        val gDiv = i32(g + 2)
        if (gDiv == 0) return null
        val gMul = i16(g)
        val a = g + 15
        val aDiv = i32(a + 2)
        if (aDiv == 0) return null
        val aMul = i16(a)

        // The packet's own multiplier/divisor yields DEGREES per second, as in the
        // reference driver; the fusion wants radians. Missing this made every head
        // movement 57x too large — the first Air owner on real hardware called the
        // tracking unusable, which is exactly what that looks like.
        val toRad = (Math.PI / 180.0).toFloat()
        return Sample(
            i24(g + 6) * gMul.toFloat() / gDiv * toRad,
            i24(g + 9) * gMul.toFloat() / gDiv * toRad,
            i24(g + 12) * gMul.toFloat() / gDiv * toRad,
            i24(a + 6) * aMul.toFloat() / aDiv,
            i24(a + 9) * aMul.toFloat() / aDiv,
            i24(a + 12) * aMul.toFloat() / aDiv
        )
    }
}
