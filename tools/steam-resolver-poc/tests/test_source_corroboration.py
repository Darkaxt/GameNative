import json

from steam_resolver.corpus import validate_sources
from steam_resolver.http import HttpResponse
from steam_resolver.source_ids import encode_epic_stable_id


class UrlTransport:
    def __init__(self, bodies):
        self.bodies = bodies

    def get(self, url, *, params=None, headers=None, timeout=10.0):
        status, content_type, body = self.bodies[url]
        return HttpResponse(
            status=status,
            headers={"content-type": content_type},
            body=body.encode("utf-8"),
            endpoint=url,
        )


def test_live_source_corroboration_parses_gog_epic_and_amazon_evidence():
    epic_id = encode_epic_stable_id("jaguar", "3257e06c28764231acd93049f3774ed6")
    cases = [
        {
            "caseId": "gog",
            "input": {
                "source": "GOG",
                "stableSourceId": "1771589310",
                "displayName": "Disco Elysium - The Final Cut",
                "appType": "GAME",
            },
            "expectedSteamAppId": 632470,
            "sourceEvidenceUrls": ["https://evidence.test/gog"],
        },
        {
            "caseId": "epic",
            "input": {
                "source": "EPIC",
                "stableSourceId": epic_id,
                "displayName": "Subnautica",
                "appType": "GAME",
            },
            "rawSourceIdentity": {
                "namespace": "jaguar",
                "catalogId": "3257e06c28764231acd93049f3774ed6",
            },
            "expectedSteamAppId": 264710,
            "sourceEvidenceUrls": ["https://evidence.test/epic"],
        },
        {
            "caseId": "amazon",
            "input": {
                "source": "AMAZON",
                "stableSourceId": "amzn1.adg.product.a533b569-f163-4ad9-b0dc-e6c069695a72",
                "displayName": "GRIME",
                "appType": "GAME",
            },
            "expectedSteamAppId": 1123050,
            "sourceEvidenceUrls": ["https://evidence.test/amazon"],
        },
    ]
    transport = UrlTransport(
        {
            "https://evidence.test/gog": (
                200,
                "application/json",
                json.dumps({"id": 1771589310, "title": "Disco Elysium - The Final Cut"}),
            ),
            "https://evidence.test/epic": (
                200,
                "application/json",
                json.dumps(
                    {
                        "namespace": "jaguar",
                        "catalogId": "3257e06c28764231acd93049f3774ed6",
                        "title": "Subnautica",
                    }
                ),
            ),
            "https://evidence.test/amazon": (
                200,
                "text/plain",
                "GRIME amzn1.adg.product.a533b569-f163-4ad9-b0dc-e6c069695a72 steam 1123050",
            ),
        }
    )

    result = validate_sources(cases, live=True, transport=transport)

    assert result["valid"] is True
    assert result["validated"] == 3
    assert all(item["corroborated"] for item in result["cases"])
    assert all(diagnostic["bodyBytes"] > 0 for diagnostic in result["diagnostics"])
    assert {diagnostic["parser"] for diagnostic in result["diagnostics"]} == {
        "SOURCE_EVIDENCE_CORROBORATED"
    }


def test_gog_json_unicode_escapes_are_decoded_before_title_corroboration():
    case = {
        "caseId": "gog-baldurs-gate-3",
        "input": {
            "source": "GOG",
            "stableSourceId": "1456460669",
            "displayName": "Baldur's Gate 3",
            "appType": "GAME",
        },
        "expectedSteamAppId": 1086940,
        "sourceEvidenceUrls": ["https://evidence.test/gog-escaped"],
    }
    transport = UrlTransport(
        {
            "https://evidence.test/gog-escaped": (
                200,
                "application/json",
                '{"id":1456460669,"title":"Baldur' + chr(92) + 'u0027s Gate 3"}',
            )
        }
    )

    result = validate_sources([case], live=True, transport=transport)

    assert result["valid"] is True
    assert result["cases"][0]["corroborated"] is True


def test_live_source_failure_preserves_endpoint_status_body_and_parser_diagnostics():
    case = {
        "caseId": "failed",
        "input": {
            "source": "GOG",
            "stableSourceId": "1771589310",
            "displayName": "Disco Elysium",
            "appType": "GAME",
        },
        "expectedSteamAppId": 632470,
        "sourceEvidenceUrls": ["https://evidence.test/fail"],
    }
    transport = UrlTransport(
        {"https://evidence.test/fail": (503, "text/html", "<html>unavailable</html>")}
    )

    result = validate_sources([case], live=True, transport=transport)

    assert result["valid"] is False
    assert result["failed"] == 1
    assert result["diagnostics"][0] == {
        "caseId": "failed",
        "source": "GOG",
        "endpoint": "https://evidence.test/fail",
        "status": 503,
        "contentType": "text/html",
        "bodyBytes": 24,
        "parser": "HTTP_ERROR",
        "error": None,
    }
