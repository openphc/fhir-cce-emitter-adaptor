# API Reference — FHIR CCE Emitter Adaptor

## Overview

The FHIR CCE Emitter Adaptor exposes a single API group — the **Callback API** — which receives REST-hook notifications from the FHIR server. Subscriptions are managed automatically on startup via `StartupSubscriptionRunner`; there is no runtime subscription management API.

| API Group | Base Path | Purpose |
|-----------|-----------|---------|
| **Callback** | `/callback` | REST-hook callback endpoint for the FHIR server |

**Base URL:** `http://{host}:9090` (configurable via `SERVER_PORT`)

---

## 1. Callback API

### 1.1 REST-hook Callback

Receives REST-hook notifications from the FHIR server when subscribed resources change. The callback delegates to the `ResourceEnrichmentOrchestrator` which runs three enrichment strategies in sequence: (1) **NationalIdEnrichmentStrategy** — uses `NationalIdResolver` to resolve the national-id: for the configured identity-resource type (default: `RelatedPerson`), extracts the national-id from its own identifiers; for other resources, uses the configured JSON path (`person-identity-reference-paths`) to locate the identity-source reference, fetches that resource from the FHIR server, and resolves its national-id. Three placement strategies are checked in priority order based on the resource type's FHIR R4 definition: (a) **has `subject` field**: sets `subject.reference = "Patient/<national-id>"`; (b) **has `patient` field**: sets `patient.reference = "Patient/<national-id>"`; (c) **neither field**: adds `{"system": "<configured-system>", "value": "<national-id>"}` to `identifier[]`. If resolution fails, logs WARN and continues. (2) **PractitionerDisplayEnrichmentStrategy** — if `practitioner-display-paths` is configured for the resource type, uses `PractitionerResolver` to locate the first `Practitioner/{id}` reference at the configured path; if its `display` field is absent/blank, fetches the Practitioner from the FHIR server (`_elements=name`) and populates the display name. (3) **LocationEnrichmentStrategy** — dual-mode location enrichment (organization-path or generic). Only structurally invalid payloads (not a JSON object, blank `resourceType`) are skipped. The enriched (or original) JSON is forwarded to OpenHIM, and the endpoint always returns `200 OK` with an empty body to the FHIR server, regardless of forwarding outcome.

```
PUT /callback/{resourceType}/**
POST /callback/{resourceType}/**
```

**Path Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `resourceType` | string | Yes | FHIR resource type from the URL path (e.g., "patient", "encounter") |

**Wildcard segments** — The FHIR server may append `/{ResourceType}/{id}` to the callback URL. The controller matches all sub-paths:
- `/callback/patient`
- `/callback/patient/Patient/123`
- `/callback/encounter/Encounter/456`

**Content Types:** `application/json`, `application/fhir+json`

**Request Body:** Full FHIR R4 resource JSON

**Response:** Always `200 OK` with empty body, regardless of whether forwarding to OpenHIM succeeded or failed. Forwarding failures are logged and metered but not surfaced to the FHIR server.

> **Why always 200?** HAPI FHIR uses its internal FHIR client to deliver callbacks. Any non-2xx response, or a 2xx response with a non-FHIR body, causes the FHIR client to throw `DataFormatException`, which `RetryingMessageHandlerWrapper` retries indefinitely — creating an infinite redelivery loop. Returning `200 OK` with an empty body prevents this.

**Example Request:**

```http
PUT /callback/patient/Patient/123 HTTP/1.1
Host: localhost:9090
Content-Type: application/fhir+json

{
  "resourceType": "Patient",
  "id": "123",
  "active": true,
  "name": [{"family": "Smith", "given": ["John"]}]
}
```

**Example Response:**

```http
HTTP/1.1 200 OK
```

(Empty body — always returned regardless of forwarding outcome.)

### 1.2 Ping Endpoint

FHIR servers verify the callback endpoint is reachable before activating a subscription. This endpoint handles the ping request.

```
GET /callback/{resourceType}/**
HEAD /callback/{resourceType}/**
```

**Response:** `200 OK` with body `"OK"`

> **Note:** GET/HEAD requests do **not** trigger forwarding. They are purely for endpoint verification.

---

## 2. Content Types

| Content Type | Used By | Description |
|-------------|---------|-------------|
| `application/json` | Callback | Standard JSON |
| `application/fhir+json` | Callback | FHIR-specific JSON (preferred by FHIR servers) |

Both content types are accepted on the callback endpoint. The response Content-Type is `application/json`.

---

## 3. HTTP Status Codes

| Status | Endpoint | Meaning |
|--------|----------|---------|
| `200 OK` | Callback (PUT/POST) | Always returned — forwarding success or failure is logged/metered internally |
| `200 OK` | Ping (GET/HEAD) | Endpoint verification |

---

## 4. Error Handling

### Callback Endpoints (`/callback/**`)

The callback endpoint always returns `200 OK` with an empty body. This is required to prevent HAPI FHIR's `RetryingMessageHandlerWrapper` from triggering infinite redelivery loops:

- **Forwarding succeeds** → `200 OK` (empty body), `forward.success` counter incremented
- **Forwarding skipped (structurally invalid payload)** → `200 OK` (empty body), logged as `WARN`, `forward.skipped` counter incremented — occurs only when the enricher returns `null`: payload is not a JSON object, or `resourceType` is blank
- **National-id resolution fails** → `200 OK` (empty body), resource is forwarded as-is without enrichment, `forward.success` counter incremented on success — occurs when: no configured path for the resource type, no identity-source reference at the configured path (no `personReferenceIdentifier` found), or no national-id resolved from the identity-source resource
- **Forwarding fails (4xx/5xx from OpenHIM)** → `200 OK` (empty body), logged as `WARN`, `forward.failure` counter incremented
- **OpenHIM unreachable** → `200 OK` (empty body), logged as `WARN`, `forward.failure` counter incremented
- **Parse failures** → logged as `WARN`, forwarding still attempted with `resourceType = "Unknown"`
- **Reference resolution failures** → logged as `WARN`, resource is forwarded as-is without enrichment (enricher returns original JSON)

Forwarding failures are observable via Prometheus metrics (`fhir_emitter_forward_failure_total`) and logs. All failures are logged with full context (callbackKey, resourceType, resourceId, HTTP status, response body).

---

## 5. Forwarding Headers

When the `ForwardingEngine` forwards a resource to OpenHIM, it includes these headers:

### Always Present

| Header | Value | Description |
|--------|-------|-------------|
| `Content-Type` | `application/json` | JSON content type |

### Authentication Headers (per OpenHIM config)

| Auth Type | Header | Value |
|-----------|--------|-------|
| `basic` | `Authorization` | `Basic <base64(username:password)>` |
| `jwt` | `Authorization` | `Bearer <jwt-token>` |
| `custom-token` | `Authorization` | `Custom <token>` |
| `none` | — | No auth header |

---

## 6. Subscription Management (Startup Reconciliation)

Subscriptions are **not managed via a runtime API**. Instead, the `StartupSubscriptionRunner` automatically reconciles FHIR R4 REST-hook Subscriptions on application startup — creating missing ones and deleting stale adaptor-owned ones.

### How It Works

1. Application starts → `StartupSubscriptionRunner.run()` triggered (when `emitter.startup-subscriptions.enabled=true`)
2. Sleeps for `delay-seconds` (default 10s) to allow the FHIR server to become ready
3. Parses each resource type entry from `emitter.startup-subscriptions.resource-types`
4. Delegates to `SubscriptionRegistrationService.subscribeAll()`
5. Bulk-fetches existing adaptor-owned subscriptions from the FHIR server by owner tag (`https://openphc.org/cce/fhir-emitter|fhir-cce-emitter-adaptor`)
6. For each configured resource type: if an adaptor-owned subscription already exists, skip (`already-exists`); otherwise create (`registered`)
7. Identifies stale subscriptions — adaptor-owned subscriptions on the server not in the configured list — and deletes them (`deleted`)
8. Failures are logged but do not block remaining operations or application startup

### Resource Types

5 FHIR resource types are subscribed to on startup by default (configurable via `emitter.startup-subscriptions.resource-types` or `EMITTER_STARTUP_RESOURCE_TYPES` env var):

Patient, RelatedPerson, Encounter, Observation, Condition, MedicationRequest, MedicationDispense, MedicationStatement, DiagnosticReport, QuestionnaireResponse, ServiceRequest, CarePlan, Appointment, Group, Location, Organization, Practitioner, Coverage, PaymentNotice, Device, Provenance

### Restart Behavior

On restart, the `StartupSubscriptionRunner` reconciles subscriptions:
- **Existing subscriptions** for configured resource types are detected and skipped (`already-exists`)
- **New subscriptions** for resource types added to the config are created (`registered`)
- **Stale subscriptions** for resource types removed from the config are deleted (`deleted`)
- Only adaptor-owned subscriptions (tagged with `https://openphc.org/cce/fhir-emitter|fhir-cce-emitter-adaptor`) are ever loaded or deleted — non-adaptor subscriptions are never touched.

### Monitoring Startup Subscriptions

```bash
# Check startup subscription logs
docker logs fhir-cce-emitter-adaptor | grep "StartupSubscriptionRunner"
```
