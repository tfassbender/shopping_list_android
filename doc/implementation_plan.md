# Implementation Plan

Phased roadmap for the Android app of [shopping_list_server](https://github.com/tfassbender/shopping_list_server).
Based on the server's `doc/features.md` (what), `doc/tech_stack.md` here (how) and `doc/server_api.md`
here (the REST contract). Update the checkboxes and the status as work completes.

**Status:** Planning. Nothing implemented yet.

## Scope of this plan

**Only the non-UI part of the app ("app backend")**: project setup, API client, session, local storage,
offline sync, live updates and the client-side domain logic (sorting, optimistic changes). Every phase
ends with tested repositories that a UI can sit on.

The **UI is planned later**, once the web frontend's UI improvements are done – the app should follow the
final web design rather than a moving target. Until then, a throwaway debug screen may be used to try
things out by hand (optional).

## Guiding principles

- **The server is the contract.** No business rule only in the app; where the app needs logic offline
  (sorting, optimistic apply), it is a port of the server/web logic with **mirrored tests**.
- **The local store is the single source of truth** for cached data. Repositories expose `StateFlow`s;
  network answers are written to the store, never handed to the UI directly.
- **Offline-first for lists and items** (all mutations go through the sync queue, exactly like the web
  app); everything else is online with a read cache.
- **Tests:** pure Kotlin domain + sync engine with fakes (JUnit 4); API with `MockWebServer`; a few
  integration tests against a locally running server (skipped if none is reachable, like the server's
  `e2e` module).

## Overview

| Phase | Content                                   | Result |
|-------|-------------------------------------------|--------|
| 0     | Project setup                             | Empty app builds, tests run, server URL configurable |
| 1     | API client                                | Every endpoint callable, errors mapped to codes |
| 2     | Session & account                         | Login/register, encrypted token, sliding refresh, logout on 401 |
| 3     | Local storage & spaces                    | JSON file store, spaces/members/invites repositories |
| 4     | Client domain logic                       | Change model, optimistic apply, pull merge, sorter |
| 5     | Offline sync engine                       | **Lists & items work offline** |
| 6     | Catalog, layouts, favorites, suggestions  | Categories, units, shop sorting, autocomplete |
| 7     | Live updates (SSE)                        | Shared lists update without refresh while the app is open |
| 8     | Integration hardening                     | Tested end-to-end against a local server |
| later | UI, widget, app links, voice input        | |

---

## Phase 0 – Project setup

Goal: an empty Android project that builds and runs tests, ready to talk to a local server. Setup modelled
on `../homeautomation`.

- [ ] Android Studio project: single `app` module, Kotlin DSL, version catalog, Compose enabled (only a
      placeholder activity for now)
- [ ] Application id / namespace `com.tfassbender.shoppinglist`, debug suffix `.debug`;
      compileSdk/targetSdk 36, minSdk 31, Java 11
- [ ] Versioning as homeautomation: `appVersion` → derived `versionCode`
- [ ] Package skeleton `domain` / `data.remote` / `data.local` / `data.sync` / `data.repo`
- [ ] `AppContainer` (manual DI) created in a custom `Application` class
- [ ] `BuildConfig.API_BASE_URL` per build type (debug `http://10.0.2.2:4720/`), runtime override in debug
- [ ] Network security config: cleartext only to `10.0.2.2` / `localhost`, debug only
- [ ] Backup: exclude the local store and the token from backup / device transfer
- [ ] Test setup: JUnit 4, MockWebServer, coroutines-test
- [ ] `.gitignore`, `CLAUDE.md` with build/test commands
- [ ] Release signing as homeautomation (decide later whether the key is committed)

## Phase 1 – API client

Goal: every endpoint of `doc/server_api.md` is callable from Kotlin.

- [ ] `doc/openapi.json` from the server (`/q/openapi?format=json` of a local dev server) + a skill /
      script to refresh it (like homeautomation's `update-openapi`)
- [ ] openapi-generator wired into the build (generated into `build/generated/openapi`, never edited)
- [ ] Check the generated code for the tricky spots: `Instant` fields, enums, `PATCH` edit item (clear
      a field by sending `""` – the server treats `""` like `null`, so dropped nulls don't matter),
      `remove-checked` / empty bodies, the SSE endpoint (not usable generated → own OkHttp client)
- [ ] `ApiClientFactory`: OkHttp with `AuthInterceptor` (token only to the API host; HTTPS or the local
      dev hosts), no redirects, timeouts, BASIC logging in debug only
- [ ] `apiCall { }` → `ApiResult` / `ApiError(status, code, message)` parsed from the JSON error body;
      network failure as its own case (`Offline`) – the sync engine must tell them apart
- [ ] Tests with `MockWebServer`: error mapping, auth header, no token to foreign hosts / plain HTTP

## Phase 2 – Session & account

Goal: a user can register, log in and stay logged in for weeks – token stored encrypted.

- [ ] `TokenCipher` + `TokenStore` from homeautomation (AES-256/GCM, Keystore key), extended with
      `expiresAt` / `receivedAt`; undecryptable token → logged out
- [ ] `AuthRepository`: config, register (handle `verificationRequired` – no token yet), login,
      resend verification, forgot password, logout (local only)
- [ ] Verification / reset links point to the web app – the app only triggers the emails (see App Links
      under "Later")
- [ ] Sliding session: refresh when the token is older than one day (on app start / foreground)
- [ ] `401` from any call → clear session and all local data, emit "logged out" (reason: expired / revoked)
- [ ] `AccountRepository`: me (cached), update settings, display name, change password and logout
      everywhere (both return a **new token** → store it), delete account
- [ ] `ApiTokenRepository`: list, create (raw token returned once), revoke – online only
- [ ] Tests: refresh timing, 401 handling, token replacement after password change

## Phase 3 – Local storage & spaces

Goal: the JSON file store; spaces with members and invites.

- [ ] `JsonFileStore`: atomic writes (`AtomicFile`), `schemaVersion`, lenient reading (unknown keys,
      defaults), unreadable file → dropped and resynced
- [ ] Files: `me.json`, `spaces.json`, `spaces/<spaceId>.json` (`SpaceState`, filled in Phases 5/6)
- [ ] In-memory `StateFlow` per file, `Mutex` per space for load-modify-save
- [ ] `SpaceRepository`: observe spaces (cache), refresh, create, rename, delete, leave
- [ ] `MemberRepository`: members, remove member, transfer ownership
- [ ] `InviteRepository`: list, create (expiry), revoke, preview + accept by token; parse the token from a
      pasted / shared link (`.../invite/<token>`)
- [ ] Owner-only actions: check `myRole` locally for a good UX, still handle `403 OWNER_ONLY`
- [ ] Online-only operations fail with a clear `NeedsConnection` error when offline
- [ ] Logout deletes the store
- [ ] Tests: store round trips (temp dir, plain JVM), repositories with a fake API

## Phase 4 – Client domain logic

Goal: the pure Kotlin logic the sync engine and the UI need – ported from the server/web with mirrored tests.

- [ ] Domain models (`ShoppingList`, `Item`, `Category`, `ShopLayout`, …) separate from API models
- [ ] Change model `SyncOp` (sealed class, one per `SyncChangeType`) with client-generated UUIDs for change
      ids, list ids and item ids
- [ ] `applyOp(lists, op)` – the optimistic view (port of web `applyOp.ts`): trimming, blank → null, edit
      name never blank, `REMOVE_CHECKED` with the item ids seen checked
- [ ] `applyPull(lists, pull)` – merge a pull into the confirmed state: tombstones, `fullResync`, drop
      lists missing in `listIds`
- [ ] `ItemSorter` port (checked to end → layout position → favorites → most bought → name → createdAt)
      and `normalize(name)`; mirror the server's `ItemSorterTest` cases
- [ ] Input limits (`doc/server_api.md`) as constants + validation helpers
- [ ] Tests: plain JUnit, no Android

## Phase 5 – Offline sync engine

Goal: lists and items work without a connection and sync when back online. Port of the web `SyncEngine`.

- [ ] `SyncEngine` per space: confirmed state + acked + queued changes, persisted in the space file after
      every step (a crash never loses a change)
- [ ] `ListRepository`: observe lists of a space / one list as the **view** (confirmed + acked + queued
      applied); `enqueue(op)` stores the change and triggers a debounced sync (~300 ms)
- [ ] Operations: create list, rename list, add item, edit field, check, uncheck, delete item, remove
      checked; **delete list** is online only (not a sync change, owner only)
- [ ] Sync run (one per space at a time): push ≤ 200 queued with `baseSeq` → results (APPLIED/DUPLICATE →
      acked; REJECTED → warning, except `LIST_NOT_FOUND` which `LIST_DELETED` covers) → pull
      `since = baseSeq` → `applyPull` → store new seq, clear acked
- [ ] Error handling: offline → keep everything, status `offline`; `404` → drop the space locally; other
      errors → status `error`, retry on next trigger (no tight loop)
- [ ] Merge warnings: stored, exposed as `Flow`, "clear all"; respects the `ignoreMergeWarnings` setting
- [ ] Undo for check = enqueue the opposite change (UI later)
- [ ] Triggers: enqueue, app start, app foreground, connectivity regained (`ConnectivityManager`
      callback), periodic fallback every 5 min while in foreground
- [ ] `SyncWorker` (WorkManager, network constraint, unique work): flushes the queue when the app is in
      the background or closed
- [ ] Sync status `Flow` (idle / syncing / offline / error) + pending change count
- [ ] Tests: port the scenarios of the web `syncEngine.test.ts` (fake API, temp dir store), incl. changes
      made during a running sync, resend after lost response (idempotency), full resync

## Phase 6 – Catalog, layouts, favorites, suggestions

Goal: categories, units, shop sorting and autocomplete – available offline for reading.

- [ ] Catalog and layouts come with the **pull** (only when changed) → stored in the space file; explicit
      `GET` when a space is opened the first time
- [ ] `CatalogRepository`: categories (add, rename/recolor, delete, reorder), units (replace) – online
      only, answer written to the store
- [ ] `LayoutRepository`: CRUD, mode switch, category order / item order, connect / disconnect lists –
      online only
- [ ] `FavoriteRepository`: per layout, cached in the space file (fetched separately until the pull
      contains them – server issue); add/remove online
- [ ] Sorting a list for a shop **on the device** with the ported `ItemSorter` (works offline;
      `checkedItemsToEnd` from the user settings); remember the last used layout per list (DataStore)
- [ ] Shop choice for a list: connected layouts first, then the other layouts of the space
- [ ] Suggestions: online via `GET .../suggestions`; offline from the item history in the catalog once
      the server delivers it (server issue), until then from item names of the local lists
- [ ] Tests: repositories with fake API, sorting with cached data

## Phase 7 – Live updates (SSE, foreground only)

Goal: changes of other members appear without manual refresh while the app is open. In the background
nothing is received (decided: no push / Firebase) – the app pulls when it is opened.

- [ ] SSE client with OkHttp (`okhttp-sse`) on `GET /api/events`, auth header, no read timeout
- [ ] Lifecycle-aware: connected while the app is in the foreground (`ProcessLifecycleOwner`),
      disconnected in the background
- [ ] Coming to the foreground → sync all spaces (covers everything missed in the background)
- [ ] `SPACE_CHANGED` with a seq newer than the local one → sync that space; `PING` ignored
- [ ] Reconnect with backoff 1 s → 30 s; full sync of all spaces after each (re)connect
- [ ] Event for an unknown space → refresh spaces (e.g. invite accepted on another device)
- [ ] Tests: event parsing, backoff, reconnect → sync (fake event source)

## Phase 8 – Integration hardening

Goal: confidence that the app works against the real server.

- [ ] Integration tests (JVM, against `http://localhost:4720`, skipped when unreachable): register →
      space → list → items; two clients editing the same list offline and syncing (merge warnings);
      invite flow with two users; logout everywhere → 401
- [ ] Manual offline checklist (like the server's `doc/offline_test_checklist.md`): airplane mode, app
      killed with queued changes, token expiry, removal from a space while offline, changes of others
      while the app was in the background
- [ ] Refresh `doc/openapi.json` and check for API drift

## Later (not planned in detail yet)

- [ ] **UI** (Compose): screens following the final web app – start page with spaces, lists, list screen
      with quick add, item editor, categories, layouts, members/invites, settings, merge warning dialog,
      offline indicator; German + English strings; dark mode
- [ ] Voice input via Android `SpeechRecognizer` (free, on-device) – *Could*
- [ ] Home screen widget (Glance)
- [ ] Android App Links for invite / verification / reset links (server issue: `assetlinks.json`)
- [ ] Release: signing, production server URL, distribution

## Decisions

| # | Topic                     | Decision |
|---|---------------------------|----------|
| 1 | Application id / package  | `com.tfassbender.shoppinglist` (pattern of homeautomation / ikbpool) |
| 2 | minSdk                    | 31 (Android 12), targetSdk 36 (Android 16) |
| 3 | DI                        | Manual `AppContainer` |
| 4 | Token storage             | Encrypted: `TokenCipher` / `TokenStore` from homeautomation |
| 5 | Offline suggestions       | Server delivers the item history (category/unit) to clients – server issue |
| 6 | Background live updates   | None (no Firebase) – the app pulls when opened; SSE only in the foreground |
| 7 | Local storage             | JSON files per space instead of Room |
| 8 | API client                | Generated from the server's OpenAPI spec (as homeautomation) |

## Server issues

Server-side work the app depends on or benefits from:
1. [#1](https://github.com/tfassbender/shopping_list_server/issues/1) Item history (category/unit) for offline autocomplete (+ catalog seq not bumped on history changes) – needed for Phase 6 offline suggestions.
2. [#2](https://github.com/tfassbender/shopping_list_server/issues/2) Android App Links (`/.well-known/assetlinks.json`) – needed for opening invite/email links in the app.
3. [#3](https://github.com/tfassbender/shopping_list_server/issues/3) Personal favorites in the sync pull – nice to have (otherwise fetched per layout).
