# darkp2p for Android

Native Kotlin client for the `darkp2p/v3` protocol. Design, wire contract and
roadmap: [`docs/android/TECHNICAL_DESIGN.md`](../docs/android/TECHNICAL_DESIGN.md).

Status: **Phase 0** (scaffold). The modules build and the structural checks
run. `:protocol` holds the wire constants; the rest is empty until its phase.

## Modules

```
:app ──► :service ──► :transport ──► :protocol
  │         │             ▲              ▲
  └──► :storage ──────────┘──────────────┘        :testing (test-only fakes)
```

| Module | Kind | Contents |
|---|---|---|
| `:protocol` | Kotlin/JVM | Framing, HKDF/AES-GCM, state machines, relay budget. No Android. |
| `:transport` | Android library | Signaling and relay WebSocket, WebRTC DataChannel, Tor. |
| `:storage` | Android library | Keystore vault (Pro token, pending claim), claim polling. |
| `:service` | Android library | Foreground service, streaming file I/O. |
| `:app` | Application | UI. Flavors: `full` (GitHub/Play), `libre` (F-Droid). |

## Build

Needs JDK 17 or newer (CI uses 21) and the Android SDK (`ANDROID_HOME`, or
`sdk.dir` in `android/local.properties`).

```bash
cd android
./gradlew check :app:assembleFullDebug
```

`check` runs the unit tests, Android Lint and `checkStructure`:

| Task | Fails when |
|---|---|
| `checkModuleGraph` | a module depends on one the graph above does not allow |
| `checkProtocolPurity` | `:protocol` imports Android or depends on anything but Kotlin/kotlinx |
| `checkForbiddenApis` | main code logs, bypasses the Tor-aware HTTP client, resolves DNS itself, or uses WebView |
| `checkDependencies` | a version is not exact, a dependency is declared outside the catalog, or a proprietary Google SDK appears |

## Test vectors

`:protocol` tests read `../test/vectors/v3.json`, generated from the web client:

```bash
node test/vectors/export.mjs
```

Run that from the repository root after any change to the wire format in
`public/index.html`. `npm test` fails while the committed file is stale, and
the Kotlin tests then show what the Android side must change.

## Dependencies

Versions live in `gradle/libs.versions.toml`, exact versions only. Every
downloaded artifact is checked against `gradle/verification-metadata.xml`.
After changing a version, regenerate the checksums and review the diff:

```bash
./gradlew --write-verification-metadata sha256 check :app:assembleFullDebug :app:assembleFullRelease :app:assembleLibreDebug :app:assembleLibreRelease
```

The file was generated on Linux. A first build on macOS or Windows needs the
same command once, to add that platform's build tools (for example `aapt2`).
