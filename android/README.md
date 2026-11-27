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

## Run on an emulator

One-time setup on your own machine (Android Studio does all of it through its
SDK Manager and Device Manager; these are the command-line equivalents):

```bash
sdkmanager "platform-tools" "emulator" "platforms;android-36" "system-images;android-36;google_apis;x86_64"
```

```bash
avdmanager create avd -n darkp2p -k "system-images;android-36;google_apis;x86_64" -d pixel_7
```

On an Apple Silicon Mac use `arm64-v8a` in place of `x86_64` in both commands.

Then, each time:

```bash
emulator -avd darkp2p
```

```bash
android/scripts/dev-emulator.sh
```

On Windows, in PowerShell:

```bash
powershell -ExecutionPolicy Bypass -File android\scripts\dev-emulator.ps1
```

The script forwards port 3000 from the emulator to this machine
(`adb reverse`), starts `server.js` there if it is not running, installs a
debug build whose default server is `http://127.0.0.1:3000`, and starts the
app. Open `http://127.0.0.1:3000/` in a desktop browser to be the other
device: paste the app's room link into the browser, or the browser's link into
the app.

What makes this work, and why it cannot leak into a release:

- `-Pdarkp2p.origin=...` changes the default server of **debug** builds only.
- Debug builds allow plain http to `127.0.0.1` and `localhost`
  (`app/src/debug/res/xml/network_security_config.xml`); release builds allow
  it for `.onion` only.

Neither script has been run yet; treat the first run as part of the test.

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
