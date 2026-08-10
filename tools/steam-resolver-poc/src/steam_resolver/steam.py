from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any, Iterable

from .http import (
    HttpResponse,
    UrllibTransport,
    diagnostic,
    ensure_retrying_transport,
)
from .models import AppType, ProviderBatch, SteamCandidate
from .normalization import normalize_title


STORESEARCH_URL = "https://store.steampowered.com/api/storesearch/"
APPDETAILS_URL = "https://store.steampowered.com/api/appdetails"
ISTORE_APPLIST_URL = "https://api.steampowered.com/IStoreService/GetAppList/v1/"
_YEAR = re.compile(r"(?:19|20)\d{2}")


@dataclass(frozen=True)
class _SearchHit:
    app_id: int
    title: str


def _json_payload(response: HttpResponse) -> tuple[Any | None, str | None]:
    if response.status is None or not 200 <= response.status < 300:
        return None, "HTTP_ERROR"
    try:
        return json.loads(response.body.decode("utf-8")), None
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None, "MALFORMED_JSON"


def _first_string(value: Any) -> str | None:
    if not isinstance(value, list):
        return None
    for item in value:
        if isinstance(item, str) and item.strip():
            return item.strip()
    return None


def _release_year(data: dict[str, Any]) -> int | None:
    release_date = data.get("release_date")
    if not isinstance(release_date, dict):
        return None
    date = release_date.get("date")
    if not isinstance(date, str):
        return None
    match = _YEAR.search(date)
    return int(match.group()) if match else None


def _verify_hits(
    hits: Iterable[_SearchHit],
    *,
    transport: Any,
    diagnostics: list[dict[str, Any]],
    timeout: float,
) -> tuple[list[SteamCandidate], bool]:
    candidates: list[SteamCandidate] = []
    partial = False
    for hit in hits:
        response = transport.get(
            APPDETAILS_URL,
            params={"appids": hit.app_id, "l": "english", "cc": "US"},
            timeout=timeout,
        )
        payload, parser_error = _json_payload(response)
        if parser_error:
            diagnostics.append(diagnostic(response, parser_error, steamAppId=hit.app_id))
            candidates.append(_unverified(hit))
            partial = True
            continue
        expected_key = str(hit.app_id)
        if not isinstance(payload, dict) or set(payload) != {expected_key}:
            diagnostics.append(
                diagnostic(response, "APPDETAILS_KEY_MISMATCH", steamAppId=hit.app_id)
            )
            candidates.append(_unverified(hit))
            partial = True
            continue
        record = payload[expected_key]
        data = record.get("data") if isinstance(record, dict) and record.get("success") is True else None
        if not isinstance(data, dict) or data.get("steam_appid") != hit.app_id:
            diagnostics.append(
                diagnostic(response, "APPDETAILS_SCHEMA_ERROR", steamAppId=hit.app_id)
            )
            candidates.append(_unverified(hit))
            partial = True
            continue
        if data.get("type") != "game":
            diagnostics.append(
                diagnostic(response, "APPDETAILS_NON_GAME", steamAppId=hit.app_id)
            )
            continue
        title = data.get("name")
        if not isinstance(title, str) or not title.strip():
            diagnostics.append(
                diagnostic(response, "APPDETAILS_SCHEMA_ERROR", steamAppId=hit.app_id)
            )
            candidates.append(_unverified(hit))
            partial = True
            continue
        candidates.append(
            SteamCandidate(
                steam_app_id=hit.app_id,
                title=title.strip(),
                developer=_first_string(data.get("developers")),
                publisher=_first_string(data.get("publishers")),
                release_year=_release_year(data),
                app_type=AppType.GAME,
                verified=True,
            )
        )
        diagnostics.append(diagnostic(response, "APPDETAILS_OK", steamAppId=hit.app_id))
    return candidates, partial


def _unverified(hit: _SearchHit) -> SteamCandidate:
    return SteamCandidate(
        steam_app_id=hit.app_id,
        title=hit.title,
        developer=None,
        publisher=None,
        release_year=None,
        app_type=AppType.UNKNOWN,
        verified=False,
    )


class SteamStoreProvider:
    name = "storesearch"

    def __init__(
        self,
        *,
        transport: Any | None = None,
        max_search_candidates: int = 15,
        timeout: float = 10.0,
        sleeper: Any = time.sleep,
        clock: Any = time.time,
    ) -> None:
        if not 1 <= max_search_candidates <= 50:
            raise ValueError("max_search_candidates must be between 1 and 50")
        self.transport = ensure_retrying_transport(
            transport or UrllibTransport(), sleeper=sleeper, clock=clock
        )
        self.max_search_candidates = max_search_candidates
        self.timeout = timeout

    def retrieve(self, queries: tuple[str, ...]) -> ProviderBatch:
        diagnostics: list[dict[str, Any]] = []
        hits_by_id: dict[int, _SearchHit] = {}
        successful_searches = 0
        failed_searches = 0
        for query in dict.fromkeys(queries):
            response = self.transport.get(
                STORESEARCH_URL,
                params={"term": query, "l": "english", "cc": "US"},
                timeout=self.timeout,
            )
            payload, parser_error = _json_payload(response)
            if parser_error:
                diagnostics.append(diagnostic(response, parser_error, query=query))
                failed_searches += 1
                continue
            if not isinstance(payload, dict) or not isinstance(payload.get("items", []), list):
                diagnostics.append(diagnostic(response, "STORESEARCH_SCHEMA_ERROR", query=query))
                failed_searches += 1
                continue
            successful_searches += 1
            accepted = 0
            for item in payload.get("items", []):
                if not isinstance(item, dict) or item.get("type") not in (None, "app"):
                    continue
                app_id = item.get("id")
                title = item.get("name")
                if (
                    isinstance(app_id, int)
                    and not isinstance(app_id, bool)
                    and app_id > 0
                    and isinstance(title, str)
                    and title.strip()
                ):
                    hits_by_id.setdefault(app_id, _SearchHit(app_id, title.strip()))
                    accepted += 1
            diagnostics.append(
                diagnostic(response, "STORESEARCH_OK", query=query, parsedCandidates=accepted)
            )

        if successful_searches == 0:
            return ProviderBatch(
                diagnostics=tuple(diagnostics),
                provider_unavailable=True,
                partial=True,
                warnings=("No Steam storesearch query produced a parseable success response.",),
            )
        search_partial = failed_searches > 0
        hits = list(hits_by_id.values())[: self.max_search_candidates]
        if not hits and search_partial:
            return ProviderBatch(
                diagnostics=tuple(diagnostics),
                provider_unavailable=True,
                partial=True,
                warnings=("Steam storesearch was incomplete and produced no candidates.",),
            )
        candidates, detail_partial = _verify_hits(
            hits,
            transport=self.transport,
            diagnostics=diagnostics,
            timeout=self.timeout,
        )
        partial = search_partial or detail_partial
        warnings: list[str] = []
        if search_partial:
            warnings.append("One or more Steam storesearch queries failed.")
        if detail_partial:
            warnings.append(
                "One or more Steam appdetails responses were incomplete; search hits are review-only."
            )
        return ProviderBatch(
            candidates=tuple(candidates),
            diagnostics=tuple(diagnostics),
            partial=partial,
            warnings=tuple(warnings),
        )


class CachedIndexProvider:
    name = "cached-index"

    def __init__(
        self,
        index_path: str | Path,
        *,
        transport: Any | None = None,
        max_search_candidates: int = 15,
        timeout: float = 10.0,
        sleeper: Any = time.sleep,
        clock: Any = time.time,
    ) -> None:
        self.index_path = Path(index_path)
        self.transport = ensure_retrying_transport(
            transport or UrllibTransport(), sleeper=sleeper, clock=clock
        )
        self.max_search_candidates = max_search_candidates
        self.timeout = timeout

    def retrieve(self, queries: tuple[str, ...]) -> ProviderBatch:
        payload = json.loads(self.index_path.read_text(encoding="utf-8"))
        apps = payload.get("apps")
        if not isinstance(apps, list):
            raise ValueError("cached Steam index is missing apps")
        query_keys = {normalize_title(query) for query in queries if normalize_title(query)}
        ranked: list[tuple[float, int, str]] = []
        exact: list[tuple[float, int, str]] = []
        for app in apps:
            if not isinstance(app, dict):
                continue
            app_id = app.get("appid")
            name = app.get("name")
            if not isinstance(app_id, int) or app_id <= 0 or not isinstance(name, str):
                continue
            name_key = normalize_title(name)
            if name_key in query_keys:
                exact.append((1.0, app_id, name))
                continue
            similarity = max(
                (SequenceMatcher(None, key, name_key).ratio() for key in query_keys),
                default=0.0,
            )
            if similarity >= 0.72:
                ranked.append((similarity, app_id, name))
        selected = exact or ranked
        selected.sort(key=lambda value: (-value[0], value[1]))
        hits = [_SearchHit(app_id, name) for _, app_id, name in selected[: self.max_search_candidates]]
        diagnostics: list[dict[str, Any]] = [
            {
                "endpoint": str(self.index_path),
                "status": 200,
                "contentType": "application/json",
                "bodyBytes": self.index_path.stat().st_size,
                "parser": "CACHED_INDEX_OK",
                "error": None,
                "parsedCandidates": len(hits),
            }
        ]
        candidates, partial = _verify_hits(
            hits,
            transport=self.transport,
            diagnostics=diagnostics,
            timeout=self.timeout,
        )
        return ProviderBatch(
            candidates=tuple(candidates), diagnostics=tuple(diagnostics), partial=partial
        )


class FixtureProvider:
    name = "recorded-fixture"

    def __init__(self, fixture_path: str | Path) -> None:
        self.fixture_path = Path(fixture_path)
        payload = json.loads(self.fixture_path.read_text(encoding="utf-8"))
        apps = payload.get("apps")
        if not isinstance(apps, list):
            raise ValueError("fixture is missing apps")
        self.apps = tuple(apps)

    def retrieve(self, queries: tuple[str, ...]) -> ProviderBatch:
        query_keys = {normalize_title(query) for query in queries if normalize_title(query)}
        candidates: list[SteamCandidate] = []
        for app in self.apps:
            if normalize_title(app.get("title")) not in query_keys:
                continue
            candidates.append(
                SteamCandidate(
                    steam_app_id=app["steamAppId"],
                    title=app["title"],
                    developer=app.get("developer"),
                    publisher=app.get("publisher"),
                    release_year=app.get("releaseYear"),
                    app_type=AppType(app.get("appType", "UNKNOWN")),
                    verified=True,
                )
            )
        candidates.sort(key=lambda item: item.steam_app_id)
        diagnostic_item = {
            "endpoint": str(self.fixture_path),
            "status": 200,
            "contentType": "application/json",
            "bodyBytes": self.fixture_path.stat().st_size,
            "parser": "FIXTURE_CATALOG_OK",
            "error": None,
            "parsedCandidates": len(candidates),
        }
        return ProviderBatch(candidates=tuple(candidates), diagnostics=(diagnostic_item,))


def refresh_istore_index(
    transport: Any,
    target: str | Path,
    *,
    api_key: str,
    timeout: float = 30.0,
    max_pages: int = 100,
    sleeper: Any = time.sleep,
    clock: Any = time.time,
) -> dict[str, Any]:
    if not api_key:
        raise ValueError("STEAM_WEB_API_KEY is required")
    active_transport = ensure_retrying_transport(
        transport, sleeper=sleeper, clock=clock
    )
    apps_by_id: dict[int, str] = {}
    last_appid = 0
    pages = 0
    while pages < max_pages:
        params: dict[str, Any] = {
            "include_games": "true",
            "max_results": 50000,
        }
        if last_appid:
            params["last_appid"] = last_appid
        response = active_transport.get(
            ISTORE_APPLIST_URL,
            params=params,
            headers={"x-webapi-key": api_key},
            timeout=timeout,
        )
        payload, parser_error = _json_payload(response)
        if parser_error:
            raise RuntimeError(
                f"IStoreService/GetAppList failed: status={response.status} parser={parser_error}"
            )
        container = payload.get("response") if isinstance(payload, dict) else None
        page_apps = container.get("apps") if isinstance(container, dict) else None
        if not isinstance(page_apps, list):
            raise ValueError("IStoreService/GetAppList response is missing apps")
        for app in page_apps:
            if not isinstance(app, dict):
                continue
            app_id = app.get("appid")
            name = app.get("name")
            if isinstance(app_id, int) and app_id > 0 and isinstance(name, str) and name.strip():
                apps_by_id[app_id] = name.strip()
        pages += 1
        if not container.get("have_more_results"):
            break
        next_last = container.get("last_appid")
        if not isinstance(next_last, int) or next_last <= last_appid:
            raise ValueError("IStoreService pagination did not advance last_appid")
        last_appid = next_last
    else:
        raise RuntimeError("IStoreService index refresh exceeded page bound")

    output = {
        "schemaVersion": 1,
        "provider": "IStoreService/GetAppList/v1",
        "apps": [
            {"appid": app_id, "name": apps_by_id[app_id]} for app_id in sorted(apps_by_id)
        ],
    }
    target_path = Path(target)
    target_path.parent.mkdir(parents=True, exist_ok=True)
    target_path.write_text(
        json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return {"appCount": len(apps_by_id), "pageCount": pages, "path": str(target_path)}
