package com.neoxr.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Path arithmetic of the share browser — the part that has no network in it. */
class SmbTest {

    @Test
    fun parentClimbsOneLevel() {
        assertEquals("smb://nas/movies/", Smb.parent("smb://nas/movies/VR/"))
        assertEquals("smb://nas/", Smb.parent("smb://nas/movies"))
    }

    @Test
    fun parentStopsAtTheServer() {
        assertNull(Smb.parent("smb://nas/"))
        assertNull(Smb.parent("smb://nas"))
    }

    @Test
    fun childKeepsOneSeparator() {
        assertEquals("smb://nas/movies/a.mkv", Smb.child("smb://nas/movies/", "a.mkv"))
        assertEquals("smb://nas/movies/a.mkv", Smb.child("smb://nas/movies", "a.mkv"))
    }

    @Test
    fun sharesKeepTheirScheme() {
        assertEquals("smb://user:pw@10.0.0.2/vr", Deo.normalizeSiteUrl(" smb://user:pw@10.0.0.2/vr "))
        assertEquals("https://example.com", Deo.normalizeSiteUrl("example.com"))
    }

    @Test
    fun onlyVideoFilesAreListed() {
        assertTrue(Smb.isVideo("Movie.2019.1080p.SBS.mkv"))
        assertTrue(Smb.isVideo("clip.MP4"))
        assertEquals(false, Smb.isVideo("cover.jpg"))
        assertEquals(false, Smb.isVideo("metadata.json"))
        assertEquals(false, Smb.isVideo("README"))
    }

    /**
     * The reason a 4 GB film would not start: media3's extractors read a container
     * header a few bytes at a time, and one SMB request per read is minutes of
     * round trips. Reads must come out of a buffer.
     */
    @Test
    fun smallReadsCostOneRequestPerBuffer() {
        val server = CountingSource(2_000_000)
        val src = Smb.Source()
        src.attach(server, 2_000_000)
        val one = ByteArray(1)
        repeat(300_000) { src.read(one, 0, 1) }
        assertEquals(2, server.requests)
    }

    /** Bytes must come back in order and unmodified across buffer refills. */
    @Test
    fun bufferedReadsKeepTheStreamIntact() {
        val src = Smb.Source()
        src.attach(CountingSource(700_000), 700_000)
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(9_999)
        while (true) {
            val n = src.read(chunk, 0, chunk.size)
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        val bytes = out.toByteArray()
        assertEquals(700_000, bytes.size)
        assertTrue(bytes.indices.all { bytes[it] == (it % 251).toByte() })
    }

    /** Stand-in for a share: hands out a known pattern and counts network calls. */
    private class CountingSource(private val size: Int) : jcifs.SmbRandomAccess {
        var requests = 0
        private var pos = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            requests++
            val n = minOf(len, size - pos)
            if (n <= 0) return -1
            for (i in 0 until n) b[off + i] = ((pos + i) % 251).toByte()
            pos += n
            return n
        }

        override fun seek(p: Long) { pos = p.toInt() }
        override fun close() {}
        override fun read() = throw UnsupportedOperationException()
        override fun read(b: ByteArray) = read(b, 0, b.size)
        override fun length() = size.toLong()
        override fun getFilePointer() = pos.toLong()
        override fun setLength(newLength: Long) = throw UnsupportedOperationException()
        override fun skipBytes(n: Int) = throw UnsupportedOperationException()
        override fun readFully(b: ByteArray) = throw UnsupportedOperationException()
        override fun readFully(b: ByteArray, off: Int, len: Int) = throw UnsupportedOperationException()
        override fun readBoolean() = throw UnsupportedOperationException()
        override fun readByte() = throw UnsupportedOperationException()
        override fun readUnsignedByte() = throw UnsupportedOperationException()
        override fun readShort() = throw UnsupportedOperationException()
        override fun readUnsignedShort() = throw UnsupportedOperationException()
        override fun readChar() = throw UnsupportedOperationException()
        override fun readInt() = throw UnsupportedOperationException()
        override fun readLong() = throw UnsupportedOperationException()
        override fun readFloat() = throw UnsupportedOperationException()
        override fun readDouble() = throw UnsupportedOperationException()
        override fun readLine() = throw UnsupportedOperationException()
        override fun readUTF() = throw UnsupportedOperationException()
        override fun write(b: Int) = throw UnsupportedOperationException()
        override fun write(b: ByteArray) = throw UnsupportedOperationException()
        override fun write(b: ByteArray, off: Int, len: Int) = throw UnsupportedOperationException()
        override fun writeBoolean(v: Boolean) = throw UnsupportedOperationException()
        override fun writeByte(v: Int) = throw UnsupportedOperationException()
        override fun writeShort(v: Int) = throw UnsupportedOperationException()
        override fun writeChar(v: Int) = throw UnsupportedOperationException()
        override fun writeInt(v: Int) = throw UnsupportedOperationException()
        override fun writeLong(v: Long) = throw UnsupportedOperationException()
        override fun writeFloat(v: Float) = throw UnsupportedOperationException()
        override fun writeDouble(v: Double) = throw UnsupportedOperationException()
        override fun writeBytes(sv: String) = throw UnsupportedOperationException()
        override fun writeChars(sv: String) = throw UnsupportedOperationException()
        override fun writeUTF(sv: String) = throw UnsupportedOperationException()
    }
}
