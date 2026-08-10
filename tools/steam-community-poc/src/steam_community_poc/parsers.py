"""Deterministic parsers for Steam's public JSON and HTML responses."""

from __future__ import annotations

import copy
import json
import re
from typing import Any

from bs4 import BeautifulSoup, Tag

from .bounds import (
    MAX_ACTIVITY_CHARS,
    MAX_CURSOR_CHARS,
    MAX_DEVELOPER_RESPONSE_CHARS,
    MAX_DISCUSSION_ITEMS,
    MAX_PAGE_NUMBER,
    MAX_POST_TEXT_CHARS,
    MAX_REVIEW_ITEMS,
    MAX_REVIEW_TEXT_CHARS,
    MAX_TITLE_CHARS,
)
from .models import ParseError
from .routes import RouteKind, next_paging_route, normalize_title, route_page, validate_discussion_route

_WHITESPACE = re.compile(r"\s+")


def _json_object(body: str, source: str) -> dict[str, Any]:
    try:
        value = json.loads(body)
    except (json.JSONDecodeError, TypeError) as error:
        raise ParseError("invalid_json", f"{source} returned invalid JSON") from error
    if not isinstance(value, dict):
        raise ParseError("invalid_json_shape", f"{source} JSON root was not an object")
    return value


def parse_store_search(body: str, requested_title: str) -> dict[str, Any]:
    root = _json_object(body, "Steam store search")
    normalized = normalize_title(requested_title)
    if not normalized:
        raise ParseError("invalid_title", "Steam title must not be blank")
    items = root.get("items")
    if not isinstance(items, list):
        raise ParseError("invalid_search_shape", "Steam search omitted its items list")
    matches: list[dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        app_id, title = item.get("id"), item.get("name")
        if (
            isinstance(app_id, int)
            and not isinstance(app_id, bool)
            and app_id > 0
            and isinstance(title, str)
            and normalize_title(title) == normalized
        ):
            matches.append({"appId": app_id, "title": title[:MAX_TITLE_CHARS]})
    if len(matches) != 1:
        raise ParseError(
            "exact_title_not_found",
            "Steam search did not return exactly one exact title match",
            context={"title": requested_title, "matchCount": len(matches)},
        )
    return matches[0]


def parse_app_details(body: str, app_id: int) -> dict[str, Any]:
    root = _json_object(body, "Steam app details")
    entry = root.get(str(app_id))
    if not isinstance(entry, dict) or entry.get("success") is not True:
        raise ParseError("app_details_unavailable", "Steam app details did not resolve the AppID")
    data = entry.get("data")
    if not isinstance(data, dict):
        raise ParseError("invalid_app_details", "Steam app details omitted its data object")
    returned_id, title = data.get("steam_appid"), data.get("name")
    if (
        not isinstance(returned_id, int)
        or isinstance(returned_id, bool)
        or returned_id != app_id
        or not isinstance(title, str)
        or not title.strip()
    ):
        raise ParseError("app_identity_mismatch", "Steam app details did not preserve AppID identity")
    return {"appId": app_id, "title": title[:MAX_TITLE_CHARS]}


def _integer(value: Any, default: int = 0) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) else default


def _nonnegative_integer(value: Any) -> int:
    return max(0, _integer(value))


def _boolean(value: Any) -> bool:
    return value if isinstance(value, bool) else False


def parse_review_page(body: str, *, page_number: int = 1) -> dict[str, Any]:
    root = _json_object(body, "Steam reviews")
    if _integer(root.get("success")) != 1:
        raise ParseError("reviews_unsuccessful", "Steam reviews reported an unsuccessful response")
    raw_reviews = root.get("reviews")
    if not isinstance(raw_reviews, list):
        raise ParseError("invalid_reviews_shape", "Steam reviews omitted its reviews list")
    if not 1 <= page_number <= MAX_PAGE_NUMBER:
        raise ParseError("invalid_review_page", "Review parser received an invalid page number")

    reviews: list[dict[str, Any]] = []
    skipped = 0
    identity_fallbacks = 0
    for element_index, value in enumerate(raw_reviews[:MAX_REVIEW_ITEMS], start=1):
        if not isinstance(value, dict):
            skipped += 1
            continue
        text = value.get("review")
        if not isinstance(text, str) or not text.strip():
            skipped += 1
            continue
        author = value.get("author") if isinstance(value.get("author"), dict) else {}
        playtime = _integer(author.get("playtime_forever"), -1)
        developer_response = value.get("developer_response")
        recommendation_id = value.get("recommendationid")
        if (
            isinstance(recommendation_id, (str, int))
            and not isinstance(recommendation_id, bool)
            and str(recommendation_id).strip()
        ):
            identity = {"kind": "recommendation_id", "value": str(recommendation_id)}
        else:
            identity_fallbacks += 1
            identity = {
                "kind": "review_page_element",
                "value": f"{page_number}:{element_index}",
            }
        reviews.append(
            {
                "recommended": _boolean(value.get("voted_up")),
                "text": text[:MAX_REVIEW_TEXT_CHARS],
                "playtimeMinutes": playtime if playtime >= 0 else None,
                "helpfulVotes": _nonnegative_integer(value.get("votes_up")),
                "funnyVotes": _nonnegative_integer(value.get("votes_funny")),
                "commentCount": _nonnegative_integer(value.get("comment_count")),
                "postedAtEpochSeconds": _nonnegative_integer(value.get("timestamp_created")),
                "updatedAtEpochSeconds": _nonnegative_integer(value.get("timestamp_updated")),
                "receivedForFree": _boolean(value.get("received_for_free")),
                "earlyAccess": _boolean(value.get("written_during_early_access")),
                "developerResponse": (
                    developer_response[:MAX_DEVELOPER_RESPONSE_CHARS]
                    if isinstance(developer_response, str) and developer_response.strip()
                    else None
                ),
                "_identity": identity,
            }
        )

    cursor = root.get("cursor")
    next_cursor = (
        cursor if isinstance(cursor, str) and cursor.strip() and len(cursor) <= MAX_CURSOR_CHARS else None
    )
    return {
        "reviews": reviews,
        "nextCursor": next_cursor,
        "skippedItems": skipped,
        "identityFallbacks": identity_fallbacks,
    }


def _safe_text(
    element: Tag | None, limit: int, *, preserve_emoticons: bool = False
) -> str | None:
    if element is None:
        return None
    clone = copy.copy(element)
    if preserve_emoticons:
        for emoticon in clone.select("img.emoticon[alt]"):
            alt = emoticon.get("alt")
            if isinstance(alt, str):
                emoticon.replace_with(f" {alt} ")
    for unwanted in clone.select("script, style, iframe, img, video, audio, object, embed"):
        unwanted.decompose()
    text = _WHITESPACE.sub(" ", clone.get_text(" ", strip=True)).strip()
    return text[:limit] if text else None


def _parse_count(value: str | None) -> int | None:
    if value is None:
        return None
    digits = "".join(character for character in value if character.isascii() and character.isdigit())
    return min(int(digits), 2_147_483_647) if digits else None


def _paging_total_pages(soup: BeautifulSoup, current_page: int) -> int | None:
    summary = soup.select_one(".forum_paging_summary")
    if summary is None:
        return None
    values: list[int] = []
    for span in summary.select("span"):
        text = span.get_text(strip=True).replace(",", "")
        if text.isascii() and text.isdecimal():
            values.append(int(text))
    if len(values) < 3:
        return None
    start, end, total = values[:3]
    if start < 1 or end < start or total < end:
        return None
    observed_page_size = end - start + 1
    inferred_page_size = 0
    if current_page > 1 and start > 1 and (start - 1) % (current_page - 1) == 0:
        inferred_page_size = (start - 1) // (current_page - 1)
    page_size = max(observed_page_size, inferred_page_size)
    if page_size < 1:
        return None
    return min(MAX_PAGE_NUMBER, (total + page_size - 1) // page_size)


def _raise_for_known_steam_error(soup: BeautifulSoup) -> None:
    if soup.select_one(".error_ctn, .community_home_error, #error_box, [data-steam-error]"):
        raise ParseError("steam_error_html", "Steam returned an error or blocked HTML page")
    client_root = soup.select_one("#application_root")
    community_bundle = soup.select_one(
        "script[src*='/javascript/applications/community/main.js']"
    )
    server_forum_content = soup.select_one(
        ".forum_topic, .forum_op, .forum_post, .commentthread_comment"
    )
    if client_root is not None and community_bundle is not None and server_forum_content is None:
        raise ParseError(
            "steam_client_rendered_shell",
            "Steam returned a client-rendered application shell without forum content",
            context={"representation": "client_rendered"},
        )


def parse_discussion_listing(body: str, app_id: int, route: str) -> dict[str, Any]:
    canonical_route = validate_discussion_route(app_id, route, RouteKind.LISTING)
    if canonical_route is None:
        raise ParseError("invalid_listing_route", "Listing parser received an invalid route")
    soup = BeautifulSoup(body, "html.parser")
    _raise_for_known_steam_error(soup)
    topics = soup.select(".forum_topic")[:MAX_DISCUSSION_ITEMS]
    explicit_empty = soup.select_one(
        ".forum_no_topics, .forum_topic_none, [data-forum-empty='true']"
    )
    if not topics and explicit_empty is None:
        raise ParseError(
            "unexpected_listing_html",
            "Steam listing HTML contained neither topic containers nor an explicit empty marker",
        )
    threads: list[dict[str, Any]] = []
    skipped = 0
    for topic in topics:
        title = _safe_text(topic.select_one(".forum_topic_name"), MAX_TITLE_CHARS)
        overlay = topic.select_one("a.forum_topic_overlay")
        thread_route = validate_discussion_route(
            app_id,
            overlay.get("href", "") if overlay is not None else "",
            RouteKind.THREAD,
        )
        if title is None or thread_route is None:
            skipped += 1
            continue
        threads.append(
            {
                "title": title,
                "replyCount": _parse_count(
                    _safe_text(topic.select_one(".forum_topic_reply_count"), MAX_ACTIVITY_CHARS)
                ),
                "activityLabel": _safe_text(
                    topic.select_one(".forum_topic_lastpost"), MAX_ACTIVITY_CHARS
                ),
                "route": thread_route,
                "viewCount": _parse_count(
                    _safe_text(topic.select_one(".forum_topic_view_count"), MAX_ACTIVITY_CHARS)
                ),
            }
        )
    if topics and not threads:
        raise ParseError(
            "listing_selector_drift",
            "Steam topic containers were present but no topic matched required selectors",
            context={"topicContainerCount": len(topics), "skippedItemCount": skipped},
        )
    return {
        "threads": threads,
        "nextRoute": next_paging_route(
            canonical_route,
            RouteKind.LISTING,
            _paging_total_pages(soup, route_page(canonical_route, RouteKind.LISTING)),
        ),
        "skippedItems": skipped,
    }


def _is_inside_opening_post(element: Tag) -> bool:
    return element.find_parent(class_="forum_op") is not None


def _stable_post_identity(element: Tag) -> dict[str, str] | None:
    current: Tag | None = element
    while current is not None:
        for attribute, prefix in (("data-postid", "post:"), ("data-commentid", "comment:")):
            raw_value = current.get(attribute)
            if isinstance(raw_value, str) and raw_value.isascii() and raw_value.isdecimal():
                return {"kind": "steam_post_id", "value": f"{prefix}{raw_value}"}
        element_id = current.get("id")
        if isinstance(element_id, str) and re.fullmatch(
            r"(?:forum_op|forum_post|comment|commentthread_comment)[_-][0-9]+", element_id
        ):
            return {"kind": "steam_post_id", "value": element_id}
        parent = current.parent
        current = parent if isinstance(parent, Tag) else None
    return None


def _is_blank_post_content(element: Tag) -> bool:
    clone = copy.copy(element)
    for line_break in clone.select("br"):
        line_break.decompose()
    return clone.find(True) is None and not clone.get_text(" ", strip=True)


def parse_discussion_thread(body: str, app_id: int, route: str) -> dict[str, Any]:
    canonical_route = validate_discussion_route(app_id, route, RouteKind.THREAD)
    if canonical_route is None:
        raise ParseError("invalid_thread_route", "Thread parser received an invalid route")
    soup = BeautifulSoup(body, "html.parser")
    _raise_for_known_steam_error(soup)
    containers = soup.select(".forum_op, .forum_post, .commentthread_comment")
    explicit_empty = soup.select_one(
        ".forum_thread_empty, .forum_posts_empty, [data-forum-thread-empty='true']"
    )
    if not containers and explicit_empty is None:
        raise ParseError(
            "unexpected_thread_html",
            "Steam thread HTML contained neither post containers nor an explicit empty marker",
        )
    title = _safe_text(soup.select_one(".topic, .forum_topic_name, h1"), MAX_TITLE_CHARS)
    if title is None:
        title = "Steam discussion"

    candidates: list[Tag] = []
    candidate_ids: set[int] = set()
    if route_page(canonical_route, RouteKind.THREAD) == 1:
        opening = soup.select_one(
            ".forum_op > .content, .forum_op .forum_post_text, .forum_op .forum_post_body"
        )
        if opening is not None:
            candidates.append(opening)
            candidate_ids.add(id(opening))
    for selector in (
        ".forum_post > .content",
        ".forum_post_text",
        ".forum_post_body",
        ".commentthread_comment_text",
    ):
        for element in soup.select(selector):
            if not _is_inside_opening_post(element) and id(element) not in candidate_ids:
                candidates.append(element)
                candidate_ids.add(id(element))

    if containers and not candidates:
        raise ParseError(
            "thread_selector_drift",
            "Steam post containers were present but no post matched required content selectors",
            context={"postContainerCount": len(containers)},
        )
    posts: list[dict[str, Any]] = []
    blank_post_count = 0
    skipped = 0
    skipped_reasons: dict[str, int] = {}
    identity_fallbacks = 0
    for element_index, candidate in enumerate(candidates[:MAX_DISCUSSION_ITEMS], start=1):
        text = _safe_text(
            candidate, MAX_POST_TEXT_CHARS, preserve_emoticons=True
        )
        if text is None:
            if _is_blank_post_content(candidate):
                blank_post_count += 1
            else:
                skipped += 1
                skipped_reasons["unmapped_post_content"] = (
                    skipped_reasons.get("unmapped_post_content", 0) + 1
                )
        else:
            identity = _stable_post_identity(candidate)
            if identity is None:
                identity_fallbacks += 1
                identity = {
                    "kind": "page_element",
                    "value": f"{canonical_route}#post-{element_index}",
                }
            posts.append({"text": text, "_identity": identity})
    if candidates and not posts:
        raise ParseError(
            "thread_selector_drift",
            "Steam post content selectors matched but yielded no bounded plain text",
            context={
                "candidateCount": len(candidates),
                "blankPostCount": blank_post_count,
                "skippedItemCount": skipped,
                "skippedItemReasons": skipped_reasons,
            },
        )
    return {
        "title": title,
        "posts": posts,
        "route": canonical_route,
        "nextRoute": next_paging_route(
            canonical_route,
            RouteKind.THREAD,
            _paging_total_pages(soup, route_page(canonical_route, RouteKind.THREAD)),
        ),
        "skippedItems": skipped,
        "skippedItemReasons": skipped_reasons,
        "blankPostCount": blank_post_count,
        "identityFallbacks": identity_fallbacks,
    }
