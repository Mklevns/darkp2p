#!/usr/bin/env bash
# Host firewall for the darkp2p stack.
#
# Docker-published ports BYPASS UFW: Docker inserts its own iptables rules
# ahead of UFW's, so caddy's published 80/443 are reachable whatever UFW says.
# Publish only what must be public in docker-compose.yml. coturn uses host
# networking, so its ports below are governed by UFW.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "setup-ufw: must run as root (sudo $0)" >&2
  exit 1
fi

SSH_PORT="${SSH_PORT:-22}"
# coturn relay range: keep in sync with MIN_PORT/MAX_PORT in
# scripts/gen-turnserver-conf.sh.
TURN_MIN_PORT=49160
TURN_MAX_PORT=49200

# Without IPV6=yes, UFW leaves IPv6 unfiltered.
UFW_DEFAULTS=/etc/default/ufw
if [ -f "$UFW_DEFAULTS" ]; then
  if grep -q '^IPV6=' "$UFW_DEFAULTS"; then
    sed -i 's/^IPV6=.*/IPV6=yes/' "$UFW_DEFAULTS"
  else
    echo 'IPV6=yes' >> "$UFW_DEFAULTS"
  fi
fi
if ! grep -qx 'IPV6=yes' "$UFW_DEFAULTS" 2>/dev/null; then
  echo "setup-ufw: WARNING: could not set IPV6=yes in $UFW_DEFAULTS; IPv6 is NOT filtered" >&2
fi

ufw default deny incoming
ufw default allow outgoing

ufw limit "${SSH_PORT}/tcp" comment 'SSH (rate-limited)'
ufw allow 80/tcp comment 'Caddy HTTP'
ufw allow 443/tcp comment 'Caddy HTTPS'
ufw allow 443/udp comment 'Caddy HTTP/3'
ufw allow 3478/tcp comment 'coturn STUN/TURN TCP'
ufw allow 3478/udp comment 'coturn STUN/TURN UDP'
ufw allow "${TURN_MIN_PORT}:${TURN_MAX_PORT}/udp" comment 'coturn media relay'

ufw --force enable
ufw status verbose
