# Server API Reference

The REST contract of [shopping_list_server](https://github.com/tfassbender/shopping_list_server) as far
as the Android app needs it. Derived from the server code (`backend/.../adapter/rest/*.kt`, state: server
Phases 0–10 done, not deployed yet). **The server code is the source of truth** – when this file and the
server disagree, fix this file. A running server also serves the spec at `/q/openapi` and Swagger UI at
`/q/swagger-ui`.

## Basics

- Base path `/api`, JSON only. Local dev server: `http://localhost:4723` (Android emulator:
  `http://10.0.2.2:4723`). Start it with `./gradlew :backend:quarkusDev` in the server repo; in dev mode
  email verification is disabled (registration returns a token directly).
- Auth: `Authorization: Bearer <token>` – a session JWT from login/register. (Personal API tokens `slt_...`
  exist for AI agents; the app always uses a JWT.)
- Timestamps are ISO-8601 instants (`2026-11-11T17:09:00Z`). Ids are strings (UUIDs); the client may
  generate ids for lists, items, categories and layouts.
- Errors: `{"code": "LIST_NOT_FOUND", "message": "..."}` with a stable `code` (use it for translated
  messages); 500s add `errorId`. Status by kind: 400 validation, 401 unauthorized, 403 forbidden
  (e.g. `OWNER_ONLY`), 404 not found, 409 conflict, 503 unavailable (e.g. `EMAIL_DISABLED`).
- Non-members of a space get `404 SPACE_NOT_FOUND` (existence is not revealed); members calling owner
  actions get `403 OWNER_ONLY`.

## The server's OpenAPI spec

The app's client is generated from the spec. Since server issue
[#4](https://github.com/tfassbender/shopping_list_server/issues/4) it documents all response schemas and
optional query parameters; `.claude/skills/update-openapi/fetch_openapi.py` only drops the SSE endpoint
(own OkHttp client) and warns if an empty response schema shows up again.
- Null fields are sometimes left out although marked required (`SessionResponse.token`) → the client
  decodes with `explicitNulls = false`.

## Session model

- JWT valid **30 days**, "sliding": the client exchanges it via `POST /api/auth/refresh` (web: when the
  token is older than one day).
- Password change / reset and "log out everywhere" invalidate all older tokens (session version claim) →
  those get `401`. The client logs out on `401`.
- Endpoints marked **session** reject API tokens – irrelevant for the app, which always uses a JWT.

## Endpoints

### System
| Method | Path          | Body / Params | Response |
|--------|---------------|---------------|----------|
| GET    | `/api/health` | –             | `{status: "UP", version}` (no auth) |

### Auth (`/api/auth`, public except `refresh`)
| Method | Path                   | Body                                         | Response |
|--------|------------------------|----------------------------------------------|----------|
| GET    | `/config`              | –                                            | `{emailEnabled, minPasswordLength}` |
| POST   | `/register`            | `{email, displayName, password, language?}`  | 201 `SessionResponse` |
| POST   | `/login`               | `{email, password}`                          | `SessionResponse` |
| POST   | `/refresh` (session)   | –                                            | `SessionResponse` |
| POST   | `/verify-email`        | `{token}`                                    | `SessionResponse` |
| POST   | `/resend-verification` | `{email}`                                    | 204 (always) |
| POST   | `/forgot-password`     | `{email}`                                    | 204 (always) |
| POST   | `/reset-password`      | `{token, password}`                          | `SessionResponse` |

- `SessionResponse = {user: UserDto, token?, expiresAt?, verificationRequired}` – `token` is missing
  after registering while the email still has to be verified.
- `language` is `en` / `de`; it also decides the language of the default categories of new spaces.
- Verification and reset tokens arrive by email as links to the **web app** (`public-base-url`).

### Account (`/api/users/me`)
| Method | Path                        | Body                                 | Response |
|--------|-----------------------------|--------------------------------------|----------|
| GET    | `/`                         | –                                    | `UserDto` |
| PUT    | `/settings`                 | `SettingsDto` (complete)             | `UserDto` |
| PUT    | `/display-name`             | `{displayName}`                      | `UserDto` |
| POST   | `/change-password` (session)| `{currentPassword, newPassword}`     | `SessionResponse` (new token) |
| POST   | `/logout-everywhere` (session)| –                                  | `SessionResponse` (new token for this device) |
| DELETE | `/` (session)               | –                                    | 204 |

- `UserDto = {id, email, displayName, emailVerified, settings}`
- `SettingsDto = {language: "en"|"de", theme: SYSTEM|LIGHT|DARK, checkedItemsToEnd, ignoreMergeWarnings}`

### API tokens (`/api/users/me/api-tokens`, session)
| Method | Path         | Body                                  | Response |
|--------|--------------|---------------------------------------|----------|
| GET    | `/`          | –                                     | `ApiTokenDto[]` |
| POST   | `/`          | `{name, scope: READ|FULL, expiresInDays?}` (1–3650, null = never) | 201 `{token: ApiTokenDto, rawToken}` (raw token shown once) |
| DELETE | `/{tokenId}` | –                                     | 204 |

`ApiTokenDto = {id, name, scope, createdAt, expiresAt?, lastUsedAt?}`

### Spaces (`/api/spaces`)
| Method | Path                                  | Body                    | Who    | Response |
|--------|---------------------------------------|-------------------------|--------|----------|
| GET    | `/`                                   | –                       | member | `SpaceDto[]` |
| POST   | `/`                                   | `{name}`                | any    | 201 `SpaceDto` (creator = owner) |
| GET    | `/{spaceId}`                          | –                       | member | `SpaceDto` |
| PUT    | `/{spaceId}/name`                     | `{name}`                | owner  | `SpaceDto` |
| DELETE | `/{spaceId}`                          | –                       | owner  | 204 |
| GET    | `/{spaceId}/members`                  | –                       | member | `MemberDto[]` |
| DELETE | `/{spaceId}/members/{userId}`         | –                       | owner  | 204 |
| POST   | `/{spaceId}/leave`                    | –                       | member | 204 (owner: `OWNER_CANNOT_LEAVE`) |
| POST   | `/{spaceId}/transfer-ownership`       | `{userId}`              | owner  | `SpaceDto` |
| GET    | `/{spaceId}/invites`                  | –                       | owner  | `InviteDto[]` (open invites) |
| POST   | `/{spaceId}/invites`                  | `{expiresInHours?}` (default 24, 1–720) | owner | 201 `{invite: InviteDto, token, url}` |
| DELETE | `/{spaceId}/invites/{inviteId}`       | –                       | owner  | 204 |

- `SpaceDto = {id, name, myRole: OWNER|MEMBER, memberCount, createdAt}`
- `MemberDto = {userId, displayName, role, joinedAt}`; `InviteDto = {id, createdAt, expiresAt}`
- The invite `url` is `<public-base-url>/invite/<token>` (a web app link).

### Invites (`/api/invites/{token}`, for the invited user)
| Method | Path      | Response |
|--------|-----------|----------|
| GET    | `/`       | `{spaceName, invitedBy, alreadyMember}` |
| POST   | `/accept` | `SpaceDto` (single use; errors `INVITE_INVALID`, `ALREADY_MEMBER`) |

### Lists (`/api/spaces/{spaceId}/lists`)

The app routes list/item **mutations through the sync endpoint** (see below), like the web app. These
plain endpoints are still useful for reading, for deleting lists (not a sync change) and for tests.

| Method | Path                                   | Body | Response |
|--------|----------------------------------------|------|----------|
| GET    | `/`                                    | –    | `ListSummaryDto[]` |
| POST   | `/`                                    | `{name, id?}` | 201 `ListDto` |
| GET    | `/{listId}?sort=<layoutId>`            | –    | `ListDto` |
| PUT    | `/{listId}/name`                       | `{name}` | `ListDto` |
| DELETE | `/{listId}`                            | –    | 204 (**owner only**) |
| POST   | `/{listId}/items`                      | `{name, id?, quantity?, unit?, note?, categoryId?}` | 201 `ListDto` |
| PATCH  | `/{listId}/items/{itemId}`             | subset of `{name, quantity, unit, note, categoryId}` – missing = unchanged, `null`/`""` = clear | `ListDto` |
| POST   | `/{listId}/items/{itemId}/check`       | –    | `ListDto` |
| POST   | `/{listId}/items/{itemId}/uncheck`     | –    | `ListDto` |
| DELETE | `/{listId}/items/{itemId}`             | –    | `ListDto` |
| POST   | `/{listId}/remove-checked`             | –    | `ListDto` |

- `ListDto = {id, spaceId, name, createdAt, seq, items: ItemDto[], sortedBy?}` (no tombstones)
- `ItemDto = {id, name, quantity?, unit?, note?, categoryId?, checked, createdAt, seq}`
- `ListSummaryDto = {id, name, openItems, checkedItems, seq}`
- If an added item has no category, the server fills it in from the item history.

### Catalog (`/api/spaces/{spaceId}/catalog`, every member)
| Method | Path                         | Body | Response |
|--------|------------------------------|------|----------|
| GET    | `/`                          | –    | `CatalogDto` |
| POST   | `/categories`                | `{name, color: "#RRGGBB", id?}` | 201 `CatalogDto` |
| PUT    | `/categories/{categoryId}`   | `{name, color}` | `CatalogDto` |
| DELETE | `/categories/{categoryId}`   | –    | `CatalogDto` (its items become uncategorized) |
| PUT    | `/categories/order`          | `{ids: [...all category ids]}` | `CatalogDto` |
| PUT    | `/units`                     | `{units: [...]}` (replaces) | `CatalogDto` |
| GET    | `/suggestions?q=&limit=8`    | –    | `SuggestionDto[]` |
| GET    | `/history`                   | –    | `HistoryEntryDto[]` (alphabetically) |
| PUT    | `/history?name=`             | `{name}` | `HistoryEntryDto[]` (rename; merged if the new name exists) |
| DELETE | `/history?name=`             | –    | `HistoryEntryDto[]` (forget) |

- `CatalogDto = {categories: [{id, name, color}] (in order), units: string[], usage: {normalizedName: count},
  history: HistoryEntryDto[]}`
- `HistoryEntryDto = {name, categoryId?, unit?, usageCount, lastUsed}` – items used before in the space.
- `SuggestionDto = {name, categoryId?, unit?, usageCount}` – names starting with the query first, then
  word-prefix matches; by usage count, then recency, then name. The app ranks the cached `history` the
  same way offline (`rankSuggestions`).
- Category names are unique per space (case/space-insensitive, `CATEGORY_EXISTS`).

### Shop layouts (`/api/spaces/{spaceId}/layouts`)
| Method | Path                               | Body | Response |
|--------|------------------------------------|------|----------|
| GET    | `/`                                | –    | `LayoutDto[]` |
| POST   | `/`                                | `{name, mode?: CATEGORIES|ITEMS, id?}` | 201 `LayoutDto` |
| POST   | `/{layoutId}/copy`                 | `{name, id?}` | 201 `LayoutDto` (same mode, orders, connected lists) |
| GET    | `/{layoutId}`                      | –    | `LayoutDto` |
| PUT    | `/{layoutId}`                      | `{name, mode}` | `LayoutDto` |
| DELETE | `/{layoutId}`                      | –    | 204 |
| PUT    | `/{layoutId}/category-order`       | `{entries: [categoryIds]}` | `LayoutDto` |
| PUT    | `/{layoutId}/item-order`           | `{entries: [itemNames]}` | `LayoutDto` |
| PUT    | `/{layoutId}/lists/{listId}`       | –    | `LayoutDto` (connect list) |
| DELETE | `/{layoutId}/lists/{listId}`       | –    | `LayoutDto` (disconnect list) |

`LayoutDto = {id, name, mode, categoryOrder: string[], itemOrder: string[], listIds: string[], createdAt}`

### Offline sync (`/api/spaces/{spaceId}/changes`)

**Push** `POST` `{baseSeq, changes: SyncChangeDto[]}` (max **500** per batch, `BATCH_TOO_LARGE`):

| `type`           | Required fields                                        |
|------------------|--------------------------------------------------------|
| `CREATE_LIST`    | `listId` (client UUID), `name`                         |
| `RENAME_LIST`    | `listId`, `name`                                       |
| `ADD_ITEM`       | `listId`, `itemId` (client UUID), `name`, optional `quantity`, `unit`, `note`, `categoryId` |
| `EDIT_ITEM`      | `listId`, `itemId`, `field: NAME|QUANTITY|UNIT|NOTE|CATEGORY`, `value` (null/"" clears) |
| `CHECK_ITEM` / `UNCHECK_ITEM` / `DELETE_ITEM` | `listId`, `itemId`        |
| `REMOVE_CHECKED` | `listId`, `itemIds` = the items the client saw checked (null = all checked now) |

Every change has a unique `changeId` (≤ 64 chars); resending the same id is a no-op (remembered 30 days).

Response `{seq, results: [{changeId, status: APPLIED|DUPLICATE|REJECTED, code?}], warnings: WarningDto[]}`

`WarningDto = {type, listId, listName?, itemId?, itemName?, field?, theirValue?, yourValue?, byName?}`:
- `OVERWRITTEN` – same field changed concurrently by someone else; later applied wins (`field` is an
  `ItemAspect`: `NAME` with `itemId` null = list name)
- `RESTORED` – your edit/check restored an item someone deleted (edit wins over delete)
- `DELETE_SKIPPED` – your delete was skipped because someone else changed the item
- `LIST_DELETED` – the list no longer exists; changes to it are rejected

A change conflicts only if what it touches was changed after `baseSeq` **by another user**.

**Pull** `GET ?since=<seq>` (0 = full state):
`{seq, fullResync, lists: SyncListDto[], listIds, catalog?, historyChanges, layouts?, layoutIds}`
- `SyncListDto = {id, name, createdAt, seq, deleted, items: SyncItemDto[]}` – only changed lists, with
  only their changed items, **including tombstones** (`deleted: true`).
- `SyncItemDto = ItemDto` plus `deleted`.
- `listIds` / `layoutIds`: all existing ids – drop local ones not contained.
- `catalog` (complete, incl. history) only if categories/units changed or history entries were removed /
  renamed – replace the local catalog. Otherwise `historyChanges` = history entries added or updated since
  `since` (items added or checked) – merge them by normalized name and update `usage`.
- `layouts` (all of them) only if one changed.
- `fullResync: true` → `lists` is the complete state; drop everything local not in it. Happens for
  `since = 0`, `since` older than the tombstone purge horizon (30 days) or `since` > current seq.

**Protocol:** push queued changes with `baseSeq` = seq of the last pull, **then pull with `since = baseSeq`**
(not the push seq – other users' changes may lie in between). Store the pulled `seq` as the new base.

Not in the pull: spaces/members, user settings – fetch those separately.

### Live updates (`GET /api/events`)

Server-sent events (`text/event-stream`), one stream per user, normal `Authorization` header.
Data payloads: `{"type":"SPACE_CHANGED","spaceId":"…","seq":42}` and `{"type":"PING"}` every 25 s.
On `SPACE_CHANGED` the client syncs that space (if `seq` > its own); after every (re)connect it syncs all
spaces, since events may have been missed.

## Limits (validate client-side for good error messages)

| What                        | Limit |
|-----------------------------|-------|
| Space / list / layout name  | 1–60 chars, trimmed |
| Item name                   | 1–100 chars |
| Quantity / unit             | ≤ 20 chars |
| Note                        | ≤ 200 chars |
| Category name               | ≤ 40 chars; max 100 categories |
| Units                       | ≤ 20 chars each; max 50 |
| Items per list              | 1000 (`LIST_FULL`) |
| Layout item order           | 1000 entries |
| Invite validity             | 1 h – 30 days |
| Sync batch                  | 500 changes |

Name normalization (history, ITEMS layouts): `trim().lowercase()`, whitespace runs → one space.

## Error codes

`ALREADY_MEMBER BATCH_TOO_LARGE CATEGORY_EXISTS CATEGORY_NOT_FOUND EMAIL_DISABLED EMAIL_NOT_VERIFIED
EMAIL_TAKEN INVALID_COLOR INVALID_CREDENTIALS INVALID_EMAIL INVALID_EXPIRY INVALID_ID INVALID_ITEM_FIELD
INVALID_ITEM_NAME INVALID_LANGUAGE INVALID_NAME INVALID_ORDER INVALID_TOKEN INVALID_UNIT INVITE_INVALID
INVITE_NOT_FOUND ITEM_EXISTS ITEM_NOT_FOUND LAYOUT_EXISTS LAYOUT_NOT_FOUND LIST_EXISTS LIST_FULL
LIST_NOT_FOUND MEMBER_NOT_FOUND OWNER_CANNOT_LEAVE OWNER_ONLY PASSWORD_TOO_LONG PASSWORD_TOO_SHORT
SESSION_EXPIRED SPACE_NOT_FOUND TOKEN_NOT_FOUND TOO_MANY_CATEGORIES TOO_MANY_TOKENS
TOO_MANY_UNITS USER_NOT_FOUND WRONG_PASSWORD INTERNAL_ERROR`, plus framework codes like `UNAUTHORIZED`.
Sync push rejections additionally use `INVALID_CHANGE`.
