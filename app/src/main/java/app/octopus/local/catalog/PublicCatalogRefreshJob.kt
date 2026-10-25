package app.octopus.local.catalog

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.octopus.local.OctopusApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** OS schedules only a public catalog refresh. Never starts provider UI or accessibility. */
class PublicCatalogRefreshJob : JobService() {
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val graph = (application as OctopusApplication).graph
        running = graph.scope.launch {
            try {
                withTimeout(60_000) {
                    graph.catalogReady.first { it }
                    val area = graph.settingsStore.flow.first().catalogArea ?: return@withTimeout
                    graph.publicCatalog.state.first { it.area == area }
                    if (!graph.catalog.value.busy && !graph.catalog.value.comparisonRunning)
                        graph.publicCatalog.refresh(force = false)?.join()
                }
            } finally { jobFinished(params,false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); running = null; return true }
    companion object {
        private const val ID = 40721
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(ID) != null) return
            scheduler.schedule(JobInfo.Builder(ID, ComponentName(context,PublicCatalogRefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(30 * 60_000L).build())
        }
        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(ID) }
    }
}
