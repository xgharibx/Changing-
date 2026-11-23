#!/usr/bin/env bash
# Milestone 1 walk-through through the real console endpoints (no shortcuts into the database):
# operator uploads -> reviewer publishes -> operator uploads a revision -> reviewer publishes -> operator replays the revision.
# Requires the server running on $BASE (ops/run-server.sh). All data is synthetic.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
PW=${SEED_PASSWORD:-dev-only-password}
DIR="$(cd "$(dirname "$0")/.." && pwd)/fixtures/m1"
J=$(mktemp -d)
login() { curl -s -c "$J/$1" -o /dev/null -w "%{http_code}" -d "email=$2&password=$PW" "$BASE/console/login"; }
upload() { curl -s -b "$J/operator" -o /dev/null -w "%{redirect_url}" -F org=00000000-0000-4000-8000-000000000001 -F mode=partial -F "file=@$1;type=text/csv" "$BASE/console/imports"; }
publish() { curl -s -b "$J/reviewer" -o /dev/null -w "%{redirect_url}" -d "note=$2" "$BASE/console/imports/$1/publish"; }
batch_of() { echo "$1" | sed -E 's#.*/imports/([0-9a-f-]+).*#\1#'; }

if [ "${SKIP_FIRST:-0}" != 1 ]; then
echo "login operator: $(login operator operator@test.wadeh.invalid)  reviewer: $(login reviewer reviewer@test.wadeh.invalid)"
U1=$(upload "$DIR/01-gardens-2026-11-03.csv"); B1=$(batch_of "$U1"); echo "1) staged  $B1"
echo "   operator tries to publish own batch -> $(curl -s -b "$J/operator" -o /dev/null -w "%{redirect_url}" -d note=x "$BASE/console/imports/$B1/publish" | sed 's/.*err=//' | python3 -c 'import sys,urllib.parse;print(urllib.parse.unquote(sys.stdin.read()))' | head -c 90)"
echo "   published -> $(publish "$B1" first-sheet | sed 's/.*ok=//' | python3 -c 'import sys,urllib.parse;print(urllib.parse.unquote(sys.stdin.read()))')"
echo "   API now lists: $(curl -s "$BASE/v1/listings" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["items"]))') offers"
if [ "${PAUSE:-0}" = 1 ]; then read -rp "Inspect the phone, then press Enter to publish the revision… " _; fi
if [ "${STOP_AFTER_FIRST:-0}" = 1 ]; then exit 0; fi
fi
[ "${SKIP_FIRST:-0}" = 1 ] && { login operator operator@test.wadeh.invalid >/dev/null; login reviewer reviewer@test.wadeh.invalid >/dev/null; }
U2=$(upload "$DIR/02-gardens-2026-11-10-revision.csv"); B2=$(batch_of "$U2"); echo "2) staged revision $B2 -> published $(publish "$B2" price-revision | sed 's/.*ok=//')"
U3=$(upload "$DIR/02-gardens-2026-11-10-revision.csv"); echo "3) replay of the same revision -> $U3"
cp "$DIR/02-gardens-2026-11-10-revision.csv" "$J/renamed-final.csv"; U4=$(upload "$J/renamed-final.csv"); echo "4) replay under a new filename -> $U4"
ID=$(curl -s "$BASE/v1/listings?q=B12-0304" | python3 -c 'import sys,json; print(json.load(sys.stdin)["items"][0]["id"])')
curl -s "$BASE/v1/listings/$ID" | python3 -c '
import sys,json; d=json.load(sys.stdin); ch=[c for c in d["changes"] if c["kind"]!="offer_published"]
print("5) B12-0304: version", d["card"]["offerVersion"], "| changes visible to buyer:", len(ch)); [print("   ", c["kind"], c["field"], c["oldValue"], "->", c["newValue"], "like-for-like:", c["likeForLike"]) for c in ch]'
echo "listing id: $ID"
