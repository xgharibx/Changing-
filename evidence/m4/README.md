# Milestone 4 evidence (partial): understanding the buyer, viewing requests

| Check | Result | Evidence |
|---|---|---|
| Brief's sentence "عايز شقة في التجمع، مقدمها مايزيدش عن مليون، وأوصل شغلي في ميفيدا قبل تسعة." | On the device it becomes the chips **Apartment · In التجمع · Cash to start ≤ EGP 1,000,000 · Commute to ميفيدا by 09:00**. The commute is shown as **not yet filtering** until a mode and day are chosen. The API returned 3 matching offers. | `device-nl-search-ar-sentence.xml` |
| Parser evaluation (deterministic, `qp-1.0.0`) | **Dev set 21/21 cases (77 checks)** across ar-EG, MSA, Arabizi, English and mixed, including 2 instruction-injection cases. **Held-out set 10/12 on its first, untuned run.** Failures: a commute phrase without "في"; masculine "متشطب". Kept as recorded, not tuned away. | `query-parser-v1-report.txt`, `query-parser-holdout-v1-report.txt`, `core/src/test/resources/eval/` |
| Viewing request: honest pending state | After sending, the app says "Request sent… **Not confirmed yet**". It never says confirmed before the partner does. | `device-viewing-form.xml` |
| **Offer changes while the buyer reads** (release-blocking) | The supplier published a new cash price mid-flow. On submit the app showed "The offer changed since you opened it… **Nothing has been sent yet**". Name, phone and slot were preserved, the DB inquiry count was unchanged, and "Continue at the new price" then sent it. | `device-viewing-offer-changed.xml`; `InquiryTest#price changed…` |
| Partner inbox → buyer | The partner (synthetic operator) acknowledged and confirmed in the console. The buyer's app shows "**confirmed by the seller**". Each event is recorded with its actor. | `console-inbox-after-confirm.png`, `console-inbox-next-actions.png`, `device-viewing-confirmed.xml` |
| Same slot at the same instant (release-blocking) | Two threads released together: one holds the slot (`requested`); the other is `waitlisted` with 3 real alternative slots | `InquiryTest#two buyers…` |
| Network retry | The same idempotency key returns the same inquiry and one outbox message | `InquiryTest#network retry…` |
| Supplier isolation | The broker cannot see or act on another organisation's request | `InquiryTest#partner isolation…` |
| Unanswered | After 24 h without acknowledgement the request becomes `no_response` (operator follow-up); the partner can still confirm | `InquiryTest#unanswered…` |
| Retention | Contact details are erased after the retention period; the anonymous trail is kept | `InquiryTest#retention…` |
| Production gate | `LEADS_ENABLED` defaults to **false** when `APP_ENV=prod`, pending the PDPC licence decision (L-01) | `Main.kt`, `InquiryTest#…disabled leads…` |

Not done in M4: newsroom and corrections, push and email delivery from the outbox, the LLM provider (no key provisioned, so the deterministic path is the product today), and the flagship "two commutes" isochrone filter.
