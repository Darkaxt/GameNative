"""Strict Discussions-only live validation for the resolver corpus's first GOG group."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Callable

from .collector import CollectorConfig, SteamCommunityCollector
from .http import BoundedHttpClient
from .models import NetworkError, ParseError, PocError, RateLimitError, ValidationError
from .schema import validate_result
from .routes import normalize_title

GOG_DISCUSSION_TARGETS = (
    ("Disco Elysium - The Final Cut", 632470),
    ("Cyberpunk 2077", 1091500),
    ("The Witcher 3: Wild Hunt", 292030),
    ("Baldur's Gate 3", 1086940),
    ("Stardew Valley", 413150),
    ("No Man's Sky", 275850),
    ("Control Ultimate Edition", 870780),
    ("Hollow Knight", 367520),
    ("Divinity: Original Sin 2 - Definitive Edition", 435150),
    ("Terraria", 105600),
)

_CORPUS = {
    "source": "resolver corpus",
    "group": "GOG",
    "groupPosition": 1,
    "groupSize": 10,
    "statement": "The GOG group is the resolver corpus's first exact group of 10.",
}


def _page_item_counts(diagnostics: list[dict[str, Any]], code: str) -> list[int]:
    pages: dict[int, int] = {}
    for diagnostic in diagnostics:
        if diagnostic["code"] != code:
            continue
        page = diagnostic["context"].get("page")
        item_count = diagnostic["context"].get("itemCount")
        if isinstance(page, int) and isinstance(item_count, int):
            pages[page] = item_count
    return [pages[page] for page in sorted(pages)]


def _discussion_conditions(
    result: dict[str, Any],
    expected_title: str,
    expected_app_id: int,
    *,
    app_id_fallback: bool = False,
) -> list[str]:
    unmet: list[str] = []
    target = result["target"]
    if app_id_fallback:
        if (
            target["input"] != str(expected_app_id)
            or target["resolution"] != "app_id"
        ):
            unmet.append("app_id_fallback_resolution_mismatch")
    elif (
        normalize_title(target["input"]) != normalize_title(expected_title)
        or normalize_title(target["title"]) != normalize_title(expected_title)
        or target["resolution"] != "exact_title"
    ):
        unmet.append("exact_title_resolution_mismatch")
    if target["appId"] != expected_app_id:
        unmet.append("expected_app_id_mismatch")

    discussions = result["discussions"]
    listing = discussions["pagination"]
    if discussions["sectionState"]["kind"] != "Listing":
        unmet.append("discussion_listing_state_not_listing")
    if listing["requestedPages"] < 4 or listing["fetchedPages"] < 4:
        unmet.append("discussion_listing_pages_below_four")
    if (
        listing["uniqueRequestCount"] < 4
        or listing["uniqueRequestCount"] != listing["fetchedPages"]
        or len(set(listing["requestedUrls"])) != listing["uniqueRequestCount"]
    ):
        unmet.append("discussion_listing_pages_not_unique")
    if not discussions["items"]:
        unmet.append("discussion_listing_empty")
    if listing["duplicateItemCount"] != 0:
        unmet.append("duplicate_topic_identities")
    if listing["uniqueItemCount"] != len(discussions["items"]):
        unmet.append("topic_identity_count_mismatch")

    listing_counts = _page_item_counts(
        result["diagnostics"], "discussion_listing_parsed"
    )
    for page in range(1, 5):
        if len(listing_counts) < page or listing_counts[page - 1] <= 0:
            unmet.append(f"discussion_listing_page_{page}_empty")

    threads = discussions["sampledThreads"]
    if not threads:
        unmet.append("sampled_thread_missing")
    else:
        thread = threads[0]
        thread_pagination = thread["pagination"]
        if thread["sectionState"]["kind"] != "Thread":
            unmet.append("sampled_thread_state_not_thread")
        if thread_pagination["requestedPages"] < 3 or thread_pagination["fetchedPages"] < 3:
            unmet.append("sampled_thread_pages_below_three")
        if (
            thread_pagination["uniqueRequestCount"] < 3
            or thread_pagination["uniqueRequestCount"] != thread_pagination["fetchedPages"]
            or len(set(thread_pagination["requestedUrls"]))
            != thread_pagination["uniqueRequestCount"]
        ):
            unmet.append("sampled_thread_pages_not_unique")
        if not thread["posts"]:
            unmet.append("sampled_thread_empty")
        if thread_pagination["duplicateItemCount"] != 0:
            unmet.append("duplicate_post_identities")
        if thread_pagination["uniqueItemCount"] != len(thread["posts"]):
            unmet.append("post_identity_count_mismatch")

        thread_counts = _page_item_counts(
            result["diagnostics"], "discussion_thread_parsed"
        )
        for page in range(1, 4):
            if len(thread_counts) < page or thread_counts[page - 1] <= 0:
                unmet.append(f"sampled_thread_page_{page}_empty")

    discussion_parser_diagnostics = [
        diagnostic
        for diagnostic in result["diagnostics"]
        if diagnostic["code"]
        in {"discussion_listing_parsed", "discussion_thread_parsed"}
    ]
    if any(
        diagnostic["context"].get("skippedItemCount", 0) != 0
        for diagnostic in discussion_parser_diagnostics
    ):
        unmet.append("discussion_parser_skips")
    if any(
        diagnostic["severity"] in {"warning", "error"}
        for diagnostic in result["diagnostics"]
    ):
        unmet.append("diagnostic_warning_or_error")
    return unmet


def summarize_discussion_result(
    result: dict[str, Any],
    *,
    expected_app_id: int,
    command: list[str],
    expected_title: str | None = None,
    app_id_fallback: bool = False,
) -> dict[str, Any]:
    validate_result(result)
    title = expected_title or result["target"]["input"]
    unmet = _discussion_conditions(
        result,
        title,
        expected_app_id,
        app_id_fallback=app_id_fallback,
    )
    diagnostics = result["diagnostics"]
    endpoint_counts = Counter(
        diagnostic["context"]["purpose"]
        for diagnostic in diagnostics
        if diagnostic["type"] == "http"
        and diagnostic["code"] == "http_response"
        and isinstance(diagnostic["context"].get("purpose"), str)
    )
    listing_counts = _page_item_counts(diagnostics, "discussion_listing_parsed")
    thread_counts = _page_item_counts(diagnostics, "discussion_thread_parsed")
    discussion_parser_skips = sum(
        diagnostic["context"].get("skippedItemCount", 0)
        for diagnostic in diagnostics
        if diagnostic["code"]
        in {"discussion_listing_parsed", "discussion_thread_parsed"}
    )
    blank_post_count = sum(
        diagnostic["context"].get("blankPostCount", 0)
        for diagnostic in diagnostics
        if diagnostic["code"] == "discussion_thread_parsed"
    )
    discussion_parser_skip_reasons: Counter[str] = Counter()
    for diagnostic in diagnostics:
        if diagnostic["code"] not in {
            "discussion_listing_parsed",
            "discussion_thread_parsed",
        }:
            continue
        reasons = diagnostic["context"].get("skippedItemReasons", {})
        if isinstance(reasons, dict):
            discussion_parser_skip_reasons.update(
                {
                    reason: count
                    for reason, count in reasons.items()
                    if isinstance(reason, str)
                    and isinstance(count, int)
                    and not isinstance(count, bool)
                    and count > 0
                }
            )
    listing = result["discussions"]
    listing_pagination = listing["pagination"]
    sampled = listing["sampledThreads"][0] if listing["sampledThreads"] else None
    sampled_summary = None
    if sampled is not None:
        thread_pagination = sampled["pagination"]
        sampled_summary = {
            "title": sampled["title"],
            "route": sampled["route"],
            "sectionState": sampled["sectionState"]["kind"],
            "requestedPages": thread_pagination["requestedPages"],
            "fetchedPages": thread_pagination["fetchedPages"],
            "uniqueRequestCount": thread_pagination["uniqueRequestCount"],
            "postCount": len(sampled["posts"]),
            "uniquePostIdentityCount": thread_pagination["uniqueItemCount"],
            "duplicatePostIdentityCount": thread_pagination["duplicateItemCount"],
            "identityKinds": thread_pagination["identityKinds"],
            "perPageItemCounts": thread_counts,
        }
    return {
        "target": result["target"],
        "expectedTitle": title,
        "expectedAppId": expected_app_id,
        "command": command,
        "success": not unmet,
        "unmetConditions": unmet,
        "schemaValid": True,
        "identityMatchesExpectedAppId": result["target"]["appId"] == expected_app_id,
        "discussionListing": {
            "sectionState": listing["sectionState"]["kind"],
            "requestedPages": listing_pagination["requestedPages"],
            "fetchedPages": listing_pagination["fetchedPages"],
            "uniqueRequestCount": listing_pagination["uniqueRequestCount"],
            "topicCount": len(listing["items"]),
            "uniqueTopicIdentityCount": listing_pagination["uniqueItemCount"],
            "duplicateTopicIdentityCount": listing_pagination["duplicateItemCount"],
            "identityKinds": listing_pagination["identityKinds"],
            "perPageItemCounts": listing_counts,
        },
        "sampledThread": sampled_summary,
        "discussionParserSkipCount": discussion_parser_skips,
        "discussionParserSkipReasons": dict(
            sorted(discussion_parser_skip_reasons.items())
        ),
        "blankPostCount": blank_post_count,
        "endpointCounts": dict(sorted(endpoint_counts.items())),
        "warningsAndErrors": [
            diagnostic
            for diagnostic in diagnostics
            if diagnostic["severity"] in {"warning", "error"}
        ],
    }


def _command(title: str) -> list[str]:
    return [
        "python",
        "-m",
        "steam_community_poc.cli",
        title,
        "--review-pages",
        "1",
        "--discussion-pages",
        "10",
        "--thread-pages",
        "3",
        "--sample-threads",
        "1",
        "--pretty",
    ]


def _error_type(error: PocError) -> str:
    if isinstance(error, ValidationError):
        return "validation"
    if isinstance(error, ParseError):
        return "parser"
    if isinstance(error, RateLimitError):
        return "rate_limit"
    if isinstance(error, NetworkError):
        return "http"
    return "validation"


def _failure_endpoint_counts(error: PocError) -> dict[str, int]:
    recorded = error.context.get("completedEndpointCounts")
    if isinstance(recorded, dict) and all(
        isinstance(purpose, str)
        and isinstance(count, int)
        and not isinstance(count, bool)
        and count >= 0
        for purpose, count in recorded.items()
    ):
        return dict(sorted(recorded.items()))
    if isinstance(error, ParseError) and error.code == "exact_title_not_found":
        return {"store_search": 1}
    return {}


def _error_record(error: PocError) -> dict[str, Any]:
    return {
        "type": _error_type(error),
        "severity": "error",
        "code": error.code,
        "message": str(error),
        "context": error.context,
    }


def run_discussion_validation(
    output_path: Path,
    *,
    collector_factory: Callable[[], SteamCommunityCollector] | None = None,
) -> tuple[dict[str, Any], int]:
    factory = collector_factory or (lambda: SteamCommunityCollector(BoundedHttpClient()))
    config = CollectorConfig(
        review_pages=1,
        discussion_pages=10,
        thread_pages=3,
        sample_threads=1,
    )
    targets: list[dict[str, Any]] = []
    for title, expected_app_id in GOG_DISCUSSION_TARGETS:
        command = _command(title)
        try:
            result = factory().collect(title, config)
            target_summary = summarize_discussion_result(
                result,
                expected_title=title,
                expected_app_id=expected_app_id,
                command=command,
            )
            target_summary["usedAppIdFallback"] = False
            target_summary["resolutionAttempts"] = [
                {
                    "input": title,
                    "method": "exact_title",
                    "success": True,
                    "appId": result["target"]["appId"],
                    "title": result["target"]["title"],
                }
            ]
            targets.append(target_summary)
        except PocError as error:
            if isinstance(error, ParseError) and error.code == "exact_title_not_found":
                fallback_input = str(expected_app_id)
                fallback_command = _command(fallback_input)
                try:
                    fallback_result = factory().collect(fallback_input, config)
                    target_summary = summarize_discussion_result(
                        fallback_result,
                        expected_title=title,
                        expected_app_id=expected_app_id,
                        command=command,
                        app_id_fallback=True,
                    )
                    combined_counts = Counter(target_summary["endpointCounts"])
                    combined_counts.update(_failure_endpoint_counts(error))
                    target_summary["endpointCounts"] = dict(
                        sorted(combined_counts.items())
                    )
                    target_summary["fallbackCommand"] = fallback_command
                    target_summary["usedAppIdFallback"] = True
                    target_summary["resolutionAttempts"] = [
                        {
                            "input": title,
                            "method": "exact_title",
                            "success": False,
                            "error": _error_record(error),
                        },
                        {
                            "input": fallback_input,
                            "method": "app_id_fallback",
                            "success": True,
                            "appId": fallback_result["target"]["appId"],
                            "title": fallback_result["target"]["title"],
                        },
                    ]
                    targets.append(target_summary)
                    continue
                except PocError as fallback_error:
                    combined_counts = Counter(_failure_endpoint_counts(error))
                    combined_counts.update(_failure_endpoint_counts(fallback_error))
                    targets.append(
                        {
                            "target": {"input": title},
                            "expectedTitle": title,
                            "expectedAppId": expected_app_id,
                            "command": command,
                            "fallbackCommand": fallback_command,
                            "usedAppIdFallback": True,
                            "resolutionAttempts": [
                                {
                                    "input": title,
                                    "method": "exact_title",
                                    "success": False,
                                    "error": _error_record(error),
                                },
                                {
                                    "input": fallback_input,
                                    "method": "app_id_fallback",
                                    "success": False,
                                    "error": _error_record(fallback_error),
                                },
                            ],
                            "success": False,
                            "unmetConditions": ["probe_failed_before_proof"],
                            "schemaValid": False,
                            "identityMatchesExpectedAppId": False,
                            "endpointCounts": dict(sorted(combined_counts.items())),
                            "error": _error_record(fallback_error),
                        }
                    )
                    continue
                except Exception as fallback_error:
                    unexpected_error = {
                        "type": "validation",
                        "severity": "error",
                        "code": "unexpected_probe_failure",
                        "message": "Discussion AppID fallback probe failed unexpectedly",
                        "context": {"errorType": type(fallback_error).__name__},
                    }
                    targets.append(
                        {
                            "target": {"input": title},
                            "expectedTitle": title,
                            "expectedAppId": expected_app_id,
                            "command": command,
                            "fallbackCommand": fallback_command,
                            "usedAppIdFallback": True,
                            "resolutionAttempts": [
                                {
                                    "input": title,
                                    "method": "exact_title",
                                    "success": False,
                                    "error": _error_record(error),
                                },
                                {
                                    "input": fallback_input,
                                    "method": "app_id_fallback",
                                    "success": False,
                                    "error": unexpected_error,
                                },
                            ],
                            "success": False,
                            "unmetConditions": ["probe_failed_before_proof"],
                            "schemaValid": False,
                            "identityMatchesExpectedAppId": False,
                            "endpointCounts": _failure_endpoint_counts(error),
                            "error": unexpected_error,
                        }
                    )
                    continue
            targets.append(
                {
                    "target": {"input": title},
                    "expectedTitle": title,
                    "expectedAppId": expected_app_id,
                    "command": command,
                    "usedAppIdFallback": False,
                    "resolutionAttempts": [
                        {
                            "input": title,
                            "method": "exact_title",
                            "success": False,
                            "error": _error_record(error),
                        }
                    ],
                    "success": False,
                    "unmetConditions": ["probe_failed_before_proof"],
                    "schemaValid": False,
                    "identityMatchesExpectedAppId": False,
                    "endpointCounts": _failure_endpoint_counts(error),
                    "error": _error_record(error),
                }
            )
        except Exception as error:
            targets.append(
                {
                    "target": {"input": title},
                    "expectedTitle": title,
                    "expectedAppId": expected_app_id,
                    "command": command,
                    "success": False,
                    "unmetConditions": ["probe_failed_before_proof"],
                    "schemaValid": False,
                    "identityMatchesExpectedAppId": False,
                    "endpointCounts": {},
                    "error": {
                        "type": "validation",
                        "severity": "error",
                        "code": "unexpected_probe_failure",
                        "message": "Discussion live probe failed unexpectedly",
                        "context": {"errorType": type(error).__name__},
                    },
                }
            )
    completed_endpoint_counts: Counter[str] = Counter()
    for target in targets:
        completed_endpoint_counts.update(target.get("endpointCounts", {}))
    summary = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(UTC).isoformat(),
        "corpus": _CORPUS,
        "configuration": {
            "reviewPages": 1,
            "discussionPages": 10,
            "threadPages": 3,
            "sampleThreads": 1,
            "rawBodiesPersisted": False,
        },
        "completedEndpointCounts": dict(sorted(completed_endpoint_counts.items())),
        "allSucceeded": all(target["success"] for target in targets),
        "targets": targets,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return summary, 0 if summary["allSucceeded"] else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="steam-community-poc-discussions-10",
        description="Validate Discussions for the resolver corpus's first exact GOG group of 10.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("reports/discussions-10-title-validation.json"),
        help="summary JSON path; complete live bodies and item text are never written",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    _, status = run_discussion_validation(args.output)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
