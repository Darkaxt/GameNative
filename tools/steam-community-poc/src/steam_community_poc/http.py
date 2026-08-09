"""Bounded cookie-free HTTP with manual purpose-preserving redirects."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Callable
from urllib.parse import parse_qsl, urljoin, urlsplit

import requests

from .bounds import MAX_BODY_BYTES, MAX_CURSOR_CHARS, MAX_NETWORK_HOPS, MAX_REVIEW_ITEMS
from .models import NetworkError
from .routes import RouteKind, validate_discussion_route

_REDIRECT_CODES = {301, 302, 303, 307, 308}
_STORE_HOST = "store.steampowered.com"


class RequestPurpose(str, Enum):
    STORE_SEARCH = "store_search"
    APP_DETAILS = "app_details"
    REVIEWS = "reviews"
    DISCUSSION_LISTING = "discussion_listing"
    DISCUSSION_THREAD = "discussion_thread"


@dataclass(frozen=True)
class HttpResult:
    body: str
    status_code: int
    final_url: str
    body_bytes: int
    redirects: list[dict[str, Any]]
    content_type: str = "unknown"


def _safe_https_url(url: str, host: str) -> Any | None:
    try:
        parsed = urlsplit(url)
        port = parsed.port
    except ValueError:
        return None
    if (
        parsed.scheme != "https"
        or parsed.hostname != host
        or port not in (None, 443)
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
    ):
        return None
    return parsed


def _query_pairs(parsed: Any) -> list[tuple[str, str]] | None:
    try:
        pairs = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True)
    except ValueError:
        return None
    if len({key for key, _ in pairs}) != len(pairs):
        return None
    return pairs


def validate_request_url(
    url: str,
    purpose: RequestPurpose,
    *,
    app_id: int | None = None,
) -> bool:
    if purpose in (RequestPurpose.DISCUSSION_LISTING, RequestPurpose.DISCUSSION_THREAD):
        if app_id is None or app_id <= 0:
            return False
        parsed = _safe_https_url(url, "steamcommunity.com")
        if parsed is None:
            return False
        route = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        kind = (
            RouteKind.LISTING
            if purpose is RequestPurpose.DISCUSSION_LISTING
            else RouteKind.THREAD
        )
        return validate_discussion_route(app_id, route, kind) is not None

    parsed = _safe_https_url(url, _STORE_HOST)
    if parsed is None:
        return False
    pairs = _query_pairs(parsed)
    if pairs is None:
        return False
    query = dict(pairs)

    if purpose is RequestPurpose.STORE_SEARCH:
        return (
            parsed.path == "/api/storesearch/"
            and set(query) <= {"term", "l", "cc"}
            and bool(query.get("term"))
        )
    if purpose is RequestPurpose.APP_DETAILS:
        return (
            parsed.path == "/api/appdetails"
            and app_id is not None
            and set(query) <= {"appids", "l", "cc"}
            and query.get("appids") == str(app_id)
        )
    if purpose is not RequestPurpose.REVIEWS or app_id is None:
        return False
    required = {
        "json",
        "filter",
        "language",
        "review_type",
        "purchase_type",
        "num_per_page",
    }
    if parsed.path != f"/appreviews/{app_id}" or not required <= set(query):
        return False
    if set(query) - (required | {"cursor"}):
        return False
    cursor = query.get("cursor")
    return (
        query["json"] == "1"
        and query["filter"] in {"all", "recent"}
        and query["language"] in {"all", "english"}
        and query["review_type"] in {"all", "positive", "negative"}
        and query["purchase_type"] in {"all", "steam"}
        and query["num_per_page"] == str(MAX_REVIEW_ITEMS)
        and (cursor is None or bool(cursor) and len(cursor) <= MAX_CURSOR_CHARS)
    )


def _validated_content_type(response: Any, purpose: RequestPurpose) -> str:
    raw_content_type = response.headers.get("Content-Type", "")
    media_type = raw_content_type.partition(";")[0].strip().casefold()
    expected = (
        {"text/html", "application/xhtml+xml"}
        if purpose in {RequestPurpose.DISCUSSION_LISTING, RequestPurpose.DISCUSSION_THREAD}
        else {"application/json"}
    )
    if media_type not in expected:
        raise NetworkError(
            "unexpected_content_type",
            "Steam response content type did not match the validated request purpose",
            context={
                "purpose": purpose.value,
                "contentType": media_type or "missing",
                "expectedContentTypes": sorted(expected),
            },
        )
    return media_type


class BoundedHttpClient:
    def __init__(
        self,
        *,
        session_factory: Callable[[], Any] = requests.Session,
        timeout_seconds: float = 20.0,
    ) -> None:
        self._session_factory = session_factory
        self._timeout_seconds = timeout_seconds

    def get(
        self,
        url: str,
        purpose: RequestPurpose,
        *,
        app_id: int | None = None,
    ) -> HttpResult:
        if not validate_request_url(url, purpose, app_id=app_id):
            raise NetworkError("request_rejected", "Initial request URL was rejected")
        current_url = url
        redirects: list[dict[str, Any]] = []

        for _ in range(MAX_NETWORK_HOPS):
            session = self._session_factory()
            response = None
            try:
                session.cookies.clear()
                session.headers.pop("Cookie", None)
                response = session.get(
                    current_url,
                    allow_redirects=False,
                    stream=True,
                    timeout=self._timeout_seconds,
                    headers={
                        "Accept": "application/json,text/html;q=0.9,*/*;q=0.1",
                        "Cache-Control": "no-store",
                        "User-Agent": "GameNative-Steam-Community-POC/0.1",
                    },
                )
                status = response.status_code
                if status in _REDIRECT_CODES:
                    location = response.headers.get("Location")
                    next_url = urljoin(current_url, location) if location else None
                    if next_url is None or not validate_request_url(
                        next_url, purpose, app_id=app_id
                    ):
                        raise NetworkError(
                            "redirect_rejected",
                            "Redirect did not preserve its validated request kind",
                            context={"url": current_url, "statusCode": status},
                        )
                    redirects.append(
                        {"statusCode": status, "fromUrl": current_url, "toUrl": next_url}
                    )
                    current_url = next_url
                    continue
                if not 200 <= status < 300:
                    raise NetworkError(
                        "http_status",
                        f"Steam returned HTTP {status}",
                        context={"url": current_url, "statusCode": status},
                    )
                content_type = _validated_content_type(response, purpose)
                body, body_bytes = self._read_bounded(response)
                return HttpResult(
                    body=body,
                    status_code=status,
                    final_url=current_url,
                    body_bytes=body_bytes,
                    redirects=redirects,
                    content_type=content_type,
                )
            except requests.RequestException as error:
                raise NetworkError(
                    "network_failure",
                    "Steam request failed",
                    context={"url": current_url, "errorType": type(error).__name__},
                ) from error
            finally:
                if response is not None:
                    response.close()
                session.cookies.clear()
                session.close()

        raise NetworkError(
            "redirect_limit",
            "Steam response exceeded the manual redirect limit",
            context={"url": current_url, "maxHops": MAX_NETWORK_HOPS},
        )

    @staticmethod
    def _read_bounded(response: Any) -> tuple[str, int]:
        declared = response.headers.get("Content-Length")
        if declared and declared.isascii() and declared.isdecimal() and int(declared) > MAX_BODY_BYTES:
            raise NetworkError(
                "body_too_large",
                "Steam response exceeded the body limit",
                context={"maxBytes": MAX_BODY_BYTES, "declaredBytes": int(declared)},
            )
        chunks: list[bytes] = []
        total = 0
        for chunk in response.iter_content(chunk_size=64 * 1024):
            if not chunk:
                continue
            total += len(chunk)
            if total > MAX_BODY_BYTES:
                raise NetworkError(
                    "body_too_large",
                    "Steam response exceeded the body limit",
                    context={"maxBytes": MAX_BODY_BYTES, "observedBytes": total},
                )
            chunks.append(chunk)
        encoding = response.encoding or "utf-8"
        return b"".join(chunks).decode(encoding, errors="replace"), total
