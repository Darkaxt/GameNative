#!/usr/bin/env bash

set -euo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$REPOSITORY_ROOT/.env"

if [[ ! -f "$ENV_FILE" ]]; then
    printf 'status=configuration_error\n'
    exit 2
fi

unset STEAM_WEB_API_KEY
if ! source "$ENV_FILE" >/dev/null 2>&1; then
    printf 'status=configuration_error\n'
    exit 2
fi
export -n STEAM_WEB_API_KEY 2>/dev/null || true

if [[ ! "${STEAM_WEB_API_KEY:-}" =~ ^[0-9A-Fa-f]{32}$ ]]; then
    unset STEAM_WEB_API_KEY
    printf 'status=configuration_error\n'
    exit 2
fi

if ! command -v curl >/dev/null 2>&1; then
    unset STEAM_WEB_API_KEY
    printf 'status=dependency_error\n'
    exit 3
fi
if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN="python"
else
    unset STEAM_WEB_API_KEY
    printf 'status=dependency_error\n'
    exit 3
fi

TEMP_DIR="$(mktemp -d)"
cleanup() {
    unset STEAM_WEB_API_KEY
    rm -rf -- "$TEMP_DIR" >/dev/null 2>&1 || true
}
trap cleanup EXIT

HEADER_FILE="$TEMP_DIR/headers"
BODY_FILE="$TEMP_DIR/response.json"
printf 'x-webapi-key: %s\n' "$STEAM_WEB_API_KEY" > "$HEADER_FILE"
unset STEAM_WEB_API_KEY

ENDPOINT="https://api.steampowered.com/IStoreService/GetAppList/v1/"
if ! HTTP_STATUS="$(
    curl \
        --silent \
        --output "$BODY_FILE" \
        --write-out '%{http_code}' \
        --header "@$HEADER_FILE" \
        --get \
        --data-urlencode 'max_results=1' \
        "$ENDPOINT" \
        2>/dev/null
)"; then
    printf 'status=request_error\n'
    exit 4
fi

if [[ ! "$HTTP_STATUS" =~ ^[0-9]{3}$ ]]; then
    printf 'status=request_error\n'
    exit 4
fi
if [[ ! "$HTTP_STATUS" =~ ^2[0-9]{2}$ ]]; then
    printf 'status=http_error http_status=%s\n' "$HTTP_STATUS"
    exit 5
fi

if ! AGGREGATES="$(
    "$PYTHON_BIN" - "$BODY_FILE" 2>/dev/null <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as response_file:
    payload = json.load(response_file)
response = payload.get("response")
if not isinstance(response, dict):
    raise ValueError("missing response")
apps = response.get("apps")
if not isinstance(apps, list):
    raise ValueError("missing apps")
have_more_results = response.get("have_more_results")
if not isinstance(have_more_results, bool):
    raise ValueError("missing pagination status")
print(
    f"app_count={len(apps)} "
    f"have_more_results={str(have_more_results).lower()}"
)
PY
)"; then
    printf 'status=invalid_response http_status=%s\n' "$HTTP_STATUS"
    exit 6
fi

printf 'status=ok http_status=%s %s\n' "$HTTP_STATUS" "$AGGREGATES"
