#!/usr/bin/env bash
#
# AniBlaze API proxy — VPS setup (Caddy reverse proxy, automatic HTTPS).
#
# Run on a FRESH Ubuntu 22.04/24.04 VPS that is OUTSIDE Russia (so it can reach
# api.anixart.tv) but reachable FROM Russia (a clean, non-Cloudflare IP). Point a
# domain's A-record at this server first, then:
#
#   PROXY_DOMAIN=proxy.example.com bash setup-vps.sh
#
# When it finishes, give that domain back so it can go into the app's PROXY_HOSTS.
set -euo pipefail

: "${PROXY_DOMAIN:?Set PROXY_DOMAIN=your.domain (A-record must already point at this server)}"

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg

curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
apt-get update
apt-get install -y caddy

cat > /etc/caddy/Caddyfile <<EOF
${PROXY_DOMAIN} {
    reverse_proxy https://api.anixart.tv {
        header_up Host api.anixart.tv
    }
    encode gzip
}
EOF

systemctl restart caddy
sleep 3
echo
echo "==> Caddy is up. Verifying (give it a few seconds for the TLS cert)…"
echo "    curl -s -X POST https://${PROXY_DOMAIN}/filter/0?perPage=2 -H 'Content-Type: application/json' -d '{\"sort\":4}' | head -c 200"
