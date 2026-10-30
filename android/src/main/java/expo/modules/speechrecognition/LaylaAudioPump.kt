package expo.modules.speechrecognition

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Drains captured microphone frames to the recognizer on its own thread, so the shared mic's
 * capture thread never waits for the recognizer.
 *
 * Ending input (stop) still delivers every frame already captured, then EOF: segmented recognition
 * finishes there. A recognizer that stops reading once it has been told to stop has simply
 * finished; while listening, one that stops reading is a failure. Recordings are layla-audio's
 * (`startRecording`), not this feed's.
 */
internal class LaylaAudioPump(
    /** Writes one chunk to the recognizer; throws [IOException] if it stops reading. */
    private val toRecognizer: (ByteArray) -> Unit,
    /** Ends the recognizer's input (EOF). Called once, on the pump thread (or by [close]). */
    private val closeRecognizer: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val frames = ArrayBlockingQueue<FloatArray>(100) // about two seconds
    private val accepting = AtomicBoolean(true)
    private val feeding = AtomicBoolean(true)
    private val recognizerEnded = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val worker = thread(start = false, name = "LaylaRecognitionPipe") { run() }

    /** True once input has ended; the recognizer is then only fed what was already captured. */
    val stopping: Boolean get() = !accepting.get()

    fun start() {
        if (started.compareAndSet(false, true)) worker.start()
    }

    /** Queues one capture frame. False only when capture has outrun the pump. */
    fun offer(samples: FloatArray): Boolean = !accepting.get() || frames.offer(samples)

    /** Ends input: what is already queued still reaches the recognizer, then EOF. */
    fun finishInput() {
        accepting.set(false)
    }

    /** Ends input and the recognizer feed now: queued frames are dropped. */
    fun close() {
        finishInput()
        feeding.set(false)
        frames.clear()
        if (started.compareAndSet(false, true)) endRecognizer()
    }

    fun fail(error: Exception) {
        if (failed.compareAndSet(false, true)) {
            accepting.set(false)
            onFailure(error)
        }
    }

    private fun run() {
        try {
            while (feeding.get() && (accepting.get() || frames.isNotEmpty())) {
                val samples = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                feed(laylaPcm16(samples))
            }
        } catch (error: InterruptedException) {
            // ended
        } finally {
            endRecognizer()
        }
    }

    private fun feed(bytes: ByteArray) {
        try {
            toRecognizer(bytes)
        } catch (error: IOException) {
            feeding.set(false)
            if (!stopping) fail(error)
        }
    }

    private fun endRecognizer() {
        feeding.set(false)
        if (recognizerEnded.compareAndSet(false, true)) runCatching { closeRecognizer() }
    }
}
