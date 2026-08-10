from __future__ import annotations

import math
import time
from dataclasses import dataclass, replace
from datetime import timezone
from email.utils import parsedate_to_datetime
from typing import Any, Callable, Mapping
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


MAX_BODY_BYTES = 2_000_000
MAX_RATE_LIMIT_ATTEMPTS = 4
MAX_RETRY_DELAY_SECONDS = 30.0
USER_AGENT = "GameNative-Steam-Resolver-POC/1.0"


@dataclass(frozen=True)
class HttpResponse:
    status: int | None
    headers: Mapping[str, str]
    body: bytes
    endpoint: str
    error: str | None = None
    attempts: tuple[dict[str, Any], ...] = ()

    @property
    def content_type(self) -> str | None:
        value = self.headers.get("content-type") or self.headers.get("Content-Type")
        return value.split(";", 1)[0].strip() if value else None


class RateLimitExhausted(RuntimeError):
    def __init__(self, endpoint: str, attempts: tuple[dict[str, Any], ...]) -> None:
        self.endpoint = endpoint
        self.attempts = attempts
        super().__init__(
            f"Provider remained rate limited after {len(attempts)} GET attempts"
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "error": "RATE_LIMIT_EXHAUSTED",
            "message": str(self),
            "endpoint": self.endpoint,
            "attempts": list(self.attempts),
        }


class UrllibTransport:
    def get(
        self,
        url: str,
        *,
        params: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
        timeout: float = 10.0,
    ) -> HttpResponse:
        endpoint = url
        if params:
            endpoint = f"{url}?{urlencode(params)}"
        request_headers = {"User-Agent": USER_AGENT, "Accept": "application/json,text/plain;q=0.8"}
        request_headers.update(headers or {})
        request = Request(endpoint, headers=request_headers, method="GET")
        try:
            with urlopen(request, timeout=timeout) as response:
                body = response.read(MAX_BODY_BYTES + 1)[:MAX_BODY_BYTES]
                return HttpResponse(
                    status=response.status,
                    headers=dict(response.headers.items()),
                    body=body,
                    endpoint=response.geturl(),
                )
        except HTTPError as error:
            body = error.read(MAX_BODY_BYTES + 1)[:MAX_BODY_BYTES]
            return HttpResponse(
                status=error.code,
                headers=dict(error.headers.items()) if error.headers else {},
                body=body,
                endpoint=endpoint,
                error=None,
            )
        except (URLError, TimeoutError, OSError) as error:
            reason = getattr(error, "reason", error)
            return HttpResponse(
                status=None,
                headers={},
                body=b"",
                endpoint=endpoint,
                error=f"{type(reason).__name__}: {reason}",
            )


class RetryingTransport:
    def __init__(
        self,
        transport: Any,
        *,
        sleeper: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.transport = transport
        self.sleeper = sleeper
        self.clock = clock

    def get(
        self,
        url: str,
        *,
        params: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
        timeout: float = 10.0,
    ) -> HttpResponse:
        attempts: list[dict[str, Any]] = []
        for attempt in range(1, MAX_RATE_LIMIT_ATTEMPTS + 1):
            response = self.transport.get(
                url,
                params=params,
                headers=headers,
                timeout=timeout,
            )
            if response.status != 429:
                attempts.append(
                    {"attempt": attempt, "status": response.status, "delaySeconds": 0.0}
                )
                return replace(response, attempts=tuple(attempts))
            if attempt == MAX_RATE_LIMIT_ATTEMPTS:
                attempts.append(
                    {"attempt": attempt, "status": 429, "delaySeconds": 0.0}
                )
                raise RateLimitExhausted(response.endpoint, tuple(attempts))
            delay = _retry_delay_seconds(response.headers, attempt, self.clock())
            attempts.append(
                {"attempt": attempt, "status": 429, "delaySeconds": delay}
            )
            self.sleeper(delay)
        raise AssertionError("rate-limit retry loop must return or raise")


def ensure_retrying_transport(
    transport: Any,
    *,
    sleeper: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.time,
) -> RetryingTransport:
    if isinstance(transport, RetryingTransport):
        return transport
    return RetryingTransport(transport, sleeper=sleeper, clock=clock)


def _retry_delay_seconds(
    headers: Mapping[str, str], attempt: int, now_epoch_seconds: float
) -> float:
    retry_after = next(
        (value for key, value in headers.items() if key.casefold() == "retry-after"),
        None,
    )
    if retry_after is not None:
        value = retry_after.strip()
        try:
            numeric = float(value)
        except ValueError:
            numeric = None
        if numeric is not None and math.isfinite(numeric) and numeric >= 0:
            return min(numeric, MAX_RETRY_DELAY_SECONDS)
        try:
            parsed = parsedate_to_datetime(value)
        except (TypeError, ValueError, OverflowError):
            parsed = None
        if parsed is not None:
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            delay = max(0.0, parsed.timestamp() - now_epoch_seconds)
            return min(delay, MAX_RETRY_DELAY_SECONDS)
    return float(2 ** (attempt - 1))


def diagnostic(
    response: HttpResponse,
    parser: str,
    **extra: Any,
) -> dict[str, Any]:
    result = {
        "endpoint": response.endpoint,
        "status": response.status,
        "contentType": response.content_type,
        "bodyBytes": len(response.body),
        "parser": parser,
        "error": response.error,
    }
    if response.attempts:
        result["attempts"] = list(response.attempts)
    result.update(extra)
    return result
