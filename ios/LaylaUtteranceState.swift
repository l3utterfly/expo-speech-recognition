/// Apple may repeat a boundary result and then send a task-final copy. Commit once,
/// while allowing a new partial with identical text (e.g. the user says "yes" twice).
struct LaylaUtteranceState {
  private var latestText = ""
  private var committedText: String?
  private(set) var hasPending = false

  mutating func observe(text: String, boundary: Bool) -> Bool {
    guard !text.isEmpty else { return false }
    let changed = text != latestText || (!boundary && !hasPending)
    latestText = text
    if boundary {
      let commit = hasPending || committedText != text
      hasPending = false
      committedText = text
      return commit
    }
    hasPending = true
    return changed
  }

  mutating func commitPending() -> Bool {
    guard hasPending else { return false }
    hasPending = false
    committedText = latestText
    return true
  }
}
