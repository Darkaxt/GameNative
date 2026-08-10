from datetime import datetime, timezone
from email.utils import format_datetime

import pytest

from steam_resolver.http import (
    HttpResponse,
    RateLimitExhausted,
    RetryingTransport,
    diagnostic,
)


class SequenceTransport:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def get(self, url, *, params=None, headers=None, timeout=10.0):
        self.calls.append(
            {"url": url, "params": params or {}, "headers": headers or {}, "timeout": timeout}
        )
        return self.responses.pop(0)


def response(status, *, retry_after=None):
    headers = {"content-type": "application/json"}
    if retry_after is not None:
        headers["Retry-After"] = retry_after
    return HttpResponse(
        status=status,
        headers=headers,
        body=b"{}",
        endpoint="https://store.steampowered.com/api/appdetails?appids=208580",
    )


def test_429_then_200_retries_once_and_records_attempt_diagnostics():
    sleeps = []
    base = SequenceTransport([response(429), response(200)])
    transport = RetryingTransport(base, sleeper=sleeps.append, clock=lambda: 1_700_000_000.0)

    result = transport.get("https://store.steampowered.com/api/appdetails")

    assert result.status == 200
    assert sleeps == [1.0]
    assert len(base.calls) == 2
    assert result.attempts == (
        {"attempt": 1, "status": 429, "delaySeconds": 1.0},
        {"attempt": 2, "status": 200, "delaySeconds": 0.0},
    )
    assert diagnostic(result, "APPDETAILS_OK")["attempts"] == list(result.attempts)


def test_repeated_429_exhausts_after_four_attempts_and_three_sleeps():
    sleeps = []
    base = SequenceTransport([response(429) for _ in range(4)])
    transport = RetryingTransport(base, sleeper=sleeps.append, clock=lambda: 0.0)

    with pytest.raises(RateLimitExhausted) as raised:
        transport.get("https://store.steampowered.com/api/appdetails")

    assert len(base.calls) == 4
    assert sleeps == [1.0, 2.0, 4.0]
    assert raised.value.attempts == (
        {"attempt": 1, "status": 429, "delaySeconds": 1.0},
        {"attempt": 2, "status": 429, "delaySeconds": 2.0},
        {"attempt": 3, "status": 429, "delaySeconds": 4.0},
        {"attempt": 4, "status": 429, "delaySeconds": 0.0},
    )


def test_retry_after_numeric_and_http_date_are_honored_and_capped():
    numeric_sleeps = []
    numeric = RetryingTransport(
        SequenceTransport([response(429, retry_after="120"), response(200)]),
        sleeper=numeric_sleeps.append,
        clock=lambda: 1_700_000_000.0,
    )
    numeric.get("https://store.steampowered.com/api/appdetails")

    now = 1_700_000_000.0
    retry_date = format_datetime(
        datetime.fromtimestamp(now + 12, tz=timezone.utc), usegmt=True
    )
    date_sleeps = []
    dated = RetryingTransport(
        SequenceTransport([response(429, retry_after=retry_date), response(200)]),
        sleeper=date_sleeps.append,
        clock=lambda: now,
    )
    dated.get("https://store.steampowered.com/api/appdetails")

    assert numeric_sleeps == [30.0]
    assert date_sleeps == [12.0]


def test_successful_first_attempt_never_sleeps():
    sleeps = []
    base = SequenceTransport([response(200)])

    result = RetryingTransport(base, sleeper=sleeps.append).get(
        "https://store.steampowered.com/api/appdetails"
    )

    assert result.status == 200
    assert sleeps == []
    assert result.attempts == (
        {"attempt": 1, "status": 200, "delaySeconds": 0.0},
    )
