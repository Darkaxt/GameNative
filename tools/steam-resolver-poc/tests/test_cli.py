import io
import json
from pathlib import Path

from steam_resolver.cli import _json_text, main
from steam_resolver.http import RateLimitExhausted
from steam_resolver.models import ProviderBatch


FIXTURES = Path(__file__).parent / "fixtures"
CORPUS = Path(__file__).parent / "corpus" / "real-30.json"


def invoke(args, stdin_text=""):
    stdout = io.StringIO()
    stderr = io.StringIO()
    exit_code = main(args, stdin=io.StringIO(stdin_text), stdout=stdout, stderr=stderr)
    return exit_code, stdout.getvalue(), stderr.getvalue()


def test_resolve_cli_emits_gamenative_aligned_schema_and_deterministic_json():
    payload = {
        "source": "GOG",
        "stableSourceId": "2049187585",
        "displayName": "Control Ultimate Edition",
        "developer": "Remedy Entertainment",
        "releaseYear": 2020,
        "appType": "GAME",
    }
    args = [
        "resolve",
        "--candidate-provider",
        "fixture",
        "--fixture",
        str(FIXTURES / "store-control.json"),
    ]

    first = invoke(args, json.dumps(payload))
    second = invoke(args, json.dumps(payload))

    assert first == second
    assert first[0] == 0
    assert first[2] == ""
    result = json.loads(first[1])
    assert result["schemaVersion"] == 1
    assert result["resolverVersion"] == 1
    assert result["decision"] == "AUTO_ACCEPT"
    assert result["candidateSteamAppId"] == 870780
    assert result["matchMethod"] == "STEAM_CATALOG"
    assert result["confidence"] == "HIGH"
    assert result["decisionSource"] == "AUTOMATIC"


def test_validate_sources_cli_offline_reports_contract():
    exit_code, output, error = invoke(
        ["corpus", "validate-sources", "--file", str(CORPUS), "--offline"]
    )

    assert exit_code == 0
    assert error == ""
    summary = json.loads(output)
    assert summary["contract"]["valid"] is True
    assert summary["sourceValidation"]["validated"] == 30


def test_exhausted_rate_limit_writes_only_typed_error_and_no_match_json(monkeypatch):
    attempts = tuple(
        {"attempt": attempt, "status": 429, "delaySeconds": delay}
        for attempt, delay in ((1, 1.0), (2, 2.0), (3, 4.0), (4, 0.0))
    )

    class ExhaustedProvider:
        name = "rate-limited-provider"

        def retrieve(self, queries):
            raise RateLimitExhausted(
                "https://store.steampowered.com/api/appdetails?appids=870780",
                attempts,
            )

    monkeypatch.setattr(
        "steam_resolver.cli._provider_from_args", lambda args: ExhaustedProvider()
    )
    payload = {
        "source": "GOG",
        "stableSourceId": "2049187585",
        "displayName": "Control Ultimate Edition",
    }

    exit_code, output, error = invoke(["resolve"], json.dumps(payload))

    assert exit_code == 4
    assert output == ""
    parsed = json.loads(error)
    assert set(parsed) == {"error", "message", "endpoint", "attempts"}
    assert parsed["error"] == "RATE_LIMIT_EXHAUSTED"
    assert parsed["attempts"] == list(attempts)
    assert "decision" not in parsed
    assert "candidates" not in parsed


def test_epic_fallback_rate_limit_is_stderr_only_typed_failure(monkeypatch):
    attempts = tuple(
        {"attempt": attempt, "status": 429, "delaySeconds": delay}
        for attempt, delay in ((1, 1.0), (2, 2.0), (3, 4.0), (4, 0.0))
    )

    class EmptySteamProvider:
        name = "complete-empty-steam"

        def retrieve(self, queries):
            return ProviderBatch()

    class ExhaustedEpicProvider:
        name = "epic-cms"

        def retrieve(self, owned_copy):
            raise RateLimitExhausted(
                "https://store-content.ak.epicgames.com/api/en-US/content/products/alan-wake-2",
                attempts,
            )

    monkeypatch.setattr(
        "steam_resolver.cli._provider_from_args", lambda args: EmptySteamProvider()
    )
    monkeypatch.setattr(
        "steam_resolver.cli._epic_provider_from_args",
        lambda args: ExhaustedEpicProvider(),
    )
    payload = {
        "source": "EPIC",
        "stableSourceId": (
            "YzQ3NjNmMjM2ZDA4NDIzZWI0N2I0YzMwMDg3NzljODQ."
            "OTNmMmE4YzM1NDc4NDZlZGE5NjZjYjNjMTUyYTAyNmU"
        ),
        "displayName": "Alan Wake 2",
        "appType": "GAME",
        "epicProductSlug": "alan-wake-2",
    }

    exit_code, output, error = invoke(["resolve"], json.dumps(payload))

    assert exit_code == 4
    assert output == ""
    parsed = json.loads(error)
    assert parsed["error"] == "RATE_LIMIT_EXHAUSTED"
    assert parsed["attempts"] == list(attempts)
    assert "decision" not in parsed
    assert "sourcePresentation" not in parsed


def test_json_output_is_ascii_transport_safe_without_losing_unicode_data():
    text = _json_text({"title": "Fire " + chr(0x1F525)})

    text.encode("ascii")
    assert json.loads(text)["title"] == "Fire " + chr(0x1F525)


def test_invalid_resolve_input_is_json_error_with_nonzero_exit():
    exit_code, output, error = invoke(["resolve"], "{}")

    assert exit_code == 2
    assert output == ""
    parsed = json.loads(error)
    assert parsed["error"] == "INVALID_INPUT"
    assert parsed["message"]
