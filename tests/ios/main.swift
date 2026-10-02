// Run from the fork root on a host with Swift:
// swiftc ios/LaylaUtteranceState.swift tests/ios/main.swift -o /tmp/layla-utterance-tests
// /tmp/layla-utterance-tests
var state = LaylaUtteranceState()
assert(state.observe(text: "yes", boundary: false))
assert(state.observe(text: "yes", boundary: true))
assert(!state.observe(text: "yes", boundary: true)) // metadata duplicate
assert(!state.commitPending()) // task-final copy / renewal does not repeat it
assert(state.observe(text: "yes", boundary: false)) // user repeats the same utterance
assert(state.observe(text: "yes", boundary: true))
assert(state.observe(text: "next utterance", boundary: false))
assert(!state.observe(text: "next utterance", boundary: false)) // unchanged partial
assert(state.commitPending()) // finalization timeout preserves pending text
assert(!state.commitPending())
assert(!state.observe(text: "", boundary: true))
print("Layla utterance lifecycle tests passed")
