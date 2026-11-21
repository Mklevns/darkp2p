# Pinned by tag and multi-arch index digest. To update, pick an exact
# 22.x.y-alpine3.xx tag (https://hub.docker.com/_/node/tags), then take the
# top-level "Digest:" line from:
#   docker buildx imagetools inspect node:<tag>
FROM node:22.23.5-alpine3.24@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a

ENV NODE_ENV=production

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY server.js ./
COPY public ./public

USER node
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:3000/health | grep -q "^ok$" || exit 1

CMD ["node", "server.js"]
