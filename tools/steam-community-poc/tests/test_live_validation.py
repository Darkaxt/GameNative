import copy
import json
from pathlib import Path

import pytest

from steam_community_poc.live_validation import run_live_validation, summarize_success
from steam_community_poc.models import NetworkError


def valid_live_result() -> dict:
    page_urls = [f"https://steam.example/page/{page}" for page in range(1, 4)]
    pagination = {
        "requestedPages": 3,
        "fetchedPages": 3,
        "requestedUrls": page_urls,
        "uniqueRequestCount": 3,
        "uniqueItemCount": 1,
        "duplicateItemCount": 0,
        "identityKinds": ["recommendation_id"],
        "continuationAvailable": True,
    }
    return {
        "schemaVersion": 1,
        "target": {
            "input": "DREDGE",
            "appId": 1562430,
            "title": "DREDGE",
            "resolution": "exact_title",
        },
        "request": {
            "reviewPages": 3,
            "discussionPages": 3,
            "threadPages": 3,
            "sampleThreads": 1,
        },
        "reviews": {
            "sectionState": {
                "kind": "Content",
                "canLoadMore": True,
                "loadingMore": False,
                "refreshFailed": False,
            },
            "items": [
                {
                    "recommended": True,
                    "text": "DO_NOT_PERSIST_FULL_BODY",
                    "playtimeMinutes": 2,
                    "helpfulVotes": 1,
                    "funnyVotes": 0,
                    "commentCount": 0,
                    "postedAtEpochSeconds": 1,
                    "updatedAtEpochSeconds": 1,
                    "receivedForFree": False,
                    "earlyAccess": False,
                    "developerResponse": None,
                }
            ],
            "pagination": copy.deepcopy(pagination),
        },
        "discussions": {
            "sectionState": {
                "kind": "Listing",
                "canLoadMore": True,
                "loadingMore": False,
                "refreshFailed": False,
            },
            "items": [
                {
                    "title": "Public topic",
                    "replyCount": 40,
                    "activityLabel": "Now",
                    "route": "/app/1562430/discussions/0/1/",
                    "viewCount": 2,
                }
            ],
            "pagination": copy.deepcopy(pagination),
            "sampledThreads": [
                {
                    "title": "Public topic",
                    "route": "/app/1562430/discussions/0/1/",
                    "sectionState": {
                        "kind": "Thread",
                        "canLoadMore": True,
                        "loadingMore": False,
                        "refreshFailed": False,
                    },
                    "posts": [{"text": "DO_NOT_PERSIST_FULL_BODY"}],
                    "pagination": copy.deepcopy(pagination),
                }
            ],
        },
        "diagnostics": [
            {
                "type": "http",
                "severity": "info",
                "code": "http_response",
                "message": "bounded",
                "context": {"statusCode": 200, "bodyBytes": 100},
            },
            {
                "type": "parser",
                "severity": "info",
                "code": "parsed",
                "message": "parsed one",
                "context": {"skippedItemCount": 0},
            },
        ],
    }


def summarize(result: dict) -> dict:
    return summarize_success(
        result,
        expected_app_id=1562430,
        command=["python", "-m", "steam_community_poc.cli", "DREDGE"],
    )


def test_live_summary_requires_and_records_complete_proof_gates_without_item_bodies() -> None:
    summary = summarize(valid_live_result())

    serialized = json.dumps(summary)
    assert summary["success"] is True
    assert summary["unmetConditions"] == []
    assert summary["schemaValid"] is True
    assert summary["identityMatchesExpectedAppId"] is True
    assert summary["reviews"]["itemCount"] == 1
    assert summary["discussionListing"]["fetchedPages"] == 3
    assert summary["sampledThreads"][0]["fetchedPages"] == 3
    assert summary["sampledThreads"][0]["identityKinds"] == ["recommendation_id"]
    assert summary["diagnostics"]["countsByType"] == {"http": 1, "parser": 1}
    assert summary["diagnostics"]["http"][0]["context"]["bodyBytes"] == 100
    assert "DO_NOT_PERSIST_FULL_BODY" not in serialized


def _reviews_not_content(result: dict) -> None:
    result["reviews"]["sectionState"] = {"kind": "Empty"}


def _listing_not_listing(result: dict) -> None:
    result["discussions"]["sectionState"] = {"kind": "Empty"}


def _thread_not_thread(result: dict) -> None:
    result["discussions"]["sampledThreads"][0]["sectionState"] = {"kind": "Empty"}


def _reviews_page_mismatch(result: dict) -> None:
    result["reviews"]["pagination"]["fetchedPages"] = 2


def _listing_request_not_unique(result: dict) -> None:
    result["discussions"]["pagination"]["uniqueRequestCount"] = 2


def _reviews_empty(result: dict) -> None:
    result["reviews"]["items"] = []
    result["reviews"]["pagination"]["uniqueItemCount"] = 0


def _listing_empty(result: dict) -> None:
    result["discussions"]["items"] = []
    result["discussions"]["pagination"]["uniqueItemCount"] = 0


def _sample_missing(result: dict) -> None:
    result["discussions"]["sampledThreads"] = []


def _thread_posts_empty(result: dict) -> None:
    result["discussions"]["sampledThreads"][0]["posts"] = []
    result["discussions"]["sampledThreads"][0]["pagination"]["uniqueItemCount"] = 0


def _duplicate_identity(result: dict) -> None:
    result["reviews"]["pagination"]["duplicateItemCount"] = 1


def _warning_diagnostic(result: dict) -> None:
    result["diagnostics"].append(
        {
            "type": "parser",
            "severity": "warning",
            "code": "selector_fallback",
            "message": "proof degraded",
            "context": {},
        }
    )


@pytest.mark.parametrize(
    ("mutate", "condition"),
    [
        (_reviews_not_content, "reviews_state_not_content"),
        (_listing_not_listing, "discussion_listing_state_not_listing"),
        (_thread_not_thread, "sampled_thread_1_state_not_thread"),
        (_reviews_page_mismatch, "reviews_pages_not_exact"),
        (_listing_request_not_unique, "discussion_listing_pages_not_unique"),
        (_reviews_empty, "reviews_empty"),
        (_listing_empty, "discussion_listing_empty"),
        (_sample_missing, "sampled_thread_missing"),
        (_thread_posts_empty, "sampled_thread_1_empty"),
        (_duplicate_identity, "reviews_duplicate_identities"),
        (_warning_diagnostic, "diagnostic_warning_or_error"),
    ],
)
def test_live_success_fails_closed_and_records_each_unmet_condition(
    mutate, condition: str
) -> None:
    result = valid_live_result()
    mutate(result)

    summary = summarize(result)

    assert summary["success"] is False
    assert condition in summary["unmetConditions"]


class ConfigCapturingFailCollector:
    def __init__(self) -> None:
        self.configs = []

    def collect(self, target: str, config: object) -> dict:
        self.configs.append(config)
        raise NetworkError("network_failure", "offline", context={"target": target})


def test_live_runner_configures_exact_three_three_two_page_proof(tmp_path: Path) -> None:
    collector = ConfigCapturingFailCollector()

    summary, status = run_live_validation(
        tmp_path / "summary.json",
        collector_factory=lambda: collector,
    )

    assert status == 1
    assert summary["configuration"]["reviewPages"] == 3
    assert summary["configuration"]["discussionPages"] == 3
    assert summary["configuration"]["threadPages"] == 2
    assert len(collector.configs) == 3
    assert all(config.review_pages == 3 for config in collector.configs)
    assert all(config.discussion_pages == 3 for config in collector.configs)
    assert all(config.thread_pages == 2 for config in collector.configs)
    assert b"\r\n" not in (tmp_path / "summary.json").read_bytes()
    for target in summary["targets"]:
        option_index = target["command"].index("--thread-pages")
        assert target["command"][option_index + 1] == "2"


class FailingCollector:
    def collect(self, target: str, config: object) -> dict:
        raise NetworkError("network_failure", "offline", context={"target": target})


def test_probe_failure_before_proof_records_an_unmet_condition(tmp_path: Path) -> None:
    summary, status = run_live_validation(
        tmp_path / "summary.json",
        collector_factory=FailingCollector,
    )

    assert status == 1
    assert summary["allSucceeded"] is False
    assert all(
        target["unmetConditions"] == ["probe_failed_before_proof"]
        for target in summary["targets"]
    )
