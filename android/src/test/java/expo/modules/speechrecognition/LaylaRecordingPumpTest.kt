package expo.modules.speechrecognition

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LaylaRecordingPumpTest {
    @TempDir
    lateinit var tempDir: File

    private val outputPath get() = File(tempDir, "recording.wav").absolutePath

    private fun frames(count: Int) = List(count) { index -> FloatArray(160) { ((index * 160 + it) % 200 - 100) / 128f } }

    private fun pcm(frames: List<FloatArray>) =
        ByteArrayOutputStream().apply { frames.forEach { write(laylaPcm16(it)) } }.toByteArray()

    private class Closed {
        private val latch = CountDownLatch(1)
        private val calls = AtomicInteger()
        private val file = AtomicReference<File?>()
        val callback: (File?) -> Unit = {
            file.set(it)
            calls.incrementAndGet()
            latch.countDown()
        }

        fun await(): File? {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "recording was never reported")
            Thread.sleep(50) // a second report would arrive here
            assertEquals(1, calls.get())
            return file.get()
        }
    }

    private fun assertWav(file: File?, expected: ByteArray) {
        val bytes = file!!.readBytes()
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + expected.size, header.getInt(4))
        assertEquals(16_000, header.getInt(24))
        assertEquals(expected.size, header.getInt(40))
        assertArrayEquals(expected, bytes.copyOfRange(44, bytes.size))
    }

    @Test
    fun `keeps every captured frame when the recognizer stops reading at stop`() {
        val recognized = ByteArrayOutputStream()
        val recognizerClosed = AtomicInteger()
        val failures = AtomicInteger()
        lateinit var pump: LaylaRecordingPump
        pump = LaylaRecordingPump(tempDir, outputPath, { bytes ->
            if (pump.stopping) throw IOException("recognizer is done")
            recognized.write(bytes)
        }, { recognizerClosed.incrementAndGet() }, { failures.incrementAndGet() })
        val captured = frames(50)

        captured.forEach { assertTrue(pump.offer(it)) }
        pump.finishInput() // stop: the recognizer refuses the queued frames
        pump.start()
        val closed = Closed()
        pump.close(keep = true, closed.callback)

        assertWav(closed.await(), pcm(captured))
        assertEquals(1, recognizerClosed.get())
        assertEquals(0, failures.get())
    }

    @Test
    fun `drains frames still queued at stop to the recognizer and the recording`() {
        val recognized = ByteArrayOutputStream()
        val recognizerClosed = CountDownLatch(1)
        val pump = LaylaRecordingPump(tempDir, outputPath, { synchronized(recognized) { recognized.write(it) } },
            { recognizerClosed.countDown() }, { throw AssertionError(it) })
        val captured = frames(80)
        pump.start()
        captured.forEach { assertTrue(pump.offer(it)) }

        // Stop listening: what was captured still reaches the recognizer, then it sees EOF.
        pump.finishInput()
        assertTrue(pump.offer(FloatArray(160) { 1f }), "frames after stop are ignored, not failures")
        assertTrue(recognizerClosed.await(5, TimeUnit.SECONDS))
        assertArrayEquals(pcm(captured), synchronized(recognized) { recognized.toByteArray() })

        val closed = Closed()
        pump.close(keep = true, closed.callback)
        assertWav(closed.await(), pcm(captured))
    }

    @Test
    fun `a recognizer that stalls while listening fails the session but not the recording`() {
        val failure = AtomicReference<Exception?>()
        val pump = LaylaRecordingPump(tempDir, outputPath, { throw IOException("pipe full") }, {}, { failure.set(it) })
        val captured = frames(10)
        captured.forEach { pump.offer(it) }
        pump.start()

        val deadline = System.currentTimeMillis() + 5000
        while (failure.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(failure.get() is IOException)

        val closed = Closed()
        pump.close(keep = true, closed.callback)
        assertWav(closed.await(), pcm(captured))
    }

    @Test
    fun `a discarded recording leaves no file`() {
        val pump = LaylaRecordingPump(tempDir, outputPath, {}, {}, {})
        frames(20).forEach { pump.offer(it) }
        pump.start()
        val closed = Closed()
        pump.close(keep = false, closed.callback)

        assertNull(closed.await())
        assertFalse(File(outputPath).exists())
        assertEquals(emptyList<String>(), tempDir.list()!!.toList())
    }

    @Test
    fun `closing before the pump starts reports at once and ends the recognizer feed`() {
        val recognizerClosed = AtomicInteger()
        val pump = LaylaRecordingPump(tempDir, outputPath, {}, { recognizerClosed.incrementAndGet() }, {})
        val closed = Closed()
        pump.close(keep = true, closed.callback)

        assertNull(closed.await())
        assertEquals(1, recognizerClosed.get())
        pump.start() // a late start must not run the pump
        Thread.sleep(150)
        assertFalse(File(outputPath).exists())
    }

    @Test
    fun `without an output path the recognizer still gets everything and no file is written`() {
        val recognized = ByteArrayOutputStream()
        val recognizerClosed = CountDownLatch(1)
        val pump = LaylaRecordingPump(tempDir, null, { synchronized(recognized) { recognized.write(it) } },
            { recognizerClosed.countDown() }, {})
        val captured = frames(15)
        captured.forEach { pump.offer(it) }
        pump.start()
        pump.finishInput()
        assertTrue(recognizerClosed.await(5, TimeUnit.SECONDS))
        val closed = Closed()
        pump.close(keep = true, closed.callback)

        assertNull(closed.await())
        assertArrayEquals(pcm(captured), synchronized(recognized) { recognized.toByteArray() })
        assertEquals(emptyList<String>(), tempDir.list()!!.toList())
    }
}
