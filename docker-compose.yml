services:
  caddy:
    image: caddy:2-alpine
    container_name: darkp2p-caddy
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    environment:
      - DOMAIN=${DOMAIN}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      darkp2p:
        condition: service_healthy
    networks:
      - internal

  darkp2p:
    build: .
    container_name: darkp2p
    restart: unless-stopped
    read_only: true
    cap_drop:
      - ALL
    environment:
      - PORT=3000
      - TURN_HOST=${TURN_HOST}
      - TURN_SECRET=${TURN_SECRET}
      - TURN_TTL=${TURN_TTL:-3600}
      - PRO_TOKEN_SECRET=${PRO_TOKEN_SECRET}
      - PRO_TOKEN_DAYS=${PRO_TOKEN_DAYS:-30}
      - BTCPAY_WEBHOOK_SECRET=${BTCPAY_WEBHOOK_SECRET}
    networks:
      - internal

  coturn:
    image: coturn/coturn:4.6-alpine
    container_name: darkp2p-coturn
    restart: unless-stopped
    network_mode: host
    read_only: true
    tmpfs:
      - /var/tmp
      - /var/lib/coturn
    command:
      - --listening-port=3478
      - --realm=${TURN_HOST}
      - --use-auth-secret
      - --static-auth-secret=${TURN_SECRET}
      - --fingerprint
      - --min-port=49160
      - --max-port=49200
      - --user-quota=4
      - --total-quota=100
      - --max-bps=5000000
      - --bps-capacity=50000000
      - --max-allocate-lifetime=1800
      - --stale-nonce=600
      - --no-cli
      - --no-tls
      - --no-dtls
      - --no-software-attribute
      - --no-multicast-peers
      - --no-loopback-peers
      - --denied-peer-ip=10.0.0.0-10.255.255.255
      - --denied-peer-ip=100.64.0.0-100.127.255.255
      - --denied-peer-ip=169.254.0.0-169.254.255.255
      - --denied-peer-ip=172.16.0.0-172.31.255.255
      - --denied-peer-ip=192.168.0.0-192.168.255.255
      - --denied-peer-ip=::1
      - --denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
      - --denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff
      - --log-file=stdout

networks:
  internal:
    driver: bridge

volumes:
  caddy_data:
  caddy_config:
