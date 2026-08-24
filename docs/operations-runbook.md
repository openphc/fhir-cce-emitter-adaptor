# Operations Runbook — FHIR CCE Emitter Adaptor

## 1. Prometheus Metrics

All metrics use the prefix `fhir.emitter.` and carry the common tag `application=fhir-cce-emitter-adaptor`.

### Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `fhir.emitter.callbacks.received` | `fhir_emitter_callbacks_received_total` | `application` | Total callbacks received from the FHIR server |
| `fhir.emitter.forward.success` | `fhir_emitter_forward_success_total` | `application` | Successful forwards to OpenHIM |
| `fhir.emitter.forward.failure` | `fhir_emitter_forward_failure_total` | `application` | Failed forwards (4xx, 5xx, or unreachable) |
| `fhir.emitter.forward.skipped` | `fhir_emitter_forward_skipped_total` | `application` | Forwards skipped — structurally invalid payload (not a JSON object, blank `resourceType`). Note: when national-id resolution fails, resources are forwarded as-is (not skipped). |
| `fhir.emitter.subscriptions.created` | `fhir_emitter_subscriptions_created_total` | `application` | Subscriptions successfully created on the FHIR server |
| `fhir.emitter.subscriptions.failed` | `fhir_emitter_subscriptions_failed_total` | `application` | Subscription creation failures |
| `fhir.emitter.subscriptions.deleted` | `fhir_emitter_subscriptions_deleted_total` | `application` | Subscriptions successfully deleted |

### Gauges

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `fhir.emitter.subscriptions.active` | `fhir_emitter_subscriptions_active` | `application` | Current number of active subscriptions (in-memory map size) |

### Timers

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `fhir.emitter.forward.duration` | `fhir_emitter_forward_duration_seconds` | `application`, `openhim`, `resourceType`, `outcome` | Time to forward a resource to OpenHIM |

### Micrometer Naming Convention

Micrometer converts dots to underscores and appends `_total` for counters when exported to Prometheus:

```
fhir.emitter.callbacks.received  → fhir_emitter_callbacks_received_total
fhir.emitter.forward.duration    → fhir_emitter_forward_duration_seconds
```

### Prometheus Scraping

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'fhir-emitter'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['fhir-cce-emitter-adaptor:9090']
```

---

## 2. Health Endpoint Monitoring

### Endpoints

| Endpoint | Purpose | Probe Type |
|----------|---------|------------|
| `/actuator/health` | Overall health | General |
| `/actuator/health/liveness` | JVM is alive | Kubernetes liveness |
| `/actuator/health/readiness` | Ready to serve traffic | Kubernetes readiness |

> **Note:** The service uses a **fail-fast** approach for subscription health. If the FHIR server is unreachable during startup subscription reconciliation (all retry attempts exhausted), the application aborts startup (exit code non-zero). To enable automatic recovery, configure `restart: unless-stopped` in Docker Compose (or equivalent in your orchestrator) — the container will restart automatically with exponential backoff until the FHIR server is available. Without a restart policy, the container stays stopped and requires manual restart (`docker start` or `docker compose up`). Custom health indicators (`FhirServerHealthIndicator`, `OpenhimHealthIndicator`) are not implemented — fail-fast makes them unnecessary for subscription health.

### Example Health Response

```json
{
  "status": "UP",
  "components": {
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

---

## 3. Subscription Reconciliation After Restart

When the emitter restarts, subscriptions are reconciled against the configured resource types. The service bulk-fetches adaptor-owned subscriptions from the FHIR server (by owner tag) with configurable retry + backoff, creates missing ones, and deletes stale ones.

### Fail-Fast on Fetch Failure

If the FHIR server is unreachable during the bulk-fetch (all retry attempts exhausted), the service **aborts startup** by throwing `IllegalStateException`. This prevents duplicate subscriptions from being created blindly.

**Two-tier resilience (recommended):**
1. **App-level retry** (built-in) — configurable attempts with exponential backoff (`fetch-retry-max-attempts: 3`, `fetch-retry-backoff-ms: 15000`; interval doubles each attempt: 15s → 30s → 60s) handles transient blips (FHIR server still booting)
2. **Container-level restart** (requires configuration) — set `restart: unless-stopped` in Docker Compose to handle longer outages. Docker applies exponential backoff (100ms → 200ms → ... → 1 min cap) between restarts. Without this, the service stays down after fail-fast and requires manual restart.

```bash
# Logs show retry attempts before failure
docker logs fhir-cce-emitter-adaptor | grep "Attempt\|aborting\|FATAL"
```

> **No lost events:** If the FHIR server is down, it can't send callbacks anyway. Once both services are up, the emitter successfully reconciles and begins receiving callbacks.

### Automatic Startup Reconciliation (Recommended)

With `emitter.startup-subscriptions.enabled=true` (the default production configuration), the `StartupSubscriptionRunner` automatically reconciles subscriptions on startup:

1. Sleeps for `delay-seconds` (default 10s) to allow the FHIR server to become ready
2. Bulk-fetches existing **adaptor-owned** subscriptions from the FHIR server by owner tag (`https://openphc.org/cce/fhir-emitter|fhir-cce-emitter-adaptor`)
3. For each configured resource type: if an adaptor-owned subscription already exists, creation is skipped (`already-exists`); otherwise a new subscription is created (`registered`)
4. Identifies stale subscriptions — adaptor-owned subscriptions on the server whose resource type is no longer in the configured list — and deletes them (`deleted`)
5. Non-adaptor subscriptions (created by other systems) are completely invisible to the reconciliation — never loaded, never modified, never deleted
6. Failures (both creation and deletion) are logged but do not block remaining operations or startup

```bash
# Check startup subscription logs
docker logs fhir-cce-emitter-adaptor | grep "StartupSubscriptionRunner"
```

> **Note:** To change which resource types are subscribed to, update `emitter.startup-subscriptions.resource-types` in YAML or set the `EMITTER_STARTUP_RESOURCE_TYPES` environment variable (comma-separated). No code changes or rebuild required. On the next restart, the reconciliation will create subscriptions for newly added types and delete stale adaptor-owned subscriptions for removed types.

---

## 4. Troubleshooting

### 4.1 Callback URL Not Reachable

**Symptom:** Subscriptions are created successfully but no callbacks are received.

**Cause:** The FHIR server cannot reach `EMITTER_SELF_BASE_URL`.

**Resolution:**
1. Verify the callback URL from the FHIR server's network:
   ```bash
   # From the FHIR server container
   curl -s http://fhir-cce-emitter-adaptor:9090/callback/test
   # Expected: 200 OK
   ```
2. Check `EMITTER_SELF_BASE_URL` — must be the hostname/IP resolvable by the FHIR server, not `localhost`
3. Verify Docker network connectivity (both containers on the same network)
4. Check firewall rules for port 9090

### 4.2 FHIR Server Auth Failure

**Symptom:** Subscribe operations fail with `"failed: 401 Unauthorized"` or `"failed: 403 Forbidden"`.

**Cause:** Invalid credentials or token endpoint misconfiguration.

**Resolution:**
1. Check `FHIR_SERVER_AUTH_TYPE` — is it the correct type for your FHIR server?
2. For `token-endpoint`:
   - Verify `FHIR_SERVER_TOKEN_URL` is reachable from the emitter
   - Check username/password credentials
   - Check the `client` header value — some auth services require a specific value
   - Look for `TokenEndpointAuthService` errors in logs:
     ```bash
     docker logs fhir-cce-emitter-adaptor | grep "TokenEndpointAuth"
     ```
3. For `oauth2`:
   - Verify `FHIR_SERVER_TOKEN_URL` is reachable from the emitter
   - Verify `FHIR_SERVER_OAUTH2_CLIENT_ID` and `FHIR_SERVER_OAUTH2_CLIENT_SECRET`
   - Test the token endpoint manually:
     ```bash
     curl -X POST https://keycloak.example.com/auth/realms/fhir/protocol/openid-connect/token \
       -d "grant_type=client_credentials&client_id=client-id&client_secret=secret" \
       -H "Content-Type: application/x-www-form-urlencoded"
     ```
   - Check if the `scope` is required by your OAuth2 provider
4. For `basic`: Verify `FHIR_SERVER_AUTH_USERNAME` and `FHIR_SERVER_AUTH_PASSWORD`
5. For `bearer`: Verify the token is valid and not expired

### 4.3 OpenHIM Auth Failure

**Symptom:** Callbacks are received (counter increments) but `forward.failure` counter increases.

**Cause:** OpenHIM rejects forwarded requests due to invalid auth.

**Resolution:**
1. Check OpenHIM auth configuration:
   ```bash
   # Verify OpenHIM is reachable
   curl -v http://openhim:5001/fhir
   ```
2. For `basic`: Verify `OPENHIM_AUTH_USERNAME` and `OPENHIM_AUTH_PASSWORD`
3. For `jwt`: Verify `OPENHIM_AUTH_TOKEN` contains a valid JWT
4. For `custom-token`: Verify `OPENHIM_AUTH_TOKEN` matches the Custom Token configured in OpenHIM
5. For `none`: Verify the OpenHIM channel does not require authentication
4. Check `forward.failure` counter for failure patterns

### 4.4 Reference Resolution Failures

**Symptom:** Resources are being forwarded to OpenHIM without enrichment (no `subject.reference`, `patient.reference`, or `identifier[]` national-id entry); national-id resolution warnings in logs.

**Causes and resolutions:**

1. **Auth failure fetching the FHIR resource** — `NationalIdResolver` uses `FhirResourceFetcher` (backed by `FhirClientFactory`) with the same `emitter.fhir-server.auth` config as subscription registration. Verify auth is correct (see Section 4.2). Look for FHIR client errors:
   ```bash
   docker logs fhir-cce-emitter-adaptor | grep "NationalIdResolver\|FhirResourceFetcher"
   ```

2. **No configured path for the resource type** — The resource type is not listed in `emitter.reference-resolution.person-identity-reference-paths`. Only resource types with a configured path (and the configured identity-resource type itself) are enriched; all others are forwarded as-is without enrichment. To add a resource type, set `EMITTER_PERSON_IDENTITY_REFERENCE_PATHS` with the appropriate `ResourceType:dot.path` entry and restart.

3. **No identity-source reference at the configured path** — The JSON path configured for the resource type does not contain a `{personIdentityResourceType}/{personReferenceIdentifier}` reference (e.g. `RelatedPerson/499063`) in the actual payload. The `personReferenceIdentifier` is the FHIR resource ID portion of the reference. Verify the FHIR data has the expected reference at the configured path. Enable `DEBUG` logging to see path traversal:
   ```bash
   docker logs fhir-cce-emitter-adaptor | grep "NationalIdResolver\|ResolverPathHelper"
   ```

4. **Identity-resource resource has no national identifier** — None of the configured match strategies (`use-official`, `type-code`, `system-suffix`) found a match in the identity-source resource's `identifier[]` array. Inspect the resource (identified by `personReferenceIdentifier`) directly on the FHIR server.

5. **Wrong match strategy for source server** — The default `use-official,type-code,system-suffix` order works for spec-compliant servers and SPICE. For servers using non-standard or flat identifier systems (e.g., `system: "NID"` with no `use` or `type.coding` fields), override `EMITTER_NATIONAL_ID_SYSTEM_SUFFIX=NID` so the `system-suffix` strategy matches. See [configuration-guide.md](configuration-guide.md#7-reference-resolution-national-id-lookup) for examples.

6. **Stale data after national-id change** — not applicable. `NationalIdResolver` fetches fresh from the FHIR server on every callback via `FhirResourceFetcher`, so each notification picks up the latest national-id.

### 4.5 Token Expired

**Symptom:** FHIR operations fail intermittently with 401 errors.

**Cause:** Cached token has expired and the `TokenEndpointAuthService` hasn't refreshed it.

**Resolution:**
1. Check token TTL configuration (default: 3600 seconds)
2. Look for token refresh activity in logs:
   ```bash
   docker logs fhir-cce-emitter-adaptor | grep "token"
   ```
3. Verify the token endpoint is responding correctly:
   ```bash
   curl -X POST http://token-url/auth/login \
     -d "username=user&password=pass" \
     -H "Content-Type: application/x-www-form-urlencoded"
   ```
4. The service will attempt to refresh on the next request after expiry

### 4.6 Forwards Skipped (Structurally Invalid Payload)

**Symptom:** `forward.skipped` counter is incrementing; some callbacks are not reaching OpenHIM.

**Cause:** The payload received from the FHIR server is structurally invalid — either not a valid JSON object or missing a `resourceType` field. This should be rare and indicates a problem with the FHIR server's callback delivery.

**Resolution:**
1. Check which resources are being skipped:
   ```bash
   docker logs fhir-cce-emitter-adaptor | grep "skipping forward"
   ```
2. Inspect the FHIR server's subscription delivery logs for malformed payloads
3. Verify the subscription criteria are correct and the FHIR server is delivering valid FHIR JSON

---

## 5. Structured Logging

### 3-Tier Configuration (logback-spring.xml)

| Profile | Format | Level | Use Case |
|---------|--------|-------|----------|
| `local` / `default` | Console + MDC fields | `DEBUG` for `org.openphc.cce` | Local development |
| `staging` | Console + MDC fields | `INFO` for all loggers | Pre-production |
| `production` | Structured JSON | `INFO` for `org.openphc.cce` | Log aggregation (ELK, Loki) |

### Log Pattern (Console)

```
%d{ISO8601} [%thread] %-5level %logger{36} [%X{requestId:-}] [%X{callbackKey:-}] [%X{resourceType:-}] [%X{resourceId:-}] - %msg%n
```

### MDC Fields

| Field | Source | Description |
|-------|--------|-------------|
| `requestId` | `X-Request-ID` header or generated UUID | Unique request identifier |
| `callbackKey` | URL path `/callback/{key}/...` | Callback subscription key |
| `resourceType` | Parsed from FHIR JSON | FHIR resource type (e.g., `Patient`) |
| `resourceId` | Parsed from FHIR JSON | FHIR resource ID |
| `method` | HTTP request method | `GET`, `POST`, `PUT`, etc. |
| `path` | HTTP request URI | Request path |

### MDC Lifecycle

- **LoggingFilter** (request thread): Sets `requestId`, `callbackKey`, `method`, `path`; clears ALL in `finally` block
- **ForwardingEngine** (same request thread): Sets `resourceType`, `resourceId` after parsing

### Example Log Output

**Console (local/staging):**
```
2025-01-15T10:30:00.123 [http-nio-9090-exec-1] INFO  ForwardingEngine [abc-123] [patient] [Patient] [456] - Forwarded Patient/456 to openhim (200 OK, 45ms)
```

**JSON (production):**
```json
{
  "timestamp": "2025-01-15T10:30:00.123Z",
  "level": "INFO",
  "logger": "org.openphc.cce.emitter.service.ForwardingEngine",
  "thread": "http-nio-9090-exec-1",
  "message": "Forwarded Patient/456 to openhim (200 OK, 45ms)",
  "requestId": "abc-123",
  "callbackKey": "patient",
  "resourceType": "Patient",
  "resourceId": "456"
}
```

---

## 6. Alerting Recommendations

### Critical Alerts

| Alert | Condition | Action |
|-------|-----------|--------|
| **Forward failure rate high** | `rate(fhir_emitter_forward_failure_total[5m]) > 0.1` | Investigate OpenHIM connectivity |
| **No callbacks received** | `increase(fhir_emitter_callbacks_received_total[15m]) == 0` (when expecting traffic) | Check FHIR server → emitter connectivity |
| **Health DOWN** | `/actuator/health` returns non-UP | Check FHIR server and OpenHIM connectivity |

### Warning Alerts

| Alert | Condition | Action |
|-------|-----------|--------|
| **Subscription failure** | `increase(fhir_emitter_subscriptions_failed_total[5m]) > 0` | Check FHIR server auth and connectivity |

### Grafana Dashboard Panels (Suggested)

| Panel | Metric | Type |
|-------|--------|------|
| Callback Rate | `rate(fhir_emitter_callbacks_received_total[5m])` | Graph |
| Forward Success/Failure | `rate(fhir_emitter_forward_success_total[5m])` vs `rate(fhir_emitter_forward_failure_total[5m])` | Graph |
| Forward Duration (p95) | `histogram_quantile(0.95, fhir_emitter_forward_duration_seconds_bucket)` | Graph |
| Active Subscriptions | `fhir_emitter_subscriptions_active` | Stat |

---

## 7. Operational Procedures

### Force Token Refresh

Currently, tokens are refreshed automatically on TTL expiry or on 401 response from the FHIR server. Manual token refresh is not exposed via API — restart the service to clear the token cache.

### View All Metrics

```bash
curl http://localhost:9090/actuator/prometheus | grep fhir_emitter
```

---

## 8. Future Optimisations

### 8.1 Async Enrichment + Forwarding with Service-Managed Retry

**Current behaviour:**
`SubscriptionCallbackController` delegates to `ForwardingEngine` synchronously on the HAPI FHIR HTTP thread. The controller then returns `200 OK` with an empty body. This design was introduced to prevent HAPI FHIR's `RetryingMessageHandlerWrapper` from triggering an infinite redelivery loop (any non-2xx or non-FHIR body causes HAPI's internal FHIR client to throw `DataFormatException` and retry indefinitely).

The drawback of the current synchronous approach is that the `200 OK` is held until enrichment and forwarding complete. If reference resolution or the OpenHIM POST is slow, the HAPI delivery thread is blocked for the duration. Under high load or a slow FHIR server, this risks exhausting the HTTP thread pool and could exceed HAPI's own delivery timeout — causing a spurious redelivery.

**Proposed improvement — fire-and-forget with bounded thread pool:**

1. The controller hands off the raw JSON to a bounded `ThreadPoolTaskExecutor` and **returns `200 OK` immediately** — HAPI FHIR is ACKed before any enrichment or forwarding begins, completely eliminating the redelivery risk.
2. The async task runs `ResourceEnrichmentOrchestrator` → `ForwardingEngine` → OpenHIM POST off the HTTP thread.
3. Because the service now controls the retry (HAPI is already ACKed), **exponential backoff retry** can be introduced safely — e.g. up to 3 attempts with `2 s × attempt` backoff — without risking HAPI redelivery loops.
4. Failures after all retries are logged, metered (`forward.failure` counter), and optionally written to a dead-letter log or alert.

**Sketch (Spring `@Async` or explicit `TaskExecutor`):**

```java
// Controller — ACK immediately
@RequestMapping(...)
public ResponseEntity<Void> handleCallback(@PathVariable String callbackKey,
                                           @RequestBody String resourceJson) {
    forwardingEngine.submitAsync(callbackKey, resourceJson);   // non-blocking hand-off
    return ResponseEntity.ok().build();
}

// ForwardingEngine — off-thread with retry
@Async("callbackExecutor")
public void submitAsync(String callbackKey, String resourceJson) {
    String enriched = resourceEnrichmentOrchestrator.enrichReferences(context);
    int attempt = 0;
    while (attempt < maxAttempts) {
        try {
            postToOpenhim(enriched, ...);
            forwardSuccessCounter.increment();
            return;
        } catch (Exception e) {
            attempt++;
            if (attempt >= maxAttempts) {
                forwardFailureCounter.increment();
                log.warn("Forward failed after {} attempts: {}", maxAttempts, e.getMessage());
                return;
            }
            Thread.sleep(backoffMs * attempt);   // linear backoff; replace with exponential as needed
        }
    }
}
```

**Configuration additions required:**

```yaml
emitter:
  openhim:
    retry:
      max-attempts: ${OPENHIM_RETRY_MAX_ATTEMPTS:3}
      backoff-ms: ${OPENHIM_RETRY_BACKOFF_MS:2000}

spring:
  task:
    execution:
      pool:
        core-size: 4
        max-size: 16
        queue-capacity: 200
      thread-name-prefix: callback-
```

**New metric to add:** `fhir.emitter.forward.attempts` (distribution summary) — tracks how many attempts each successful forward required, to tune retry parameters in production.

> **Note:** Thread pool sizing should be tuned to the expected callback rate and OpenHIM response latency. A queue-capacity of 200 provides a buffer for bursts; if the queue fills, new callbacks are dropped and `forward.failure` is incremented. Monitor `fhir_emitter_forward_failure_total` and queue depth to detect saturation.
