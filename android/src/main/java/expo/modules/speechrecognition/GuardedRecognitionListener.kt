package expo.modules.speechrecognition

import android.os.Bundle
import android.speech.RecognitionListener

/** A destroyed recognizer can still have callbacks queued on the main looper. */
internal class GuardedRecognitionListener(
    private val delegate: RecognitionListener,
    private val isCurrent: () -> Boolean,
) : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) { if (isCurrent()) delegate.onReadyForSpeech(params) }
    override fun onBeginningOfSpeech() { if (isCurrent()) delegate.onBeginningOfSpeech() }
    override fun onRmsChanged(rmsdB: Float) { if (isCurrent()) delegate.onRmsChanged(rmsdB) }
    override fun onBufferReceived(buffer: ByteArray?) { if (isCurrent()) delegate.onBufferReceived(buffer) }
    override fun onEndOfSpeech() { if (isCurrent()) delegate.onEndOfSpeech() }
    override fun onError(error: Int) { if (isCurrent()) delegate.onError(error) }
    override fun onResults(results: Bundle?) { if (isCurrent()) delegate.onResults(results) }
    override fun onPartialResults(partialResults: Bundle?) { if (isCurrent()) delegate.onPartialResults(partialResults) }
    override fun onEvent(eventType: Int, params: Bundle?) { if (isCurrent()) delegate.onEvent(eventType, params) }
    override fun onSegmentResults(segmentResults: Bundle) { if (isCurrent()) delegate.onSegmentResults(segmentResults) }
    override fun onEndOfSegmentedSession() { if (isCurrent()) delegate.onEndOfSegmentedSession() }
    override fun onLanguageDetection(results: Bundle) { if (isCurrent()) delegate.onLanguageDetection(results) }
}
