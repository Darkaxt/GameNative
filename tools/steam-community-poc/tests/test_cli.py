import copy
import json
from pathlib import Path

import pytest

from steam_community_poc.cli import build_parser, run
from steam_community_poc.models import ParseError


def valid_result() -> dict:
    pagination = {
        "requestedPages": 1,
        "fetchedPages": 0,
        "requestedUrls": [],
        "uniqueRequestCount": 0,
        "uniqueItemCount": 0,
        "duplicateItemCount": 0,
        "identityKinds": [],
        "continuationAvailable": False,
    }
    return {
        "schemaVersion": 1,
        "target": {"input": "42", "appId": 42, "title": "Game", "resolution": "app_id"},
        "request": {
            "reviewPages": 1,
            "discussionPages": 1,
            "threadPages": 1,
            "sampleThreads": 1,
        },
        "reviews": {
            "sectionState": {"kind": "Empty"},
            "items": [],
            "pagination": copy.deepcopy(pagination),
        },
        "discussions": {
            "sectionState": {"kind": "Empty"},
            "items": [],
            "pagination": copy.deepcopy(pagination),
            "sampledThreads": [],
        },
        "diagnostics": [],
    }


class CapturingCollector:
    def __init__(self, outcome: dict | Exception) -> None:
        self.outcome = outcome
        self.calls = []

    def collect(self, target: str, config: object) -> dict:
        self.calls.append((target, config))
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


@pytest.mark.parametrize(
    "arguments",
    [
        ["Game", "--review-pages", "0"],
        ["Game", "--review-pages", "11"],
        ["Game", "--discussion-pages", "-1"],
        ["Game", "--thread-pages", "999"],
        ["Game", "--sample-threads", "0"],
    ],
)
def test_parser_rejects_out_of_bound_page_and_sample_counts(arguments: list[str]) -> None:
    with pytest.raises(SystemExit) as caught:
        build_parser().parse_args(arguments)

    assert caught.value.code == 2


def test_cli_passes_config_and_writes_schema_valid_utf8_json(tmp_path: Path) -> None:
    output = tmp_path / "result.json"
    collector = CapturingCollector(valid_result())

    status = run(
        [
            "Dota 2",
            "--review-pages",
            "2",
            "--discussion-pages",
            "3",
            "--thread-pages",
            "2",
            "--sample-threads",
            "2",
            "--output",
            str(output),
        ],
        collector_factory=lambda: collector,
    )

    assert status == 0
    assert json.loads(output.read_text(encoding="utf-8"))["schemaVersion"] == 1
    target, config = collector.calls[0]
    assert target == "Dota 2"
    assert (config.review_pages, config.discussion_pages, config.thread_pages) == (2, 3, 2)
    assert config.sample_threads == 2


def test_cli_rejects_nonpositive_numeric_target_before_network(capsys: pytest.CaptureFixture[str]) -> None:
    collector = CapturingCollector(valid_result())

    status = run(["0"], collector_factory=lambda: collector)

    error = json.loads(capsys.readouterr().err)
    assert status == 2
    assert collector.calls == []
    assert error["error"]["type"] == "validation"
    assert error["error"]["code"] == "invalid_app_id"


def test_cli_emits_typed_unexpected_failure_without_sensitive_exception_text(
    capsys: pytest.CaptureFixture[str],
) -> None:
    collector = CapturingCollector(RuntimeError("cookie=secret-must-not-leak"))

    status = run(["Public title"], collector_factory=lambda: collector)

    error_text = capsys.readouterr().err
    error = json.loads(error_text)
    assert status == 1
    assert "secret-must-not-leak" not in error_text
    assert error["error"] == {
        "type": "validation",
        "severity": "error",
        "code": "unexpected_failure",
        "message": "Steam community POC failed unexpectedly",
        "context": {"errorType": "RuntimeError"},
    }


def test_cli_emits_typed_parser_failure_without_traceback(capsys: pytest.CaptureFixture[str]) -> None:
    collector = CapturingCollector(
        ParseError(
            "exact_title_not_found",
            "No exact title",
            context={"title": "Public title", "matchCount": 0},
        )
    )

    status = run(["Public title"], collector_factory=lambda: collector)

    error_text = capsys.readouterr().err
    error = json.loads(error_text)
    assert status == 1
    assert "Traceback" not in error_text
    assert error["error"] == {
        "type": "parser",
        "severity": "error",
        "code": "exact_title_not_found",
        "message": "No exact title",
        "context": {"title": "Public title", "matchCount": 0},
    }
