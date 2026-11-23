# Milestone 2 evidence: buyer decision (partial)

Same environment as M1 (CPU-emulated API 34 emulator, dev API on Postgres `wadeh_dev`, synthetic supplier only).

| Journey step | Observed result | Evidence |
|---|---|---|
| Save two homes via WhatsApp-style deep links | Both appear in **Saved** with their source lines; snapshot kept for offline | `device-01-saved.xml` |
| Compare 2 homes (B12-0304 vs B12-0512) | Same engine figures as the API. B12-0512 total EGP 6,372,000 tagged **"Exact total unknown"** (parking required, amount not given). No "lowest" total is declared; "Lowest" is shown as a text tag on cash-to-start and first 12 months. | `device-02-compare.xml` |
| Share a read-only link | Server stores only the 2 public listing ids; Android share sheet opens (the ATD image has no share targets) | `device-03-share-sheet.xml`, `web-share-{ar,en}-before-revoke.png`, `web-share-ar.png` (after typography and localisation fixes) |
| Revoke from the phone | App shows "Revoked: the link no longer opens"; API **410**; web page **410**; old link body contains no prices | `device-04-saved-after-revoke.xml`, `web-share-after-revoke.png`; test `DecisionTest` |
| Supplier adds a cash price on a newer sheet (fixtures/m2/03…) | Saved shows **"1 change since you looked"** only on B12-0512; the reconfirmed B12-0304 gets no badge. The change is `price_changed cash_price null → 540000000`, like-for-like. | `device-05-saved-change-since-seen.xml`, `device-06-detail-cash-offer-change.xml`, `api-b12-0512-changes.txt` |
| Server stopped (no connectivity to Wadeh) | Saved home opens from cache with **"Offline. Showing what you saw at 23 Nov, 13:17."** and the full payment breakdown | `device-07-offline-saved-detail.xml` |

Design fixes made from looking at real renders: Arabic thousands separator rendered as an apostrophe in browsers (now uses the app's OFL typeface), price per m² rounded to whole pounds, Arabic quarter names, localised suppression notes, and a text "lowest" marker so the signal does not rely on colour.

Still open in M2: filter chips (budget, cash-to-start, bedrooms), budget-stretch quarter view, revalidation before a price-dependent inquiry (lands with M4 inquiries), TalkBack walkthrough on a physical device.
