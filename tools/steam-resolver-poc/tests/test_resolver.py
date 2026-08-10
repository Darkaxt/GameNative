from steam_resolver.models import AppType, OwnedCopy, ProviderBatch, Source, SteamCandidate
from steam_resolver.resolver import SteamResolver


def copy(**overrides):
    values = {
        "source": Source.GOG,
        "stable_source_id": "2049187585",
        "display_name": "Control Ultimate Edition",
        "developer": "Remedy Entertainment",
        "release_year": 2020,
        "app_type": AppType.GAME,
    }
    values.update(overrides)
    return OwnedCopy(**values)


def game(app_id, title="Control Ultimate Edition", **overrides):
    values = {
        "steam_app_id": app_id,
        "title": title,
        "developer": "Remedy Entertainment",
        "publisher": "505 Games",
        "release_year": 2020,
        "app_type": AppType.GAME,
        "verified": True,
    }
    values.update(overrides)
    return SteamCandidate(**values)


class FakeProvider:
    name = "fake-storesearch"

    def __init__(self, batch):
        self.batch = batch
        self.queries = None

    def retrieve(self, queries):
        self.queries = queries
        return self.batch


def test_resolver_auto_accepts_high_confidence_verified_candidate():
    provider = FakeProvider(ProviderBatch(candidates=(game(870780),)))

    result = SteamResolver(provider).resolve(copy())

    assert result["decision"] == "AUTO_ACCEPT"
    assert result["candidateSteamAppId"] == 870780
    assert result["matchMethod"] == "STEAM_CATALOG"
    assert result["confidence"] == "HIGH"
    assert result["decisionSource"] == "AUTOMATIC"
    assert result["score"] == 1.0
    assert result["input"]["stableSourceId"] == "2049187585"
    assert provider.queries == ("Control Ultimate Edition",)


def test_resolver_requires_review_for_ambiguous_candidates_without_source_year():
    provider = FakeProvider(
        ProviderBatch(candidates=(game(20), game(10, release_year=2021)))
    )

    result = SteamResolver(provider).resolve(copy(release_year=None))

    assert result["decision"] == "REVIEW_REQUIRED"
    assert result["confidence"] == "REVIEW_REQUIRED"
    assert [item["steamAppId"] for item in result["candidates"]] == [10, 20]
    assert result["margin"] == 0.0


def test_resolver_returns_unmatched_only_after_complete_provider_no_match():
    result = SteamResolver(FakeProvider(ProviderBatch())).resolve(copy())

    assert result["decision"] == "UNMATCHED"
    assert result["confidence"] == "UNMATCHED"
    assert result["candidateSteamAppId"] is None


def test_resolver_distinguishes_provider_unavailable():
    batch = ProviderBatch(
        provider_unavailable=True,
        diagnostics=({"endpoint": "storesearch", "status": 503, "parser": "HTTP_ERROR"},),
    )

    result = SteamResolver(FakeProvider(batch)).resolve(copy())

    assert result["decision"] == "PROVIDER_UNAVAILABLE"
    assert result["confidence"] == "PROVIDER_UNAVAILABLE"
    assert result["candidateSteamAppId"] is None
    assert result["diagnostics"][0]["status"] == 503


def test_resolver_keeps_partial_exact_search_hit_for_review_but_never_accepts_it():
    batch = ProviderBatch(
        candidates=(game(870780, verified=False, developer=None, publisher=None, release_year=None),),
        partial=True,
        diagnostics=({"parser": "HTTP_ERROR", "status": 503},),
    )

    result = SteamResolver(FakeProvider(batch)).resolve(copy())

    assert result["decision"] == "REVIEW_REQUIRED"
    assert result["candidateSteamAppId"] == 870780
    assert result["candidates"][0]["verified"] is False


def test_minimal_input_exact_verified_game_is_reviewable_not_unmatched():
    minimal = OwnedCopy.from_dict(
        {
            "source": "GOG",
            "stableSourceId": "2049187585",
            "displayName": "Control Ultimate Edition",
        }
    )

    result = SteamResolver(
        FakeProvider(ProviderBatch(candidates=(game(870780),)))
    ).resolve(minimal)

    assert result["decision"] == "REVIEW_REQUIRED"
    assert result["confidence"] == "REVIEW_REQUIRED"
    assert result["candidateSteamAppId"] == 870780
    assert result["score"] == 0.56


def test_partial_provider_with_verified_candidate_never_auto_accepts():
    result = SteamResolver(
        FakeProvider(ProviderBatch(candidates=(game(870780),), partial=True))
    ).resolve(copy())

    assert result["decision"] == "REVIEW_REQUIRED"
    assert result["candidateSteamAppId"] == 870780


def test_partial_provider_without_candidates_is_provider_unavailable():
    result = SteamResolver(FakeProvider(ProviderBatch(partial=True))).resolve(copy())

    assert result["decision"] == "PROVIDER_UNAVAILABLE"
    assert result["confidence"] == "PROVIDER_UNAVAILABLE"
    assert result["candidateSteamAppId"] is None


def test_ambiguity_selects_closest_verified_candidate_at_or_before_source_year():
    source = copy(
        display_name="Shared Game",
        developer="Shared Studio",
        release_year=2015,
    )
    candidates = (
        game(100, title="Shared Game", developer="Shared Studio", release_year=2010),
        game(150, title="Shared Game", developer="Shared Studio", release_year=2012),
        game(300, title="Shared Game", developer="Shared Studio", release_year=2020),
    )

    result = SteamResolver(FakeProvider(ProviderBatch(candidates=candidates))).resolve(source)

    assert result["decision"] == "AUTO_ACCEPT"
    assert result["confidence"] == "HIGH"
    assert result["candidateSteamAppId"] == 150
    assert [item["steamAppId"] for item in result["candidates"]] == [150, 100, 300]
    assert result["evidence"]["ambiguity"] == {
        "kind": "AMBIGUITY_RESOLVED_BY_PRIOR_YEAR",
        "sourceYear": 2015,
        "selectedSteamAppId": 150,
        "eligibleCandidates": [
            {"steamAppId": 150, "releaseYear": 2012, "yearDelta": 3},
            {"steamAppId": 100, "releaseYear": 2010, "yearDelta": 5},
        ],
    }


def test_same_year_candidate_wins_and_later_remaster_cannot_displace_base():
    source = copy(
        display_name="Shared Game",
        developer="Shared Studio",
        release_year=2015,
    )
    candidates = (
        game(100, title="Shared Game", developer="Shared Studio", release_year=2015),
        game(
            300,
            title="Shared Game Remastered",
            developer="Shared Studio",
            release_year=2020,
        ),
    )

    result = SteamResolver(FakeProvider(ProviderBatch(candidates=candidates))).resolve(source)

    assert result["decision"] == "AUTO_ACCEPT"
    assert result["candidateSteamAppId"] == 100
    assert result["evidence"]["ambiguity"]["kind"] == "AMBIGUITY_RESOLVED_BY_PRIOR_YEAR"
    assert result["evidence"]["ambiguity"]["eligibleCandidates"] == [
        {"steamAppId": 100, "releaseYear": 2015, "yearDelta": 0}
    ]


def test_year_ambiguity_remains_review_when_source_year_missing_or_no_prior_candidate():
    candidates = (
        game(100, title="Shared Game", developer="Shared Studio", release_year=2010),
        game(300, title="Shared Game", developer="Shared Studio", release_year=2020),
    )
    missing_year = SteamResolver(
        FakeProvider(ProviderBatch(candidates=candidates))
    ).resolve(
        copy(display_name="Shared Game", developer="Shared Studio", release_year=None)
    )
    no_prior = SteamResolver(FakeProvider(ProviderBatch(candidates=candidates))).resolve(
        copy(display_name="Shared Game", developer="Shared Studio", release_year=2005)
    )

    assert missing_year["decision"] == "REVIEW_REQUIRED"
    assert no_prior["decision"] == "REVIEW_REQUIRED"


def test_year_ambiguity_remains_review_when_closest_prior_candidates_tie():
    source = copy(
        display_name="Shared Game",
        developer="Shared Studio",
        release_year=2015,
    )
    candidates = (
        game(100, title="Shared Game", developer="Shared Studio", release_year=2012),
        game(150, title="Shared Game", developer="Shared Studio", release_year=2012),
        game(300, title="Shared Game", developer="Shared Studio", release_year=2020),
    )

    result = SteamResolver(FakeProvider(ProviderBatch(candidates=candidates))).resolve(source)

    assert result["decision"] == "REVIEW_REQUIRED"
    assert result["confidence"] == "REVIEW_REQUIRED"


def test_resolver_output_candidates_are_deduplicated_sorted_and_bounded():
    batch = ProviderBatch(
        candidates=tuple(game(app_id) for app_id in [9, 8, 7, 6, 5, 4, 3, 2, 1, 1])
    )

    result = SteamResolver(FakeProvider(batch), max_candidates=5).resolve(copy())

    assert [item["steamAppId"] for item in result["candidates"]] == [1, 2, 3, 4, 5]
    assert len(result["candidates"]) == 5


def test_unknown_source_app_type_can_never_auto_accept():
    result = SteamResolver(FakeProvider(ProviderBatch(candidates=(game(870780),)))).resolve(
        copy(app_type=AppType.UNKNOWN)
    )

    assert result["decision"] == "REVIEW_REQUIRED"
