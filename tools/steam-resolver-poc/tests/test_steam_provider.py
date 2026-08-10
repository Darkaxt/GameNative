import json

import pytest

from steam_resolver.http import HttpResponse, RateLimitExhausted
from steam_resolver.steam import CachedIndexProvider, SteamStoreProvider, refresh_istore_index


class FakeTransport:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def get(self, url, *, params=None, headers=None, timeout=10.0):
        self.calls.append(
            {"url": url, "params": params or {}, "headers": headers or {}, "timeout": timeout}
        )
        response = self.responses.pop(0)
        if callable(response):
            return response(url, params or {}, headers or {})
        return response


def response(payload, *, status=200, content_type="application/json", endpoint="https://example.test"):
    body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
    return HttpResponse(
        status=status,
        headers={"content-type": content_type},
        body=body,
        endpoint=endpoint,
    )


def appdetails(app_id=870780, *, app_type="game", name="Control Ultimate Edition"):
    return {
        str(app_id): {
            "success": True,
            "data": {
                "steam_appid": app_id,
                "type": app_type,
                "name": name,
                "developers": ["Remedy Entertainment"],
                "publishers": ["505 Games"],
                "release_date": {"date": "27 Aug, 2020"},
            },
        }
    }


def test_storesearch_candidates_are_deduplicated_bounded_and_appdetails_verified():
    search = {
        "total": 3,
        "items": [
            {"type": "app", "id": 870780, "name": "Control Ultimate Edition"},
            {"type": "app", "id": 870780, "name": "duplicate"},
            {"type": "app", "id": 111, "name": "Control Soundtrack"},
        ],
    }
    transport = FakeTransport(
        [response(search), response(search), response(appdetails()), response(appdetails(111, app_type="music"))]
    )
    provider = SteamStoreProvider(transport=transport, max_search_candidates=2)

    batch = provider.retrieve(("Control Ultimate Edition", "control ultimate edition"))

    assert [candidate.steam_app_id for candidate in batch.candidates] == [870780]
    assert batch.candidates[0].verified is True
    assert batch.candidates[0].release_year == 2020
    assert batch.provider_unavailable is False
    assert batch.partial is False
    detail_calls = [call for call in transport.calls if call["url"].endswith("/appdetails")]
    assert len(detail_calls) == 2
    assert all(set(call["params"]) >= {"appids", "l", "cc"} for call in detail_calls)


def test_appdetails_requires_matching_key_and_game_type():
    transport = FakeTransport(
        [
            response({"total": 1, "items": [{"type": "app", "id": 870780, "name": "Control"}]}),
            response(appdetails(app_id=999)),
        ]
    )

    batch = SteamStoreProvider(transport=transport).retrieve(("Control",))

    assert batch.partial is True
    assert len(batch.candidates) == 1
    assert batch.candidates[0].steam_app_id == 870780
    assert batch.candidates[0].verified is False
    assert any(diagnostic["parser"] == "APPDETAILS_KEY_MISMATCH" for diagnostic in batch.diagnostics)


def test_malformed_json_is_provider_unavailable_not_unmatched():
    malformed = FakeTransport([response(b"{not json")])

    malformed_batch = SteamStoreProvider(transport=malformed).retrieve(("Control",))

    assert malformed_batch.provider_unavailable is True
    assert malformed_batch.diagnostics[0]["parser"] == "MALFORMED_JSON"


def test_exhausted_appdetails_rate_limit_aborts_before_other_candidates():
    search = {
        "total": 2,
        "items": [
            {"type": "app", "id": 870780, "name": "Control Ultimate Edition"},
            {"type": "app", "id": 111, "name": "Control Soundtrack"},
        ],
    }
    sleeps = []
    transport = FakeTransport(
        [
            response(search),
            *[response({"error": "limited"}, status=429) for _ in range(4)],
            response(appdetails(111)),
        ]
    )
    provider = SteamStoreProvider(
        transport=transport,
        max_search_candidates=2,
        sleeper=sleeps.append,
        clock=lambda: 0.0,
    )

    with pytest.raises(RateLimitExhausted):
        provider.retrieve(("Control Ultimate Edition",))

    assert sleeps == [1.0, 2.0, 4.0]
    assert len(transport.calls) == 5
    assert all(
        call["params"].get("appids") == 870780 for call in transport.calls[1:]
    )
    assert len(transport.responses) == 1


def test_exhausted_storesearch_rate_limit_aborts_instead_of_partial_outcome():
    sleeps = []
    transport = FakeTransport(
        [response({"error": "limited"}, status=429) for _ in range(4)]
    )
    provider = SteamStoreProvider(
        transport=transport,
        sleeper=sleeps.append,
        clock=lambda: 0.0,
    )

    with pytest.raises(RateLimitExhausted):
        provider.retrieve(("Disco Elysium - The Final Cut",))

    assert len(transport.calls) == 4
    assert sleeps == [1.0, 2.0, 4.0]


def test_timeout_is_reported_without_body_or_false_no_match():
    transport = FakeTransport(
        [
            HttpResponse(
                status=None,
                headers={},
                body=b"",
                endpoint="https://store.steampowered.com/api/storesearch/",
                error="TimeoutError: timed out",
            )
        ]
    )

    batch = SteamStoreProvider(transport=transport).retrieve(("Control",))

    assert batch.provider_unavailable is True
    assert batch.diagnostics[0]["bodyBytes"] == 0
    assert batch.diagnostics[0]["error"] == "TimeoutError: timed out"


def test_partial_details_retain_search_evidence_for_review():
    transport = FakeTransport(
        [
            response(
                {
                    "total": 1,
                    "items": [
                        {"type": "app", "id": 870780, "name": "Control Ultimate Edition"}
                    ],
                }
            ),
            response(b"<html>blocked</html>", status=503, content_type="text/html"),
        ]
    )

    batch = SteamStoreProvider(transport=transport).retrieve(("Control Ultimate Edition",))

    assert batch.provider_unavailable is False
    assert batch.partial is True
    assert len(batch.candidates) == 1
    assert batch.candidates[0].verified is False
    assert batch.candidates[0].title == "Control Ultimate Edition"


def test_istore_refresh_sends_key_only_in_header_and_never_caches_it(tmp_path):
    key = "private-test-key"
    transport = FakeTransport(
        [
            response(
                {
                    "response": {
                        "apps": [
                            {"appid": 870780, "name": "Control Ultimate Edition"},
                            {"appid": 413150, "name": "Stardew Valley"},
                        ],
                        "have_more_results": False,
                        "last_appid": 870780,
                    }
                }
            )
        ]
    )
    target = tmp_path / "steam-index.json"

    summary = refresh_istore_index(transport, target, api_key=key)

    assert summary["appCount"] == 2
    assert transport.calls[0]["headers"] == {"x-webapi-key": key}
    assert key not in transport.calls[0]["url"]
    assert key not in json.dumps(transport.calls[0]["params"])
    assert key not in target.read_text(encoding="utf-8")
    cached = json.loads(target.read_text(encoding="utf-8"))
    assert [app["appid"] for app in cached["apps"]] == [413150, 870780]


def test_cached_index_retrieval_still_verifies_candidates_with_appdetails(tmp_path):
    index = tmp_path / "steam-index.json"
    index.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "apps": [
                    {"appid": 111, "name": "Control Soundtrack"},
                    {"appid": 870780, "name": "Control Ultimate Edition"},
                ],
            }
        ),
        encoding="utf-8",
    )
    transport = FakeTransport([response(appdetails())])

    batch = CachedIndexProvider(index, transport=transport).retrieve(("Control Ultimate Edition",))

    assert [item.steam_app_id for item in batch.candidates] == [870780]
    assert batch.candidates[0].verified is True
    assert len(transport.calls) == 1
    assert transport.calls[0]["url"].endswith("/appdetails")
