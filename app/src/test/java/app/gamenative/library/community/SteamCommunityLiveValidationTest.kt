package app.gamenative.library.community

import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SteamCommunityLiveValidationTest {
    private val reviews by lazy { SteamReviewPageProvider() }
    private val discussions by lazy { SteamDiscussionProvider() }

    @Test
    fun threeTitleMultiPageProductionProvidersMatchValidatedPoc() = runTest(timeout = 10.minutes) {
        requireLiveValidation()

        THREE_TITLE_TARGETS.forEachIndexed { targetIndex, steamAppId ->
            validateReviews(steamAppId, pageCount = 3)
            validateDiscussions(
                steamAppId,
                listingPageCount = 3,
                threadPageCount = 2,
                targetIndex = targetIndex,
            )
        }
    }

    @Test
    fun tenTitleDiscussionBreadthMatchesValidatedPoc() = runTest(timeout = 20.minutes) {
        requireLiveValidation()

        TEN_TITLE_TARGETS.forEachIndexed { targetIndex, steamAppId ->
            validateReviews(steamAppId, pageCount = 1)
            validateDiscussions(
                steamAppId,
                listingPageCount = 10,
                threadPageCount = 3,
                targetIndex = targetIndex,
            )
        }
    }

    private suspend fun validateReviews(steamAppId: Int, pageCount: Int) {
        var cursor: String? = null
        val identities = mutableSetOf<String>()
        repeat(pageCount) { pageIndex ->
            val page = reviews.fetch(
                steamAppId = steamAppId,
                query = SteamReviewQuery(language = SteamReviewLanguage.ALL),
                cursor = cursor,
            )
            assertTrue("review_page_empty", page.reviews.isNotEmpty())
            assertTrue(
                "review_identity_duplicate",
                page.reviews.all { review -> identities.add(review.recommendationId) },
            )
            cursor = page.nextCursor
            if (pageIndex < pageCount - 1) {
                assertNotNull("review_continuation_missing", cursor)
            }
        }
    }

    private suspend fun validateDiscussions(
        steamAppId: Int,
        listingPageCount: Int,
        threadPageCount: Int,
        targetIndex: Int,
    ) {
        var listingRoute: String? = null
        val listingThreads = mutableListOf<SteamDiscussionSummary>()
        val threadIdentities = mutableSetOf<String>()
        repeat(listingPageCount) { pageIndex ->
            val listing = try {
                discussions.fetchListing(steamAppId, listingRoute)
            } catch (error: IOException) {
                throw AssertionError(
                    "discussion_listing_unavailable_target_${targetIndex}_page_$pageIndex",
                    error,
                )
            }
            assertTrue("discussion_listing_empty", listing.threads.isNotEmpty())
            assertTrue(
                "discussion_identity_duplicate",
                listing.threads.all { thread -> threadIdentities.add(thread.route) },
            )
            listingThreads.addAll(listing.threads)
            listingRoute = listing.nextRoute
            if (pageIndex < listingPageCount - 1) {
                assertNotNull("discussion_continuation_missing", listingRoute)
            }
        }

        val sampledThreadRoute = listingThreads
            .maxByOrNull { thread -> thread.replyCount ?: -1 }
            ?.route
        assertNotNull("discussion_sample_missing", sampledThreadRoute)
        var threadRoute = requireNotNull(sampledThreadRoute)
        val postIdentities = mutableSetOf<String>()
        repeat(threadPageCount) { pageIndex ->
            val thread = discussions.fetchThread(steamAppId, threadRoute)
            assertTrue("discussion_thread_empty", thread.posts.isNotEmpty())
            assertTrue(
                "discussion_post_duplicate",
                thread.posts.all { post -> postIdentities.add(post.postId.ifEmpty { post.text }) },
            )
            val nextRoute = thread.nextRoute
            if (pageIndex < threadPageCount - 1) {
                assertNotNull("discussion_thread_continuation_missing", nextRoute)
                threadRoute = requireNotNull(nextRoute)
            }
        }
    }

    private fun requireLiveValidation() {
        assumeTrue(
            "Set STEAM_COMMUNITY_LIVE=1 to run public Steam community validation",
            System.getenv("STEAM_COMMUNITY_LIVE") == "1",
        )
    }

    private companion object {
        val THREE_TITLE_TARGETS = listOf(1_562_430, 570, 413_150)
        val TEN_TITLE_TARGETS = listOf(
            632_470,
            1_091_500,
            292_030,
            1_086_940,
            413_150,
            275_850,
            870_780,
            367_520,
            435_150,
            105_600,
        )
    }
}
