"""Download the server's OpenAPI spec into doc/openapi.json (patched for the generator).

Fetches <BASE_URL>/q/openapi?format=json from a running shopping list server (default: the local dev
server, `./gradlew :backend:quarkusDev` in the server repo) and writes it pretty-printed, so git diffs
between API versions stay readable.

The spec Quarkus produces has gaps that break openapi-generator; they are patched here (see PATCHES
below and the server issue about @APIResponse annotations). Remove a patch once the server fixes it -
the script warns when a patch is no longer needed.

Usage (from the repo root):  python .claude/skills/update-openapi/fetch_openapi.py [BASE_URL]
"""

import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_BASE_URL = "http://localhost:4720"

REPO_ROOT = Path(__file__).resolve().parents[3]
OUTPUT_FILE = REPO_ROOT / "doc" / "openapi.json"

# Endpoints that return a JAX-RS Response on the server: the spec has an empty schema ({}) for them,
# which the generator turns into `Any` (not decodable). Created resources get their real schema,
# everything else becomes 204 No Content.
CREATED_SCHEMAS = {
    "register": "SessionResponse",
    "createSpace": "SpaceDto",
    "createList": "ListDto",
    "addItem": "ListDto",
    "createInvite": "CreatedInviteDto",
    "addCategory": "CatalogDto",
    "createLayout": "LayoutDto",
    "createApiToken": "CreatedApiTokenDto",
}

# DTOs that only appear inside Response bodies and are therefore missing from the spec.
MISSING_SCHEMAS = {
    "CreatedInviteDto": {
        "type": "object",
        "required": ["invite", "token", "url"],
        "properties": {
            "invite": {"$ref": "#/components/schemas/InviteDto"},
            "token": {"type": "string"},
            "url": {"type": "string"},
        },
    },
    "CreatedApiTokenDto": {
        "type": "object",
        "required": ["token", "rawToken"],
        "properties": {
            "token": {"$ref": "#/components/schemas/ApiTokenDto"},
            "rawToken": {"type": "string", "description": "The raw token - shown only once."},
        },
    },
}

# Server-sent events can't be consumed through the generated Retrofit client (own OkHttp SSE client).
DROPPED_PATHS = ["/api/events"]


def is_empty_schema(response: dict) -> bool:
    content = response.get("content", {}).get("application/json")
    return content is not None and content.get("schema") == {}


def patch_responses(spec: dict) -> list[str]:
    notes = []
    for path, operations in spec["paths"].items():
        for method, operation in operations.items():
            op_id = operation.get("operationId")
            ok = operation.get("responses", {}).get("200")
            if ok is None or not is_empty_schema(ok):
                if op_id in CREATED_SCHEMAS:
                    notes.append(f"patch for {op_id} no longer needed?")
                continue
            del operation["responses"]["200"]
            if op_id in CREATED_SCHEMAS:
                ref = {"$ref": f"#/components/schemas/{CREATED_SCHEMAS[op_id]}"}
                operation["responses"]["201"] = {
                    "description": "Created",
                    "content": {"application/json": {"schema": ref}},
                }
            else:
                operation["responses"]["204"] = {"description": "No Content"}
    return notes


def patch_optional_query_params(spec: dict) -> None:
    """Nullable or defaulted query parameters are optional on the server, but marked required."""
    for operations in spec["paths"].values():
        for operation in operations.values():
            for param in operation.get("parameters", []):
                schema = param.get("schema", {})
                types = schema.get("type")
                nullable = isinstance(types, list) and "null" in types
                if param.get("in") == "query" and (nullable or "default" in schema):
                    param["required"] = False


def patch(spec: dict) -> list[str]:
    notes = patch_responses(spec)
    patch_optional_query_params(spec)
    schemas = spec["components"]["schemas"]
    for name, schema in MISSING_SCHEMAS.items():
        if name in schemas:
            notes.append(f"schema {name} now in the spec - patch no longer needed?")
        else:
            schemas[name] = schema
    for path in DROPPED_PATHS:
        spec["paths"].pop(path, None)
    return notes


def main() -> int:
    base_url = (sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BASE_URL).rstrip("/")
    request = urllib.request.Request(
        f"{base_url}/q/openapi?format=json",
        headers={"Accept": "application/json", "User-Agent": "shoppinglist-openapi-fetch/1.0"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            spec = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"ERROR: HTTP {e.code}")
        return 1
    except urllib.error.URLError as e:
        print(f"ERROR: server not reachable at {base_url}: {e.reason}")
        return 1

    if "openapi" not in spec or "paths" not in spec:
        print("ERROR: response does not look like an OpenAPI document")
        return 1

    for note in patch(spec):
        print(f"NOTE: {note}")
    OUTPUT_FILE.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    info = spec.get("info", {})
    print(f"Wrote {OUTPUT_FILE.relative_to(REPO_ROOT)}: OpenAPI {spec['openapi']}, "
          f"'{info.get('title')}' {info.get('version')}, {len(spec['paths'])} paths")
    return 0


if __name__ == "__main__":
    sys.exit(main())
