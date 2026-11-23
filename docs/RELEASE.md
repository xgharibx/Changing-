# Release decision table and path to Play

State on **23 Nov 2026**. Each row moves only on evidence. "Built", "signed by publisher", "uploaded to internal testing", "submitted", "approved" and "publicly installable" are separate states.

| Row | State | Evidence | Environment | Owner | Outstanding action |
|---|---|---|---|---|---|
| Software release candidate | **Not cut.** Dev debug APK built and exercised. | `evidence/m1..m4` | CPU-emulated API 34 emulator; Robolectric; local Postgres | Engineering | Finish M3 map view and M4 news; then cut RC from a tagged revision |
| Authorised supply | **None.** Synthetic test supplier only. | `fixtures/` (all synthetic) | — | Founder (F-01) | Sign ≥3 pilot partners using `docs/PILOT_PACK.md` |
| Public coverage claim | **None allowed.** Coverage endpoint reports counts with a "no national denominator" note. | `evidence/m3/coverage-dev.json` | dev | Product | Claim only per-locality counts from authorised partners |
| External providers | OpenFreeMap (no SLA, attribution); OSM extract (ODbL); no traffic; no LLM key | `docs/RESEARCH.md` R-03, R-04 | — | Engineering + Founder (F-06) | Decide map fallback (self-host PMTiles) and AI spend cap |
| Lawful publication rights | **Not obtained** for any real project. Directory listing of real projects is not enabled. | — | — | Founder + counsel | Partner agreements grant publication rights per project |
| Personal data / PDPC | **Blocker L-01.** Leads are disabled when `APP_ENV=prod` (`LEADS_ENABLED=false`). | `docs/PRIVACY_LEGAL_REGISTER.md` | — | Counsel | PDPC licence decision; hosting location (L-02) |
| Production infrastructure | **None deployed.** | — | — | Founder (approve spend) | Choose host and region after L-02; apply `ops/` scripts |
| Signing | **No publisher key.** Release config reads keys from the environment only. | `android/app/build.gradle.kts` | — | Founder | Create the upload key and enrol in Play App Signing (steps below) |
| Device verification | Emulator UI-tree checks pass. **No physical-device or pixel evidence from a device.** | `evidence/m1/README.md` "Not verified here" | — | QA | Run the journeys on a mid-range device (e.g. a 2024–2025 Samsung A-series), Arabic and English, TalkBack |
| Play submission | **Not started.** | — | — | Founder | Developer account, listing, Data safety and AI declarations (below) |
| Public availability | **No.** | — | — | — | — |

## Package identity
`applicationId = eg.wadeh.app` is **provisional**. Before the first upload, confirm a publisher-controlled identifier (ideally a reverse of a domain the company owns). It cannot change after publishing.

## Producing the upload-signed bundle (founder, on a trusted machine)
1. `keytool -genkeypair -v -keystore wadeh-upload.jks -keyalg RSA -keysize 4096 -validity 9125 -alias upload`. Store it in a password manager or vault, **never in git** (`.gitignore` blocks `*.jks`).
2. Export these in CI secrets: `WADEH_UPLOAD_KEYSTORE`, `WADEH_UPLOAD_STORE_PASSWORD`, `WADEH_UPLOAD_KEY_ALIAS`, `WADEH_UPLOAD_KEY_PASSWORD`, `WADEH_VERSION_CODE`, and `WADEH_PROD_API` (an https URL that is not `.invalid`).
3. `ops/release-check.sh` must print PASS.
4. `cd android && ./gradlew :app:bundleProdRelease`, then `sha256sum app/build/outputs/bundle/prodRelease/app-prod-release.aab`.
5. Upload to Play **internal testing**, enrol in Play App Signing, and test the install through the testing track (not sideloaded).

## Play checklist derived from the implementation (re-check rules at submission time)
- **Target API 36** (required for new apps from 31 Aug 2026; R-01). Already set.
- **Permissions:** INTERNET and ACCESS_NETWORK_STATE only. No location (we use landmark choice) and no notifications yet.
- **Data safety** (from actual flows):
  - Collected only when the user requests a viewing: name and phone, shared with the named seller with explicit consent, retained 180 days, deletable.
  - Not collected: location, contacts.
  - On-device only: saved homes and private notes.
  - Share links contain listing ids only.
- **Account deletion:** no accounts exist yet (guest-first). When accounts ship, an in-app and web deletion path is required.
- **Generative AI policy:** AI is off in all builds today. When enabled, an in-app report/flag control for AI output is required.
- **App access for review:** guest browsing needs no login. The reviewer note must explain that the content is synthetic test data unless partners are live.
- **Store listing:** Arabic and English copy must be written from the release candidate's real screenshots. Do not use the Robolectric renders as store screenshots.

## Rollback and restore
- DB: Flyway migrations are forward-only and additive. Roll back the app tier first; the schema remains compatible with the previous server (no destructive migrations yet).
- **Restore drill: not yet rehearsed.** It needs a staging host. Command sketch: `pg_dump -Fc wadeh > snap.dump` then `pg_restore -d wadeh_restore snap.dump` and `ops/release-check.sh` against it.
- Bad supplier batch: console "Revert batch". Tested (`SupplyToPhoneTest#revert`).
- Provider switch-off: `provider_switch` rows for `traffic` and `ai`. Tested for traffic (`RoutingTest`).
