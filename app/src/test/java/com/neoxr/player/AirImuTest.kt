package com.neoxr.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the Air-series report decoding. The layout is fixed by the hardware, so
 * these tests pin the field offsets and the multiplier/divisor scaling — the parts
 * that would silently produce plausible-looking nonsense if they drifted.
 */
class AirImuTest {

    /** Builds a report the way the glasses lay one out. */
    private fun report(
        gyro: Triple<Int, Int, Int> = Triple(1000, -2000, 3000),
        gyroMul: Int = 1, gyroDiv: Int = 1000,
        accel: Triple<Int, Int, Int> = Triple(0, 0, 8192),
        accelMul: Int = 1, accelDiv: Int = 8192,
        signature: Pair<Int, Int> = 0x01 to 0x02
    ): ByteArray {
        val b = ByteArray(64)
        b[0] = signature.first.toByte()
        b[1] = signature.second.toByte()
        fun put16(o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun put32(o: Int, v: Int) {
            put16(o, v and 0xFFFF); put16(o + 2, (v shr 16) and 0xFFFF)
        }
        fun put24(o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte()
            b[o + 1] = ((v shr 8) and 0xFF).toByte()
            b[o + 2] = ((v shr 16) and 0xFF).toByte()
        }
        put16(12, gyroMul); put32(14, gyroDiv)
        put24(18, gyro.first); put24(21, gyro.second); put24(24, gyro.third)
        put16(27, accelMul); put32(29, accelDiv)
        put24(33, accel.first); put24(36, accel.second); put24(39, accel.third)
        return b
    }

    /**
     * The packet's multiplier/divisor yield DEGREES per second; the fusion consumes
     * radians. Getting this wrong amplified every head movement 57x, which is how it
     * shipped in 1.3 and what the first Air owner on real hardware reported.
     */
    private fun rad(deg: Float) = (deg * Math.PI / 180.0).toFloat()

    @Test
    fun `decodes gyro and accel with packet scaling`() {
        val s = AirImu.parse(report(), 64)
        assertNotNull(s)
        assertEquals(rad(1.0f), s!!.gx, 1e-6f)
        assertEquals(rad(-2.0f), s.gy, 1e-6f)
        assertEquals(rad(3.0f), s.gz, 1e-6f)
        assertEquals(0f, s.ax, 1e-6f)
        assertEquals(1.0f, s.az, 1e-6f) // one g, before unit normalisation
    }

    @Test
    fun `gyro leaves the parser in radians, not degrees`() {
        val s = AirImu.parse(report(gyro = Triple(90_000, 0, 0)), 64)!! // 90 deg/s
        assertEquals(1.5708f, s.gx, 1e-4f)
    }

    @Test
    fun `negative 24-bit values keep their sign`() {
        val s = AirImu.parse(report(gyro = Triple(-1, -8_388_608, 8_388_607)), 64)
        assertNotNull(s)
        assertEquals(rad(-0.001f), s!!.gx, 1e-6f)
        assertEquals(rad(-8388.608f), s.gy, 1e-2f)
        assertEquals(rad(8388.607f), s.gz, 1e-2f)
    }

    @Test
    fun `other frame types are skipped`() {
        assertNull(AirImu.parse(report(signature = 0xAA to 0x53), 64)) // init frame
        assertNull(AirImu.parse(report(signature = 0x00 to 0x00), 64))
    }

    @Test
    fun `short reads and zero divisors are rejected`() {
        assertNull(AirImu.parse(report(), 32))
        assertNull(AirImu.parse(report(gyroDiv = 0), 64))
        assertNull(AirImu.parse(report(accelDiv = 0), 64))
    }
}
