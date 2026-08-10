"""Summary-only live validation for the three required public Steam targets."""

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

_TARGETS = (
    ("DREDGE", 1562430),
    ("Dota 2", 570),
    ("Stardew Valley", 413150),
)


def _page_summary(section: dict[str, Any], item_key: str) -> dict[str, Any]:
    pagination = section["pagination"]
    return {
        "sectionState": section["sectionState"]["kind"],
        "requestedPages": pagination["requestedPages"],
        "fetchedPages": pagination["fetchedPages"],
        "itemCount": len(section[item_key]),
        "uniqueRequestCount": pagination["uniqueRequestCount"],
        "uniqueItemCount": pagination["uniqueItemCount"],
        "duplicateItemCount": pagination["duplicateItemCount"],
        "identityKinds": pagination["identityKinds"],
        "continuationAvailable": pagination["continuationAvailable"],
    }


def _proof_conditions(result: dict[str, Any], expected_app_id: int) -> list[str]:
    unmet: list[str] = []
    if result["target"]["appId"] != expected_app_id:
        unmet.append("identity_app_id_mismatch")

    def check_section(
        prefix: str,
        section: dict[str, Any],
        *,
        required_state: str,
        item_key: str,
        configured_pages: int,
    ) -> None:
        state = section["sectionState"]
        if state["kind"] != required_state:
            unmet.append(f"{prefix}_state_not_{required_state.casefold()}")
        elif state.get("refreshFailed", False):
            unmet.append(f"{prefix}_refresh_failed")
        pagination = section["pagination"]
        requested = pagination["requestedPages"]
        fetched = pagination["fetchedPages"]
        unique_requests = pagination["uniqueRequestCount"]
        if requested != configured_pages or fetched != requested:
            unmet.append(f"{prefix}_pages_not_exact")
        if unique_requests != fetched:
            unmet.append(f"{prefix}_pages_not_unique")
        item_count = len(section[item_key])
        if item_count == 0:
            unmet.append(f"{prefix}_empty")
        if pagination["uniqueItemCount"] != item_count:
            unmet.append(f"{prefix}_identity_count_mismatch")
        if pagination["duplicateItemCount"] != 0:
            unmet.append(f"{prefix}_duplicate_identities")

    check_section(
        "reviews",
        result["reviews"],
        required_state="Content",
        item_key="items",
        configured_pages=result["request"]["reviewPages"],
    )
    check_section(
        "discussion_listing",
        result["discussions"],
        required_state="Listing",
        item_key="items",
        configured_pages=result["request"]["discussionPages"],
    )
    threads = result["discussions"]["sampledThreads"]
    if not threads:
        unmet.append("sampled_thread_missing")
    if len(threads) != result["request"]["sampleThreads"]:
        unmet.append("sampled_thread_count_not_exact")
    for index, thread in enumerate(threads, start=1):
        check_section(
            f"sampled_thread_{index}",
            thread,
            required_state="Thread",
            item_key="posts",
            configured_pages=result["request"]["threadPages"],
        )
    if any(
        diagnostic["severity"] in {"warning", "error"}
        for diagnostic in result["diagnostics"]
    ):
        unmet.append("diagnostic_warning_or_error")
    return unmet


def summarize_success(
    result: dict[str, Any], *, expected_app_id: int, command: list[str]
) -> dict[str, Any]:
    validate_result(result)
    diagnostics = result["diagnostics"]
    counts = Counter(diagnostic["type"] for diagnostic in diagnostics)
    http_diagnostics = [item for item in diagnostics if item["type"] == "http"]
    parser_diagnostics = [item for item in diagnostics if item["type"] == "parser"]
    failures = [item for item in diagnostics if item["severity"] in {"warning", "error"}]
    identity_matches = result["target"]["appId"] == expected_app_id
    unmet_conditions = _proof_conditions(result, expected_app_id)
    return {
        "target": result["target"],
        "expectedAppId": expected_app_id,
        "command": command,
        "success": not unmet_conditions,
        "unmetConditions": unmet_conditions,
        "schemaValid": True,
        "identityMatchesExpectedAppId": identity_matches,
        "reviews": _page_summary(result["reviews"], "items"),
        "discussionListing": _page_summary(result["discussions"], "items"),
        "sampledThreads": [
            {
                "title": thread["title"],
                "route": thread["route"],
                **_page_summary(thread, "posts"),
            }
            for thread in result["discussions"]["sampledThreads"]
        ],
        "diagnostics": {
            "countsByType": dict(sorted(counts.items())),
            "http": http_diagnostics,
            "parser": parser_diagnostics,
            "warningsAndErrors": failures,
        },
    }


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


def _command(title: str) -> list[str]:
    return [
        "python",
        "-m",
        "steam_community_poc.cli",
        title,
        "--review-pages",
        "3",
        "--discussion-pages",
        "3",
        "--thread-pages",
        "2",
        "--sample-threads",
        "1",
        "--pretty",
    ]


def run_live_validation(
    output_path: Path,
    *,
    collector_factory: Callable[[], SteamCommunityCollector] | None = None,
) -> tuple[dict[str, Any], int]:
    factory = collector_factory or (lambda: SteamCommunityCollector(BoundedHttpClient()))
    config = CollectorConfig(
        review_pages=3,
        discussion_pages=3,
        thread_pages=2,
        sample_threads=1,
    )
    targets: list[dict[str, Any]] = []
    for title, expected_app_id in _TARGETS:
        command = _command(title)
        try:
            result = factory().collect(title, config)
            targets.append(
                summarize_success(result, expected_app_id=expected_app_id, command=command)
            )
        except PocError as error:
            targets.append(
                {
                    "target": {"input": title},
                    "expectedAppId": expected_app_id,
                    "command": command,
                    "success": False,
                    "unmetConditions": ["probe_failed_before_proof"],
                    "schemaValid": False,
                    "identityMatchesExpectedAppId": False,
                    "error": {
                        "type": _error_type(error),
                        "severity": "error",
                        "code": error.code,
                        "message": str(error),
                        "context": error.context,
                    },
                }
            )
        except Exception as error:  # Preserve a report even if a dependency fails unexpectedly.
            targets.append(
                {
                    "target": {"input": title},
                    "expectedAppId": expected_app_id,
                    "command": command,
                    "success": False,
                    "unmetConditions": ["probe_failed_before_proof"],
                    "schemaValid": False,
                    "identityMatchesExpectedAppId": False,
                    "error": {
                        "type": "validation",
                        "severity": "error",
                        "code": "unexpected_probe_failure",
                        "message": "Live probe failed unexpectedly",
                        "context": {"errorType": type(error).__name__},
                    },
                }
            )
    summary = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(UTC).isoformat(),
        "configuration": {
            "reviewPages": 3,
            "discussionPages": 3,
            "threadPages": 2,
            "sampleThreads": 1,
            "bodyBytesMax": 1024 * 1024,
            "rawBodiesPersisted": False,
        },
        "allSucceeded": all(target["success"] for target in targets),
        "targets": targets,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return summary, 0 if summary["allSucceeded"] else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="steam-community-poc-live",
        description="Run strict multi-page probes for the required public Steam targets.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("reports/live-validation-summary.json"),
        help="summary JSON path (raw bodies and complete items are never written)",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    _, status = run_live_validation(args.output)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
