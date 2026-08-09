"""Validation and construction of Steam discussion routes."""

from __future__ import annotations

import re
import unicodedata
from enum import Enum
from urllib.parse import parse_qsl, urlencode, urljoin, urlsplit

from .bounds import MAX_PAGE_NUMBER, MAX_ROUTE_CHARS
from .models import ValidationError

STEAM_COMMUNITY_ORIGIN = "https://steamcommunity.com"


class RouteKind(str, Enum):
    LISTING = "listing"
    THREAD = "thread"


def normalize_title(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).split()).casefold()


def parse_positive_app_id(value: str) -> int:
    if not value or not value.isascii() or not value.isdecimal():
        raise ValidationError("invalid_app_id", "Steam AppID must be a positive AppID")
    app_id = int(value)
    if app_id <= 0:
        raise ValidationError("invalid_app_id", "Steam AppID must be a positive AppID")
    return app_id


def validate_requested_count(name: str, value: int, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= maximum:
        raise ValidationError(
            "invalid_count",
            f"{name} must be between 1 and {maximum}",
            context={"name": name, "value": value, "maximum": maximum},
        )
    return value


def validate_discussion_route(app_id: int, value: str, kind: RouteKind) -> str | None:
    if app_id <= 0 or not value or len(value) > MAX_ROUTE_CHARS:
        return None
    try:
        parsed = urlsplit(urljoin(f"{STEAM_COMMUNITY_ORIGIN}/", value))
        port = parsed.port
    except ValueError:
        return None
    if (
        parsed.scheme != "https"
        or parsed.hostname != "steamcommunity.com"
        or port not in (None, 443)
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
    ):
        return None

    escaped_id = re.escape(str(app_id))
    listing_pattern = rf"/app/{escaped_id}/discussions/(?:[0-9]+/)?"
    thread_pattern = rf"/app/{escaped_id}/discussions/[0-9]+/[0-9]+/"
    pattern = listing_pattern if kind is RouteKind.LISTING else thread_pattern
    if re.fullmatch(pattern, parsed.path) is None:
        return None

    expected_key = "fp" if kind is RouteKind.LISTING else "ctp"
    try:
        pairs = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True)
    except ValueError:
        return None
    if pairs:
        if len(pairs) != 1 or pairs[0][0] != expected_key:
            return None
        page_text = pairs[0][1]
        if not page_text.isascii() or not page_text.isdecimal():
            return None
        page = int(page_text)
        if not 1 <= page <= MAX_PAGE_NUMBER:
            return None
    return parsed.path + (f"?{urlencode(pairs)}" if pairs else "")


def route_page(route: str, kind: RouteKind) -> int:
    parsed = urlsplit(route)
    key = "fp" if kind is RouteKind.LISTING else "ctp"
    pairs = dict(parse_qsl(parsed.query, keep_blank_values=True))
    return int(pairs.get(key, "1"))


def next_paging_route(route: str, kind: RouteKind, max_numeric_page: int | None) -> str | None:
    current = route_page(route, kind)
    if max_numeric_page is None or current >= min(max_numeric_page, MAX_PAGE_NUMBER):
        return None
    key = "fp" if kind is RouteKind.LISTING else "ctp"
    return f"{urlsplit(route).path}?{key}={current + 1}"
