package expo.modules.speechrecognition

/** Converts normalized mono floats to raw signed 16-bit little-endian PCM. */
internal fun laylaPcm16(samples: FloatArray): ByteArray {
    val bytes = ByteArray(samples.size * 2)
    samples.forEachIndexed { index, sample ->
        val value = if (sample.isFinite()) {
            (sample.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767)
        } else {
            0
        }
        bytes[index * 2] = value.toByte()
        bytes[index * 2 + 1] = (value shr 8).toByte()
    }
    return bytes
}
