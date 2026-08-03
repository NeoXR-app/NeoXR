package com.neoxr.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeoTest {

    @Test
    fun normalizeSiteUrl() {
        // scheme is added, but the path is never rewritten
        assertEquals("https://example.com/deovr", Deo.normalizeSiteUrl("example.com/deovr"))
        assertEquals("https://example.com/", Deo.normalizeSiteUrl("https://example.com/"))
        assertEquals("https://x.com/custom.json", Deo.normalizeSiteUrl("https://x.com/custom.json"))
    }

    @Test
    fun premiumLockedDetection() {
        val locked = """{"isPremium":true,"encodings":[{"name":"h264","videoSources":[{"resolution":1080,"url":""}]}]}"""
        assertTrue(Deo.isPremiumLocked(locked))
        try {
            Deo.parseVideo(locked)
            fail("expected premium error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Premium"))
        }
        val free = """{"isPremium":false,"encodings":[{"name":"h264","videoSources":[{"resolution":1080,"url":"u"}]}]}"""
        assertFalse(Deo.isPremiumLocked(free))
    }

    @Test
    fun parseScenes() {
        val json = """{"scenes":[{"name":"Feed","list":[
            {"title":"Sample Video","videoLength":79,"thumbnailUrl":"/thumb1.png","video_url":"/deovr/video/id/1"}
        ]}]}"""
        val scenes = Deo.parseScenes("https://example.com/deovr", json)
        assertEquals(1, scenes.size)
        assertEquals("Feed", scenes[0].name)
        val v = scenes[0].videos[0]
        assertEquals("Sample Video", v.title)
        assertEquals("https://example.com/thumb1.png", v.thumb)
        assertEquals("https://example.com/deovr/video/id/1", v.videoUrl)
    }

    @Test
    fun parseVideoPicksCappedResolutionAndPrefersH265() {
        val json = """{"title":"T","screenType":"dome","stereoMode":"sbs","encodings":[
            {"name":"h264","videoSources":[
                {"resolution":1080,"url":"u1080"},{"resolution":2880,"url":"u2880"},{"resolution":7680,"url":"u7680"}]},
            {"name":"h265","videoSources":[{"resolution":2160,"url":"h265-2160"}]}
        ]}"""
        val info = Deo.parseVideo(json)
        assertEquals("h265-2160", info.url) // h265 preferred over h264
        assertEquals(180, info.angleDeg)
        assertEquals("sbs", info.stereo)

        val h264only = """{"encodings":[{"name":"h264","videoSources":[
            {"resolution":1080,"url":"u1080"},{"resolution":2880,"url":"u2880"},{"resolution":7680,"url":"u7680"}]}]}"""
        assertEquals("u2880", Deo.parseVideo(h264only).url) // highest under MAX_HEIGHT cap

        val hugeOnly = """{"encodings":[{"name":"h264","videoSources":[{"resolution":7680,"url":"u7680"}]}]}"""
        assertEquals("u7680", Deo.parseVideo(hugeOnly).url) // nothing under cap -> lowest available
    }

    @Test
    fun parseVideoListsSourcesBestFirst() {
        val json = """{"encodings":[{"name":"h264","videoSources":[
            {"resolution":1080,"url":"u1080"},{"resolution":2880,"url":"u2880"}]}]}"""
        assertEquals(listOf(2880 to "u2880", 1080 to "u1080"), Deo.parseVideo(json).sources)
    }

    // Filters in the list screen are offered only when the feed ships full items;
    // the documented short form must stay "unknown", not get a fabricated default.
    @Test
    fun parseScenesReadsListingExtrasOnlyWhenPresent() {
        val json = """{"scenes":[{"name":"Feed","list":[
            {"title":"Short","video_url":"/v/1"},
            {"title":"Full","is3d":true,"screenType":"sphere","stereoMode":"sbs2l",
             "encodings":[{"name":"h264","videoSources":[
                {"resolution":2160,"url":"u1"},{"resolution":4320,"url":"u2"}]}]}
        ]}]}"""
        val (short, full) = Deo.parseScenes("https://x.com/deovr", json)[0].videos

        assertEquals(0, short.maxHeight)   // unknown
        assertEquals(-1, short.angleDeg)   // unknown
        assertEquals(null, short.stereo)   // unknown

        assertEquals(4320, full.maxHeight) // best of the listed sources
        assertEquals(360, full.angleDeg)
        assertEquals("sbs", full.stereo)
    }

    @Test
    fun formatDuration() {
        assertEquals("0:45", Deo.formatDuration(45))
        assertEquals("4:05", Deo.formatDuration(245))
        assertEquals("1:01:05", Deo.formatDuration(3665))
    }

    @Test
    fun parseVideoDefaults() {
        val info = Deo.parseVideo("""{"encodings":[{"name":"h264","videoSources":[{"resolution":1080,"url":"u"}]}]}""")
        assertEquals(180, info.angleDeg)
        assertEquals("sbs", info.stereo)
    }

    // Real feeds use fields beyond the documented contract: stereoMode
    // "sbs2l"/"mono", screenType null + viewAngle, is3d — all must normalize.
    @Test
    fun parseVideoNormalizesFeedMetadata() {
        val src = """"encodings":[{"name":"h264","videoSources":[{"resolution":1080,"url":"u"}]}]"""
        fun parse(extra: String) = Deo.parseVideo("""{$extra,$src}""")

        val flat3d = parse(""""screenType":"flat","stereoMode":"sbs2l","is3d":true,"viewAngle":120""")
        assertEquals(0, flat3d.angleDeg) // flat wins over viewAngle
        assertEquals("sbs", flat3d.stereo) // sbs2l is still sbs

        val mono360 = parse(""""stereoMode":"mono","is3d":false,"viewAngle":360""")
        assertEquals(360, mono360.angleDeg)
        assertEquals("off", mono360.stereo)

        val sphere = parse(""""screenType":"sphere","stereoMode":"tb"""")
        assertEquals(360, sphere.angleDeg)
        assertEquals("tb", sphere.stereo)

        val fisheye = parse(""""screenType":"fisheye","viewAngle":200""")
        assertEquals(200, fisheye.angleDeg) // partial/fisheye spans honored
        assertEquals("sbs", fisheye.stereo)

        val flat2d = parse(""""screenType":"flat","is3d":false""")
        assertEquals(0, flat2d.angleDeg)
        assertEquals("off", flat2d.stereo) // no stereoMode + is3d:false = mono

        assertEquals(180, parse(""""screenType":"mkx200"""").angleDeg) // unknown lens → dome
    }

    @Test
    fun guessFormatFromUrlTokens() {
        assertEquals(180 to "sbs", Deo.guessFormat("https://x.com/v/clip_VR180.mp4"))
        assertEquals(180 to "sbs", Deo.guessFormat("https://x.com/scene_180x180_3dh_LR.mp4"))
        assertEquals(180 to "sbs", Deo.guessFormat("https://x.com/rig_MKX200_original.mp4?t=1"))
        assertEquals(360 to "off", Deo.guessFormat("https://x.com/pano_360.mp4")) // bare 360 = mono
        assertEquals(360 to "tb", Deo.guessFormat("https://x.com/trip_360_TB.mp4"))
        assertEquals(180 to "tb", Deo.guessFormat("https://x.com/v_180_OU.mp4"))
        assertEquals(0 to "sbs", Deo.guessFormat("https://x.com/movie.3D.SBS.mkv")) // stereo alone = flat rip
        // resolution digits must never read as 180/360 tokens
        assertEquals(90 to "sbs", Deo.guessFormat("https://x.com/v_3600x1800_h360p.m3u8"))
    }

    @Test
    fun guessFormatFromAspectRatio() {
        val u = "https://x.com/master.m3u8" // no tokens
        assertEquals(180 to "sbs", Deo.guessFormat(u, 3840, 1920)) // 2:1 equirect SBS
        assertEquals(180 to "sbs", Deo.guessFormat(u, 2880, 2880)) // square anamorphic SBS
        assertEquals(0 to "sbs", Deo.guessFormat(u, 7680, 2160)) // 32:9 full SBS pair
        assertEquals(90 to "sbs", Deo.guessFormat(u, 1920, 1080)) // 16:9 web stream: wide SBS
        assertEquals(180 to "tb", Deo.guessFormat(u, 1920, 3840)) // tall = TB pair
        assertEquals(0 to "off", Deo.guessFormat(u, 1440, 1080)) // 4:3 = plain 2D
        assertEquals(90 to "sbs", Deo.guessFormat(u)) // no signal at all: intercept prior
    }
}
