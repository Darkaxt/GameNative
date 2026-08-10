"""Command-line interface for the standalone Steam community POC."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Callable, Sequence

from .bounds import MAX_PAGES_PER_KIND, MAX_SAMPLED_THREADS
from .collector import CollectorConfig, SteamCommunityCollector
from .http import BoundedHttpClient
from .models import NetworkError, ParseError, PocError, RateLimitError, ValidationError
from .routes import parse_positive_app_id
from .schema import validate_result


def _bounded_integer(name: str, maximum: int) -> Callable[[str], int]:
    def parse(value: str) -> int:
        try:
            number = int(value)
        except ValueError as error:
            raise argparse.ArgumentTypeError(f"{name} must be an integer") from error
        if not 1 <= number <= maximum:
            raise argparse.ArgumentTypeError(f"{name} must be between 1 and {maximum}")
        return number

    return parse


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="steam-community-poc",
        description="Collect bounded public Steam reviews and discussions as GameNative-shaped JSON.",
    )
    parser.add_argument("target", help="Exact Steam title or positive numeric AppID")
    parser.add_argument(
        "--review-pages",
        type=_bounded_integer("review pages", MAX_PAGES_PER_KIND),
        default=3,
        help=f"review pages to request (1-{MAX_PAGES_PER_KIND}; default: 3)",
    )
    parser.add_argument(
        "--discussion-pages",
        type=_bounded_integer("discussion pages", MAX_PAGES_PER_KIND),
        default=3,
        help=f"discussion listing pages to request (1-{MAX_PAGES_PER_KIND}; default: 3)",
    )
    parser.add_argument(
        "--thread-pages",
        type=_bounded_integer("thread pages", MAX_PAGES_PER_KIND),
        default=3,
        help=f"pages per sampled thread (1-{MAX_PAGES_PER_KIND}; default: 3)",
    )
    parser.add_argument(
        "--sample-threads",
        type=_bounded_integer("sample threads", MAX_SAMPLED_THREADS),
        default=1,
        help=f"listing threads to sample (1-{MAX_SAMPLED_THREADS}; default: 1)",
    )
    parser.add_argument("--output", type=Path, help="write full JSON result to this path")
    parser.add_argument("--pretty", action="store_true", help="indent JSON output")
    return parser


def _default_collector() -> SteamCommunityCollector:
    return SteamCommunityCollector(BoundedHttpClient())


def _error_type(error: PocError) -> str:
    if isinstance(error, ValidationError):
        return "validation"
    if isinstance(error, ParseError):
        return "parser"
    if isinstance(error, RateLimitError):
        return "rate_limit"
    if isinstance(error, NetworkError):
        return "http"
    return "validation"


def run(
    argv: Sequence[str] | None = None,
    *,
    collector_factory: Callable[[], Any] = _default_collector,
) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.target.isascii() and args.target.isdecimal():
            parse_positive_app_id(args.target)
        config = CollectorConfig(
            review_pages=args.review_pages,
            discussion_pages=args.discussion_pages,
            thread_pages=args.thread_pages,
            sample_threads=args.sample_threads,
        )
        result = collector_factory().collect(args.target, config)
        validate_result(result)
        serialized = json.dumps(
            result,
            ensure_ascii=False,
            indent=2 if args.pretty else None,
            separators=None if args.pretty else (",", ":"),
        )
        if args.output is not None:
            args.output.write_text(serialized + "\n", encoding="utf-8")
        else:
            sys.stdout.write(serialized + "\n")
        return 0
    except PocError as error:
        payload = {
            "error": {
                "type": _error_type(error),
                "severity": "error",
                "code": error.code,
                "message": str(error),
                "context": error.context,
            }
        }
        sys.stderr.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n")
        return 2 if isinstance(error, ValidationError) else 1
    except Exception as error:
        payload = {
            "error": {
                "type": "validation",
                "severity": "error",
                "code": "unexpected_failure",
                "message": "Steam community POC failed unexpectedly",
                "context": {"errorType": type(error).__name__},
            }
        }
        sys.stderr.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n")
        return 1


def main() -> int:
    return run()


if __name__ == "__main__":
    raise SystemExit(main())
