from __future__ import annotations

import argparse
import json
import os
import shlex
import sys
from pathlib import Path
from typing import Any, TextIO

from .corpus import (
    evaluate_corpus,
    load_corpus,
    validate_corpus_contract,
    validate_sources,
)
from .http import UrllibTransport
from .models import OwnedCopy
from .resolver import RESOLVER_VERSION, SCHEMA_VERSION, SteamResolver
from .steam import (
    CachedIndexProvider,
    FixtureProvider,
    SteamStoreProvider,
    refresh_istore_index,
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="steam-resolver")
    subparsers = parser.add_subparsers(dest="command", required=True)

    resolve = subparsers.add_parser("resolve", help="Resolve one owned-copy JSON object")
    resolve.add_argument("--input", default="-", help="JSON file or - for stdin")
    _add_provider_args(resolve)

    index = subparsers.add_parser("steam-index", help="Manage an optional Steam app index")
    index_subparsers = index.add_subparsers(dest="index_command", required=True)
    refresh = index_subparsers.add_parser("refresh", help="Refresh IStoreService app names")
    refresh.add_argument("--cache-dir", default=".cache")
    refresh.add_argument("--output")

    corpus = subparsers.add_parser("corpus", help="Validate or evaluate a real corpus")
    corpus_subparsers = corpus.add_subparsers(dest="corpus_command", required=True)
    sources = corpus_subparsers.add_parser(
        "validate-sources", help="Validate source identities and public evidence"
    )
    sources.add_argument("--file", required=True)
    source_mode = sources.add_mutually_exclusive_group(required=True)
    source_mode.add_argument("--offline", action="store_true")
    source_mode.add_argument("--live", action="store_true")
    sources.add_argument("--output")

    evaluate = corpus_subparsers.add_parser("evaluate", help="Resolve and score a corpus")
    evaluate.add_argument("--file", required=True)
    _add_provider_args(evaluate)
    evaluate.add_argument("--output")
    evaluate.add_argument("--require-recall-at5", type=int)
    evaluate.add_argument("--require-top1", type=int)
    evaluate.add_argument("--require-auto", type=int)
    return parser


def _add_provider_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--candidate-provider",
        choices=("storesearch", "cached-index", "fixture"),
        default="storesearch",
    )
    parser.add_argument("--fixture")
    parser.add_argument("--cache-dir", default=".cache")
    parser.add_argument("--index")
    parser.add_argument("--max-search-candidates", type=int, default=15)
    parser.add_argument("--max-output-candidates", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=10.0)


def main(
    argv: list[str] | None = None,
    *,
    stdin: TextIO | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
) -> int:
    args_list = list(sys.argv[1:] if argv is None else argv)
    input_stream = stdin or sys.stdin
    output_stream = stdout or sys.stdout
    error_stream = stderr or sys.stderr
    args = build_parser().parse_args(args_list)
    try:
        if args.command == "resolve":
            payload = _read_json(args.input, input_stream)
            owned_copy = OwnedCopy.from_dict(payload)
            result = SteamResolver(
                _provider_from_args(args), max_candidates=args.max_output_candidates
            ).resolve(owned_copy)
            _write_json(result, output_stream)
            return 0
        if args.command == "steam-index":
            key = os.environ.get("STEAM_WEB_API_KEY", "")
            target = Path(args.output) if args.output else Path(args.cache_dir) / "steam-index.json"
            result = refresh_istore_index(UrllibTransport(), target, api_key=key)
            _write_json(
                {"schemaVersion": SCHEMA_VERSION, "resolverVersion": RESOLVER_VERSION, **result},
                output_stream,
            )
            return 0
        if args.command == "corpus" and args.corpus_command == "validate-sources":
            cases = load_corpus(args.file)
            report = {
                "schemaVersion": SCHEMA_VERSION,
                "resolverVersion": RESOLVER_VERSION,
                "command": "steam-resolver " + shlex.join(args_list),
                "contract": validate_corpus_contract(cases),
                "sourceValidation": validate_sources(cases, live=args.live),
            }
            _write_report(report, args.output, output_stream)
            return 0 if report["contract"]["valid"] and report["sourceValidation"]["valid"] else 1
        if args.command == "corpus" and args.corpus_command == "evaluate":
            cases = load_corpus(args.file)
            provider = _provider_from_args(args)
            evaluation = evaluate_corpus(
                cases,
                SteamResolver(provider, max_candidates=args.max_output_candidates),
            )
            report = {
                "schemaVersion": SCHEMA_VERSION,
                "resolverVersion": RESOLVER_VERSION,
                "mode": "live" if args.candidate_provider == "storesearch" else "offline",
                "candidateProvider": provider.name,
                "command": "steam-resolver " + shlex.join(args_list),
                "contract": validate_corpus_contract(cases),
                **evaluation,
            }
            _write_report(report, args.output, output_stream)
            return _gate_exit_code(report, args)
        raise ValueError("unsupported command")
    except (KeyError, TypeError, ValueError, OSError, json.JSONDecodeError) as error:
        _write_json(
            {"error": "INVALID_INPUT", "message": f"{type(error).__name__}: {error}"},
            error_stream,
        )
        return 2
    except RuntimeError as error:
        _write_json(
            {"error": "PROVIDER_UNAVAILABLE", "message": str(error)}, error_stream
        )
        return 3


def _provider_from_args(args: argparse.Namespace) -> Any:
    if args.candidate_provider == "storesearch":
        return SteamStoreProvider(
            max_search_candidates=args.max_search_candidates, timeout=args.timeout
        )
    if args.candidate_provider == "fixture":
        if not args.fixture:
            raise ValueError("--fixture is required for fixture provider")
        return FixtureProvider(args.fixture)
    index_path = Path(args.index) if args.index else Path(args.cache_dir) / "steam-index.json"
    return CachedIndexProvider(
        index_path,
        max_search_candidates=args.max_search_candidates,
        timeout=args.timeout,
    )


def _read_json(path: str, stdin: TextIO) -> Any:
    if path == "-":
        return json.load(stdin)
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _json_text(payload: Any) -> str:
    return json.dumps(payload, ensure_ascii=True, indent=2, sort_keys=True) + "\n"


def _write_json(payload: Any, stream: TextIO) -> None:
    stream.write(_json_text(payload))


def _write_report(payload: Any, path: str | None, stdout: TextIO) -> None:
    text = _json_text(payload)
    if path:
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    stdout.write(text)


def _gate_exit_code(report: dict[str, Any], args: argparse.Namespace) -> int:
    if not report["contract"]["valid"]:
        return 1
    overall = report["overall"]
    gates = (
        (args.require_recall_at5, overall["recallAt5Count"]),
        (args.require_top1, overall["top1Count"]),
        (args.require_auto, overall["automaticCorrectCount"]),
    )
    return 1 if any(required is not None and actual < required for required, actual in gates) else 0
