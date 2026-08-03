package com.neoxr.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImuTest {

    private fun frame(
        gyro: FloatArray = floatArrayOf(0.1f, -0.2f, 0.3f),
        accel: FloatArray = floatArrayOf(0f, 0f, 9.8f),
        typed: Boolean = true
    ): ByteArray {
        val b = ByteArray(ImuFrame.SIZE)
        ImuFrame.MAGIC.copyInto(b, 0)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(34)
        gyro.forEach { bb.putFloat(it) }
        accel.forEach { bb.putFloat(it) }
        if (typed) {
            byteArrayOf(0x00, 0x40, 0x1f, 0x00, 0x00, 0x40).copyInto(b, 78)
        }
        return b
    }

    @Test
    fun `frame parses gyro and accel`() {
        val v = ImuFrame.parse(frame(), 0)
        assertNotNull(v)
        assertEquals(0.1f, v!![0], 1e-6f)
        assertEquals(-0.2f, v[1], 1e-6f)
        assertEquals(0.3f, v[2], 1e-6f)
        assertEquals(9.8f, v[5], 1e-6f)
    }

    @Test
    fun `non-imu frame type is skipped`() {
        assertNull(ImuFrame.parse(frame(typed = false), 0))
    }

    @Test
    fun `garbage values are rejected`() {
        assertNull(ImuFrame.parse(frame(gyro = floatArrayOf(Float.NaN, 0f, 0f)), 0))
        assertNull(ImuFrame.parse(frame(accel = floatArrayOf(0f, 0f, 500f)), 0))
    }

    @Test
    fun `magic scan finds a frame mid-buffer`() {
        val junk = ByteArray(7) { 0x11 }
        val buf = junk + frame()
        assertTrue(!ImuFrame.magicAt(buf, 0))
        assertTrue(ImuFrame.magicAt(buf, 7))
    }

    @Test
    fun `madgwick integrates a pure yaw rotation`() {
        val m = Madgwick()
        // 90°/s about z for one second at 1 kHz; accel zeroed → correction skipped
        val w = Math.toRadians(90.0).toFloat()
        repeat(1000) { m.update(0f, 0f, w, 0f, 0f, 0f, 0.001f) }
        assertEquals(90f, Math.abs(m.yawDeg), 2f)
        assertEquals(0f, m.pitchDeg, 2f)
    }

    @Test
    fun `madgwick levels the horizon from gravity`() {
        val m = Madgwick(beta = 0.5f) // strong correction to converge fast in test
        // pitch the pose ~17° off level, then feed still gravity along +z
        m.update(0f, 1f, 0f, 0f, 0f, 0f, 0.3f)
        repeat(2000) { m.update(0f, 0f, 0f, 0f, 0f, 9.8f, 0.001f) }
        assertEquals(0f, m.pitchDeg, 2f)
    }
}
