import json

import pytest

from steam_resolver.epic import (
    EpicCatalogProvider,
    EpicCmsValidationError,
    SlugRequired,
)
from steam_resolver.http import HttpResponse, RateLimitExhausted
from steam_resolver.models import AppType, OwnedCopy, ProviderBatch, Source, SteamCandidate
from steam_resolver.resolver import SteamResolver


ALAN_WAKE_2_ID = (
    "YzQ3NjNmMjM2ZDA4NDIzZWI0N2I0YzMwMDg3NzljODQ."
    "OTNmMmE4YzM1NDc4NDZlZGE5NjZjYjNjMTUyYTAyNmU"
)
NAMESPACE = "c4763f236d08423eb47b4c3008779c84"
CATALOG_ID = "93f2a8c3547846eda966cb3c152a026e"
OFFER_ID = "a7364ebfa54147f1b90f78a81c8093f7"
HERO = "https://cdn2.unrealengine.com/alan-wake-2-hero.jpg"
SCREENSHOT = "https://cdn2.unrealengine.com/alan-wake-2-shot.jpg"
HLS = "https://media-cdn.epicgames.com/video/manifest.m3u8"
DASH = "https://media-cdn.epicgames.com/video/manifest.mpd"
POSTER = "https://cdn2.unrealengine.com/alan-wake-2-poster.jpg"


def epic_copy(**overrides):
    values = {
        "source": Source.EPIC,
        "stable_source_id": ALAN_WAKE_2_ID,
        "display_name": "Alan Wake 2",
        "developer": "Remedy Entertainment",
        "app_type": AppType.GAME,
        "epic_product_slug": "alan-wake-2",
    }
    values.update(overrides)
    return OwnedCopy(**values)


def cms_payload(**overrides):
    recipes = json.dumps(
        {
            "en-US": [
                {
                    "recipe": "video-hls",
                    "outputs": [
                        {
                            "contentType": "application/x-mpegURL",
                            "key": "manifest",
                            "url": HLS,
                        },
                        {
                            "contentType": "image/jpeg",
                            "key": "thumbnail",
                            "url": POSTER,
                        },
                    ],
                },
                {
                    "recipe": "video-fmp4",
                    "outputs": [
                        {
                            "contentType": "application/dash+xml",
                            "key": "manifest",
                            "url": DASH,
                        }
                    ],
                },
            ]
        }
    )
    page = {
        "type": "productHome",
        "namespace": NAMESPACE,
        "productName": "Alan Wake 2",
        "item": {"catalogId": ""},
        "offer": {"namespace": NAMESPACE, "id": OFFER_ID, "hasOffer": True},
        "data": {
            "about": {
                "shortDescription": "A supernatural survival-horror sequel.",
                "description": "Saga Anderson and Alan Wake confront a dark story.",
                "developerAttribution": "Remedy Entertainment",
                "publisherAttribution": "Epic Games Publishing",
            },
            "hero": {"backgroundImageUrl": HERO},
            "carousel": {
                "items": [
                    {"image": {}, "video": {"recipes": recipes}},
                    {"image": {"src": SCREENSHOT}, "video": {}},
                ]
            },
            "requirements": {
                "systems": [{"systemType": "Windows", "details": []}],
                "languages": [
                    "AUDIO: English, German | TEXT: English, French, German"
                ],
            },
            "meta": {"customReleaseDate": "Coming Soon"},
        },
    }
    payload = {
        "namespace": NAMESPACE,
        "productName": "Alan Wake 2",
        "_slug": "alan-wake-2",
        "pages": [page, {"type": "offer", "namespace": NAMESPACE}],
    }
    payload.update(overrides)
    return payload


class FakeTransport:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def get(self, url, *, params=None, headers=None, timeout=10.0):
        self.calls.append(
            {"url": url, "params": params, "headers": headers, "timeout": timeout}
        )
        response = self.responses.pop(0)
        if callable(response):
            return response(url)
        return response


def response(
    payload,
    *,
    status=200,
    content_type="application/json",
    endpoint=(
        "https://store-content.ak.epicgames.com/api/en-US/content/products/alan-wake-2"
    ),
):
    body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
    return HttpResponse(
        status=status,
        headers={"Content-Type": content_type},
        body=body,
        endpoint=endpoint,
    )


class SteamProvider:
    name = "fake-steam"

    def __init__(self, batch):
        self.batch = batch

    def retrieve(self, queries):
        return self.batch


class CountingEpicProvider:
    name = "counting-epic"

    def __init__(self, result=None):
        self.calls = 0
        self.result = result

    def retrieve(self, owned_copy):
        self.calls += 1
        return self.result


def test_valid_epic_cms_becomes_source_only_presentation_fallback():
    transport = FakeTransport([response(cms_payload())])
    epic = EpicCatalogProvider(
        transport=transport,
        sleeper=lambda _: None,
        clock=lambda: 1_700_000_000.0,
    )

    result = SteamResolver(
        SteamProvider(ProviderBatch()), source_catalog_provider=epic
    ).resolve(epic_copy())

    assert result["decision"] == "SOURCE_CATALOG_FALLBACK"
    assert result["matchMethod"] == "SOURCE_CATALOG"
    assert result["candidateSteamAppId"] is None
    assert result["confidence"] == "SOURCE_ONLY"
    assert result["candidates"] == []
    assert result["sourceCatalogProvider"] == "epic-cms"
    presentation = result["sourcePresentation"]
    assert presentation["stableSourceId"] == ALAN_WAKE_2_ID
    assert presentation["namespace"] == NAMESPACE
    assert presentation["catalogId"] == CATALOG_ID
    assert presentation["catalogIdCorroboratedByCms"] is False
    assert presentation["slug"] == "alan-wake-2"
    assert presentation["offerId"] == OFFER_ID
    assert presentation["title"] == "Alan Wake 2"
    assert presentation["shortDescription"].startswith("A supernatural")
    assert presentation["about"].startswith("Saga Anderson")
    assert presentation["headerImage"] == HERO
    assert presentation["screenshots"] == [SCREENSHOT]
    assert presentation["movies"] == [
        {"dash": DASH, "hls": HLS, "poster": POSTER}
    ]
    assert presentation["developer"] == "Remedy Entertainment"
    assert presentation["publisher"] == "Epic Games Publishing"
    assert presentation["releaseDate"] is None
    assert presentation["releaseYear"] is None
    assert presentation["platforms"] == {
        "windows": True,
        "mac": False,
        "linux": False,
    }
    assert presentation["rawLanguages"] == [
        "AUDIO: English, German | TEXT: English, French, German"
    ]
    assert presentation["languages"] == [
        {"name": "English", "audio": True, "text": True},
        {"name": "German", "audio": True, "text": True},
        {"name": "French", "audio": False, "text": True},
    ]
    assert presentation["genres"] == []
    assert presentation["tags"] == []
    assert presentation["features"] == []
    assert presentation["storeUrl"] == (
        "https://store.epicgames.com/en-US/p/alan-wake-2"
    )
    assert result["canonicalGameMetadata"] == {
        "title": "Alan Wake 2",
        "shortDescription": "A supernatural survival-horror sequel.",
        "about": "Saga Anderson and Alan Wake confront a dark story.",
        "headerImageUrl": HERO,
        "screenshots": [SCREENSHOT],
        "movies": [
            {
                "name": None,
                "previewImageUrl": POSTER,
                "streamUrl": HLS,
            }
        ],
        "developers": ["Remedy Entertainment"],
        "publishers": ["Epic Games Publishing"],
        "releaseDate": None,
        "platforms": ["WINDOWS"],
        "languages": ["English", "German", "French"],
        "requirements": None,
        "genres": [],
        "features": [],
        "achievementCount": None,
        "dlcCount": None,
        "fetchedAtEpochMs": 1_700_000_000_000,
    }
    assert any("Coming Soon" in warning for warning in result["warnings"])
    assert result["diagnostics"][-1]["parser"] == "EPIC_CMS_OK"
    assert transport.calls[0]["url"].endswith(
        "/api/en-US/content/products/alan-wake-2"
    )


def test_epic_requirements_map_to_game_native_minimum_recommended_shape():
    payload = cms_payload()
    payload["pages"][0]["data"]["requirements"]["systems"][0]["details"] = [
        {
            "title": "Windows OS",
            "minimum": "Windows 10 64-bit",
            "recommended": "Windows 11 64-bit",
        },
        {
            "title": "Windows Memory",
            "minimum": "16 GB",
            "recommended": "16 GB",
        },
    ]
    provider = EpicCatalogProvider(
        transport=FakeTransport([response(payload)]),
        sleeper=lambda _: None,
        clock=lambda: 1_700_000_000.0,
    )

    result = provider.retrieve(epic_copy())

    assert result.canonical_metadata["requirements"] == {
        "minimum": "Windows OS: Windows 10 64-bit\nWindows Memory: 16 GB",
        "recommended": "Windows OS: Windows 11 64-bit\nWindows Memory: 16 GB",
    }


def test_absent_explicit_slug_tries_one_derived_slug_and_404_is_slug_required():
    transport = FakeTransport([response({}, status=404)])
    epic = EpicCatalogProvider(transport=transport, sleeper=lambda _: None)

    with pytest.raises(SlugRequired) as raised:
        epic.retrieve(epic_copy(epic_product_slug=None))

    assert raised.value.to_dict()["error"] == "SLUG_REQUIRED"
    assert len(transport.calls) == 1
    assert transport.calls[0]["url"].endswith("/products/alan-wake-2")


def test_cms_namespace_mismatch_is_rejected():
    payload = cms_payload(namespace="wrong-namespace")
    epic = EpicCatalogProvider(
        transport=FakeTransport([response(payload)]), sleeper=lambda _: None
    )

    with pytest.raises(EpicCmsValidationError, match="namespace"):
        epic.retrieve(epic_copy())


def test_wrong_input_store_host_and_wrong_media_host_are_rejected():
    with pytest.raises(ValueError, match="store.epicgames.com"):
        OwnedCopy.from_dict(
            {
                "source": "EPIC",
                "stableSourceId": ALAN_WAKE_2_ID,
                "displayName": "Alan Wake 2",
                "appType": "GAME",
                "epicStoreUrl": "https://evil.example/en-US/p/alan-wake-2",
            }
        )

    payload = cms_payload()
    payload["pages"][0]["data"]["hero"]["backgroundImageUrl"] = (
        "https://evil.example/hero.jpg"
    )
    epic = EpicCatalogProvider(
        transport=FakeTransport([response(payload)]), sleeper=lambda _: None
    )
    with pytest.raises(EpicCmsValidationError, match="media"):
        epic.retrieve(epic_copy())

    redirected = EpicCatalogProvider(
        transport=FakeTransport(
            [response(cms_payload(), endpoint="https://evil.example/alan-wake-2")]
        ),
        sleeper=lambda _: None,
    )
    with pytest.raises(EpicCmsValidationError, match="endpoint"):
        redirected.retrieve(epic_copy())


def test_cms_rejects_title_or_catalog_identity_conflicts():
    wrong_root_title = cms_payload(productName="Different Game")
    wrong_page_title = cms_payload()
    wrong_page_title["pages"][0]["productName"] = "Different Game"
    wrong_catalog = cms_payload()
    wrong_catalog["pages"][0]["item"]["catalogId"] = "different-catalog"

    for payload, message in (
        (wrong_root_title, "title"),
        (wrong_page_title, "title"),
        (wrong_catalog, "catalogId"),
    ):
        epic = EpicCatalogProvider(
            transport=FakeTransport([response(payload)]), sleeper=lambda _: None
        )
        with pytest.raises(EpicCmsValidationError, match=message):
            epic.retrieve(epic_copy())


def test_cms_requires_bounded_json_body():
    cases = (
        (response(b"x" * 1_048_577), "1 MiB"),
        (response(b"{"), "malformed JSON"),
        (response(cms_payload(), content_type="text/html"), "not JSON"),
    )
    for cms_response, message in cases:
        epic = EpicCatalogProvider(
            transport=FakeTransport([cms_response]), sleeper=lambda _: None
        )
        with pytest.raises(EpicCmsValidationError, match=message):
            epic.retrieve(epic_copy())


def test_multiple_product_home_pages_are_rejected():
    payload = cms_payload()
    payload["pages"].append(dict(payload["pages"][0]))
    epic = EpicCatalogProvider(
        transport=FakeTransport([response(payload)]), sleeper=lambda _: None
    )

    with pytest.raises(EpicCmsValidationError, match="exactly one"):
        epic.retrieve(epic_copy())


@pytest.mark.parametrize(
    ("batch", "expected_decision"),
    [
        (ProviderBatch(partial=True), "PROVIDER_UNAVAILABLE"),
        (ProviderBatch(provider_unavailable=True), "PROVIDER_UNAVAILABLE"),
        (
            ProviderBatch(
                diagnostics=({"parser": "MALFORMED_JSON", "status": 200},)
            ),
            "UNMATCHED",
        ),
    ],
)
def test_incomplete_failed_or_malformed_steam_run_never_triggers_fallback(
    batch, expected_decision
):
    epic = CountingEpicProvider()

    result = SteamResolver(
        SteamProvider(batch), source_catalog_provider=epic
    ).resolve(epic_copy())

    assert result["decision"] == expected_decision
    assert epic.calls == 0


def test_review_required_steam_candidate_never_triggers_fallback():
    candidate = SteamCandidate(
        steam_app_id=123,
        title="Alan Wake 2",
        developer=None,
        publisher=None,
        release_year=None,
        app_type=AppType.GAME,
        verified=True,
    )
    epic = CountingEpicProvider()

    result = SteamResolver(
        SteamProvider(ProviderBatch(candidates=(candidate,))),
        source_catalog_provider=epic,
    ).resolve(epic_copy(developer=None))

    assert result["decision"] == "REVIEW_REQUIRED"
    assert epic.calls == 0


def test_persistent_epic_cms_429_aborts_with_typed_failure_only():
    attempts = [response({}, status=429) for _ in range(4)]
    transport = FakeTransport(attempts)
    epic = EpicCatalogProvider(transport=transport, sleeper=lambda _: None)

    with pytest.raises(RateLimitExhausted) as raised:
        SteamResolver(
            SteamProvider(ProviderBatch()), source_catalog_provider=epic
        ).resolve(epic_copy())

    assert len(transport.calls) == 4
    assert raised.value.to_dict()["error"] == "RATE_LIMIT_EXHAUSTED"
    assert raised.value.to_dict()["attempts"] == [
        {"attempt": 1, "status": 429, "delaySeconds": 1.0},
        {"attempt": 2, "status": 429, "delaySeconds": 2.0},
        {"attempt": 3, "status": 429, "delaySeconds": 4.0},
        {"attempt": 4, "status": 429, "delaySeconds": 0.0},
    ]


def test_epic_store_url_supplies_locale_and_slug_without_ambiguity():
    copy = OwnedCopy.from_dict(
        {
            "source": "EPIC",
            "stableSourceId": ALAN_WAKE_2_ID,
            "displayName": "Alan Wake 2",
            "appType": "GAME",
            "epicStoreUrl": "https://store.epicgames.com/de/p/alan-wake-2",
        }
    )
    transport = FakeTransport(
        [lambda url: response(cms_payload(), endpoint=url)]
    )

    result = EpicCatalogProvider(
        transport=transport, sleeper=lambda _: None
    ).retrieve(copy)

    assert result.presentation["slug"] == "alan-wake-2"
    assert result.presentation["storeUrl"] == (
        "https://store.epicgames.com/de/p/alan-wake-2"
    )
    assert "/api/de/content/products/alan-wake-2" in transport.calls[0]["url"]
