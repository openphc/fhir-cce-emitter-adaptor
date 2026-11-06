package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.OpenhimAuthConfig;
import org.openphc.cce.emitter.config.EmitterProperties.OpenhimConfig;
import org.openphc.cce.emitter.service.enrichment.ResourceEnrichmentOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

/**
 * Synchronous forwarding engine that receives FHIR resources from callback
 * endpoints and forwards them to OpenHIM with retry and auth support.
 * <p>
 * This is the core processing component of the service. It parses incoming
 * FHIR JSON for metadata extraction (resource type, ID), then POSTs the
 * raw JSON to OpenHIM with configurable authentication and retry logic.
 */
@Service
public class ForwardingEngine {

    private static final Logger log = LoggerFactory.getLogger(ForwardingEngine.class);

    private final FhirContext fhirContext;
    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final RestTemplate trustAllRestTemplate;
    private final ResourceEnrichmentOrchestrator enrichmentOrchestrator;

    // Metrics
    private final Counter callbacksReceivedCounter;
    private final Counter forwardSuccessCounter;
    private final Counter forwardFailureCounter;
    private final Counter forwardSkippedCounter;
    private final MeterRegistry meterRegistry;

    public ForwardingEngine(FhirContext fhirContext,
                            EmitterProperties properties,
                            RestTemplate restTemplate,
                            @Qualifier("trustAllRestTemplate") RestTemplate trustAllRestTemplate,
                            ResourceEnrichmentOrchestrator enrichmentOrchestrator,
                            MeterRegistry meterRegistry) {
        this.fhirContext = fhirContext;
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.trustAllRestTemplate = trustAllRestTemplate;
        this.enrichmentOrchestrator = enrichmentOrchestrator;
        this.meterRegistry = meterRegistry;

        // Register metrics
        this.callbacksReceivedCounter = Counter.builder("fhir.emitter.callbacks.received")
                .description("Total callbacks received from the FHIR server")
                .register(meterRegistry);
        this.forwardSuccessCounter = Counter.builder("fhir.emitter.forward.success")
                .description("Successful forwards to OpenHIM")
                .register(meterRegistry);
        this.forwardFailureCounter = Counter.builder("fhir.emitter.forward.failure")
                .description("Failed forwards (all retries exhausted)")
                .register(meterRegistry);
        this.forwardSkippedCounter = Counter.builder("fhir.emitter.forward.skipped")
                .description("Forwards skipped — no Patient subject or RelatedPerson reference")
                .register(meterRegistry);
    }

    /**
     * Receives a FHIR resource JSON from a callback, parses metadata,
     * and forwards synchronously to OpenHIM.
     *
     * @param callbackResourceType  the resource type from the callback URL path
     * @param resourceJson raw FHIR JSON payload
     * @return forwarding result (success, failure, or unreachable)
     */
    public ForwardResult forward(String callbackResourceType, String resourceJson) {
        callbacksReceivedCounter.increment();

        // Parse FHIR resource metadata — fallback to "Unknown" on failure
        String resourceType = "Unknown";
        String resourceId = "Unknown";
        try {
            IBaseResource resource = fhirContext.newJsonParser().parseResource(resourceJson);
            resourceType = resource.fhirType();
            resourceId = resource.getIdElement() != null ? resource.getIdElement().toUnqualifiedVersionless().getValue() : "Unknown";
            log.debug("Parsed FHIR resource: type={}, id={}", resourceType, resourceId);
        } catch (Exception e) {
            log.warn("Failed to parse FHIR resource — forwarding with type=Unknown: {}", e.getMessage());
        }

        // Set MDC fields on the request thread for structured logging
        MDC.put("resourceType", resourceType);
        MDC.put("resourceId", resourceId);

        // Enrich references (e.g. Patient/616 → Patient/<nationalId>).
        // Returns null only for structurally invalid payloads (not a JSON object,
        // blank resourceType). Otherwise returns enriched or original JSON as-is.
        String payloadToForward = enrichmentOrchestrator.enrichReferences(resourceJson);

        if (payloadToForward == null) {
            log.info("Skipping forward for {} {} — structurally invalid payload",
                    resourceType, resourceId);
            forwardSkippedCounter.increment();
            return ForwardResult.skipped();
        }

        return forwardToOpenhim(payloadToForward, resourceType, resourceId, callbackResourceType);
    }

    /**
     * Forwards the raw FHIR JSON to OpenHIM — single attempt, no retry.
     *
     * <p>Retries are intentionally removed. The controller always returns {@code 200 OK}
     * to the HAPI FHIR delivery client regardless of outcome. Any delay here (retry
     * backoff) risks exceeding HAPI's callback timeout, which causes HAPI FHIR's
     * {@code RetryingMessageHandlerWrapper} to classify the delivery as failed and
     * restart it indefinitely — the exact loop we must avoid. Forward once and return;
     * let monitoring/alerts surface failures.
     */
    ForwardResult forwardToOpenhim(String resourceJson, String resourceType,
                                    String resourceId, String callbackResourceType) {
        OpenhimConfig openhimConfig = properties.getOpenhim();

        // Resolve OpenHIM URL
        String openhimUrl = openhimConfig.getBaseUrl();
        if (openhimConfig.isAppendResourceType()) {
            openhimUrl = openhimUrl + "/" + resourceType;
        }

        // Build headers
        HttpHeaders headers = buildHeaders(openhimConfig.getAuth());
        HttpEntity<String> request = new HttpEntity<>(resourceJson, headers);

        // Select RestTemplate based on SSL trust config
        RestTemplate templateToUse = openhimConfig.isSslTrustAll() ? trustAllRestTemplate : restTemplate;

        Timer.Sample timerSample = Timer.start(meterRegistry);

        try {
            log.info("Forwarding {} {} to OpenHIM: {}", resourceType, resourceId, openhimUrl);

            ResponseEntity<String> response = templateToUse.exchange(
                    openhimUrl, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully forwarded {} {} to OpenHIM ({})",
                        resourceType, resourceId, response.getStatusCode());
                forwardSuccessCounter.increment();
                recordDuration(timerSample, resourceType, "success");
                return ForwardResult.success();
            }

            // Non-2xx (e.g. 3xx redirect) — unlikely but handle defensively
            log.warn("OpenHIM returned {} for {} {}: {}",
                    response.getStatusCode(), resourceType, resourceId, response.getBody());
            forwardFailureCounter.increment();
            recordDuration(timerSample, resourceType, "failure");
            return ForwardResult.failure(response.getStatusCode().value(), response.getBody());

        } catch (HttpClientErrorException e) {
            // 4xx — permanent client error (e.g. PATIENT_ID_NOT_FOUND)
            log.warn("OpenHIM rejected {} {} with {}: {}",
                    resourceType, resourceId, e.getStatusCode().value(), e.getResponseBodyAsString());
            forwardFailureCounter.increment();
            recordDuration(timerSample, resourceType, "failure");
            return ForwardResult.failure(e.getStatusCode().value(), e.getResponseBodyAsString());

        } catch (HttpServerErrorException e) {
            // 5xx — transient server error; no retry to avoid HAPI timeout loop
            log.warn("OpenHIM 5xx for {} {} ({}): {}",
                    resourceType, resourceId, e.getStatusCode().value(), e.getResponseBodyAsString());
            forwardFailureCounter.increment();
            recordDuration(timerSample, resourceType, "failure");
            return ForwardResult.failure(e.getStatusCode().value(), e.getResponseBodyAsString());

        } catch (ResourceAccessException e) {
            // Connection error — OpenHIM unreachable; no retry to avoid HAPI timeout loop
            log.warn("OpenHIM unreachable for {} {}: {}", resourceType, resourceId, e.getMessage());
            forwardFailureCounter.increment();
            recordDuration(timerSample, resourceType, "unreachable");
            return ForwardResult.unreachable(1);

        } catch (Exception e) {
            // Unexpected error
            log.error("Unexpected error forwarding {} {}: {}", resourceType, resourceId, e.getMessage(), e);
            forwardFailureCounter.increment();
            recordDuration(timerSample, resourceType, "failure");
            return ForwardResult.failure(0, e.getMessage());
        }
    }

    /**
     * Builds HTTP headers for the OpenHIM request including auth.
     * Supports 4 auth types: basic, jwt, custom-token, none.
     */
    HttpHeaders buildHeaders(OpenhimAuthConfig authConfig) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String authType = authConfig.getType() != null ? authConfig.getType().toLowerCase() : "none";

        switch (authType) {
            case "basic" -> {
                String credentials = authConfig.getUsername() + ":" + authConfig.getPassword();
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
                headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
            }
            case "jwt" -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + authConfig.getToken());
            case "custom-token" -> headers.set(HttpHeaders.AUTHORIZATION, "Custom " + authConfig.getToken());
            case "none" -> { /* No auth header */ }
            default -> log.warn("Unknown OpenHIM auth type: {} — skipping auth header", authType);
        }

        return headers;
    }

    /**
     * Records forwarding duration with resource type and outcome tags.
     */
    private void recordDuration(Timer.Sample sample, String resourceType, String outcome) {
        sample.stop(Timer.builder("fhir.emitter.forward.duration")
                .tag("resourceType", resourceType)
                .tag("outcome", outcome)
                .register(meterRegistry));
    }

    /**
     * Sleeps for the specified duration. Extracted for testability.
     */
    void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Backoff sleep interrupted");
        }
    }
}
