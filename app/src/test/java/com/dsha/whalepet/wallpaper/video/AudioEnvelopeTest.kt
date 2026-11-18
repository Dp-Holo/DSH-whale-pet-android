package com.dsha.whalepet.wallpaper.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioEnvelopeTest {

    @Test
    fun parsesAndInterpolates() {
        val envelope = requireNotNull(
            AudioEnvelope.parse("""{"stepMs":50,"rms":[0.0,0.5,1.0]}""")
        )
        assertEquals(0f, envelope.sample(0), 0.001f)
        assertEquals(0.25f, envelope.sample(25), 0.001f)
        assertEquals(0.5f, envelope.sample(50), 0.001f)
        // 超出末尾时保持最后一个值（视频循环前不会突然黑一下）
        assertEquals(1.0f, envelope.sample(10_000), 0.001f)
    }

    @Test
    fun clampsToUnitRange() {
        val envelope = requireNotNull(
            AudioEnvelope.parse("""{"stepMs":50,"rms":[-1.0,2.0]}""")
        )
        assertEquals(0f, envelope.sample(0), 0.001f)
        assertEquals(1f, envelope.sample(50), 0.001f)
    }

    @Test
    fun returnsNullOnBadJson() {
        assertNull(AudioEnvelope.parse("not json"))
        assertNull(AudioEnvelope.parse("""{"stepMs":50}"""))
    }
}
