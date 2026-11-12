# Shopping List – Android

Native Android app (Kotlin, Jetpack Compose) for the
[shopping list server](https://github.com/tfassbender/shopping_list_server): shared shopping lists that
also work offline and sync when the connection is back.

## Status

The non-UI part is done (Phases 0–8 of the [implementation plan](doc/implementation_plan.md)): API
client, encrypted login session, local storage, offline sync, shop sorting and live updates. The user
interface follows once the web app's UI is final – until then the app only shows a placeholder.

## Build

Requires JDK 17 or newer and the Android SDK (minSdk 31 / Android 12).

```sh
./gradlew assembleDebug        # debug APK ("Shopping List (Debug)", installs next to the real app)
./gradlew testDebugUnitTest    # unit tests (+ integration tests if a local server runs)
./gradlew installDebug         # install on the emulator / connected device
./gradlew connectedDebugAndroidTest  # device tests on the emulator
```

## Local server

The debug build talks to the local dev server: start it in the server repo with
`./gradlew :backend:quarkusDev` (port 4723). The emulator reaches it at `http://10.0.2.2:4723/`; on a
real device run `adb reverse tcp:4723 tcp:4723` first.

The API client is generated from [doc/openapi.json](doc/openapi.json). After API changes on the server,
refresh it with `python .claude/skills/update-openapi/fetch_openapi.py`.

## Documentation

- [Implementation plan](doc/implementation_plan.md) – phases, status, decisions
- [Tech stack & architecture](doc/tech_stack.md)
- [Server API reference](doc/server_api.md)
