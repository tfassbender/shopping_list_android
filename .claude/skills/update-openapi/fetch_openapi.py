"""Download the server's OpenAPI spec into doc/openapi.json (patched for the generator).

Fetches <BASE_URL>/q/openapi?format=json from a running shopping list server (default: the local dev
server, `./gradlew :backend:quarkusDev` in the server repo) and writes it pretty-printed, so git diffs
between API versions stay readable.

Only the SSE endpoint is dropped (consumed by an own OkHttp client). The server documents all response
schemas since issue #4; the script warns if an empty schema shows up again.

Usage (from the repo root):  python .claude/skills/update-openapi/fetch_openapi.py [BASE_URL]
"""

import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_BASE_URL = "http://localhost:4723"

REPO_ROOT = Path(__file__).resolve().parents[3]
OUTPUT_FILE = REPO_ROOT / "doc" / "openapi.json"

# Server-sent events can't be consumed through the generated Retrofit client (own OkHttp SSE client).
DROPPED_PATHS = ["/api/events"]


def check_gaps(spec: dict) -> list[str]:
    """The server documents all response schemas (issue #4). Warn if an empty schema comes back - the
    generator would turn it into `Any`, which can't be decoded."""
    notes = []
    for path, operations in spec["paths"].items():
        for method, operation in operations.items():
            for status, response in operation.get("responses", {}).items():
                content = response.get("content", {}).get("application/json")
                if content is not None and content.get("schema") == {}:
                    notes.append(f"empty response schema: {method.upper()} {path} {status} - fix it on the server")
    return notes


def patch(spec: dict) -> list[str]:
    notes = check_gaps(spec)
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
