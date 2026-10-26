#!/usr/bin/env bash
# One-time: add a site block to the server's Caddyfile (CADDYFILE in deploy/dialife.conf).
# Backs up the file, validates the result and only then reloads (graceful).
# On validation failure the original file is restored and Caddy is not touched.
#
#   deploy/caddy-add.sh                                      # the API at DIALIFE_DOMAIN
#   deploy/caddy-add.sh deploy/ntfy.Caddyfile.snippet ntfy   # ntfy at DIALIFE_NTFY_DOMAIN
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONF="$ROOT/deploy/dialife.conf"
if [ ! -f "$CONF" ]; then
	echo "missing $CONF: copy deploy/dialife.conf.example and fill it in" >&2
	exit 1
fi
# shellcheck source=/dev/null
. "$CONF"
HOST="$DIALIFE_HOST"
SNIPPET="${1:-$ROOT/deploy/Caddyfile.snippet}"
DOMAIN="$DIALIFE_DOMAIN"
if [ "${2:-}" = ntfy ]; then DOMAIN="$DIALIFE_NTFY_DOMAIN"; fi

# the snippet names its site __DOMAIN__
sed "s/__DOMAIN__/$DOMAIN/" "$SNIPPET" | /usr/bin/ssh "$HOST" 'cat > /tmp/dialife.caddy'

/usr/bin/ssh "$HOST" "DOMAIN='$DOMAIN' CF='$CADDYFILE' SVC='$CADDY_SERVICE' bash -s" <<'REMOTE'
set -euo pipefail
if grep -qF "$DOMAIN {" "$CF" || grep -qF "$DOMAIN," "$CF"; then
	echo "block already present, nothing to do"
	rm -f /tmp/dialife.caddy
	exit 0
fi

BAK="$CF.bak-$(date +%Y%m%d-%H%M%S)"
cp -p "$CF" "$BAK"
cat /tmp/dialife.caddy >> "$CF"
rm -f /tmp/dialife.caddy

if ! caddy validate --config "$CF" --adapter caddyfile; then
	echo "validation FAILED, restoring $BAK"
	cp -p "$BAK" "$CF"
	exit 1
fi

systemctl reload "$SVC"
echo "caddy reloaded, backup at $BAK"
REMOTE
