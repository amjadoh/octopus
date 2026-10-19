package app.octopus.local.diagnostics

/** Accepts only the already-redacted output of InspectionReader, never provider raw text. */
data class SessionEvidence(
    val stage: String,
    val nodesJson: String,
    val nodeCount: Int,
    val truncated: Boolean,
    val capturedAtMillis: Long,
)

/** Bounded, foreground-session-only capture. No monitoring service and no automatic upload. */
class SessionEvidenceBuffer(private val stableMillis: Long = 650, private val limit: Int = 6) {
    private var candidate: SessionEvidence? = null
    private var since = 0L
    private val frames = linkedMapOf<String, SessionEvidence>()

    init { require(stableMillis >= 0 && limit in 1..6) }

    fun observe(value: SessionEvidence, elapsedMillis: Long) {
        if (value.nodeCount !in 1..400 || value.nodesJson.length > 131_072 ||
            !value.stage.matches(Regex("AUTO_[A-Z_]{1,64}"))) { resetPending(); return }
        val old = candidate
        if (old == null || old.stage != value.stage || old.nodesJson != value.nodesJson ||
            old.truncated != value.truncated || old.nodeCount != value.nodeCount || elapsedMillis < since) {
            candidate = value
            since = elapsedMillis
            return
        }
        if (elapsedMillis - since < stableMillis) return
        // One most recent stable view per stage; never an unbounded screen history.
        frames.remove(value.stage)
        frames[value.stage] = value
        while (frames.size > limit) frames.remove(frames.keys.first())
    }

    fun resetPending() { candidate = null; since = 0L }
    fun drain(): List<SessionEvidence> = frames.values.toList().also { clear() }
    fun clear() { resetPending(); frames.clear() }
}
