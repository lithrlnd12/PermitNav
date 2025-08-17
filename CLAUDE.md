- add to memory always refer to this PRD unless instucted otherwise  Overview

PermitNav is a mobile-first application (Android first, iOS-ready) for truck drivers to simplify navigation, compliance, and trip planning based on state-specific oversize/overweight (OSOW) permits.

Drivers can:

Snap a photo of their permit → OCR extracts key info.

Get compliant routing → Based on HERE truck routing & state rules.

Access driver-friendly tools → parking, weather, amenities, etc.

2. Goals

Provide safe & legal routing based on truck attributes and state-issued permits.

Replace manual interpretation of permits with automated compliance checks.

Offer an all-in-one tool for navigation, rest, parking, fuel, and road condition awareness.

Build MVP in Indiana → scale nationally.

3. Target Users

Truck Drivers (owner-operators, fleet drivers).

Dispatchers (support drivers, validate compliance).

Fleet Managers (monitor efficiency, safety, and compliance).

4. Core Features (MVP)

Permit Scanning

Take a picture of Indiana permit.

OCR extracts dimensions, weight, allowed roads, dates.

Validate against state rules stored in JSON.

Truck-Compliant Routing

HERE Routing API with truck attributes (height, weight, hazardous, axle count).

Overlay state restrictions from local rule JSON.

Navigation UI

Google Maps–style interface with turn-by-turn navigation.

Route recalculation if driver deviates.

5. Extended Features (Future)

Real-time road closures (INDOT 511, later nationwide).

Weather & hazard alerts (NOAA / OWM).

Truck parking availability (INDOT API, later multi-state).

Fuel price comparison & truck stop amenities.

Toll cost estimates (HERE Toll API).

Fleet dashboard (manager portal).

6. API Roadmap
✅ Phase 1 – MVP (Free / On-Device)

HERE Routing API (truck routing).

Google ML Kit OCR (on-device permit scanning).

Local JSON Rule Files (/assets/state_rules/indiana.json).

🚛 Phase 2 – Contextual Data (Free)

NOAA / NWS API (weather).

INDOT 511 API (road closures).

INDOT Parking API (truck parking availability).

FMCSA Safer API (carrier safety lookups).

⛽ Phase 3 – Driver Value Features (Freemium)

OpenWeatherMap (global weather fallback).

Google Places API (fuel, rest stops, amenities).

EIA.gov (fuel averages).

GasBuddy API (fuel prices – partner access).

HERE Toll Costs API (paid).

🌎 Phase 4 – National Expansion (Paid / Scraping)

Multi-state DOT permit feeds (partnerships/scraping).

Commercial data providers (ProMiles, KeepTruckin).

Crowdsourced driver inputs (ratings, parking, weigh stations).

7. Tech Stack

Frontend: Android (Kotlin, XML) → later iOS (Swift).

Backend: Firebase (Auth, Firestore, Cloud Functions).

AI/OCR: Google ML Kit OCR (on-device).

Maps/Routing: HERE SDK (REST APIs + Android SDK).

Storage: Firebase Storage (permit images, rule JSON).

8. Success Metrics

MVP adoption: # of drivers creating compliant routes.

Compliance: Reduction in permit violations.

Engagement: Daily active users, session length.

Expansion readiness: # of APIs integrated per state.

9. Risks & Mitigations

Data availability: Most states lack public permit APIs → solve with JSON/manual rules early.

Cost creep: Limit API calls (batch, cache routes).

Adoption barrier: Start with Indiana where APIs exist.

Legal liability: Include disclaimers (drivers remain responsible for compliance).

👉 This PRD is now Claude Code–ready: you can paste it as context, and then prompt Claude to scaffold the project (frontend skeleton, Firebase setup, HERE API integration, local JSON rules).