#!/usr/bin/env bash
# Builds and runs the API + console against the development database on :8080.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew -q :server:installDist
export DATABASE_URL=${DATABASE_URL:-"jdbc:postgresql://localhost:5432/wadeh_dev?user=wadeh&password=wadeh_dev"}
export APP_ENV=${APP_ENV:-dev}
server/build/install/server/bin/server seed
exec server/build/install/server/bin/server
