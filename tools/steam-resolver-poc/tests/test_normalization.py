from steam_resolver.normalization import (
    edition_tokens,
    normalize_developer,
    normalize_title,
    title_queries,
)


def test_title_normalization_is_nfkc_casefolded_and_punctuation_stable():
    assert normalize_title("  Ｃontrol™: Ultimate—Edition®  ") == "control ultimate edition"
    assert normalize_title("Clouds & Sheep 2") == "clouds and sheep 2"
    assert normalize_title("Baldur’s Gate III") == "baldur s gate 3"


def test_developer_normalization_removes_only_legal_suffixes():
    assert normalize_developer("Thekla, Inc.") == "thekla"
    assert normalize_developer("Unknown Worlds Entertainment, LLC") == "unknown worlds entertainment"
    assert normalize_developer("2D BOY") == "2d boy"


def test_title_queries_add_safe_alias_and_case_insensitively_distinct_normalized_query():
    assert title_queries("Playdead's INSIDE") == (
        "Playdead's INSIDE",
        "INSIDE",
        "playdead s inside",
    )
    assert title_queries("Playdead’s LIMBO") == (
        "Playdead’s LIMBO",
        "LIMBO",
        "playdead s limbo",
    )
    assert title_queries("Baldur's Gate 3") == ("Baldur's Gate 3", "baldur s gate 3")
    assert title_queries("Disco Elysium - The Final Cut") == (
        "Disco Elysium - The Final Cut",
        "disco elysium the final cut",
    )
    assert title_queries("Control Ultimate Edition") == ("Control Ultimate Edition",)


def test_edition_terms_are_preserved_and_detected():
    source = "Divinity: Original Sin 2 - Definitive Edition"
    assert "definitive" in normalize_title(source)
    assert edition_tokens(source) == frozenset({"definitive"})
    assert edition_tokens("Disco Elysium - The Final Cut") == frozenset({"final cut"})
    assert edition_tokens("Control Ultimate Edition") == frozenset({"ultimate"})
