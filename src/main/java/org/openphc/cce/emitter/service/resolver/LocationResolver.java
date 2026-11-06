package org.openphc.cce.emitter.service.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves location-related data from the FHIR server.
 *
 * <p>Supports two operations:
 * <ul>
 *   <li>Fetching a Location resource's display name (for enriching existing location references)</li>
 *   <li>Fetching an Encounter's location data (for deriving location when none exists in payload)</li>
 * </ul>
 */
@Service
public class LocationResolver {

    private static final Logger log = LoggerFactory.getLogger(LocationResolver.class);
    private static final String ENCOUNTER_PREFIX = "Encounter/";

    private final FhirResourceFetcher fhirResourceFetcher;

    public LocationResolver(FhirResourceFetcher fhirResourceFetcher) {
        this.fhirResourceFetcher = fhirResourceFetcher;
    }

    /**
     * Extracts an Encounter ID from the payload at the given path using
     * {@link ResolverPathHelper#findReferenceNodeAtPath}.
     *
     * @param payload the FHIR resource JSON payload
     * @param dotPath dot-separated path to the Encounter reference container (e.g. "encounter")
     * @return the Encounter ID, or {@code null} if not found
     */
    public String extractEncounterIdAtPath(JsonNode payload, String dotPath) {
        JsonNode referenceNode = ResolverPathHelper.findReferenceNodeAtPath(payload, dotPath, ENCOUNTER_PREFIX);
        return ResolverPathHelper.extractIdFromReferenceNode(referenceNode, ENCOUNTER_PREFIX);
    }

    /**
     * Fetches a Location resource from the FHIR server and extracts its display name.
     *
     * @param locationId the Location resource ID
     * @return the Location name, or {@code null} if fetch fails or no name
     */
    public String fetchLocationDisplayName(String locationId) {
        JsonNode responseNode = fhirResourceFetcher.fetchResource("Location", locationId, "name");
        if (responseNode == null) {
            return null;
        }
        String name = responseNode.path("name").asText(null);
        return (name != null && !name.isBlank()) ? name : null;
    }

    /**
     * Fetches an Encounter resource from the FHIR server and returns its location array.
     *
     * <p>The returned JsonNode is the Encounter's {@code location[]} array — an array of
     * BackboneElements each containing a nested {@code location} reference object:
     * <pre>{@code
     * [
     *   { "location": { "reference": "Location/123", "display": "ICU Room 3" } }
     * ]
     * }</pre>
     *
     * @param encounterId the Encounter resource ID
     * @return the Encounter's {@code location[]} array node, or {@code null} if fetch fails
     *         or Encounter has no location data
     */
    public JsonNode fetchEncounterLocationData(String encounterId) {
        JsonNode responseNode = fhirResourceFetcher.fetchResource("Encounter", encounterId, "location");
        if (responseNode == null) {
            return null;
        }
        JsonNode locationArray = responseNode.path("location");
        if (locationArray.isMissingNode() || !locationArray.isArray() || locationArray.isEmpty()) {
            log.debug("Encounter/{} has no location data", encounterId);
            return null;
        }
        return locationArray;
    }
}
