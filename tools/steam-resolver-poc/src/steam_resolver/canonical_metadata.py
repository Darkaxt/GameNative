from __future__ import annotations

from typing import Any


CANONICAL_GAME_METADATA_FIELDS = frozenset(
    {
        "title",
        "shortDescription",
        "about",
        "headerImageUrl",
        "screenshots",
        "movies",
        "developers",
        "publishers",
        "releaseDate",
        "platforms",
        "languages",
        "requirements",
        "genres",
        "features",
        "achievementCount",
        "dlcCount",
        "fetchedAtEpochMs",
    }
)


def epic_presentation_to_canonical_metadata(
    presentation: dict[str, Any],
    *,
    fetched_at_epoch_ms: int,
) -> dict[str, Any]:
    if fetched_at_epoch_ms < 0:
        raise ValueError("fetched_at_epoch_ms must be nonnegative")

    platform_names = (
        ("windows", "WINDOWS"),
        ("mac", "MACOS"),
        ("linux", "LINUX"),
    )
    platforms = presentation["platforms"]
    movies = [
        {
            "name": None,
            "previewImageUrl": movie.get("poster"),
            "streamUrl": movie.get("hls") or movie["dash"],
        }
        for movie in presentation["movies"]
    ]
    metadata = {
        "title": presentation["title"],
        "shortDescription": presentation["shortDescription"],
        "about": presentation["about"],
        "headerImageUrl": presentation["headerImage"],
        "screenshots": list(presentation["screenshots"]),
        "movies": movies,
        "developers": _optional_singleton(presentation["developer"]),
        "publishers": _optional_singleton(presentation["publisher"]),
        "releaseDate": presentation["releaseDate"],
        "platforms": [
            canonical
            for source, canonical in platform_names
            if platforms[source]
        ],
        "languages": [language["name"] for language in presentation["languages"]],
        "requirements": presentation["requirements"],
        "genres": list(presentation["genres"]),
        "features": list(presentation["features"]),
        "achievementCount": None,
        "dlcCount": None,
        "fetchedAtEpochMs": fetched_at_epoch_ms,
    }
    if frozenset(metadata) != CANONICAL_GAME_METADATA_FIELDS:
        raise AssertionError("CanonicalGameMetadata field contract drifted")
    return metadata


def _optional_singleton(value: str | None) -> list[str]:
    return [] if value is None else [value]
