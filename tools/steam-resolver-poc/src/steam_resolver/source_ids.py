from __future__ import annotations

import base64
import binascii
import re
import uuid

from .models import Source


_GOG_ID = re.compile(r"[1-9][0-9]*\Z")
_BASE64URL = re.compile(r"[A-Za-z0-9_-]+\Z")
_AMAZON_PREFIX = "amzn1.adg.product."
_AMAZON_ID = re.compile(
    r"amzn1\.adg\.product\."
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z"
)


def _encode_part(value: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError("Epic identity parts must be nonblank strings")
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii").rstrip("=")


def encode_epic_stable_id(namespace: str, catalog_id: str) -> str:
    return f"{_encode_part(namespace)}.{_encode_part(catalog_id)}"


def _decode_part(value: str) -> str:
    if not _BASE64URL.fullmatch(value) or "=" in value:
        raise ValueError("Epic stableSourceId parts must be unpadded base64url")
    try:
        decoded = base64.b64decode(
            value + "=" * (-len(value) % 4), altchars=b"-_", validate=True
        ).decode("utf-8")
    except (binascii.Error, UnicodeDecodeError) as error:
        raise ValueError("Epic stableSourceId contains invalid base64url UTF-8") from error
    if not decoded.strip():
        raise ValueError("Epic identity parts must decode to nonblank strings")
    return decoded


def decode_epic_stable_id(value: str) -> tuple[str, str]:
    if not isinstance(value, str):
        raise TypeError("Epic stableSourceId must be a string")
    parts = value.split(".")
    if len(parts) != 2:
        raise ValueError("Epic stableSourceId must contain two base64url parts")
    decoded = (_decode_part(parts[0]), _decode_part(parts[1]))
    if encode_epic_stable_id(*decoded) != value:
        raise ValueError("Epic stableSourceId is not canonical")
    return decoded


def validate_source_id(source: Source, value: str) -> None:
    if not isinstance(value, str):
        raise TypeError("stableSourceId must be a string")
    if source is Source.GOG:
        if not _GOG_ID.fullmatch(value):
            raise ValueError("GOG stableSourceId must be a positive canonical decimal product ID")
        return
    if source is Source.EPIC:
        decode_epic_stable_id(value)
        return
    if source is Source.AMAZON:
        if not _AMAZON_ID.fullmatch(value):
            raise ValueError("Amazon stableSourceId must be amzn1.adg.product.<lowercase UUID>")
        uuid_text = value.removeprefix(_AMAZON_PREFIX)
        try:
            parsed = uuid.UUID(uuid_text)
        except ValueError as error:
            raise ValueError("Amazon stableSourceId UUID is invalid") from error
        if str(parsed) != uuid_text:
            raise ValueError("Amazon stableSourceId UUID is not canonical")
        return
    raise ValueError(f"unsupported source: {source}")
