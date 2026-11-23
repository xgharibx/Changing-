# Progress: where things stand

**Date:** 23 Nov 2026. **Branch:** `ccr-25b00c83-ovxqda`. **Known-good revision:** the commit that adds this file (see `git log -1`). **Migrations:** V1–V6.

## What a person can do now (synthetic data only)
- **Supplier operator:** upload an Arabic/English CSV or XLSX, then get a row-by-row preview and remediation report. A different reviewer publishes it. A bad batch can be reverted. Replaying the same file changes nothing.
- **Buyer (Android, Arabic/English):**
  - search in their own words ("عايز شقة في التجمع، مقدمها مايزيدش عن مليون…") and get editable chips that filter real results;
  - see each unit's exact payment schedule (EGY-Q1 golden figures) with a dated source line, and "what changed" with a like-for-like flag;
  - save homes with private notes, get "N changes since you looked", compare 2–4, share a read-only link and revoke it;
  - work offline with dated cached copies;
  - get a route to the visitor gate from a landmark;
  - request a viewing: offer revalidation if the price changed, honest "not confirmed yet" until the seller confirms.
- **Partner:** a viewing-request inbox in the console (acknowledge, confirm, propose a time, decline, record the outcome), scoped to their organisation.
- **Relative without the app:** opens a shared comparison as a web page in Arabic or English.

## Commands
```bash
ops/bootstrap-db.sh                        # Postgres 16 + PostGIS + pgRouting
./gradlew :core:test :server:test          # 22 + 28 tests, all passing at this revision
ops/run-server.sh                          # API + console on :8080, synthetic seed
server/build/install/server/bin/server load-osm data/osm/map.osm osm-new-cairo-90st   # real road graph extract (ODbL)
ops/demo-m1.sh                             # supply → publish → revise → replay
cd android && ./gradlew :app:assembleDevDebug :app:testDevDebugUnitTest   # APK + 11 Robolectric renders
ops/release-check.sh                       # currently FAILS by design: no production API configured
```

## Test results at this revision
core 22/22 · server 28/28 (real Postgres/PostGIS/pgRouting) · Android Robolectric 11/11 · parser dev eval 21/21 · parser held-out 10/12 (reported, not tuned) · emulator journeys M1–M4 (UI-tree evidence in `evidence/m*/`).

## Not verified / blocked
- **No physical device; no device pixels.** Emulator screenshots are black under software CPU emulation (`docs/DEVICE_TESTING.md`). Performance targets are unmeasured.
- **No authorised supply, no production deployment, no signing key, no Play account.** See `docs/RELEASE.md`.
- **PDPC licence (L-01) and hosting location (L-02)** gate production lead handling. See `docs/PRIVACY_LEGAL_REGISTER.md`.
- The real route (Point 90 Mall → AUC Gate 5) needs a field check.

## Next concrete action
Build the native **MapLibre map view**: list/map sync with precision circles, a TalkBack list alternative, and attribution. Then the **newsroom with corrections** flowing into timelines and the outbox. In parallel, the founder sends the pilot outreach (after approving `docs/PILOT_PACK.md`) and books counsel for L-01/L-02.

## Resume checklist for the next session
1. Read this file, `PRODUCT.md`, `ledger.json`.
2. `ops/bootstrap-db.sh && ./gradlew :core:test :server:test` (smoke).
3. Pick the highest-impact unblocked `planned` item in `ledger.json`.
