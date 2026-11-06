# Architecture — FHIR CCE Emitter Adaptor

## 1. Service Purpose

The **FHIR CCE Emitter Adaptor** (`fhir-cce-emitter-adaptor`) is a FHIR-specific **Emitter Adaptor** for the Care Coordination Engine (CCE) platform.

As defined in the *CCE Solution Design v0.3* (Section 4.3.7.2), an Emitter Adaptor captures events from an external system and submits them to CCE for compliance tracking. This service is the FHIR-flavored implementation: it is deployed on the **source system side** (e.g., alongside SPICE's HAPI FHIR server), subscribes to FHIR R4 resource changes via **REST-hook Subscriptions**, receives callbacks when resources change, and forwards the raw FHIR JSON to **OpenHIM**, where an **OpenHIM Emitter Adaptor** (registered as a mediator) wraps and routes events to CCE.

### Key Simplification: Inferred Matching

The adaptor does **not** need to understand CCE's compliance protocol model. Per the CCE Solution Design (Section 4.3.4.3), CCE uses **inferred matching** — it determines which protocol and step an event belongs to based on trigger definitions in the protocol configuration. The Emitter Adaptor's job is simply to capture FHIR resource changes and forward them. CCE's trigger-based matching handles the rest.

### Server-Agnostic, OpenHIM-Targeted

The service is **not coupled to any specific FHIR server implementation**. It works with any FHIR R4-compliant server that supports REST-hook subscriptions:

- HAPI FHIR Server
- IBM FHIR Server
- Firely Server
- Google Cloud Healthcare API
- Microsoft FHIR Server
- Any other FHIR R4-compliant server

The target is **OpenHIM** — the service forwards FHIR resources to OpenHIM, where an OpenHIM Emitter Adaptor (registered as a mediator) wraps them in CloudEvents envelopes and routes them to CCE.

---

## 2. Responsibilities

The FHIR CCE Emitter Adaptor has **six core responsibilities**:

| # | Responsibility | Description |
|---|----------------|-------------|
| 1 | **Subscribe on Startup** | Register FHIR R4 REST-hook `Subscription` resources on the configured FHIR server automatically on startup |
| 2 | **Receive Callbacks** | Accept HTTP callbacks (PUT/POST) from the FHIR server when subscribed resources change |
| 3 | **Parse Metadata** | Parse incoming FHIR JSON to extract resource metadata (type, ID) using HAPI FHIR client library |
| 4 | **Enrich with National-ID** | Resolve the national-id and place it on the appropriate field based on the resource type's FHIR R4 definition. Three enrichment strategies checked in priority order: (a) **Has `subject` field** (e.g. Encounter, Observation, ServiceRequest): sets `subject.reference = "Patient/<national-id>"`; (b) **Has `patient` field** (e.g. AllergyIntolerance, RelatedPerson, Claim, EpisodeOfCare): sets `patient.reference = "Patient/<national-id>"`; (c) **Neither field** (e.g. Location, Organization, Practitioner): adds `{"system": "<configured-system>", "value": "<national-id>"}` to `identifier[]`. The identifier system URI is configurable via `national-id-identifier-system` (default: `http://openphc.org/identifier/upid`). If national-id resolution fails, the resource is **forwarded as-is without enrichment** (never skipped). Only structurally invalid payloads (not a JSON object, blank `resourceType`) are skipped. |
| 4b | **Enrich Practitioner Display** | After national-id enrichment, populates the `display` field on Practitioner reference nodes. Uses configurable `practitioner-display-paths` (e.g. `Encounter:participant.individual`) to locate the first `Practitioner/{id}` reference via a recursive JSON path walker. If `display` is absent/blank, fetches `GET /Practitioner/{id}?_elements=name` from the FHIR server and extracts a human-readable name (priority: `name[0].text` → `given + family` → `family` → `given`). Skips silently if no path configured, no Practitioner found, display already present, or fetch fails. |
| 4c | **Enrich Location** | Populates location fields on resources. Supports two modes: **(a) Organization-path mode** (default) — uses `location-from-organization-paths` (e.g. `Encounter:serviceProvider`, `ServiceRequest:performer`) to find an Organization reference, fetches `GET /Organization/{id}?_elements=name`, and sets the location display name. **(b) Generic mode** — 3-step cascade: (1) if a location reference already exists with a `display` → skip; (2) if a location reference exists without `display` → fetch `GET /Location/{id}?_elements=name` and set the display; (3) **only if no location reference is found in the payload at all** → fall back to the encounter route: find the Encounter reference at the configured path (e.g. `Observation:encounter`), fetch `GET /Encounter/{id}?_elements=location`, extract its location data, and set on the payload. FHIR R4 spec-aware: uses `locationReference[]` for ServiceRequest, `location[]` (BackboneElement) for Encounter. Resources without a location field in FHIR R4 are skipped. Always enabled (like other enrichment strategies). |
| 5 | **Forward to OpenHIM** | Forward the enriched (or original) FHIR JSON synchronously to OpenHIM with authentication headers (Basic Auth, JWT, Custom Token) — single attempt, no retry; skip forwarding (return `ForwardResult.skipped()`) only when enricher returns `null` (structurally invalid payload) |
| 6 | **Expose Observability** | Expose Prometheus metrics and Spring Boot Actuator health probes |

---

## 3. System Context Diagram

The emitter adaptor is **deployed on the source system side** — co-located with the participating system's FHIR server (e.g., SPICE's HAPI FHIR server). It taps into FHIR resource changes via REST-hook Subscriptions and forwards them to **OpenHIM**.

```
  Source System Side (e.g., SPICE)
  ════════════════════════════════════════
  ┌──────────────────┐
  │  FHIR R4 Server  │
  │  (e.g., SPICE    │
  │   HAPI FHIR)     │
  └────────┬─────────┘
           │  REST-hook Subscription callbacks
           │  (PUT/POST with FHIR JSON body)
           ▼
  ┌──────────────────────────────┐
  │                              │
  │  ★ FHIR CCE Emitter Adaptor  │
  │                              │
  │  • Receive REST-hook callback│
  │  • Parse FHIR metadata      │
  │  • Resolve references       │
  │    (RelatedPerson →       │
  │     national-id via       │
  │     configured paths)     │
  │  • Forward enriched JSON    │
  │                              │
  └──────────────┬───────────────┘
  ════════════════╪═══════════════════════
                 │
                 │  HTTP POST (enriched FHIR JSON)
                 ▼
  OpenHIM (Interoperability Layer)
  ════════════════════════════════════════
  ┌──────────────────────────────┐
  │         OpenHIM Core         │
  │  (channel → mediator)        │
  └──────────────────────────────┘
```

On startup, the `StartupSubscriptionRunner` reconciles FHIR Subscriptions on the FHIR server — bulk-fetching existing ones (with retry + backoff), creating missing ones, and deleting stale adaptor-owned ones. If the FHIR server is unreachable after all retry attempts, the service **aborts startup** (fail-fast) to prevent duplicate subscriptions:

```
  ┌──────────────────────────────┐          ┌──────────────────┐
  │  ★ FHIR CCE Emitter Adaptor  │──FHIR──▶│  FHIR R4 Server  │
  │  StartupSubscriptionRunner   │  client  │  (create/delete   │
  │  (startup reconciliation)    │◀─────────│   Subscription)  │
  └──────────────────────────────┘          └──────────────────┘
```

> **No runtime subscription management API** — subscriptions are reconciled on startup only. There is no POST/DELETE/GET `/api/subscriptions` endpoint. Stale adaptor-owned subscriptions are automatically deleted.

---

## 4. Position in the CCE Platform

### Emitter/Receiver Adaptor Model

The CCE platform uses an **Emitter/Receiver Adaptor** model (*CCE Solution Design v0.3*, Section 4.3.7):

- **Emitter Adaptors** capture clinical events from external systems and route them toward CCE for compliance tracking.
- **Receiver Adaptors** receive intelligence events from CCE and translate them into actions in target systems.

This service is a **FHIR-specific Emitter Adaptor**. It is deployed on the **source system side** — co-located with the participating system's FHIR server (e.g., SPICE's HAPI FHIR server). It captures FHIR resource changes (Encounters, Observations, ServiceRequests, etc.) via REST-hook Subscriptions and forwards them to **OpenHIM**. It does not wrap payloads in CloudEvents — that transformation happens downstream in the **OpenHIM Emitter Adaptor** (a mediator registered in OpenHIM that routes events to CCE).

### Where This Service Fits

The emitter adaptor sits on the **source system side**, co-deployed alongside the participating system's FHIR server.

```mermaid
flowchart LR
    subgraph SPICE["Source Side: SPICE"]
        FHIR["HAPI FHIR Server"]
        FE["★ fhir-cce-emitter-adaptor ★"]
        FHIR -->|REST-hook callbacks| FE
    end

    subgraph OHIM["OpenHIM (Interoperability Layer)"]
        OH["OpenHIM Core"]
        MED["OpenHIM Emitter Adaptor\n(mediator)"]
        OH --> MED
    end

    subgraph CCE["CCE Platform"]
        GW["CCE Gateway"]
        COL["Collector"]
        COMP["Compliance"]
        GW --> COL --> COMP
    end

    FE -->|HTTP POST\nFHIR JSON| OH
    MED -->|CloudEvents| GW
```

The FHIR CCE Emitter Adaptor is part of the **source-side infrastructure**. It taps into the local FHIR server's REST-hook Subscriptions and forwards captured FHIR resources to OpenHIM. The **OpenHIM Emitter Adaptor** (registered as a mediator in OpenHIM) handles CloudEvents wrapping and routing to CCE.

---

## 5. Typical Deployment Flows

### Deployment Flow

```
  Source Side (e.g., SPICE)
  ══════════════════════════════════
  FHIR R4 Server (SPICE HAPI FHIR)
       │ REST-hook Subscription callbacks
       ▼
  ┌─────────────────────────────┐
  │  ★ FHIR CCE Emitter Adaptor │
  │  Receive callback → Forward │
  └──────────────┬──────────────┘
  ═══════════════╪══════════════════
                 │ HTTP POST (FHIR JSON)
                 ▼
  OpenHIM (Interoperability Layer)
  ══════════════════════════════════
  ┌─────────────────────────────┐
  │  OpenHIM Core                │
  │  Route → Mediator            │
  └──────────────┬──────────────┘
                 │
                 ▼
  ┌─────────────────────────────┐
  │  OpenHIM Emitter Adaptor     │
  │  (mediator: CloudEvents wrap)│
  └──────────────┬──────────────┘
  ═══════════════╪══════════════════
                 │ CloudEvents envelope
                 ▼
  CCE Platform
  ══════════════════════════════════
  ┌─────────────────────────────┐
  │  CCE Gateway → Collector     │
  │  Validate → Kafka → Comply   │
  └─────────────────────────────┘
```

---

## 6. Technology Stack

| Concern | Technology | Version |
|---------|------------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.4.x |
| Build tool | Gradle (Groovy DSL) | 8.x |
| FHIR library | HAPI FHIR Client | 7.4.0 |
| HTTP client | Spring `RestTemplate` | (Spring Boot managed) |
| Observability | SLF4J + Logback, Micrometer, Prometheus | (Spring Boot managed) |
| Monitoring | Spring Boot Actuator | (Spring Boot managed) |
| Testing | JUnit 5, MockMvc, Mockito, WireMock | (Spring Boot managed / 3.9.1) |
| Container runtime | Eclipse Temurin | 21 (JDK build, JRE runtime) |

### Key Gradle Dependencies

```kotlin
// HAPI FHIR (client library for FHIR R4 — not server-specific)
implementation("ca.uhn.hapi.fhir:hapi-fhir-base:$hapiFhirVersion")
implementation("ca.uhn.hapi.fhir:hapi-fhir-client:$hapiFhirVersion")
implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:$hapiFhirVersion")

// Spring Boot starters
implementation("org.springframework.boot:spring-boot-starter-web")
implementation("org.springframework.boot:spring-boot-starter-validation")
implementation("org.springframework.boot:spring-boot-starter-actuator")

// Metrics
runtimeOnly("io.micrometer:micrometer-registry-prometheus")

// Testing
testImplementation("org.wiremock:wiremock-standalone:3.9.1")
```

> **Note:** The HAPI FHIR Client library is a **Java FHIR SDK** — it speaks standard FHIR REST API and is NOT tied to any specific FHIR server implementation.

---

## 7. Package Structure

```
src/main/java/org/openphc/cce/emitter/
├── FhirCceEmitterAdaptorApplication.java         # Spring Boot entry point
├── config/
│   ├── EmitterProperties.java                    # @ConfigurationProperties(prefix="emitter")
│   ├── FhirConfig.java                           # FhirContext.forR4() singleton bean
│   ├── LoggingFilter.java                        # MDC request tracing filter
│   ├── ObservabilityConfig.java                  # Micrometer metrics registration
│   ├── RestClientConfig.java                     # RestTemplate + trust-all RestTemplate beans
│   └── StartupSubscriptionRunner.java            # Auto-subscribe on startup (@ConditionalOnProperty)
├── controller/
│   └── SubscriptionCallbackController.java       # REST-hook callback endpoint (/callback/**)
└── service/
    ├── FhirClientFactory.java                    # Authenticated HAPI FHIR client creation (shared)
    │   ├── ForwardingEngine.java                     # Orchestrates enrichment + forwards FHIR JSON to OpenHIM
    ├── ForwardResult.java                        # Forwarding outcome record
    ├── RegistrationResult.java                   # Subscription registration outcome record
    ├── SubscriptionRegistrationService.java      # FHIR Subscription creation on server (startup-only)
    ├── TokenEndpointAuthService.java             # Token endpoint + OAuth2 token fetching
    ├── enrichment/                               # Strategy/Orchestrator pattern for payload enrichment
    │   ├── EnrichmentStrategy.java               # Strategy interface (enrich)
    │   ├── EnrichmentContext.java                # Immutable context record (payload, resourceType, resourceId)
    │   ├── ResourceEnrichmentOrchestrator.java   # Orchestrator: runs strategies in injection order
    │   ├── NationalIdEnrichmentStrategy.java     # Resolves national-id → sets subject/patient/identifier[]
    │   ├── PractitionerDisplayEnrichmentStrategy.java  # Populates Practitioner display names
    │   └── LocationEnrichmentStrategy.java       # Dual-mode location enrichment (org-path or generic)
    └── resolver/                                 # FHIR resource resolution services
        ├── FhirResourceFetcher.java              # Shared service: GET /{type}/{id}?_elements=fields
        ├── ResolverPathHelper.java               # Static utility: dot-path walking, array fan-out, prefix matching
        ├── NationalIdResolver.java               # Resolves national-id from payload (path lookup + match strategies)
        ├── PractitionerResolver.java             # Extracts Practitioner ref nodes + fetches display names
        ├── OrganizationResolver.java             # Extracts Organization IDs + fetches display names
        └── LocationResolver.java                 # Extracts Encounter IDs + fetches Location/Encounter data
```

### Test Structure

```
src/test/java/org/openphc/cce/emitter/
├── FhirCceEmitterAdaptorApplicationTests.java    # Context load test
├── config/
│   └── StartupSubscriptionRunnerTest.java        # Startup auto-subscription tests
├── controller/
│   └── SubscriptionCallbackControllerTest.java   # Callback endpoint tests
├── service/
│   ├── ForwardingEngineTest.java                 # Forwarding + error handling tests
│   ├── FhirClientFactoryTest.java                # Auth client creation tests
│   ├── TokenEndpointAuthServiceTest.java         # Token extraction + caching tests
│   ├── SubscriptionRegistrationServiceTest.java  # Subscription CRUD tests
│   ├── enrichment/
│   │   └── ResourceEnrichmentOrchestratorTest.java   # Orchestrator enrichment tests
│   └── resolver/
│       └── NationalIdResolverTest.java           # National-id resolution tests
└── integration/
    ├── AbstractIntegrationTest.java              # WireMock base class (FHIR + OpenHIM)
    ├── CallbackForwardIntegrationTest.java       # End-to-end callback → forward tests
    ├── ErrorHandlingIntegrationTest.java         # Malformed body, ping, error propagation
    ├── FhirServerOAuth2AuthIntegrationTest.java  # OAuth2 Client Credentials flow
    ├── FhirServerTokenAuthIntegrationTest.java   # Token-endpoint auth flow
    ├── HealthEndpointIntegrationTest.java        # Health endpoint test
    ├── OpenhimBasicAuthIntegrationTest.java      # OpenHIM Basic auth verification
    ├── OpenhimCustomTokenAuthIntegrationTest.java # OpenHIM Custom Token auth
    ├── OpenhimJwtAuthIntegrationTest.java        # OpenHIM JWT auth
    └── StartupSubscriptionIntegrationTest.java   # Startup subscription flow with WireMock
```

---

## 8. Processing Flows

### 8.1 Callback → Forward Flow (core path)

This is the primary processing path — the synchronous pipeline from FHIR server callback to OpenHIM:

| Step | Component | Action |
|------|-----------|--------|
| 1 | **FHIR Server** | Sends REST-hook callback: `PUT /callback/{callbackKey}/{ResourceType}/{id}` with full FHIR resource JSON body |
| 2 | **SubscriptionCallbackController** | Receives request, logs metadata (method, callbackKey, URI, payload size) |
| 3 | **SubscriptionCallbackController** | Calls `ForwardingEngine.forward()` synchronously |
| 4 | **ForwardingEngine** | Increments `callbacks.received` counter |
| 5 | **ForwardingEngine** | Parses FHIR resource metadata: `fhirContext.newJsonParser().parseResource()` → extract `resourceType` and `resourceId`; falls back to `"Unknown"` on parse failure |
| 6 | **ResourceEnrichmentOrchestrator** | Runs all registered `EnrichmentStrategy` beans in injection order. Each strategy receives an `EnrichmentContext` containing the mutable `ObjectNode` payload, `resourceType`, and `resourceId`. Strategies enrich in-place and operate independently. |
| 6a | **NationalIdEnrichmentStrategy** (@Order 100) | Resolves the national-id via `NationalIdResolver.resolveNationalIdFromPayload()`. If resolution succeeds, determines whether the resource type has a `subject` or `patient` field (via HAPI FHIR's `RuntimeResourceDefinition`): (a) **has `subject`**: sets `subject.reference = "Patient/<national-id>"`; (b) **has `patient`**: sets `patient.reference = "Patient/<national-id>"`; (c) **neither**: adds `{"system": "<configured-system>", "value": "<national-id>"}` to `identifier[]`. If resolution fails, logs WARN and continues — resource is forwarded as-is without national-id enrichment. |
| 6b | **PractitionerDisplayEnrichmentStrategy** (@Order 200) | Looks up the configured path from `practitioner-display-paths` for this resource type (e.g. `Encounter` → `participant.individual`). Delegates to `PractitionerResolver.extractPractitionerRefNodeAtPath()` which splits the dot-path into segments and recursively walks the JSON tree with array fan-out to find the first `Practitioner/{id}` reference. If found and `display` is absent/blank, extracts the Practitioner ID, fetches `GET /Practitioner/{id}?_elements=name` via `FhirResourceFetcher`, extracts a human-readable display name (priority: `name[0].text` → `given + family` → `family` → `given`), and sets `display` on the reference node (mutates the payload in-place). Skips silently if: no path configured, no Practitioner reference found, display already present, or fetch fails. |
| 6c | **LocationEnrichmentStrategy** (@Order 300) | **Dual-mode location enrichment** (always enabled, like other strategies). Mode determined by `location-enrichment-based-on-organization-path` (default `true`): **Organization-path mode** — uses `location-from-organization-paths` (e.g. `Encounter:serviceProvider`, `ServiceRequest:performer`) to locate the Organization reference, fetches `GET /Organization/{id}?_elements=name` via `OrganizationResolver`, and populates the location field. **Generic mode** — 3-step cascade: (1) location reference already has `display` → skip; (2) location reference exists without `display` → fetch `GET /Location/{id}?_elements=name` via `FhirResourceFetcher` and set display; (3) **only if no location reference is found in the payload** → fall back to the encounter route: find Encounter reference at path configured in `location-from-encounter-paths` (e.g. `Observation:encounter`), fetch `GET /Encounter/{id}?_elements=location` via `LocationResolver`, extract location data, and set on the payload. FHIR R4 spec-aware: uses `locationReference[]` for ServiceRequest, `location[]` (BackboneElement) for Encounter. Resources without a location field in FHIR R4 (e.g. Observation, Condition) are skipped. |
| 7 | **ForwardingEngine** | If the payload was structurally invalid (not a JSON object, blank `resourceType`), increments `forward.skipped` counter and returns `ForwardResult.skipped()` — no OpenHIM call. Otherwise proceeds to forward (enrichment failures are logged but do not block forwarding). |
| 8 | **ForwardingEngine** | Builds OpenHIM URL: `baseUrl + "/" + resourceType` if `append-resource-type: true`, otherwise just `baseUrl` |
| 9 | **ForwardingEngine** | Builds headers: auth (Basic Auth, JWT, Custom Token, or none) |
| 10 | **ForwardingEngine** | POSTs enriched JSON to OpenHIM via RestTemplate (trust-all or standard); single attempt, no retry. Failures are logged and metered. |
| 11 | **SubscriptionCallbackController** | Always returns `200 OK` with empty body to HAPI FHIR regardless of forwarding outcome. Returning any non-FHIR body (including error envelopes) or non-2xx causes HAPI FHIR's internal FHIR client to throw `DataFormatException`, triggering infinite redelivery via `RetryingMessageHandlerWrapper`. Failures are logged and metered. |

**Success:** Increments `forward.success` counter, records `forward.duration` timer. Returns `200 OK` with empty body.
**Skipped:** Increments `forward.skipped` counter — only for structurally invalid payloads (not a JSON object, blank `resourceType`). Returns `200 OK` with empty body (ACK to FHIR server).
**Enrichment failure:** When any enrichment strategy fails (e.g. national-id resolution, Practitioner fetch), the failure is logged but forwarding continues — the resource is sent to OpenHIM with whatever enrichment succeeded. Increments `forward.success` counter on successful forward.
**Failure:** Increments `forward.failure` counter, logs `WARN`. Always returns `200 OK` with empty body to HAPI FHIR — returning a non-FHIR body or non-2xx status causes HAPI FHIR's delivery client to throw `DataFormatException`, which `RetryingMessageHandlerWrapper` retries indefinitely.

#### Sequence Diagram

```mermaid
sequenceDiagram
    participant FS as FHIR Server
    participant CB as CallbackController
    participant FE as ForwardingEngine
    participant PL as ResourceEnrichmentOrchestrator
    participant NI as NationalIdEnrichmentStrategy
    participant PR as PractitionerDisplayStrategy
    participant LO as LocationEnrichmentStrategy
    participant NR as NationalIdResolver
    participant T as OpenHIM

    FS->>CB: PUT /callback/{key}/{Type}/{id} (FHIR JSON)
    CB->>FE: forward(callbackKey, resourceJson)
    FE->>FE: Parse FHIR metadata (resourceType, resourceId)

    alt Structurally invalid payload (not JSON object, blank resourceType)
        FE->>FE: Increment forward.skipped counter
        FE-->>CB: ForwardResult.skipped()
        CB-->>FS: 200 OK (empty body)
    else Valid payload
        FE->>PL: enrich(context)
        Note over PL: Runs strategies in @Order sequence (100, 200, 300)

        PL->>NI: enrich(context) — @Order(100)
        NI->>NR: resolveNationalIdFromPayload(payload, resourceType)
        alt Identity-resource (e.g. RelatedPerson)
            NR->>NR: Extract national-id from own identifier[]
            NR-->>NI: national-id
        else Other resource (e.g. Encounter)
            NR->>NR: Walk configured path → find identity-resource reference
            NR->>FS: GET /{personIdentityResourceType}/{id}?_elements=identifier
            FS-->>NR: Identity-resource JSON
            NR->>NR: Apply match strategies (use-official → type-code → system-suffix)
            NR-->>NI: national-id
        end
        NI->>NI: Set subject/patient/identifier[] based on RuntimeResourceDefinition

        PL->>PR: enrich(context) — @Order(200)
        PR->>PR: Find Practitioner ref at configured path (array fan-out)
        opt Practitioner found, display absent
            PR->>FS: GET /Practitioner/{id}?_elements=name
            FS-->>PR: Practitioner JSON
            PR->>PR: Set display on reference node
        end

        PL->>LO: enrich(context) — @Order(300)
        alt Organization-path mode
            LO->>LO: Find Organization ref at configured path
            LO->>FS: GET /Organization/{id}?_elements=name
            FS-->>LO: Organization JSON
            LO->>LO: Populate location field with Organization ref + display
        else Generic mode
            LO->>LO: Check location field in payload
            alt Location reference exists with display
                LO->>LO: Skip (already enriched)
            else Location reference exists without display
                LO->>FS: GET /Location/{id}?_elements=name
                FS-->>LO: Location JSON
                LO->>LO: Set display on location reference
            else No location reference in payload
                LO->>LO: Find Encounter ref at configured path (fallback)
                LO->>FS: GET /Encounter/{id}?_elements=location
                FS-->>LO: Encounter JSON
                LO->>LO: Extract location data from Encounter, set on payload
            end
        end

        PL-->>FE: enrichment complete (in-place mutation)
        FE->>FE: Build headers (auth)
        FE->>T: POST /fhir/{ResourceType} (enriched FHIR JSON)
        alt Success
            T-->>FE: 200 OK
            FE->>FE: Increment forward.success counter
        else Failure (4xx/5xx/unreachable)
            T-->>FE: Error / connection failure
            FE->>FE: Increment forward.failure counter, log WARN
        end
    end
    FE-->>CB: forwarding result
    CB-->>FS: 200 OK (empty body — always, regardless of outcome)
```

### 8.2 Startup Auto-Subscription Flow

When `emitter.startup-subscriptions.enabled=true`, the service reconciles subscriptions on startup — creating missing ones and deleting stale adaptor-owned ones:

| Step | Component | Action |
|------|-----------|--------|
| 1 | **StartupSubscriptionRunner** | `ApplicationRunner.run()` triggered by Spring after context initialization |
| 2 | **StartupSubscriptionRunner** | Reads resource types from `emitter.startup-subscriptions.resource-types` configuration |
| 3 | **StartupSubscriptionRunner** | Sleeps for `delay-seconds` (default 10s) to allow the FHIR server to become ready |
| 4 | **StartupSubscriptionRunner** | Parses entries and delegates to `registrationService.subscribeAll(resourceTypeEntries)` |
| 5 | **SubscriptionRegistrationService** | Bulk-fetches existing **adaptor-owned** subscriptions from the FHIR server by owner tag (`https://openphc.org/cce/fhir-emitter|fhir-cce-emitter-adaptor`) with configurable retry + exponential backoff (`fetch-retry-max-attempts`, `fetch-retry-backoff-ms`; interval doubles each attempt: base × 2^(attempt-1), e.g. 15s → 30s → 60s). Only tagged subscriptions are loaded — non-adaptor subscriptions are never seen. **Fail-fast**: if all retry attempts are exhausted, throws `IllegalStateException`. |
| 6 | **SubscriptionRegistrationService** | For each configured resource type: if an adaptor-owned subscription already exists, creation is skipped (`already-exists`); otherwise, a new subscription is created (`registered`). |
| 7 | **SubscriptionRegistrationService** | Identifies stale subscriptions — adaptor-owned subscriptions on the server whose resource type is no longer in the configured list — and deletes them (`deleted`). Delete failures are non-fatal (`delete-failed`). |
| 8 | **StartupSubscriptionRunner** | Logs per-resource result and summary (N succeeded, M deleted, P failed). If `subscribeAll()` throws `IllegalStateException` (fetch retries exhausted), logs FATAL and re-throws — aborting Spring Boot startup. If Docker `restart: unless-stopped` (or equivalent) is configured, the container restarts automatically; otherwise, manual restart is required. Individual creation/deletion failures are non-fatal. |

> **Adaptor-owned subscriptions only:** The service identifies its own subscriptions by the owner tag (`https://openphc.org/cce/fhir-emitter|fhir-cce-emitter-adaptor`) added to each subscription's `meta.tag[]`. Only tagged subscriptions are loaded during the bulk-fetch query — non-adaptor subscriptions (created by other systems or tools) are completely invisible to the reconciliation logic and are **never modified or deleted**.

> **Non-fatal for individual subscriptions, fail-fast for bulk-fetch:** Individual subscription creation/deletion failures are logged but never thrown — the FHIR server may not support some resource types, and that's OK. However, if the initial bulk-fetch of existing subscriptions fails (FHIR server unreachable), the service aborts startup to prevent creating duplicate subscriptions. To enable automatic recovery, configure `restart: unless-stopped` in Docker Compose (or equivalent restart policy in your orchestrator). Without a restart policy, the container stays stopped and requires manual intervention.

---

## 9. Stateless Design

The FHIR CCE Emitter Adaptor is **intentionally stateless** — it has no database and no persistent local state:

| Aspect | Design |
|--------|--------|
| **Subscription tracking** | In-memory `HashMap` keyed by `resourceType|criteria` — built fresh from FHIR server bulk-fetch (by owner tag) on each startup; used for duplicate detection during creation and for identifying stale subscriptions to delete |
| **Auto-resubscription** | With `startup-subscriptions.enabled=true`, subscriptions are reconciled automatically on restart: missing ones are created, stale adaptor-owned ones are deleted. Non-adaptor subscriptions are never touched. |
| **Token cache** | In-memory `ConcurrentHashMap` keyed by token URL — rebuilt on first use after restart |
| **No DB required** | No Flyway, no JPA, no PostgreSQL — zero data persistence infrastructure |
| **Single instance** | Designed to run as a single instance (in-memory tracking is not shared) |

### Why Stateless?

- The FHIR server is the source of truth for subscriptions, not the emitter.
- REST-hook subscriptions persist on the FHIR server even when the emitter restarts.
- The `StartupSubscriptionRunner` reconciles subscriptions on each startup — if an adaptor-owned subscription already exists (detected by owner tag), creation is skipped. Stale adaptor-owned subscriptions for resource types no longer in the configured list are deleted. Non-adaptor subscriptions on the same server are completely ignored.
- No event deduplication required — the FHIR server manages subscription state; CCE handles idempotency via CloudEvents `id` + `source` downstream.

---

## 10. Multi-Auth Architecture

### FHIR Server Authentication

The service supports five authentication types for connecting to the FHIR server:

| Type | Description | Configuration Fields |
|------|-------------|---------------------|
| `none` | No authentication | — |
| `basic` | HTTP Basic Auth | `username`, `password` |
| `bearer` | Static Bearer token | `token` |
| `token-endpoint` | Fetch token from an HTTP endpoint (e.g., Keycloak, custom auth service) | `token-url`, `username`, `password`, `client` |
| `oauth2` | OAuth2 Client Credentials grant (industry standard) | `token-url`, `client-id`, `client-secret`, `scope` |

The `token-endpoint` type authenticates by POSTing credentials to a configurable token URL. Token extraction supports multiple response formats:
1. `Set-Cookie` header (configurable cookie name, optional base64 decoding)
2. `Authorization` response header
3. JSON response body field (configurable field name)
4. Raw response body (fallback)

The `oauth2` type implements the standard **OAuth2 Client Credentials grant** (`grant_type=client_credentials`). It POSTs `client_id`, `client_secret`, and optional `scope` to the token URL and extracts `access_token` from the JSON response. If `expires_in` is present in the response, it is used for token TTL; otherwise, the configured `token-ttl-seconds` (default: 3600) is used. This is the industry standard for server-to-server authentication with Keycloak, Azure AD, Google Cloud, Okta, Auth0, and other OAuth2 providers.

### OpenHIM Authentication

OpenHIM supports multiple client authentication mechanisms. It supports four authentication types:

| Type | Description | Configuration Fields |
|------|-------------|---------------------|
| `none` | No authentication | — |
| `basic` | HTTP Basic Auth | `username`, `password` |
| `jwt` | JSON Web Token | `token` |
| `custom-token` | OpenHIM Custom Token | `token` |

---

## 11. What This Service Does NOT Do

| Exclusion | Rationale |
|-----------|-----------|
| **Transform FHIR resource structure** | Enrichment is limited to: (a) setting `subject.reference` or `patient.reference` to `Patient/<national-id>` (or adding to `identifier[]`), and (b) populating Practitioner `display` names on reference nodes at configured paths. All other fields are forwarded as-is. Full structural transformation happens downstream (OpenHIM Emitter Adaptor mediator or CCE). |
| **Wrap in CloudEvents envelopes** | CloudEvents wrapping happens in the OpenHIM Emitter Adaptor (mediator) or CCE Collector Service |
| **Validate FHIR profile conformance** | Only structural parse for metadata extraction (type, ID); no profile validation |
| **Persist state to a database** | Stateless — subscription tracking is in-memory, reconciled from server |
| **Produce or consume Kafka events** | HTTP-only forwarding; Kafka is used within CCE core, not in adaptors |
| **Deduplicate events** | FHIR server manages subscription state; CCE handles idempotency via CloudEvents `id` + `source` |
| **Rate-limit or apply mTLS** | Infrastructure-layer concerns handled by API gateway / service mesh |
| **Perform compliance tracking** | That is CCE core's responsibility (Compliance Service) |
| **Act as a Receiver Adaptor** | It only emits events toward CCE; it does not receive intelligence events from CCE |
