#!/usr/bin/env bash
# One-time setup on the server: writes .env with random secrets and an nginx site.
# Usage: ./setup.sh call.example.com
set -euo pipefail
cd "$(dirname "$0")"
DOMAIN="${1:-}"
[ -n "$DOMAIN" ] || { echo "usage: ./setup.sh <your-domain>   (e.g. call.example.com)"; exit 1; }
[ -f .env ] && { echo ".env already exists, not overwriting"; exit 1; }

IP="$(curl -fsS https://api.ipify.org || hostname -I | awk '{print $1}')"
rnd() { openssl rand -hex "$1"; }
ADMIN_PATH="$(rnd 16)"; ADMIN_PASSWORD="$(rnd 12)"; DL_PATH="$(rnd 8)"; CODE="$(shuf -i 100000-999999 -n 1)"

umask 077
cat > .env <<ENV
ADMIN_PASSWORD=$ADMIN_PASSWORD
ADMIN_PATH=$ADMIN_PATH
DL_PATH=$DL_PATH
JOIN_CODE=$CODE
TURN_SECRET=$(rnd 32)
TURN_URLS=turn:$IP:3478?transport=udp,turn:$IP:3478?transport=tcp
TURN_EXTERNAL_IP=$IP
TURN_REALM=$DOMAIN
ENV
mkdir -p data secrets && chown 65534:65534 data 2>/dev/null || true

sed "s/call.example.com/$DOMAIN/" nginx-call.conf > "nginx-$DOMAIN.conf"

cat <<MSG

Done. Next steps:
  1. DNS: point $DOMAIN to $IP
  2. nginx:   sudo cp nginx-$DOMAIN.conf /etc/nginx/sites-enabled/ && sudo nginx -t && sudo systemctl reload nginx
  3. HTTPS:   sudo certbot --nginx -d $DOMAIN
  4. Firewall: open 443/tcp, 3478/udp+tcp, 49160-49200/udp
  5. Start:   docker compose up -d --build

Admin panel:  https://$DOMAIN/$ADMIN_PATH/
Password:     $ADMIN_PASSWORD
Join code:    $CODE      (first group, more groups in the admin panel)

Keep .env private. These values are only shown now; they stay in .env.
MSG
