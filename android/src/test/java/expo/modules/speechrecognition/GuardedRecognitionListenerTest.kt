package expo.modules.speechrecognition

import android.os.Bundle
import android.speech.RecognitionListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuardedRecognitionListenerTest {
    @Test
    fun `late result and error callbacks cannot affect a replacement session`() {
        val events = mutableListOf<String>()
        val delegate = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { events.add("start") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { events.add("error") }
            override fun onResults(results: Bundle?) { events.add("final") }
            override fun onPartialResults(partialResults: Bundle?) { events.add("partial") }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onEndOfSegmentedSession() { events.add("end") }
        }
        var session = 1
        val old = GuardedRecognitionListener(delegate) { session == 1 }
        old.onReadyForSpeech(null)
        old.onPartialResults(null)
        session = 2
        old.onResults(null)
        old.onError(3)
        old.onEndOfSegmentedSession()
        val replacement = GuardedRecognitionListener(delegate) { session == 2 }
        replacement.onResults(null)
        assertEquals(listOf("start", "partial", "final"), events)
    }
}
