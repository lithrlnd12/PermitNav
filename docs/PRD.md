# Clearway Cargo - Product Requirements Document (PRD)

## 1. Overview

Clearway Cargo is a mobile-first platform (Android-first, iOS-ready) for truck drivers and dispatchers to simplify navigation, compliance, and trip planning for oversize/overweight (OSOW) permits.

**Drivers can:**
- Snap a photo of their permit → OCR extracts key info.
- Receive compliant routing based on truck attributes & state rules.
- Access driver-friendly tools (parking, weather, amenities).

**Dispatchers can:**
- Manage loads and drivers from a central system.
- Automate permit validation, route assignment, and driver notifications.
- Connect with automation tools (e.g., n8n) to streamline workflows.

## 2. Goals

- Provide safe & legal routing based on permits and truck specs.
- Replace manual permit interpretation with automated compliance checks.
- Automate dispatcher workflows for efficiency and fewer violations.
- Build MVP in Indiana, then expand nationally.

## 3. Target Users

- **Truck Drivers** (owner-operators, fleet drivers).
- **Dispatchers** (assign loads, validate compliance, notify drivers).
- **Fleet Managers** (monitor compliance, safety, and efficiency).

## 4. Core Features (MVP)

### Driver Side

**Permit Scanning (OCR)**
- Capture image of Indiana permit.
- OCR extracts dimensions, weight, allowed roads, validity dates.
- Validate against state rule JSON.

**Truck-Compliant Routing**
- HERE Routing API with truck attributes (height, weight, axle count, hazmat).
- Overlay state restrictions (from JSON).
- Route recalculation if driver deviates.

**Navigation UI**
- Turn-by-turn navigation.
- Driver-friendly design (large buttons, voice feedback).

### Dispatcher Side

**Load Management**
- Create or import loads (origin/destination, time windows, dims/weight).
- Assign loads to drivers & trucks.

**Task Automation**
- Tasks generated automatically (permit validation, route planning, driver notification).
- Dispatcher can view/manage tasks in app or via automation dashboard.

**Permit Validation**
- OCR permit stored in Firestore.
- Cloud Function checks against state JSON using AI worker.
- Mark permits as validated or flagged.

**Driver Communication**
- Automated notifications (in-app, SMS, email).
- Dispatchers can trigger custom messages.

## 5. Extended Features (Future)

- Real-time road closures (INDOT 511, later nationwide).
- Weather & hazard alerts (NOAA / OpenWeatherMap).
- Truck parking availability (INDOT API → multi-state).
- Fuel price comparison & amenities (Google Places, GasBuddy, EIA).
- Toll estimates (HERE Toll API).
- Fleet dashboard (web portal for managers).

## 6. API Roadmap

### ✅ Phase 1 – MVP (Indiana)
- HERE Routing API (truck routing).
- Google ML Kit OCR (on-device permit scanning).
- Local JSON Rule Files (/assets/state_rules/indiana.json).

### 🚛 Phase 2 – Contextual Data
- NOAA / NWS API (weather).
- INDOT 511 API (closures).
- INDOT Parking API.
- FMCSA Safer API (carrier safety lookups).

### ⛽ Phase 3 – Driver Value Features (Freemium)
- OpenWeatherMap (fallback).
- Google Places API (fuel, stops).
- EIA.gov (fuel averages).
- GasBuddy API (fuel prices).
- HERE Toll Costs API (paid).

### 🌎 Phase 4 – National Expansion (Paid)
- Multi-state DOT permit feeds (scraping/partnerships).
- Commercial providers (ProMiles, KeepTruckin).
- Crowdsourced driver inputs (parking, weigh stations).

## 7. Tech Stack

- **Frontend:** Android (Kotlin, XML), iOS (Swift later).
- **Backend:** Firebase (Auth, Firestore, Functions, Storage).
- **AI/OCR:** Google ML Kit OCR.
- **Maps/Routing:** HERE SDK (REST + Android SDK).
- **Automation:** n8n orchestrator for dispatcher workflows.

## 8. Success Metrics

- **MVP adoption:** # of drivers creating compliant routes.
- **Compliance:** Reduction in permit violations.
- **Engagement:** Daily active users, session length.
- **Expansion readiness:** # of APIs integrated per state.

## 9. Risks & Mitigations

- **Data availability:** Many states lack APIs → use JSON/manual rules.
- **Cost creep:** Limit API calls (batch, cache routes).
- **Adoption barrier:** Start in Indiana where APIs exist.
- **Liability:** Disclaimers → drivers remain responsible for compliance.