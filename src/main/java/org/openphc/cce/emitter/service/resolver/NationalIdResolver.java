package org.openphc.cce.emitter.service.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Resolves the national-id for a FHIR resource by determining the identity-source
 * reference (configurable, default: {@code RelatedPerson}) and extracting the
 * national-id from its {@code identifier[]} array.
 *
 * <p>Supported match strategies (tried in configured order, first match wins):
 * <ul>
 *   <li>{@code use-official} — {@code identifier.use == "official"}</li>
 *   <li>{@code type-code} — {@code identifier.type.coding[].code == nationalIdTypeCode}</li>
 *   <li>{@code system-suffix} — {@code identifier.system.endsWith(nationalIdSystemSuffix)}</li>
 * </ul>
 */
@Service
public class NationalIdResolver {

    private static final Logger log = LoggerFactory.getLogger(NationalIdResolver.class);

    private final List<String> matchStrategies;
    private final String nationalIdSystemSuffix;
    private final String nationalIdTypeCode;
    private final String personIdentityResourceType;
    private final String personIdentityResourcePrefix;
    private final Map<String, String> personIdentityReferencePathMap;

    private final FhirResourceFetcher fhirResourceFetcher;

    public NationalIdResolver(EmitterProperties properties,
                              FhirResourceFetcher fhirResourceFetcher) {
        this.fhirResourceFetcher = fhirResourceFetcher;

        var refConfig = properties.getReferenceResolution();
        this.matchStrategies = List.copyOf(refConfig.getNationalIdMatchStrategies());
        this.nationalIdSystemSuffix = refConfig.getNationalIdSystemSuffix();
        this.nationalIdTypeCode = refConfig.getNationalIdTypeCode();
        this.personIdentityResourceType = refConfig.getPersonIdentityResourceType();
        this.personIdentityResourcePrefix = personIdentityResourceType + "/";
        this.personIdentityReferencePathMap = ResolverPathHelper.parsePathConfig(refConfig.getPersonIdentityReferencePaths());

        log.info("NationalIdResolver — identity resource type: {}, paths: {}",
                personIdentityResourceType, personIdentityReferencePathMap.keySet());
    }

    /**
     * Resolves the national-id for a FHIR resource JSON payload.
     *
     * <p>If the resource IS the configured identity-source type (e.g. RelatedPerson), the national-id
     * is extracted directly from its own {@code identifier[]}. Otherwise, the configured dot-path is
     * walked to find the identity-source reference, which is fetched from the FHIR server.
     *
     * <p>Example — RelatedPerson (identity-source resource, extracts from own identifiers):
     * <pre>{@code
     * Input:  {"resourceType": "RelatedPerson", "identifier": [{"use": "official", "value": "NI-12345"}]}
     * Result: "NI-12345"
     * }</pre>
     *
     * <p>Example — Encounter (non-identity-source, walks path to find RelatedPerson reference):
     * <pre>{@code
     * Input:  {"resourceType": "Encounter", "participant": [{"individual": {"reference": "RelatedPerson/rp-1"}}]}
     * Path:   "participant.individual" → finds "RelatedPerson/rp-1" → fetches GET /RelatedPerson/rp-1?_elements=identifier
     * Result: "NI-67890" (extracted from fetched RelatedPerson's identifier[])
     * }</pre>
     *
     * @param incomingPayload parsed FHIR resource JSON tree
     * @return national-id value, or {@code null} if unresolvable (no path configured, no reference found, or no national-id in identifiers)
     */
    public String resolveNationalIdFromPayload(JsonNode incomingPayload) {
        String resourceType = incomingPayload.path("resourceType").asText("");
        try {
            // Case 1: Resource IS the identity-source (e.g. RelatedPerson) — extract national-id from own identifier[]
            if (personIdentityResourceType.equals(resourceType)) {
                JsonNode identifiers = incomingPayload.path("identifier");
                String nationalId = extractNationalIdFromIdentifiers(identifiers);
                if (nationalId == null) {
                    log.error("{} resource has no national-id — skipping forward", personIdentityResourceType);
                }
                return nationalId;
            }

            // Case 2: Resource references an identity-source — look up the configured dot-path
            // e.g. "Encounter" → "participant.individual", "ServiceRequest" → "performer"
            String personIdentityReferencePath = personIdentityReferencePathMap.get(resourceType);
            if (personIdentityReferencePath == null) {
                log.error("No reference path configured for resource type: {} — skipping forward", resourceType);
                return null;
            }

            // Walk the JSON tree at the configured path to find a "RelatedPerson/{id}" reference
            String personReferenceIdentifier = extractPersonReferenceIdentifierAtPath(incomingPayload, personIdentityReferencePath);
            if (personReferenceIdentifier == null) {
                log.error("No {} reference found at configured path '{}' for {} — skipping forward",
                        personIdentityResourceType, personIdentityReferencePath, resourceType);
                return null;
            }

            // Fetch the identity-source resource from FHIR server and extract its national-id
            // e.g. GET /RelatedPerson/rp-1?_elements=identifier → extract national-id from response
            String nationalId = fetchAndExtractNationalId(personIdentityResourceType, personReferenceIdentifier);
            if (nationalId == null) {
                log.error("Failed to resolve national-id from {}/{} for {} — skipping forward",
                        personIdentityResourceType, personReferenceIdentifier, resourceType);
            }
            return nationalId;

        } catch (Exception e) {
            log.error("National-id resolution failed for {}: {} — skipping forward",
                    resourceType, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches a FHIR resource by type and ID from the FHIR server and extracts the national-id
     * from its {@code identifier[]} array using configured match strategies.
     *
     * <p>Example:
     * <pre>{@code
     * Input: personIdentityResourceType="RelatedPerson", personReferenceIdentifier="rp-1"
     * Fetch: GET /RelatedPerson/rp-1?_elements=identifier
     * Response: {"identifier": [{"system": "http://example.org/national-id", "value": "NI-67890"}]}
     * Result: "NI-67890" (matched by system-suffix strategy)
     * }</pre>
     *
     * @param personIdentityResourceType the FHIR resource type to fetch (e.g. "RelatedPerson")
     * @param personReferenceIdentifier the resource ID to fetch (e.g. "rp-1")
     * @return national-id value, or {@code null} if the resource couldn't be fetched or has no matching identifier
     */
    public String fetchAndExtractNationalId(String personIdentityResourceType, String personReferenceIdentifier) {
        log.debug("Resolving reference {}/{} via FHIR client (_elements=identifier)",
                personIdentityResourceType, personReferenceIdentifier);
        JsonNode responseNode = fhirResourceFetcher.fetchResource(
                personIdentityResourceType, personReferenceIdentifier, "identifier");
        if (responseNode == null) {
            log.warn("Failed to resolve national-id for {}/{} — leaving reference unresolved",
                    personIdentityResourceType, personReferenceIdentifier);
            return null;
        }
        return extractNationalIdFromIdentifiers(responseNode.path("identifier"));
    }

    /**
     * Extracts the national-id value from a FHIR {@code identifier[]} array using configured match strategies.
     *
     * <p>Strategies are tried in configured order (default: {@code use-official → type-code → system-suffix}).
     * First match wins — subsequent strategies are not tried.
     *
     * <p>Example — matched by {@code use-official}:
     * <pre>{@code
     * Input:  [{"use": "official", "value": "NI-12345"}, {"system": "http://other", "value": "MRN-99"}]
     * Result: "NI-12345"
     * }</pre>
     *
     * <p>Example — matched by {@code system-suffix} (suffix="/national-id"):
     * <pre>{@code
     * Input:  [{"system": "http://example.org/national-id", "value": "NI-67890"}]
     * Result: "NI-67890"
     * }</pre>
     *
     * <p>Example — matched by {@code type-code} (code="NI"):
     * <pre>{@code
     * Input:  [{"type": {"coding": [{"code": "NI"}]}, "value": "NI-11111"}]
     * Result: "NI-11111"
     * }</pre>
     *
     * @param identifiers the FHIR identifier JSON array node
     * @return the national-id value, or {@code null} if no strategy matches
     */
    public String extractNationalIdFromIdentifiers(JsonNode identifiers) {
        if (!identifiers.isArray()) {
            log.warn("No identifier array found — leaving reference unresolved");
            return null;
        }

        for (String strategy : matchStrategies) {
            String value = switch (strategy) {
                case "use-official" -> findByUseOfficial(identifiers);
                case "type-code" -> findByTypeCode(identifiers);
                case "system-suffix" -> findBySystemSuffix(identifiers);
                default -> {
                    log.warn("Unknown national-id match strategy '{}' — skipping", strategy);
                    yield null;
                }
            };
            if (value != null) {
                log.debug("Resolved national-id={} (strategy={})", value, strategy);
                return value;
            }
        }

        log.warn("No national-id found using strategies {} — leaving reference unresolved", matchStrategies);
        return null;
    }

    // ── Path-based identity-source lookup ────────────────────────────

    /**
     * Walks the JSON tree at the configured dot-path to find a reference matching the identity-source prefix.
     *
     * <p>Example — path="participant.individual", prefix="RelatedPerson/":
     * <pre>{@code
     * Input:  {"participant": [{"individual": {"reference": "RelatedPerson/rp-1"}}]}
     * Result: "rp-1"
     * }</pre>
     */
    private String extractPersonReferenceIdentifierAtPath(JsonNode incomingPayload, String personIdentityReferencePath) {
        // Use ResolverPathHelper to walk arrays and find the first matching reference node
        JsonNode referenceNode = ResolverPathHelper.findReferenceNodeAtPath(
                incomingPayload, personIdentityReferencePath, personIdentityResourcePrefix);
        // Extract the ID portion after the prefix (e.g. "RelatedPerson/rp-1" → "rp-1")
        return ResolverPathHelper.extractIdFromReferenceNode(referenceNode, personIdentityResourcePrefix);
    }

    // ── National-id match strategies ────────────────────────────────

    /**
     * Matches by {@code identifier.system} ending with the configured suffix (e.g. "/national-id").
     *
     * <p>Example — suffix="/national-id":
     * <pre>{@code
     * Matches:     {"system": "http://example.org/national-id", "value": "NI-123"}
     * No match:    {"system": "http://example.org/mrn", "value": "MRN-456"}
     * }</pre>
     */
    private String findBySystemSuffix(JsonNode identifiers) {
        for (JsonNode id : identifiers) {
            String system = id.path("system").asText("");
            if (system.endsWith(nationalIdSystemSuffix)) {
                String value = id.path("value").asText(null);
                if (value != null && !value.isBlank()) return value;
            }
        }
        return null;
    }

    /**
     * Matches by {@code identifier.use == "official"}.
     *
     * <p>Example:
     * <pre>{@code
     * Matches:     {"use": "official", "value": "NI-12345"}
     * No match:    {"use": "usual", "value": "MRN-99"}
     * }</pre>
     */
    private String findByUseOfficial(JsonNode identifiers) {
        for (JsonNode id : identifiers) {
            if ("official".equals(id.path("use").asText(null))) {
                String value = id.path("value").asText(null);
                if (value != null && !value.isBlank()) return value;
            }
        }
        return null;
    }

    /**
     * Matches by {@code identifier.type.coding[].code} equaling the configured type code (e.g. "NI").
     *
     * <p>Example — typeCode="NI":
     * <pre>{@code
     * Matches:     {"type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0203", "code": "NI"}]}, "value": "NI-11111"}
     * No match:    {"type": {"coding": [{"code": "MR"}]}, "value": "MRN-222"}
     * }</pre>
     */
    private String findByTypeCode(JsonNode identifiers) {
        for (JsonNode id : identifiers) {
            JsonNode codings = id.path("type").path("coding");
            if (codings.isArray()) {
                for (JsonNode coding : codings) {
                    if (nationalIdTypeCode.equals(coding.path("code").asText(null))) {
                        String value = id.path("value").asText(null);
                        if (value != null && !value.isBlank()) return value;
                    }
                }
            }
        }
        return null;
    }
}
