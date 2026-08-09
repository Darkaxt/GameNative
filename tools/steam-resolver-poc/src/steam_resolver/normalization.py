from __future__ import annotations

import re
import unicodedata


_MARKS = str.maketrans({"™": "", "®": "", "©": "", "&": " and "})
_NON_WORD = re.compile(r"[^\w]+", re.UNICODE)
_WHITESPACE = re.compile(r"\s+")
_ROMAN_NUMERALS = {"iii": "3"}
_LEGAL_SUFFIXES = {
    "inc",
    "incorporated",
    "llc",
    "ltd",
    "limited",
    "corp",
    "corporation",
    "plc",
}
_PLAYDEAD_ALIAS = re.compile(r"^playdead['’]s\s+(.+)$", re.IGNORECASE)
_EDITION_PATTERNS = (
    ("director s cut", "director's cut"),
    ("final cut", "final cut"),
    ("game of the year", "game of the year"),
    ("definitive", "definitive"),
    ("enhanced", "enhanced"),
    ("ultimate", "ultimate"),
    ("remastered", "remastered"),
    ("redux", "redux"),
    ("complete", "complete"),
    ("anniversary", "anniversary"),
)


def _words(value: str) -> list[str]:
    normalized = unicodedata.normalize("NFKC", value.translate(_MARKS)).translate(_MARKS).casefold()
    words = _WHITESPACE.sub(" ", _NON_WORD.sub(" ", normalized)).strip().split()
    return [_ROMAN_NUMERALS.get(word, word) for word in words]


def normalize_title(value: str | None) -> str:
    if not value:
        return ""
    return " ".join(_words(value))


def normalize_developer(value: str | None) -> str:
    if not value:
        return ""
    words = _words(value)
    while words and words[-1] in _LEGAL_SUFFIXES:
        words.pop()
    return " ".join(words)


def title_queries(value: str) -> tuple[str, ...]:
    original = value.strip()
    queries = [original]
    alias_match = _PLAYDEAD_ALIAS.fullmatch(original)
    if alias_match:
        queries.append(alias_match.group(1).strip())
    normalized = normalize_title(original)
    if normalized.casefold() != original.casefold():
        queries.append(normalized)
    return tuple(dict.fromkeys(query for query in queries if query))


def normalized_title_keys(value: str) -> tuple[tuple[str, str], ...]:
    keys: list[tuple[str, str]] = [("EXACT", normalize_title(value))]
    for alias in title_queries(value)[1:]:
        keys.append(("SAFE_ALIAS_EXACT", normalize_title(alias)))
    return tuple(dict.fromkeys(keys))


def edition_tokens(value: str | None) -> frozenset[str]:
    key = normalize_title(value)
    return frozenset(label for phrase, label in _EDITION_PATTERNS if phrase in key)


def edition_base_title(value: str | None) -> str:
    key = normalize_title(value)
    for phrase, _ in _EDITION_PATTERNS:
        key = re.sub(rf"\b{re.escape(phrase)}\b", " ", key)
    words = [word for word in key.split() if word not in {"edition", "version"}]
    while words and words[-1] == "the":
        words.pop()
    return " ".join(words)
