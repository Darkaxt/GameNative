import pytest

from steam_resolver.models import OwnedCopy, Source
from steam_resolver.source_ids import (
    decode_epic_stable_id,
    encode_epic_stable_id,
    validate_source_id,
)


def test_validates_real_source_id_forms():
    validate_source_id(Source.GOG, "1771589310")
    validate_source_id(
        Source.EPIC,
        "YjY3MWZiYzdiZTQyNGU4ODhjOTM0NmE5YTZkM2Q5ZGI."
        "MzhjMDdhMDlkYzE3NGI2OWI3NTZhYTUxODkwYzNkZDQ",
    )
    validate_source_id(
        Source.AMAZON,
        "amzn1.adg.product.5d35cae7-39d1-4e53-ba92-36004c4a5211",
    )


@pytest.mark.parametrize(
    ("source", "value"),
    [
        (Source.GOG, "001771589310"),
        (Source.GOG, "gog-1771589310"),
        (Source.GOG, "0"),
        (Source.EPIC, "namespace.catalogId"),
        (Source.EPIC, "YQ==.Yg"),
        (Source.EPIC, "YQ.Yg.extra"),
        (Source.AMAZON, "5d35cae7-39d1-4e53-ba92-36004c4a5211"),
        (Source.AMAZON, "amzn1.adg.product.5D35CAE7-39D1-4E53-BA92-36004C4A5211"),
        (Source.AMAZON, "amzn1.adg.product.not-a-uuid"),
    ],
)
def test_rejects_malformed_or_noncanonical_source_ids(source, value):
    with pytest.raises(ValueError):
        validate_source_id(source, value)


def test_epic_encoding_round_trips_only_canonical_unpadded_base64url():
    namespace = "商品/namespace"
    catalog_id = "catalog:id.ß"
    encoded = encode_epic_stable_id(namespace, catalog_id)

    assert "=" not in encoded
    assert decode_epic_stable_id(encoded) == (namespace, catalog_id)
    assert encode_epic_stable_id(*decode_epic_stable_id(encoded)) == encoded


def test_owned_copy_requires_projection_fields_and_defaults_optional_type():
    copy = OwnedCopy.from_dict(
        {"source": "GOG", "stableSourceId": "1771589310", "displayName": "Disco Elysium"}
    )

    assert copy.source is Source.GOG
    assert copy.developer is None
    assert copy.release_year is None
    assert copy.app_type.value == "UNKNOWN"


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"source": "STEAM", "stableSourceId": "1", "displayName": "x"},
        {"source": "GOG", "stableSourceId": "1", "displayName": "   "},
        {
            "source": "GOG",
            "stableSourceId": "1",
            "displayName": "x",
            "releaseYear": 1800,
        },
        {
            "source": "GOG",
            "stableSourceId": "1",
            "displayName": "x",
            "appType": "DLC",
        },
    ],
)
def test_owned_copy_rejects_invalid_input(payload):
    with pytest.raises((KeyError, TypeError, ValueError)):
        OwnedCopy.from_dict(payload)
