FROM alpine:3.23.8

# Exact package version from the v3.23 repository. To update, check
# https://pkgs.alpinelinux.org/packages?name=tor&branch=v3.23 and bump both.
RUN apk add --no-cache tor=0.4.9.17-r0 \
 && mkdir -p /var/lib/tor/hidden_service \
 && chown -R tor:tor /var/lib/tor \
 && chmod 700 /var/lib/tor /var/lib/tor/hidden_service

# A fresh tor_data volume copies this directory, with its tor ownership, on
# first mount. A volume created by the old root-run container must be chowned
# once; see the comment on the tor service in docker-compose.yml.
VOLUME /var/lib/tor

USER tor

CMD ["tor", "-f", "/etc/tor/torrc"]
