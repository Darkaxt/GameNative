from __future__ import annotations

from typing import Any

from .models import OwnedCopy, ProviderBatch
from .normalization import title_queries
from .scoring import rank_candidates, score_candidate, select_decision


SCHEMA_VERSION = 1
RESOLVER_VERSION = 1


class SteamResolver:
    def __init__(self, provider: Any, *, max_candidates: int = 5) -> None:
        if not 1 <= max_candidates <= 20:
            raise ValueError("max_candidates must be between 1 and 20")
        self.provider = provider
        self.max_candidates = max_candidates

    def resolve(self, owned_copy: OwnedCopy) -> dict[str, Any]:
        queries = title_queries(owned_copy.display_name)
        try:
            batch = self.provider.retrieve(queries)
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
            if len(ranked) == 1
            else round(ranked[0].score - ranked[1].score, 4)
        )
        top = ranked[0] if ranked else None
        candidate_app_id = (
            top.candidate.steam_app_id
            if top is not None and decision in {"AUTO_ACCEPT", "REVIEW_REQUIRED"}
            else None
        )
        return {
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
