package com.neoxr.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drive identity for the file list — the part that has no device attached. */
class VolumesTest {

    @Test
    fun phoneNeverSharesAKeyWithACard() {
        assertEquals(
            Volumes.PRIMARY,
            Volumes.id(true, "ABCD-1234", "ABCD-1234", "/storage/ABCD-1234")
        )
    }

    @Test
    fun volumeIndexedAsThePhoneStaysThePhone() {
        assertEquals(Volumes.PRIMARY, Volumes.id(false, "external_primary", "ABCD-1234", "/storage/ABCD-1234"))
        assertEquals(Volumes.PRIMARY, Volumes.id(false, "external", null, "/storage/emulated/0"))
    }

    @Test
    fun removablePrefersTheNameMediaStoreUses() {
        assertEquals(
            "abcd-1234",
            Volumes.id(false, "ABCD-1234", "FFFF-0000", "/storage/ABCD-1234")
        )
    }

    @Test
    fun unscannedDriveStillHasAnId() {
        assertEquals("abcd-1234", Volumes.id(false, null, "ABCD-1234", "/storage/ABCD-1234"))
        assertEquals("/storage/usb", Volumes.id(false, null, null, "/storage/usb"))
        assertEquals("removable", Volumes.id(false, null, null, null))
    }

    @Test
    fun blankVolumeCountsAsThePhone() {
        assertTrue(Volumes.isPrimary(null))
        assertTrue(Volumes.isPrimary(""))
        assertTrue(Volumes.isPrimary("external"))
        assertTrue(Volumes.isPrimary("EXTERNAL_PRIMARY"))
        assertEquals(Volumes.PRIMARY, Volumes.canonical(null))
        assertEquals(Volumes.PRIMARY, Volumes.canonical("external"))
        assertFalse(Volumes.isPrimary("abcd-1234"))
    }

    @Test
    fun pathMatchRespectsTheBoundaryAndTheLongestRoot() {
        val mounts = listOf(
            "/storage/emulated/0" to Volumes.PRIMARY,
            "/storage/ABCD" to "abcd",
            "/storage/ABCD-1234" to "abcd-1234"
        )
        assertEquals(Volumes.PRIMARY, Volumes.volumeIdForPath("/storage/emulated/0/Movies/a.mp4", mounts))
        assertEquals("abcd-1234", Volumes.volumeIdForPath("/storage/ABCD-1234/Movies/a.mp4", mounts))
        assertEquals("abcd", Volumes.volumeIdForPath("/storage/ABCD/a.mp4", mounts))
        assertEquals(Volumes.PRIMARY, Volumes.volumeIdForPath(null, mounts))
        assertEquals(Volumes.PRIMARY, Volumes.volumeIdForPath("/mnt/other/a.mp4", mounts))
    }

    @Test
    fun sameFolderNameOnTwoVolumesStaysApart() {
        val phone = listOf(Volumes.PRIMARY, Volumes.PRIMARY)
        val card = listOf("abcd-1234")
        assertEquals(2, phone.count { Volumes.isPrimary(it) })
        assertEquals(0, card.count { Volumes.isPrimary(it) })
        val drives = Volumes.drives(
            listOf(Volumes.Drive(Volumes.PRIMARY, "Internal storage"), Volumes.Drive("abcd-1234", "SD card")),
            phone + card
        )
        assertEquals(listOf("SD card"), drives.map { it.label })
    }

    @Test
    fun indexedVolumeMissingFromTheMountListIsStillADrive() {
        val drives = Volumes.drives(emptyList(), listOf(Volumes.PRIMARY, "usb-1", "usb-1"))
        assertEquals("usb-1", drives.single().id)
        assertEquals("External drive", drives.single().label)
    }

    @Test
    fun twoUnknownVolumesGetDistinctLabels() {
        val drives = Volumes.drives(emptyList(), listOf("usb-1", "usb-2"))
        assertEquals(listOf("External drive 1", "External drive 2"), drives.map { it.label })
    }

    @Test
    fun mountedDriveIsKeptEvenWithNoVideos() {
        val drives = Volumes.drives(listOf(Volumes.Drive("abcd-1234", "USB drive")), emptyList())
        assertEquals("USB drive", drives.single().label)
    }
}
