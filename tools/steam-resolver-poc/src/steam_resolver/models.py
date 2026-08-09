from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class Source(str, Enum):
    GOG = "GOG"
    EPIC = "EPIC"
    AMAZON = "AMAZON"


class AppType(str, Enum):
    GAME = "GAME"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class OwnedCopy:
    source: Source
    stable_source_id: str
    display_name: str
    developer: str | None = None
    release_year: int | None = None
    app_type: AppType = AppType.UNKNOWN

    def __post_init__(self) -> None:
        from .source_ids import validate_source_id

        validate_source_id(self.source, self.stable_source_id)
        if not isinstance(self.display_name, str) or not self.display_name.strip():
            raise ValueError("displayName must be a nonblank string")
        if self.developer is not None and (
            not isinstance(self.developer, str) or not self.developer.strip()
        ):
            raise ValueError("developer must be a nonblank string when present")
        if self.release_year is not None:
            if isinstance(self.release_year, bool) or not isinstance(self.release_year, int):
                raise TypeError("releaseYear must be an integer")
            if not 1900 <= self.release_year <= 2100:
                raise ValueError("releaseYear must be between 1900 and 2100")

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> OwnedCopy:
        if not isinstance(payload, dict):
            raise TypeError("resolver input must be a JSON object")
        allowed = {
            "source",
            "stableSourceId",
            "displayName",
            "developer",
            "releaseYear",
            "appType",
        }
        unexpected = sorted(set(payload) - allowed)
        if unexpected:
            raise ValueError(f"unexpected input fields: {', '.join(unexpected)}")
        source = Source(payload["source"])
        app_type_value = payload.get("appType") or AppType.UNKNOWN.value
        return cls(
            source=source,
            stable_source_id=payload["stableSourceId"],
            display_name=payload["displayName"],
            developer=payload.get("developer"),
            release_year=payload.get("releaseYear"),
            app_type=AppType(app_type_value),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "source": self.source.value,
            "stableSourceId": self.stable_source_id,
            "displayName": self.display_name,
            "developer": self.developer,
            "releaseYear": self.release_year,
            "appType": self.app_type.value,
        }


@dataclass(frozen=True)
class SteamCandidate:
    steam_app_id: int
    title: str
    developer: str | None
    publisher: str | None
    release_year: int | None
    app_type: AppType
    verified: bool = True

    def __post_init__(self) -> None:
        if isinstance(self.steam_app_id, bool) or self.steam_app_id <= 0:
            raise ValueError("Steam AppID must be positive")
        if not self.title.strip():
            raise ValueError("Steam title must be nonblank")


@dataclass(frozen=True)
class ProviderBatch:
    candidates: tuple[SteamCandidate, ...] = ()
    diagnostics: tuple[dict[str, Any], ...] = ()
    provider_unavailable: bool = False
    partial: bool = False
    warnings: tuple[str, ...] = ()
