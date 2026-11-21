#!/usr/bin/env bash
set -euo pipefail

ufw default deny incoming
ufw default allow outgoing

ufw limit 22/tcp comment 'SSH (rate-limited)'
ufw allow 80/tcp comment 'Caddy HTTP'
ufw allow 443/tcp comment 'Caddy HTTPS'
ufw allow 443/udp comment 'Caddy HTTP/3'
ufw allow 3478/tcp comment 'coturn STUN/TURN TCP'
ufw allow 3478/udp comment 'coturn STUN/TURN UDP'
ufw allow 49160:49200/udp comment 'coturn media relay'

ufw --force enable
ufw status verbose
