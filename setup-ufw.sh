#!/usr/bin/env bash
set -euo pipefail

SSH_PORT="${SSH_PORT:-22}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo ./setup-ufw.sh" >&2
  exit 1
fi

if ! command -v ufw >/dev/null 2>&1; then
  apt-get update -qq
  apt-get install -y -qq ufw
fi

ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw limit "${SSH_PORT}/tcp" comment 'SSH (rate-limited)'
ufw allow 80/tcp comment 'Caddy HTTP'
ufw allow 443/tcp comment 'Caddy HTTPS/WSS'
ufw allow 443/udp comment 'Caddy HTTP/3'
ufw allow 3478/tcp comment 'coturn STUN/TURN TCP'
ufw allow 3478/udp comment 'coturn STUN/TURN UDP'
ufw allow 49160:49200/udp comment 'coturn relay ports'
ufw --force enable
ufw status verbose
