from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urlsplit

from .normalization import normalize_title


_EPIC_SLUG = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
_EPIC_LOCALE = re.compile(r"[a-z]{2}(?:-[A-Z]{2})?")
_EPIC_STORE_HOST = "store.epicgames.com"
_DEFAULT_LOCALE = "en-US"


@dataclass(frozen=True)
class EpicProductLocation:
    locale: str
    slug: str
    store_url: str
    derived: bool


def validate_epic_product_slug(value: str) -> str:
    if not isinstance(value, str) or not _EPIC_SLUG.fullmatch(value):
        raise ValueError(
            "epicProductSlug must be a canonical lowercase ASCII product slug"
        )
    if len(value) > 160:
        raise ValueError("epicProductSlug must not exceed 160 characters")
    return value


def parse_epic_store_url(value: str) -> tuple[str, str]:
    if not isinstance(value, str):
        raise TypeError("epicStoreUrl must be a string")
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https"
        or parsed.netloc != _EPIC_STORE_HOST
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError(
            "epicStoreUrl must use https://store.epicgames.com without "
            "credentials, port, query, or fragment"
        )
    parts = parsed.path.split("/")
    if len(parts) != 4 or parts[0] or parts[2] != "p":
        raise ValueError("epicStoreUrl path must be /{locale}/p/{slug}")
    locale, slug = parts[1], parts[3]
    if not _EPIC_LOCALE.fullmatch(locale):
        raise ValueError("epicStoreUrl locale is not canonical")
    validate_epic_product_slug(slug)
    canonical = f"https://{_EPIC_STORE_HOST}/{locale}/p/{slug}"
    if value != canonical:
        raise ValueError("epicStoreUrl must be canonical")
    return locale, slug


def resolve_epic_product_location(
    display_name: str,
    *,
    product_slug: str | None,
    store_url: str | None,
) -> EpicProductLocation:
    if product_slug is not None:
        slug = validate_epic_product_slug(product_slug)
        locale = _DEFAULT_LOCALE
        return EpicProductLocation(
            locale=locale,
            slug=slug,
            store_url=f"https://{_EPIC_STORE_HOST}/{locale}/p/{slug}",
            derived=False,
        )
    if store_url is not None:
        locale, slug = parse_epic_store_url(store_url)
        return EpicProductLocation(locale, slug, store_url, False)

    normalized = normalize_title(display_name)
    slug = "-".join(
        part for part in normalized.split() if part.isascii() and part.isalnum()
    )
    if not slug:
        raise ValueError("displayName cannot produce a canonical Epic product slug")
    validate_epic_product_slug(slug)
    locale = _DEFAULT_LOCALE
    return EpicProductLocation(
        locale=locale,
        slug=slug,
        store_url=f"https://{_EPIC_STORE_HOST}/{locale}/p/{slug}",
        derived=True,
    )
