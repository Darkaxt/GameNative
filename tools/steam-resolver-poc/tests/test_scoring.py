from steam_resolver.models import AppType, OwnedCopy, Source, SteamCandidate
from steam_resolver.scoring import rank_candidates, score_candidate, select_decision


def source_copy(**overrides):
    values = {
        "source": Source.GOG,
        "stable_source_id": "1771589310",
        "display_name": "Control Ultimate Edition",
        "developer": "Remedy Entertainment",
        "release_year": 2020,
        "app_type": AppType.GAME,
    }
    values.update(overrides)
    return OwnedCopy(**values)


def candidate(**overrides):
    values = {
        "steam_app_id": 870780,
        "title": "Control Ultimate Edition",
        "developer": "Remedy Entertainment",
        "publisher": "505 Games",
        "release_year": 2020,
        "app_type": AppType.GAME,
        "verified": True,
    }
    values.update(overrides)
    return SteamCandidate(**values)


def test_exact_metadata_score_uses_declared_weights():
    scored = score_candidate(source_copy(), candidate())

    assert scored.score == 1.0
    assert scored.evidence["title"]["kind"] == "EXACT"
    assert scored.evidence["developer"]["kind"] == "EXACT"
    assert scored.evidence["releaseYear"]["delta"] == 0
    assert scored.evidence["appType"]["compatible"] is True
    assert scored.edition_conflict is False


def test_exact_title_and_year_are_sufficient_corroboration_at_boundary():
    scored = score_candidate(source_copy(developer=None), candidate(developer=None, publisher=None))

    assert scored.score == 0.8
    decision = select_decision([scored])
    assert decision == ("AUTO_ACCEPT", "HIGH")


def test_safe_alias_can_auto_accept_but_fuzzy_title_cannot():
    alias_source = source_copy(
        display_name="Playdead's INSIDE", developer="Playdead", release_year=2016
    )
    alias = score_candidate(
        alias_source,
        candidate(
            steam_app_id=304430,
            title="INSIDE",
            developer="Playdead",
            publisher="Playdead",
            release_year=2016,
        ),
    )

    assert alias.score == 0.97
    assert alias.evidence["title"]["kind"] == "SAFE_ALIAS_EXACT"
    assert select_decision([alias]) == ("AUTO_ACCEPT", "HIGH")


def test_year_conflict_penalizes_and_edition_conflict_blocks_auto_accept():
    conflicted = score_candidate(
        source_copy(),
        candidate(title="Control Definitive Edition", release_year=2015),
    )

    assert conflicted.evidence["releaseYear"]["weight"] == -0.1
    assert conflicted.edition_conflict is True
    assert select_decision([conflicted]) == ("REVIEW_REQUIRED", "REVIEW_REQUIRED")


def test_cross_store_original_year_does_not_veto_exact_verified_kotor_match():
    source = source_copy(
        source=Source.AMAZON,
        stable_source_id="amzn1.adg.product.bc8c2cf6-ded7-42fb-91d9-d0865af9e57a",
        display_name="Star Wars: Knights of the Old Republic II - The Sith Lords",
        developer="Obsidian Entertainment",
        release_year=2005,
    )
    steam = candidate(
        steam_app_id=208580,
        title="STAR WARS™ Knights of the Old Republic™ II - The Sith Lords™",
        developer="Obsidian Entertainment",
        publisher="LucasArts",
        release_year=2012,
    )

    scored = score_candidate(source, steam)

    assert scored.evidence["title"]["kind"] == "EXACT"
    assert scored.evidence["developer"]["kind"] == "EXACT"
    assert scored.evidence["releaseYear"] == {
        "source": 2005,
        "candidate": 2012,
        "delta": 7,
        "kind": "CROSS_STORE_RELEASE_VARIANCE",
        "weight": 0.0,
    }
    assert scored.score == 0.86
    assert select_decision([scored]) == ("AUTO_ACCEPT", "HIGH")


def test_margin_below_eight_hundredths_requires_review():
    first = score_candidate(source_copy(), candidate(steam_app_id=20))
    second = score_candidate(source_copy(), candidate(steam_app_id=10, release_year=2021))

    assert round(first.score - second.score, 4) == 0.04
    assert select_decision(rank_candidates([first, second])) == (
        "REVIEW_REQUIRED",
        "REVIEW_REQUIRED",
    )


def test_ranking_is_score_descending_then_appid_ascending_and_bounded():
    candidates = [
        score_candidate(source_copy(), candidate(steam_app_id=30)),
        score_candidate(source_copy(), candidate(steam_app_id=10)),
        score_candidate(source_copy(), candidate(steam_app_id=20)),
    ]

    assert [item.candidate.steam_app_id for item in rank_candidates(candidates, limit=2)] == [10, 20]


def test_unverified_search_hit_never_auto_accepts():
    unverified = score_candidate(source_copy(), candidate(verified=False))

    assert select_decision([unverified], provider_partial=True) == (
        "REVIEW_REQUIRED",
        "REVIEW_REQUIRED",
    )
