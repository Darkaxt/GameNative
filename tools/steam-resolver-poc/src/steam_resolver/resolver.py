from __future__ import annotations

from typing import Any

from .http import RateLimitExhausted
from .models import AppType, OwnedCopy, ProviderBatch, Source
from .normalization import title_queries
from .scoring import (
    rank_candidates,
    resolve_prior_year_ambiguity,
    score_candidate,
    select_decision,
)


SCHEMA_VERSION = 1
RESOLVER_VERSION = 1


class SteamResolver:
    def __init__(
        self,
        provider: Any,
        *,
        max_candidates: int = 5,
        source_catalog_provider: Any | None = None,
    ) -> None:
        if not 1 <= max_candidates <= 20:
            raise ValueError("max_candidates must be between 1 and 20")
        self.provider = provider
        self.max_candidates = max_candidates
        self.source_catalog_provider = source_catalog_provider

    def resolve(self, owned_copy: OwnedCopy) -> dict[str, Any]:
        queries = title_queries(owned_copy.display_name)
        try:
            batch = self.provider.retrieve(queries)
        except RateLimitExhausted:
            raise
        except Exception as error:
            batch = ProviderBatch(
                provider_unavailable=True,
                diagnostics=(
                    {
                        "endpoint": getattr(self.provider, "name", "unknown"),
                        "status": None,
                        "contentType": None,
                        "bodyBytes": 0,
                        "parser": "PROVIDER_EXCEPTION",
                        "error": f"{type(error).__name__}: {error}",
                    },
                ),
                warnings=("Candidate provider raised an exception.",),
            )

        ranked = rank_candidates(
            score_candidate(owned_copy, candidate) for candidate in batch.candidates
        )
        ambiguity = None
        if not batch.provider_unavailable and not batch.partial:
            ambiguity = resolve_prior_year_ambiguity(owned_copy, ranked)
            ranked = list(ambiguity.ranked)

        if ambiguity is not None and ambiguity.force_review:
            decision, confidence = "REVIEW_REQUIRED", "REVIEW_REQUIRED"
        elif ambiguity is not None and ambiguity.resolved:
            decision, confidence = select_decision(ranked[:1])
        else:
            decision, confidence = select_decision(
                ranked,
                provider_unavailable=batch.provider_unavailable,
                provider_partial=batch.partial,
            )
        visible = ranked[: self.max_candidates]
        margin = (
            None
            if not ranked
            else 1.0
            if len(ranked) == 1 or (ambiguity is not None and ambiguity.resolved)
            else round(ranked[0].score - ranked[1].score, 4)
        )
        top = ranked[0] if ranked else None
        candidate_app_id = (
            top.candidate.steam_app_id
            if top is not None and decision in {"AUTO_ACCEPT", "REVIEW_REQUIRED"}
            else None
        )
        result = {
            "schemaVersion": SCHEMA_VERSION,
            "resolverVersion": RESOLVER_VERSION,
            "input": owned_copy.to_dict(),
            "decision": decision,
            "candidateSteamAppId": candidate_app_id,
            "matchMethod": "STEAM_CATALOG",
            "confidence": confidence,
            "decisionSource": "AUTOMATIC",
            "candidateProvider": getattr(self.provider, "name", type(self.provider).__name__),
            "score": top.score if top else None,
            "margin": margin,
            "evidence": top.evidence if top else None,
            "candidates": [self._candidate_dict(item) for item in visible],
            "warnings": list(batch.warnings),
            "diagnostics": list(batch.diagnostics),
        }
        if self._should_use_epic_fallback(
            owned_copy,
            batch=batch,
            ranked=ranked,
            steam_decision=decision,
        ):
            source_result = self.source_catalog_provider.retrieve(owned_copy)
            result.update(
                {
                    "decision": "SOURCE_CATALOG_FALLBACK",
                    "candidateSteamAppId": None,
                    "matchMethod": "SOURCE_CATALOG",
                    "confidence": "SOURCE_ONLY",
                    "sourceCatalogProvider": getattr(
                        self.source_catalog_provider,
                        "name",
                        type(self.source_catalog_provider).__name__,
                    ),
                    "score": None,
                    "margin": None,
                    "evidence": {
                        **source_result.evidence,
                        "steamDecision": "UNMATCHED",
                    },
                    "candidates": [],
                    "sourcePresentation": source_result.presentation,
                    "canonicalGameMetadata": source_result.canonical_metadata,
                    "warnings": [*result["warnings"], *source_result.warnings],
                    "diagnostics": [
                        *result["diagnostics"],
                        *source_result.diagnostics,
                    ],
                }
            )
        return result

    def _should_use_epic_fallback(
        self,
        owned_copy: OwnedCopy,
        *,
        batch: ProviderBatch,
        ranked: list[Any],
        steam_decision: str,
    ) -> bool:
        if (
            self.source_catalog_provider is None
            or owned_copy.source is not Source.EPIC
            or owned_copy.app_type is not AppType.GAME
            or steam_decision != "UNMATCHED"
            or batch.provider_unavailable
            or batch.partial
            or self._steam_diagnostics_failed(batch.diagnostics)
        ):
            return False
        return not any(
            item.strong_title or (item.edition_conflict and item.edition_base_match)
            for item in ranked
        )

    @staticmethod
    def _steam_diagnostics_failed(diagnostics: tuple[dict[str, Any], ...]) -> bool:
        failure_markers = (
            "ERROR",
            "MALFORMED",
            "MISMATCH",
            "EXCEPTION",
            "TIMEOUT",
        )
        for item in diagnostics:
            parser = str(item.get("parser", "")).upper()
            status = item.get("status")
            if any(marker in parser for marker in failure_markers):
                return True
            if status is None and item.get("error"):
                return True
            if isinstance(status, int) and not 200 <= status < 300:
                return True
        return False

    @staticmethod
    def _candidate_dict(scored: Any) -> dict[str, Any]:
        candidate = scored.candidate
        return {
            "steamAppId": candidate.steam_app_id,
            "title": candidate.title,
            "developer": candidate.developer,
            "publisher": candidate.publisher,
            "releaseYear": candidate.release_year,
            "appType": candidate.app_type.value,
            "verified": candidate.verified,
            "score": scored.score,
            "evidence": scored.evidence,
        }
