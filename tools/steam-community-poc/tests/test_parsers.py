import json
from pathlib import Path

import pytest

from steam_community_poc.bounds import MAX_REVIEW_ITEMS, MAX_REVIEW_TEXT_CHARS
from steam_community_poc.models import ParseError
from steam_community_poc.parsers import (
    parse_discussion_listing,
    parse_discussion_thread,
    parse_review_page,
    parse_store_search,
)

FIXTURES = Path(__file__).parent / "fixtures"


def fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def test_store_search_requires_one_exact_normalized_title_match() -> None:
    result = parse_store_search(fixture("store-search.json"), "  dredge  ")

    assert result == {"appId": 1562430, "title": "DREDGE"}
    with pytest.raises(ParseError, match="exact title"):
        parse_store_search(fixture("store-search.json"), "DREDGE Sound")


def test_review_parser_maps_current_steam_review_card_fields() -> None:
    page = parse_review_page(fixture("reviews.json"))

    assert page["reviews"] == [
        {
            "recommended": True,
            "text": "Safe <b>plain</b> review text",
            "playtimeMinutes": 90,
            "helpfulVotes": 2,
            "funnyVotes": 1,
            "commentCount": 3,
            "postedAtEpochSeconds": 10,
            "updatedAtEpochSeconds": 11,
            "receivedForFree": False,
            "earlyAccess": True,
            "developerResponse": "Developer response",
            "_identity": {"kind": "recommendation_id", "value": "r1"},
        }
    ]
    assert page["identityFallbacks"] == 0
    assert page["nextCursor"] == "cursor two/+"
    assert page["skippedItems"] == 2


def test_review_parser_enforces_item_and_text_bounds() -> None:
    review = {
        "voted_up": False,
        "review": "x" * (MAX_REVIEW_TEXT_CHARS + 20),
        "author": {"playtime_forever": -4},
        "votes_up": -1,
    }
    body = json.dumps({"success": 1, "reviews": [review] * (MAX_REVIEW_ITEMS + 5)})

    page = parse_review_page(body, page_number=4)

    assert len(page["reviews"]) == MAX_REVIEW_ITEMS
    assert len(page["reviews"][0]["text"]) == MAX_REVIEW_TEXT_CHARS
    assert page["reviews"][0]["playtimeMinutes"] is None
    assert page["reviews"][0]["helpfulVotes"] == 0
    assert page["identityFallbacks"] == MAX_REVIEW_ITEMS
    assert page["reviews"][0]["_identity"] == {
        "kind": "review_page_element",
        "value": "4:1",
    }


def test_listing_parser_maps_summary_fields_and_uses_fp_pagination() -> None:
    listing = parse_discussion_listing(
        fixture("listing.html"), 42, "/app/42/discussions/"
    )

    assert listing["threads"] == [
        {
            "title": "First safe topic",
            "replyCount": 1234,
            "activityLabel": "Yesterday",
            "route": "/app/42/discussions/0/101/",
            "viewCount": 9876,
        }
    ]
    assert listing["nextRoute"] == "/app/42/discussions/?fp=2"
    assert listing["skippedItems"] == 2


def test_thread_parser_returns_inert_text_and_numeric_span_ctp_next_route() -> None:
    thread = parse_discussion_thread(
        fixture("thread.html"), 42, "/app/42/discussions/0/100/"
    )

    assert thread == {
        "title": "Thread title",
        "posts": [
            {
                "text": "Opening post",
                "_identity": {"kind": "steam_post_id", "value": "forum_op_100"},
            },
            {
                "text": "Reply one",
                "_identity": {"kind": "steam_post_id", "value": "post:101"},
            },
            {
                "text": "Reply two",
                "_identity": {"kind": "steam_post_id", "value": "comment_102"},
            },
        ],
        "route": "/app/42/discussions/0/100/",
        "nextRoute": "/app/42/discussions/0/100/?ctp=2",
        "skippedItems": 0,
        "skippedItemReasons": {},
        "blankPostCount": 0,
        "identityFallbacks": 0,
    }
    assert all("<" not in post["text"] for post in thread["posts"])


def test_thread_parser_preserves_inert_emoticon_alt_text() -> None:
    html = """
    <div class='topic'>Emoticon reply</div>
    <div class='commentthread_comment' id='comment_99'>
      <div class='commentthread_comment_text'>
        <img class='emoticon' alt=':1scoreSD:' src='https://steam.example/emoticon.png'>
      </div>
    </div>
    """

    thread = parse_discussion_thread(html, 42, "/app/42/discussions/0/100/")

    assert thread["posts"] == [
        {
            "text": ":1scoreSD:",
            "_identity": {"kind": "steam_post_id", "value": "comment_99"},
        }
    ]
    assert thread["skippedItems"] == 0


def test_thread_parser_counts_unmarked_blank_post_as_omission() -> None:
    html = """
    <div class='topic'>Blank reply</div>
    <div class='commentthread_comment' id='comment_98'>
      <div class='commentthread_comment_text'>A visible reply</div>
    </div>
    <div class='commentthread_comment' id='comment_99'>
      <div class='commentthread_comment_text'><br><br><br></div>
    </div>
    """

    thread = parse_discussion_thread(html, 42, "/app/42/discussions/0/100/")

    assert [post["text"] for post in thread["posts"]] == ["A visible reply"]
    assert thread["blankPostCount"] == 1
    assert thread["skippedItems"] == 0
    assert thread["skippedItemReasons"] == {}


def test_thread_parser_rejects_nonempty_page_when_every_post_is_blank() -> None:
    html = """
    <div class='topic'>All blank replies</div>
    <div class='commentthread_comment' id='comment_99'>
      <div class='commentthread_comment_text'><br><br><br></div>
    </div>
    """

    with pytest.raises(ParseError) as caught:
        parse_discussion_thread(html, 42, "/app/42/discussions/0/100/")

    assert caught.value.code == "thread_selector_drift"
    assert caught.value.context == {
        "candidateCount": 1,
        "blankPostCount": 1,
        "skippedItemCount": 0,
        "skippedItemReasons": {},
    }


def test_thread_parser_dedupes_opening_post_after_page_one() -> None:
    thread = parse_discussion_thread(
        fixture("thread.html"), 42, "/app/42/discussions/0/100/?ctp=2"
    )

    assert [post["text"] for post in thread["posts"]] == ["Reply one", "Reply two"]
    assert [post["_identity"]["value"] for post in thread["posts"]] == [
        "post:101",
        "comment_102",
    ]
    assert thread["route"] == "/app/42/discussions/0/100/?ctp=2"
    assert thread["nextRoute"] == "/app/42/discussions/0/100/?ctp=3"


def test_thread_parser_preserves_distinct_posts_with_identical_plain_text() -> None:
    html = """
    <div class='topic'>Duplicates can be legitimate</div>
    <div class='forum_post'><div class='content'>Same reply</div></div>
    <div class='forum_post'><div class='content'>Same reply</div></div>
    """

    thread = parse_discussion_thread(html, 42, "/app/42/discussions/0/100/")

    assert [post["text"] for post in thread["posts"]] == ["Same reply", "Same reply"]
    identities = [post["_identity"] for post in thread["posts"]]
    assert [identity["kind"] for identity in identities] == ["page_element", "page_element"]
    assert len({identity["value"] for identity in identities}) == 2
    assert thread["identityFallbacks"] == 2


@pytest.mark.parametrize(
    "html",
    [
        "<html><body><h1>Welcome</h1></body></html>",
        "<html><body><div class='error_ctn'>Access denied</div></body></html>",
    ],
)
def test_listing_parser_rejects_arbitrary_or_block_html(html: str) -> None:
    with pytest.raises(ParseError) as caught:
        parse_discussion_listing(html, 42, "/app/42/discussions/")

    assert caught.value.code in {"unexpected_listing_html", "steam_error_html"}


def test_listing_parser_types_empty_client_rendered_steam_shell() -> None:
    html = """
    <html>
      <head>
        <title>Example Game :: Steam Community</title>
        <script src='https://community.fastly.steamstatic.com/public/javascript/applications/community/main.js'></script>
      </head>
      <body class='flat_page blue responsive_page'>
        <div class='responsive_page_template_content'><div id='application_root'></div></div>
      </body>
    </html>
    """

    with pytest.raises(ParseError) as caught:
        parse_discussion_listing(html, 42, "/app/42/discussions/")

    assert caught.value.code == "steam_client_rendered_shell"
    assert caught.value.context == {"representation": "client_rendered"}


def test_listing_parser_rejects_selector_drift_instead_of_reporting_empty() -> None:
    html = """
    <div id='forum_topic_list'>
      <div class='forum_topic'>
        <div class='renamed_topic_name'>A real topic hidden by selector drift</div>
      </div>
    </div>
    """

    with pytest.raises(ParseError) as caught:
        parse_discussion_listing(html, 42, "/app/42/discussions/")

    assert caught.value.code == "listing_selector_drift"


def test_listing_parser_accepts_only_an_explicit_empty_marker_as_empty() -> None:
    listing = parse_discussion_listing(
        "<div class='forum_no_topics' data-forum-empty='true'>No active topics</div>",
        42,
        "/app/42/discussions/",
    )

    assert listing["threads"] == []
    assert listing["nextRoute"] is None


@pytest.mark.parametrize(
    "html",
    [
        "<html><body><h1>Welcome</h1></body></html>",
        "<html><body><div class='error_ctn'>Rate limited</div></body></html>",
    ],
)
def test_thread_parser_rejects_arbitrary_or_block_html(html: str) -> None:
    with pytest.raises(ParseError) as caught:
        parse_discussion_thread(html, 42, "/app/42/discussions/0/100/")

    assert caught.value.code in {"unexpected_thread_html", "steam_error_html"}


def test_thread_parser_rejects_selector_drift_instead_of_reporting_empty() -> None:
    html = """
    <div class='topic'>Drifted thread</div>
    <div class='forum_post' id='forum_post_9'><div class='renamed_content'>Hidden</div></div>
    """

    with pytest.raises(ParseError) as caught:
        parse_discussion_thread(html, 42, "/app/42/discussions/0/100/")

    assert caught.value.code == "thread_selector_drift"


def test_thread_parser_accepts_only_an_explicit_empty_marker_as_empty() -> None:
    thread = parse_discussion_thread(
        "<div class='forum_thread_empty' data-forum-thread-empty='true'>No posts</div>",
        42,
        "/app/42/discussions/0/100/",
    )

    assert thread["posts"] == []
    assert thread["identityFallbacks"] == 0


def test_thread_pagination_derives_total_pages_from_live_comment_range_spans() -> None:
    html = """
    <div class='topic'>Two-page thread</div>
    <div class='forum_op' id='forum_op_100'><div class='content'>Opening</div></div>
    <div class='commentthread_comment' id='comment_200'>
      <div class='commentthread_comment_text'>Last reply</div>
    </div>
    <div class='forum_paging'>
      <div class='forum_paging_summary'>
        Showing <span>16</span> - <span>24</span> of <span>24</span> comments
      </div>
    </div>
    """

    thread = parse_discussion_thread(
        html, 42, "/app/42/discussions/0/100/?ctp=2"
    )

    assert thread["nextRoute"] is None


def test_thread_pagination_ignores_non_numeric_next_links() -> None:
    html = """
    <div class='topic'>One page</div>
    <div class='forum_op'><div class='content'>Opening</div></div>
    <div class='forum_paging'><a rel='next' href='/app/42/discussions/0/100/?ctp=99'>Next</a></div>
    """

    thread = parse_discussion_thread(html, 42, "/app/42/discussions/0/100/")

    assert thread["nextRoute"] is None
