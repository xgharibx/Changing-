# Supplier pilot pack (draft for founder review. **Do not send without approval.**)

## Value proposition

**English.** Buyers in Cairo ask your sales team the same questions again and again: which unit, what is due when, is it still available. Send us the price sheet you already keep, weekly. We show each unit with its exact payment schedule and a dated source line ("Developer price sheet, 3 Nov"). Viewing requests reach you with the buyer's chosen time, after they have seen the real numbers. Every month we tell you which questions buyers could not answer from your data and which "available" units caused wasted visits.

**العربية.** المشترين في القاهرة بيسألوا فريق المبيعات نفس الأسئلة كل مرة: أنهي وحدة، هيدفع إيه وإمتى، وهل لسه متاحة. ابعتلنا قائمة الأسعار اللي عندكم أصلًا كل أسبوع. هنعرض كل وحدة بجدول السداد الحقيقي ومصدر مؤرّخ («قائمة أسعار المطوّر، ٣ نوفمبر»). طلبات المعاينة توصلكم بموعد يختاره المشتري بعد ما شاف الأرقام الحقيقية. وكل شهر هنقولكم الأسئلة اللي المشترين ما لقوش إجابتها في بياناتكم، والوحدات «المتاحة» اللي سبّبت زيارات بلا فايدة.

## What we ask for
| Item | Detail |
|---|---|
| Inventory file | The CSV/XLSX template (`/console/template.csv`). Arabic or English headers. Required: project code, unit code, unit type, availability, price-sheet date |
| Cadence | Weekly, or on any price or availability change. Refresh target: 14 days for developers, 7 for brokers |
| Rights | Permission to publish per project: unit facts, prices, payment plan, availability, approximate location. Photos only with stated rights |
| Mode | Partial updates by default. A full snapshot (which withdraws missing rows) only when agreed |
| Contact routing | One inbox owner who acknowledges viewing requests within 24 hours |

## What we commit to
- Nothing goes public without a second-person review. Suspicious changes (more than a 20% price move, or withdrawing more than 30% of offers) need explicit acknowledgement.
- A row-by-row remediation report for every upload. A bad import can be reverted in one action.
- A competing seller's offer for the same unit is shown separately and never overwrites yours. Disputes go to an operator with a named owner.
- A partner can correct its own data but cannot suppress a legitimate competing offer.
- Opt-out: we withdraw all your offers within 1 business day of a written request.
- A commercial relationship never changes a verification label or a ranking.

## Monthly performance report (fields)
Offers live; median age of confirmation; offers past the refresh target; rows rejected and why; viewing requests received, acknowledged within SLA, confirmed, attended; buyer questions not answerable from the data; reported discrepancies and time to correction.

## Why keep updating a sold unit? (hypothesis H-04)
We show the partner the wasted viewings, and the buyer complaints, that came from stale "available" units. **Test:** compare stale-offer exposure in weeks 1–2 and weeks 5–6 for partners who receive the report against those who don't.

## Outreach draft (needs founder approval before sending)
> Subject: Pilot: fewer unqualified viewing requests for [Project]
> We're piloting a buyer tool for New Cairo that shows unit-level payment schedules with a dated source. We'd like to include [Project] for 8 weeks at no cost. You keep sending the sheet you already maintain; we send you viewing requests from buyers who've seen the exact numbers, plus a monthly report. 20 minutes to walk through the template?

## 30/60/90 days (starting from today's stage: working software, synthetic supply only)
- **Days 1–30:** counsel decisions L-01, L-02 and L-04. Recruit 3 suppliers in New Cairo (1 developer, 2 brokerages). Run 8 buyer interviews and 5 supplier walkthroughs (`docs/RESEARCH_PLAN.md`). Physical-device pass. Staging deploy and restore drill.
- **Days 31–60:** first authorised imports (expect 50–200 units). Verify gates on site for the pilot projects (named reviewer and date). Closed beta with 30–50 buyers. Measure the north star on attended viewings.
- **Days 61–90:** decide on a second locality (West Cairo: Sheikh Zayed/October) only if ≥80% of pilot offers are confirmed within target, partner acknowledgement is ≥70%, and ≥30 attended viewings have feedback. Prepare the Play internal → closed track.

**Budget ask:** see `docs/economics/costs.md`. The pilot infrastructure estimate is USD 120–300/month plus about 0.4 ops FTE. Counsel and the PDPC licence are unpriced.

## International sequence (comparison; recommendation, not a plan)
| Market | Supply rights | Transparency | Consumer problem | Language | Regulatory effort | Recommendation |
|---|---|---|---|---|---|---|
| Saudi Arabia (Riyadh off-plan) | Developer-led, licensed brokers (licensing regime: to verify) | Growing public data | Similar instalment complexity | Arabic | High (broker licensing) | Second market candidate once the Egypt pilot proves supplier retention |
| UAE (Dubai) | Mature developer and broker feeds; public transaction data (to verify) | High | Less acute (strong incumbents) | Arabic/English | Medium–high | Later; strong incumbents |
Conditions that would change this order: a Saudi partner offering feed rights early, or the Egypt pilot showing that the engine (not supply) is the differentiator. The latter would favour a B2B "explain this offer" tool for brokers in any market.
