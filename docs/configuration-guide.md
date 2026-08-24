# Configuration Guide — FHIR CCE Emitter Adaptor

## 1. Profile Hierarchy

The service uses Spring Boot profiles to separate environment-specific configuration:

```
application.yml              ← Base config (all values env-var-wrapped with defaults)
  ├── application-local.yml   ← Local development overrides
  ├── application-staging.yml ← Pre-production tuning
  └── application-production.yml ← Production hardening
```

| Profile | Purpose | Key Overrides |
|---------|---------|---------------|
| `default` | Base configuration | All values env-var-wrapped with sensible defaults |
| `local` | Local development | `DEBUG` logging, `ssl-trust-all: true`, `show-details: always`, startup subscriptions enabled |
| `staging` | Pre-production | `INFO` logging, tuned async pool |
| `production` | Production | `WARN` root logging, `show-details: never`, larger pool, more retries |

> **No `docker` profile needed.** The base `application.yml` uses `${ENV_VAR:default}` syntax for all configurable values. Docker/K8s deployments just set environment variables without requiring a separate profile.

---

## 2. Configuration Properties

All configuration is bound to `EmitterProperties` via `@ConfigurationProperties(prefix = "emitter")`.

### Top-Level Structure

```yaml
emitter:
  self-base-url: "..."        # Callback URL base (must be reachable by FHIR server)
  fhir-server:                # Single FHIR R4 server
    name: "..."
    url: "..."
    auth: { ... }
  openhim:                    # OpenHIM connection
    name: "..."
    base-url: "..."
    auth: { ... }             # none, basic, jwt, or custom-token
  startup-subscriptions:      # Auto-subscribe on startup
    enabled: false
    delay-seconds: 10
  reference-resolution:        # Reference resolution — national-id extraction from identity-source resource
    person-identity-resource-type: "RelatedPerson"    # FHIR resource type used as identity source (RelatedPerson, Patient, etc.)
    national-id-match-strategies: [use-official, type-code, system-suffix]
    national-id-system-suffix: "/national-id"
    national-id-type-code: "NI"
    national-id-identifier-system: "http://openphc.org/identifier/upid"   # System URI used when adding national-id to identifier[]
    person-identity-reference-paths: Encounter:participant.individual,ServiceRequest:performer,Observation:performer   # ResourceType:dot.path entries for locating identity-source references
    practitioner-display-paths: Encounter:participant.individual,Observation:performer,ServiceRequest:performer,Condition:asserter   # ResourceType:dot.path entries for Practitioner display name enrichment
    location-enrichment-based-on-organization-path: true   # Mode: true=organization-path, false=generic (3-step cascade)
    location-from-organization-paths: Encounter:serviceProvider,ServiceRequest:performer   # ResourceType:dot.path for Organization reference (used in org-path mode)
    location-from-encounter-paths: Observation:encounter,ServiceRequest:encounter,Condition:encounter,MedicationRequest:encounter   # ResourceType:dot.path for Encounter reference (used in generic mode)
```

### Config Classes

| Class | Prefix | Description |
|-------|--------|-------------|
| `EmitterProperties` | `emitter` | Root config with `@Valid @NotNull` nested objects |
| `FhirServerConfig` | `emitter.fhir-server` | FHIR server name, URL, auth |
| `FhirServerAuthConfig` | `emitter.fhir-server.auth` | Auth type + credentials for FHIR server |
| `OpenhimConfig` | `emitter.openhim` | OpenHIM name, URL, auth, SSL |
| `OpenhimAuthConfig` | `emitter.openhim.auth` | Auth type + credentials for OpenHIM (none, basic, jwt, or custom-token) |
| `StartupSubscriptionConfig` | `emitter.startup-subscriptions` | Auto-subscribe toggle and delay |
| `ReferenceResolutionConfig` | `emitter.reference-resolution` | Identity-resource type, national-id match strategies, identifier system URI, per-resource-type paths for identity-source references, Practitioner display enrichment, and dual-mode location enrichment (organization-path or generic 3-step cascade) |

---

## 3. Base Configuration (`application.yml`)

```yaml
server:
  port: ${SERVER_PORT:9090}
  shutdown: graceful
  servlet:
    context-path: /

spring:
  application:
    name: fhir-cce-emitter-adaptor
  lifecycle:
    timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:30s}

emitter:
  self-base-url: "${EMITTER_SELF_BASE_URL:http://localhost:9090}"

  fhir-server:
    name: "${FHIR_SERVER_NAME:default-fhir}"
    url: "${FHIR_SERVER_BASE_URL:http://localhost:8090/fhir}"
    auth:
      type: "${FHIR_SERVER_AUTH_TYPE:token-endpoint}"      # none | basic | bearer | token-endpoint | oauth2
      token-url: "${FHIR_SERVER_TOKEN_URL:}"
      username: "${FHIR_SERVER_AUTH_USERNAME:}"
      password: "${FHIR_SERVER_AUTH_PASSWORD:}"
      client: "${FHIR_SERVER_AUTH_CLIENT:web}"
      token-cookie-name: "${FHIR_SERVER_TOKEN_COOKIE_NAME:AuthCookie}"
      token-cookie-base64: ${FHIR_SERVER_TOKEN_COOKIE_BASE64:true}
      token-body-field: "${FHIR_SERVER_TOKEN_BODY_FIELD:}"
      # OAuth2 Client Credentials grant
      client-id: "${FHIR_SERVER_OAUTH2_CLIENT_ID:}"
      client-secret: "${FHIR_SERVER_OAUTH2_CLIENT_SECRET:}"
      scope: "${FHIR_SERVER_OAUTH2_SCOPE:}"
      token-ttl-seconds: ${FHIR_SERVER_TOKEN_TTL_SECONDS:3600}

  openhim:
    name: "${OPENHIM_NAME:openhim}"
    base-url: "${OPENHIM_BASE_URL:http://localhost:5001/fhir}"
    auth:
      type: "${OPENHIM_AUTH_TYPE:basic}"                    # none | basic | jwt | custom-token
      username: "${OPENHIM_AUTH_USERNAME:}"
      password: "${OPENHIM_AUTH_PASSWORD:}"
      token: "${OPENHIM_AUTH_TOKEN:}"
    ssl-trust-all: ${OPENHIM_SSL_TRUST_ALL:false}
    append-resource-type: ${OPENHIM_APPEND_RESOURCE_TYPE:true}

  startup-subscriptions:
    enabled: ${EMITTER_STARTUP_SUBSCRIPTIONS_ENABLED:true}
    delay-seconds: ${EMITTER_STARTUP_DELAY_SECONDS:10}
    resource-types: ${EMITTER_STARTUP_RESOURCE_TYPES:Patient,RelatedPerson,Encounter,Observation,Condition,MedicationRequest,MedicationDispense,MedicationStatement,DiagnosticReport,QuestionnaireResponse,ServiceRequest,CarePlan,Appointment,Group,Location,Organization,Practitioner,Coverage,PaymentNotice,Device,Provenance}

  # Reference resolution — national-id extraction from identity-source resource
  reference-resolution:
    person-identity-resource-type: ${EMITTER_PERSON_IDENTITY_RESOURCE_TYPE:RelatedPerson}
    national-id-match-strategies: ${EMITTER_NATIONAL_ID_MATCH_STRATEGIES:use-official,type-code,system-suffix}
    national-id-system-suffix: "${EMITTER_NATIONAL_ID_SYSTEM_SUFFIX:/national-id}"
    national-id-type-code: "${EMITTER_NATIONAL_ID_TYPE_CODE:NI}"
    national-id-identifier-system: "${EMITTER_NATIONAL_ID_IDENTIFIER_SYSTEM:http://openphc.org/identifier/upid}"
    person-identity-reference-paths: ${EMITTER_PERSON_IDENTITY_REFERENCE_PATHS:Encounter:participant.individual,ServiceRequest:performer,Observation:performer,Patient:link.other}
    practitioner-display-paths: ${EMITTER_PRACTITIONER_DISPLAY_PATHS:Encounter:participant.individual,Observation:performer,ServiceRequest:performer,Condition:asserter}
    location-enrichment-based-on-organization-path: ${EMITTER_LOCATION_ENRICHMENT_BASED_ON_ORGANIZATION_PATH:true}
    location-from-organization-paths: ${EMITTER_LOCATION_FROM_ORGANIZATION_PATHS:Encounter:serviceProvider,ServiceRequest:performer}
    location-from-encounter-paths: ${EMITTER_LOCATION_FROM_ENCOUNTER_PATHS:Observation:encounter,ServiceRequest:encounter,Condition:encounter,MedicationRequest:encounter}

logging:
  level:
    root: ${LOG_LEVEL_ROOT:INFO}
    org.openphc.cce: ${LOG_LEVEL_APP:INFO}
    org.springframework.web: ${LOG_LEVEL_SPRING_WEB:INFO}
    ca.uhn.fhir: ${LOG_LEVEL_FHIR:INFO}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: ${HEALTH_SHOW_DETAILS:when-authorized}
      probes:
        enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
```

---

## 4. FHIR Server Configuration

### `emitter.fhir-server`

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `name` | string | Yes | `default-fhir` | Display name used in logs and API responses |
| `url` | string | Yes | `http://localhost:8090/fhir` | FHIR R4 server base URL |
| `auth.type` | string | No | `none` | Auth type: `none`, `basic`, `bearer`, `token-endpoint`, `oauth2` |
| `auth.username` | string | Conditional | — | Username (required for `basic` and `token-endpoint`) |
| `auth.password` | string | Conditional | — | Password (required for `basic` and `token-endpoint`) |
| `auth.token` | string | Conditional | — | Static Bearer token (required for `bearer`) |
| `auth.token-url` | string | Conditional | — | Token endpoint URL (required for `token-endpoint` and `oauth2`) |
| `auth.client` | string | No | `web` | Client type header sent to token endpoint |
| `auth.token-cookie-name` | string | No | `AuthCookie` | Cookie name to extract JWT from Set-Cookie |
| `auth.token-cookie-base64` | boolean | No | `true` | Whether the cookie value is base64-encoded |
| `auth.token-body-field` | string | No | — | JSON field name for token in response body |
| `auth.client-id` | string | Conditional | — | OAuth2 client ID (required for `oauth2`) |
| `auth.client-secret` | string | Conditional | — | OAuth2 client secret (required for `oauth2`) |
| `auth.scope` | string | No | — | OAuth2 scope (optional, space-separated for multiple scopes) |
| `auth.token-ttl-seconds` | long | No | `3600` | Token cache TTL in seconds (overridden by `expires_in` for `oauth2`) |

### FHIR Server Auth Types

#### `none`
No authentication. The FHIR server is accessed without credentials.

#### `basic`
HTTP Basic Auth. Requires `username` and `password`.

```yaml
emitter:
  fhir-server:
    auth:
      type: basic
      username: fhir-user
      password: fhir-pass
```

#### `bearer`
Static Bearer token. Requires `token`.

```yaml
emitter:
  fhir-server:
    auth:
      type: bearer
      token: "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

#### `token-endpoint`
Fetch a token from an HTTP endpoint (e.g., Keycloak, custom auth service). POSTs credentials to `token-url` and extracts the token from the response.

```yaml
emitter:
  fhir-server:
    auth:
      type: token-endpoint
      token-url: "https://keycloak.example.com/auth/realms/fhir/protocol/openid-connect/token"
      username: fhir-user
      password: fhir-pass
      client: web
```

**Token extraction priority:**
1. `Set-Cookie` header — looks for cookie named `token-cookie-name` (default: `AuthCookie`), optionally base64-decodes the value
2. `Authorization` response header — extracts the token value
3. JSON response body — extracts the field named `token-body-field` (e.g., `access_token`, `token`) via Jackson ObjectMapper
4. Raw response body — fallback, treats the entire body as the token

Tokens are cached in-memory with a configurable TTL (default: 3600 seconds). `TokenEndpointAuthService` handles caching and refresh.

#### `oauth2`
Standard OAuth2 Client Credentials grant (`grant_type=client_credentials`). This is the **industry standard** for server-to-server authentication with Keycloak, Azure AD, Google Cloud, Okta, Auth0, and other OAuth2 providers. Requires `token-url`, `client-id`, and `client-secret`.

```yaml
emitter:
  fhir-server:
    auth:
      type: oauth2
      token-url: "https://keycloak.example.com/auth/realms/fhir/protocol/openid-connect/token"
      client-id: fhir-emitter-client
      client-secret: "s3cr3t-k3y"
      scope: "fhir.read"   # optional, space-separated for multiple scopes
```

**How it works:**
1. POSTs to `token-url` with `Content-Type: application/x-www-form-urlencoded`
2. Request body: `grant_type=client_credentials&client_id=...&client_secret=...&scope=...`
3. Extracts `access_token` from the JSON response body:
   ```json
   {
     "access_token": "eyJhbGciOiJSUzI1NiIs...",
     "token_type": "Bearer",
     "expires_in": 3600
   }
   ```
4. If `expires_in` is present in the response, it is used as the token TTL
5. Otherwise, falls back to `token-ttl-seconds` (default: 3600)

Tokens are cached in-memory and automatically refreshed when expired.

> **`token-endpoint` vs `oauth2`:** Use `token-endpoint` for custom auth services (e.g., SPICE) that accept `username`/`password` and return tokens in non-standard formats (cookies, headers). Use `oauth2` for standard OAuth2 providers that support the Client Credentials grant.

---

## 5. OpenHIM Configuration

OpenHIM is the interoperability layer that receives forwarded FHIR resources. An **OpenHIM Emitter Adaptor** (registered as a mediator in OpenHIM) wraps events in CloudEvents envelopes and routes them to CCE.

### `emitter.openhim`

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `name` | string | Yes | `openhim` | Display name for logs and metrics |
| `base-url` | string | Yes | `http://localhost:5001/fhir` | OpenHIM base URL |
| `auth.type` | string | No | `basic` | Auth type: `none`, `basic`, `jwt`, or `custom-token` |
| `auth.username` | string | Conditional | — | Username (required for `basic`) |
| `auth.password` | string | Conditional | — | Password (required for `basic`) |
| `auth.token` | string | Conditional | — | Token string (required for `jwt` and `custom-token`) |
| `ssl-trust-all` | boolean | No | `false` | Trust all SSL certificates for OpenHIM |
| `append-resource-type` | boolean | No | `true` | Append FHIR resource type to OpenHIM URL |

### OpenHIM URL Construction

When `append-resource-type` is `true` (default), the OpenHIM URL includes the FHIR resource type:

```
Base URL:     http://openhim:5001/fhir
Resource:     Patient
OpenHIM URL:  http://openhim:5001/fhir/Patient
```

When `false`, only the base URL is used:

```
Base URL:     http://openhim:5001/fhir
OpenHIM URL:  http://openhim:5001/fhir
```

### SSL Trust-All

When `ssl-trust-all: true`, the `ForwardingEngine` uses a trust-all `RestTemplate` for OpenHIM. The trust-all SSL context is **per-connection** (not process-wide) — it does not affect FHIR server connections or other outbound traffic.

---

## 6. Startup Auto-Subscription

### `emitter.startup-subscriptions`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable automatic subscription on startup |
| `delay-seconds` | int | `10` | Delay before subscribing (allows FHIR server to become ready) |
| `fetch-page-size` | int | `500` | Maximum number of existing subscriptions to fetch in a single query during reconciliation |
| `fetch-retry-max-attempts` | int | `3` | Number of retry attempts for bulk-fetching existing subscriptions from the FHIR server |
| `fetch-retry-backoff-ms` | long | `15000` | Base backoff interval (ms) — doubles each attempt (exponential: 15s → 30s → 60s) |

When `enabled: true`, the `StartupSubscriptionRunner` (`ApplicationRunner`, gated by `@ConditionalOnProperty`) reconciles subscriptions on startup: bulk-fetches existing adaptor-owned subscriptions (with retry + exponential backoff — interval doubles each attempt), creates missing ones for configured resource types, and deletes stale adaptor-owned subscriptions for resource types no longer in the list. If the FHIR server is unreachable after all retry attempts, the service **aborts startup** (fail-fast) to prevent duplicate subscriptions. To enable automatic recovery, configure `restart: unless-stopped` in Docker Compose (or equivalent restart policy in your orchestrator). Without a restart policy, the container stays stopped and requires manual restart.

### Resource Types

Resource types for startup subscription are configured in YAML via `emitter.startup-subscriptions.resource-types`. The default list includes 5 FHIR R4 resource types:

```yaml
emitter:
  startup-subscriptions:
    enabled: true
    delay-seconds: 10
    resource-types:
      - Patient
      - RelatedPerson
      - Encounter
      - Observation
      - Condition
      - MedicationRequest
      - MedicationDispense
      - MedicationStatement
      - DiagnosticReport
      - QuestionnaireResponse
      - ServiceRequest
      - CarePlan
      - Appointment
      - Group
      - Location
      - Organization
      - Practitioner
      - Coverage
      - PaymentNotice
      - Device
      - Provenance
```

To customize for a specific deployment, override the list via YAML or the `EMITTER_STARTUP_RESOURCE_TYPES` environment variable (comma-separated):

```bash
export EMITTER_STARTUP_RESOURCE_TYPES=Patient,Encounter,Observation,ServiceRequest
```

**Add or remove types by editing the configuration** — no code changes or rebuild required. On the next restart, the reconciliation will create subscriptions for newly added types and delete stale adaptor-owned subscriptions for removed types.

### Startup Flow

1. Application starts → `StartupSubscriptionRunner.run()` triggered
2. Sleep for `delay-seconds` (allows FHIR server to become ready)
3. Parse each resource type entry from `emitter.startup-subscriptions.resource-types`
4. Delegate to `registrationService.subscribeAll(resourceTypeEntries)`
5. Bulk-fetch existing adaptor-owned subscriptions from the FHIR server (by owner tag) with retry + backoff
6. **If fetch fails after all retries** → throw `IllegalStateException` → Spring Boot aborts startup → container restarts automatically if `restart: unless-stopped` is configured; otherwise requires manual restart
7. For each configured entry: if an adaptor-owned subscription already exists, skip (`already-exists`); otherwise create (`registered`)
8. Identify stale subscriptions — adaptor-owned subscriptions on the server not in the configured list — and delete them (`deleted`)
9. Log per-resource result and summary (N succeeded, M deleted, P failed)

**Individual creation/deletion failures are non-fatal** — if a subscription fails (resource type not supported, transient error), remaining subscriptions continue and the application starts normally. Only the bulk-fetch failure (FHIR server completely unreachable) aborts startup.

### Restart Behavior

With `startup-subscriptions.enabled=true`, restarting the emitter automatically reconciles subscriptions:
- **Existing subscriptions** for configured resource types are detected and skipped (`already-exists`)
- **New subscriptions** for resource types added to the config are created (`registered`)
- **Stale subscriptions** for resource types removed from the config are deleted (`deleted`)
- Only adaptor-owned subscriptions (tagged with `https://openphc.org/cce/fhir-emitter|fhir-cce-emitter-adaptor`) are ever loaded or deleted — non-adaptor subscriptions created by other systems are never touched.

---

## 7. Reference Resolution (national-id lookup)

The `ResourceEnrichmentOrchestrator` orchestrates enrichment on every inbound FHIR callback, running registered `EnrichmentStrategy` beans in injection order. The `NationalIdEnrichmentStrategy` resolves the national-id via `NationalIdResolver` and places it on the appropriate field based on the resource type's FHIR R4 definition.

### Enrichment Strategies

`NationalIdEnrichmentStrategy` checks whether the resource type has a `subject` or `patient` field in the FHIR R4 specification using HAPI FHIR's `RuntimeResourceDefinition`. Fields are checked in priority order (`FHIR_R4_PATIENT_REFERENCE_FIELDS = ["subject", "patient"]`) — first match wins:

1. **Has `subject` field** (e.g. Encounter, Observation, ServiceRequest): sets `subject.reference = "Patient/<national-id>"`
2. **Has `patient` field** (e.g. AllergyIntolerance, RelatedPerson, Claim, EpisodeOfCare): sets `patient.reference = "Patient/<national-id>"`
3. **Neither field** (e.g. Location, Organization, Practitioner): adds `{"system": "<configured-system>", "value": "<national-id>"}` to the `identifier[]` array

The identifier system URI is configurable via `national-id-identifier-system` (default: `http://openphc.org/identifier/upid`).

### Forward-as-is Behavior

If national-id resolution fails for any reason (no configured path, no identity-source reference at path, no national-id resolved, or any exception), the resource is **forwarded as-is without enrichment** — it is never skipped. Only structurally invalid payloads (not a JSON object, blank `resourceType`) cause the forward to be skipped (returns `null`).

### Identity Source Type

The **identity-resource type** (default: `RelatedPerson`) is the FHIR resource type from which the national-id is extracted. This is configurable via `emitter.reference-resolution.person-identity-resource-type` or the `EMITTER_PERSON_IDENTITY_RESOURCE_TYPE` environment variable. It can be set to `Patient`, `RelatedPerson`, or any resource type that carries a national-id in its `identifier[]`.

**Identity-resource resources (e.g. RelatedPerson):** When the incoming callback is for the configured identity-resource type, the national-id is extracted directly from its own `identifier[]` using the configured match strategies. Enrichment is then applied based on the resource type's FHIR R4 definition — RelatedPerson has a `patient` field, so `patient.reference = "Patient/<national-id>"` is set. If no national-id is found, the resource is forwarded as-is.

**Other resources (e.g. Encounter, Observation):** The resolver looks up the configured JSON path from `person-identity-reference-paths` (e.g. `Encounter:participant.individual`), walks the JSON tree along that path to find an identity-source reference (e.g. `RelatedPerson/499063`). The FHIR resource ID extracted from this reference is called the **`personReferenceIdentifier`** (e.g. `"499063"` from `"RelatedPerson/499063"`) — it identifies which person resource to fetch. The resolver then fetches `GET /{personIdentityResourceType}/{personReferenceIdentifier}?_elements=identifier` from the FHIR server, resolves the national-id via the configured match strategies, and applies the appropriate enrichment strategy.

**Enrichment strategies:** Resources are checked for `subject` and `patient` fields in priority order. For resources with a `subject` field, `subject.reference = "Patient/<national-id>"` is set. For resources with a `patient` field (but no `subject`), `patient.reference = "Patient/<national-id>"` is set. For resources with neither field, `{"system": "<configured-system>", "value": "<national-id>"}` is added to `identifier[]`.

**Forward-as-is rules:** If national-id resolution fails — no configured path for the resource type, no identity-source reference found at the configured path, or no national-id resolved from the identity-source resource — the resource is forwarded as-is without enrichment (never skipped).

### Identity Source Type

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `person-identity-resource-type` | string | `RelatedPerson` | `EMITTER_PERSON_IDENTITY_RESOURCE_TYPE` | The FHIR resource type used as the identity source for national-id extraction. Can be `RelatedPerson`, `Patient`, or any resource type that carries a national-id identifier. |

### Related Person Paths

`emitter.reference-resolution.person-identity-reference-paths` maps FHIR resource types to the JSON path where an identity-source reference (e.g. `RelatedPerson/{personReferenceIdentifier}`) can be found. The **`personReferenceIdentifier`** is the FHIR resource ID portion extracted from the reference string — it identifies which identity-source resource to fetch from the FHIR server. Format: `ResourceType:dot.separated.path`.

```yaml
emitter:
  reference-resolution:
    person-identity-reference-paths:
      - "Encounter:participant.individual"
      - "ServiceRequest:performer"
      - "Observation:performer"
      - "Patient:link.other"
```

or via env var (comma-separated):

```bash
EMITTER_PERSON_IDENTITY_REFERENCE_PATHS=Encounter:participant.individual,ServiceRequest:performer,Observation:performer,Patient:link.other
```

Resources not listed in `person-identity-reference-paths` and that are not the configured identity-resource type will have national-id resolution fail, and will be **forwarded as-is without enrichment** (never skipped).

### Match Strategies

`emitter.reference-resolution.national-id-match-strategies` is an ordered list. Each
strategy is tried against the resource's `identifier[]` array; the first match wins.

| Strategy | Match rule | When to use |
|----------|-----------|-------------|
| `use-official` | `identifier.use == "official"` | FHIR R4 standard; preferred for spec-compliant servers |
| `type-code` | Any `identifier.type.coding[].code` equals `national-id-type-code` (default `NI`, HL7 v2-0203) | FHIR R4 standard with typed identifiers (e.g. national-id, passport) |
| `system-suffix` | `identifier.system.endsWith(national-id-system-suffix)` (default `/national-id`) | Custom servers like SPICE that don't set `use` or `type.coding` |

Defaults try all three in the order above, so a single deployment can transparently
support a mix of source systems.

### Examples

**SPICE HAPI FHIR (default suffix `/national-id`):**

```jsonc
// RelatedPerson/498113 returned by the FHIR server
{
  "resourceType": "RelatedPerson",
  "id": "498113",
  "identifier": [
    { "system": "http://spice/fhir/identity-type", "value": "National ID" },
    { "system": "http://spice/fhir/national-id",    "value": "NID-1774256338" }
  ]
}
// → subject.reference set to "Patient/NID-1774256338"  (matched by `system-suffix`)
```

No configuration override needed — `system-suffix` matches `…/national-id`.

**Flat identifier systems (no `use` or `type.coding` fields):**

```bash
EMITTER_NATIONAL_ID_MATCH_STRATEGIES=system-suffix
EMITTER_NATIONAL_ID_SYSTEM_SUFFIX=NID
```

**Spec-compliant server with `use=official`:**

```jsonc
{
  "resourceType": "RelatedPerson", "id": "123",
  "identifier": [
    { "use": "secondary", "system": "https://example.org/local",    "value": "L-55" },
    { "use": "official",  "system": "https://example.org/national", "value": "NID-10001" }
  ]
}
// → subject.reference set to "Patient/NID-10001"  (matched by `use-official`, first in the list)
```

### Resolution Behavior

Each inbound callback triggers at most one FHIR server fetch for the identity-source resource (identified by the `personReferenceIdentifier` extracted from the reference at the configured path). There is no in-memory caching — every callback resolves fresh from the FHIR server, ensuring the latest data is always used.

### Failure Behavior

`NationalIdResolver` is **strict**: if any step in the resolution pipeline fails — no configured path, no identity-source reference at path (i.e. no `personReferenceIdentifier` found), no national-id from the fetched resource, or any exception during resolution — the resolver returns `null`. The `NationalIdEnrichmentStrategy` then logs WARN and continues — the resource is forwarded **as-is without national-id enrichment** (never skipped). Only structurally invalid payloads (not a JSON object, blank `resourceType`) cause the forward to be skipped.

### Practitioner Display Paths

`emitter.reference-resolution.practitioner-display-paths` maps FHIR resource types to the JSON path where a Practitioner reference can be found. The `PractitionerDisplayEnrichmentStrategy` (@Order 200) uses these paths to locate the first `Practitioner/{id}` reference and populate its `display` field by fetching the Practitioner's name from the FHIR server via `PractitionerResolver`. Format: `ResourceType:dot.separated.path`.

```yaml
emitter:
  reference-resolution:
    practitioner-display-paths:
      - "Encounter:participant.individual"
      - "Observation:performer"
      - "ServiceRequest:performer"
      - "Condition:asserter"
```

or via env var (comma-separated):

```bash
EMITTER_PRACTITIONER_DISPLAY_PATHS=Encounter:participant.individual,Observation:performer,ServiceRequest:performer,Condition:asserter
```

**How it works:**

1. For each inbound resource, looks up the configured path for its resource type (e.g. `Encounter` → `participant.individual`)
2. Walks the JSON tree using `PractitionerResolver.extractPractitionerRefNodeAtPath()` — which splits the dot-path into segments and uses `ResolverPathHelper` to recursively walk the tree with array fan-out at intermediate levels, checking the leaf node for a `Practitioner/{id}` reference
3. If found and `display` is absent or blank, extracts the Practitioner ID (e.g. `"12345"` from `"Practitioner/12345"`)
4. Fetches `GET /Practitioner/{id}?_elements=name` from the FHIR server
5. Extracts a human-readable display name from the FHIR `HumanName` array (priority: `name[0].text` → `given[0] + " " + family` → `family` → `given[0]`)
6. Sets `display` on the reference node (mutates the payload in-place)

**Skip conditions (silent, non-fatal):**
- No path configured for this resource type
- No Practitioner reference found at the configured path
- `display` already present and non-blank (do not overwrite)
- FHIR server fetch fails (logs WARN, continues without display)
- Practitioner has no usable name fields

**Example enrichment:**

```jsonc
// Before enrichment (Encounter with Practitioner reference, no display):
{ "participant": [{ "individual": { "reference": "Practitioner/12345" } }] }

// After enrichment (display populated from FHIR server):
{ "participant": [{ "individual": { "reference": "Practitioner/12345", "display": "Jane Smith" } }] }
```

Resources not listed in `practitioner-display-paths` are not affected — their Practitioner references are forwarded as-is.

---

### Location Enrichment

The `LocationEnrichmentStrategy` (@Order 300) provides **dual-mode** location enrichment, controlled by the mode selector:

- `emitter.reference-resolution.location-enrichment-based-on-organization-path` — mode selector (default: `true`)

```yaml
emitter:
  reference-resolution:
    location-enrichment-based-on-organization-path: true       # true=org-path mode, false=generic mode
    location-from-organization-paths: "Encounter:serviceProvider,ServiceRequest:performer"
    location-from-encounter-paths: "Observation:encounter,ServiceRequest:encounter,Condition:encounter,MedicationRequest:encounter"
```

or via env vars:

```bash
EMITTER_LOCATION_ENRICHMENT_BASED_ON_ORGANIZATION_PATH=true
EMITTER_LOCATION_FROM_ORGANIZATION_PATHS=Encounter:serviceProvider,ServiceRequest:performer
EMITTER_LOCATION_FROM_ENCOUNTER_PATHS=Observation:encounter,ServiceRequest:encounter,Condition:encounter,MedicationRequest:encounter
```

**FHIR R4 spec-aware behavior:**

The strategy uses HAPI FHIR's `RuntimeResourceDefinition` to determine the correct location field for each resource type:

| Resource Type | Location Field | Structure |
|---------------|---------------|-----------|
| ServiceRequest | `locationReference` | Flat `Reference(Location)[]` array |
| Encounter | `location` | BackboneElement array with nested `.location` Reference |
| Observation, Condition, etc. | *(none)* | Skipped entirely — no location field in FHIR R4 |

#### Organization-Path Mode (`location-enrichment-based-on-organization-path: true`)

Uses `location-from-organization-paths` to locate the Organization reference in the payload:

1. Looks up the configured path for the resource type (e.g. `Encounter` → `serviceProvider`)
2. Walks the JSON tree to find an `Organization/{id}` reference via `OrganizationResolver`
3. Fetches `GET /Organization/{id}?_elements=name` from the FHIR server
4. Populates the location field with `{"reference": "Organization/{id}", "display": "<org name>"}`
   - ServiceRequest: populates flat `locationReference[]`
   - Encounter: populates nested `location[].location`

#### Generic Mode (`location-enrichment-based-on-organization-path: false`)

Uses `location-from-encounter-paths` and `LocationResolver` for a 3-step cascade:

1. **Skip if display exists** — if the location field already has a populated `display`, no fetch needed
2. **Fetch Location display** — if `Location/{id}` reference exists, fetches `GET /Location/{id}?_elements=name` to get the display name
3. **Derive from Encounter** — if no location is populated but an `encounter` reference exists (using configured `location-from-encounter-paths`):
   - Fetches `GET /Encounter/{id}?_elements=location` from the FHIR server
   - For `locationReference` targets (ServiceRequest): converts Encounter's nested `location[].location` to flat `Reference[]`
   - For `location` targets (Encounter-like): copies the `location[]` array directly

**Skip conditions (silent, non-fatal):**
- Resource type has no location field in FHIR R4
- No configured path for the resource type in the active mode
- Organization/Location/Encounter fetch fails (logs WARN, continues without enrichment)
- Location uses contained references (`#1`) — cannot be fetched externally

---

## 8. Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP server port | `9090` |
| `SHUTDOWN_TIMEOUT` | Graceful shutdown timeout | `30s` |
| `EMITTER_SELF_BASE_URL` | Callback URL base (must be reachable by FHIR server) | `http://localhost:9090` |
| `FHIR_SERVER_NAME` | FHIR server display name | `default-fhir` |
| `FHIR_SERVER_BASE_URL` | FHIR R4 server base URL | `http://localhost:8090/fhir` |
| `FHIR_SERVER_AUTH_TYPE` | FHIR server auth type | `token-endpoint` |
| `FHIR_SERVER_TOKEN_URL` | Token endpoint URL | *(must be set for token-endpoint/oauth2)* |
| `FHIR_SERVER_AUTH_USERNAME` | FHIR server auth username | *(must be set for basic/token-endpoint)* |
| `FHIR_SERVER_AUTH_PASSWORD` | FHIR server auth password | *(must be set for basic/token-endpoint)* |
| `FHIR_SERVER_AUTH_CLIENT` | Client type header for token endpoint | `web` |
| `FHIR_SERVER_TOKEN_COOKIE_NAME` | Cookie name for token extraction | `AuthCookie` |
| `FHIR_SERVER_TOKEN_COOKIE_BASE64` | Whether cookie value is base64 | `true` |
| `FHIR_SERVER_TOKEN_BODY_FIELD` | JSON field for token in response body | *(empty)* |
| `FHIR_SERVER_OAUTH2_CLIENT_ID` | OAuth2 client ID | *(must be set for oauth2)* |
| `FHIR_SERVER_OAUTH2_CLIENT_SECRET` | OAuth2 client secret | *(must be set for oauth2)* |
| `FHIR_SERVER_OAUTH2_SCOPE` | OAuth2 scope (space-separated) | *(empty — optional)* |
| `FHIR_SERVER_TOKEN_TTL_SECONDS` | Token cache TTL in seconds | `3600` |
| `OPENHIM_NAME` | OpenHIM display name | `openhim` |
| `OPENHIM_BASE_URL` | OpenHIM base URL | `http://localhost:5001/fhir` |
| `OPENHIM_AUTH_TYPE` | OpenHIM auth type (`none`, `basic`, `jwt`, or `custom-token`) | `basic` |
| `OPENHIM_AUTH_USERNAME` | OpenHIM auth username | *(must be set for basic)* |
| `OPENHIM_AUTH_PASSWORD` | OpenHIM auth password | *(must be set for basic)* |
| `OPENHIM_AUTH_TOKEN` | OpenHIM auth token (JWT or Custom Token) | *(must be set for jwt/custom-token)* |
| `OPENHIM_SSL_TRUST_ALL` | Trust all SSL for OpenHIM | `false` |
| `OPENHIM_APPEND_RESOURCE_TYPE` | Append resource type to OpenHIM URL | `true` |
| `EMITTER_STARTUP_SUBSCRIPTIONS_ENABLED` | Enable auto-subscribe on startup | `true` |
| `EMITTER_STARTUP_DELAY_SECONDS` | Delay before startup subscriptions | `10` |
| `EMITTER_STARTUP_FETCH_PAGE_SIZE` | Maximum number of existing subscriptions to fetch in a single query | `500` |
| `EMITTER_STARTUP_RESOURCE_TYPES` | Comma-separated FHIR resource types for startup subscription | *(5 defaults — see below)* |
| `EMITTER_PERSON_IDENTITY_RESOURCE_TYPE` | FHIR resource type used as identity source for national-id extraction (e.g. `RelatedPerson`, `Patient`) | `RelatedPerson` |
| `EMITTER_PERSON_IDENTITY_REFERENCE_PATHS` | Comma-separated `ResourceType:dot.path` entries for locating identity-source references per resource type. The resolver extracts the `personReferenceIdentifier` (FHIR resource ID) from the reference found at the path. | *(4 defaults — see YAML)* |
| `EMITTER_NATIONAL_ID_MATCH_STRATEGIES` | Comma-separated, ordered list of national-id match strategies (`use-official`, `type-code`, `system-suffix`) | `use-official,type-code,system-suffix` |
| `EMITTER_NATIONAL_ID_SYSTEM_SUFFIX` | Suffix to match against `identifier.system` for the `system-suffix` strategy | `/national-id` |
| `EMITTER_NATIONAL_ID_TYPE_CODE` | HL7 v2-0203 code (or other code) used by the `type-code` strategy | `NI` |
| `EMITTER_NATIONAL_ID_IDENTIFIER_SYSTEM` | System URI used when adding national-id to `identifier[]` for resources without a `subject` or `patient` field | `http://openphc.org/identifier/upid` |
| `EMITTER_PRACTITIONER_DISPLAY_PATHS` | Comma-separated `ResourceType:dot.path` entries for locating Practitioner references to enrich with display names. The resolver walks the path to find the first `Practitioner/{id}` reference and fetches the Practitioner's name from the FHIR server. | `Encounter:participant.individual,Observation:performer,ServiceRequest:performer,Condition:asserter` |
| `EMITTER_LOCATION_ENRICHMENT_BASED_ON_ORGANIZATION_PATH` | Location enrichment mode: `true` = organization-path mode (uses `location-from-organization-paths`), `false` = generic mode (uses `location-from-encounter-paths` with 3-step cascade). | `true` |
| `EMITTER_LOCATION_FROM_ORGANIZATION_PATHS` | Comma-separated `ResourceType:dot.path` entries for locating Organization references in the payload to populate the location field (organization-path mode). | `Encounter:serviceProvider,ServiceRequest:performer` |
| `EMITTER_LOCATION_FROM_ENCOUNTER_PATHS` | Comma-separated `ResourceType:dot.path` entries for locating Encounter references used to derive location data (generic mode). | `Observation:encounter,ServiceRequest:encounter,Condition:encounter,MedicationRequest:encounter` |
| `HEALTH_SHOW_DETAILS` | Health endpoint detail visibility | `when-authorized` |
| `LOG_LEVEL_ROOT` | Root log level | `INFO` |
| `LOG_LEVEL_APP` | Application log level | `INFO` |
| `LOG_LEVEL_SPRING_WEB` | Spring Web log level | `INFO` |
| `LOG_LEVEL_FHIR` | HAPI FHIR log level | `INFO` |

> **Note:** Startup subscription resource types default to 5 FHIR R4 resource types (Patient, RelatedPerson, Encounter, Observation, ServiceRequest). Override via `EMITTER_STARTUP_RESOURCE_TYPES` environment variable (comma-separated) or YAML `emitter.startup-subscriptions.resource-types` list.

---

## 9. Callback URL Reachability

The `self-base-url` property determines the callback URL registered with the FHIR server. The FHIR server must be able to reach this URL to deliver REST-hook notifications.

### Common Scenarios

| Scenario | `self-base-url` value | Notes |
|----------|-----------------------|-------|
| Both in Docker (same network) | `http://fhir-cce-emitter-adaptor:9090` | Use container name |
| Emitter on host, FHIR in Docker | `http://host.docker.internal:9090` | Docker Desktop only |
| Emitter on host, FHIR on host | `http://localhost:9090` | Both on same host |
| Emitter on host, FHIR remote | `http://<emitter-host-ip>:9090` | Use routable IP |
| Kubernetes | `http://fhir-emitter-svc.namespace.svc:9090` | Use K8s service DNS |

> **Common gotcha:** When the FHIR server runs in Docker and the emitter runs on the host (or vice versa), `localhost` won't work. Use the appropriate hostname/IP that is resolvable from the FHIR server's network context.

---

## 10. Profile Examples

### Local Development (`application-local.yml`)

```yaml
emitter:
  openhim:
    ssl-trust-all: true
  startup-subscriptions:
    enabled: true
    delay-seconds: 5

logging:
  level:
    root: DEBUG
    org.openphc.cce: DEBUG

management:
  endpoint:
    health:
      show-details: always
```

### Staging (`application-staging.yml`)

```yaml
logging:
  level:
    root: INFO
    org.openphc.cce: INFO
```

### Production (`application-production.yml`)

```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 45s

logging:
  level:
    root: WARN
    org.openphc.cce: INFO

management:
  endpoint:
    health:
      show-details: never
```
