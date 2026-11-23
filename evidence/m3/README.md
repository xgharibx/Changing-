# Milestone 3 evidence: location and evidence (partial)

| Check | Result | Where | Environment |
|---|---|---|---|
| Divided-road synthetic test (brief §7) | Car goes west on the legal carriageway, takes the U-turn, then east to the **visitor gate**. The **locked** nearest fence gate is excluded with a reason. Walking reports `no_pedestrian_connection` with a straight-line distance and no time. The proposed footbridge is not routed until an approved status change. | `server/src/test/kotlin/eg/wadeh/server/RoutingTest.kt`, `fixtures/m3/synthetic-divided-road.osm`, `route-synthetic-*.json`, `../screens/route-synthetic-divided-road-*.png` | local Postgres + pgRouting 3.6 |
| Station status | A developer brochure and an unapproved operator notice do not change status; a newer **approved operator** claim does | `RoutingTest#station becomes operational…` | local |
| Traffic provider disconnected | A broken provider gives `trafficStatus=unavailable` and copy saying "No live or typical traffic". A working provider is labelled `live`. | `RoutingTest#disconnected traffic provider…`; the app shows "Estimate without traffic…" | local; emulator |
| **Real public route** | Origin: Point 90 Mall (centroid of OSM way 471574860). Destination: AUC New Cairo named gates (OSM nodes; one Gate 2 node `locked=yes` was excluded). **Car → Gate 5: 615 m, ~72 s free-flow. Walk → Gate 5: 565 m, ~7 min via a mapped footway crossing.** | `real-route-point90-to-auc-{car,foot}.json`, `real-route-point90-to-auc.png` (OpenFreeMap basemap, MapLibre GL, attribution visible) | OSM API extract bbox 31.488,30.012,31.506,30.026 downloaded **23 Nov 2026 11:33 UTC** (ODbL) |
| App "Getting there" | Landmark picker (no location permission). The route to the synthetic project's visitor gate shows conditions, distance, minutes, the no-traffic notice, localised step types, assumptions, attribution and data date. | `device-getting-there-car.xml`, `../screens/route-real-point90-auc-foot-ar.png` | emulator (UI tree); Robolectric (pixels) |
| Coverage | Counts per locality with an explicit "no national denominator" note; synthetic only | `coverage-dev.json`, `GET /v1/coverage` | dev API |

## Needs local field checking (cannot be verified from here)
- Whether Gate 5 accepts visitors on foot and by car, its opening hours, and whether the footway crossing on the American University Axis is usable and safe. OSM tags these gates `access=permissive`.
- Whether the one-way tagging of the carriageways matches signage today.
- The synthetic project's gate is **synthetic**. It was placed on a real public road only to exercise the pipeline.

## Not done yet in M3
- Native map view in the Android app (MapLibre Native). It is not implemented because pixels can't be verified on this emulator; the web renderer above shows the style and attribution work.
- Transit layer (GTFS), amenity layers, the market heat-map with sample thresholds, and the map layer picker.
