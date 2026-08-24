package org.openphc.cce.emitter.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.service.resolver.ResolverPathHelper;
import org.openphc.cce.emitter.service.resolver.PractitionerResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Enrichment strategy that finds Practitioner references at configured paths
 * and populates their {@code display} field by fetching the name from the FHIR server.
 *
 * <p>Execution order: 200 (runs after national-id enrichment).
 */
@Component
@Order(200)
public class PractitionerDisplayEnrichmentStrategy implements EnrichmentStrategy {

    private static final Logger log = LoggerFactory.getLogger(PractitionerDisplayEnrichmentStrategy.class);
    private static final String PRACTITIONER_PREFIX = "Practitioner/";

    private final PractitionerResolver practitionerResolver;
    private final Map<String, String> practitionerDisplayPathMap;

    public PractitionerDisplayEnrichmentStrategy(PractitionerResolver practitionerResolver,
                                                 EmitterProperties properties) {
        this.practitionerResolver = practitionerResolver;
        this.practitionerDisplayPathMap = ResolverPathHelper.parsePathConfig(
                properties.getReferenceResolution().getPractitionerDisplayPaths());
        log.info("PractitionerDisplayEnrichmentStrategy — paths configured for: {}",
                practitionerDisplayPathMap.keySet());
    }

    /**
     * Enriches the Practitioner reference in the payload with a human-readable display name.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Look up the configured dot-path for the resource type (e.g. {@code Encounter → "participant.individual"})</li>
     *   <li>Walk the JSON tree to find the first {@code Practitioner/{id}} reference node at that path</li>
     *   <li>Skip if the reference node already has a non-blank {@code display} field</li>
     *   <li>Fetch the Practitioner from the FHIR server ({@code GET /Practitioner/{id}?_elements=name})</li>
     *   <li>Set the {@code display} field on the reference node with the resolved name</li>
     * </ol>
     *
     * <p>Example — Encounter with participant.individual referencing a Practitioner:
     * <pre>{@code
     * Before: {"participant": [{"individual": {"reference": "Practitioner/prac-42"}}]}
     * After:  {"participant": [{"individual": {"reference": "Practitioner/prac-42", "display": "Dr. Jane Smith"}}]}
     * }</pre>
     *
     * <p>Example — display already present (skipped):
     * <pre>{@code
     * Input: {"performer": [{"reference": "Practitioner/prac-7", "display": "Dr. Doe"}]}
     * Result: unchanged — existing display is preserved
     * }</pre>
     *
     * <p>Non-fatal: any exception is logged as WARN and the resource is forwarded without the display name.
     *
     * @param context the enrichment context containing the resource payload and metadata
     */
    @Override
    public void enrich(EnrichmentContext context) {
        // Step 1: Check if this resource type has a configured path for Practitioner display enrichment
        // e.g. "Encounter" → "participant.individual", "Observation" → "performer"
        String practitionerDisplayPath = practitionerDisplayPathMap.get(context.resourceType());
        if (practitionerDisplayPath == null) {
            return;
        }

        try {
            // Step 2: Walk the JSON tree along the configured dot-path to find the Practitioner reference node
            // Handles arrays (fan-out) and nested objects, returns the first node with "Practitioner/{id}"
            JsonNode practitionerRefNode = practitionerResolver.extractPractitionerRefNodeAtPath(
                    context.payload(), practitionerDisplayPath);
            if (practitionerRefNode == null) {
                return;
            }

            // Step 3: Skip enrichment if the reference already has a display name populated
            // e.g. {"reference": "Practitioner/prac-7", "display": "Dr. Doe"} → no action needed
            String existingDisplay = practitionerRefNode.path("display").asText(null);
            if (existingDisplay != null && !existingDisplay.isBlank()) {
                return;
            }

            // Step 4: Extract the Practitioner ID from the reference string
            // e.g. "Practitioner/prac-42" → "prac-42"
            String practitionerId = practitionerRefNode.path("reference").asText()
                    .substring(PRACTITIONER_PREFIX.length());

            // Step 5: Fetch the Practitioner resource from the FHIR server and extract display name
            // Priority: name[0].text → given + family → family → given
            String practitionerDisplayName = practitionerResolver.fetchPractitionerDisplayName(practitionerId);

            // Step 6: Set the display field on the reference node if a name was resolved
            // e.g. {"reference": "Practitioner/prac-42"} → {"reference": "Practitioner/prac-42", "display": "Dr. Jane Smith"}
            if (practitionerDisplayName != null) {
                ((ObjectNode) practitionerRefNode).put("display", practitionerDisplayName);
                log.debug("Enriched Practitioner/{} with display: {}", practitionerId, practitionerDisplayName);
            }
        } catch (Exception e) {
            log.warn("Practitioner display enrichment failed for {} — forwarding without display: {}",
                    context.resourceType(), e.getMessage());
        }
    }

}
