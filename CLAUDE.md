Project Identity

App name: Clearway Cargo (formerly PermitNav).

Mission: Mobile-first app for truck drivers and dispatchers to simplify oversize/overweight (OSOW) permit compliance, routing, and trip planning.

Current Focus (MVP Phase)

Dispatcher Task Automation is the active build priority.

Drivers already have OCR permit scanning + HERE truck routing.

Now adding dispatcher workflows:

Capture loads (manual entry or scraped boards).

Trigger tasks: permit validation, route planning, driver notification.

Separate roles: drivers in the field, dispatchers in the office.

Tech Stack

Frontend: Android (Kotlin), with Firebase Auth & Firestore.

Backend: Firebase Cloud Functions (Node/TS).

Automation: n8n orchestrates workflows.

APIs: HERE Routing (truck attributes), Google ML Kit OCR.

Storage: Firebase Storage for permits, /assets/state_rules/ JSON for regulations.

Data Model (current scope)

users (role = driver | dispatcher).

trucks (attrs: height, weight, axle count, hazmat).

permits (OCR + validation status).

loads (origin/destination, dims/weight, status).

tasks (type: verify_permit, route_plan, bid_load, notify, etc.).

routePlans (HERE routing results linked to loads).

Core Functions for Automation

createLoad → add a load, spawn tasks.

planRoute → request truck-compliant route via HERE.

validatePermit → check OCR'd permit against state JSON rules.

createTask → create generic dispatcher tasks.

n8n or a phone/voice agent should connect via HTTP requests to these endpoints.

Style for Instructions

Prefer short, copy-pasteable blocks (code, JSON, rules).

Explanations = step-by-step.

If missing info, ask a clear follow-up.

Avoid drifting into long-term features unless explicitly asked.

Reference

For the full long-term vision (fuel, parking, weather, fleet dashboards, national scaling), see docs/PRD.md in the repo.