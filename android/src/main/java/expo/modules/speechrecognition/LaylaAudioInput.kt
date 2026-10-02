package expo.modules.speechrecognition

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import expo.modules.laylaaudio.LaylaAudioEngine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Native fan-out consumer. Pipe backpressure never blocks Layla's capture/VAD thread. */
internal class LaylaAudioInput(
    private val context: Context,
    private val outputPath: String?,
    private val onFailure: (Exception) -> Unit,
) {
    private val pipe = ParcelFileDescriptor.createPipe()
    val parcel: ParcelFileDescriptor = pipe[0]
    private val writer = pipe[1]
    private val frames = ArrayBlockingQueue<FloatArray>(100) // about two seconds
    private val running = AtomicBoolean(false)
    private val accepting = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private var subscription: AutoCloseable? = null
    private var worker: Thread? = null
    @Volatile var outputFile: File? = null
        private set
    val outputFileUri: String? = outputPath?.let { "file://$it" }

    init {
        // A service may ignore EXTRA_AUDIO_SOURCE entirely. Nonblocking writes
        // let us detect that failure and stop instead of hanging on a full pipe.
        try {
            val flags = Os.fcntlInt(writer.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(writer.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        } catch (error: Exception) {
            parcel.close()
            writer.close()
            throw error
        }
    }

    fun start() {
        LaylaAudioEngine.initialize(context)
        running.set(true)
        accepting.set(true)
        worker = thread(name = "LaylaRecognitionPipe") { pump() }
        try {
            subscription = LaylaAudioEngine.addMicConsumer { samples, rate ->
                if (accepting.get()) {
                    if (rate != 16000 || !frames.offer(samples)) {
                        fail(IOException("Speech recognizer is not consuming Layla microphone audio"))
                    }
                }
            }
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    /** Drain queued PCM and close the write end: segmented recognition finishes at EOF. */
    fun finish() {
        accepting.set(false)
        subscription?.close()
        subscription = null
    }

    fun stop() {
        finish()
        running.set(false)
        worker?.join(1000)
        runCatching { writer.close() }
        runCatching { parcel.close() }
        worker = null
        frames.clear()
    }

    private fun pump() {
        var pcmFile: File? = null
        try {
            pcmFile = outputPath?.let { File.createTempFile("layla-recognition-", ".pcm", context.cacheDir) }
            val fileStream = pcmFile?.let { FileOutputStream(it) }
            try {
                while (running.get() && (accepting.get() || frames.isNotEmpty())) {
                    val samples = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val bytes = laylaPcm16(samples)
                    writeFully(bytes)
                    fileStream?.write(bytes)
                }
            } finally {
                fileStream?.close()
            }
            if (outputPath != null && pcmFile != null) {
                outputFile = ExpoAudioRecorder.appendWavHeader(outputPath, pcmFile, 16000)
            }
        } catch (error: Exception) {
            if (running.get()) fail(error)
        } finally {
            runCatching { writer.close() }
            pcmFile?.delete()
        }
    }

    private fun writeFully(bytes: ByteArray) {
        var offset = 0
        var lastProgress = SystemClock.elapsedRealtime()
        while (offset < bytes.size && running.get()) {
            try {
                val written = Os.write(writer.fileDescriptor, bytes, offset, bytes.size - offset)
                if (written > 0) {
                    offset += written
                    lastProgress = SystemClock.elapsedRealtime()
                }
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN) throw error
                if (SystemClock.elapsedRealtime() - lastProgress > 2000) {
                    throw IOException("Speech recognizer did not read the supplied audio pipe", error)
                }
                Thread.sleep(5)
            }
        }
    }

    private fun fail(error: Exception) {
        if (failed.compareAndSet(false, true)) {
            accepting.set(false)
            onFailure(error)
        }
    }
}
