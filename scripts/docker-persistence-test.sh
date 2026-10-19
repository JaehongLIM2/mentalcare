#!/usr/bin/env bash
# Verifies that counseling data survives a container restart / recreate, that the port is bound to
# 127.0.0.1 only, and that the container is non-root. Runs in an ISOLATED compose project with a
# temporary data directory and its own port, so your real ./data and running container are untouched.
# Requires docker compose. Run from the repository root:
#   bash scripts/docker-persistence-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT=mentalcare-test
export MENTALCARE_HOST_PORT="${MENTALCARE_TEST_PORT:-18080}"
export MENTALCARE_HOST_DATA="$(mktemp -d "${TMPDIR:-/tmp}/mentalcare-test-XXXXXX")"
BASE="http://127.0.0.1:$MENTALCARE_HOST_PORT"
compose() { docker compose -p "$PROJECT" "$@"; }

cleanup() {
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$MENTALCARE_HOST_DATA"
}
trap cleanup EXIT

wait_ready() {
  for _ in $(seq 1 60); do
    if curl -fs "$BASE/api/health" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "server did not become ready"; compose logs --tail=50; exit 1
}

echo "== build & start (project=$PROJECT, port=$MENTALCARE_HOST_PORT, data=$MENTALCARE_HOST_DATA)"
compose up -d --build
wait_ready

echo "== create a session with a check-in"
ID=$(curl -fs -X POST "$BASE/api/sessions" -H 'Content-Type: application/json' \
  -d '{"checkIn":{"mood":6,"stress":7,"note":"persistence-test"}}' | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "session id=$ID"
curl -fs -X POST "$BASE/api/sessions/$ID/complete" >/dev/null || true

echo "== port must not be reachable on a non-loopback interface"
HOST_IP=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
if [ -n "${HOST_IP:-}" ] && curl -s -m 3 "http://$HOST_IP:$MENTALCARE_HOST_PORT/api/health" >/dev/null 2>&1; then
  echo "FAIL: port $MENTALCARE_HOST_PORT is reachable on $HOST_IP"; exit 1
fi
echo "ok: not reachable on LAN interface (${HOST_IP:-n/a})"

echo "== Host header guard"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: evil.example.com' "$BASE/api/health")
[ "$CODE" = "403" ] || { echo "FAIL: expected 403 for foreign Host, got $CODE"; exit 1; }
echo "ok: foreign Host rejected"

echo "== restart container"
compose restart
wait_ready
curl -fs "$BASE/api/sessions/$ID" | grep -q '"id":'"$ID" || { echo "FAIL: session lost after restart"; exit 1; }

echo "== recreate container (down/up) - data must survive"
compose down
compose up -d
wait_ready
curl -fs "$BASE/api/sessions/$ID" | grep -q 'persistence-test' || { echo "FAIL: session lost after recreate"; exit 1; }
echo "ok: data persisted in $MENTALCARE_HOST_DATA"

echo "== container runs as non-root"
UID_IN=$(compose exec -T mentalcare id -u)
[ "$UID_IN" != "0" ] || { echo "FAIL: running as root"; exit 1; }
echo "ok: uid $UID_IN"

echo "ALL OK (temporary project and data removed on exit)"
