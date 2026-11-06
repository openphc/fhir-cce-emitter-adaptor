package org.openphc.cce.emitter.service.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves Practitioner display names by walking configured JSON paths in the payload
 * to locate Practitioner references and fetching display names from the FHIR server.
 */
@Service
public class PractitionerResolver {

    private static final Logger log = LoggerFactory.getLogger(PractitionerResolver.class);
    private static final String PRACTITIONER_PREFIX = "Practitioner/";

    private final FhirResourceFetcher fhirResourceFetcher;

    public PractitionerResolver(FhirResourceFetcher fhirResourceFetcher) {
        this.fhirResourceFetcher = fhirResourceFetcher;
    }

    /**
     * Extracts the first Practitioner reference node by traversing the FHIR resource JSON
     * along a dot-separated path (e.g. "participant.individual").
     *
     * @param incomingPayload         the incoming FHIR resource JSON tree
     * @param practitionerDisplayPath dot-separated path to the Practitioner reference
     * @return the first Practitioner reference object node, or {@code null} if not found
     */
    public JsonNode extractPractitionerRefNodeAtPath(JsonNode incomingPayload, String practitionerDisplayPath) {
        return ResolverPathHelper.findReferenceNodeAtPath(incomingPayload, practitionerDisplayPath, PRACTITIONER_PREFIX);
    }

    /**
     * Fetches a Practitioner resource from the FHIR server and extracts a
     * human-readable display name from its name array.
     *
     * <p>Name extraction priority: text → given + family → family → given
     *
     * @param practitionerId the Practitioner resource ID
     * @return display name string, or {@code null} if fetch fails or no usable name
     */
    public String fetchPractitionerDisplayName(String practitionerId) {
        JsonNode responseNode = fhirResourceFetcher.fetchResource("Practitioner", practitionerId, "name");
        if (responseNode == null) {
            return null;
        }
        return extractDisplayNameFromNames(responseNode.path("name"));
    }

    // ── Name extraction ─────────────────────────────────────────────

    /**
     * Extracts a human-readable display name from a FHIR {@code HumanName[]} array.
     *
     * <p>Uses the first entry in the array ({@code name[0]}) and applies the following
     * priority cascade to construct a display string:
     * <ol>
     *   <li>{@code name[0].text} — pre-composed full name (highest priority)</li>
     *   <li>{@code name[0].given[0] + " " + name[0].family} — given + family concatenation</li>
     *   <li>{@code name[0].family} — family name only</li>
     *   <li>{@code name[0].given[0]} — given name only (lowest priority)</li>
     * </ol>
     *
     * <p>Example — text field present:
     * <pre>{@code
     * Input:  [{"text": "Dr. Jane Smith", "family": "Smith", "given": ["Jane"]}]
     * Result: "Dr. Jane Smith"
     * }</pre>
     *
     * <p>Example — no text, both given and family:
     * <pre>{@code
     * Input:  [{"family": "Doe", "given": ["John"]}]
     * Result: "John Doe"
     * }</pre>
     *
     * <p>Example — family only:
     * <pre>{@code
     * Input:  [{"family": "Kumar"}]
     * Result: "Kumar"
     * }</pre>
     *
     * <p>Example — given only:
     * <pre>{@code
     * Input:  [{"given": ["Alice"]}]
     * Result: "Alice"
     * }</pre>
     *
     * @param names the FHIR {@code name} array node from the Practitioner resource
     * @return a display name string, or {@code null} if the array is empty or no usable name parts exist
     */
    private String extractDisplayNameFromNames(JsonNode names) {
        // Guard: ensure the node is a non-empty array
        if (!names.isArray() || names.isEmpty()) {
            return null;
        }

        // Use the first HumanName entry (FHIR allows multiple names but first is typically the active one)
        JsonNode name = names.get(0);

        // Priority 1: pre-composed text field (e.g. "Dr. Jane Smith")
        String text = name.path("text").asText(null);
        if (text != null && !text.isBlank()) {
            return text;
        }

        // Extract individual name components for fallback composition
        String family = name.path("family").asText(null);
        JsonNode givenArray = name.path("given");
        // Take first given name only (e.g. ["Jane", "Marie"] → "Jane")
        String given = (givenArray.isArray() && !givenArray.isEmpty())
                ? givenArray.get(0).asText(null) : null;

        // Priority 2: "given family" concatenation (e.g. "John Doe")
        if (given != null && !given.isBlank() && family != null && !family.isBlank()) {
            return given + " " + family;
        }
        // Priority 3: family name only (e.g. "Kumar")
        if (family != null && !family.isBlank()) {
            return family;
        }
        // Priority 4: given name only (e.g. "Alice")
        if (given != null && !given.isBlank()) {
            return given;
        }

        // No usable name parts found
        return null;
    }
}
