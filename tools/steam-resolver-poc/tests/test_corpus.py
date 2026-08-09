from pathlib import Path

from steam_resolver.corpus import (
    evaluate_corpus,
    load_corpus,
    validate_corpus_contract,
    validate_sources,
)
from steam_resolver.models import OwnedCopy
from steam_resolver.resolver import SteamResolver
from steam_resolver.steam import FixtureProvider


CORPUS = Path(__file__).parent / "corpus" / "real-30.json"
CATALOG_FIXTURE = Path(__file__).parent / "fixtures" / "steam-catalog-30.json"

EXPECTED = {
    "GOG": {
        "1771589310": 632470,
        "2093619782": 1091500,
        "1207664663": 292030,
        "1456460669": 1086940,
        "1453375253": 413150,
        "1446213994": 275850,
        "2049187585": 870780,
        "1308320804": 367520,
        "1584823040": 435150,
        "1207665503": 105600,
    },
    "AMAZON": {
        "amzn1.adg.product.5d35cae7-39d1-4e53-ba92-36004c4a5211": 228280,
        "amzn1.adg.product.852e8b11-4233-42dc-be63-89f2ea9ce78f": 257350,
        "amzn1.adg.product.2cbd524e-6778-4f53-b367-b3255aca792c": 439800,
        "amzn1.adg.product.21965245-d668-4a8f-818a-38133698587b": 898890,
        "amzn1.adg.product.35fd4aba-910f-4f83-b4b8-5ef898a20ae1": 735290,
        "amzn1.adg.product.a533b569-f163-4ad9-b0dc-e6c069695a72": 1123050,
        "amzn1.adg.product.bcce181d-9bb0-4feb-8cac-1aad48afb56b": 645320,
        "amzn1.adg.product.bc8c2cf6-ded7-42fb-91d9-d0865af9e57a": 208580,
        "amzn1.adg.product.e31e4571-b8d9-444e-852f-93846749eb40": 874260,
        "amzn1.adg.product.c25c2ac3-b318-4192-96b7-bafef73060b9": 2187290,
    },
}


def test_authoritative_corpus_has_exactly_30_and_10_per_store():
    cases = load_corpus(CORPUS)
    by_source = {
        source: [case for case in cases if case["input"]["source"] == source]
        for source in ("GOG", "EPIC", "AMAZON")
    }

    assert len(cases) == 30
    assert {source: len(group) for source, group in by_source.items()} == {
        "GOG": 10,
        "EPIC": 10,
        "AMAZON": 10,
    }
    for source, expected in EXPECTED.items():
        assert {
            case["input"]["stableSourceId"]: case["expectedSteamAppId"]
            for case in by_source[source]
        } == expected
    assert [case["expectedSteamAppId"] for case in by_source["EPIC"]] == [
        504230,
        264710,
        304430,
        210970,
        388880,
        433340,
        40800,
        311690,
        22000,
        48000,
    ]


def test_contract_validates_unique_ids_epic_derivation_and_required_urls():
    result = validate_corpus_contract(load_corpus(CORPUS))

    assert result == {"valid": True, "caseCount": 30, "countsBySource": {"AMAZON": 10, "EPIC": 10, "GOG": 10}, "errors": []}


def test_offline_source_validation_checks_forms_without_network():
    summary = validate_sources(load_corpus(CORPUS), live=False)

    assert summary["mode"] == "offline"
    assert summary["valid"] is True
    assert summary["validated"] == 30
    assert summary["failed"] == 0
    assert summary["diagnostics"] == []


class GoldBlindResolver:
    def __init__(self, expected_by_stable_id):
        self.expected_by_stable_id = expected_by_stable_id
        self.received = []

    def resolve(self, owned_copy):
        assert isinstance(owned_copy, OwnedCopy)
        assert not hasattr(owned_copy, "expected_steam_app_id")
        self.received.append(owned_copy.to_dict())
        expected = self.expected_by_stable_id[owned_copy.stable_source_id]
        return {
            "decision": "AUTO_ACCEPT",
            "candidateSteamAppId": expected,
            "candidates": [
                {"steamAppId": expected, "title": owned_copy.display_name, "score": 1.0},
                {"steamAppId": expected + 1, "title": "Other", "score": 0.1},
            ],
            "diagnostics": [],
        }


def test_corpus_evaluation_exposes_gold_only_after_each_resolution():
    cases = load_corpus(CORPUS)
    resolver = GoldBlindResolver(
        {case["input"]["stableSourceId"]: case["expectedSteamAppId"] for case in cases}
    )

    summary = evaluate_corpus(cases, resolver)

    assert len(resolver.received) == 30
    assert summary["overall"] == {
        "caseCount": 30,
        "recallAt5Count": 30,
        "recallAt5": 1.0,
        "top1Count": 30,
        "top1": 1.0,
        "autoAcceptedCount": 30,
        "autoAcceptedRate": 1.0,
        "automaticCorrectCount": 30,
        "automaticCorrectRate": 1.0,
    }
    assert set(summary["perStore"]) == {"AMAZON", "EPIC", "GOG"}
    assert all(store["top1Count"] == 10 for store in summary["perStore"].values())


def test_recorded_catalog_fixture_resolves_authoritative_30_without_gold_lookup():
    resolver = SteamResolver(FixtureProvider(CATALOG_FIXTURE))

    summary = evaluate_corpus(load_corpus(CORPUS), resolver)

    assert summary["overall"]["recallAt5Count"] == 30
    assert summary["overall"]["top1Count"] == 30
    assert summary["overall"]["autoAcceptedCount"] == 30
    assert summary["overall"]["automaticCorrectCount"] == 30


class WrongAutomaticResolver:
    def __init__(self, expected):
        self.expected = expected

    def resolve(self, owned_copy):
        return {
            "decision": "AUTO_ACCEPT",
            "candidateSteamAppId": self.expected + 2,
            "candidates": [
                {"steamAppId": self.expected + 1, "title": "Wrong top", "score": 1.0}
            ],
            "diagnostics": [],
        }


def test_wrong_auto_accept_is_counted_and_reported_separately():
    case = load_corpus(CORPUS)[0]
    expected = case["expectedSteamAppId"]

    summary = evaluate_corpus([case], WrongAutomaticResolver(expected))

    assert summary["overall"]["autoAcceptedCount"] == 1
    assert summary["overall"]["automaticCorrectCount"] == 0
    assert summary["cases"][0]["autoAccepted"] is True
    assert summary["cases"][0]["automaticCorrect"] is False
    assert summary["failures"] == [
        {
            "caseId": case["caseId"],
            "source": "GOG",
            "expectedSteamAppId": expected,
            "reasons": ["RECALL_AT_5_MISS", "TOP_1_MISS", "WRONG_AUTOMATIC_MATCH"],
            "decision": "AUTO_ACCEPT",
            "topCandidateSteamAppId": expected + 1,
        }
    ]
