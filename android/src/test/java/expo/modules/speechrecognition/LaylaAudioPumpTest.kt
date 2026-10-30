package expo.modules.speechrecognition

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaylaAudioPumpTest {
    private fun frames(count: Int) = List(count) { index -> FloatArray(160) { ((index * 160 + it) % 200 - 100) / 128f } }

    private fun pcm(frames: List<FloatArray>) =
        ByteArrayOutputStream().apply { frames.forEach { write(laylaPcm16(it)) } }.toByteArray()

    @Test
    fun `drains frames still queued at stop to the recognizer, then ends its input`() {
        val recognized = ByteArrayOutputStream()
        val recognizerClosed = CountDownLatch(1)
        val pump = LaylaAudioPump({ synchronized(recognized) { recognized.write(it) } },
            { recognizerClosed.countDown() }, { throw AssertionError(it) })
        val captured = frames(80)
        pump.start()
        captured.forEach { assertTrue(pump.offer(it)) }

        // Stop listening: what was captured still reaches the recognizer, then it sees EOF.
        pump.finishInput()
        assertTrue(pump.offer(FloatArray(160) { 1f }), "frames after stop are ignored, not failures")
        assertTrue(recognizerClosed.await(5, TimeUnit.SECONDS))
        assertArrayEquals(pcm(captured), synchronized(recognized) { recognized.toByteArray() })
    }

    @Test
    fun `a recognizer that stops reading at stop has finished, not failed`() {
        val recognizerClosed = CountDownLatch(1)
        val failures = AtomicInteger()
        lateinit var pump: LaylaAudioPump
        pump = LaylaAudioPump({ if (pump.stopping) throw IOException("recognizer is done") },
            { recognizerClosed.countDown() }, { failures.incrementAndGet() })
        frames(50).forEach { assertTrue(pump.offer(it)) }
        pump.finishInput()
        pump.start()

        assertTrue(recognizerClosed.await(5, TimeUnit.SECONDS))
        assertEquals(0, failures.get())
    }

    @Test
    fun `a recognizer that stalls while listening fails the session`() {
        val failure = AtomicReference<Exception?>()
        val pump = LaylaAudioPump({ throw IOException("pipe full") }, {}, { failure.set(it) })
        frames(10).forEach { pump.offer(it) }
        pump.start()

        val deadline = System.currentTimeMillis() + 5000
        while (failure.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(failure.get() is IOException)
        assertTrue(pump.offer(FloatArray(160)), "a failed feed ignores later frames")
    }

    @Test
    fun `closing drops what is queued and ends the recognizer feed once`() {
        val fed = AtomicInteger()
        val recognizerClosed = AtomicInteger()
        val pump = LaylaAudioPump({ fed.incrementAndGet() }, { recognizerClosed.incrementAndGet() }, {})
        frames(100).forEach { pump.offer(it) }
        pump.close()
        pump.start() // a late start must not run the pump
        Thread.sleep(150)

        assertEquals(0, fed.get())
        assertEquals(1, recognizerClosed.get())
    }
}
