from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

from .http import HttpResponse, UrllibTransport, diagnostic
from .models import OwnedCopy, Source
from .normalization import normalize_title
from .source_ids import encode_epic_stable_id


SOURCES = ("AMAZON", "EPIC", "GOG")


def load_corpus(path: str | Path) -> list[dict[str, Any]]:
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or payload.get("schemaVersion") != 1:
        raise ValueError("corpus schemaVersion must be 1")
    cases = payload.get("cases")
    if not isinstance(cases, list):
        raise ValueError("corpus cases must be an array")
    return cases


def validate_corpus_contract(cases: list[dict[str, Any]]) -> dict[str, Any]:
    errors: list[str] = []
    counts = Counter()
    case_ids: set[str] = set()
    stable_ids: set[tuple[str, str]] = set()
    if len(cases) != 30:
        errors.append(f"expected exactly 30 cases, got {len(cases)}")
    for index, case in enumerate(cases):
        label = case.get("caseId", f"index-{index}") if isinstance(case, dict) else f"index-{index}"
        if not isinstance(case, dict):
            errors.append(f"{label}: case must be an object")
            continue
        if not isinstance(label, str) or not label:
            errors.append(f"index-{index}: caseId must be nonblank")
        elif label in case_ids:
            errors.append(f"{label}: duplicate caseId")
        else:
            case_ids.add(label)
        try:
            owned_copy = OwnedCopy.from_dict(case["input"])
        except (KeyError, TypeError, ValueError) as error:
            errors.append(f"{label}: invalid input: {error}")
            continue
        counts[owned_copy.source.value] += 1
        identity = (owned_copy.source.value, owned_copy.stable_source_id)
        if identity in stable_ids:
            errors.append(f"{label}: duplicate stable source identity")
        stable_ids.add(identity)
        expected = case.get("expectedSteamAppId")
        if isinstance(expected, bool) or not isinstance(expected, int) or expected <= 0:
            errors.append(f"{label}: expectedSteamAppId must be positive")
        urls = case.get("sourceEvidenceUrls")
        if not isinstance(urls, list) or not urls or not all(
            isinstance(url, str) and url.startswith("https://") for url in urls
        ):
            errors.append(f"{label}: sourceEvidenceUrls must contain HTTPS URLs")
        if owned_copy.source is Source.EPIC:
            raw = case.get("rawSourceIdentity")
            if not isinstance(raw, dict):
                errors.append(f"{label}: Epic rawSourceIdentity is required")
            else:
                try:
                    derived = encode_epic_stable_id(raw["namespace"], raw["catalogId"])
                except (KeyError, TypeError, ValueError) as error:
                    errors.append(f"{label}: invalid Epic raw identity: {error}")
                else:
                    if derived != owned_copy.stable_source_id:
                        errors.append(f"{label}: Epic stableSourceId does not match raw identity")
    for source in SOURCES:
        if counts[source] != 10:
            errors.append(f"{source}: expected 10 cases, got {counts[source]}")
    return {
        "valid": not errors,
        "caseCount": len(cases),
        "countsBySource": {source: counts[source] for source in SOURCES},
        "errors": errors,
    }


def validate_sources(
    cases: list[dict[str, Any]],
    *,
    live: bool,
    transport: Any | None = None,
    timeout: float = 15.0,
) -> dict[str, Any]:
    valid_cases: list[tuple[dict[str, Any], OwnedCopy]] = []
    results: list[dict[str, Any]] = []
    diagnostics: list[dict[str, Any]] = []
    for case in cases:
        label = case.get("caseId", "unknown")
        try:
            owned_copy = OwnedCopy.from_dict(case["input"])
            if owned_copy.source is Source.EPIC:
                raw = case.get("rawSourceIdentity")
                if not isinstance(raw, dict) or encode_epic_stable_id(
                    raw["namespace"], raw["catalogId"]
                ) != owned_copy.stable_source_id:
                    raise ValueError("Epic stableSourceId does not match raw identity")
        except (KeyError, TypeError, ValueError) as error:
            results.append(
                {"caseId": label, "source": case.get("input", {}).get("source"), "corroborated": False, "error": str(error)}
            )
            continue
        valid_cases.append((case, owned_copy))

    if not live:
        results.extend(
            {
                "caseId": case["caseId"],
                "source": owned_copy.source.value,
                "corroborated": True,
                "error": None,
            }
            for case, owned_copy in valid_cases
        )
        failed = sum(not item["corroborated"] for item in results)
        return {
            "mode": "offline",
            "valid": failed == 0,
            "validated": len(results) - failed,
            "failed": failed,
            "cases": results,
            "diagnostics": diagnostics,
        }

    active_transport = transport or UrllibTransport()
    response_cache: dict[str, HttpResponse] = {}
    for case, owned_copy in valid_cases:
        label = case["caseId"]
        case_responses: list[HttpResponse] = []
        for url in case.get("sourceEvidenceUrls", []):
            response = response_cache.get(url)
            if response is None:
                response = active_transport.get(url, timeout=timeout)
                response_cache[url] = response
            case_responses.append(response)
        combined = b"\n".join(
            response.body
            for response in case_responses
            if response.status is not None and 200 <= response.status < 300
        ).decode("utf-8", errors="replace")
        corroborated = _evidence_corroborates(case, owned_copy, combined)
        for response in case_responses:
            if response.status is None or not 200 <= response.status < 300:
                parser = "HTTP_ERROR"
            elif corroborated:
                parser = "SOURCE_EVIDENCE_CORROBORATED"
            else:
                parser = "SOURCE_EVIDENCE_NOT_CORROBORATED"
            diagnostics.append(
                {
                    "caseId": label,
                    "source": owned_copy.source.value,
                    **diagnostic(response, parser),
                }
            )
        results.append(
            {
                "caseId": label,
                "source": owned_copy.source.value,
                "corroborated": corroborated,
                "error": None if corroborated else "public evidence did not contain the required identity mapping",
            }
        )

    failed = sum(not item["corroborated"] for item in results)
    return {
        "mode": "live",
        "valid": failed == 0,
        "validated": len(results) - failed,
        "failed": failed,
        "cases": results,
        "diagnostics": diagnostics,
    }


def _evidence_corroborates(
    case: dict[str, Any], owned_copy: OwnedCopy, combined_body: str
) -> bool:
    body = combined_body.casefold()
    if owned_copy.source is Source.GOG:
        try:
            payload = json.loads(combined_body)
        except json.JSONDecodeError:
            payload = None
        if isinstance(payload, dict):
            return (
                str(payload.get("id")) == owned_copy.stable_source_id
                and normalize_title(payload.get("title"))
                == normalize_title(owned_copy.display_name)
            )
        return owned_copy.stable_source_id in body and normalize_title(
            owned_copy.display_name
        ) in normalize_title(combined_body)
    if owned_copy.source is Source.EPIC:
        raw = case["rawSourceIdentity"]
        return raw["namespace"].casefold() in body and raw["catalogId"].casefold() in body
    expected = case.get("expectedSteamAppId")
    return owned_copy.stable_source_id.casefold() in body and str(expected) in body


def evaluate_corpus(cases: list[dict[str, Any]], resolver: Any) -> dict[str, Any]:
    per_case: list[dict[str, Any]] = []
    flattened_diagnostics: list[dict[str, Any]] = []
    decision_counts: Counter[str] = Counter()
    for case in cases:
        owned_copy = OwnedCopy.from_dict(case["input"])
        resolution = resolver.resolve(owned_copy)
        expected = case["expectedSteamAppId"]
        candidate_ids = [
            candidate.get("steamAppId") for candidate in resolution.get("candidates", [])[:5]
        ]
        recall_at_5 = expected in candidate_ids
        top_1 = bool(candidate_ids) and candidate_ids[0] == expected
        auto_accepted = resolution.get("decision") == "AUTO_ACCEPT"
        automatic_correct = (
            auto_accepted and resolution.get("candidateSteamAppId") == expected
        )
        decision = str(resolution.get("decision", "UNKNOWN"))
        decision_counts[decision] += 1
        item = {
            "caseId": case["caseId"],
            "source": owned_copy.source.value,
            "stableSourceId": owned_copy.stable_source_id,
            "displayName": owned_copy.display_name,
            "expectedSteamAppId": expected,
            "recallAt5": recall_at_5,
            "top1": top_1,
            "autoAccepted": auto_accepted,
            "automaticCorrect": automatic_correct,
            "resolution": resolution,
        }
        per_case.append(item)
        for entry in resolution.get("diagnostics", []):
            flattened_diagnostics.append(
                {"caseId": case["caseId"], "source": owned_copy.source.value, **entry}
            )

    per_store = {
        source: _metrics([item for item in per_case if item["source"] == source])
        for source in SOURCES
    }
    failures = []
    for item in per_case:
        reasons: list[str] = []
        if not item["recallAt5"]:
            reasons.append("RECALL_AT_5_MISS")
        if not item["top1"]:
            reasons.append("TOP_1_MISS")
        if item["autoAccepted"] and not item["automaticCorrect"]:
            reasons.append("WRONG_AUTOMATIC_MATCH")
        elif not item["autoAccepted"]:
            reasons.append("NOT_AUTOMATIC")
        if reasons:
            candidates = item["resolution"].get("candidates", [])
            top_candidate_app_id = (
                candidates[0].get("steamAppId") if candidates else None
            )
            failures.append(
                {
                    "caseId": item["caseId"],
                    "source": item["source"],
                    "expectedSteamAppId": item["expectedSteamAppId"],
                    "reasons": reasons,
                    "decision": item["resolution"].get("decision"),
                    "topCandidateSteamAppId": top_candidate_app_id,
                }
            )
    return {
        "overall": _metrics(per_case),
        "perStore": per_store,
        "decisionCounts": dict(sorted(decision_counts.items())),
        "failures": failures,
        "cases": per_case,
        "diagnostics": flattened_diagnostics,
    }


def _metrics(items: Iterable[dict[str, Any]]) -> dict[str, Any]:
    values = list(items)
    count = len(values)
    recall = sum(bool(item["recallAt5"]) for item in values)
    top1 = sum(bool(item["top1"]) for item in values)
    auto_accepted = sum(bool(item["autoAccepted"]) for item in values)
    automatic_correct = sum(bool(item["automaticCorrect"]) for item in values)
    return {
        "caseCount": count,
        "recallAt5Count": recall,
        "recallAt5": round(recall / count, 4) if count else 0.0,
        "top1Count": top1,
        "top1": round(top1 / count, 4) if count else 0.0,
        "autoAcceptedCount": auto_accepted,
        "autoAcceptedRate": round(auto_accepted / count, 4) if count else 0.0,
        "automaticCorrectCount": automatic_correct,
        "automaticCorrectRate": round(automatic_correct / count, 4) if count else 0.0,
    }
