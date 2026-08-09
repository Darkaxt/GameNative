from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable

from .models import AppType, OwnedCopy, SteamCandidate
from .normalization import (
    edition_base_title,
    edition_tokens,
    normalize_developer,
    normalize_title,
    normalized_title_keys,
)


@dataclass(frozen=True)
class ScoredCandidate:
    candidate: SteamCandidate
    score: float
    evidence: dict[str, Any]
    strong_title: bool
    corroborated: bool
    edition_conflict: bool
    edition_base_match: bool


def score_candidate(source: OwnedCopy, candidate: SteamCandidate) -> ScoredCandidate:
    candidate_key = normalize_title(candidate.title)
    title_kind = "NONE"
    title_weight = 0.0
    source_key = normalize_title(source.display_name)
    for kind, key in normalized_title_keys(source.display_name):
        if key and key == candidate_key:
            title_kind = kind
            title_weight = 0.56 if kind == "EXACT" else 0.53
            break

    source_developer = normalize_developer(source.developer)
    candidate_developers = {
        key
        for key in (
            normalize_developer(candidate.developer),
            normalize_developer(candidate.publisher),
        )
        if key
    }
    if source_developer and source_developer in candidate_developers:
        developer_kind = "EXACT"
        developer_weight = 0.20
    elif source_developer and candidate_developers:
        developer_kind = "CONFLICT"
        developer_weight = 0.0
    else:
        developer_kind = "UNKNOWN"
        developer_weight = 0.0

    year_delta: int | None = None
    if source.release_year is not None and candidate.release_year is not None:
        year_delta = abs(source.release_year - candidate.release_year)
        if year_delta == 0:
            year_kind = "EXACT"
            year_weight = 0.14
        elif year_delta == 1:
            year_kind = "NEAR"
            year_weight = 0.10
        else:
            year_kind = "CONFLICT"
            year_weight = -0.10
    else:
        year_kind = "UNKNOWN"
        year_weight = 0.0

    type_compatible = source.app_type is AppType.GAME and candidate.app_type is AppType.GAME
    type_weight = 0.10 if type_compatible else 0.0
    source_editions = edition_tokens(source.display_name)
    candidate_editions = edition_tokens(candidate.title)
    has_edition_conflict = source_editions != candidate_editions and bool(
        source_editions or candidate_editions
    )
    source_edition_base = edition_base_title(source.display_name)
    candidate_edition_base = edition_base_title(candidate.title)
    edition_base_match = bool(source_edition_base) and source_edition_base == candidate_edition_base

    score = round(
        max(0.0, min(1.0, title_weight + developer_weight + year_weight + type_weight)),
        4,
    )
    corroborated = developer_weight > 0 or year_weight > 0
    evidence = {
        "title": {
            "sourceKey": source_key,
            "candidateKey": candidate_key,
            "kind": title_kind,
            "weight": title_weight,
        },
        "developer": {
            "sourceKey": source_developer or None,
            "candidateKeys": sorted(candidate_developers),
            "kind": developer_kind,
            "weight": developer_weight,
        },
        "releaseYear": {
            "source": source.release_year,
            "candidate": candidate.release_year,
            "delta": year_delta,
            "kind": year_kind,
            "weight": year_weight,
        },
        "appType": {
            "source": source.app_type.value,
            "candidate": candidate.app_type.value,
            "compatible": type_compatible,
            "weight": type_weight,
        },
        "edition": {
            "sourceTokens": sorted(source_editions),
            "candidateTokens": sorted(candidate_editions),
            "sourceBaseKey": source_edition_base,
            "candidateBaseKey": candidate_edition_base,
            "baseTitleMatch": edition_base_match,
            "conflict": has_edition_conflict,
        },
    }
    return ScoredCandidate(
        candidate=candidate,
        score=score,
        evidence=evidence,
        strong_title=title_kind in {"EXACT", "SAFE_ALIAS_EXACT"},
        corroborated=corroborated,
        edition_conflict=has_edition_conflict,
        edition_base_match=edition_base_match,
    )


def rank_candidates(
    candidates: Iterable[ScoredCandidate], limit: int | None = None
) -> list[ScoredCandidate]:
    best_by_id: dict[int, ScoredCandidate] = {}
    for scored in candidates:
        existing = best_by_id.get(scored.candidate.steam_app_id)
        if existing is None or scored.score > existing.score:
            best_by_id[scored.candidate.steam_app_id] = scored
    ranked = sorted(
        best_by_id.values(),
        key=lambda item: (-item.score, item.candidate.steam_app_id),
    )
    return ranked if limit is None else ranked[:limit]


def select_decision(
    ranked: list[ScoredCandidate],
    *,
    provider_unavailable: bool = False,
    provider_partial: bool = False,
) -> tuple[str, str]:
    if provider_unavailable:
        return "PROVIDER_UNAVAILABLE", "PROVIDER_UNAVAILABLE"
    if provider_partial:
        return (
            ("REVIEW_REQUIRED", "REVIEW_REQUIRED")
            if ranked
            else ("PROVIDER_UNAVAILABLE", "PROVIDER_UNAVAILABLE")
        )
    if not ranked:
        return "UNMATCHED", "UNMATCHED"

    top = ranked[0]
    margin = 1.0 if len(ranked) == 1 else round(top.score - ranked[1].score, 4)
    can_accept = (
        top.candidate.verified
        and top.evidence["appType"]["compatible"]
        and top.score >= 0.80
        and top.strong_title
        and top.corroborated
        and margin >= 0.08
        and not top.edition_conflict
    )
    if can_accept:
        return "AUTO_ACCEPT", "HIGH"
    if (
        top.score >= 0.62
        or (top.candidate.verified and top.strong_title)
        or (top.edition_conflict and top.edition_base_match)
    ):
        return "REVIEW_REQUIRED", "REVIEW_REQUIRED"
    return "UNMATCHED", "UNMATCHED"
