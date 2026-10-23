package app.glint.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatChoicesTest {
    private fun format(
        id: String,
        height: Int? = null,
        width: Int? = null,
        fps: Int? = null,
        video: Boolean = height != null,
        audio: Boolean = !video,
        size: Long? = null,
        estimate: Boolean = false,
        kbps: Double? = null,
        codec: String? = if (video) "avc1.640028" else null,
        range: String? = null,
    ) =
        MediaFormat(
            id = id,
            ext = "mp4",
            width = width,
            height = height,
            fps = fps,
            videoCodec = codec,
            audioCodec = if (audio) "mp4a.40.2" else null,
            hasVideo = video,
            hasAudio = audio,
            bitrateKbps = kbps,
            sizeBytes = size,
            sizeIsEstimate = estimate,
            note = "",
            dynamicRange = range,
            language = null,
        )

    @Test
    fun `one choice per resolution, highest first, using yt-dlp's best stream`() {
        val formats =
            listOf(
                format("140", size = 3_000_000),
                format("134", height = 360, width = 640, size = 5_000_000),
                format("136", height = 720, width = 1280, size = 10_000_000),
                format("298", height = 720, width = 1280, fps = 60, size = 15_000_000),
                format("137", height = 1080, width = 1920, size = 20_000_000),
            )

        val choices = FormatChoices.video(formats, durationSeconds = 100)

        assertEquals(listOf("1080p", "720p60", "360p"), choices.map { it.label })
        assertEquals("298+ba/298/bv*[height<=720]+ba/b[height<=720]/b", choices[1].selector)
        assertEquals(18_000_000L, choices[1].sizeBytes)
        assertFalse(choices[1].sizeIsEstimate)
    }

    @Test
    fun `a stream with sound needs no separate audio`() {
        val formats = listOf(format("18", height = 360, width = 640, audio = true, size = 9_000_000))

        val choice = FormatChoices.video(formats, durationSeconds = 100).single()

        assertEquals("18/bv*[height<=360]+ba/b[height<=360]/b", choice.selector)
        assertEquals(9_000_000L, choice.sizeBytes)
    }

    @Test
    fun `sizes are estimated from the bitrate when the site gives none`() {
        val formats = listOf(format("251", kbps = 128.0), format("247", height = 720, width = 1280, kbps = 1000.0))

        val choice = FormatChoices.video(formats, durationSeconds = 80).single()

        assertEquals((1000.0 * 1000 / 8 * 80 + 128.0 * 1000 / 8 * 80).toLong(), choice.sizeBytes)
        assertTrue(choice.sizeIsEstimate)
    }

    @Test
    fun `an unknown audio size leaves the total unknown`() {
        val formats = listOf(format("251"), format("247", height = 720, width = 1280, size = 1_000))

        assertNull(FormatChoices.video(formats, durationSeconds = 0).single().sizeBytes)
    }

    @Test
    fun `portrait videos are labeled by their short side`() {
        val formats = listOf(format("1", height = 1920, width = 1080, audio = true))

        assertEquals("1080p", FormatChoices.video(formats, 10).single().label)
    }

    @Test
    fun `HDR streams are marked`() {
        val formats = listOf(format("399", height = 1080, width = 1920, range = "HDR10", codec = "av01.0.09M.10"))

        val choice = FormatChoices.video(formats, 10).single()

        assertTrue(choice.hdr)
        assertEquals("AV1", choice.codec)
    }

    @Test
    fun `a preset picks the highest quality within it`() {
        val formats =
            listOf(
                format("1", height = 360, width = 640),
                format("2", height = 720, width = 1280),
                format("3", height = 1080, width = 1920),
            )
        val choices = FormatChoices.video(formats, 10)

        assertEquals("1080p", FormatChoices.pick(choices, Quality.Best)?.label)
        assertEquals("720p", FormatChoices.pick(choices, Quality.P720)?.label)
        assertEquals("360p", FormatChoices.pick(choices, Quality.P480)?.label)
        assertNull(FormatChoices.pick(emptyList(), Quality.P480))
        // When everything is above the preset, the lowest choice is the closest.
        assertEquals("1080p", FormatChoices.pick(choices.take(1), Quality.P480)?.label)
    }

    @Test
    fun `a portrait video counts its short side against a preset`() {
        val choices = FormatChoices.video(listOf(format("1", height = 1920, width = 1080), format("2", height = 1280, width = 720)), 10)

        assertEquals("1080p", FormatChoices.pick(choices, Quality.P1080)?.label)
    }

    @Test
    fun `exact streams get audio only when they have none`() {
        assertEquals("137+ba/137", FormatChoices.selectorFor(format("137", height = 1080)))
        assertEquals("18", FormatChoices.selectorFor(format("18", height = 360, audio = true)))
        assertEquals("251", FormatChoices.selectorFor(format("251")))
    }

    @Test
    fun `presets fall back to whatever is available`() {
        assertEquals("bv*+ba/b", FormatChoices.preset(MediaType.Video, Quality.Best))
        assertEquals("bv*[height<=720]+ba/b[height<=720]/b", FormatChoices.preset(MediaType.Video, Quality.P720))
        assertEquals("ba/b", FormatChoices.preset(MediaType.Audio, Quality.P720))
        assertEquals("720p", FormatChoices.presetLabel(MediaType.Video, Quality.P720, AudioFormat.Mp3))
        assertEquals("MP3", FormatChoices.presetLabel(MediaType.Audio, Quality.P720, AudioFormat.Mp3))
        assertEquals("", FormatChoices.presetLabel(MediaType.Audio, Quality.Best, AudioFormat.Original))
    }

    @Test
    fun `codec names are short and readable`() {
        assertEquals("AV1", FormatChoices.codecName("av01.0.08M.08"))
        assertEquals("VP9", FormatChoices.codecName("vp09.00.40.08"))
        assertEquals("VP9", FormatChoices.codecName("vp9"))
        assertEquals("H.264", FormatChoices.codecName("avc1.640028"))
        assertEquals("HEVC", FormatChoices.codecName("hvc1.2.4.L153"))
        assertEquals("AAC", FormatChoices.codecName("mp4a.40.2"))
        assertEquals("Opus", FormatChoices.codecName("opus"))
        assertNull(FormatChoices.codecName(null))
    }
}
