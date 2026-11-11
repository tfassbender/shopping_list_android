---
name: update-openapi
description: Download the current OpenAPI spec of the shopping list server into doc/openapi.json and regenerate the API client. Use when the user says the server API changed, asks to update/refresh/re-download the API spec or openapi.json, or when generated API code doesn't match the server.
---

# Update the OpenAPI spec

The app's API client is generated from `doc/openapi.json` (see `app/build.gradle.kts`, openapi-generator
plugin). This skill refreshes that file from a running server.

## Steps

1. Make sure the server runs (local dev server: `./gradlew :backend:quarkusDev` in
   `D:\intelliJ_workspace\shopping_list_server`, port 4720). Then run from the repo root:
   ```sh
   python .claude/skills/update-openapi/fetch_openapi.py            # local dev server
   python .claude/skills/update-openapi/fetch_openapi.py https://…  # another server
   ```
   It downloads `/q/openapi?format=json`, **patches** the known gaps of the Quarkus spec (empty response
   schemas of endpoints returning a JAX-RS `Response`, missing `Created*Dto` schemas, optional query
   parameters marked required, the SSE endpoint) and writes `doc/openapi.json`.
   - A `NOTE: ... no longer needed?` means the server fixed a gap → remove that patch from the script.
   - Not reachable → the server isn't running; tell the user, don't retry in a loop.

2. Show what changed: `git diff --stat doc/openapi.json`, then summarize the API changes for the user
   (new/removed paths, changed schemas or fields), not the raw diff. If nothing changed, say so and stop.

3. Regenerate and compile: `./gradlew assembleDebug` (the client is generated as part of the build).
   Fix call sites in hand-written code that broke because of renamed/changed models or operations.
   Also check `doc/server_api.md` and update it.

4. Run unit tests: `./gradlew testDebugUnitTest`.

5. Do not commit automatically — report the result and let the user decide.
