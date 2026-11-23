# Wadeh · واضح — engineering entry points

An Egypt-first real estate product. The Android app is the flagship; the web console serves suppliers and operators.
Read `PROGRESS.md` first (current state and next action), then `PRODUCT.md` (decisions), `ledger.json` (feature evidence).

## Layout
- `core/` — pure Kotlin, shared by server and Android: `Money`, `SupplierParse` (Arabic digits, amounts, dates), `CashFlowEngine` (versioned), `AvailabilityRules`, API contract `core/api/Contract.kt`.
- `server/` — Ktor API (`/v1/*`) and operations console (`/console`), Flyway migrations in `server/src/main/resources/db/migration`.
  - Import pipeline: `server/.../imports/` (`SupplierFile` → `RowNormalizer` → `ImportService.stage/publish/revert`).
- `android/` — separate Gradle build that includes `../core`. Flavours `dev` / `staging` / `prod`.
- `fixtures/` — **synthetic** supplier files only. `evidence/` — screenshots and run logs by milestone.
- `ops/` — `bootstrap-db.sh`, `run-server.sh`, `demo-m1.sh`, `release-check.sh`.

## Commands
```bash
ops/bootstrap-db.sh                     # Postgres 16 + PostGIS + pgRouting: wadeh_dev, wadeh_test
./gradlew :core:test :server:test       # unit + integration tests (needs wadeh_test)
ops/run-server.sh                       # migrate, seed synthetic data, serve :8080 (console: /console)
ops/demo-m1.sh                          # operator upload → reviewer publish → revision → replay
cd android && ./gradlew :app:assembleDevDebug    # needs ANDROID_HOME / local.properties sdk.dir
ops/release-check.sh                    # blocks fixture ids, dev endpoints, test orgs in prod builds
```
Dev logins (synthetic, local only): `operator@test.wadeh.invalid`, `reviewer@test.wadeh.invalid`, `admin@test.wadeh.invalid`; password `dev-only-password` (override with `SEED_PASSWORD`).

## Rules that keep the product honest
- Money is integer minor units + currency. Never `Double`. Rounding rule changes → bump `ENGINE_VERSION`.
- Never edit an `offer_version`; supersede it. Never treat an unknown fee as zero.
- Evidence age comes from the supplier's asserted date, never from upload time.
- Every consumer-visible change is a `change_event` with a `dedupe_key`; replays must produce none.
- Synthetic data lives under orgs with `is_test=true`; `APP_ENV=prod` hides them and seeding refuses to run.
- Don't log query strings or personal data. Use `X-Request-Id` for correlation.
- Emulator in this cloud environment has no KVM: boot with `ANDROID_I_WANT_MY_TCG=yes … -accel off` (≈12 min); see `docs/DEVICE_TESTING.md`.
