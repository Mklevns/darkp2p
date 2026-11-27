# darkp2p for Android

Native Kotlin client for the `darkp2p/v3` protocol. Design, wire contract and
roadmap: [`docs/android/TECHNICAL_DESIGN.md`](../docs/android/TECHNICAL_DESIGN.md).

Status: **Phase 5** code complete; nothing has run on a device yet.
All five modules are built. The logic is tested on the host JVM, including
file transfers with the unmodified `server.js` and page (relay and WebRTC, real
Chromium). Everything that touches Android itself, the whole UI included,
compiles, passes Lint and builds release APKs, but has never been run or seen.
The next step is a device or emulator session.

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

`check` runs the unit tests, the `:protocol` coverage minimum (90 % of lines),
Android Lint and `checkStructure`:

| Task | Fails when |
|---|---|
| `checkModuleGraph` | a module depends on one the graph above does not allow |
| `checkProtocolPurity` | `:protocol` imports Android or depends on anything but Kotlin/kotlinx |
| `checkForbiddenApis` | main code logs, bypasses the Tor-aware HTTP client, resolves DNS itself, or uses WebView |
| `checkDependencies` | a version is not exact, a dependency is declared outside the catalog, or a proprietary Google SDK appears |

## Test vectors

Two committed files tie this client to the web client, one per direction:

| File | Produced by | Checked by |
|---|---|---|
| `test/vectors/v3.json` | the page's own code (`node test/vectors/export.mjs`) | `:protocol` tests: same keys, same frame bytes, same decisions |
| `test/vectors/android-v3.json` | `:protocol` (`./gradlew :protocol:test -PwriteVectors`) | `npm test`: the page decrypts each frame and its state machines accept the transfer |

After a change to the wire format in `public/index.html`, run the first
command from the repository root. `npm test` fails while a committed file is
stale, and the Kotlin tests then show what the Android side must change.

## Interop tests

`:transport`'s unit tests run on the host JVM and start `test/interop/peer.mjs`,
which runs the real `server.js` and real copies of the page. They need Node 22
and `npm ci` in the repository root.

```bash
./gradlew :transport:testFullDebugUnitTest
```

The WebRTC tests use a desktop build of libwebrtc and run on Linux x86-64
only; elsewhere they are reported as skipped.

With Playwright and its Chromium installed, the page can also run in a real
headless browser, over the relay and over WebRTC:

```bash
DARKP2P_CHROMIUM=1 ./gradlew :transport:testFullDebugUnitTest --tests '*Chromium*'
```

## Dependencies

Versions live in `gradle/libs.versions.toml`, exact versions only. Every
downloaded artifact is checked against `gradle/verification-metadata.xml`.
After changing a version, regenerate the checksums and review the diff:

```bash
./gradlew --write-verification-metadata sha256 check :app:assembleFullDebug :app:assembleFullRelease :app:assembleLibreDebug :app:assembleLibreRelease
```

The file was generated on Linux. A first build on macOS or Windows needs the
same command once, to add that platform's build tools (for example `aapt2`).
