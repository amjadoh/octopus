package app.octopus.local.diagnostics

/** The exception carries sanitized structure only; its message never includes screen values. */
class CatalogStoppedException(val evidence: CatalogStopEvidence) : IllegalStateException(
    when (evidence.reason) {
        CatalogStopReason.FINANCIAL_ELEMENT ->
            "توقفت القراءة عند عنصر مالي غير مدعوم؛ لم يُؤكد طلب أو دفع."
        CatalogStopReason.FINANCIAL_CONTROL_ON_UNKNOWN_SCREEN ->
            "زر مالي في شاشة غير معروفة؛ توقفت القراءة ولم يُؤكد طلب أو دفع."
    }
)

/**
 * A session-local stop latch. Catching an exception must not turn a financial stop into permission
 * to retry, navigate elsewhere, or fall back to a different reading path. There is intentionally no
 * reset method: only a newly requested session gets a new state. The first stop remains the cause.
 */
class CatalogStopState {
    @Volatile private var firstEvidence: CatalogStopEvidence? = null
    val evidence: CatalogStopEvidence? get() = firstEvidence

    @Synchronized
    fun stop(evidence: CatalogStopEvidence): Nothing {
        if (firstEvidence == null) firstEvidence = evidence
        throw CatalogStoppedException(checkNotNull(firstEvidence))
    }

    fun throwIfStopped() {
        firstEvidence?.let { throw CatalogStoppedException(it) }
    }
}
