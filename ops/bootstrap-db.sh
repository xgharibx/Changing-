#!/usr/bin/env bash
# Creates the local development and test databases with PostGIS. Idempotent. Run as a user that can sudo to postgres.
set -euo pipefail
PSQL_SUPER=${PSQL_SUPER:-"sudo -u postgres psql"}
command -v pg_ctlcluster >/dev/null && (pg_lsclusters | grep -q online || sudo service postgresql start)
$PSQL_SUPER -tc "SELECT 1 FROM pg_roles WHERE rolname='wadeh'" | grep -q 1 || $PSQL_SUPER -c "CREATE ROLE wadeh LOGIN PASSWORD 'wadeh_dev' CREATEDB;"
for db in wadeh_dev wadeh_test; do
  $PSQL_SUPER -tc "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1 || $PSQL_SUPER -c "CREATE DATABASE $db OWNER wadeh;"
  $PSQL_SUPER -d $db -c "CREATE EXTENSION IF NOT EXISTS postgis; CREATE EXTENSION IF NOT EXISTS pgrouting; CREATE EXTENSION IF NOT EXISTS pg_trgm;"
done
echo "Databases ready: wadeh_dev, wadeh_test (role wadeh / password wadeh_dev — local development only)"
