#!/usr/bin/env bash
# Writes coturn/turnserver.conf from .env, so the TURN secret never appears on
# a command line or in docker-compose.yml. Re-run after changing TURN_HOST,
# TURN_PUBLIC_IP, TURN_LISTEN_IP, TURN_EXTERNAL_IP or TURN_SECRET in .env,
# then:
#   docker compose up -d --force-recreate coturn
# --force-recreate is required: the file is replaced by rename, a running
# container keeps the old one through its bind mount, and a plain `up -d`
# sees no change in docker-compose.yml and leaves the container alone. After
# a TURN_SECRET change also run `docker compose up -d darkp2p` (it signs the
# credentials with the same secret) and then `docker compose restart tor`
# (tor resolved the old darkp2p container's address when it started).
#
# Run this BEFORE the first `docker compose up`. If the file is missing,
# Docker creates a directory at coturn/turnserver.conf for the bind mount,
# and this script then refuses to write until that directory is removed.
#
# Usage: scripts/gen-turnserver-conf.sh [ENV_FILE] [OUT_FILE]
#   defaults: .env  coturn/turnserver.conf  (relative to the repo root)
#
# The file is written with mode 0600 and owned by the uid:gid coturn runs as
# inside its image (nobody:nogroup, 65534:65533), so run it with sudo.
# COTURN_OWNER overrides the owner; COTURN_OWNER= (empty) skips the chown.
set -euo pipefail

cd "$(dirname "$0")/.."
ENV_FILE="${1:-.env}"
OUT_FILE="${2:-coturn/turnserver.conf}"
COTURN_OWNER="${COTURN_OWNER-65534:65533}"

# Relay port range. setup-ufw.sh opens the same range: change both together.
MIN_PORT=49160
MAX_PORT=49200

die() { echo "gen-turnserver-conf: $*" >&2; exit 1; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE not found"

# `docker compose up` before this script makes Docker create the bind-mount
# source as a directory. mv would then put the config inside it and coturn
# would still start without one, so stop here.
if [ -d "$OUT_FILE" ]; then
  die "$OUT_FILE is a directory, not a file. Docker creates it when
  'docker compose up' runs before this script. Fix it with:
    docker compose rm -sf coturn
    sudo rmdir '$OUT_FILE'
  then re-run this script and: docker compose up -d coturn"
fi
if [ -e "$OUT_FILE" ] && [ ! -f "$OUT_FILE" ]; then
  die "$OUT_FILE exists and is not a regular file"
fi

# Read KEY=VALUE without sourcing the file (it is data, not a script). The
# last assignment wins, as with env_file; one pair of matching quotes is
# stripped.
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
TURN_PUBLIC_IP="$(env_get TURN_PUBLIC_IP)"
TURN_SECRET="$(env_get TURN_SECRET)"
# Optional, for a host whose public address is not on an interface (1:1 NAT:
# a DigitalOcean Reserved IP, AWS Elastic IP, ...). See the README.
TURN_LISTEN_IP="$(env_get TURN_LISTEN_IP)"
TURN_EXTERNAL_IP="$(env_get TURN_EXTERNAL_IP)"

is_ipv4() {
  local octet
  [[ "$1" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
  for octet in "${BASH_REMATCH[@]:1}"; do
    [ "$((10#$octet))" -le 255 ] || return 1
  done
}

[[ "$TURN_HOST" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$ ]] \
  || die "TURN_HOST must be set to a hostname"
# TURN_PUBLIC_IP is validated here but deliberately NOT denied as a peer:
# when both peers relay ("Hide my IP" on each side), each one's peer address
# is this server's own relay address, and a deny answers 403 Forbidden IP.
# Relaying is UDP-only, so this exposes only the host's public UDP services
# (coturn itself and Caddy's 443/udp). Do not deny "::" either: coturn 4.6.3
# then rejects every IPv4 peer. scripts/check-turn-relay.sh tests both paths.
is_ipv4 "$TURN_PUBLIC_IP" || die "TURN_PUBLIC_IP must be set to the server's public IPv4"
# TURN_LISTEN_IP: the local IPv4 coturn binds for listening and relaying
# (default: every interface). TURN_EXTERNAL_IP: the public IPv4 coturn puts in
# relay addresses when TURN_LISTEN_IP is a private address behind 1:1 NAT.
if [ -n "$TURN_LISTEN_IP" ]; then
  is_ipv4 "$TURN_LISTEN_IP" || die "TURN_LISTEN_IP must be an IPv4 address on this host (or empty)"
fi
if [ -n "$TURN_EXTERNAL_IP" ]; then
  is_ipv4 "$TURN_EXTERNAL_IP" || die "TURN_EXTERNAL_IP must be a public IPv4 address (or empty)"
  [ -n "$TURN_LISTEN_IP" ] \
    || die "TURN_EXTERNAL_IP needs TURN_LISTEN_IP (the private address it is mapped to)"
  [ "$TURN_EXTERNAL_IP" != "$TURN_LISTEN_IP" ] \
    || die "TURN_EXTERNAL_IP and TURN_LISTEN_IP are the same: leave TURN_EXTERNAL_IP empty"
fi
# Printable, no whitespace or '#', so it stays one value on one conf line.
[[ "$TURN_SECRET" =~ ^[!-~]{16,}$ && "$TURN_SECRET" != *"#"* ]] \
  || die "TURN_SECRET must be at least 16 printable characters without spaces or '#'"

out_dir="$(dirname "$OUT_FILE")"
mkdir -p "$out_dir"
umask 077
tmp="$(mktemp "$out_dir/.turnserver.conf.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

cat > "$tmp" <<EOF
# Generated by scripts/gen-turnserver-conf.sh from $ENV_FILE. Do not edit or
# commit: it holds TURN_SECRET. Re-run the script instead.
listening-port=3478
realm=$TURN_HOST
use-auth-secret
static-auth-secret=$TURN_SECRET
fingerprint
min-port=$MIN_PORT
max-port=$MAX_PORT
user-quota=4
total-quota=40
max-bps=5000000
bps-capacity=50000000
max-allocate-lifetime=1800
stale-nonce=600
no-cli
no-tls
no-dtls
no-software-attribute
no-multicast-peers
no-tcp-relay
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.0.0.0-192.0.0.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=198.18.0.0-198.19.255.255
denied-peer-ip=240.0.0.0-255.255.255.255
denied-peer-ip=::1
denied-peer-ip=::ffff:0.0.0.0-::ffff:255.255.255.255
denied-peer-ip=64:ff9b::-64:ff9b::ffff:ffff
denied-peer-ip=2002::-2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff
denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff
# coturn logs every client and peer address: keep logging off. simple-log
# stops coturn appending a date to log-file; without it, opening
# /dev/null_<date>.log fails and coturn falls back to /var/tmp.
no-stdout-log
simple-log
log-file=/dev/null
EOF
if [ -n "$TURN_LISTEN_IP" ]; then
  {
    echo "# From TURN_LISTEN_IP: bind this address only, for listening and relaying."
    echo "listening-ip=$TURN_LISTEN_IP"
    echo "relay-ip=$TURN_LISTEN_IP"
  } >> "$tmp"
fi
if [ -n "$TURN_EXTERNAL_IP" ]; then
  {
    echo "# From TURN_EXTERNAL_IP: the public address clients see for TURN_LISTEN_IP."
    echo "external-ip=$TURN_EXTERNAL_IP/$TURN_LISTEN_IP"
  } >> "$tmp"
fi

chmod 0600 "$tmp"
if [ -n "$COTURN_OWNER" ]; then
  chown "$COTURN_OWNER" "$tmp" 2>/dev/null \
    || die "cannot chown to $COTURN_OWNER (coturn's user); re-run with sudo"
fi
mv -f "$tmp" "$OUT_FILE"
trap - EXIT
echo "gen-turnserver-conf: wrote $OUT_FILE (mode 0600, owner ${COTURN_OWNER:-unchanged})"
