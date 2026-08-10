"""Bounded multi-page Steam community collection orchestration."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol
from urllib.parse import urlencode, urlsplit

from .bounds import MAX_PAGES_PER_KIND, MAX_SAMPLED_THREADS
from .http import HttpResult, RequestPurpose
from .models import Diagnostic, NetworkError, ParseError, PocError, RateLimitError, ValidationError
from .parsers import (
    parse_app_details,
    parse_discussion_listing,
    parse_discussion_thread,
    parse_review_page,
    parse_store_search,
)
from .routes import (
    RouteKind,
    normalize_title,
    parse_positive_app_id,
    validate_discussion_route,
    validate_requested_count,
)
from .schema import validate_result

_STORE_ORIGIN = "https://store.steampowered.com"
_COMMUNITY_ORIGIN = "https://steamcommunity.com"


class HttpClient(Protocol):
    def get(
        self, url: str, purpose: RequestPurpose, *, app_id: int | None = None
    ) -> HttpResult: ...


@dataclass(frozen=True)
class CollectorConfig:
    review_pages: int = 3
    discussion_pages: int = 3
    thread_pages: int = 3
    sample_threads: int = 1

    def __post_init__(self) -> None:
        validate_requested_count("review pages", self.review_pages, MAX_PAGES_PER_KIND)
        validate_requested_count("discussion pages", self.discussion_pages, MAX_PAGES_PER_KIND)
        validate_requested_count("thread pages", self.thread_pages, MAX_PAGES_PER_KIND)
        validate_requested_count("sample threads", self.sample_threads, MAX_SAMPLED_THREADS)


class SteamCommunityCollector:
    def __init__(self, http_client: HttpClient) -> None:
        self._http = http_client

    def collect(self, target_input: str, config: CollectorConfig) -> dict[str, Any]:
        if not isinstance(target_input, str) or not normalize_title(target_input):
            raise ValidationError("invalid_target", "Steam title or AppID must not be blank")
        if len(target_input) > 512:
            raise ValidationError("target_too_long", "Steam title or AppID exceeded its limit")
        diagnostics: list[dict[str, Any]] = []
        target = self._resolve(target_input, diagnostics)
        app_id = target["appId"]

        reviews = self._collect_reviews(app_id, config.review_pages, diagnostics)
        discussions = self._collect_discussions(
            app_id,
            config.discussion_pages,
            config.thread_pages,
            config.sample_threads,
            diagnostics,
        )
        diagnostics.append(
            Diagnostic(
                "validation",
                "info",
                "schema_validation_pending",
                "Result was assembled for bundled JSON Schema validation",
                {"schemaVersion": 1},
            ).as_dict()
        )
        result = {
            "schemaVersion": 1,
            "target": target,
            "request": {
                "reviewPages": config.review_pages,
                "discussionPages": config.discussion_pages,
                "threadPages": config.thread_pages,
                "sampleThreads": config.sample_threads,
            },
            "reviews": reviews,
            "discussions": discussions,
            "diagnostics": diagnostics,
        }
        validate_result(result)
        result["diagnostics"][-1] = Diagnostic(
            "validation",
            "info",
            "schema_valid",
            "Result passed the bundled JSON Schema",
            {"schemaVersion": 1},
        ).as_dict()
        return result

    def _resolve(self, target_input: str, diagnostics: list[dict[str, Any]]) -> dict[str, Any]:
        if target_input.isascii() and target_input.isdecimal():
            app_id = parse_positive_app_id(target_input)
            query = urlencode({"appids": app_id, "l": "english", "cc": "US"})
            url = f"{_STORE_ORIGIN}/api/appdetails?{query}"
            result = self._fetch(url, RequestPurpose.APP_DETAILS, diagnostics, app_id=app_id, page=1)
            identity = parse_app_details(result.body, app_id)
            resolution = "app_id"
        else:
            query = urlencode({"term": target_input, "l": "english", "cc": "US"})
            url = f"{_STORE_ORIGIN}/api/storesearch/?{query}"
            result = self._fetch(url, RequestPurpose.STORE_SEARCH, diagnostics, page=1)
            identity = parse_store_search(result.body, target_input)
            app_id = identity["appId"]
            resolution = "exact_title"
        diagnostics.append(
            Diagnostic(
                "resolution",
                "info",
                "target_resolved",
                "Steam target resolved with exact identity preservation",
                {"input": target_input, "appId": app_id, "title": identity["title"], "method": resolution},
            ).as_dict()
        )
        return {
            "input": target_input,
            "appId": app_id,
            "title": identity["title"],
            "resolution": resolution,
        }

    def _collect_reviews(
        self, app_id: int, requested_pages: int, diagnostics: list[dict[str, Any]]
    ) -> dict[str, Any]:
        items: list[dict[str, Any]] = []
        identities: set[tuple[str, str]] = set()
        identity_kinds: set[str] = set()
        duplicate_count = 0
        urls: list[str] = []
        cursor: str | None = None
        scheduled_cursors: set[str] = set()
        fetched = 0
        continuation = False
        failed = False

        for page in range(1, requested_pages + 1):
            parameters = {
                "json": "1",
                "filter": "all",
                "language": "all",
                "review_type": "all",
                "purchase_type": "all",
                "num_per_page": "20",
            }
            if cursor is not None:
                parameters["cursor"] = cursor
            url = f"{_STORE_ORIGIN}/appreviews/{app_id}?{urlencode(parameters)}"
            urls.append(url)
            try:
                response = self._fetch(
                    url, RequestPurpose.REVIEWS, diagnostics, app_id=app_id, page=page
                )
                parsed = parse_review_page(response.body, page_number=page)
            except RateLimitError:
                raise
            except PocError as error:
                failed = True
                self._append_error(diagnostics, error, page=page, purpose="reviews")
                break
            fetched += 1
            diagnostics.append(
                Diagnostic(
                    "parser",
                    "info",
                    "reviews_parsed",
                    "Parsed a bounded Steam review page",
                    {
                        "page": page,
                        "itemCount": len(parsed["reviews"]),
                        "skippedItemCount": parsed["skippedItems"],
                        "identityFallbackCount": parsed["identityFallbacks"],
                    },
                ).as_dict()
            )
            if parsed["identityFallbacks"]:
                diagnostics.append(
                    Diagnostic(
                        "pagination",
                        "warning",
                        "review_identity_fallback",
                        "Review identity fell back to structural page and element position",
                        {"page": page, "fallbackCount": parsed["identityFallbacks"]},
                    ).as_dict()
                )
            for item in parsed["reviews"]:
                identity = item["_identity"]
                identity_key = (identity["kind"], identity["value"])
                identity_kinds.add(identity["kind"])
                if identity_key in identities:
                    duplicate_count += 1
                    continue
                identities.add(identity_key)
                items.append({key: value for key, value in item.items() if key != "_identity"})
            next_cursor = parsed["nextCursor"]
            if next_cursor is None:
                continuation = False
                break
            if next_cursor in scheduled_cursors:
                continuation = False
                diagnostics.append(
                    Diagnostic(
                        "pagination",
                        "warning",
                        "repeated_review_cursor",
                        "Stopped review pagination because Steam repeated a cursor",
                        {"page": page, "cursor": next_cursor},
                    ).as_dict()
                )
                break
            scheduled_cursors.add(next_cursor)
            cursor = next_cursor
            continuation = True

        if duplicate_count:
            diagnostics.append(
                Diagnostic(
                    "pagination",
                    "warning",
                    "review_identity_overlap",
                    "Duplicate review identities overlapped across requested pages",
                    {"duplicateItemCount": duplicate_count},
                ).as_dict()
            )
        state = self._review_state(items, fetched, continuation, failed)
        return {
            "sectionState": state,
            "items": items,
            "pagination": self._pagination(
                requested_pages,
                fetched,
                urls,
                len(identities),
                duplicate_count,
                continuation,
                identity_kinds,
            ),
        }

    def _collect_discussions(
        self,
        app_id: int,
        requested_pages: int,
        thread_pages: int,
        sample_threads: int,
        diagnostics: list[dict[str, Any]],
    ) -> dict[str, Any]:
        items: list[dict[str, Any]] = []
        routes_seen: set[str] = set()
        duplicate_count = 0
        urls: list[str] = []
        route = f"/app/{app_id}/discussions/"
        scheduled_routes: set[str] = {route}
        fetched = 0
        continuation = False
        failed = False

        for page in range(1, requested_pages + 1):
            url = f"{_COMMUNITY_ORIGIN}{route}"
            urls.append(url)
            try:
                response = self._fetch(
                    url,
                    RequestPurpose.DISCUSSION_LISTING,
                    diagnostics,
                    app_id=app_id,
                    page=page,
                )
                parser_route = self._response_route(response, app_id, RouteKind.LISTING, route)
                parsed = parse_discussion_listing(response.body, app_id, parser_route)
            except RateLimitError:
                raise
            except PocError as error:
                failed = True
                self._append_error(diagnostics, error, page=page, purpose="discussion_listing")
                break
            fetched += 1
            diagnostics.append(
                Diagnostic(
                    "parser",
                    "info",
                    "discussion_listing_parsed",
                    "Parsed a bounded Steam discussion listing page",
                    {
                        "page": page,
                        "itemCount": len(parsed["threads"]),
                        "skippedItemCount": parsed["skippedItems"],
                        "route": parser_route,
                    },
                ).as_dict()
            )
            for item in parsed["threads"]:
                if item["route"] in routes_seen:
                    duplicate_count += 1
                    continue
                routes_seen.add(item["route"])
                items.append(item)
            next_route = parsed["nextRoute"]
            if next_route is None:
                continuation = False
                break
            if next_route in scheduled_routes:
                continuation = False
                diagnostics.append(
                    Diagnostic(
                        "pagination",
                        "warning",
                        "repeated_listing_route",
                        "Stopped listing pagination because Steam repeated a route",
                        {"page": page, "route": next_route},
                    ).as_dict()
                )
                break
            scheduled_routes.add(next_route)
            route = next_route
            continuation = True

        if duplicate_count:
            diagnostics.append(
                Diagnostic(
                    "pagination",
                    "warning",
                    "discussion_item_overlap",
                    "Discussion routes overlapped across listing pages",
                    {"duplicateItemCount": duplicate_count},
                ).as_dict()
            )
        sampling_candidates = sorted(
            items,
            key=lambda summary: (
                summary["replyCount"] if summary["replyCount"] is not None else -1
            ),
            reverse=True,
        )
        minimum_reply_count = max(0, (thread_pages - 1) * 15)
        depth_candidates = [
            summary
            for summary in sampling_candidates
            if summary["replyCount"] is None
            or summary["replyCount"] >= minimum_reply_count
        ]
        if not depth_candidates:
            depth_candidates = sampling_candidates[:sample_threads]

        sampled: list[dict[str, Any]] = []
        shallow_fallbacks: list[dict[str, Any]] = []
        for summary in depth_candidates[:MAX_SAMPLED_THREADS]:
            thread = self._collect_thread(app_id, summary, thread_pages, diagnostics)
            fetched_pages = thread["pagination"]["fetchedPages"]
            if fetched_pages >= thread_pages:
                sampled.append(thread)
                if len(sampled) == sample_threads:
                    break
                continue
            shallow_fallbacks.append(thread)
            diagnostics.append(
                Diagnostic(
                    "pagination",
                    "info",
                    "thread_candidate_too_shallow",
                    "Sampled thread paging did not prove the requested depth",
                    {
                        "route": thread["route"],
                        "requestedPages": thread_pages,
                        "fetchedPages": fetched_pages,
                    },
                ).as_dict()
            )
        if len(sampled) < sample_threads:
            shallow_fallbacks.sort(
                key=lambda thread: thread["pagination"]["fetchedPages"], reverse=True
            )
            sampled.extend(shallow_fallbacks[: sample_threads - len(sampled)])
        return {
            "sectionState": self._listing_state(items, fetched, continuation, failed),
            "items": items,
            "pagination": self._pagination(
                requested_pages,
                fetched,
                urls,
                len(routes_seen),
                duplicate_count,
                continuation,
                {"discussion_route"} if routes_seen else set(),
            ),
            "sampledThreads": sampled,
        }

    def _collect_thread(
        self,
        app_id: int,
        summary: dict[str, Any],
        requested_pages: int,
        diagnostics: list[dict[str, Any]],
    ) -> dict[str, Any]:
        posts: list[dict[str, str]] = []
        identities: set[tuple[str, str]] = set()
        identity_kinds: set[str] = set()
        duplicate_count = 0
        urls: list[str] = []
        route = summary["route"]
        base_route = route
        scheduled_routes: set[str] = {route}
        fetched = 0
        continuation = False
        failed = False
        title = summary["title"]

        for page in range(1, requested_pages + 1):
            url = f"{_COMMUNITY_ORIGIN}{route}"
            urls.append(url)
            try:
                response = self._fetch(
                    url,
                    RequestPurpose.DISCUSSION_THREAD,
                    diagnostics,
                    app_id=app_id,
                    page=page,
                )
                parser_route = self._response_route(response, app_id, RouteKind.THREAD, route)
                parsed = parse_discussion_thread(response.body, app_id, parser_route)
            except RateLimitError:
                raise
            except PocError as error:
                failed = True
                self._append_error(
                    diagnostics, error, page=page, purpose="discussion_thread", route=route
                )
                break
            fetched += 1
            title = parsed["title"]
            diagnostics.append(
                Diagnostic(
                    "parser",
                    "info",
                    "discussion_thread_parsed",
                    "Parsed a bounded Steam discussion thread page",
                    {
                        "page": page,
                        "itemCount": len(parsed["posts"]),
                        "skippedItemCount": parsed["skippedItems"],
                        "skippedItemReasons": parsed["skippedItemReasons"],
                        "blankPostCount": parsed["blankPostCount"],
                        "identityFallbackCount": parsed["identityFallbacks"],
                        "route": parser_route,
                    },
                ).as_dict()
            )
            if parsed["identityFallbacks"]:
                diagnostics.append(
                    Diagnostic(
                        "pagination",
                        "info",
                        "thread_identity_fallback",
                        "Thread identity fell back to structural page and element position",
                        {
                            "page": page,
                            "route": parser_route,
                            "fallbackCount": parsed["identityFallbacks"],
                        },
                    ).as_dict()
                )
            for post in parsed["posts"]:
                identity = post["_identity"]
                identity_key = (identity["kind"], identity["value"])
                identity_kinds.add(identity["kind"])
                if identity_key in identities:
                    duplicate_count += 1
                    continue
                identities.add(identity_key)
                posts.append({"text": post["text"]})
            next_route = parsed["nextRoute"]
            if next_route is None:
                continuation = False
                break
            if next_route in scheduled_routes:
                continuation = False
                diagnostics.append(
                    Diagnostic(
                        "pagination",
                        "warning",
                        "repeated_thread_route",
                        "Stopped thread pagination because Steam repeated a route",
                        {"page": page, "route": next_route},
                    ).as_dict()
                )
                break
            scheduled_routes.add(next_route)
            route = next_route
            continuation = True

        if duplicate_count:
            diagnostics.append(
                Diagnostic(
                    "pagination",
                    "warning",
                    "thread_identity_overlap",
                    "Duplicate stable thread-post identities overlapped across sampled pages",
                    {"route": base_route, "duplicateItemCount": duplicate_count},
                ).as_dict()
            )
        return {
            "title": title,
            "route": base_route,
            "sectionState": self._thread_state(posts, fetched, continuation, failed),
            "posts": posts,
            "pagination": self._pagination(
                requested_pages,
                fetched,
                urls,
                len(identities),
                duplicate_count,
                continuation,
                identity_kinds,
            ),
        }

    def _fetch(
        self,
        url: str,
        purpose: RequestPurpose,
        diagnostics: list[dict[str, Any]],
        *,
        app_id: int | None = None,
        page: int,
    ) -> HttpResult:
        result = self._http.get(url, purpose, app_id=app_id)
        diagnostics.append(
            Diagnostic(
                "http",
                "info",
                "http_response",
                "Steam returned a bounded successful response",
                {
                    "purpose": purpose.value,
                    "page": page,
                    "requestedUrl": url,
                    "finalUrl": result.final_url,
                    "statusCode": result.status_code,
                    "bodyBytes": result.body_bytes,
                    "contentType": result.content_type,
                    "redirectCount": len(result.redirects),
                    "attempts": result.attempts,
                },
            ).as_dict()
        )
        return result

    @staticmethod
    def _response_route(
        response: HttpResult, app_id: int, kind: RouteKind, fallback: str
    ) -> str:
        parsed = urlsplit(response.final_url)
        candidate = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        return validate_discussion_route(app_id, candidate, kind) or fallback

    @staticmethod
    def _append_error(
        diagnostics: list[dict[str, Any]],
        error: PocError,
        *,
        page: int,
        purpose: str,
        route: str | None = None,
    ) -> None:
        diagnostic_type = "parser" if isinstance(error, ParseError) else "http"
        context = {**error.context, "page": page, "purpose": purpose}
        if route is not None:
            context["route"] = route
        diagnostics.append(
            Diagnostic(diagnostic_type, "error", error.code, str(error), context).as_dict()
        )

    @staticmethod
    def _pagination(
        requested: int,
        fetched: int,
        urls: list[str],
        unique_items: int,
        duplicate_items: int,
        continuation: bool,
        identity_kinds: set[str],
    ) -> dict[str, Any]:
        return {
            "requestedPages": requested,
            "fetchedPages": fetched,
            "requestedUrls": urls,
            "uniqueRequestCount": len(set(urls)),
            "uniqueItemCount": unique_items,
            "duplicateItemCount": duplicate_items,
            "identityKinds": sorted(identity_kinds),
            "continuationAvailable": continuation,
        }

    @staticmethod
    def _review_state(
        items: list[dict[str, Any]], fetched: int, continuation: bool, failed: bool
    ) -> dict[str, Any]:
        if items:
            return {
                "kind": "Content",
                "canLoadMore": continuation or failed,
                "loadingMore": False,
                "refreshFailed": failed,
            }
        if fetched:
            return {"kind": "Empty"}
        return {"kind": "Unavailable"}

    @staticmethod
    def _listing_state(
        items: list[dict[str, Any]], fetched: int, continuation: bool, failed: bool
    ) -> dict[str, Any]:
        if items:
            return {
                "kind": "Listing",
                "canLoadMore": continuation or failed,
                "loadingMore": False,
                "refreshFailed": failed,
            }
        if fetched:
            return {"kind": "Empty"}
        return {"kind": "Unavailable"}

    @staticmethod
    def _thread_state(
        posts: list[dict[str, str]], fetched: int, continuation: bool, failed: bool
    ) -> dict[str, Any]:
        if posts:
            return {
                "kind": "Thread",
                "canLoadMore": continuation or failed,
                "loadingMore": False,
                "refreshFailed": failed,
            }
        if fetched:
            return {"kind": "Empty"}
        return {"kind": "Unavailable"}
