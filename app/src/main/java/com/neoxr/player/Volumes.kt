package com.neoxr.player

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

/**
 * SD cards and USB drives in the file list.
 *
 * The list is not a directory walk. It is the videos MediaStore has already
 * scanned. One query of the merged external volume returns every drive's
 * videos, but the volume column on that query is often blank or the generic
 * name "external" — both of which mean "the phone" here — so a card's videos
 * land in the phone's folders and the card never gets its own row. Each drive
 * is therefore queried through its own collection, and keyed apart from the
 * phone, whether or not it has videos yet.
 */
object Volumes {

    /** MediaStore's name for the phone's shared storage. */
    const val PRIMARY = "external_primary"

    /** A mounted volume. [path] is only needed on Android 9 and below, which have no volume column. */
    data class Mount(val id: String, val label: String, val primary: Boolean, val path: String?)

    /** A row in the file list: one removable drive. */
    data class Drive(val id: String, val label: String)

    /**
     * The phone's own storage. A missing name is the phone too: older scans
     * simply have no volume column, and treating that as a drive would invent
     * a blank row. "external" is the merged collection, not one card.
     */
    fun isPrimary(id: String?): Boolean {
        val n = id?.trim()?.lowercase().orEmpty()
        return n.isEmpty() || n == PRIMARY || n == "external"
    }

    /** Volume id stored on a video. Blank and primary names collapse to [PRIMARY]. */
    fun canonical(name: String?): String {
        val n = name?.trim()?.lowercase().orEmpty()
        return if (isPrimary(n)) PRIMARY else n
    }

    /**
     * Stable id for a mounted volume. The phone is always [PRIMARY]. A
     * removable volume never is, even when its MediaStore name comes back as
     * the phone's: that generic name is what hid the card's row. It prefers
     * the real per-drive name videos are tagged with, then the filesystem
     * uuid, then the mount path — the last two keep a drive with no indexed
     * video yet.
     */
    fun id(isPrimary: Boolean, mediaStoreVolumeName: String?, uuid: String?, path: String?): String {
        if (isPrimary) return PRIMARY
        val media = mediaStoreVolumeName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && !isPrimary(it) }
        if (media != null) return media
        uuid?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { return it }
        return path?.trim()?.takeIf { it.isNotEmpty() } ?: "removable"
    }

    /**
     * Drive a video belongs to. [queriedVolume] is the collection that returned
     * it — a real card name wins outright. The merged collection and the phone's
     * own collection both look like [PRIMARY], and then the file path is the
     * only remaining way to tell a card from the phone.
     */
    fun assignVolume(queriedVolume: String?, filePath: String?, mounts: List<Pair<String, String>>): String {
        val tagged = if (queriedVolume == null) volumeIdForPath(filePath, mounts) else canonical(queriedVolume)
        if (!isPrimary(tagged)) return tagged
        val byPath = volumeIdForPath(filePath, mounts)
        return if (isPrimary(byPath)) tagged else byPath
    }

    /**
     * Which mounted volume a file path sits on. The longest matching root wins,
     * and the root must end on a boundary so `/storage/ABCD` does not swallow
     * `/storage/ABCD-1234`. No match means the phone: that is where an untagged
     * file already lived before drives had their own rows.
     */
    fun volumeIdForPath(filePath: String?, mounts: List<Pair<String, String>>): String {
        if (filePath.isNullOrBlank()) return PRIMARY
        val hit = mounts.mapNotNull { (path, id) ->
            val root = path.trimEnd('/')
            if (root.isNotEmpty() && (filePath == root || filePath.startsWith("$root/"))) root to id
            else null
        }.maxByOrNull { it.first.length }
        return hit?.second ?: PRIMARY
    }

    /**
     * Removable drives to show as their own rows. Mounted drives come first,
     * in the order the system reports them. A volume that already has indexed
     * videos but is missing from that list is appended, so those videos stay
     * out of the phone's folders instead of disappearing.
     */
    fun drives(mounts: List<Drive>, videoVolumeIds: Iterable<String>): List<Drive> {
        val external = mounts.filter { !isPrimary(it.id) }.distinctBy { it.id }
        val known = external.map { it.id }.toSet()
        val extra = videoVolumeIds.map { canonical(it) }
            .filter { !isPrimary(it) && it !in known }
            .distinct()
        return external + extra.mapIndexed { i, id ->
            Drive(id, if (extra.size == 1) "External drive" else "External drive ${i + 1}")
        }
    }

    /** Volumes currently mounted. Unmounted entries are omitted on Android 11+, where the state is public. */
    fun mounts(context: Context): List<Mount> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.mapNotNull { vol ->
            if (Build.VERSION.SDK_INT >= 30) {
                val state = vol.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                    return@mapNotNull null
                }
            }
            val path = mountPath(vol)
            val id = id(vol.isPrimary, mediaStoreName(vol), vol.uuid, path)
            val label = vol.getDescription(context).ifBlank { "External drive" }
            Mount(id, label, vol.isPrimary, path)
        }.distinctBy { it.id }
    }

    /** Name MediaStore uses for this volume, or null when the scanner has not indexed it. */
    private fun mediaStoreName(volume: StorageVolume): String? {
        if (Build.VERSION.SDK_INT >= 30) return volume.mediaStoreVolumeName
        return null
    }

    /**
     * Mount path. Public since Android 11; before that the same method exists
     * but is hidden, and the file path is the only way to tell a card's videos
     * from the phone's.
     */
    private fun mountPath(volume: StorageVolume): String? {
        if (Build.VERSION.SDK_INT >= 30) return volume.directory?.absolutePath
        return try {
            volume.javaClass.getMethod("getPath").invoke(volume) as? String
        } catch (_: Exception) {
            null
        }
    }
}
