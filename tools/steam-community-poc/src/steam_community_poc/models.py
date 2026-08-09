"""Typed failures and safe diagnostic helpers."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


class PocError(Exception):
    """Expected POC failure with a stable machine-readable code."""

    def __init__(self, code: str, message: str, *, context: dict[str, Any] | None = None):
        super().__init__(message)
        self.code = code
        self.context = context or {}


class ParseError(PocError):
    def __init__(self, code: str, message: str, *, context: dict[str, Any] | None = None):
        super().__init__(code, message, context=context)


class ValidationError(PocError):
    pass


class NetworkError(PocError):
    pass


@dataclass(frozen=True)
class Diagnostic:
    type: str
    severity: str
    code: str
    message: str
    context: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {
            "type": self.type,
            "severity": self.severity,
            "code": self.code,
            "message": self.message,
            "context": self.context,
        }
