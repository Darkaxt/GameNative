"""Bundled JSON Schema access and validation."""

from __future__ import annotations

import json
from importlib.resources import files
from typing import Any

from jsonschema import Draft202012Validator


def load_schema() -> dict[str, Any]:
    resource = files("steam_community_poc").joinpath("result.schema.json")
    return json.loads(resource.read_text(encoding="utf-8"))


_VALIDATOR = Draft202012Validator(load_schema())


def validate_result(result: dict[str, Any]) -> None:
    _VALIDATOR.validate(result)
