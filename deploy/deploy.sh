#!/usr/bin/env bash
# Build locally and ship only the binary + systemd units to the server.
# Does NOT touch Caddy — see caddy-add.sh for that one-time step.
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
VERSION="$(date -u +%Y%m%d-%H%M%S)"
BIN="$ROOT/bin/dialife-linux-amd64"

echo "==> test"
(cd "$ROOT" && go test ./...)

echo "==> build $VERSION"
(cd "$ROOT" && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
	go build -trimpath -ldflags="-s -w -X main.version=$VERSION" -o "$BIN" ./cmd/dialife)

echo "==> upload"
/usr/bin/scp -q "$BIN" "$HOST:/tmp/dialife.new"
/usr/bin/scp -q "$ROOT/deploy/dialife.service" "$ROOT/deploy/dialife-backup.service" "$ROOT/deploy/dialife-backup.timer" "$HOST:/tmp/"

echo "==> install"
/usr/bin/ssh "$HOST" 'bash -s' <<'EOF'
set -euo pipefail
id dialife >/dev/null 2>&1 || useradd --system --home-dir /opt/dialife --shell /usr/sbin/nologin dialife
install -d -o root    -g root    -m 755 /opt/dialife /opt/dialife/bin
install -d -o dialife -g dialife -m 750 /opt/dialife/data

install -o root -g root -m 755 /tmp/dialife.new /opt/dialife/bin/dialife.new
mv -f /opt/dialife/bin/dialife.new /opt/dialife/bin/dialife
for u in dialife.service dialife-backup.service dialife-backup.timer; do
	install -o root -g root -m 644 "/tmp/$u" "/etc/systemd/system/$u"
	rm -f "/tmp/$u"
done
rm -f /tmp/dialife.new

systemctl daemon-reload
systemctl enable --quiet dialife
systemctl enable --quiet --now dialife-backup.timer
systemctl restart dialife
sleep 1
systemctl is-active dialife
/opt/dialife/bin/dialife version
EOF
echo "==> done ($VERSION)"
