package app.getknit.knit.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec, encoders and bitrate mode a video encode asks Media3 for (ADR 2026-10.hmfe, the 2026-11-20 amendment,
 * #353): CBR before VBR, hardware before software, HEVC before H.264, and never an encoder Media3's filters would drop.
 */
class VideoEncoderChoiceTest {
    private fun encoder(
        name: String,
        hardware: Boolean = true,
        takesOutputSize: Boolean = true,
        cbr: Boolean = true,
        vbr: Boolean = true,
    ) = EncoderCandidate(name, hardware, takesOutputSize, cbr, vbr)

    /** AOSP's software HEVC encoder: CBR, but it stops at 512 × 512, so it never takes 480p. */
    private val aospHevc = encoder("c2.android.hevc.encoder", hardware = false, takesOutputSize = false)

    /** AOSP's software H.264 encoder: CBR, and it takes 480p. */
    private val aospAvc = encoder("c2.android.avc.encoder", hardware = false)

    @Test
    fun aPhoneWithAHardwareHevcEncoderInCbrSendsHevcInCbrOnThatEncoder() {
        // The shape of the seven Device Farm phones that passed on 2026-11-20 (Pixel 10, Galaxy S25 Ultra, …).
        val choice =
            VideoEncoderChoice.choose(
                hevc = listOf(encoder("c2.qti.hevc.encoder"), aospHevc),
                h264 = listOf(encoder("c2.qti.avc.encoder"), aospAvc),
            )
        assertEquals(EncoderChoice(VideoEncodePolicy.MIME_HEVC, listOf("c2.qti.hevc.encoder"), BitrateMode.CBR), choice)
    }

    @Test
    fun aPhoneWhoseHardwareOffersNoCbrEncodesInSoftwareCbrNotHardwareVbr() {
        // The Galaxy A51 and Redmi Note 13 Pro+ shape: asking the hardware for CBR left Media3 no encoder (#353), and
        // asking it for VBR lets Android 12+'s quality floor put a 61 s clip past the ceiling.
        val choice =
            VideoEncoderChoice.choose(
                hevc = listOf(encoder("c2.mtk.hevc.encoder", cbr = false), aospHevc),
                h264 = listOf(encoder("c2.mtk.avc.encoder", cbr = false), aospAvc),
            )
        assertEquals(EncoderChoice(VideoEncodePolicy.MIME_H264, listOf("c2.android.avc.encoder"), BitrateMode.CBR), choice)
    }

    @Test
    fun hardwareH264InCbrIsChosenOverHardwareHevcInVbr() {
        val choice =
            VideoEncoderChoice.choose(
                hevc = listOf(encoder("OMX.Exynos.HEVC.Encoder", cbr = false), aospHevc),
                h264 = listOf(encoder("OMX.Exynos.AVC.Encoder"), aospAvc),
            )
        assertEquals(EncoderChoice(VideoEncodePolicy.MIME_H264, listOf("OMX.Exynos.AVC.Encoder"), BitrateMode.CBR), choice)
    }

    @Test
    fun onlyTheHardwareEncodersThatOfferCbrAreOffered() {
        val choice =
            VideoEncoderChoice.choose(
                hevc = listOf(encoder("hw.hevc.a", cbr = false), encoder("hw.hevc.b"), encoder("hw.hevc.c")),
                h264 = listOf(aospAvc),
            )
        assertEquals(EncoderChoice(VideoEncodePolicy.MIME_HEVC, listOf("hw.hevc.b", "hw.hevc.c"), BitrateMode.CBR), choice)
    }

    @Test
    fun anEncoderThatCannotTakeTheOutputSizeIsNeverOffered() {
        val choice =
            VideoEncoderChoice.choose(
                hevc = listOf(encoder("hw.hevc.small", takesOutputSize = false), encoder("hw.hevc", cbr = false)),
                h264 = listOf(encoder("hw.avc", cbr = false)),
            )
        // The only CBR encoders cannot take 480p, so the encode is VBR on the hardware HEVC encoder that can.
        assertEquals(EncoderChoice(VideoEncodePolicy.MIME_HEVC, listOf("hw.hevc"), BitrateMode.VBR), choice)
    }

    @Test
    fun withNoEncoderThatTakesTheOutputSizeThereIsNoChoice() {
        assertNull(VideoEncoderChoice.choose(hevc = listOf(aospHevc), h264 = emptyList()))
        assertNull(VideoEncoderChoice.choose(hevc = emptyList(), h264 = emptyList()))
    }

    @Test
    fun everyOfferedEncoderPassesMediaThreesFiltersAndCbrIsNeverPassedOver() {
        // Every combination of two HEVC and two H.264 encoders, each hardware or not, taking the size or not, with
        // or without CBR and VBR: 2^16 phones.
        val flags = 4
        val perEncoder = 1 shl flags
        val names = listOf("hevc.0", "hevc.1", "avc.0", "avc.1")
        for (phone in 0 until (1 shl (flags * names.size))) {
            val all =
                names.mapIndexed { i, name ->
                    val bits = (phone shr (i * flags)) and (perEncoder - 1)
                    encoder(name, bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, bits and 8 != 0)
                }
            val hevc = all.take(2)
            val h264 = all.drop(2)
            val choice = VideoEncoderChoice.choose(hevc, h264)
            val sized = all.filter { it.takesOutputSize && (it.cbr || it.vbr) }
            if (choice == null) {
                assertTrue("phone $phone has a usable encoder but no choice", sized.isEmpty())
                continue
            }
            val pool = if (choice.mime == VideoEncodePolicy.MIME_HEVC) hevc else h264
            val offered = pool.filter { it.name in choice.encoders }
            assertEquals("phone $phone offers only its own encoders", choice.encoders.size, offered.size)
            for (it in offered) {
                assertTrue("phone $phone offers ${it.name}, which cannot take the size", it.takesOutputSize)
                val inMode = if (choice.mode == BitrateMode.CBR) it.cbr else it.vbr
                assertTrue("phone $phone offers ${it.name}, which lacks ${choice.mode}", inMode)
                assertEquals("phone $phone mixes hardware and software", offered.first().hardware, it.hardware)
            }
            if (sized.any { it.cbr }) assertEquals("phone $phone passed CBR over", BitrateMode.CBR, choice.mode)
            if (!offered.first().hardware) {
                val hardwareInMode = sized.any { it.hardware && (if (choice.mode == BitrateMode.CBR) it.cbr else it.vbr) }
                assertTrue("phone $phone chose software over hardware in ${choice.mode}", !hardwareInMode)
            }
        }
    }
}
