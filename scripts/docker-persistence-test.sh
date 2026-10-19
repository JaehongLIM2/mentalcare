#!/usr/bin/env bash
# Verifies that counseling data survives a container restart / recreate, and that the port is
# bound to 127.0.0.1 only. Requires docker compose. Run from the repository root:
#   bash scripts/docker-persistence-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."

BASE=http://127.0.0.1:8080
export MENTALCARE_DATA_DIR_OVERRIDE=""

wait_ready() {
  for _ in $(seq 1 60); do
    if curl -fs "$BASE/api/home" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "server did not become ready"; docker compose logs --tail=50; exit 1
}

echo "== build & start"
docker compose up -d --build
wait_ready

echo "== create a session with a check-in"
ID=$(curl -fs -X POST "$BASE/api/sessions" -H 'Content-Type: application/json' \
  -d '{"checkIn":{"mood":6,"stress":7,"note":"persistence-test"}}' | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "session id=$ID"
curl -fs -X POST "$BASE/api/sessions/$ID/complete" >/dev/null || true

echo "== port must not be reachable on a non-loopback interface"
HOST_IP=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
if [ -n "${HOST_IP:-}" ] && curl -s -m 3 "http://$HOST_IP:8080/api/home" >/dev/null 2>&1; then
  echo "FAIL: port 8080 is reachable on $HOST_IP"; exit 1
fi
echo "ok: not reachable on LAN interface (${HOST_IP:-n/a})"

echo "== Host header guard"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: evil.example.com' "$BASE/api/home")
[ "$CODE" = "403" ] || { echo "FAIL: expected 403 for foreign Host, got $CODE"; exit 1; }
echo "ok: foreign Host rejected"

echo "== restart container"
docker compose restart
wait_ready
curl -fs "$BASE/api/sessions/$ID" | grep -q '"id":'"$ID" || { echo "FAIL: session lost after restart"; exit 1; }

echo "== recreate container (down/up) – data must survive"
docker compose down
docker compose up -d
wait_ready
curl -fs "$BASE/api/sessions/$ID" | grep -q 'persistence-test' || { echo "FAIL: session lost after recreate"; exit 1; }
echo "ok: data persisted in ./data"

echo "== container runs as non-root"
UID_IN=$(docker compose exec -T mentalcare id -u)
[ "$UID_IN" != "0" ] || { echo "FAIL: running as root"; exit 1; }
echo "ok: uid $UID_IN"

docker compose down
echo "ALL OK"
