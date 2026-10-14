package expo.modules.speechrecognition

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import expo.modules.laylaaudio.LaylaAudioEngine
import java.io.File
import java.io.IOException

/** Native fan-out consumer. Pipe backpressure never blocks Layla's capture/VAD thread. */
internal class LaylaAudioInput(
    private val context: Context,
    outputPath: String?,
    onFailure: (Exception) -> Unit,
) {
    private val pipe = ParcelFileDescriptor.createPipe()
    val parcel: ParcelFileDescriptor = pipe[0]
    private val writer = pipe[1]
    private val pump = LaylaRecordingPump(
        context.cacheDir,
        outputPath,
        ::writeFully,
        { runCatching { writer.close() } },
        onFailure,
    )
    private var subscription: AutoCloseable? = null
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
        pump.start()
        try {
            subscription = LaylaAudioEngine.addMicConsumer { samples, rate ->
                if (rate != 16000) {
                    pump.fail(IOException("Layla microphone audio is not 16 kHz"))
                } else if (!pump.offer(samples)) {
                    pump.fail(IOException("Speech recognizer is not consuming Layla microphone audio"))
                }
            }
        } catch (error: Exception) {
            close(keepRecording = false) {}
            throw error
        }
    }

    /** Ends capture; queued PCM still drains, then the recognizer sees EOF (segmented sessions finish there). */
    fun finish() {
        pump.finishInput()
        subscription?.close()
        subscription = null
    }

    /**
     * Ends capture and the recognizer feed, then finishes the recording off the caller's thread.
     * [onClosed] gets the complete WAV (null without one, or when [keepRecording] is false).
     */
    fun close(keepRecording: Boolean, onClosed: (File?) -> Unit) {
        finish()
        pump.close(keepRecording) { file ->
            runCatching { parcel.close() }
            onClosed(file)
        }
    }

    private fun writeFully(bytes: ByteArray) {
        var offset = 0
        var lastProgress = SystemClock.elapsedRealtime()
        while (offset < bytes.size) {
            try {
                val written = Os.write(writer.fileDescriptor, bytes, offset, bytes.size - offset)
                if (written > 0) {
                    offset += written
                    lastProgress = SystemClock.elapsedRealtime()
                }
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN) throw IOException("Speech recognizer audio pipe closed", error)
                // Once stopped, a recognizer may stop reading for good: give up on it quickly so the
                // recording is not held back.
                val limit = if (pump.stopping) 250 else 2000
                if (SystemClock.elapsedRealtime() - lastProgress > limit) {
                    throw IOException("Speech recognizer did not read the supplied audio pipe", error)
                }
                Thread.sleep(5)
            }
        }
    }
}
