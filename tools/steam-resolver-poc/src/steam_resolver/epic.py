from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from urllib.parse import urlsplit

from .epic_input import EpicProductLocation, resolve_epic_product_location
from .http import UrllibTransport, diagnostic, ensure_retrying_transport
from .models import AppType, OwnedCopy, Source
from .normalization import normalize_title
from .source_ids import decode_epic_stable_id


EPIC_CMS_BASE_URL = "https://store-content.ak.epicgames.com/api"
MAX_EPIC_CMS_BODY_BYTES = 1_048_576
MAX_CAROUSEL_ITEMS = 100
MAX_SCREENSHOTS = 20
MAX_MOVIES = 10
MAX_LANGUAGES = 64
MAX_MEDIA_URL_CHARS = 2_048
MAX_TEXT_CHARS = 50_000
MAX_RECIPE_CHARS = 300_000
_OFFER_ID = re.compile(r"[0-9a-f]{32}")
_MEDIA_ROOTS = ("epicgames.com", "unrealengine.com")
_FAILURE_RELEASE_LABELS = {
    "coming soon",
    "tba",
    "tbd",
    "to be announced",
    "available soon",
}


class SlugRequired(RuntimeError):
    def __init__(self, display_name: str, attempted_slug: str | None) -> None:
        self.display_name = display_name
        self.attempted_slug = attempted_slug
        super().__init__(
            "No Epic product page was found for the single title-derived slug; "
            "provide epicProductSlug or epicStoreUrl"
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "error": "SLUG_REQUIRED",
            "message": str(self),
            "attemptedSlug": self.attempted_slug,
        }


class EpicCmsValidationError(RuntimeError):
    pass


@dataclass(frozen=True)
class EpicCatalogResult:
    presentation: dict[str, Any]
    evidence: dict[str, Any]
    warnings: tuple[str, ...]
    diagnostics: tuple[dict[str, Any], ...]


class EpicCatalogProvider:
    name = "epic-cms"

    def __init__(
        self,
        *,
        transport: Any | None = None,
        timeout: float = 10.0,
        sleeper: Any = time.sleep,
        clock: Any = time.time,
    ) -> None:
        self.transport = ensure_retrying_transport(
            transport or UrllibTransport(), sleeper=sleeper, clock=clock
        )
        self.timeout = timeout

    def retrieve(self, owned_copy: OwnedCopy) -> EpicCatalogResult:
        if owned_copy.source is not Source.EPIC or owned_copy.app_type is not AppType.GAME:
            raise ValueError("Epic CMS presentation requires an Epic GAME input")
        namespace, catalog_id = decode_epic_stable_id(owned_copy.stable_source_id)
        try:
            location = resolve_epic_product_location(
                owned_copy.display_name,
                product_slug=owned_copy.epic_product_slug,
                store_url=owned_copy.epic_store_url,
            )
        except ValueError as error:
            if owned_copy.epic_product_slug is None and owned_copy.epic_store_url is None:
                raise SlugRequired(owned_copy.display_name, None) from error
            raise

        endpoint = (
            f"{EPIC_CMS_BASE_URL}/{location.locale}/content/products/{location.slug}"
        )
        response = self.transport.get(
            endpoint,
            headers={"Accept": "application/json"},
            timeout=self.timeout,
        )
        if response.status == 404 and location.derived:
            raise SlugRequired(owned_copy.display_name, location.slug)
        if response.status != 200:
            raise RuntimeError(
                f"Epic CMS request failed: status={response.status} error={response.error}"
            )
        if response.endpoint != endpoint:
            raise EpicCmsValidationError(
                "Epic CMS response endpoint does not match the requested public CMS endpoint"
            )
        if len(response.body) > MAX_EPIC_CMS_BODY_BYTES:
            raise EpicCmsValidationError("Epic CMS body exceeds the 1 MiB limit")
        if response.content_type not in {"application/json", "text/json"}:
            raise EpicCmsValidationError("Epic CMS response is not JSON")
        try:
            payload = json.loads(response.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise EpicCmsValidationError("Epic CMS response contains malformed JSON") from error

        presentation, evidence, warnings = _parse_cms_payload(
            payload,
            owned_copy=owned_copy,
            namespace=namespace,
            catalog_id=catalog_id,
            location=location,
        )
        return EpicCatalogResult(
            presentation=presentation,
            evidence=evidence,
            warnings=tuple(warnings),
            diagnostics=(
                diagnostic(
                    response,
                    "EPIC_CMS_OK",
                    namespace=namespace,
                    slug=location.slug,
                    offerId=presentation["offerId"],
                ),
            ),
        )


def _parse_cms_payload(
    payload: Any,
    *,
    owned_copy: OwnedCopy,
    namespace: str,
    catalog_id: str,
    location: EpicProductLocation,
) -> tuple[dict[str, Any], dict[str, Any], list[str]]:
    root = _mapping(payload, "Epic CMS root")
    pages = root.get("pages")
    if not isinstance(pages, list):
        raise EpicCmsValidationError("Epic CMS pages must be a list")
    if any(not isinstance(page, dict) for page in pages):
        raise EpicCmsValidationError("Every Epic CMS page must be an object")
    product_pages = [page for page in pages if page.get("type") == "productHome"]
    if len(product_pages) != 1:
        raise EpicCmsValidationError(
            "Epic CMS must contain exactly one productHome page"
        )
    page = product_pages[0]

    if root.get("namespace") != namespace or page.get("namespace") != namespace:
        raise EpicCmsValidationError(
            "Epic CMS namespace does not match the stable source identity"
        )
    if root.get("_slug") != location.slug:
        raise EpicCmsValidationError("Epic CMS slug does not match the requested product")
    _validate_optional_locale(root.get("_locale"), location.locale)
    _validate_optional_locale(page.get("_locale"), location.locale)

    root_title = _required_text(root.get("productName"), "root productName", 512)
    page_title = _required_text(page.get("productName"), "productHome productName", 512)
    expected_title = normalize_title(owned_copy.display_name)
    if (
        not expected_title
        or normalize_title(root_title) != expected_title
        or normalize_title(page_title) != expected_title
    ):
        raise EpicCmsValidationError(
            "Epic CMS title does not strictly match the source title"
        )

    offer = _mapping(page.get("offer"), "productHome offer")
    offer_id = offer.get("id")
    if (
        offer.get("hasOffer") is not True
        or offer.get("namespace") != namespace
        or not isinstance(offer_id, str)
        or not _OFFER_ID.fullmatch(offer_id)
    ):
        raise EpicCmsValidationError("Epic CMS offer identity is invalid")

    item = page.get("item")
    if item is None:
        cms_catalog_id = ""
    else:
        item = _mapping(item, "productHome item")
        cms_catalog_id = item.get("catalogId", "")
        cms_item_namespace = item.get("namespace", "")
        if not isinstance(cms_catalog_id, str):
            raise EpicCmsValidationError("Epic CMS catalogId must be a string")
        if cms_item_namespace not in ("", namespace):
            raise EpicCmsValidationError(
                "Epic CMS item namespace conflicts with the stable source identity"
            )
    catalog_corroborated = bool(cms_catalog_id)
    if catalog_corroborated and cms_catalog_id != catalog_id:
        raise EpicCmsValidationError(
            "Epic CMS catalogId conflicts with the stable source identity"
        )

    data = _mapping(page.get("data"), "productHome data")
    about = _optional_mapping(data.get("about"), "about")
    developer = _optional_text(about.get("developerAttribution"), "developer", 512)
    publisher = _optional_text(about.get("publisherAttribution"), "publisher", 512)
    short_description = _optional_text(
        about.get("shortDescription"), "shortDescription", MAX_TEXT_CHARS
    )
    about_text = _optional_text(
        about.get("description"), "about description", MAX_TEXT_CHARS
    )

    hero = _optional_mapping(data.get("hero"), "hero")
    header_image = _media_url(
        hero.get("backgroundImageUrl"), "hero background image", optional=True
    )
    screenshots, movies, media_warnings = _presentation_media(
        data.get("carousel"), location.locale
    )
    platforms = _platforms(data.get("requirements"))
    raw_languages, languages = _languages(data.get("requirements"))
    release_date, release_year, release_warnings = _release_date(data.get("meta"))

    presentation = {
        "source": Source.EPIC.value,
        "stableSourceId": owned_copy.stable_source_id,
        "namespace": namespace,
        "catalogId": catalog_id,
        "catalogIdCorroboratedByCms": catalog_corroborated,
        "slug": location.slug,
        "offerId": offer_id,
        "title": page_title,
        "shortDescription": short_description,
        "about": about_text,
        "headerImage": header_image,
        "screenshots": screenshots,
        "movies": movies,
        "developer": developer,
        "publisher": publisher,
        "releaseDate": release_date,
        "releaseYear": release_year,
        "platforms": platforms,
        "languages": languages,
        "rawLanguages": raw_languages,
        "genres": [],
        "tags": [],
        "features": [],
        "storeUrl": location.store_url,
    }
    evidence = {
        "kind": "EPIC_CMS_IDENTITY_VALIDATED",
        "namespaceMatched": True,
        "titleMatched": True,
        "offerNamespaceMatched": True,
        "catalogIdCorroboratedByCms": catalog_corroborated,
        "slugSource": "DERIVED" if location.derived else "EXPLICIT",
    }
    return presentation, evidence, [*media_warnings, *release_warnings]


def _mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EpicCmsValidationError(f"Epic CMS {label} must be an object")
    return value


def _optional_mapping(value: Any, label: str) -> dict[str, Any]:
    if value is None:
        return {}
    return _mapping(value, label)


def _required_text(value: Any, label: str, max_chars: int) -> str:
    text = _optional_text(value, label, max_chars)
    if text is None:
        raise EpicCmsValidationError(f"Epic CMS {label} must be nonblank")
    return text


def _optional_text(value: Any, label: str, max_chars: int) -> str | None:
    if value is None or value == "":
        return None
    if not isinstance(value, str):
        raise EpicCmsValidationError(f"Epic CMS {label} must be a string")
    text = value.strip()
    if not text:
        return None
    if len(text) > max_chars:
        raise EpicCmsValidationError(f"Epic CMS {label} exceeds its size limit")
    return text


def _validate_optional_locale(value: Any, expected: str) -> None:
    if value is not None and value != expected:
        raise EpicCmsValidationError("Epic CMS locale does not match the request")


def _media_url(value: Any, label: str, *, optional: bool = False) -> str | None:
    if value is None or value == "":
        if optional:
            return None
        raise EpicCmsValidationError(f"Epic CMS {label} media URL is missing")
    if not isinstance(value, str) or len(value) > MAX_MEDIA_URL_CHARS:
        raise EpicCmsValidationError(f"Epic CMS {label} media URL is invalid")
    parsed = urlsplit(value)
    host = parsed.hostname or ""
    allowed_host = any(host == root or host.endswith(f".{root}") for root in _MEDIA_ROOTS)
    try:
        port = parsed.port
    except ValueError as error:
        raise EpicCmsValidationError(f"Epic CMS {label} media URL is invalid") from error
    if (
        parsed.scheme != "https"
        or not allowed_host
        or parsed.username is not None
        or parsed.password is not None
        or port not in (None, 443)
        or not parsed.path.startswith("/")
        or parsed.fragment
    ):
        raise EpicCmsValidationError(
            f"Epic CMS {label} media URL is not validated Epic/Unreal HTTPS media"
        )
    return value


def _presentation_media(
    carousel_value: Any, locale: str
) -> tuple[list[str], list[dict[str, str]], list[str]]:
    carousel = _optional_mapping(carousel_value, "carousel")
    items = carousel.get("items", [])
    if not isinstance(items, list):
        raise EpicCmsValidationError("Epic CMS carousel items must be a list")
    if len(items) > MAX_CAROUSEL_ITEMS:
        raise EpicCmsValidationError("Epic CMS carousel exceeds its item bound")

    screenshots: list[str] = []
    movies: list[dict[str, str]] = []
    for index, raw_item in enumerate(items):
        item = _mapping(raw_item, f"carousel item {index}")
        image = _optional_mapping(item.get("image"), f"carousel item {index} image")
        image_url = _media_url(
            image.get("src"), f"carousel item {index} image", optional=True
        )
        if image_url and image_url not in screenshots and len(screenshots) < MAX_SCREENSHOTS:
            screenshots.append(image_url)

        video = _optional_mapping(item.get("video"), f"carousel item {index} video")
        recipes = video.get("recipes")
        if recipes in (None, ""):
            continue
        movie = _movie_from_recipes(recipes, locale, index)
        if movie and movie not in movies and len(movies) < MAX_MOVIES:
            movies.append(movie)

    warnings: list[str] = []
    screenshot_total = sum(
        1
        for raw_item in items
        if isinstance(raw_item, dict)
        and isinstance(raw_item.get("image"), dict)
        and raw_item["image"].get("src")
    )
    movie_total = sum(
        1
        for raw_item in items
        if isinstance(raw_item, dict)
        and isinstance(raw_item.get("video"), dict)
        and raw_item["video"].get("recipes")
    )
    if screenshot_total > MAX_SCREENSHOTS:
        warnings.append(f"Epic screenshots truncated to {MAX_SCREENSHOTS} items.")
    if movie_total > MAX_MOVIES:
        warnings.append(f"Epic movies truncated to {MAX_MOVIES} items.")
    return screenshots, movies, warnings


def _movie_from_recipes(value: Any, locale: str, item_index: int) -> dict[str, str] | None:
    if not isinstance(value, str) or len(value) > MAX_RECIPE_CHARS:
        raise EpicCmsValidationError(
            f"Epic CMS carousel item {item_index} recipes are invalid"
        )
    try:
        payload = json.loads(value)
    except json.JSONDecodeError as error:
        raise EpicCmsValidationError(
            f"Epic CMS carousel item {item_index} recipes contain malformed JSON"
        ) from error
    if not isinstance(payload, dict):
        raise EpicCmsValidationError("Epic CMS movie recipes must be an object")

    locale_keys = ([locale] if locale in payload else []) + sorted(
        key for key in payload if key != locale
    )
    movie: dict[str, str] = {}
    for locale_key in locale_keys:
        recipes = payload[locale_key]
        if not isinstance(recipes, list) or len(recipes) > 50:
            raise EpicCmsValidationError("Epic CMS locale recipes must be a bounded list")
        for recipe in recipes:
            recipe = _mapping(recipe, "movie recipe")
            outputs = recipe.get("outputs", [])
            if not isinstance(outputs, list) or len(outputs) > 50:
                raise EpicCmsValidationError("Epic CMS movie outputs must be a bounded list")
            for output in outputs:
                output = _mapping(output, "movie output")
                key = output.get("key")
                content_type = output.get("contentType")
                if not isinstance(key, str) or not isinstance(content_type, str):
                    continue
                target: str | None = None
                if key == "manifest" and content_type.casefold() == "application/x-mpegurl":
                    target = "hls"
                elif key == "manifest" and content_type.casefold() == "application/dash+xml":
                    target = "dash"
                elif key == "thumbnail" and content_type.casefold().startswith("image/"):
                    target = "poster"
                if target and target not in movie:
                    movie[target] = str(
                        _media_url(output.get("url"), f"movie {target}")
                    )
    return movie if "hls" in movie or "dash" in movie else None


def _platforms(requirements_value: Any) -> dict[str, bool]:
    requirements = _optional_mapping(requirements_value, "requirements")
    systems = requirements.get("systems", [])
    if not isinstance(systems, list):
        raise EpicCmsValidationError("Epic CMS requirements systems must be a list")
    result = {"windows": False, "mac": False, "linux": False}
    aliases = {
        "windows": "windows",
        "mac": "mac",
        "macos": "mac",
        "mac os": "mac",
        "linux": "linux",
    }
    for system in systems[:20]:
        system = _mapping(system, "requirements system")
        system_type = system.get("systemType")
        if isinstance(system_type, str):
            key = aliases.get(system_type.strip().casefold())
            if key:
                result[key] = True
    if len(systems) > 20:
        raise EpicCmsValidationError("Epic CMS requirements systems exceed their bound")
    return result


def _languages(requirements_value: Any) -> tuple[list[str], list[dict[str, Any]]]:
    requirements = _optional_mapping(requirements_value, "requirements")
    raw = requirements.get("languages", [])
    if not isinstance(raw, list) or len(raw) > MAX_LANGUAGES:
        raise EpicCmsValidationError("Epic CMS languages must be a bounded list")
    raw_languages: list[str] = []
    parsed: dict[str, dict[str, Any]] = {}
    for value in raw:
        text = _required_text(value, "language entry", 10_000)
        raw_languages.append(text)
        for section in text.split("|"):
            label, separator, values = section.partition(":")
            if not separator:
                continue
            kind = label.strip().casefold()
            if kind not in {"audio", "text"}:
                continue
            for name_value in values.split(","):
                name = name_value.strip()
                if not name:
                    continue
                normalized = name.casefold()
                entry = parsed.setdefault(
                    normalized, {"name": name, "audio": False, "text": False}
                )
                entry[kind] = True
                if len(parsed) > MAX_LANGUAGES:
                    raise EpicCmsValidationError(
                        "Epic CMS parsed languages exceed their bound"
                    )
    return raw_languages, list(parsed.values())


def _release_date(meta_value: Any) -> tuple[str | None, int | None, list[str]]:
    meta = _optional_mapping(meta_value, "meta")
    raw = meta.get("customReleaseDate")
    if raw in (None, ""):
        return None, None, []
    if not isinstance(raw, str) or len(raw) > 128:
        raise EpicCmsValidationError("Epic CMS release label is invalid")
    label = raw.strip()
    if not label:
        return None, None, []
    formats = ("%Y-%m-%d", "%B %d, %Y", "%b %d, %Y", "%d %B %Y", "%d %b %Y")
    for date_format in formats:
        try:
            parsed = datetime.strptime(label, date_format).date()
        except ValueError:
            continue
        return parsed.isoformat(), parsed.year, []
    qualifier = "stale" if label.casefold() in _FAILURE_RELEASE_LABELS else "unreliable"
    return (
        None,
        None,
        [f"Ignored {qualifier} Epic release label: {label}"],
    )
