# Wadeh · واضح — product decisions and scope

*Provisional name.* "واضح" means "clear". It is short, sayable in Egyptian Arabic and English, and searchable. A first search (23 Nov 2026) found no real-estate product with this name. A trademark and domain check is **pending** (founder action F-03) before public use.

## Launch thesis (v0, 23 Nov 2026)

**Who switches first.** Egyptian households buying off-plan in Greater Cairo, mainly New Cairo and West Cairo, comparing two to five developer offers. Today they work from WhatsApp screenshots and broker voice notes. They will switch to a product that answers "what will I actually pay, and when, for *this* unit, and who said so on what date". Comparison portals answer "which compounds exist and what is the starting price".

**Why suppliers participate.** A small developer sales team spends its time re-answering the same questions about price, plan and availability. Wadeh asks them for one spreadsheet they already keep. In return they get qualified viewing requests whose buyers already understand the payment plan, plus a report of questions buyers could not answer from their data. The incentive to keep updating a sold unit is reputational and operational: stale "available" units generate wasted viewings that we measure and show back to them (a hypothesis to test in the pilot, H-04).

**Wedge.** Unit-level, versioned offers with a deterministic cash-flow breakdown, and a visible record of what changed and who confirmed it. We do not chase national listing volume first. National *search* exists from day one, with explicit coverage boundaries.

**Invalidating assumption (H-01).** We cannot get even three developers or brokerages to share unit-level price sheets weekly under terms that permit publication. If pilot outreach (F-01) yields fewer than three signed pilots in 60 days, the wedge changes. The fallback is a buyer-side "bring your own offer" tool: the buyer pastes a broker's sheet and the same engine explains it privately, so no publication rights are needed.

**North-star metric (definition, not yet measurable).** Share of attended viewings where price and availability matched what the buyer last saw in Wadeh.
- Numerator: viewings marked attended with a "matched" response.
- Denominator: attended viewings with any post-visit response.
- Window: 14 days after the visit date. One response per viewing.
- Minimum reporting sample: n ≥ 30 per month.
- Known bias: responders over-represent good or bad extremes.

## Decisions (choice · evidence · implication · how we will know)

| ID | Decision | Evidence | Implication | Success signal |
|---|---|---|---|---|
| D-01 | Separate physical **unit**, seller **listing** and immutable **offer version**; availability is a separate observation stream. | Brief §5; two brokers commonly advertise one unit (Nawy separates developer and resale inventory). | One unit can have many offers; a price change never edits history. | Test "two sellers on the same unit" passes; physical unit count is not inflated. |
| D-02 | One Kotlin **core** module (money, parsers, cash-flow engine, API contract) shared by server and Android. | Brief requires API, Android, comparison and AI to agree on EGY-Q1. | One implementation means no reconciliation bugs, and offline figures equal online ones. | EGY-Q1 golden tests run against core and against the API; Android renders the same engine output. |
| D-03 | **Ktor + PostgreSQL/PostGIS + Flyway**, plain SQL; a server-rendered operations console (no SPA). | Small team; PostGIS and pgRouting are available locally; the console must work on any office laptop. | One deployable unit, one language. The console has less interactivity than an SPA. | A new engineer runs `ops/bootstrap-db.sh && ops/run-server.sh` from a clean checkout. |
| D-04 | **Native Kotlin + Jetpack Compose**, minSdk 26, target 36. | Play requires target API 36 for new apps from 31 Aug 2026. minSdk 26 gives adaptive icons and java.time; Android 7 share in Egypt is small (to be measured, H-07). | Excludes Android 7 devices. | Crash-free sessions, and the install-base share below 26 reported by Play after launch. |
| D-05 | Publishing requires a **second person** (four-eyes). Publish re-plans against current state inside one transaction with a per-supplier advisory lock. | Brief: stale preview, out-of-order and concurrent imports. | Slower for a one-person supplier; we provide the reviewer during the pilot. | Zero published regressions from stale previews in the pilot. |
| D-06 | Idempotency by **content hash** at two levels: the file (identical bytes return the same batch) and offer facts (identical facts are "unchanged"). The asserted price-sheet date, never the upload time, drives confirmation age. | Brief: a stale sheet re-uploaded must not refresh its evidence age. | Suppliers must state a sheet date. Rows without one are rejected with a remediation note. | Test "stale sheet re-uploaded" passes. |
| D-07 | Evidence is shown as **words** ("Developer price sheet, 3 Nov 2026"), plus a stale state past the supplier's refresh target. No confidence percentages. | Brief §5. | Refresh targets are per supplier (default 14 days, broker 7). | Buyers in usability test U-2 can say who confirmed the price and when. |
| D-08 | Basemap: **MapLibre Native + OpenFreeMap** (OSM data, no key, attribution) for dev and pilot. Commercial SDK benchmark before scale. | OpenFreeMap public instance: free, no keys, attribution "OpenFreeMap © OpenMapTiles Data from OpenStreetMap" (openfreemap.org, checked 23 Nov 2026). Offered "as-is", with no SLA. | No SLA, so production needs a self-hosted or contracted fallback (cost register C-04). | Map cold start and tile errors measured on device; fallback rehearsed. |
| D-09 | Routing on **our own pgRouting graph built from OSM (ODbL)** for entrance-aware routes. Transit only where a dated GTFS feed exists. | Brief §7; pgRouting 3.6 is available. The TfC metro GTFS covers two lines only. | We own the graph and the gate model; no live traffic at launch. Copy must never imply live traffic. | Divided-road synthetic test, plus one documented public route. |
| D-10 | AI is **optional and bounded**. Deterministic parser, search and engine work without a model. The LLM may only call typed tools and must cite record IDs. | Brief §8; no model key is provisioned in this environment. | Features degrade to filters when the kill switch is on. | Evaluation suite runs in both modes; zero unsupported numeric claims. |

## Hypotheses (untested; no interviews have been run)

- H-01 Suppliers will share weekly unit-level sheets for a qualified-lead report. *Test:* pilot outreach F-01.
- H-02 Buyers trust a dated source line more than a "verified" badge. *Test:* usability task U-2, A/B in Arabic.
- H-03 "Cash needed in the first 12 months" is the number buyers compare first, before the list price. *Test:* interview guide Q7, card-sort.
- H-04 Showing suppliers the wasted viewings caused by stale stock changes their update cadence. *Test:* pilot report at weeks 2 and 6.
- H-05 Relatives abroad are a meaningful share of decision-makers. *Test:* interview recruitment quota and onboarding question.
- H-06 WhatsApp links are the dominant entry point. *Test:* deep-link attribution in the pilot.
- H-07 Android below 8.0 is under 2% of the target audience. *Test:* Play pre-launch device catalogue and Play console data.

## Scope by increment

See `ledger.json` (machine-readable) and `PROGRESS.md`. Statuses are: `planned`, `implemented`, `exercised_fixtures`, `verified_authorised_data`, `externally_blocked`, `released`. A claim never advances its own status; evidence paths are required.

## Research log

See `docs/RESEARCH.md`. Every row has a URL, the date checked, jurisdiction, the observation, and the decision it changed.
