package com.neoxr.player

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        // No bundled site list: the app ships empty and users add their own feeds.
        // Keeps the build free of third-party URLs.

        // Short plain English, written for non-native readers; shown in-layout so it
        // reads the same on the phone, in SBS split and on the glasses.
        // NO manual wrapping inside sentences — the TextView wraps to its own width,
        // and hand-inserted breaks produce ragged lines at the glasses' width.
        val HELP = """
            NEOXR — QUICK GUIDE

            SITES
            Tap a site to open its videos: a list with previews, and a "Browse" panel for search, sorting and filters over it. Add any site by its URL. ✕ removes a site.
            "Open File" lists the videos on this device, grouped by folder; NeoXR also appears in "Open with" and "Share" for video files.

            BROWSER
            Sites without a video feed open in the built-in browser: play a video there and press "▶ VR" to watch it in the player. ← → navigate; "View" reloads the page as a phone, a Quest 3 or a desktop; the % button sets page scale; "Pad" turns the right third of the screen into a touchpad with a pointer, for use through the glasses.

            NETWORK SHARES (NAS)
            Add a share the same way as a site: smb://user:password@192.168.1.10 — or smb://192.168.1.10 for a guest share. Tap it to browse the server's folders and play straight from it, with no download. The password is stored as typed, in this app's own storage.

            GLASSES (USB-C)
            Plug in XREAL glasses — everything shows in the glasses, and the phone becomes the controller: drag — move the pointer, tap — click, two fingers — scroll.
            By default the view in a video follows the PHONE gyro: turn the phone and the picture turns with it.
            Without glasses: long-press any screen to turn the SBS split view on / off (for the glasses' mirror mode).

            PLAYER
            Tap — show / hide the controls. Swipe — look around. Long press — center the view. Double tap — phone gyro on / off.
            ◄◄ and ►► skip 10 s back and 15 s forward; hold them for 1 min back and 5 min forward.
            A video reopens where you left it.
            The bottom bar also has "…" for stream quality, when the video offers more than one.

            PICTURE
            Left buttons — screen shape: Flat / Wide / 180° / 360°. Right buttons — 3D layout: 2D / SBS (side by side) / OU (over-under). Both are detected from the video, and stay on whatever you press.
            Z − / + — zoom. W − / + — width. "3D" in the bottom bar opens the rest: depth − / + and eye swap ⇄ for material with the halves reversed, plus H − / + for height.
            Use W and H when a video is encoded with the wrong scale; over 100 they fill the panel and crop.
            "Ambient glow" in that panel lights the black surround with the colours of the picture's edges, in three strengths — for flat and wide screens. Films letterboxed inside their own frame are handled: the glow looks past the black bar for the real edge of the picture.
            "PIC" in the bottom bar holds the picture calibration — black level, brightness, contrast and gamma (lower gamma opens up shadows on panels that crush them) — plus the glow width and the head / gyro sensitivity. These describe your glasses, so they are kept between videos.
            "Output" appears when you watch on the phone without glasses: it switches the split pair to a single view.

            SUBTITLES AND AUDIO
            "CC" appears when a video carries several audio tracks or subtitles. Tracks the device cannot decode are marked; picking one that fails no longer stops the video, it keeps playing without sound.
            The same menu sets subtitle size, height, weight and colour, and has "Subtitle source" for tracks that are already a 3D pair — switch it when you see four copies instead of two. Height runs from −20 to 100: negative puts the line below the picture, in the black bar, and 100 puts it at the top.

            SCREENS
            "Screen" appears when the phone has more than one display to render on — press it to move the video to the next one.

            HEAD TRACKING
            The view follows your head, using the sensor inside the glasses. Works on XREAL One and Air series; on other glasses use the phone gyro instead (double tap).
            1. In the glasses' own menu: Stabilizer OFF, mode Follow (not Anchor), latest firmware.
            2. Open a video and press "Head" in the left column (shown only with glasses connected). On Air series, allow the USB permission Android asks for.
            3. Look forward and long-press to center.
            If the view turns faster than you do, lower "Head / gyro sensitivity" under "PIC"; 100 means the scene turns exactly as much as you do.
            Press "Head" again to return to the phone gyro. On One series, a VPN can block the connection — enable "allow apps to bypass VPN" in its settings if head tracking cannot reach the glasses.
        """.trimIndent()
    }

    private val prefs by lazy { getSharedPreferences("neoxr", MODE_PRIVATE) }
    private var sites = mutableListOf<String>()
    private val adapter = SiteAdapter()

    private val REQ_OPEN_FILE = 7
    private val REQ_MEDIA_PERM = 8
    private var wrapView: SbsFrameLayout? = null

    private fun mediaPermission() =
        if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun openLocalVideos() {
        if (checkSelfPermission(mediaPermission()) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) showLocalVideos() else requestPermissions(arrayOf(mediaPermission()), REQ_MEDIA_PERM)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MEDIA_PERM) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showLocalVideos()
        } else {
            // no media access — the system document picker still works without it
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }, REQ_OPEN_FILE)
        }
    }

    // One video on the device: its URI, file name, duration and the folder it lives in.
    private data class LocalVideo(
        val uri: android.net.Uri, val name: String, val duration: String, val folder: String
    )

    private var localVideos = listOf<LocalVideo>()
    private var openFolder: String? = null // null = folder list, otherwise its videos

    // Network share being browsed in the same overlay; null = the overlay is showing
    // local videos instead.
    private var smbUrl: String? = null
    private var smbEntries = listOf<Smb.Entry>()

    /**
     * Opens a share in the file overlay. Listing is a network call, so it runs off
     * the main thread and the overlay shows up right away with a working title.
     */
    private fun openSmb(url: String) {
        val wrap = wrapView ?: return
        smbUrl = url
        smbEntries = emptyList()
        wrap.findViewById<TextView>(R.id.fileTitle).text = "◂  " + Smb.display(url).uppercase()
        wrap.findViewById<TextView>(R.id.fileEmpty).text = "Connecting…"
        renderFileList()
        wrap.findViewById<View>(R.id.fileOverlay).visibility = View.VISIBLE
        Thread {
            val listed = runCatching { Smb.list(url) }
            runOnUiThread {
                if (smbUrl != url) return@runOnUiThread // navigated away while loading
                smbEntries = listed.getOrDefault(emptyList())
                wrap.findViewById<TextView>(R.id.fileEmpty).text =
                    listed.exceptionOrNull()?.let { Smb.explain(it) } ?: "Nothing here"
                renderFileList()
            }
        }.start()
    }

    private fun showLocalVideos() {
        val wrap = wrapView ?: return
        val found = mutableListOf<LocalVideo>()
        try {
            contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    android.provider.MediaStore.Video.Media._ID,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Video.Media.DURATION,
                    android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                ),
                null, null,
                android.provider.MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(0)
                    )
                    found.add(
                        LocalVideo(
                            uri,
                            c.getString(1) ?: "?",
                            Deo.formatDuration((c.getLong(2) / 1000).toInt()),
                            c.getString(3) ?: "Other"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Media query failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        localVideos = found
        openFolder = null
        smbUrl = null
        renderFileList()
        wrap.findViewById<View>(R.id.fileOverlay).visibility = View.VISIBLE
    }

    /**
     * Draws either the folder list or the videos inside [openFolder]. A flat list of
     * every video on the device is unusable on a real library, so the first level
     * groups by folder (MediaStore's bucket) with a count per row.
     */
    private fun renderFileList() {
        val wrap = wrapView ?: return
        val title = wrap.findViewById<TextView>(R.id.fileTitle)
        val list = wrap.findViewById<ListView>(R.id.fileList)
        val pad = (12 * resources.displayMetrics.density).toInt()

        val share = smbUrl
        val folder = openFolder
        // rows: label to action
        val rows: List<Pair<String, () -> Unit>> = if (share != null) {
            title.text = "◂  " + Smb.display(share).uppercase()
            listOfNotNull(Smb.parent(share)?.let { up -> "◂  Up" to { openSmb(up) } }) +
                    smbEntries.map { e ->
                        val label = if (e.isDir) "▸  ${e.name}"
                        else "${e.name}   ·   ${e.size / 1_048_576} MB"
                        label to {
                            if (e.isDir) openSmb(Smb.child(share, e.name))
                            else startActivity(
                                Intent(this, PlayerActivity::class.java)
                                    .putExtra("stream", Smb.child(share, e.name))
                            )
                        }
                    }
        } else if (folder == null) {
            title.text = "LOCAL VIDEOS"
            localVideos.groupBy { it.folder }.entries
                .sortedBy { it.key.lowercase() }
                .map { (name, items) ->
                    "▸  $name   (${items.size})" to { openFolder = name; renderFileList() }
                }
        } else {
            title.text = "◂  ${folder.uppercase()}"
            listOf<Pair<String, () -> Unit>>(
                "◂  Back to folders" to { openFolder = null; renderFileList() }
            ) + localVideos.filter { it.folder == folder }.map { v ->
                "${v.name}   ·   ${v.duration}" to {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .setData(v.uri)
                    )
                }
            }
        }

        wrap.findViewById<View>(R.id.fileEmpty).visibility =
            if (rows.isEmpty() || (smbUrl != null && smbEntries.isEmpty())) View.VISIBLE
            else View.GONE
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(pos: Int) = rows[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
                val v = (convert as? TextView) ?: TextView(this@MainActivity).apply {
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setTextColor(getColor(R.color.on_surface))
                    setPadding(pad, pad, pad, pad)
                }
                v.text = rows[pos].first
                return v
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> rows[pos].second() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // findViewById via wrap: with glasses attached the content lives on THEM,
        // not in the activity's content view (which is then the touchpad controller)
        val wrap = SbsFrameLayout.attach(this, R.layout.activity_main)
        // version in the header so a stale APK is visible at a glance
        wrap.findViewById<TextView>(R.id.subtitle).append(
            " · v" + packageManager.getPackageInfo(packageName, 0).versionName
        )
        load()

        val list = wrap.findViewById<ListView>(R.id.sites)
        val input = wrap.findViewById<EditText>(R.id.siteInput)
        list.adapter = adapter

        val help = wrap.findViewById<View>(R.id.helpOverlay)
        wrap.findViewById<TextView>(R.id.helpText).text = HELP
        wrap.findViewById<View>(R.id.btnHelp).setOnClickListener { help.visibility = View.VISIBLE }
        wrap.findViewById<View>(R.id.helpClose).setOnClickListener { help.visibility = View.GONE }

        // local videos: an IN-LAYOUT MediaStore list — the system picker is a
        // phone-only window and cannot be shown on the glasses (same limitation as
        // dialogs/IME), while this overlay lives in the wrap: on the glasses,
        // SBS-aware, cursor-clickable. Fallback to the system picker only when the
        // media permission is denied.
        wrap.findViewById<View>(R.id.btnFile).setOnClickListener { openLocalVideos() }
        wrap.findViewById<View>(R.id.fileClose).setOnClickListener {
            wrapView?.findViewById<View>(R.id.fileOverlay)?.visibility = View.GONE
        }
        wrapView = wrap

        fun addTypedSite() {
            try {
                val url = Deo.normalizeSiteUrl(input.text.toString())
                if (url !in sites) {
                    sites.add(url)
                    save()
                    adapter.notifyDataSetChanged()
                }
                input.text.clear()
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
            }
        }
        wrap.findViewById<Button>(R.id.addSite).setOnClickListener { addTypedSite() }
        // Done on the keyboard adds the site too — typing a URL and then hunting for
        // the Add button is a tap too many
        input.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                            event.action == android.view.KeyEvent.ACTION_DOWN)
            if (done && input.text.isNotBlank()) {
                addTypedSite()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                input.clearFocus()
            }
            done
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val url = sites[pos]
            if (Smb.isSmb(url)) openSmb(url)
            else startActivity(Intent(this, VideoListActivity::class.java).putExtra("url", url))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // survive player recreate on glasses plug/unplug (grants die with the task)
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(uri)
        )
    }

    private fun confirmRemove(url: String) {
        AlertDialog.Builder(this)
            .setMessage("Remove ${host(url)}?")
            .setPositiveButton("Remove") { _, _ ->
                sites.remove(url)
                save()
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // The credentials of a share stay in storage but never on screen: a site row is
    // also drawn on the glasses, and a password does not belong in a list anyone can
    // look at over your shoulder.
    private fun host(url: String) =
        if (Smb.isSmb(url)) Smb.display(url).substringBefore('/')
        else try {
            URL(url).host.removePrefix("www.")
        } catch (e: Exception) {
            url
        }

    private fun shown(url: String) =
        if (Smb.isSmb(url)) Smb.SCHEME + Smb.display(url) else url

    private fun load() {
        val arr = JSONArray(prefs.getString("sites", "[]"))
        sites = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
    }

    private fun save() = prefs.edit()
        .putString("sites", JSONArray(sites).toString())
        .apply()

    private inner class SiteAdapter : BaseAdapter() {
        override fun getCount() = sites.size
        override fun getItem(pos: Int) = sites[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            val view = convert ?: layoutInflater.inflate(R.layout.item_site, parent, false)
            val url = sites[pos]
            val h = host(url)
            view.findViewById<TextView>(R.id.siteAvatar).text =
                h.firstOrNull()?.uppercase() ?: "?"
            view.findViewById<TextView>(R.id.siteHost).text = h
            view.findViewById<TextView>(R.id.siteUrl).text = shown(url)
            view.findViewById<TextView>(R.id.siteRemove).setOnClickListener {
                confirmRemove(url)
            }
            return view
        }
    }
}
