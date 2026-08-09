import io
import json
from pathlib import Path

from steam_resolver.cli import _json_text, main


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
