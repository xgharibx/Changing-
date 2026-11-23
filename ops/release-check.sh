#!/usr/bin/env bash
# Release gate. Blocks a production build that contains development endpoints, fixture identifiers, synthetic orgs,
# cleartext config, placeholder copy or committed secrets. Exit non-zero on any finding.
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0; f() { echo "BLOCK: $*"; fail=1; }

# 1. Android prod flavour must not point at dev hosts or placeholders.
PROD_API=${WADEH_PROD_API:-}
[ -z "$PROD_API" ] && f "WADEH_PROD_API is not set (prod flavour would use the placeholder https://api.wadeh.invalid)"
case "$PROD_API" in *10.0.2.2*|*localhost*|*127.0.0.1*|http://*|*.invalid*) f "WADEH_PROD_API is a development/placeholder URL: $PROD_API";; esac
grep -q 'cleartextTrafficPermitted="true"' android/app/src/main/res/xml/network_security_config.xml && f "main network config allows cleartext"
grep -q 'targetSdk = 36' android/app/build.gradle.kts || f "targetSdk is not 36 (Play requirement since 31 Aug 2026)"

# 2. No fixture identifiers or synthetic seed accounts in shipped Android sources.
grep -rnE '00000000-0000-4000-8000|test\.wadeh\.invalid|dev-only-password' android/app/src/main && f "fixture identifiers in android/app/src/main"

# 3. No secrets committed.
git ls-files | grep -E '\.(jks|keystore|p12|pem)$|(^|/)\.env$' && f "key/secret files are tracked by git"
git grep -nE '(sk-ant-[A-Za-z0-9]|AIza[0-9A-Za-z_-]{30}|-----BEGIN (RSA |EC )?PRIVATE KEY)' -- . ':!ops/release-check.sh' && f "secret-looking strings in repository"

# 4. Production database must contain no synthetic organisations visible to the public API (checked when PROD_DATABASE_URL is provided).
if [ -n "${PROD_DATABASE_URL:-}" ]; then
  n=$(psql "$PROD_DATABASE_URL" -Atc "SELECT count(*) FROM listing l JOIN organization o ON o.id=l.seller_org_id WHERE o.is_test AND l.publication='published'")
  [ "$n" != "0" ] && f "$n published listings from test organisations in production"
fi

# 5. Placeholder public copy.
grep -rniE 'lorem ipsum|TODO|FIXME' android/app/src/main/res && f "placeholder copy in Android resources"

[ $fail = 0 ] && echo "release-check: PASS" || echo "release-check: FAIL"
exit $fail
