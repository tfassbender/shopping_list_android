# Implementation Plan

Phased roadmap for the Android app of [shopping_list_server](https://github.com/tfassbender/shopping_list_server).
Based on the server's `doc/features.md` (what), `doc/tech_stack.md` here (how) and `doc/server_api.md`
here (the REST contract). Update the checkboxes and the status as work completes.

**Status:** Phases 0–9 done – the app has its UI (Phase 9, following the finished web app). Open: release
signing, manual offline checklist on a device, App Links.

## Scope of this plan

**Only the non-UI part of the app ("app backend")**: project setup, API client, session, local storage,
offline sync, live updates and the client-side domain logic (sorting, optimistic changes). Every phase
ends with tested repositories that a UI can sit on.

The **UI is planned later**, once the web frontend's UI improvements are done – the app should follow the
final web design rather than a moving target.

## Guiding principles

- **The server is the contract.** No business rule only in the app; where the app needs logic offline
  (sorting, optimistic apply), it is a port of the server/web logic with **mirrored tests**.
- **The local store is the single source of truth** for cached data. Repositories expose `Flow`s;
  network answers are written to the store, never handed to the UI directly.
- **Offline-first for lists and items** (all mutations go through the sync queue, exactly like the web
  app); everything else is online with a read cache.
- **Tests:** pure Kotlin domain + sync engine with fakes (JUnit 4); API with `MockWebServer`; integration
  tests against a locally running server (skipped if none is reachable, like the server's `e2e` module).

## Overview

| Phase | Content                                   | Result |
|-------|-------------------------------------------|--------|
| 0     | Project setup                             | Empty app builds, tests run, server URL configurable |
| 1     | API client                                | Every endpoint callable, errors mapped to codes |
| 2     | Session & account                         | Login/register, encrypted token, sliding refresh, logout on 401 |
| 3     | Local storage & spaces                    | JSON file store, spaces/members/invites repositories |
| 4     | Client domain logic                       | Change model, optimistic apply, pull merge, sorter |
| 5     | Offline sync engine                       | **Lists & items work offline** |
| 6     | Catalog, layouts, history, suggestions    | Categories, units, shop sorting, autocomplete |
| 7     | Live updates (SSE)                        | Shared lists update without refresh while the app is open |
| 8     | Integration hardening                     | Tested end-to-end against a local server |
| 9     | UI (Compose)                              | All pages of the web app, same look and texts |
| later | App links, release                        | |

---

## Phase 0 – Project setup

Goal: an empty Android project that builds and runs tests, ready to talk to a local server. Setup modelled
on `../homeautomation`.

- [x] Android Studio project: single `app` module, Kotlin DSL, version catalog, Compose enabled (only a
      placeholder activity for now)
- [x] Application id / namespace `com.tfassbender.shoppinglist`, debug suffix `.debug`;
      compileSdk/targetSdk 36, minSdk 31, Java 11
- [x] Versioning as homeautomation: `appVersion` → derived `versionCode`
- [x] Package skeleton `domain` / `data.remote` / `data.local` / `data.sync` / `data.repo`
- [x] `AppContainer` (manual DI) created in a custom `Application` class
- [x] `BuildConfig.API_BASE_URL` per build type (debug `http://10.0.2.2:4723/`)
- [x] Network security config: cleartext only to `10.0.2.2` / `localhost`, debug only
- [x] Backup: nothing is backed up or transferred
- [x] Test setup: JUnit 4, MockWebServer, coroutines-test
- [x] `.gitignore`, `.gitattributes`, `CLAUDE.md` with build/test commands
- [x] Release signing: key in `signing/`, **not** committed (public repo) – see `doc/releases.md`

Decided while implementing: no runtime server URL override – a real device reaches the local server via
`adb reverse tcp:4723 tcp:4723` and localhost, so the HTTP exception stays limited to fixed local hosts.
The release `API_BASE_URL` was a placeholder until the server was deployed; since 2026-11-12 it is
`https://shopping-list.tobias-fassbender.com/` (the default server, see Phase 9).
English default strings with a German translation (`values-de`).

## Phase 1 – API client

Goal: every endpoint of `doc/server_api.md` is callable from Kotlin.

- [x] `doc/openapi.json` from the server + `update-openapi` skill / script to refresh it
- [x] openapi-generator wired into the build (generated into `build/generated/openapi`, never edited)
- [x] Tricky spots of the generated code checked (see below)
- [x] `ApiClientFactory`: OkHttp with `AuthInterceptor` (token only to the API host; HTTPS or the local
      dev hosts), no redirects, timeouts, BASIC logging in debug only
- [x] `apiCall { }` → `ApiResult` / `ApiError` (`Server(status, code, message)`, `Network`,
      `InvalidResponse`)
- [x] Tests with `MockWebServer`: decoding, error mapping, auth header, no token to foreign hosts / plain
      HTTP, redirects, 401 handling

Decided while implementing:
- The Quarkus spec has gaps the generator can't handle: endpoints returning a JAX-RS `Response` have an
  empty schema (→ `Any`), `CreatedInviteDto` / `CreatedApiTokenDto` are missing, optional query parameters
  are marked required. The fetch script patched these; since server issue #4 was fixed (2026-11-12) it only
  drops the SSE path.
- `explicitNulls = false` in the generated serializer: the server leaves out null fields (e.g. `token`
  while email verification is pending) although the spec marks them required, and null request fields
  must be left out (`PATCH` = missing means unchanged; `""` clears).
- A 401 on a request that carried the token = session invalid (only `SESSION_EXPIRED` can occur there;
  `INVALID_CREDENTIALS` comes without a token).
- Generated functions order path parameters alphabetically – always call them with named arguments.

## Phase 2 – Session & account

Goal: a user can register, log in and stay logged in for weeks – token stored encrypted.

- [x] `TokenCipher` (from homeautomation, `java.util.Base64`) + `SessionStore` (token encrypted, expiry and
      receive time plain); undecryptable token → logged out
- [x] `AuthRepository`: config, register (handles `verificationRequired`), login, resend verification,
      forgot password, logout with logout hooks
- [x] Verification / reset links point to the web app – the app only triggers the emails
- [x] Sliding session: refresh when the token is older than one day (on foreground)
- [x] `401` from any call → clear session and all local data, emit "logged out" with the reason
- [x] `AccountRepository`: me (cached), settings, display name, change password and logout everywhere
      (store the new token), delete account
- [x] `ApiTokenRepository`: list, create, revoke – online only
- [x] Tests: refresh timing, expiry, 401 handling, token replacement after password change

Decided while implementing: JVM tests use an in-memory `DataStore` – the file-based one can't rename over
its open file on Windows test machines (works on Android).

## Phase 3 – Local storage & spaces

Goal: the JSON file store; spaces with members and invites.

- [x] `JsonFile`: atomic writes (temp file + move), `schemaVersion` envelope, lenient reading, unreadable
      file → default (resynced), mutex for load-modify-save, observable
- [x] `LocalStore`: `me.json`, `spaces.json`, `spaces/<spaceId>.json`; space ids validated; `clear()` on logout
- [x] `SpaceRepository`: spaces (cached), refresh, create, rename, delete, leave, members, remove member,
      transfer ownership, invites (list, create, revoke), preview + accept
- [x] `parseInviteToken`: token from a pasted link, chat text or raw token
- [x] Spaces that vanish (refresh, leave, delete, 404) are reported → their local data is dropped
- [x] Online-only operations fail with `ApiError.Network` when offline
- [x] Tests: store round trips (plain JVM), repository with MockWebServer

Decided while implementing: owner-only actions aren't blocked locally – the UI offers them only for
`Space.isOwner`, the server answers `403 OWNER_ONLY` anyway.

## Phase 4 – Client domain logic

Goal: the pure Kotlin logic the sync engine and the UI need – ported from the server/web with mirrored tests.

- [x] Domain models separate from API models (`@Serializable` for the store – pure Kotlin)
- [x] Change model `SyncOp` (sealed, serialized with the server's type names)
- [x] `applyOp` – the optimistic view (port of web `applyOp.ts`)
- [x] `applyPull` – merging a pull: tombstones, `fullResync`, vanished lists
- [x] `ItemSorter` port; all cases of the server's `ItemSorterTest` mirrored; plus order of adding and
      shop choices (connected layouts first)
- [x] `normalizeName` and input limits (`Limits`) returning the server's error codes
- [x] Tests: plain JUnit

Decided while implementing: `CreateList` / `AddItem` carry a local-only `createdAt` (not sent), so an item
not yet synced keeps a stable position (the web app recomputes it with the current time on every render).

## Phase 5 – Offline sync engine

Goal: lists and items work without a connection and sync when back online. Port of the web `SyncEngine`.

- [x] `SyncEngine`: confirmed state + acked + queued changes per space, persisted after every step; every
      write transforms the *current* state (changes during a running sync are never lost)
- [x] `ListRepository`: lists / list as the view; create, rename, add, edit, check, uncheck, delete,
      remove checked (sends the ids seen checked); delete list online only (owner)
- [x] Sync run: push ≤ 200 queued with `baseSeq` → APPLIED/DUPLICATE → acked, REJECTED → warning (except
      `LIST_NOT_FOUND`) → pull `since = baseSeq` → merge, new seq, clear acked; catalog/layouts from the pull
- [x] Errors: offline → keep everything; 404 → drop the space; others → status `ERROR`, retry with the next
      trigger; unexpected exceptions → `ERROR`, queue kept
- [x] Merge warnings stored, exposed as `Flow`, "clear all"
- [x] Undo for check = `uncheck`
- [x] Triggers (`AppSync`): enqueue (debounced 300 ms), app foreground, login, network regained, every
      5 min in the foreground
- [x] `SyncWorker` (WorkManager, network constraint, unique work): sends queued changes in the background
      / after the app was closed; scheduled when going to the background with pending changes or when a
      sync ends offline/with an error
- [x] Status `Flow` (IDLE / SYNCING / OFFLINE / ERROR) + pending change count
- [x] Logout: engine reset, work cancelled, store deleted
- [x] Tests: the scenarios of the web `syncEngine.test.ts` ported (fake server), plus debounce batching

Decided while implementing: `AppSync` syncs **all** spaces of the user (not only opened ones), so every
space is available offline. Respecting the `ignoreMergeWarnings` setting is left to the UI (it decides
whether to show the icon).

## Phase 6 – Catalog, layouts, history, suggestions

Goal: categories, units, shop sorting and autocomplete – available offline for reading.

- [x] Catalog and layouts come with the pull → space file; `refresh()` for explicit loads
- [x] `CatalogRepository`: categories (add, update, delete, reorder), units – online, answer stored
- [x] `LayoutRepository`: CRUD, mode, category / item order, connect / disconnect lists – online
- [x] ~~Favorites per layout~~ – removed on the server (2026-11-12); item history instead (see below)
- [x] `sortedItems(...)`: sorting for a shop on the device (works offline); last used shop per list
      remembered (DataStore)
- [x] Shop choices: connected layouts first, then the others
- [x] Suggestions: online from the server; offline the cached item history ranked like the server
      (`rankSuggestions`, server issue #1). The pull brings the complete catalog or `historyChanges`
      (merged by normalized name, `usage` kept in step)
- [x] Item history ("items used before"): list, rename, forget – online; copy a shop layout
- [x] Tests: repositories with MockWebServer, offline sorting from cached data

## Phase 7 – Live updates (SSE, foreground only)

Goal: changes of other members appear without manual refresh while the app is open. In the background
nothing is received (decided: no push / Firebase) – the app pulls when it is opened.

- [x] `LiveUpdates`: OkHttp `EventSource` on `GET /api/events` with the normal auth header
- [x] Lifecycle-aware (`ProcessLifecycleOwner`): connected in the foreground, also after login; stopped in
      the background and on logout
- [x] Coming to the foreground → sync all spaces
- [x] `SPACE_CHANGED` with a newer seq → sync that space; `PING` ignored
- [x] Reconnect with backoff 1 s → 30 s; sync all spaces after each (re)connect; 401/403 ends it
- [x] Event for an unknown space → refresh the space list
- [x] Tests: parsing, reconnect, backoff, 401 (MockWebServer streams)

Decided while implementing: 60 s read timeout on the stream (server pings every 25 s) to detect dead
connections instead of hanging forever.

## Phase 8 – Integration hardening

Goal: confidence that the app works against the real server.

- [x] `IntegrationTest` (JVM, `http://localhost:4723/` or `-Dshoppinglist.it.baseUrl`, skipped when
      unreachable): two users share a list and edit offline (OVERWRITTEN, RESTORED warnings); owner deletes
      a list a member changed offline (LIST_DELETED); layouts sort a list; live updates; log out
      everywhere invalidates the other device. Test users delete their accounts afterwards.
- [x] `DeviceSmokeTest` (instrumented, emulator): Keystore encryption round trip; the real `AppContainer`
      registers, syncs a list via `10.0.2.2` and logs out (local data deleted)
- [ ] Manual offline checklist on a device (airplane mode, app killed with queued changes, token expiry,
      removal from a space while offline, changes of others while in the background) – with the UI
- [x] OpenAPI spec refreshed and checked (generated client compiles against it)

Decided while implementing (found on the device): a `sync()` call that joins a running sync runs once more
if changes are still queued - the running sync (e.g. started by the login) may have begun before them.

## Phase 9 – UI (Compose)

Goal: the app looks and behaves like the web app (`frontend/src` in the server repo) – same pages, texts,
colors; the web app was finished first so the app follows a stable design.

- [x] Theme: the web's color tokens (light blue bar, navy ink, raised fields), light/dark from the user
      setting, 17sp base text; status bar icons dark on the bar in both themes
- [x] Texts generated from the web's i18n (`scripts/sync_strings.py`, en + de); language from
      the user setting, before login the device language or the EN/DE toggle; error codes → messages
- [x] Navigation: own back stack (`Screen`, `Navigator`, `NavHost`), survives process death; ViewModels
      per stack entry; title tap → start page; system back
- [x] Shared components: page with app bar (back, title, sync badges, actions, ⋮ menu, second line),
      bottom sheets for dialogs, confirm / name sheets, rows, fields, select, segmented, switch, FAB,
      drag-to-reorder list (accessibility: move up/down), color picker (palette, hue, hex)
- [x] Login, register (verification pending → "check mail"), forgot password, resend verification;
      "session expired" notice after a 401
- [x] Start page: lists of all spaces (in the user's order) with open count and "last used", + for a new
      list (space chips), spaces and settings as bar icons, first space
- [x] List page: tap to check, pencil → item editor (only changed fields), category headers on/off (color
      bars), "checked to the end" button, shop selection (connected first, remembered), rename, remove
      checked, delete (owner); add sheet with "Add another"/"Done", suggestions (online, offline from the
      history), automatic category, voice input (`RecognizerIntent`); space gone / list not found states
- [x] Spaces & sharing (personal order by drag and drop, `PUT /api/spaces/order`), space page (members,
      make owner, remove, leave, rename, delete, its lists – also offline; a list opened there leads back
      there), invite sheet
      (validity, copy, share), open invites (revoke), invite page; joining by pasted link (no App Links yet)
- [x] Categories & units (reorder, colors, rename on blur, delete), items used before (search, rename,
      remove), shops (list, editor: rename, mode, category / item order, import items of the connected
      lists, connected lists, copy, delete)
- [x] Settings (profile, language, theme, merge notes, keep screen on, API tokens, password, log out
      everywhere, log out – warns about unsent changes –, delete account, server version) and API tokens
- [x] Merge warnings sheet behind the ⚠ badge; offline badge with pending count; "Saving..."
- [x] Online-only pages show the offline hint and disable their controls
- [x] Tests: section grouping / amounts, color helpers, navigator; checked on the emulator against a
      local server (light + dark)

Decided while implementing:
- No navigation library (as homeautomation); a back-stack entry carries a UUID that keys its saved state
  and ViewModel store, so two lists on the stack don't share state.
- Dialogs are bottom sheets (the web's dialogs are bottom sheets on phones too).
- Joining a space works by pasting the invite link (⋮ on "Spaces & sharing") – kept until App Links exist.
- Debug builds take `-PdevApiBaseUrl=…` (local hosts only) – e.g. a real device via `adb reverse`.
- The server is configurable (open source, anyone can run one): `API_BASE_URL` is the default, the user
  can enter another address in the settings or on the login page (https only; checked with
  `GET /api/health`; changing it logs out). Added 2026-11-12.
- The bundle keeps all languages (`enableSplit = false`): the language is switched in the app.

## Later (not planned in detail yet)

- ~~Home screen widget~~ – not needed (decided 2026-11-12)
- [ ] Android App Links for invite / verification / reset links (server issue #2: `assetlinks.json`)
- [x] Production server URL as default (`https://shopping-list.tobias-fassbender.com/`, spec checked against it
      2026-11-12: identical apart from the version)
- [x] Minified release build runs (R8 keep rule for WorkManager's Room database in `proguard-rules.pro`;
      checked on the emulator against the production server)
- [x] Release signing (`doc/releases.md`), version 1.0.0; distributed as APK (no Play Store for now)
- ~~App Links~~ postponed (decided 2026-11-12) – invite links open in the browser / "join by link" in the app

## Decisions

| # | Topic                     | Decision |
|---|---------------------------|----------|
| 1 | Application id / package  | `com.tfassbender.shoppinglist` (pattern of homeautomation / ikbpool) |
| 2 | minSdk                    | 31 (Android 12), targetSdk 36 (Android 16) |
| 3 | DI                        | Manual `AppContainer` |
| 4 | Token storage             | Encrypted: `TokenCipher` / `SessionStore` (from homeautomation's `TokenStore`) |
| 5 | Offline suggestions       | The pull delivers the item history; the app ranks it like the server (server issue #1, done) |
| 6 | Background live updates   | None (no Firebase) – the app pulls when opened; SSE only in the foreground |
| 7 | Local storage             | JSON files per space instead of Room |
| 8 | API client                | Generated from the server's OpenAPI spec (as homeautomation); the fetch script drops the SSE path |

## Server issues

Server-side work the app depends on or benefits from (all four fixed on the server on 2026-11-11):
1. [#1](https://github.com/tfassbender/shopping_list_server/issues/1) Item history for offline autocomplete – done: `CatalogDto.history` + `PullResponse.historyChanges`, used by the app.
2. [#2](https://github.com/tfassbender/shopping_list_server/issues/2) Android App Links (`/.well-known/assetlinks.json`) – served; the app side is still open (see "Later").
3. [#3](https://github.com/tfassbender/shopping_list_server/issues/3) Personal favorites in the sync pull – obsolete: favorites were removed on 2026-11-12.
4. [#4](https://github.com/tfassbender/shopping_list_server/issues/4) OpenAPI response schemas – done; the fetch script's patches are gone.
