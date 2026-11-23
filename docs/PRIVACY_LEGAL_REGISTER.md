# Privacy, legal and policy register

No professional sign-off is implied. Each item says whether **engineering is done**, **a counsel decision is pending**, or **a regulator/provider approval has been received**. As of 23 Nov 2026, no approvals have been received.

## Data flows (as implemented)
| Data | Where it lives | Who sees it | Retention | Status |
|---|---|---|---|---|
| Saved homes, private notes | Phone only (`files/saved`, excluded from cloud backup) | The user | Until the user deletes them or uninstalls | Engineering done (PR-04) |
| Share links | Server: token + listing ids + language only | Anyone with the link until revoked or expired (30 days) | Expiry; revocation is immediate (410) | Engineering done; tested |
| Route origin | Sent per request, **not stored**; query strings are not logged | Server process memory only | None | Engineering done (PR-03). Verify log config in prod |
| Viewing request: name, phone, slot | Server `inquiry` | The recipient organisation only (object-level check) | 180 days, then the fields are erased | Engineering done (PR-01). **Counsel: L-01** |
| Operator accounts | Server `app_user` (PBKDF2 hashes), session tokens hashed | Platform admins | Account lifetime | Second factor for privileged roles **not yet built** (F-SEC-01) |
| Supplier files | Server `source_record.raw` (row text) | Supplier org, reviewers | **Policy needed** (L-05) | Retention job not built |
| Audit log | Server | Platform admins | Policy needed | — |

## Register
| ID | Question | Why it matters | Status / next step |
|---|---|---|---|
| L-01 | Does Wadeh need a PDPC licence or permit as controller/processor before handling buyer leads? (Law 151/2020; the executive regulations' compliance window is reported to have closed around 1 Nov 2026, R-02) | Viewing requests carry personal data | **Counsel decision pending.** Production leads are disabled by default |
| L-02 | Cross-border processing: does hosting outside Egypt need a transfer licence? | Choice of cloud region | **Counsel decision pending.** Blocks the infrastructure choice |
| L-03 | Real-estate advertising and brokerage rules: is passing a lead to a developer or broker regulated activity? Is a per-viewing fee permitted? | Revenue model | Counsel pending |
| L-04 | Display of developer price sheets: do partner agreements grant publication rights per project and per field? | Every public offer | Draft terms in `PILOT_PACK.md` |
| L-05 | Retention of supplier source files and row text | Evidence vs minimisation | Proposal: keep row text 24 months for dispute resolution; erase on partner exit unless legally held. Counsel to confirm |
| L-06 | Financial tools: the cash-flow engine shows payment schedules. Make sure nothing reads as credit advice | The in-app note says "not a loan offer or financial advice" | Counsel to confirm wording |
| L-07 | Map publication: OSM ODbL attribution and share-alike for derived graph data; OpenFreeMap terms | Routing graph is derived from OSM | Attribution shown in the app and on the web. A share-alike review of the derived DB is needed if it is distributed |
| L-08 | Official Egyptian Real Estate Platform terms prohibit extraction | We never scrape | Partnership or feed licence is a founder question (F-05) |
| L-09 | Play policies: Data safety, AI content, account deletion | Store approval | See `RELEASE.md` |
| L-10 | Fair discovery: no steering by sensitive traits or demographic proxies; sponsored placement labelled | Trust and law | There is no sponsored placement and no neighbourhood "safety" scores. Ranking = actionable state, then recency or price |

## Security controls implemented
- Server-side authorisation on every supplier action (upload, review, inbox), with tests for cross-tenant denial.
- Four-eyes publishing. Audit log on login, stage, publish, reject and revert.
- Cookies: HttpOnly, SameSite=Strict, Secure in prod. Origin check on state-changing console requests.
- No secrets in the repo; the release check scans for key patterns and keystores.
- Release builds: R8 minification, no cleartext traffic (dev flavour exempts only 10.0.2.2/localhost).
- **Not yet done:** TOTP for privileged roles, rate limiting, upload type/size limits beyond the 20 MB multipart cap, an OWASP MASVS review pass, restore drill.
