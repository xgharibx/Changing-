#!/usr/bin/env bash
# DEV ONLY: wipes catalogue and import data from wadeh_dev and reseeds synthetic orgs/projects. Refuses any other database.
set -euo pipefail
URL=${DEV_PSQL_URL:-postgresql://wadeh:wadeh_dev@localhost/wadeh_dev}
case "$URL" in *wadeh_dev*) ;; *) echo "refusing: not the dev database"; exit 1;; esac
psql "$URL" -q -c "TRUNCATE share_link, change_event, availability_observation, offer_version, payment_plan_version, source_record, import_batch, listing, unit_match_review, unit, building, phase, session_token CASCADE;"
cd "$(dirname "$0")/.." && DATABASE_URL="jdbc:postgresql://localhost:5432/wadeh_dev?user=wadeh&password=wadeh_dev" APP_ENV=dev server/build/install/server/bin/server seed
