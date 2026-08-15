package com.neoxr.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import java.io.IOException
import java.util.Properties

/**
 * Network shares (SMB2/3) as a video source: browsing and streaming, no download.
 *
 * A share is addressed exactly like a site — `smb://user:password@host/share/path`,
 * or `smb://host/` to list the shares of a server that allows guests. Credentials
 * live in that URL, so they sit in the site list in plain text; that is the same
 * trust level as the rest of the app's storage and it keeps the whole feature to
 * one text field instead of a login screen the glasses cannot show anyway.
 */
object Smb {

    const val SCHEME = "smb://"

    fun isSmb(url: String) = url.startsWith(SCHEME, ignoreCase = true)

    /** A share, folder or file inside the browsed location. */
    data class Entry(val name: String, val isDir: Boolean, val size: Long)

    /**
     * Containers the player can open. A share is somebody's whole disk — listing the
     * PDFs and JSON next to the films is noise, and every one of them is a dead tap.
     */
    private val VIDEO = setOf(
        "mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mts", "mpg", "mpeg",
        "wmv", "flv", "3gp", "ogv", "vob", "divx", "asf", "m3u8", "mpd"
    )

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO

    /**
     * SMB2 as the floor: SMB1 is off by default on every current NAS and Windows,
     * and asking for it only slows the handshake down. DFS off — home servers have
     * no DFS roots and the referral lookup is a timeout waiting to happen.
     */
    private val config: Properties = Properties().apply {
        setProperty("jcifs.smb.client.minVersion", "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        setProperty("jcifs.smb.client.dfs.disabled", "true")
        setProperty("jcifs.smb.client.responseTimeout", "20000")
        setProperty("jcifs.smb.client.soTimeout", "35000")
        setProperty("jcifs.smb.client.connTimeout", "10000")
    }

    private val base: CIFSContext by lazy { BaseContext(PropertyConfiguration(config)) }

    /**
     * Credentials to try, in order. `SmbFile(url, context)` ignores the user info in
     * the URL — only the context counts — so this is the single place credentials
     * come from. Anonymous (a NULL session) and guest are different logins and
     * servers accept them independently, hence both: without them a public share
     * that only allows guests would be unreachable. A user given without a password
     * also falls back to guest, since that is what such an address usually means.
     */
    private fun attempts(url: String): List<CIFSContext> {
        // decoded, so a password with @ / : can be typed percent-encoded
        val info = Uri.parse(url).userInfo?.let { Uri.decode(it) }
        if (info.isNullOrEmpty()) return listOf(base.withGuestCrendentials(), base.withAnonymousCredentials())
        val user = info.substringBefore(':')
        val pass = info.substringAfter(':', "")
        // NtlmPasswordAuthenticator itself splits DOMAIN\user and user@domain
        val named = base.withCredentials(NtlmPasswordAuthenticator(null, user, pass))
        return if (pass.isEmpty()) listOf(named, base.withGuestCrendentials()) else listOf(named)
    }

    /** Runs [op] with each candidate login until one is not rejected. */
    private fun <T> withAuth(url: String, op: (CIFSContext) -> T): T {
        var refused: SmbAuthException? = null
        for (ctx in attempts(url)) {
            try {
                return op(ctx)
            } catch (e: SmbAuthException) {
                refused = e
            }
        }
        throw refused ?: IOException("SMB: no way to log in")
    }

    /**
     * What to show a user who cannot get in. jcifs reports the server's status code
     * ("Logon failure: unknown user name or bad password"), which is accurate and
     * useless: it never says that the address is where the password goes.
     */
    fun explain(e: Throwable): String = when {
        e is SmbAuthException ->
            "Login refused. Put the account in the address — smb://user:password@host — " +
                    "and on macOS tick that account in File Sharing ▸ Options ▸ SMB."
        e is java.net.UnknownHostException -> "Host not found: ${e.message}"
        e.message?.contains("timed out", true) == true ->
            "No answer from the server. Same network? Sharing on?"
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * Lists a location. `smb://host/` enumerates the server's shares; anything
     * deeper enumerates a directory. Blocking — call it off the main thread.
     */
    fun list(url: String): List<Entry> = withAuth(url) { ctx ->
        SmbFile(if (url.endsWith("/")) url else "$url/", ctx).listFiles()
            // attributes come from the listing itself, so this costs no extra round trip
            .map { Entry(it.name.trimEnd('/'), it.name.endsWith("/"), runCatching { it.length() }.getOrDefault(0L)) }
            // hidden entries and admin shares (IPC$, print$) are never what a viewer wants
            .filter { !it.name.startsWith(".") && !it.name.endsWith("$") }
            .filter { it.isDir || isVideo(it.name) }
            .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** The location one level up, or null at the server root. */
    fun parent(url: String): String? {
        val trimmed = url.trimEnd('/')
        val afterHost = trimmed.indexOf('/', SCHEME.length)
        if (afterHost < 0) return null
        return trimmed.substring(0, trimmed.lastIndexOf('/') + 1)
    }

    /** Host and path without the credentials — what to put in a title bar. */
    fun display(url: String): String {
        val u = Uri.parse(url)
        return (u.host ?: "") + (u.path ?: "")
    }

    fun child(url: String, name: String) = url.trimEnd('/') + "/" + name

    /**
     * Reads a share over SMB for the player. Random access, so seeking works the
     * same as on a local file — nothing is copied to the phone first.
     */
    @androidx.media3.common.util.UnstableApi
    class Source : BaseDataSource(true) {
        private var uri: Uri? = null
        private var handle: jcifs.SmbRandomAccess? = null
        private var remaining = 0L

        // One SMB round trip per buffer, not per read. media3's extractors walk a
        // container header a few bytes at a time (EBML elements, MP4 atoms); served
        // straight from the network that is one request each, and a big MKV looks
        // frozen for minutes before the first frame.
        private val buf = ByteArray(256 * 1024)
        private var bufPos = 0
        private var bufEnd = 0

        override fun open(spec: DataSpec): Long {
            uri = spec.uri
            transferInitializing(spec)
            try {
                withAuth(spec.uri.toString()) { ctx ->
                    val f = SmbFile(spec.uri.toString(), ctx)
                    val raf = f.openRandomAccess("r")
                    raf.seek(spec.position)
                    handle = raf
                    bufPos = 0
                    bufEnd = 0
                    remaining = if (spec.length != C.LENGTH_UNSET.toLong()) spec.length
                    else (f.length() - spec.position).coerceAtLeast(0L)
                }
            } catch (e: Exception) {
                // wrong credentials, share gone, host asleep — all read as one IO
                // failure by the player, which surfaces it through onPlayerError
                throw IOException("SMB: " + explain(e), e)
            }
            transferStarted(spec)
            return remaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            if (bufPos == bufEnd) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val filled = handle?.read(buf, 0, want) ?: -1
                if (filled <= 0) return C.RESULT_END_OF_INPUT
                bufPos = 0
                bufEnd = filled
            }
            val n = minOf(length, bufEnd - bufPos)
            System.arraycopy(buf, bufPos, buffer, offset, n)
            bufPos += n
            remaining -= n
            bytesTransferred(n)
            return n
        }

        /** Test seam: the buffering above is the part worth checking without a server. */
        internal fun attach(source: jcifs.SmbRandomAccess, length: Long) {
            handle = source
            remaining = length
            bufPos = 0
            bufEnd = 0
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            try {
                handle?.close()
            } catch (_: IOException) {
            } finally {
                handle = null
                if (uri != null) {
                    uri = null
                    transferEnded()
                }
            }
        }

        @androidx.media3.common.util.UnstableApi
        class Factory : DataSource.Factory {
            override fun createDataSource(): DataSource = Source()
        }
    }
}
