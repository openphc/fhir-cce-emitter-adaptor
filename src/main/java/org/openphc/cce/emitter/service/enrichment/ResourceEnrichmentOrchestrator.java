package org.openphc.cce.emitter.service.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates FHIR resource enrichment by running all registered {@link EnrichmentStrategy}
 * implementations in order against the parsed payload.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Parse incoming JSON to ObjectNode (single parse)</li>
 *   <li>Validate structure (must be a JSON object with a non-blank resourceType)</li>
 *   <li>Execute strategies in Spring injection order</li>
 *   <li>Serialize enriched payload back to JSON (single serialization)</li>
 * </ul>
 *
 * <p>Replaces the old monolithic {@code ResourceEnricher} class.
 */
@Service
public class ResourceEnrichmentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ResourceEnrichmentOrchestrator.class);

    private final ObjectMapper objectMapper;
    private final List<EnrichmentStrategy> strategies;

    public ResourceEnrichmentOrchestrator(ObjectMapper objectMapper, List<EnrichmentStrategy> strategies) {
        this.objectMapper = objectMapper;
        this.strategies = List.copyOf(strategies);
        log.info("ResourceEnrichmentOrchestrator initialized with {} strategies: {}",
                this.strategies.size(),
                this.strategies.stream().map(s -> s.getClass().getSimpleName()).toList());
    }

    /**
     * Enriches the FHIR JSON by running all registered strategies in injection order.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Parse raw JSON string into a mutable {@link ObjectNode}</li>
     *   <li>Validate structure — must be a JSON object with a non-blank {@code resourceType}</li>
     *   <li>Run each {@link EnrichmentStrategy} in injection order</li>
     *   <li>Serialize the enriched payload back to a JSON string</li>
     * </ol>
     *
     * <p>Example — Encounter payload enriched by all three strategies:
     * <pre>{@code
     * Input:
     * {"resourceType":"Encounter","id":"enc-1","participant":[{"individual":{"reference":"RelatedPerson/rp-1"}}],"serviceProvider":{"reference":"Organization/org-5"}}
     *
     * After NationalIdEnrichmentStrategy:
     *   → subject.reference = "Patient/NI-12345" (resolved from RelatedPerson/rp-1's identifier[])
     *
     * After PractitionerDisplayEnrichmentStrategy:
     *   → participant[0].individual.display = "Dr. Jane Smith" (fetched from Practitioner if ref matches)
     *
     * After LocationEnrichmentStrategy:
     *   → location[0].location.display = "Main Clinic" (resolved from Organization/org-5)
     *
     * Output: serialized JSON with all enrichments applied in-place
     * }</pre>
     *
     * <p>Error handling:
     * <ul>
     *   <li>{@link JsonProcessingException} (malformed JSON) → returns {@code null} (skip forward)</li>
     *   <li>Any other exception (strategy failure) → returns original {@code resourceJson} as-is (forward unenriched)</li>
     * </ul>
     *
     * @param resourceJson raw FHIR resource JSON string from the REST-hook callback
     * @return enriched JSON string; {@code null} only for structurally invalid payloads
     *         (not a JSON object, blank resourceType, or unparseable JSON)
     */
    public String enrichReferences(String resourceJson) {
        try {
            // Step 1: Parse JSON into a tree — readTree returns JsonNode (read-only base type)
            JsonNode incomingPayload = objectMapper.readTree(resourceJson);
            if (!incomingPayload.isObject()) {
                log.error("Payload is not a JSON object — skipping forward");
                return null;
            }

            // Step 2: Extract metadata for logging and strategy routing
            // e.g. "Encounter", "Observation", "ServiceRequest"
            String resourceType = incomingPayload.path("resourceType").asText("");
            if (resourceType.isBlank()) {
                log.error("No resourceType in payload — skipping forward");
                return null;
            }

            String resourceId = incomingPayload.path("id").asText("Unknown");

            // Cast to ObjectNode — safe because isObject() was checked above.
            // ObjectNode provides mutation methods (put/set/remove) needed by strategies.
            ObjectNode payload = (ObjectNode) incomingPayload;

            // Step 3: Build immutable context shared across all strategies
            EnrichmentContext context = new EnrichmentContext(payload, resourceType, resourceId);

            // Step 4: Execute strategies in injection order
            // Each strategy enriches the payload in-place via the mutable ObjectNode reference
            for (EnrichmentStrategy strategy : strategies) {
                strategy.enrich(context);
            }

            // Step 5: Serialize the enriched payload back to JSON string
            return objectMapper.writeValueAsString(payload);

        } catch (JsonProcessingException e) {
            // Malformed JSON — cannot enrich, skip forwarding entirely
            log.error("Invalid JSON — skipping forward: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            // Strategy failure — forward the original unenriched payload rather than dropping it
            log.error("Enrichment orchestrator failed — forwarding as-is: {}", e.getMessage());
            return resourceJson;
        }
    }
}
