package app.gamenative.library.discovery

import app.gamenative.data.canonical.CanonicalGameId
import app.gamenative.db.dao.CanonicalGameDao
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A trusted canonical Steam mapping whose aggregate review count may be cached. */
data class SteamPopularityTarget(
    val canonicalId: CanonicalGameId,
    val steamAppId: Int,
    val steamReviewCount: Int?,
)

data class SteamPopularityEnrichmentProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val isRunning: Boolean = false,
)

@Singleton
class SteamPopularityEnricher @Inject constructor(
    private val source: SteamReviewSummarySource,
    private val canonicalGameDao: CanonicalGameDao,
) {
    suspend fun enrich(
        visibleTargets: List<SteamPopularityTarget>,
        allTargets: List<SteamPopularityTarget>,
        onProgress: (SteamPopularityEnrichmentProgress) -> Unit = {},
    ): SteamPopularityEnrichmentProgress {
        val targets = orderedNullTargets(visibleTargets, allTargets)
        if (targets.isEmpty()) {
            return SteamPopularityEnrichmentProgress().also(onProgress)
        }

        val nextIndex = AtomicInteger(0)
        val progressLock = Mutex()
        var completed = 0
        var failed = 0
        onProgress(SteamPopularityEnrichmentProgress(targets.size, 0, 0, true))
        coroutineScope {
            repeat(minOf(MAX_CONCURRENCY, targets.size)) {
                launch {
                    while (true) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= targets.size) break
                        val target = targets[index]
                        val didFail = try {
                            val summary = source.fetch(target.steamAppId)
                            if (summary.totalReviews < 0) {
                                true
                            } else {
                                canonicalGameDao.updateSteamReviewCountIfMissing(
                                    canonicalId = target.canonicalId.value,
                                    steamAppId = target.steamAppId,
                                    totalReviews = summary.totalReviews.toLong(),
                                )
                                false
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            true
                        }
                        progressLock.withLock {
                            completed += 1
                            if (didFail) failed += 1
                            onProgress(
                                SteamPopularityEnrichmentProgress(
                                    total = targets.size,
                                    completed = completed,
                                    failed = failed,
                                    isRunning = completed < targets.size,
                                ),
                            )
                        }
                    }
                }
            }
        }
        return SteamPopularityEnrichmentProgress(targets.size, completed, failed, false)
    }

    private fun orderedNullTargets(
        visibleTargets: List<SteamPopularityTarget>,
        allTargets: List<SteamPopularityTarget>,
    ): List<SteamPopularityTarget> = sequenceOf(visibleTargets, allTargets)
        .flatMap(List<SteamPopularityTarget>::asSequence)
        .filter { target -> target.steamAppId > 0 && target.steamReviewCount == null }
        .distinctBy { target -> target.canonicalId }
        .toList()

    private companion object {
        const val MAX_CONCURRENCY = 4
    }
}
