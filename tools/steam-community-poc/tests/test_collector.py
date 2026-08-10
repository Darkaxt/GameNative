import json
from collections import deque
from urllib.parse import parse_qs, urlsplit

import pytest

from steam_community_poc.collector import CollectorConfig, SteamCommunityCollector
from steam_community_poc.http import HttpResult, RequestPurpose
from steam_community_poc.models import NetworkError, RateLimitError
from steam_community_poc.schema import validate_result


def response(body: str, url: str = "https://example.invalid/public") -> HttpResult:
    return HttpResult(
        body=body,
        status_code=200,
        final_url=url,
        body_bytes=len(body.encode("utf-8")),
        redirects=[],
    )


class ScriptedHttp:
    def __init__(self, outcomes: list[HttpResult | Exception]) -> None:
        self.outcomes = deque(outcomes)
        self.calls: list[tuple[str, RequestPurpose, int | None]] = []

    def get(self, url: str, purpose: RequestPurpose, *, app_id: int | None = None) -> HttpResult:
        self.calls.append((url, purpose, app_id))
        outcome = self.outcomes.popleft()
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def review_body(
    text: str, cursor: str | None, *, recommendation_id: str | None = None
) -> str:
    stable_id = recommendation_id or str(sum(ord(character) for character in text))
    return json.dumps(
        {
            "success": 1,
            "cursor": cursor,
            "reviews": [
                {
                    "recommendationid": stable_id,
                    "voted_up": True,
                    "review": text,
                    "author": {"playtime_forever": 60},
                    "votes_up": 2,
                    "votes_funny": 0,
                    "comment_count": 1,
                    "timestamp_created": 10,
                    "timestamp_updated": 11,
                    "received_for_free": False,
                    "written_during_early_access": False,
                }
            ],
        }
    )


def listing_body(thread_id: int, title: str, pages: int = 2) -> str:
    total = pages * 15
    return f"""
      <div class='forum_topic'>
        <a class='forum_topic_overlay' href='/app/42/discussions/0/{thread_id}/'></a>
        <div class='forum_topic_name'>{title}</div>
        <div class='forum_topic_reply_count'>4</div>
      </div>
      <div class='forum_paging'>
        <div class='forum_paging_summary'>Showing <span>1</span> - <span>15</span> of <span>{total}</span> active topics</div>
      </div>
    """


def thread_body(reply: str, pages: int = 2, *, post_id: int | None = None) -> str:
    stable_post_id = post_id or sum(ord(character) for character in reply)
    total = pages * 15
    return f"""
      <div class='topic'>A useful thread</div>
      <div class='forum_op' id='forum_post_1'><div class='content'>Opening post</div></div>
      <div class='forum_post' id='forum_post_{stable_post_id}'><div class='content'>{reply}</div></div>
      <div class='forum_paging'>
        <div class='forum_paging_summary'>Showing <span>1</span> - <span>15</span> of <span>{total}</span> comments</div>
      </div>
    """


def test_collector_resolves_exact_title_and_fetches_unique_multi_page_content() -> None:
    http = ScriptedHttp(
        [
            response(json.dumps({"items": [{"id": 42, "name": "Exact Game"}]})),
            response(review_body("Review one", "next cursor")),
            response(review_body("Review two", None)),
            response(listing_body(100, "Topic one")),
            response(listing_body(101, "Topic two")),
            response(thread_body("Reply one")),
            response(thread_body("Reply two")),
        ]
    )

    result = SteamCommunityCollector(http).collect(
        "exact game",
        CollectorConfig(
            review_pages=3,
            discussion_pages=2,
            thread_pages=3,
            sample_threads=1,
        ),
    )

    validate_result(result)
    assert result["target"] == {
        "input": "exact game",
        "appId": 42,
        "title": "Exact Game",
        "resolution": "exact_title",
    }
    assert [item["text"] for item in result["reviews"]["items"]] == [
        "Review one",
        "Review two",
    ]
    assert all("_identity" not in item for item in result["reviews"]["items"])
    assert result["reviews"]["pagination"]["identityKinds"] == ["recommendation_id"]
    assert result["reviews"]["sectionState"] == {
        "kind": "Content",
        "canLoadMore": False,
        "loadingMore": False,
        "refreshFailed": False,
    }
    assert result["reviews"]["pagination"]["fetchedPages"] == 2
    assert result["reviews"]["pagination"]["uniqueRequestCount"] == 2
    review_second_url = http.calls[2][0]
    assert parse_qs(urlsplit(review_second_url).query)["cursor"] == ["next cursor"]

    assert [item["route"] for item in result["discussions"]["items"]] == [
        "/app/42/discussions/0/100/",
        "/app/42/discussions/0/101/",
    ]
    assert http.calls[4][0].endswith("/app/42/discussions/?fp=2")
    sampled = result["discussions"]["sampledThreads"][0]
    assert [post["text"] for post in sampled["posts"]] == [
        "Opening post",
        "Reply one",
        "Reply two",
    ]
    assert all("_identity" not in post for post in sampled["posts"])
    assert sampled["pagination"]["identityKinds"] == ["steam_post_id"]
    assert http.calls[6][0].endswith("/app/42/discussions/0/100/?ctp=2")
    assert sampled["pagination"]["fetchedPages"] == 2
    assert sampled["sectionState"]["kind"] == "Thread"


def test_collector_stops_repeated_cursor_and_reports_item_overlap() -> None:
    repeated = review_body("Same review", "same")
    http = ScriptedHttp(
        [
            response(json.dumps({"items": [{"id": 42, "name": "Exact Game"}]})),
            response(repeated),
            response(repeated),
            response("<div class='forum_no_topics' data-forum-empty='true'>Empty</div>"),
        ]
    )

    result = SteamCommunityCollector(http).collect(
        "Exact Game",
        CollectorConfig(review_pages=3, discussion_pages=1, thread_pages=1, sample_threads=1),
    )

    assert len(result["reviews"]["items"]) == 1
    assert result["reviews"]["pagination"]["duplicateItemCount"] == 1
    assert result["reviews"]["pagination"]["fetchedPages"] == 2
    assert result["reviews"]["pagination"]["continuationAvailable"] is False
    assert any(
        diagnostic["type"] == "pagination" and diagnostic["code"] == "repeated_review_cursor"
        for diagnostic in result["diagnostics"]
    )
    assert result["discussions"]["sectionState"]["kind"] == "Empty"


def test_rate_limit_exhaustion_aborts_collection_instead_of_returning_partial_content() -> None:
    http = ScriptedHttp(
        [
            response(json.dumps({"items": [{"id": 42, "name": "Exact Game"}]})),
            response(review_body("First page", "next")),
            RateLimitError(
                "steam_rate_limited",
                "Steam rate limit persisted after four GET attempts",
                context={"attemptCount": 4},
            ),
        ]
    )

    with pytest.raises(RateLimitError) as caught:
        SteamCommunityCollector(http).collect(
            "Exact Game",
            CollectorConfig(
                review_pages=2,
                discussion_pages=1,
                thread_pages=1,
                sample_threads=1,
            ),
        )

    assert caught.value.code == "steam_rate_limited"
    assert len(http.calls) == 3


def test_collector_maps_first_page_network_failure_to_unavailable_state() -> None:
    http = ScriptedHttp(
        [
            response(json.dumps({"items": [{"id": 42, "name": "Exact Game"}]})),
            NetworkError("network_failure", "offline", context={"url": "public"}),
            response("<div class='forum_no_topics' data-forum-empty='true'>Empty</div>"),
        ]
    )

    result = SteamCommunityCollector(http).collect(
        "Exact Game",
        CollectorConfig(review_pages=1, discussion_pages=1, thread_pages=1, sample_threads=1),
    )

    assert result["reviews"]["sectionState"] == {"kind": "Unavailable"}
    assert result["reviews"]["items"] == []
    assert any(
        diagnostic["type"] == "http"
        and diagnostic["severity"] == "error"
        and diagnostic["code"] == "network_failure"
        for diagnostic in result["diagnostics"]
    )


def test_thread_sampling_prefers_topics_with_enough_replies_for_pagination() -> None:
    listing = """
      <div class='forum_topic'>
        <a class='forum_topic_overlay' href='/app/42/discussions/0/100/'></a>
        <div class='forum_topic_name'>One-page topic</div>
        <div class='forum_topic_reply_count'>1</div>
      </div>
      <div class='forum_topic'>
        <a class='forum_topic_overlay' href='/app/42/discussions/0/200/'></a>
        <div class='forum_topic_name'>Long topic</div>
        <div class='forum_topic_reply_count'>100</div>
      </div>
    """
    http = ScriptedHttp(
        [
            response(json.dumps({"items": [{"id": 42, "name": "Exact Game"}]})),
            response(json.dumps({"success": 1, "reviews": []})),
            response(listing),
            response(thread_body("Reply", pages=1)),
        ]
    )

    SteamCommunityCollector(http).collect(
        "Exact Game",
        CollectorConfig(review_pages=1, discussion_pages=1, thread_pages=3, sample_threads=1),
    )

    assert http.calls[3][0].endswith("/app/42/discussions/0/200/")


def test_thread_sampling_rejects_shallow_candidate_and_selects_proven_depth() -> None:
    listing = """
      <div class='forum_topic'>
        <a class='forum_topic_overlay' href='/app/42/discussions/0/100/'></a>
        <div class='forum_topic_name'>High count but shallow live thread</div>
        <div class='forum_topic_reply_count'>100</div>
      </div>
      <div class='forum_topic'>
        <a class='forum_topic_overlay' href='/app/42/discussions/0/200/'></a>
        <div class='forum_topic_name'>Proven three-page thread</div>
        <div class='forum_topic_reply_count'>90</div>
      </div>
    """
    http = ScriptedHttp(
        [
            response(json.dumps({"items": [{"id": 42, "name": "Exact Game"}]})),
            response(json.dumps({"success": 1, "reviews": []})),
            response(listing),
            response(thread_body("Shallow", pages=1, post_id=100)),
            response(thread_body("Deep page one", pages=3, post_id=201)),
            response(thread_body("Deep page two", pages=3, post_id=202)),
            response(thread_body("Deep page three", pages=3, post_id=203)),
        ]
    )

    result = SteamCommunityCollector(http).collect(
        "Exact Game",
        CollectorConfig(review_pages=1, discussion_pages=1, thread_pages=3, sample_threads=1),
    )

    sampled = result["discussions"]["sampledThreads"][0]
    assert sampled["route"] == "/app/42/discussions/0/200/"
    assert sampled["pagination"]["fetchedPages"] == 3
    assert any(
        diagnostic["code"] == "thread_candidate_too_shallow"
        and diagnostic["context"]["route"] == "/app/42/discussions/0/100/"
        for diagnostic in result["diagnostics"]
    )


def test_collector_surfaces_blank_post_omissions_without_parser_skip() -> None:
    thread = """
      <div class='topic'>Thread with one blank reply</div>
      <div class='commentthread_comment' id='comment_98'>
        <div class='commentthread_comment_text'>A visible reply</div>
      </div>
      <div class='commentthread_comment' id='comment_99'>
        <div class='commentthread_comment_text'><br><br><br></div>
      </div>
    """
    http = ScriptedHttp(
        [
            response(json.dumps({"items": [{"id": 42, "name": "Exact Game"}]})),
            response(json.dumps({"success": 1, "reviews": []})),
            response(listing_body(100, "Thread with one blank reply", pages=1)),
            response(thread),
        ]
    )

    result = SteamCommunityCollector(http).collect(
        "Exact Game",
        CollectorConfig(review_pages=1, discussion_pages=1, thread_pages=1, sample_threads=1),
    )

    parsed = next(
        diagnostic
        for diagnostic in result["diagnostics"]
        if diagnostic["code"] == "discussion_thread_parsed"
    )
    assert parsed["context"]["blankPostCount"] == 1
    assert parsed["context"]["skippedItemCount"] == 0
    assert [post["text"] for post in result["discussions"]["sampledThreads"][0]["posts"]] == [
        "A visible reply"
    ]


def test_review_cross_page_dedupe_uses_recommendation_id_not_mutable_card_text() -> None:
    http = ScriptedHttp(
        [
            response(json.dumps({"items": [{"id": 42, "name": "Exact Game"}]})),
            response(review_body("Original text", "next", recommendation_id="777")),
            response(review_body("Edited text", None, recommendation_id="777")),
            response("<div class='forum_no_topics' data-forum-empty='true'>Empty</div>"),
        ]
    )

    result = SteamCommunityCollector(http).collect(
        "Exact Game",
        CollectorConfig(review_pages=2, discussion_pages=1, thread_pages=1, sample_threads=1),
    )

    assert [review["text"] for review in result["reviews"]["items"]] == ["Original text"]
    assert result["reviews"]["pagination"]["duplicateItemCount"] == 1
    assert result["reviews"]["pagination"]["identityKinds"] == ["recommendation_id"]
    assert "_identity" not in result["reviews"]["items"][0]


def test_thread_cross_page_dedupe_uses_stable_post_id_not_mutable_text() -> None:
    http = ScriptedHttp(
        [
            response(json.dumps({"items": [{"id": 42, "name": "Exact Game"}]})),
            response(json.dumps({"success": 1, "reviews": []})),
            response(listing_body(100, "Long topic", pages=1)),
            response(thread_body("Original reply", pages=2, post_id=99)),
            response(thread_body("Edited reply", pages=2, post_id=99)),
        ]
    )

    result = SteamCommunityCollector(http).collect(
        "Exact Game",
        CollectorConfig(review_pages=1, discussion_pages=1, thread_pages=2, sample_threads=1),
    )

    thread = result["discussions"]["sampledThreads"][0]
    assert [post["text"] for post in thread["posts"]] == ["Opening post", "Original reply"]
    assert thread["pagination"]["duplicateItemCount"] == 1
    assert thread["pagination"]["identityKinds"] == ["steam_post_id"]
    assert all("_identity" not in post for post in thread["posts"])


def test_thread_structural_fallback_is_page_element_identity_not_text_uniqueness() -> None:
    def fallback_thread(page: int) -> str:
        return f"""
          <div class='topic'>Fallback thread</div>
          <div class='forum_op'><div class='content'>Opening</div></div>
          <div class='forum_post'><div class='content'>Same text</div></div>
          <div class='forum_paging'>
            <div class='forum_paging_summary'>Showing <span>1</span> - <span>15</span> of <span>30</span> comments</div>
          </div>
        """

    http = ScriptedHttp(
        [
            response(json.dumps({"items": [{"id": 42, "name": "Exact Game"}]})),
            response(json.dumps({"success": 1, "reviews": []})),
            response(listing_body(100, "Long topic", pages=1)),
            response(fallback_thread(1)),
            response(fallback_thread(2)),
        ]
    )

    result = SteamCommunityCollector(http).collect(
        "Exact Game",
        CollectorConfig(review_pages=1, discussion_pages=1, thread_pages=2, sample_threads=1),
    )

    thread = result["discussions"]["sampledThreads"][0]
    assert [post["text"] for post in thread["posts"]] == [
        "Opening",
        "Same text",
        "Same text",
    ]
    assert thread["pagination"]["identityKinds"] == ["page_element"]
    assert thread["pagination"]["duplicateItemCount"] == 0
    assert any(
        diagnostic["code"] == "thread_identity_fallback"
        and diagnostic["severity"] == "info"
        for diagnostic in result["diagnostics"]
    )


def test_positive_app_id_uses_app_details_without_title_search() -> None:
    details = json.dumps(
        {"42": {"success": True, "data": {"steam_appid": 42, "name": "Exact Game"}}}
    )
    http = ScriptedHttp(
        [response(details), response(review_body("Review", None)), response("<div class='forum_no_topics' data-forum-empty='true'>Empty</div>")]
    )

    result = SteamCommunityCollector(http).collect(
        "42",
        CollectorConfig(review_pages=1, discussion_pages=1, thread_pages=1, sample_threads=1),
    )

    assert result["target"]["resolution"] == "app_id"
    assert result["target"]["title"] == "Exact Game"
    assert http.calls[0][1] is RequestPurpose.APP_DETAILS
    assert all(call[1] is not RequestPurpose.STORE_SEARCH for call in http.calls)
