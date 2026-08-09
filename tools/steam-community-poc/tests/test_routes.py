import pytest

from steam_community_poc.bounds import MAX_PAGES_PER_KIND
from steam_community_poc.models import ValidationError
from steam_community_poc.routes import (
    RouteKind,
    parse_positive_app_id,
    validate_discussion_route,
    validate_requested_count,
)


@pytest.mark.parametrize("value", ["0", "-1", "+1", "1.0", "", " 570 "])
def test_positive_app_id_rejects_noncanonical_or_nonpositive_values(value: str) -> None:
    with pytest.raises(ValidationError, match="positive AppID"):
        parse_positive_app_id(value)


def test_positive_app_id_accepts_decimal_digits() -> None:
    assert parse_positive_app_id("570") == 570


def test_requested_page_count_is_bounded() -> None:
    assert validate_requested_count("review pages", MAX_PAGES_PER_KIND, MAX_PAGES_PER_KIND) == 10
    with pytest.raises(ValidationError, match="between 1 and 10"):
        validate_requested_count("review pages", 11, MAX_PAGES_PER_KIND)


def test_listing_route_accepts_only_fp_and_preserves_kind() -> None:
    assert (
        validate_discussion_route(
            42,
            "https://steamcommunity.com/app/42/discussions/?fp=2",
            RouteKind.LISTING,
        )
        == "/app/42/discussions/?fp=2"
    )
    assert validate_discussion_route(42, "/app/42/discussions/?ctp=2", RouteKind.LISTING) is None
    assert validate_discussion_route(42, "/app/42/discussions/0/100/", RouteKind.LISTING) is None


def test_thread_route_accepts_only_ctp_and_preserves_app_id() -> None:
    assert (
        validate_discussion_route(42, "/app/42/discussions/0/100/?ctp=3", RouteKind.THREAD)
        == "/app/42/discussions/0/100/?ctp=3"
    )
    assert validate_discussion_route(42, "/app/42/discussions/0/100/?fp=3", RouteKind.THREAD) is None
    assert validate_discussion_route(42, "/app/99/discussions/0/100/", RouteKind.THREAD) is None


@pytest.mark.parametrize(
    "route",
    [
        "http://steamcommunity.com/app/42/discussions/",
        "https://user:pass@steamcommunity.com/app/42/discussions/",
        "https://steamcommunity.com:444/app/42/discussions/",
        "https://steamcommunity.com:notaport/app/42/discussions/",
        "https://evil.example/app/42/discussions/",
        "/app/42/discussions/?fp=2#fragment",
        "/app/42/discussions/?fp=0",
        "/app/42/discussions/?fp=101",
        "/app/42/discussions/?fp=2&fp=3",
    ],
)
def test_discussion_route_rejects_unsafe_network_shapes(route: str) -> None:
    assert validate_discussion_route(42, route, RouteKind.LISTING) is None
