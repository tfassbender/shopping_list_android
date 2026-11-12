# Shopping List – Android app

Native Android client of the shopping list server (GitHub `tfassbender/shopping_list_server`; local
checkout path in `CLAUDE.local.md`). The server defines features and API; this app is just another
client – no business rule may exist only here.

**Read first:** `doc/implementation_plan.md` (phases, status, decisions), `doc/tech_stack.md` (how),
`doc/server_api.md` (REST contract). Update the plan's checkboxes as work completes.

Sibling reference: `../homeautomation` (same family: setup, manual DI, `TokenCipher`, generated
API client). Look there before inventing something new.

## How we work

- The UI is **not planned yet** – it follows the web app once its UI improvements are done. Don't build
  feature UI until the user asks; `MainActivity` is a placeholder.
- Testing: JVM unit tests for logic, store, sync engine and API layer (MockWebServer); integration tests
  against a local server (`IntegrationTest*`, skipped when no server runs).
- Commit per phase; the user decides about pushing unless they said otherwise.

## Commands

```sh
./gradlew assembleDebug        # build (also generates the API client)
./gradlew testDebugUnitTest    # unit tests (+ integration tests if a server runs on localhost:4723)
./gradlew lintDebug
./gradlew installDebug         # install the debug app (…shoppinglist.debug) on emulator / device
./gradlew connectedDebugAndroidTest  # device tests (emulator; uses the local server via 10.0.2.2 if running)
```
Local server: `./gradlew :backend:quarkusDev` in the server repo (port 4723, email verification off).
The emulator reaches it at `http://10.0.2.2:4723/` (debug `API_BASE_URL`); on a real device run
`adb reverse tcp:4723 tcp:4723` and use localhost.
Updating the API spec: `update-openapi` skill (`.claude/skills/update-openapi/`) – it also patches gaps of
the Quarkus spec (see the script).

## Architecture

Kotlin, Compose (later), single activity, minSdk 31, targetSdk 36, Java 11. Manual DI via `AppContainer`
(created in `ShoppingListApp`).

- `api.*` – **generated** from `doc/openapi.json` into `app/build/generated/openapi`. Never edit, never
  hand-write API models.
- `domain/` – pure Kotlin (no Android, no Retrofit): models, `SyncOp`, `applyOp`/`applyPull`, `ItemSorter`,
  `normalizeName`, limits. Ports of server/web logic with mirrored tests.
- `data/remote/` – `ApiClientFactory` (OkHttp, `AuthInterceptor`), `apiCall { }` → `ApiResult`/`ApiError`
  (with the server's stable error `code`), DTO → domain mapping.
- `data/local/` – `TokenCipher` + `SessionStore` (encrypted token in DataStore), JSON file store
  (`filesDir/store/`, atomic writes).
- `data/sync/` – `SyncEngine` (port of the web `SyncEngine`), `SyncWorker`, connectivity, live updates (SSE).
- `data/repo/` – repositories: the only API the UI uses (Flows + suspend functions; online-only actions
  return `ApiResult`, offline list changes return `Result` with `InvalidInputException` for invalid input).
- `AppContainer` wires everything; `AppSync` decides when to sync (foreground, login, network, interval,
  live updates); `SyncWorker` sends queued changes in the background.

## Where things are

- Sync engine: `data/sync/SyncEngine.kt` (port of the server repo's `frontend/src/offline/syncEngine.ts`;
  tests `SyncEngineTest` port `syncEngine.test.ts`). Optimistic apply / pull merge: `domain/ApplyOp.kt`.
- Sorting: `domain/ItemSorter.kt` – `ItemSorterTest` mirrors the server's test; keep both in sync.
- Spec download: `.claude/skills/update-openapi/fetch_openapi.py` (drops the SSE path; no patches needed since server issue #4).
- Integration tests: `app/src/test/.../integration/IntegrationTest.kt` (local server, test users delete
  themselves).
- Tests: `runTest` skips virtual time while waiting on real file I/O – engines in tests use
  `autoSync = false`; timing tests use a real dispatcher. JVM tests use `InMemoryDataStore`.

## Security

- Session token: AES-GCM encrypted with an Android Keystore key (`TokenCipher`), never logged or displayed.
- HTTPS only, except plain HTTP to the local dev server in debug builds (`src/debug` network security
  config: `10.0.2.2`, `localhost`). `AuthInterceptor` refuses to send the token to any other host than the
  API's or over plain HTTP to a non-local host; the client doesn't follow redirects. Keep it that way.
- Logging: BASIC in debug only, never headers.
- Nothing is backed up (`data_extraction_rules.xml`).
