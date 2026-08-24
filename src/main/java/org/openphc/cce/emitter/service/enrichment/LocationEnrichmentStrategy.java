package org.openphc.cce.emitter.service.enrichment;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.service.resolver.LocationResolver;
import org.openphc.cce.emitter.service.resolver.OrganizationResolver;
import org.openphc.cce.emitter.service.resolver.ResolverPathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Enrichment strategy that populates FHIR R4 location fields on incoming resources.
 *
 * <p>Supports two modes controlled by {@code location-enrichment-based-on-organization-path}:
 *
 * <h3>Organization-path mode (flag ON — default)</h3>
 * <p>Uses configured {@code location-from-organization-paths} to find an Organization reference
 * in the payload, fetches its display name from the FHIR server, and sets the location field.
 *
 * <h3>Generic mode (flag OFF)</h3>
 * <ol>
 *   <li>Location already exists with display → skip (already enriched)</li>
 *   <li>Location reference exists without display → fetch display from {@code Location/{id}}</li>
 *   <li>No location at all → find Encounter reference at configured path, fetch Encounter,
 *       extract its location data, and set on the payload per FHIR R4 spec</li>
 * </ol>
 *
 * <p>FHIR R4 spec-aware location field handling:
 * <ul>
 *   <li>{@code locationReference[]} for ServiceRequest (flat Reference array)</li>
 *   <li>{@code location[]} for Encounter (BackboneElement with nested {@code .location})</li>
 * </ul>
 *
 * <p>Execution order: 300 (runs last).
 */
@Component
@Order(300)
public class LocationEnrichmentStrategy implements EnrichmentStrategy {

    private static final Logger log = LoggerFactory.getLogger(LocationEnrichmentStrategy.class);
    private static final String ORGANIZATION_PREFIX = "Organization/";
    private static final String LOCATION_PREFIX = "Location/";

    private final OrganizationResolver organizationResolver;
    private final LocationResolver locationResolver;
    private final ObjectMapper objectMapper;
    private final FhirContext fhirContext;
    private final boolean locationEnrichmentBasedOnOrganizationPath;
    private final Map<String, String> locationFromOrganizationPathMap;
    private final Map<String, String> locationFromEncounterPathMap;

    public LocationEnrichmentStrategy(OrganizationResolver organizationResolver,
                                      LocationResolver locationResolver,
                                      ObjectMapper objectMapper,
                                      FhirContext fhirContext,
                                      EmitterProperties properties) {
        this.organizationResolver = organizationResolver;
        this.locationResolver = locationResolver;
        this.objectMapper = objectMapper;
        this.fhirContext = fhirContext;

        EmitterProperties.ReferenceResolutionConfig refConfig = properties.getReferenceResolution();
        this.locationEnrichmentBasedOnOrganizationPath = refConfig.isLocationEnrichmentBasedOnOrganizationPath();
        this.locationFromOrganizationPathMap = ResolverPathHelper.parsePathConfig(refConfig.getLocationFromOrganizationPaths());
        this.locationFromEncounterPathMap = ResolverPathHelper.parsePathConfig(refConfig.getLocationFromEncounterPaths());

        log.info("LocationEnrichmentStrategy \u2014 mode: {}, org-paths: {}, encounter-paths: {}",
                locationEnrichmentBasedOnOrganizationPath ? "organization-path" : "generic",
                locationFromOrganizationPathMap.keySet(),
                locationFromEncounterPathMap.keySet());
    }

    /**
     * Enriches the resource payload with location data based on the configured mode.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Determine the FHIR R4 location field for the resource type via HAPI introspection</li>
     *   <li>Route to the appropriate enrichment flow based on the {@code locationEnrichmentBasedOnOrganizationPath} flag</li>
     * </ol>
     *
     * <p>Mode routing:
     * <ul>
     *   <li><b>Organization-path mode (flag ON)</b>: finds Organization reference → fetches display → sets location</li>
     *   <li><b>Generic mode (flag OFF)</b>: 3-step cascade — skip if display exists, fetch Location display, or derive from Encounter</li>
     * </ul>
     *
     * <p>Example — ServiceRequest with Organization-path mode:
     * <pre>{@code
     * Before: {"resourceType": "ServiceRequest", "performer": [{"reference": "Organization/1302"}]}
     * After:  {"resourceType": "ServiceRequest", ..., "locationReference": [{"reference": "Organization/1302", "display": "City Hospital"}]}
     * }</pre>
     *
     * <p>Example — Encounter with generic mode (derived from existing location):
     * <pre>{@code
     * Before: {"resourceType": "Encounter", "location": [{"location": {"reference": "Location/loc-5"}}]}
     * After:  {"resourceType": "Encounter", "location": [{"location": {"reference": "Location/loc-5", "display": "Ward A"}}]}
     * }</pre>
     *
     * <p>Non-fatal: any exception is logged as WARN and the resource is forwarded without location enrichment.
     *
     * @param context the enrichment context containing the resource payload and metadata
     */
    @Override
    public void enrich(EnrichmentContext context) {
        try {
            String resourceType = context.resourceType();
            ObjectNode payload = context.payload();

            // Step 1: Determine the FHIR R4 location field using HAPI RuntimeResourceDefinition
            // ServiceRequest → "locationReference" (flat Reference[]), Encounter → "location" (BackboneElement[])
            // Resources without a location field (e.g. Observation, Condition) → null → skip
            String fhirR4LocationField = getLocationFieldForResourceType(resourceType);
            if (fhirR4LocationField == null) {
                return; // Resource type has no location field in FHIR R4 spec
            }

            // Step 2: Route to the appropriate enrichment flow based on the mode flag
            if (locationEnrichmentBasedOnOrganizationPath) {
                // Organization-path mode: find Org ref in payload → fetch display → set location
                enrichFromOrganizationPath(payload, resourceType, fhirR4LocationField);
            } else {
                // Generic mode: 3-step cascade (skip if display exists → fetch Location display → derive from Encounter)
                enrichGenericLocation(payload, resourceType, fhirR4LocationField);
            }

        } catch (Exception e) {
            log.warn("Location enrichment failed for {} — forwarding without location details: {}",
                    context.resourceType(), e.getMessage());
        }
    }

    // ── Organization-path flow (flag ON) ─────────────────────────────

    /**
     * Enriches the location field by resolving an Organization reference found at the configured path.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Look up the configured dot-path for the resource type (e.g. {@code Encounter → "serviceProvider"})</li>
     *   <li>Walk the JSON tree to extract the Organization ID from the reference at that path</li>
     *   <li>Fetch the Organization's display name from the FHIR server ({@code GET /Organization/{id}?_elements=name})</li>
     *   <li>Build a reference node with Organization reference + display name</li>
     *   <li>Wrap per FHIR R4 spec and set on the payload's location field</li>
     * </ol>
     *
     * <p>Example — Encounter with {@code serviceProvider.reference = "Organization/1302"}:
     * <pre>{@code
     * Before: {"serviceProvider": {"reference": "Organization/1302"}}
     * After:  {"serviceProvider": ..., "location": [{"location": {"reference": "Organization/1302", "display": "City Hospital"}}]}
     * }</pre>
     *
     * <p>Example — ServiceRequest with {@code performer[].reference = "Organization/org-7"}:
     * <pre>{@code
     * Before: {"performer": [{"reference": "Organization/org-7"}]}
     * After:  {"performer": ..., "locationReference": [{"reference": "Organization/org-7", "display": "District Clinic"}]}
     * }</pre>
     *
     * @param payload the FHIR resource JSON payload to modify
     * @param resourceType the FHIR resource type name (e.g. "Encounter", "ServiceRequest")
     * @param fhirR4LocationField the location field name ({@code "locationReference"} or {@code "location"})
     */
    private void enrichFromOrganizationPath(ObjectNode payload, String resourceType, String fhirR4LocationField) {
        // Step 1: Look up the configured path for finding Organization references in this resource type
        // e.g. "Encounter" → "serviceProvider", "ServiceRequest" → "performer"
        String organizationPath = locationFromOrganizationPathMap.get(resourceType);
        if (organizationPath == null) {
            log.debug("No organization-location path configured for {} — skipping", resourceType);
            return;
        }

        // Step 2: Walk the JSON payload to extract the Organization ID from the configured path
        // e.g. {"serviceProvider": {"reference": "Organization/1302"}} → "1302"
        String organizationId = organizationResolver.extractOrganizationIdAtPath(payload, organizationPath);
        if (organizationId == null) {
            log.debug("No Organization reference found at path '{}' for {} — skipping", organizationPath, resourceType);
            return;
        }

        // Step 3: Fetch the Organization's display name from the FHIR server
        // GET /Organization/1302?_elements=name → {"name": "City Hospital"} → "City Hospital"
        String organizationDisplayName = organizationResolver.fetchOrganizationDisplayName(organizationId);
        String organizationReference = ORGANIZATION_PREFIX + organizationId;

        // Step 4: Build the reference node with Organization reference + resolved display name
        // e.g. {"reference": "Organization/1302", "display": "City Hospital"}
        ObjectNode orgRefNode = objectMapper.createObjectNode();
        orgRefNode.put("reference", organizationReference);
        if (organizationDisplayName != null) {
            orgRefNode.put("display", organizationDisplayName);
        }

        // Step 5: Wrap per FHIR R4 spec and set on the payload:
        //   ServiceRequest → locationReference: [{"reference": "Organization/1302", "display": "City Hospital"}]
        //   Encounter      → location: [{"location": {"reference": "Organization/1302", "display": "City Hospital"}}]
        ArrayNode locationArray = objectMapper.createArrayNode();
        if ("locationReference".equals(fhirR4LocationField)) {
            // Flat Reference array — add the reference node directly
            locationArray.add(orgRefNode);
        } else {
            // BackboneElement array — wrap in {"location": <refNode>} per Encounter.location structure
            ObjectNode backboneEntry = objectMapper.createObjectNode();
            backboneEntry.set("location", orgRefNode);
            locationArray.add(backboneEntry);
        }
        payload.set(fhirR4LocationField, locationArray);

        log.debug("Enriched {} — set {} with Organization/{} (display: {})",
                resourceType, fhirR4LocationField, organizationId, organizationDisplayName);
    }

    // ── Generic flow (flag OFF) ──────────────────────────────────────

    /**
     * Enriches the location field using a 3-step cascade when Organization-path mode is disabled.
     *
     * <p>Decision cascade:
     * <ol>
     *   <li><b>Skip</b> — location array exists and at least one entry already has a {@code display} → no action</li>
     *   <li><b>Fetch display</b> — location reference(s) exist without display → fetch {@code GET /Location/{id}?_elements=name}
     *       and populate the {@code display} field</li>
     *   <li><b>Derive from Encounter</b> — no location field at all → find Encounter reference at configured path,
     *       fetch the Encounter's {@code location[]} from the FHIR server, and copy it onto the payload</li>
     * </ol>
     *
     * <p>Example — Step 1 (skip, display already present):
     * <pre>{@code
     * Input: {"location": [{"location": {"reference": "Location/loc-5", "display": "Ward A"}}]}
     * Result: unchanged — display already populated
     * }</pre>
     *
     * <p>Example — Step 2 (fetch display for existing reference):
     * <pre>{@code
     * Before: {"location": [{"location": {"reference": "Location/loc-5"}}]}
     * After:  {"location": [{"location": {"reference": "Location/loc-5", "display": "Ward A"}}]}
     * }</pre>
     *
     * <p>Example — Step 3 (derive from Encounter):
     * <pre>{@code
     * Before: {"resourceType": "Observation", "encounter": {"reference": "Encounter/enc-99"}}
     * After:  {"resourceType": "Observation", ..., "location": [{"location": {"reference": "Location/loc-5", "display": "Ward A"}}]}
     * }</pre>
     *
     * @param payload the FHIR resource JSON payload to modify
     * @param resourceType the FHIR resource type name (e.g. "Observation", "Encounter")
     * @param fhirR4LocationField the location field name ({@code "locationReference"} or {@code "location"})
     */
    private void enrichGenericLocation(ObjectNode payload, String resourceType, String fhirR4LocationField) {
        // Check if the payload already has a non-empty location array
        JsonNode existingLocation = payload.path(fhirR4LocationField);
        if (!existingLocation.isMissingNode() && existingLocation.isArray() && !existingLocation.isEmpty()) {
            // Step 1: If any entry already has a display → nothing to do, skip enrichment
            if (locationHasDisplay(existingLocation, fhirR4LocationField)) {
                log.debug("{} already has location with display \u2014 skipping", resourceType);
                return;
            }
            // Step 2: Location reference(s) exist without display → fetch and populate display names
            enrichExistingLocationDisplay(existingLocation, fhirR4LocationField, resourceType);
            return;
        }

        // Step 3: No location field at all → derive location data from the Encounter reference
        enrichLocationFromEncounter(payload, resourceType, fhirR4LocationField);
    }

    /**
     * Checks whether any entry in the location array already has a non-blank {@code display} field.
     *
     * <p>Used as the Step 1 gate in the generic flow — if display is already present,
     * no further enrichment is needed.
     *
     * <p>Handles both FHIR R4 location field structures:
     * <ul>
     *   <li>{@code locationReference[]} (ServiceRequest) — each entry IS the reference node</li>
     *   <li>{@code location[]} (Encounter) — each entry is a BackboneElement with nested {@code .location}</li>
     * </ul>
     *
     * <p>Example — returns {@code true}:
     * <pre>{@code
     * locationReference: [{"reference": "Location/loc-5", "display": "Ward A"}]
     * }</pre>
     *
     * <p>Example — returns {@code false}:
     * <pre>{@code
     * location: [{"location": {"reference": "Location/loc-5"}}]  // no display field
     * }</pre>
     *
     * @param locationArray the existing location JSON array from the payload
     * @param fhirR4LocationField the location field type ({@code "locationReference"} or {@code "location"})
     * @return {@code true} if at least one entry has a non-blank display, {@code false} otherwise
     */
    private boolean locationHasDisplay(JsonNode locationArray, String fhirR4LocationField) {
        for (JsonNode entry : locationArray) {
            // Navigate to the reference node — structure differs per FHIR R4 field type
            // locationReference: entry itself is the Reference (e.g. {"reference": "...", "display": "..."})
            // location: entry is BackboneElement, reference is at entry.location (e.g. {"location": {"reference": "..."}})
            JsonNode refNode = "locationReference".equals(fhirR4LocationField) ? entry : entry.path("location");
            if (refNode.isObject() && !refNode.path("display").asText("").isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fetches and populates the {@code display} field for location references that exist without one.
     *
     * <p>Iterates over the existing location array entries, skips those that already have a display
     * or don't reference a Location resource, and fetches the display name from the FHIR server
     * ({@code GET /Location/{id}?_elements=name}) for any that are missing it.
     *
     * <p>Example — ServiceRequest with locationReference missing display:
     * <pre>{@code
     * Before: {"locationReference": [{"reference": "Location/loc-5"}]}
     * After:  {"locationReference": [{"reference": "Location/loc-5", "display": "Ward A"}]}
     * }</pre>
     *
     * <p>Example — Encounter with BackboneElement missing display:
     * <pre>{@code
     * Before: {"location": [{"location": {"reference": "Location/loc-3"}}]}
     * After:  {"location": [{"location": {"reference": "Location/loc-3", "display": "ICU Room 2"}}]}
     * }</pre>
     *
     * @param locationArray the existing location JSON array from the payload
     * @param fhirR4LocationField the location field type ({@code "locationReference"} or {@code "location"})
     * @param resourceType the FHIR resource type name (for logging)
     */
    private void enrichExistingLocationDisplay(JsonNode locationArray, String fhirR4LocationField, String resourceType) {
        for (JsonNode entry : locationArray) {
            // Navigate to the reference node based on FHIR R4 field structure
            JsonNode refNode = "locationReference".equals(fhirR4LocationField) ? entry : entry.path("location");
            if (!refNode.isObject()) continue;

            // Skip entries that already have a non-blank display — no enrichment needed
            String display = refNode.path("display").asText(null);
            if (display != null && !display.isBlank()) continue;

            // Only process references to Location resources (e.g. "Location/loc-5")
            String reference = refNode.path("reference").asText(null);
            if (reference == null || !reference.startsWith(LOCATION_PREFIX)) continue;

            // Extract the Location ID and fetch display name from the FHIR server
            // e.g. "Location/loc-5" → "loc-5" → GET /Location/loc-5?_elements=name → "Ward A"
            String locationId = reference.substring(LOCATION_PREFIX.length());
            String locationDisplayName = locationResolver.fetchLocationDisplayName(locationId);
            if (locationDisplayName != null) {
                ((ObjectNode) refNode).put("display", locationDisplayName);
                log.debug("Enriched {} — set location display for Location/{}: {}",
                        resourceType, locationId, locationDisplayName);
            }
        }
    }

    /**
     * Derives location data from the Encounter referenced in the payload when no location field exists.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Look up the configured dot-path for finding Encounter references (e.g. {@code Observation → "encounter"})</li>
     *   <li>Extract the Encounter ID from the reference at that path</li>
     *   <li>Fetch the Encounter's {@code location[]} array from the FHIR server ({@code GET /Encounter/{id}?_elements=location})</li>
     *   <li>Set the fetched location data on the payload, adapting structure per FHIR R4 spec</li>
     * </ol>
     *
     * <p>Example — Observation with encounter reference, target uses BackboneElement:
     * <pre>{@code
     * Before: {"resourceType": "Observation", "encounter": {"reference": "Encounter/enc-99"}}
     * Fetched Encounter: {"location": [{"location": {"reference": "Location/loc-5", "display": "Ward A"}}]}
     * After:  {"resourceType": "Observation", ..., "location": [{"location": {"reference": "Location/loc-5", "display": "Ward A"}}]}
     * }</pre>
     *
     * <p>Example — ServiceRequest with encounter reference, target uses flat Reference[]:
     * <pre>{@code
     * Before: {"resourceType": "ServiceRequest", "encounter": {"reference": "Encounter/enc-99"}}
     * Fetched Encounter: {"location": [{"location": {"reference": "Location/loc-5", "display": "Ward A"}}]}
     * After:  {"resourceType": "ServiceRequest", ..., "locationReference": [{"reference": "Location/loc-5", "display": "Ward A"}]}
     * }</pre>
     *
     * @param payload the FHIR resource JSON payload to modify
     * @param resourceType the FHIR resource type name (e.g. "Observation", "ServiceRequest")
     * @param fhirR4LocationField the location field name ({@code "locationReference"} or {@code "location"})
     */
    private void enrichLocationFromEncounter(ObjectNode payload, String resourceType, String fhirR4LocationField) {
        // Step 1: Look up the configured path for finding Encounter references in this resource type
        // e.g. "Observation" → "encounter", "ServiceRequest" → "encounter"
        String encounterPath = locationFromEncounterPathMap.get(resourceType);
        if (encounterPath == null) {
            log.debug("No encounter-reference path configured for {} — skipping location enrichment", resourceType);
            return;
        }

        // Step 2: Extract the Encounter ID from the payload at the configured path
        // e.g. {"encounter": {"reference": "Encounter/enc-99"}} → "enc-99"
        String encounterId = locationResolver.extractEncounterIdAtPath(payload, encounterPath);
        if (encounterId == null) {
            log.debug("No Encounter reference found at path '{}' for {} — skipping", encounterPath, resourceType);
            return;
        }

        // Step 3: Fetch the Encounter's location[] array from the FHIR server
        // GET /Encounter/enc-99?_elements=location → {"location": [{"location": {"reference": "Location/loc-5", ...}}]}
        JsonNode encounterLocationData = locationResolver.fetchEncounterLocationData(encounterId);
        if (encounterLocationData == null) {
            log.debug("Encounter/{} has no location data — skipping", encounterId);
            return;
        }

        // Step 4: Set the location data on the payload, adapting structure per FHIR R4 spec
        if ("locationReference".equals(fhirR4LocationField)) {
            // ServiceRequest style: extract flat Reference[] from Encounter's BackboneElement[]
            // e.g. [{"location": {"reference": "Location/loc-5"}}] → [{"reference": "Location/loc-5"}]
            ArrayNode locationRefs = objectMapper.createArrayNode();
            for (JsonNode backboneEntry : encounterLocationData) {
                JsonNode locationRef = backboneEntry.path("location");
                if (locationRef.isObject() && locationRef.has("reference")) {
                    locationRefs.add(locationRef.deepCopy());
                }
            }
            if (!locationRefs.isEmpty()) {
                payload.set(fhirR4LocationField, locationRefs);
            }
        } else {
            // Encounter style: copy BackboneElement[] directly as-is
            // e.g. [{"location": {"reference": "Location/loc-5", "display": "Ward A"}}]
            payload.set(fhirR4LocationField, encounterLocationData.deepCopy());
        }

        log.debug("Enriched {} — set {} from Encounter/{}", resourceType, fhirR4LocationField, encounterId);
    }

    // ── FHIR R4 spec helper ──────────────────────────────────────────

    /**
     * Determines the FHIR R4 location field name for the given resource type using HAPI introspection.
     *
     * <p>Checks in order: {@code locationReference}, then {@code location}. Uses HAPI FHIR's
     * {@link RuntimeResourceDefinition} to introspect whether the resource type defines either field.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code ServiceRequest} → returns {@code "locationReference"} (flat Reference[] per FHIR R4 spec)</li>
     *   <li>{@code Encounter} → returns {@code "location"} (BackboneElement[] with nested {@code .location})</li>
     *   <li>{@code Observation} → returns {@code null} (no location field in FHIR R4 spec)</li>
     * </ul>
     *
     * @param resourceType the FHIR resource type name (e.g. "ServiceRequest", "Encounter", "Observation")
     * @return the location field name ({@code "locationReference"} or {@code "location"}), or {@code null} if none exists
     */
    private String getLocationFieldForResourceType(String resourceType) {
        // Use HAPI FHIR runtime introspection to check if the resource type has a location field
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        // ServiceRequest defines locationReference (flat Reference array)
        if (resourceDef.getChildByName("locationReference") != null) {
            return "locationReference";
        }
        // Encounter defines location (BackboneElement array with nested .location reference)
        if (resourceDef.getChildByName("location") != null) {
            return "location";
        }
        // Resource has no location field (e.g. Observation, Condition, Patient)
        return null;
    }
}
