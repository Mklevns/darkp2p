#!/usr/bin/env sh
# Runs the app on a running emulator (or a USB device) against a server.js on
# this machine. Needs: Node 22, JDK 17+, the Android SDK (ANDROID_HOME), and an
# emulator already started. See android/README.md, "Run on an emulator".
#
#   android/scripts/dev-emulator.sh            # build, install, start
#   PORT=3100 android/scripts/dev-emulator.sh  # another port
set -eu

PORT="${PORT:-3000}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}/platform-tools/adb"
ORIGIN="http://127.0.0.1:${PORT}"

"$ADB" get-state >/dev/null 2>&1 || { echo "No emulator or device is connected (adb devices)." >&2; exit 1; }

# The emulator's own 127.0.0.1:PORT now reaches this machine's PORT.
"$ADB" reverse "tcp:${PORT}" "tcp:${PORT}"

if ! curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
  echo "Starting server.js on port ${PORT} (stop it with: kill \$(cat /tmp/darkp2p-dev-server.pid))"
  (cd "$ROOT" && [ -d node_modules ] || npm ci)
  # DOMAIN is only used for links the server prints; the app and the page talk to 127.0.0.1.
  (cd "$ROOT" && PORT="$PORT" DOMAIN=localhost nohup node server.js >/tmp/darkp2p-dev-server.log 2>&1 & echo $! >/tmp/darkp2p-dev-server.pid)
  sleep 1
  curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null || { echo "server.js did not start; see /tmp/darkp2p-dev-server.log" >&2; exit 1; }
fi

(cd "$ROOT/android" && ./gradlew :app:installFullDebug "-Pdarkp2p.origin=${ORIGIN}")
"$ADB" shell am start -n com.darkp2p.app/.MainActivity >/dev/null

cat <<MSG

The app is running. To test with the web page as the other device:
  1. In the app, tap "New room" and then "Copy" (or read the link on screen).
  2. Open the same link in a browser on this machine: ${ORIGIN}/#<room>:<key>
     (get it off the emulator with:  $ADB shell cmd clipboard get  - or type it)
Or the other way round: open ${ORIGIN}/ in a browser, copy its link, and paste it into the app.

App output:   $ADB logcat --pid=\$($ADB shell pidof com.darkp2p.app)
Server log:   /tmp/darkp2p-dev-server.log
MSG
