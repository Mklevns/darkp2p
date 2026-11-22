#!/bin/sh
# Submits every URL in the live sitemap to IndexNow (Bing, Yandex, Seznam,
# Naver and others). Run it by hand after a deploy that changed page content:
#
#   scripts/indexnow.sh                 # uses DOMAIN from the environment or .env
#   DOMAIN=example.com scripts/indexnow.sh
#   scripts/indexnow.sh --dry-run       # print the request, send nothing
#
# The key is the name of public/<32 hex>.txt, which the server publishes at
# https://<DOMAIN>/<key>.txt. Deploy before running: IndexNow fetches that file
# to check that the key belongs to the host. Nothing runs this automatically.
set -eu

cd "$(dirname "$0")/.."

DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

if [ -z "${DOMAIN:-}" ] && [ -f .env ]; then
  DOMAIN=$(sed -n 's/^DOMAIN=//p' .env | tail -n 1)
fi
DOMAIN=${DOMAIN:-darkp2p.com}

KEY=
for f in public/*.txt; do
  name=$(basename "$f" .txt)
  if printf '%s' "$name" | grep -Eq '^[a-f0-9]{32}$'; then
    KEY=$name
    break
  fi
done
if [ -z "$KEY" ]; then
  echo "indexnow: no key file (public/<32 hex>.txt) found" >&2
  exit 1
fi
if [ "$(tr -d '\r\n' < "public/$KEY.txt")" != "$KEY" ]; then
  echo "indexnow: public/$KEY.txt must contain exactly its own name (the key)" >&2
  exit 1
fi

BASE="https://$DOMAIN"

if ! SITEMAP=$(curl -fsS --max-time 20 "$BASE/sitemap.xml"); then
  echo "indexnow: could not fetch $BASE/sitemap.xml" >&2
  exit 1
fi
URLS=$(printf '%s\n' "$SITEMAP" | grep -o '<loc>[^<]*</loc>' | sed -e 's/^<loc>//' -e 's/<\/loc>$//')
if [ -z "$URLS" ]; then
  echo "indexnow: no <loc> entries in $BASE/sitemap.xml" >&2
  exit 1
fi

if [ "$DRY_RUN" -eq 0 ]; then
  if [ "$(curl -fsS --max-time 20 "$BASE/$KEY.txt" | tr -d '\r\n')" != "$KEY" ]; then
    echo "indexnow: $BASE/$KEY.txt does not serve the key yet (deploy first)" >&2
    exit 1
  fi
fi

# Sitemap URLs contain no characters that need JSON escaping.
LIST=$(printf '%s\n' "$URLS" | sed -e 's/^/    "/' -e 's/$/",/' | sed -e '$ s/,$//')
BODY=$(printf '{\n  "host": "%s",\n  "key": "%s",\n  "keyLocation": "%s/%s.txt",\n  "urlList": [\n%s\n  ]\n}\n' \
  "$DOMAIN" "$KEY" "$BASE" "$KEY" "$LIST")

if [ "$DRY_RUN" -eq 1 ]; then
  echo "indexnow: dry run, would POST to https://api.indexnow.org/indexnow:"
  printf '%s\n' "$BODY"
  exit 0
fi

STATUS=$(printf '%s' "$BODY" | curl -sS --max-time 30 -o /dev/null -w '%{http_code}' \
  -X POST 'https://api.indexnow.org/indexnow' \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @-)

case "$STATUS" in
  200|202) echo "indexnow: submitted $(printf '%s\n' "$URLS" | wc -l | tr -d ' ') URL(s) for $DOMAIN (HTTP $STATUS)" ;;
  *) echo "indexnow: IndexNow answered HTTP $STATUS" >&2; exit 1 ;;
esac
