# Layla microphone integration

This fork depends on the sibling `layla-audio` Expo module on Android and iOS.
The default `microphoneSource: "system"` preserves upstream capture. Layla selects
`"layla-audio"` on iOS and Android 13+:

```ts
ExpoSpeechRecognitionModule.start({
  lang: "en-US",
  microphoneSource: "layla-audio",
  continuous: true,
  interimResults: true,
});
```

PCM stays native: one Layla mic produces 16 kHz mono float frames for STT and VAD.
Recognition does not create its own microphone recorder or reconfigure the iOS
audio session. `LaylaAudio.startCall(...)` enables call routing and platform echo
cancellation; TTS and indicator sounds must play through the Layla mixer for that
cancellation to have the correct output reference. Microphone subscription alone
does not enable call mode. Layla's `BargeInSession` keeps call mode active even if
the Silero model is absent, but VAD interruption requires that model.

## Platform behavior

- **Android 13+**: converts PCM to signed 16-bit little-endian on a worker and supplies
  a pipe through `RecognizerIntent.EXTRA_AUDIO_SOURCE`. Continuous recognition uses
  `EXTRA_SEGMENTED_SESSION` with that source. Capture offers a bounded queue without
  blocking the shared mic. Stop unsubscribes and closes the writer after draining.
  A stalled reader while listening produces `audio-capture` rather than blocking
  capture; after stop, a reader that stops reading only ends its own feed. These extras depend on the selected recognition
  service: a service can ignore the supplied source and open its own mic before
  the pipe stalls. Validate every supported service/device; API level alone is
  not proof of echo-safe recognition. There is no automatic fallback to the system
  mic when shared-source recognition fails.
- **Android 12 and below**: the app chooses `system`. Continuous listening renews
  system recognition after results and benign silence errors inside native code.
  This path cannot use Layla's mic or support shared-mic barge-in.
- **iOS**: feeds `SFSpeechAudioBufferRecognitionRequest` from the shared mic. A
  continuous listening session emits utterance finals and keeps capture subscribed
  across Apple task renewal. iOS 18+ uses the same speech-duration metadata boundary
  as upstream. Uncommitted partials have a native 1.5-second inactivity endpoint for
  older iOS and metadata gaps. Tasks also renew at 50 seconds, before Apple's
  documented one-minute limit. Up to five seconds of incoming PCM is buffered during
  finalization; a two-second deadline preserves the latest uncommitted transcript
  if Apple never delivers a final. Repeated boundary/task-final copies are deduplicated.
  Native task renewal is still necessary with `SFSpeechRecognizer`; this is not a
  SpeechAnalyzer backend or a guarantee of gapless recognition. Fatal permission,
  service, capture or network errors end the listening session.

Start replacement, stop and abort guard queued work and late callbacks so an older
recognizer cannot end a newer session. `end` represents the end of the listening
session, rather than each iOS utterance/task. The app no longer debounces iOS partials
or automatically restarts recognition from JS error events. Existing chat turn
handling can still explicitly stop listening while Layla replies.

`audioSource` cannot be combined with the shared mic. Android intent overrides for
the audio pipe or segmentation are rejected. `iosCategory` and
`iosVoiceProcessingEnabled` apply only to system capture; Layla owns those settings
when using the shared mic. Optional recording persists shared PCM as WAV (Android
16 kHz PCM16; iOS 16 kHz float32). iOS rejects different recording encodings/rates.

A recording's `audioend` carries its `uri` only once the WAV is complete, and it ends
where capture did: every frame captured before stop or abort is in the file. Each
frame is written to the file before it reaches the recognizer, so a recognizer that
stalls or closes its input never costs the recording. On Android the drain and header
run off the main thread; the session's `audioend` and `end`, and any `start` or stop
issued meanwhile, wait for them. A recording that cannot be finished within five
seconds (it never should: the queue holds two) ends the session with a `null` uri.
A failed disk write also reports `null` rather than a partial file. A start that
replaces a running session discards that session's recording, as it sends no `audioend`.
On iOS the file is written and closed on the session queue before `audioend`.

## Validation

From Layla's existing Android project:

```powershell
.\gradlew.bat :expo-speech-recognition:testDebugUnitTest
```

This compiles the integration and tests PCM clipping/encoding, stale callbacks, and
that a recording keeps every captured frame however the recognizer stops reading.
App Jest tests cover shared-source selection, continuous result delivery, permissions,
explicit stop, and call/VAD lifetimes. Pure Swift utterance tests can run on a Swift host:

```sh
swiftc ios/LaylaUtteranceState.swift tests/ios/main.swift -o /tmp/layla-utterance-tests
/tmp/layla-utterance-tests
```

Before release, rebuild the native app (and install updated pods on macOS), then check:

1. iOS: multiple utterances and repeated words, silence, and a listening session longer
   than a minute. Confirm one final per utterance and no session `end` during renewal.
2. Each supported Android 13+ recognition service: play TTS through Layla, stay silent,
   then speak over it. Confirm TTS is not transcribed, speech is transcribed, and VAD
   interruption works when installed. Repeat without the VAD model to check AEC alone.
3. Rapid start/stop/start, abort, locale changes, denied permissions, network failure,
   app backgrounding, calls/interruptions, and Bluetooth route changes. Confirm stop
   releases the recognition consumer and no old results arrive in a replacement session.
4. Recording enabled: verify the emitted WAV exists and has the documented format, and
   that stopping mid-speech keeps the audio up to the stop (its length matches the session).

The implementation was built and unit-tested on Android from Windows. The iOS
native sources and Swift tests require validation on macOS; no device recognition
or echo-cancellation test of this integration has been performed yet.
