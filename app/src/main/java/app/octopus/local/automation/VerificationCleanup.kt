package app.octopus.local.automation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Diagnostics are best effort; a cancelled verification must always release its UI/session state. */
internal object VerificationCleanup {
    suspend fun finish(record:suspend ()->Unit,release:()->Unit)=withContext(NonCancellable) {
        try {record()} catch (_:Exception) { /* Recording failure never keeps the session busy. */ }
        release()
    }
}
