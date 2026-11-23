# Milestone 1 evidence: supply to phone

**Environment:** cloud Linux container. PostgreSQL 16.15 + PostGIS 3.4 (`wadeh_dev`). Ktor server `APP_ENV=dev` on :8080. Android emulator: AOSP ATD image, API 34 x86_64, `pixel_4a` profile, **software CPU emulation (TCG, no KVM)**, `-gpu swiftshader_indirect`. App: `eg.wadeh.app.dev`, debug, AOT-compiled on the device with `cmd package compile -m speed`. All data is **synthetic** (test organisation `is_test=true`).

| Step | What happened | Evidence | Source |
|---|---|---|---|
| 0 | Fresh install, no supply: "0 offers" plus an honest coverage message | `device-00-fresh-install-empty.xml` | emulator UI tree |
| 1 | Operator uploads `fixtures/m1/01-gardens-2026-11-03.csv` (Arabic headers, Arabic-Indic digits, "5.9 مليون"). The operator's own publish attempt is refused. Reviewer publishes 6 offers. | `demo-step1.log`, `console-batch-3221e48a.png`, `console-queue.png` | console over HTTP; Playwright |
| 2 | Phone lists the 6 offers from the real API | `device-01-list-after-publish.xml` | emulator |
| 3 | Deep link `wadeh://listing/{id}` opens B12-0304 (EGY-Q1): start EGP 800,000 · first 12 months EGP 1,700,000 · largest date EGP 820,000 on 1 Jan 2030 · total EGP 8,520,000 · "No changes" | `device-02-detail-v1.xml`, `../screens/detail-b12-0304-v1-{en,ar}.png` | emulator; Robolectric render of the same API JSON |
| 4 | Revision `02-…-revision.csv` published (price 8.0 → 8.4 M, instalment re-spread). Replay with identical bytes → same batch. Replay under a new filename → same batch. | `demo-step2-revision-replay.log` | console over HTTP |
| 5 | Phone shows **exactly one** change: "Price changed · Instalment price EGP 8,000,000 → EGP 8,400,000 — Not a like-for-like comparison — payment plan changed too · effective 10 Nov 2026 · Source: Test Developer" | `device-03-detail-after-revision-and-replay.xml`, `../screens/detail-b12-0304-v2-after-revision-{en,ar}.png` | emulator; Robolectric |

## Defects found by running the real workflow (fixed and covered by tests)
1. **Crash on first launch:** plural strings declared `%1$d` but received localised digit strings. Fixed to `%1$s`.
2. **One revision showed as two changes** (price and plan). A plan change now travels with the price change as a structured reason. Test: `M1 acceptance` asserts one visible change.
3. **False "handover changed"** for ready-to-move units, whose handover was derived from the sheet date. "Ready" is now a state. Test: `ready-to-move unit re-sent…`.
4. **English text inside Arabic UI** for change reasons. Reasons are now codes the app localises. Arabic date-time now uses "،".

## Not verified here (and why)
- **Pixel screenshots from the emulator are black.** `screencap` and the emulator console screenshot both return an all-zero frame under TCG + swiftshader_indirect. `-gpu guest` fell back to lavapipe and the zygote crash-looped. The on-device evidence is therefore the accessibility tree (`uiautomator dump`), which is what TalkBack reads. Pixels come from Robolectric native-graphics renders of the same API responses. **A physical mid-range device or a KVM runner is needed** for device pixels, frame timing and cold-start measurement.
- Cold-start and latency numbers from this emulator are meaningless (bytecode verification ran at about 130 bytes/s) and are not reported.
