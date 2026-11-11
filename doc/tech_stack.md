# Tech Stack

Native Android client for [shopping_list_server](https://github.com/tfassbender/shopping_list_server).
The server defines the features (`doc/features.md` there) and the API (`doc/server_api.md` here); this
app is "just another client" – no business rule may exist only in the app.

Setup and conventions follow the sibling project `../homeautomation` (same family: Compose, manual DI,
generated API client, encrypted token). Look there before inventing something new – but reimplement,
don't copy wholesale (exception: `TokenCipher`, see below).

## Overview

| Concern          | Choice |
|------------------|--------|
| Language         | Kotlin, Java 11 source/target (as homeautomation) |
| Package / app id | `com.tfassbender.shoppinglist` (debug build: suffix `.debug`, installable next to the release) |
| SDK              | compileSdk / targetSdk 36 (Android 16), **minSdk 31 (Android 12)** – as homeautomation |
| UI (later)       | Jetpack Compose + Material 3, single activity |
| Build            | Gradle Kotlin DSL, version catalog `gradle/libs.versions.toml` |
| API client       | **Generated** with openapi-generator (`kotlin` / `jvm-retrofit2` / `kotlinx_serialization`) from `doc/openapi.json` (server's `/q/openapi`) – as homeautomation |
| HTTP             | Retrofit + OkHttp |
| Live updates     | Foreground only: OkHttp SSE (`okhttp-sse`); no background updates (no Firebase) |
| Local storage    | **JSON files** (kotlinx.serialization), one file per space, atomic writes |
| Preferences      | DataStore (encrypted session token, server URL, last used layout per list) |
| Background work  | WorkManager (sync of queued changes when the network returns) |
| Concurrency      | Coroutines + Flow |
| DI               | Manual constructor injection via one `AppContainer` (created in the `Application` class) |
| Testing          | JUnit 4 (as the sibling projects), MockWebServer, coroutines-test; optional integration tests against a local server |

**Why minSdk 31:** API 26 would be Android 8.0 (2017). Android 12 (2021) is on the vast majority of
devices in use, matches homeautomation, and gives a modern baseline (Keystore AES-GCM, splash screen API,
current background-work rules) without compatibility code.

## Architecture

One Gradle module (`app`) with packages; split only if build times demand it.

```
com.tfassbender.shoppinglist
├── domain/      pure Kotlin, no Android imports: models, change model (SyncOp), applyOp/applyPull,
│                ItemSorter port, name normalization, input limits
├── api/         generated (build/generated/openapi) – never edit, never hand-write API models
├── data/
│   ├── remote/  ApiClientFactory (OkHttp, AuthInterceptor), apiCall { } / ApiResult / ApiError, SSE client
│   ├── local/   LocalStore + JsonFile (JSON files), SessionStore + TokenCipher, UiPreferences (DataStore)
│   ├── sync/    SyncEngine, SyncWorker, connectivity monitor, live updates (SSE)
│   └── repo/    repositories – the only API the UI layer uses (Flows + suspend functions)
├── AppContainer.kt
└── ui/          later (Compose screens + ViewModels)
```

Rules:
- `domain` has no Android / Retrofit imports → fast plain-JVM tests.
- Generated API models stay at the edge (`data`); the UI works with domain models.
- **The local store is the single source of truth for cached data.** Repositories expose `Flow`s
  backed by the in-memory state of the stores; network answers are written to the store, never handed to
  the UI directly.
- **Online-only operations** (space management, invites, members, API tokens, account) call the API and
  update the cache with the answer; offline they fail fast with a clear "needs a connection" error.
- Error handling as in homeautomation: every call wrapped by `apiCall { }` → `ApiResult` / `ApiError`;
  `ApiError` carries the server's stable `code`.

## Local storage: JSON files

Decided: **files, no Room.** The web client keeps one blob per space in IndexedDB; one JSON file per space
is a 1:1 port, needs no entities/DAOs/migrations, and the data is small (≤ 1000 items per list). Same
approach as the server.

```
filesDir/store/
├── me.json                 user incl. settings (cache of GET /api/users/me)
├── spaces.json             my spaces (cache of GET /api/spaces)
└── spaces/<spaceId>.json   SpaceState: seq, confirmed lists+items, acked + queued changes, merge
                            warnings, catalog, layouts, my favorites per layout
```

- Loaded into memory on first use, exposed as `Flow`; every change: write the whole file atomically
  (temp file + atomic move, plain `java.nio`, so the store is testable on the JVM) under a `Mutex` per file.
- `schemaVersion` in every file; new fields get defaults (`ignoreUnknownKeys`, default values), so most
  format changes need no migration. Unreadable file → drop it and resync from the server (the queue
  would be lost, so format changes must stay compatible).
- Logout deletes the `store/` directory.
- Not backed up (`dataExtractionRules` exclude) – the server has the data, and a restored token couldn't
  be decrypted anyway.

## Offline sync (port of the web `SyncEngine`)

Mirrors `frontend/src/offline/syncEngine.ts` of the server repo:

- Per space: `seq` (last pull), **confirmed** server state, **acked** changes (accepted by the server,
  not yet visible in a pull), **queued** changes (not sent), merge warnings.
- The UI shows confirmed state with acked + queued changes applied (`applyOp`), so every tap is instant.
- Sync run per space (one at a time, `Mutex`): push queued changes with `baseSeq = seq` → handle results
  (APPLIED/DUPLICATE → acked, REJECTED → warning) → pull `since = baseSeq` → merge (`applyPull`) → new
  `seq`, clear acked.
- Triggers: change enqueued (debounced ~300 ms), app start / foreground, network regained, live update
  (SSE), periodic fallback while in the foreground. WorkManager with a network constraint makes sure
  queued changes go **out** even if the app is closed (push only – nothing is pulled in the background).
- `404` on a space → the space is gone or the user was removed: drop its local data.

## Live updates

- **Foreground:** one SSE connection (`GET /api/events`), reconnect with backoff 1 s → 30 s, full sync
  after each (re)connect, sync a space on `SPACE_CHANGED`.
- **Background: nothing.** Decided: no push (no Firebase). The app pulls when it is opened / comes to the
  foreground, which is enough for a shopping list.

## Session & security

- **Token encrypted** exactly as in homeautomation: `TokenCipher` (AES-256/GCM, key in the Android
  Keystore, stored as Base64(IV || ciphertext)) + `SessionStore` (DataStore, `decryptOrNull` → treated as
  logged out). Copied from `../homeautomation/app/src/main/java/com/tfassbender/homeautomation/data/`,
  extended with `expiresAt` / `receivedAt` for the sliding session.
- Sliding session: refresh the JWT when it is older than one day (like the web app).
- `401` → clear session + local data, back to login.
- HTTPS for everything except the local dev server: release builds have no cleartext exception; debug
  builds allow cleartext only to `10.0.2.2` / `localhost` (network security config). `AuthInterceptor`
  refuses to send the token to any other non-HTTPS URL and the client doesn't follow redirects (as in
  homeautomation, covered by tests).
- Logging: BASIC in debug only, never headers.

## Server URL

The server is not deployed yet → configurable:
- `BuildConfig.API_BASE_URL` per build type (debug: `http://10.0.2.2:4720/`, release: production URL
  once known).
- No runtime override: on a real device use `adb reverse tcp:4720 tcp:4720` and localhost, so plain HTTP
  stays limited to fixed local hosts.

## Sharing code with the server

The server's sorter (`ItemSorter`) and change logic are pure Kotlin too. A shared library across two
repositories would be over-engineering for a handful of classes → **port them**, and mirror the server's
tests (as the web app did with `sortForLayout`).
