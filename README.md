# FHIR CCE Emitter Adaptor

## Overview

The **FHIR CCE Emitter Adaptor** is a **Spring Boot 3.4 / Java 21** microservice that acts as a FHIR-specific Emitter Adaptor for the Care Coordination Engine (CCE) platform. It subscribes to FHIR resource changes on a configured FHIR R4 server (via REST-hook Subscriptions) and forwards received resources to **OpenHIM**, where an OpenHIM Emitter Adaptor (registered as a mediator) wraps and routes events to CCE.

The adaptor is deployed on the **source system side**, co-located with the participating system's FHIR server (e.g., SPICE's HAPI FHIR server). It captures FHIR resource changes, enriches them with a **Patient subject reference** (by resolving a national-id from the associated RelatedPerson), and forwards the enriched FHIR JSON to OpenHIM. CloudEvents wrapping happens downstream in the OpenHIM mediator.

## Tech Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| Java | 21 LTS | Runtime |
| Spring Boot | 3.4.x | Application framework |
| Gradle | 8.x (Groovy DSL) | Build tool |
| HAPI FHIR | 7.4.0 | FHIR R4 parsing & client (server-agnostic SDK) |
| Micrometer + Prometheus | — | Metrics & monitoring |
| WireMock | 3.9.x | Integration test stubs |

## Quick Start

```bash
# Prerequisites: Java 21, Docker

# 1. Build
./gradlew clean build

# 2. Run locally
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. Test
curl -s http://localhost:9090/actuator/health | jq
# → { "status": "UP" }

# 4. Ping callback endpoint (simulates FHIR server subscription verification)
curl http://localhost:9090/callback/Patient
# → OK

# 5. Send a test callback (FHIR Encounter with RelatedPerson reference)
curl -X POST http://localhost:9090/callback/Encounter \
  -H "Content-Type: application/fhir+json" \
  -d '{"resourceType":"Encounter","id":"456","participant":[{"individual":{"reference":"RelatedPerson/789"}}]}'
# → enriched with subject.reference = Patient/<national-id> and forwarded to OpenHIM
# → or skipped if RelatedPerson/789 has no national-id
```

## Architecture

```
FHIR R4 Server (e.g. SPICE HAPI FHIR)
     │  REST-hook Subscription callbacks
     ▼
┌──────────────────────────────────┐
│  ★ FHIR CCE Emitter Adaptor ★   │  ← this service
│  Receive → Enrich → Forward      │
└──────────────┬───────────────────┘
               │  HTTP POST (enriched FHIR JSON)
               ▼
OpenHIM → CCE Collector → Kafka → Compliance
```

### Key Components

| Component | Description |
|-----------|-------------|
| `SubscriptionCallbackController` | `@RestController` — receives PUT/POST callbacks from the FHIR server at `/callback/{resourceType}/**`. Always returns `200 OK` immediately. |
| `ForwardingEngine` | Parses FHIR metadata, delegates to `ResourceEnrichmentOrchestrator`, then POSTs enriched JSON to OpenHIM. Single attempt, no retry. |
| `ResourceEnrichmentOrchestrator` | Orchestrates enrichment by running `EnrichmentStrategy` beans in injection order. |
| `NationalIdEnrichmentStrategy` | Resolves national-id via `NationalIdResolver`, sets `subject.reference`, `patient.reference`, or `identifier[]`. |
| `PractitionerDisplayEnrichmentStrategy` | Populates Practitioner `display` names from the FHIR server via `PractitionerResolver`. |
| `LocationEnrichmentStrategy` | Dual-mode location enrichment (organization-path or generic/encounter-based). |
| `NationalIdResolver` | Walks configured paths to find identity-source references, fetches from FHIR server, applies match strategies (`use-official`, `type-code`, `system-suffix`). |
| `FhirResourceFetcher` | Shared service for fetching FHIR resources via `GET /{type}/{id}?_elements=fields`. |
| `SubscriptionRegistrationService` | Manages R4 `Subscription` resources on the FHIR server — creates missing, deletes stale (by owner tag) |
| `StartupSubscriptionRunner` | `ApplicationRunner` — reconciles subscriptions on startup (create missing, delete stale) |
| `TokenEndpointAuthService` | Authenticates with token endpoints (custom or OAuth2 Client Credentials) for the FHIR server |
| `FhirClientFactory` | Creates authenticated HAPI FHIR `IGenericClient` instances (shared by subscription and resolution services) |
| `EmitterProperties` | `@ConfigurationProperties` — type-safe config for FHIR server, OpenHIM, auth, subscriptions, reference resolution |

### Design Decisions

- **Stateless** — no database; subscription tracking is in-memory, reconciled from the FHIR server on startup
- **Patient Subject Resolution** — enriches each resource with `subject.reference = Patient/<national-id>` by resolving the identity-source resource at a configurable JSON path; enrichment failures are logged but do not block forwarding
- **Strategy/Orchestrator pattern** — enrichment is modular: each concern (national-id, practitioner display, location) is an independent `EnrichmentStrategy` bean, run in injection order
- **Synchronous forwarding** — callbacks are enriched and forwarded synchronously; single attempt, no retry
- **Server-agnostic** — works with any FHIR R4-compliant server (HAPI FHIR, IBM FHIR, Firely, Google Healthcare API, etc.)
- **OpenHIM-targeted** — forwards enriched FHIR resources to OpenHIM; CloudEvents wrapping happens in the OpenHIM mediator downstream
- **Startup-only subscriptions** — no runtime API for subscribe/unsubscribe; change `resource-types` config and restart. Stale subscriptions auto-deleted.
- **Per-connection SSL trust** — trust-all SSL is applied per-RestTemplate, not process-wide
- **Callback body size limited** — 10 MB default via `MAX_HTTP_POST_SIZE` to prevent OOM from oversized payloads

## Project Structure

```
src/main/java/org/openphc/cce/emitter/
├── FhirCceEmitterAdaptorApplication.java
├── config/
│   ├── EmitterProperties.java            # @ConfigurationProperties(prefix="emitter")
│   ├── FhirConfig.java                   # FhirContext.forR4() singleton
│   ├── LoggingFilter.java                # MDC request tracing (requestId, resourceType)
│   ├── ObservabilityConfig.java          # Micrometer common tags
│   ├── RestClientConfig.java             # Standard + trust-all RestTemplate beans
│   └── StartupSubscriptionRunner.java    # Auto-subscribe on startup
├── controller/
│   └── SubscriptionCallbackController.java   # /callback/** endpoint
└── service/
    ├── FhirClientFactory.java            # Authenticated HAPI FHIR client creation (shared)
    ├── ForwardingEngine.java             # Orchestrates enrichment + forwards FHIR JSON to OpenHIM
    ├── ForwardResult.java                # Forwarding outcome record
    ├── RegistrationResult.java           # Subscription registration outcome record
    ├── SubscriptionRegistrationService.java  # FHIR Subscription CRUD (startup reconciliation)
    ├── TokenEndpointAuthService.java     # Token endpoint + OAuth2 token fetching
    ├── enrichment/
    │   ├── EnrichmentStrategy.java       # Strategy interface (enrich)
    │   ├── EnrichmentContext.java        # Context record (payload, resourceType, resourceId)
    │   ├── ResourceEnrichmentOrchestrator.java   # Orchestrator: runs strategies in injection order
    │   ├── NationalIdEnrichmentStrategy.java # National-id resolution
    │   ├── PractitionerDisplayEnrichmentStrategy.java  # Practitioner display
    │   └── LocationEnrichmentStrategy.java   # Dual-mode location
    └── resolver/
        ├── FhirResourceFetcher.java      # Shared: GET /{type}/{id}?_elements=fields
        ├── ResolverPathHelper.java       # Static utility: dot-path walking, array fan-out
        ├── NationalIdResolver.java       # National-id resolution (path + match strategies)
        ├── PractitionerResolver.java     # Practitioner ref extraction + display fetch
        ├── OrganizationResolver.java     # Organization ID extraction + display fetch
        └── LocationResolver.java         # Encounter-based location derivation
```

## Testing

### Unit Tests

```bash
./gradlew test
```

### Integration Tests

Integration tests boot the full Spring context with WireMock-stubbed FHIR server and OpenHIM:

| Test Class | Scope |
|-----------|-------|
| `CallbackForwardIntegrationTest` | End-to-end: callback POST/PUT → enrich → forward to OpenHIM |
| `ErrorHandlingIntegrationTest` | Malformed body, ping passthrough, error propagation |
| `OpenhimBasicAuthIntegrationTest` | OpenHIM Basic auth header verification |
| `OpenhimJwtAuthIntegrationTest` | OpenHIM JWT Bearer auth |
| `OpenhimCustomTokenAuthIntegrationTest` | OpenHIM Custom Token auth |
| `FhirServerTokenAuthIntegrationTest` | Token-endpoint auth flow (cookie/header/body extraction) |
| `FhirServerOAuth2AuthIntegrationTest` | OAuth2 Client Credentials grant flow |
| `StartupSubscriptionIntegrationTest` | Startup auto-subscription with WireMock FHIR server |
| `HealthEndpointIntegrationTest` | Health endpoint and actuator probes |

All integration tests use `@ActiveProfiles("integration-test")` with `application-integration-test.yml`, which configures a random server port, fast retry backoff, and disabled startup subscriptions.

```bash
# Run integration tests only
./gradlew test --tests "org.openphc.cce.emitter.integration.*"

# Run all tests (189 total: 167 unit + 22 integration)
./gradlew test
```

## Docker

### Build Image

```bash
docker build -t fhir-cce-emitter-adaptor .
```

### Local Development Stack

```bash
# Start the containerised service
docker compose up --build
```

The multi-stage Dockerfile uses JDK 21 for build and JRE 21 for runtime. The container runs as non-root `appuser` on port 9090.

## Configuration

All configuration is driven by environment variables with sensible defaults. No separate Docker profile needed — the base `application.yml` uses `${ENV_VAR:default}` syntax for all configurable values.

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP server port | `9090` |
| `SHUTDOWN_TIMEOUT` | Graceful shutdown timeout | `30s` |
| `MAX_HTTP_POST_SIZE` | Max callback body size (OOM protection) | `10MB` |
| `EMITTER_SELF_BASE_URL` | Base URL of this service (reachable by FHIR server) | `http://localhost:9090` |
| **FHIR Server** | | |
| `FHIR_SERVER_NAME` | FHIR server display name | `default-fhir` |
| `FHIR_SERVER_BASE_URL` | FHIR server base URL | `http://localhost:8090/fhir` |
| `FHIR_SERVER_AUTH_TYPE` | Auth type: `none`, `basic`, `bearer`, `token-endpoint`, `oauth2` | `token-endpoint` |
| `FHIR_SERVER_TOKEN_URL` | Token endpoint URL (for `token-endpoint` / `oauth2`) | *(empty)* |
| `FHIR_SERVER_AUTH_USERNAME` | Username (for `basic` / `token-endpoint`) | *(empty)* |
| `FHIR_SERVER_AUTH_PASSWORD` | Password (for `basic` / `token-endpoint`) | *(empty)* |
| `FHIR_SERVER_AUTH_CLIENT` | Client type header (for `token-endpoint`) | `web` |
| `FHIR_SERVER_OAUTH2_CLIENT_ID` | OAuth2 client ID (for `oauth2`) | *(empty)* |
| `FHIR_SERVER_OAUTH2_CLIENT_SECRET` | OAuth2 client secret (for `oauth2`) | *(empty)* |
| `FHIR_SERVER_OAUTH2_SCOPE` | OAuth2 scope (for `oauth2`) | *(empty)* |
| `FHIR_SERVER_TOKEN_TTL_SECONDS` | Token cache TTL in seconds | `3600` |
| **OpenHIM** | | |
| `OPENHIM_NAME` | OpenHIM display name | `openhim` |
| `OPENHIM_BASE_URL` | OpenHIM base URL | `http://localhost:5001/fhir` |
| `OPENHIM_AUTH_TYPE` | Auth type: `none`, `basic`, `jwt`, `custom-token` | `basic` |
| `OPENHIM_AUTH_USERNAME` | Username (for `basic`) | *(empty)* |
| `OPENHIM_AUTH_PASSWORD` | Password (for `basic`) | *(empty)* |
| `OPENHIM_AUTH_TOKEN` | Token (for `jwt` / `custom-token`) | *(empty)* |
| `OPENHIM_SSL_TRUST_ALL` | Trust all SSL certs for OpenHIM | `false` |
| `OPENHIM_APPEND_RESOURCE_TYPE` | Append FHIR resource type to URL | `true` |
| **Reference Resolution** | | |
| `EMITTER_REFERENCE_RESOLUTION_ENABLED` | Enable Patient subject enrichment | `true` |
| `EMITTER_PERSON_IDENTITY_REFERENCE_PATHS` | `ResourceType:dot.path` entries for locating RelatedPerson references | *(4 defaults)* |
| `EMITTER_NATIONAL_ID_MATCH_STRATEGIES` | Ordered strategies: `use-official`, `type-code`, `system-suffix` | `use-official,type-code,system-suffix` |
| `EMITTER_NATIONAL_ID_SYSTEM_SUFFIX` | Suffix for `system-suffix` strategy | `/national-id` |
| `EMITTER_NATIONAL_ID_TYPE_CODE` | HL7 code for `type-code` strategy | `NI` |
| **Startup Subscriptions** | | |
| `EMITTER_STARTUP_SUBSCRIPTIONS_ENABLED` | Auto-subscribe on startup | `false` |
| `EMITTER_STARTUP_DELAY_SECONDS` | Delay before subscribing (server readiness) | `10` |
| `EMITTER_STARTUP_FETCH_PAGE_SIZE` | Max existing subscriptions to fetch | `500` |
| `EMITTER_STARTUP_RESOURCE_TYPES` | Comma-separated FHIR resource types | *(21 defaults)* |
| **Observability** | | |
| `HEALTH_SHOW_DETAILS` | Health detail visibility | `when-authorized` |
| `LOG_LEVEL_ROOT` | Root log level | `INFO` |
| `LOG_LEVEL_APP` | Application log level (`org.openphc.cce`) | `INFO` |

### Profiles

| Profile | Purpose | Key overrides |
|---------|---------|---------------|
| `default` | Base config (env-var-wrapped) | All defaults |
| `local` | Local dev | DEBUG logging, SSL trust-all, startup subscriptions on |
| `staging` | Pre-production | INFO logging, startup subscriptions on |
| `production` | Production | WARN root, JSON logging, 45s shutdown, health details hidden |

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture Overview](docs/architecture.md) | System context, processing pipeline, key components |
| [API Reference](docs/api-reference.md) | All endpoints, request/response examples |
| [Configuration Guide](docs/configuration-guide.md) | Environment variables, profiles, auth setup |
| [Deployment Guide](docs/deployment-guide.md) | Docker, Kubernetes, production checklist |
| [Operations Runbook](docs/operations-runbook.md) | Troubleshooting, health checks, log analysis |

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/callback/{resourceType}/**` | PUT/POST | REST-hook callback from FHIR server |
| `/callback/{resourceType}/**` | GET/HEAD | Ping — FHIR server verifies endpoint reachability |
| `/actuator/health` | GET | Health status |
| `/actuator/health/liveness` | GET | Liveness probe (Kubernetes) |
| `/actuator/health/readiness` | GET | Readiness probe (Kubernetes) |
| `/actuator/prometheus` | GET | Prometheus metrics |

## License
