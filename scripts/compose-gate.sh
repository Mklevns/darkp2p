#!/bin/sh
# Gate: validate docker-compose.yml with dummy values.
# docker-compose.yml requires a .env next to it (env_file), so a temporary one
# is copied from the test fixture and always removed on exit. An existing .env
# is never touched: the gate refuses to run instead.
set -eu

cd "$(dirname "$0")/.."
FIXTURE=test/fixtures/compose.env

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "compose-gate: SKIPPED (tool unavailable: docker compose)"
  exit 0
fi

created=0
cleanup() {
  if [ "$created" -eq 1 ]; then rm -f .env; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# noclobber makes creation atomic: it fails rather than overwrite a real .env.
if ! (set -C; cat "$FIXTURE" > .env) 2>/dev/null; then
  echo "compose-gate: .env already exists; refusing to overwrite it" >&2
  exit 2
fi
created=1

docker compose --env-file "$FIXTURE" config -q
echo "compose-gate: docker compose config OK"
