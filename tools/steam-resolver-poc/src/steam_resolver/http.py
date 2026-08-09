from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


MAX_BODY_BYTES = 2_000_000
USER_AGENT = "GameNative-Steam-Resolver-POC/1.0"


@dataclass(frozen=True)
class HttpResponse:
    status: int | None
    headers: Mapping[str, str]
    body: bytes
    endpoint: str
    error: str | None = None

    @property
    def content_type(self) -> str | None:
        value = self.headers.get("content-type") or self.headers.get("Content-Type")
        return value.split(";", 1)[0].strip() if value else None


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
    result.update(extra)
    return result
