from collections import deque
from datetime import UTC, datetime, timedelta
from email.utils import format_datetime

import pytest

from steam_community_poc.bounds import MAX_BODY_BYTES
from steam_community_poc.http import BoundedHttpClient, RequestPurpose, validate_request_url
from steam_community_poc.models import NetworkError, RateLimitError


class FakeCookies:
    def __init__(self) -> None:
        self.clear_count = 0

    def clear(self) -> None:
        self.clear_count += 1


class FakeResponse:
    def __init__(
        self,
        status_code: int,
        body: bytes = b"",
        *,
        headers: dict[str, str] | None = None,
        chunks: list[bytes] | None = None,
    ) -> None:
        self.status_code = status_code
        self.headers = {"Content-Type": "text/html; charset=utf-8", **(headers or {})}
        self.encoding = "utf-8"
        self._chunks = chunks if chunks is not None else [body]
        self.closed = False

    def iter_content(self, chunk_size: int) -> list[bytes]:
        return self._chunks

    def close(self) -> None:
        self.closed = True


class FakeSession:
    def __init__(self, response: FakeResponse) -> None:
        self.response = response
        self.cookies = FakeCookies()
        self.headers = {"Cookie": "must-be-removed"}
        self.calls: list[tuple[str, dict[str, object]]] = []
        self.closed = False

    def get(self, url: str, **kwargs: object) -> FakeResponse:
        self.calls.append((url, kwargs))
        return self.response

    def close(self) -> None:
        self.closed = True


class SessionFactory:
    def __init__(self, responses: list[FakeResponse]) -> None:
        self.responses = deque(responses)
        self.sessions: list[FakeSession] = []

    def __call__(self) -> FakeSession:
        session = FakeSession(self.responses.popleft())
        self.sessions.append(session)
        return session


def test_manual_redirect_uses_fresh_cookie_free_session_for_every_hop() -> None:
    factory = SessionFactory(
        [
            FakeResponse(302, headers={"Location": "/app/42/discussions/?fp=2"}),
            FakeResponse(200, b"final"),
        ]
    )
    client = BoundedHttpClient(session_factory=factory)

    result = client.get(
        "https://steamcommunity.com/app/42/discussions/",
        RequestPurpose.DISCUSSION_LISTING,
        app_id=42,
    )

    assert result.body == "final"
    assert result.final_url == "https://steamcommunity.com/app/42/discussions/?fp=2"
    assert len(result.redirects) == 1
    assert len(factory.sessions) == 2
    assert all(session.closed for session in factory.sessions)
    assert all(session.response.closed for session in factory.sessions)
    assert all(session.cookies.clear_count >= 2 for session in factory.sessions)
    for session in factory.sessions:
        assert "Cookie" not in session.headers
        _, kwargs = session.calls[0]
        assert kwargs["allow_redirects"] is False
        assert kwargs["stream"] is True
        assert "Cookie" not in kwargs["headers"]


def test_discussion_request_preserves_requests_server_rendered_user_agent() -> None:
    factory = SessionFactory([FakeResponse(200, b"final")])

    BoundedHttpClient(session_factory=factory).get(
        "https://steamcommunity.com/app/42/discussions/",
        RequestPurpose.DISCUSSION_LISTING,
        app_id=42,
    )

    _, kwargs = factory.sessions[0].calls[0]
    assert "User-Agent" not in kwargs["headers"]


def test_redirect_must_preserve_listing_route_kind() -> None:
    factory = SessionFactory(
        [FakeResponse(302, headers={"Location": "/app/42/discussions/0/100/"})]
    )
    client = BoundedHttpClient(session_factory=factory)

    with pytest.raises(NetworkError) as caught:
        client.get(
            "https://steamcommunity.com/app/42/discussions/",
            RequestPurpose.DISCUSSION_LISTING,
            app_id=42,
        )

    assert caught.value.code == "redirect_rejected"
    assert len(factory.sessions) == 1


def test_decoded_response_body_is_bounded_without_content_length() -> None:
    factory = SessionFactory(
        [FakeResponse(200, chunks=[b"x" * 600_000, b"y" * 600_000])]
    )

    with pytest.raises(NetworkError) as caught:
        BoundedHttpClient(session_factory=factory).get(
            "https://steamcommunity.com/app/42/discussions/",
            RequestPurpose.DISCUSSION_LISTING,
            app_id=42,
        )

    assert caught.value.code == "body_too_large"
    assert caught.value.context["maxBytes"] == MAX_BODY_BYTES


def test_declared_response_body_is_rejected_before_iteration() -> None:
    factory = SessionFactory(
        [FakeResponse(200, headers={"Content-Length": str(MAX_BODY_BYTES + 1)})]
    )

    with pytest.raises(NetworkError, match="body limit"):
        BoundedHttpClient(session_factory=factory).get(
            "https://steamcommunity.com/app/42/discussions/",
            RequestPurpose.DISCUSSION_LISTING,
            app_id=42,
        )


def test_rate_limit_retries_then_returns_attempt_and_delay_diagnostics() -> None:
    factory = SessionFactory(
        [
            FakeResponse(429, headers={"Retry-After": "2"}),
            FakeResponse(200, b"final"),
        ]
    )
    delays: list[float] = []

    result = BoundedHttpClient(
        session_factory=factory, sleeper=delays.append
    ).get(
        "https://steamcommunity.com/app/42/discussions/",
        RequestPurpose.DISCUSSION_LISTING,
        app_id=42,
    )

    assert result.body == "final"
    assert delays == [2.0]
    assert result.attempts == [
        {
            "attempt": 1,
            "statusCode": 429,
            "retryDelaySeconds": 2.0,
            "retryAfterSource": "seconds",
        },
        {"attempt": 2, "statusCode": 200},
    ]
    assert len(factory.sessions) == 2


def test_very_large_numeric_retry_after_is_safely_capped() -> None:
    factory = SessionFactory(
        [
            FakeResponse(429, headers={"Retry-After": "9" * 10_000}),
            FakeResponse(200, b"final"),
        ]
    )
    delays: list[float] = []

    result = BoundedHttpClient(
        session_factory=factory, sleeper=delays.append
    ).get(
        "https://steamcommunity.com/app/42/discussions/",
        RequestPurpose.DISCUSSION_LISTING,
        app_id=42,
    )

    assert delays == [30.0]
    assert result.attempts[0]["retryAfterSource"] == "seconds"


def test_http_date_retry_after_is_capped_at_thirty_seconds() -> None:
    now = datetime(2026, 8, 10, tzinfo=UTC)
    factory = SessionFactory(
        [
            FakeResponse(
                429,
                headers={"Retry-After": format_datetime(now + timedelta(seconds=90))},
            ),
            FakeResponse(200, b"final"),
        ]
    )
    delays: list[float] = []

    result = BoundedHttpClient(
        session_factory=factory,
        sleeper=delays.append,
        clock=lambda: now,
    ).get(
        "https://steamcommunity.com/app/42/discussions/",
        RequestPurpose.DISCUSSION_LISTING,
        app_id=42,
    )

    assert delays == [30.0]
    assert result.attempts[0]["retryAfterSource"] == "http_date"


def test_missing_or_invalid_retry_after_uses_one_two_four_fallback() -> None:
    factory = SessionFactory(
        [
            FakeResponse(429),
            FakeResponse(429, headers={"Retry-After": "invalid"}),
            FakeResponse(429),
            FakeResponse(200, b"final"),
        ]
    )
    delays: list[float] = []

    result = BoundedHttpClient(
        session_factory=factory, sleeper=delays.append
    ).get(
        "https://steamcommunity.com/app/42/discussions/",
        RequestPurpose.DISCUSSION_LISTING,
        app_id=42,
    )

    assert delays == [1.0, 2.0, 4.0]
    assert [attempt.get("retryAfterSource") for attempt in result.attempts[:-1]] == [
        "fallback",
        "fallback",
        "fallback",
    ]
    assert len(factory.sessions) == 4


def test_persistent_rate_limit_exhausts_after_four_total_get_attempts() -> None:
    factory = SessionFactory([FakeResponse(429) for _ in range(4)])
    delays: list[float] = []

    with pytest.raises(RateLimitError) as caught:
        BoundedHttpClient(
            session_factory=factory, sleeper=delays.append
        ).get(
            "https://steamcommunity.com/app/42/discussions/",
            RequestPurpose.DISCUSSION_LISTING,
            app_id=42,
        )

    assert caught.value.code == "steam_rate_limited"
    assert delays == [1.0, 2.0, 4.0]
    assert len(factory.sessions) == 4
    assert len(caught.value.context["attempts"]) == 4


def test_success_response_content_type_must_match_request_purpose() -> None:
    factory = SessionFactory(
        [FakeResponse(200, b'{}', headers={"Content-Type": "application/json"})]
    )

    with pytest.raises(NetworkError) as caught:
        BoundedHttpClient(session_factory=factory).get(
            "https://steamcommunity.com/app/42/discussions/",
            RequestPurpose.DISCUSSION_LISTING,
            app_id=42,
        )

    assert caught.value.code == "unexpected_content_type"
    assert caught.value.context["contentType"] == "application/json"


def test_review_json_content_type_is_accepted_and_recorded() -> None:
    url = (
        "https://store.steampowered.com/appreviews/42?json=1&filter=all&language=all"
        "&review_type=all&purchase_type=all&num_per_page=20"
    )
    factory = SessionFactory(
        [FakeResponse(200, b'{"success":1}', headers={"Content-Type": "application/json; charset=utf-8"})]
    )

    result = BoundedHttpClient(session_factory=factory).get(
        url, RequestPurpose.REVIEWS, app_id=42
    )

    assert result.content_type == "application/json"


def test_request_url_validation_is_purpose_specific() -> None:
    valid_review = (
        "https://store.steampowered.com/appreviews/42?json=1&filter=all&language=all"
        "&review_type=all&purchase_type=all&num_per_page=20"
    )
    assert validate_request_url(valid_review, RequestPurpose.REVIEWS, app_id=42)
    assert not validate_request_url(valid_review.replace("/42?", "/99?"), RequestPurpose.REVIEWS, app_id=42)
    assert not validate_request_url(
        "https://store.steampowered.com/appreviews/42?json=1&cookie=secret",
        RequestPurpose.REVIEWS,
        app_id=42,
    )
    assert not validate_request_url(
        "https://steamcommunity.com/app/42/discussions/?fp=2",
        RequestPurpose.DISCUSSION_THREAD,
        app_id=42,
    )
