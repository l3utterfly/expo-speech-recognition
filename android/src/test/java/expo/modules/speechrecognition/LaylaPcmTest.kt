package expo.modules.speechrecognition

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class LaylaPcmTest {
    @Test
    fun `encodes signed samples and full scale in little endian order`() {
        assertArrayEquals(
            byteArrayOf(0, -128, 0, -64, 0, 0, 0, 64, -1, 127),
            laylaPcm16(floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)),
        )
    }

    @Test
    fun `clips overshoot and silences invalid samples instead of overflowing`() {
        assertArrayEquals(
            byteArrayOf(0, -128, -1, 127, 0, 0, 0, 0, 0, 0),
            laylaPcm16(floatArrayOf(-3f, 3f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)),
        )
    }
}
