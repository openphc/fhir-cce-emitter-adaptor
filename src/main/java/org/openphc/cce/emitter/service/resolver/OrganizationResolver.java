package org.openphc.cce.emitter.service.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves Organization references by walking configured JSON paths in the payload
 * to locate Organization references and fetching display names from the FHIR server.
 */
@Service
public class OrganizationResolver {

    private static final Logger log = LoggerFactory.getLogger(OrganizationResolver.class);
    private static final String ORGANIZATION_PREFIX = "Organization/";

    private final FhirResourceFetcher fhirResourceFetcher;

    public OrganizationResolver(FhirResourceFetcher fhirResourceFetcher) {
        this.fhirResourceFetcher = fhirResourceFetcher;
    }

    /**
     * Extracts the Organization ID from the payload by walking the configured dot-path.
     * Handles arrays by fan-out — returns the first Organization reference found.
     *
     * @param payload the FHIR resource JSON tree
     * @param dotPath dot-separated path to the Organization reference
     * @return the Organization ID (e.g. "1302"), or {@code null} if not found
     */
    public String extractOrganizationIdAtPath(JsonNode payload, String dotPath) {
        JsonNode referenceNode = ResolverPathHelper.findReferenceNodeAtPath(payload, dotPath, ORGANIZATION_PREFIX);
        return ResolverPathHelper.extractIdFromReferenceNode(referenceNode, ORGANIZATION_PREFIX);
    }

    /**
     * Fetches an Organization resource from the FHIR server and extracts its display name.
     *
     * @param organizationId the Organization resource ID
     * @return the Organization name, or {@code null} if fetch fails or no name
     */
    public String fetchOrganizationDisplayName(String organizationId) {
        JsonNode responseNode = fhirResourceFetcher.fetchResource("Organization", organizationId, "name");
        if (responseNode == null) {
            return null;
        }
        String name = responseNode.path("name").asText(null);
        return (name != null && !name.isBlank()) ? name : null;
    }
}
