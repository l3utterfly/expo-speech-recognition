import AVFoundation
import Foundation
import LaylaAudio
import Speech

/// One listening session, backed by multiple Apple recognition tasks when necessary.
/// All state and request writes live on queue; capture only offers bounded work.
/// The Layla engine owns the mic, audio session, routing and voice processing.
final class LaylaSpeechSession {
  private let queue = DispatchQueue(label: "LaylaSpeechRecognition")
  private let captureSlots = DispatchSemaphore(value: 100)
  private let overflowLock = NSLock()
  private var overflowReported = false
  private let emit: (String, [String: Any]?) -> Void
  private var options: SpeechRecognitionOptions?
  private var recognizer: SFSpeechRecognizer?
  private var subscription: LaylaAudioSubscription?
  private var request: SFSpeechAudioBufferRecognitionRequest?
  private var task: SFSpeechRecognitionTask?
  private var active = false
  private var stopping = false
  private var finishing = false
  private var sessionID = 0
  private var taskID = 0
  private var latestResult: SFSpeechRecognitionResult?
  private var utterance = LaylaUtteranceState()
  private var silenceTimer: DispatchWorkItem?
  private var taskTimer: DispatchWorkItem?
  private var finishTimer: DispatchWorkItem?
  private var pendingFrames: [[Float]] = []
  private var pendingSampleCount = 0
  private var audioFile: AVAudioFile?
  private var outputURL: URL?
  private var audioStarted = false
  private var audioEndTimestamp: Double?
  private var lastVolumeTime = Date.distantPast

  init(emit: @escaping (String, [String: Any]?) -> Void) { self.emit = emit }

  func getState() -> String {
    queue.sync { active ? (stopping ? "stopping" : "recognizing") : "inactive" }
  }

  func start(options: SpeechRecognitionOptions, recognizer: SFSpeechRecognizer) {
    queue.sync { [self] in
      endSession()
      self.options = options
      self.recognizer = recognizer
      active = true
      stopping = false
      sessionID += 1
      let currentSession = sessionID
      overflowLock.lock()
      overflowReported = false
      overflowLock.unlock()
      do {
        guard options.audioSource == nil else { throw RecognizerError.invalidAudioSource }
        if options.requiresOnDeviceRecognition && !recognizer.supportsOnDeviceRecognition {
          throw RecognizerError.recognizerIsUnavailable
        }
        try prepareRecording(options)
        try beginTask()
        subscription = try LaylaAudioEngine.shared.addMicConsumer { [weak self] samples, rate in
          self?.offer(samples, rate: rate, session: currentSession)
        }
        audioStarted = true
        emit("audiostart", ["uri": outputURL.map { $0.absoluteString as Any } ?? NSNull(), "timestamp": timestamp()])
        emit("start", nil)
      } catch {
        fail(error)
      }
    }
  }

  func stop() {
    queue.async { [self] in
      guard active, !stopping else { return }
      stopping = true
      stopCapture()
      if task == nil { endSession() } else { finishTask() }
    }
  }

  func abort() { queue.sync { [self] in endSession() } }

  private func offer(_ samples: [Float], rate: Int, session: Int) {
    guard captureSlots.wait(timeout: .now()) == .success else {
      overflowLock.lock()
      let report = !overflowReported
      overflowReported = true
      overflowLock.unlock()
      if report {
        queue.async { [weak self] in
          guard let self, self.active, self.sessionID == session else { return }
          self.fail(LaylaAudioError.device("Speech recognition fell behind microphone capture."))
        }
      }
      return
    }
    queue.async { [weak self] in
      defer { self?.captureSlots.signal() }
      guard let self, self.active, !self.stopping, self.sessionID == session else { return }
      guard rate == 16_000 else {
        self.fail(RecognizerError.invalidAudioSource)
        return
      }
      do {
        let buffer = try self.makeBuffer(samples)
        try self.audioFile?.write(from: buffer)
        self.reportVolume(samples)
        if self.finishing || self.request == nil {
          // Preserve speech during finalization/renewal, with a fixed five-second limit.
          guard self.pendingSampleCount + samples.count <= 80_000 else {
            throw LaylaAudioError.device("Speech recognition did not renew in time.")
          }
          self.pendingFrames.append(samples)
          self.pendingSampleCount += samples.count
        } else {
          self.request?.append(buffer)
        }
      } catch { self.fail(error) }
    }
  }

  private func beginTask() throws {
    guard active, !stopping, let options, let recognizer, recognizer.isAvailable else {
      throw RecognizerError.recognizerIsUnavailable
    }
    taskID += 1
    let currentTask = taskID
    let newRequest = SFSpeechAudioBufferRecognitionRequest()
    newRequest.shouldReportPartialResults = true // native utterance endpointing needs partials
    newRequest.requiresOnDeviceRecognition = options.requiresOnDeviceRecognition
    newRequest.contextualStrings = options.contextualStrings ?? []
    if let hint = options.iosTaskHint { newRequest.taskHint = hint.sfSpeechRecognitionTaskHint }
    if #available(iOS 16, *) { newRequest.addsPunctuation = options.addsPunctuation }
    request = newRequest
    finishing = false
    latestResult = nil
    utterance = LaylaUtteranceState()
    task = recognizer.recognitionTask(with: newRequest) { [weak self] result, error in
      self?.queue.async { [weak self] in
        guard let self, self.active, self.taskID == currentTask else { return }
        self.handle(result: result, error: error)
      }
    }
    for samples in pendingFrames { newRequest.append(try makeBuffer(samples)) }
    pendingFrames.removeAll(keepingCapacity: true)
    pendingSampleCount = 0
    let renewal = DispatchWorkItem { [weak self] in
      guard let self, self.active, self.taskID == currentTask else { return }
      self.finishTask()
    }
    taskTimer = renewal
    // Apple documents a one-minute task limit. Renew before it without reopening capture.
    queue.asyncAfter(deadline: .now() + 50, execute: renewal)
  }

  private func handle(result: SFSpeechRecognitionResult?, error: Error?) {
    if let result, !result.bestTranscription.formattedString.isEmpty {
      latestResult = result
      let text = result.bestTranscription.formattedString
      let wasPending = utterance.hasPending
      var boundary = result.isFinal
      if #available(iOS 18, *), !boundary {
        boundary = (result.speechRecognitionMetadata?.speechDuration ?? 0) > 0
      }
      let changed = utterance.observe(text: text, boundary: boundary)
      if boundary {
        if changed { emitResult(result, final: true); emit("speechend", nil) }
        silenceTimer?.cancel()
        if options?.continuous != true { stopping = true; stopCapture(); finishTask() }
      } else {
        if changed {
          if options?.interimResults == true { emitResult(result, final: false) }
          if !wasPending { emit("speechstart", nil) }
          scheduleSilenceTimer()
        }
      }
    }
    if result?.isFinal == true {
      completeTask()
    } else if let error {
      let native = error as NSError
      let noSpeech = native.domain == "kAFAssistantErrorDomain" && native.code == 1110
      let cancelled = native.domain == "kLSRErrorDomain" && native.code == 301
      if noSpeech || (finishing && cancelled) { completeTask() } else { fail(error) }
    }
  }

  private func scheduleSilenceTimer() {
    silenceTimer?.cancel()
    let currentTask = taskID
    let timer = DispatchWorkItem { [weak self] in
      guard let self, self.active, self.taskID == currentTask, self.utterance.hasPending else { return }
      // Older iOS versions do not produce per-utterance finals while a task runs.
      // End its audio, collect a final and renew natively; the microphone stays open.
      self.finishTask()
    }
    silenceTimer = timer
    queue.asyncAfter(deadline: .now() + 1.5, execute: timer)
  }

  private func finishTask() {
    guard active, !finishing else { return }
    finishing = true
    silenceTimer?.cancel()
    taskTimer?.cancel()
    request?.endAudio()
    task?.finish()
    let currentTask = taskID
    let timeout = DispatchWorkItem { [weak self] in
      guard let self, self.active, self.taskID == currentTask else { return }
      self.completeTask()
    }
    finishTimer = timeout
    // A stalled final response cannot leave the session accumulating unlimited audio.
    queue.asyncAfter(deadline: .now() + 2, execute: timeout)
  }

  private func completeTask() {
    if let result = latestResult, utterance.commitPending() {
      // Preserve the latest transcript if Apple ends without a final or finalization times out.
      emitResult(result, final: true)
      emit("speechend", nil)
    }
    silenceTimer?.cancel()
    taskTimer?.cancel()
    finishTimer?.cancel()
    taskID += 1 // rejects every late callback from this task
    task?.cancel()
    task = nil
    request = nil
    finishing = false
    if stopping || options?.continuous != true {
      endSession()
    } else {
      let currentSession = sessionID
      queue.asyncAfter(deadline: .now() + 0.25) { [weak self] in
        guard let self, self.active, !self.stopping, self.sessionID == currentSession else { return }
        do { try self.beginTask() } catch { self.fail(error) }
      }
    }
  }

  private func emitResult(_ result: SFSpeechRecognitionResult, final: Bool) {
    let alternatives = result.transcriptions.prefix(max(1, options?.maxAlternatives ?? 1)).map { transcription in
      let segments = transcription.segments.map { segment in
        Segment(startTimeMillis: segment.timestamp * 1000,
                endTimeMillis: (segment.timestamp + segment.duration) * 1000,
                segment: segment.substring, confidence: segment.confidence)
      }
      let confidence = segments.isEmpty ? 0 : segments.reduce(Float(0)) { $0 + $1.confidence } / Float(segments.count)
      return TranscriptionResult(transcript: transcription.formattedString, confidence: confidence, segments: segments).toDictionary()
    }
    emit("result", ["isFinal": final, "results": alternatives])
  }

  private func makeBuffer(_ samples: [Float]) throws -> AVAudioPCMBuffer {
    guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000,
                                    channels: 1, interleaved: false),
          let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
          let channel = buffer.floatChannelData?[0] else { throw RecognizerError.invalidAudioSource }
    buffer.frameLength = AVAudioFrameCount(samples.count)
    samples.withUnsafeBufferPointer { source in
      if let base = source.baseAddress { channel.update(from: base, count: source.count) }
    }
    return buffer
  }

  private func prepareRecording(_ options: SpeechRecognitionOptions) throws {
    guard let recording = options.recordingOptions, recording.persist else { return }
    guard recording.outputSampleRate == nil || recording.outputSampleRate == 16_000,
          recording.outputEncoding == nil || recording.outputEncoding == "pcmFormatFloat32" else {
      throw RecognizerError.invalidAudioSource
    }
    let directory: URL
    if let path = recording.outputDirectory {
      if path.hasPrefix("file://") {
        guard let url = URL(string: path) else { throw RecognizerError.invalidAudioSource }
        directory = url
      } else { directory = URL(fileURLWithPath: path, isDirectory: true) }
    } else { directory = FileManager.default.temporaryDirectory }
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let url = directory.appendingPathComponent(recording.outputFileName ?? "recording_\(UUID().uuidString).wav")
    let format = AVAudioFormat(standardFormatWithSampleRate: 16_000, channels: 1)!
    audioFile = try AVAudioFile(forWriting: url, settings: format.settings)
    outputURL = url
  }

  private func reportVolume(_ samples: [Float]) {
    guard options?.volumeChangeEventOptions?.enabled == true, !samples.isEmpty else { return }
    let now = Date()
    let interval = Double(options?.volumeChangeEventOptions?.intervalMillis ?? 100) / 1000
    guard now.timeIntervalSince(lastVolumeTime) >= interval else { return }
    lastVolumeTime = now
    let rms = sqrt(samples.reduce(Float(0)) { $0 + $1 * $1 } / Float(samples.count))
    emit("volumechange", ["value": max(-2, min(10, (20 * log10(max(rms, 0.00001)) + 60) / 6))])
  }

  private func fail(_ error: Error) {
    let code: String
    if let recognition = error as? RecognizerError {
      code = recognition == .recognizerIsUnavailable ? "service-not-allowed" : "audio-capture"
    } else if let audio = error as? LaylaAudioError {
      code = audio.code == "ERR_PERMISSION" ? "not-allowed" : "audio-capture"
    } else {
      code = "network"
    }
    emit("error", ["error": code, "message": String(describing: error), "code": (error as NSError).code])
    endSession()
  }

  private func stopCapture() {
    subscription?.cancel()
    subscription = nil
    if audioEndTimestamp == nil { audioEndTimestamp = timestamp() }
  }

  private func endSession() {
    guard active else { return }
    active = false
    sessionID += 1
    taskID += 1
    silenceTimer?.cancel()
    taskTimer?.cancel()
    finishTimer?.cancel()
    task?.cancel()
    task = nil
    request = nil
    stopCapture()
    audioFile = nil // closes the recorded file before audioend
    pendingFrames.removeAll()
    pendingSampleCount = 0
    if audioStarted {
      emit("audioend", ["uri": outputURL.map { $0.absoluteString as Any } ?? NSNull(), "timestamp": audioEndTimestamp ?? timestamp()])
    }
    emit("end", nil)
    outputURL = nil
    audioStarted = false
    audioEndTimestamp = nil
    stopping = false
    finishing = false
  }

  private func timestamp() -> Double { Date().timeIntervalSince1970 * 1000 }
}
