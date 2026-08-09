import copy

import pytest
from jsonschema import ValidationError as JsonSchemaValidationError

from steam_community_poc.schema import validate_result


def minimal_result() -> dict:
    pagination = {
        "requestedPages": 1,
        "fetchedPages": 0,
        "requestedUrls": [],
        "uniqueRequestCount": 0,
        "uniqueItemCount": 0,
        "duplicateItemCount": 0,
        "identityKinds": [],
        "continuationAvailable": False,
    }
    return {
        "schemaVersion": 1,
        "target": {"input": "42", "appId": 42, "title": "Game", "resolution": "app_id"},
        "request": {
            "reviewPages": 1,
            "discussionPages": 1,
            "threadPages": 1,
            "sampleThreads": 1,
        },
        "reviews": {
            "sectionState": {"kind": "Empty"},
            "items": [],
            "pagination": copy.deepcopy(pagination),
        },
        "discussions": {
            "sectionState": {"kind": "Empty"},
            "items": [],
            "pagination": copy.deepcopy(pagination),
            "sampledThreads": [],
        },
        "diagnostics": [],
    }


def test_bundled_schema_accepts_section_state_empty_result() -> None:
    validate_result(minimal_result())


def test_bundled_schema_rejects_unmapped_review_field_types() -> None:
    result = minimal_result()
    result["reviews"] = {
        "sectionState": {
            "kind": "Content",
            "canLoadMore": False,
            "loadingMore": False,
            "refreshFailed": False,
        },
        "items": [
            {
                "recommended": "yes",
                "text": "text",
                "playtimeMinutes": None,
                "helpfulVotes": 0,
                "funnyVotes": 0,
                "commentCount": 0,
                "postedAtEpochSeconds": 0,
                "updatedAtEpochSeconds": 0,
                "receivedForFree": False,
                "earlyAccess": False,
                "developerResponse": None,
            }
        ],
        "pagination": result["reviews"]["pagination"],
    }

    with pytest.raises(JsonSchemaValidationError):
        validate_result(result)


def test_bundled_schema_rejects_rich_html_side_channel() -> None:
    result = minimal_result()
    result["discussions"]["items"] = [
        {
            "title": "Topic",
            "replyCount": None,
            "activityLabel": None,
            "route": "/app/42/discussions/0/100/",
            "viewCount": None,
            "html": "<script>bad()</script>",
        }
    ]

    with pytest.raises(JsonSchemaValidationError):
        validate_result(result)
