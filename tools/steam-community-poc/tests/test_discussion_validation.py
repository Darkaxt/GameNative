import copy
from pathlib import Path

import pytest

from steam_community_poc.discussion_validation import (
    GOG_DISCUSSION_TARGETS,
    run_discussion_validation,
    summarize_discussion_result,
)
from steam_community_poc.models import NetworkError, ParseError

EXPECTED_GOG_TARGETS = (
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


def pagination(requested: int, identity_kind: str) -> dict:
    return {
        "requestedPages": requested,
        "fetchedPages": requested,
        "requestedUrls": [f"https://steam.example/{identity_kind}/{page}" for page in range(1, requested + 1)],
        "uniqueRequestCount": requested,
        "uniqueItemCount": 1,
        "duplicateItemCount": 0,
        "identityKinds": [identity_kind],
        "continuationAvailable": True,
    }


def valid_result(title: str = EXPECTED_GOG_TARGETS[0][0], app_id: int = EXPECTED_GOG_TARGETS[0][1]) -> dict:
    diagnostics = [
        {
            "type": "resolution",
            "severity": "info",
            "code": "target_resolved",
            "message": "exact",
            "context": {"appId": app_id},
        },
        {
            "type": "validation",
            "severity": "info",
            "code": "schema_valid",
            "message": "valid",
            "context": {"schemaVersion": 1},
        },
    ]
    for purpose, pages in (
        ("store_search", 1),
        ("reviews", 1),
        ("discussion_listing", 4),
        ("discussion_thread", 3),
    ):
        for page in range(1, pages + 1):
            diagnostics.append(
                {
                    "type": "http",
                    "severity": "info",
                    "code": "http_response",
                    "message": "bounded",
                    "context": {
                        "purpose": purpose,
                        "page": page,
                        "statusCode": 200,
                        "bodyBytes": 100,
                        "contentType": "application/json" if purpose in {"store_search", "reviews"} else "text/html",
                    },
                }
            )
    diagnostics.append(
        {
            "type": "parser",
            "severity": "info",
            "code": "reviews_parsed",
            "message": "parsed",
            "context": {"page": 1, "itemCount": 1, "skippedItemCount": 0},
        }
    )
    for page in range(1, 5):
        diagnostics.append(
            {
                "type": "parser",
                "severity": "info",
                "code": "discussion_listing_parsed",
                "message": "parsed",
                "context": {
                    "page": page,
                    "itemCount": 15,
                    "skippedItemCount": 0,
                    "route": f"/app/{app_id}/discussions/?fp={page}",
                },
            }
        )
    for page in range(1, 4):
        diagnostics.append(
            {
                "type": "parser",
                "severity": "info",
                "code": "discussion_thread_parsed",
                "message": "parsed",
                "context": {
                    "page": page,
                    "itemCount": 15,
                    "skippedItemCount": 0,
                    "identityFallbackCount": 0,
                    "route": f"/app/{app_id}/discussions/0/100/?ctp={page}",
                },
            }
        )
    return {
        "schemaVersion": 1,
        "target": {"input": title, "appId": app_id, "title": title, "resolution": "exact_title"},
        "request": {"reviewPages": 1, "discussionPages": 4, "threadPages": 3, "sampleThreads": 1},
        "reviews": {
            "sectionState": {"kind": "Content", "canLoadMore": True, "loadingMore": False, "refreshFailed": False},
            "items": [
                {
                    "recommended": True,
                    "text": "Review scaffolding only",
                    "playtimeMinutes": 1,
                    "helpfulVotes": 0,
                    "funnyVotes": 0,
                    "commentCount": 0,
                    "postedAtEpochSeconds": 1,
                    "updatedAtEpochSeconds": 1,
                    "receivedForFree": False,
                    "earlyAccess": False,
                    "developerResponse": None,
                }
            ],
            "pagination": pagination(1, "recommendation_id"),
        },
        "discussions": {
            "sectionState": {"kind": "Listing", "canLoadMore": True, "loadingMore": False, "refreshFailed": False},
            "items": [
                {
                    "title": "Reply-rich public topic",
                    "replyCount": 100,
                    "activityLabel": "Now",
                    "route": f"/app/{app_id}/discussions/0/100/",
                    "viewCount": 1000,
                }
            ],
            "pagination": pagination(4, "discussion_route"),
            "sampledThreads": [
                {
                    "title": "Reply-rich public topic",
                    "route": f"/app/{app_id}/discussions/0/100/",
                    "sectionState": {"kind": "Thread", "canLoadMore": True, "loadingMore": False, "refreshFailed": False},
                    "posts": [{"text": "Public post"}],
                    "pagination": pagination(3, "steam_post_id"),
                }
            ],
        },
        "diagnostics": diagnostics,
    }


def summarize(result: dict, expected_app_id: int = EXPECTED_GOG_TARGETS[0][1]) -> dict:
    return summarize_discussion_result(
        result,
        expected_app_id=expected_app_id,
        command=["python", "-m", "steam_community_poc.cli", result["target"]["input"]],
    )


def test_discussion_corpus_is_resolver_first_exact_gog_group_of_ten() -> None:
    assert GOG_DISCUSSION_TARGETS == EXPECTED_GOG_TARGETS


def test_exact_title_proof_accepts_canonical_case_only_variation() -> None:
    result = valid_result("Control Ultimate Edition", 870780)
    result["target"]["title"] = "CONTROL Ultimate Edition"

    summary = summarize_discussion_result(
        result,
        expected_title="Control Ultimate Edition",
        expected_app_id=870780,
        command=["python", "-m", "steam_community_poc.cli", "Control Ultimate Edition"],
    )

    assert summary["success"] is True
    assert summary["unmetConditions"] == []


def test_discussion_summary_passes_only_complete_four_listing_three_thread_proof() -> None:
    summary = summarize(valid_result())

    assert summary["success"] is True
    assert summary["unmetConditions"] == []
    assert summary["schemaValid"] is True
    assert summary["identityMatchesExpectedAppId"] is True
    assert summary["discussionListing"]["fetchedPages"] == 4
    assert summary["discussionListing"]["perPageItemCounts"] == [15, 15, 15, 15]
    assert summary["sampledThread"]["fetchedPages"] == 3
    assert summary["sampledThread"]["perPageItemCounts"] == [15, 15, 15]
    assert summary["endpointCounts"] == {
        "discussion_listing": 4,
        "discussion_thread": 3,
        "reviews": 1,
        "store_search": 1,
    }


def _listing_too_shallow(result: dict) -> None:
    result["discussions"]["pagination"]["fetchedPages"] = 3
    result["discussions"]["pagination"]["uniqueRequestCount"] = 3


def _listing_page_empty(result: dict) -> None:
    listing_diagnostics = [d for d in result["diagnostics"] if d["code"] == "discussion_listing_parsed"]
    listing_diagnostics[1]["context"]["itemCount"] = 0


def _thread_too_shallow(result: dict) -> None:
    thread = result["discussions"]["sampledThreads"][0]
    thread["pagination"]["fetchedPages"] = 2
    thread["pagination"]["uniqueRequestCount"] = 2


def _thread_page_empty(result: dict) -> None:
    thread_diagnostics = [d for d in result["diagnostics"] if d["code"] == "discussion_thread_parsed"]
    thread_diagnostics[2]["context"]["itemCount"] = 0


def _duplicate_topic(result: dict) -> None:
    result["discussions"]["pagination"]["duplicateItemCount"] = 1


def _duplicate_post(result: dict) -> None:
    result["discussions"]["sampledThreads"][0]["pagination"]["duplicateItemCount"] = 1


def _parser_skip(result: dict) -> None:
    next(d for d in result["diagnostics"] if d["code"] == "discussion_listing_parsed")["context"]["skippedItemCount"] = 1


def _warning(result: dict) -> None:
    result["diagnostics"].append(
        {
            "type": "pagination",
            "severity": "warning",
            "code": "degraded",
            "message": "degraded",
            "context": {},
        }
    )


@pytest.mark.parametrize(
    ("mutate", "condition"),
    [
        (_listing_too_shallow, "discussion_listing_pages_below_four"),
        (_listing_page_empty, "discussion_listing_page_2_empty"),
        (_thread_too_shallow, "sampled_thread_pages_below_three"),
        (_thread_page_empty, "sampled_thread_page_3_empty"),
        (_duplicate_topic, "duplicate_topic_identities"),
        (_duplicate_post, "duplicate_post_identities"),
        (_parser_skip, "discussion_parser_skips"),
        (_warning, "diagnostic_warning_or_error"),
    ],
)
def test_discussion_proof_fails_closed_for_each_required_gate(mutate, condition: str) -> None:
    result = valid_result()
    mutate(result)

    summary = summarize(result)

    assert summary["success"] is False
    assert condition in summary["unmetConditions"]


def test_discussion_summary_allows_and_counts_blank_post_omissions() -> None:
    result = valid_result()
    diagnostic = next(
        item
        for item in result["diagnostics"]
        if item["code"] == "discussion_thread_parsed"
    )
    diagnostic["context"]["blankPostCount"] = 1

    summary = summarize(result)

    assert summary["success"] is True
    assert summary["discussionParserSkipCount"] == 0
    assert summary["discussionParserSkipReasons"] == {}
    assert summary["blankPostCount"] == 1


class CapturingFailCollector:
    def __init__(self) -> None:
        self.configs = []

    def collect(self, target: str, config: object) -> dict:
        self.configs.append((target, config))
        raise NetworkError("network_failure", "offline", context={"target": target})


def test_discussion_runner_uses_gog_group_and_one_ten_three_configuration(tmp_path: Path) -> None:
    collector = CapturingFailCollector()

    summary, status = run_discussion_validation(
        tmp_path / "discussions.json",
        collector_factory=lambda: collector,
    )

    assert status == 1
    assert summary["corpus"] == {
        "source": "resolver corpus",
        "group": "GOG",
        "groupPosition": 1,
        "groupSize": 10,
        "statement": "The GOG group is the resolver corpus's first exact group of 10.",
    }
    assert summary["configuration"] == {
        "reviewPages": 1,
        "discussionPages": 10,
        "threadPages": 3,
        "sampleThreads": 1,
        "rawBodiesPersisted": False,
    }
    assert [target for target, _ in collector.configs] == [title for title, _ in EXPECTED_GOG_TARGETS]
    assert all(
        config.review_pages == 1
        and config.discussion_pages == 10
        and config.thread_pages == 3
        and config.sample_threads == 1
        for _, config in collector.configs
    )
    assert all(target["unmetConditions"] == ["probe_failed_before_proof"] for target in summary["targets"])
    assert summary["completedEndpointCounts"] == {}
    assert b"\r\n" not in (tmp_path / "discussions.json").read_bytes()


class TitleFailThenAppIdSuccessCollector:
    def __init__(self) -> None:
        self.calls: list[str] = []

    def collect(self, target: str, config: object) -> dict:
        self.calls.append(target)
        if not target.isdecimal():
            raise ParseError(
                "exact_title_not_found",
                "no exact match",
                context={"title": target, "matchCount": 0},
            )
        app_id = int(target)
        title = next(title for title, expected in EXPECTED_GOG_TARGETS if expected == app_id)
        result = valid_result(title, app_id)
        result["target"] = {
            "input": target,
            "appId": app_id,
            "title": title,
            "resolution": "app_id",
        }
        for diagnostic in result["diagnostics"]:
            if (
                diagnostic["type"] == "http"
                and diagnostic["context"].get("purpose") == "store_search"
            ):
                diagnostic["context"]["purpose"] = "app_details"
        return result


def test_discussion_runner_falls_back_to_expected_app_id_after_title_failure(
    tmp_path: Path,
) -> None:
    collector = TitleFailThenAppIdSuccessCollector()
    summary, status = run_discussion_validation(
        tmp_path / "fallback-successes.json",
        collector_factory=lambda: collector,
    )

    assert status == 0
    assert summary["allSucceeded"] is True
    assert summary["completedEndpointCounts"] == {
        "app_details": 10,
        "discussion_listing": 40,
        "discussion_thread": 30,
        "reviews": 10,
        "store_search": 10,
    }
    assert collector.calls == [
        probe
        for title, app_id in EXPECTED_GOG_TARGETS
        for probe in (title, str(app_id))
    ]
    for target, (_, expected_app_id) in zip(summary["targets"], EXPECTED_GOG_TARGETS):
        assert target["success"] is True
        assert target["usedAppIdFallback"] is True
        assert target["target"]["appId"] == expected_app_id
        assert target["resolutionAttempts"][0]["error"]["code"] == "exact_title_not_found"
        assert target["resolutionAttempts"][1] == {
            "input": str(expected_app_id),
            "method": "app_id_fallback",
            "success": True,
            "appId": expected_app_id,
            "title": target["target"]["title"],
        }
        assert target["endpointCounts"]["store_search"] == 1
        assert target["endpointCounts"]["app_details"] == 1


class UnexpectedAppIdFallbackCollector:
    def collect(self, target: str, config: object) -> dict:
        if not target.isdecimal():
            raise ParseError(
                "exact_title_not_found",
                "no exact match",
                context={"title": target, "matchCount": 0},
            )
        raise RuntimeError("sensitive fallback failure text")


def test_discussion_runner_sanitizes_unexpected_app_id_fallback_failure(
    tmp_path: Path,
) -> None:
    summary, status = run_discussion_validation(
        tmp_path / "fallback-failures.json",
        collector_factory=UnexpectedAppIdFallbackCollector,
    )

    assert status == 1
    assert len(summary["targets"]) == 10
    assert all(
        target["error"] == {
            "type": "validation",
            "severity": "error",
            "code": "unexpected_probe_failure",
            "message": "Discussion AppID fallback probe failed unexpectedly",
            "context": {"errorType": "RuntimeError"},
        }
        for target in summary["targets"]
    )
    assert "sensitive fallback failure text" not in str(summary)


class SuccessfulCorpusCollector:
    def collect(self, target: str, config: object) -> dict:
        app_id = dict(EXPECTED_GOG_TARGETS)[target]
        return valid_result(target, app_id)


def test_discussion_runner_aggregates_completed_endpoint_counts(tmp_path: Path) -> None:
    summary, status = run_discussion_validation(
        tmp_path / "successes.json",
        collector_factory=SuccessfulCorpusCollector,
    )

    assert status == 0
    assert summary["allSucceeded"] is True
    assert summary["completedEndpointCounts"] == {
        "discussion_listing": 40,
        "discussion_thread": 30,
        "reviews": 10,
        "store_search": 10,
    }
