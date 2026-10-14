package expo.modules.speechrecognition

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Drains captured microphone frames to the recognizer and, optionally, a WAV recording.
 *
 * The recording never depends on the recognizer. Every frame reaches the file before the
 * recognizer, and a recognizer that stops reading once it has been told to stop only ends its
 * own feed. Stopping drains every captured frame before the WAV is finished, so a recording
 * ends where capture did, and [close] reports the file only once it is complete.
 */
internal class LaylaRecordingPump(
    private val tempDirectory: File,
    private val outputPath: String?,
    /** Writes one chunk to the recognizer; throws [IOException] if it stops reading. */
    private val toRecognizer: (ByteArray) -> Unit,
    /** Ends the recognizer's input (EOF). Called once, on the pump thread. */
    private val closeRecognizer: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val frames = ArrayBlockingQueue<FloatArray>(100) // about two seconds
    private val accepting = AtomicBoolean(true)
    private val feedingRecognizer = AtomicBoolean(true)
    private val recognizerEnded = AtomicBoolean(false)
    private val keepRecording = AtomicBoolean(true)
    private val failed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val worker = thread(start = false, name = "LaylaRecognitionPipe") { run() }

    private val lock = Any()
    private var finished = false
    private var recording: File? = null
    private var onClosed: ((File?) -> Unit)? = null

    /** True once input has ended; the recognizer is then only fed what was already captured. */
    val stopping: Boolean get() = !accepting.get()

    fun start() {
        if (started.compareAndSet(false, true)) worker.start()
    }

    /** Queues one capture frame. False only when capture has outrun the pump. */
    fun offer(samples: FloatArray): Boolean = !accepting.get() || frames.offer(samples)

    /** Ends input: what is already queued still reaches the recognizer and the recording. */
    fun finishInput() {
        accepting.set(false)
    }

    /**
     * Ends input and the recognizer feed, drains the queue into the recording and reports it.
     * Call once. [onClosed] runs exactly once, on the pump thread (or the caller's, if the
     * pump already finished or never ran), with the finished WAV, or null when nothing was kept.
     */
    fun close(keep: Boolean, onClosed: (File?) -> Unit) {
        if (!keep) {
            keepRecording.set(false)
            frames.clear()
        }
        finishInput()
        feedingRecognizer.set(false)
        if (started.compareAndSet(false, true)) {
            endRecognizer()
            complete(null)
        }
        val ready = synchronized(lock) {
            this.onClosed = onClosed
            finished
        }
        if (ready) onClosed(deliver())
    }

    fun fail(error: Exception) {
        if (failed.compareAndSet(false, true)) {
            accepting.set(false)
            onFailure(error)
        }
    }

    private fun run() {
        var pcm: File? = null
        var output: File? = null
        try {
            pcm = outputPath?.let { File.createTempFile("layla-recognition-", ".pcm", tempDirectory) }
            val stream = pcm?.let { FileOutputStream(it) }
            stream.use {
                while (accepting.get() || frames.isNotEmpty()) {
                    val samples = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val bytes = laylaPcm16(samples)
                    stream?.write(bytes)
                    if (feedingRecognizer.get()) feed(bytes)
                }
            }
            endRecognizer()
            if (outputPath != null && pcm != null && keepRecording.get()) {
                output = ExpoAudioRecorder.appendWavHeader(outputPath, pcm, 16000)
            }
        } catch (error: Exception) {
            // The recording itself failed (disk, interrupt): report it and keep nothing partial.
            fail(error)
            output = null
        } finally {
            endRecognizer()
            pcm?.delete()
            complete(output)
        }
    }

    private fun feed(bytes: ByteArray) {
        try {
            toRecognizer(bytes)
        } catch (error: IOException) {
            endRecognizer()
            // While listening, a recognizer that is not reading is a failure. After stop it has
            // simply finished; the rest of the capture still belongs in the recording.
            if (!stopping) fail(error)
        }
    }

    private fun endRecognizer() {
        feedingRecognizer.set(false)
        if (recognizerEnded.compareAndSet(false, true)) runCatching { closeRecognizer() }
    }

    private fun complete(file: File?) {
        val callback = synchronized(lock) {
            if (finished) return
            finished = true
            recording = file
            onClosed
        }
        callback?.invoke(deliver())
    }

    private fun deliver(): File? {
        val file = synchronized(lock) { recording }
        if (file != null && !keepRecording.get()) {
            file.delete()
            return null
        }
        return file
    }
}
