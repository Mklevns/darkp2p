#!/usr/bin/env bash
# Deploy check: proves the running coturn actually relays, which "the
# container is up" does not. Run it on the server after
# `docker compose up -d coturn` and after every change to
# scripts/gen-turnserver-conf.sh or the coturn image.
#
#   1. peer:           client -> relay -> a UDP echo peer (turnutils_peer)
#                      on this host's public address
#   2. relay-to-relay: two clients, each sending to the other's relay
#                      address (both browsers on "Hide my IP")
#
# Either one failing with "403 Forbidden IP" means a denied-peer-ip rule is
# rejecting legitimate peers.
#
# Usage: scripts/check-turn-relay.sh [ENV_FILE]      (default: .env)
#   TURN_CHECK_SERVER  address to test instead of TURN_LISTEN_IP (when that
#                      is set in the env file) or TURN_PUBLIC_IP
#   TURN_CHECK_PORT    coturn's listening port (default 3478)
#   PEER_PORT          UDP port for the temporary echo peer (default 3480)
#
# It uses the coturn image pinned in docker-compose.yml (which ships both
# turnutils tools) with host networking, and reads TURN_HOST, TURN_PUBLIC_IP
# and TURN_SECRET from the env file without sourcing it. The secret reaches
# the client container through the environment; inside that container it is a
# turnutils_uclient argument for the few seconds the check runs. Nothing is
# written to disk and the secret is never printed.
#
# The check runs on the server itself, so the address it tests must be on one
# of this host's interfaces: with TURN_LISTEN_IP set (a Reserved IP or other
# 1:1 NAT, where the public address is not), that is the address used. It
# then proves that coturn relays, not that the public address reaches it:
# test that from outside (see the README).
set -euo pipefail

cd "$(dirname "$0")/.."
ENV_FILE="${1:-.env}"
PEER_PORT="${PEER_PORT:-3480}"
TURN_CHECK_PORT="${TURN_CHECK_PORT:-3478}"
MESSAGES=5

die() { echo "check-turn-relay: $*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "docker not found"
[ -f "$ENV_FILE" ] || die "$ENV_FILE not found"

# Same parser as gen-turnserver-conf.sh: last assignment wins, one pair of
# matching quotes is stripped.
env_get() {
  local key="$1" line val=""
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in
      "$key="*) val="${line#"$key="}" ;;
    esac
  done < "$ENV_FILE"
  case "$val" in
    \"*\") val="${val#\"}"; val="${val%\"}" ;;
    \'*\') val="${val#\'}"; val="${val%\'}" ;;
  esac
  printf '%s' "$val"
}

TURN_HOST="$(env_get TURN_HOST)"
TURN_LISTEN_IP="$(env_get TURN_LISTEN_IP)"
SERVER="${TURN_CHECK_SERVER:-${TURN_LISTEN_IP:-$(env_get TURN_PUBLIC_IP)}}"
TURN_SECRET="$(env_get TURN_SECRET)"
[ -n "$TURN_HOST" ] || die "TURN_HOST is not set in $ENV_FILE"
[ -n "$TURN_SECRET" ] || die "TURN_SECRET is not set in $ENV_FILE"
[[ "$SERVER" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] \
  || die "TURN_PUBLIC_IP (or TURN_LISTEN_IP / TURN_CHECK_SERVER) must be an IPv4 address"
[[ "$PEER_PORT" =~ ^[0-9]{1,5}$ && "$TURN_CHECK_PORT" =~ ^[0-9]{1,5}$ ]] \
  || die "PEER_PORT and TURN_CHECK_PORT must be port numbers"

IMAGE="$(sed -n 's/^[[:space:]]*image:[[:space:]]*\(coturn\/coturn[^[:space:]]*\).*$/\1/p' docker-compose.yml | head -n 1)"
[ -n "$IMAGE" ] || die "no coturn image found in docker-compose.yml"

PEER_NAME="darkp2p-turn-check-peer-$$"
cleanup() { docker rm -f "$PEER_NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# $1 = label, $2 = messages expected back, rest = extra turnutils_uclient args.
uclient() {
  local label="$1" want="$2" out
  shift 2
  export TURN_SECRET
  out="$(docker run --rm --network host -e TURN_SECRET --entrypoint sh "$IMAGE" -c \
    'exec turnutils_uclient -c -m 1 -l 100 -W "$TURN_SECRET" "$@"' sh \
    -n "$MESSAGES" -p "$TURN_CHECK_PORT" "$@" "$SERVER" 2>&1 </dev/null)" || true
  if printf '%s\n' "$out" | grep -q "tot_send_msgs=$want, tot_recv_msgs=$want\$"; then
    echo "check-turn-relay: PASS  $label"
    return 0
  fi
  echo "check-turn-relay: FAIL  $label" >&2
  if printf '%s\n' "$out" | grep -q 'Forbidden IP'; then
    echo "  coturn answered 403 Forbidden IP: a denied-peer-ip rule rejects this peer" >&2
  elif printf '%s\n' "$out" | grep -q 'Allocation Quota Reached'; then
    echo "  coturn's allocation quota is used up (a busy server, or allocations left by a" >&2
    echo "  failed check just before): wait a minute and run the check again" >&2
  elif printf '%s\n' "$out" | grep -q 'Cannot complete Allocation'; then
    echo "  coturn refused the allocation: is it running with the current TURN_SECRET?" >&2
    echo "  (re-run scripts/gen-turnserver-conf.sh, then: docker compose up -d --force-recreate coturn)" >&2
  else
    echo "  no reply through $SERVER:$TURN_CHECK_PORT: is coturn running and reachable?" >&2
  fi
  return 1
}

failed=0

docker run -d --rm --name "$PEER_NAME" --network host --entrypoint turnutils_peer \
  "$IMAGE" -L "$SERVER" -p "$PEER_PORT" >/dev/null \
  || die "could not start the echo peer on $SERVER:$PEER_PORT/udp"
sleep 1
uclient "peer (client -> relay -> echo peer)" "$MESSAGES" -e "$SERVER" -r "$PEER_PORT" || failed=1
cleanup

# -y: the two clients talk to each other through their relay addresses.
uclient "relay-to-relay (both peers on TURN)" "$((MESSAGES * 2))" -y || failed=1

if [ "$failed" -ne 0 ]; then
  die "coturn does NOT relay correctly; TURN and \"Hide my IP\" will not work"
fi
echo "check-turn-relay: OK, coturn relays to ordinary peers and relay-to-relay"
