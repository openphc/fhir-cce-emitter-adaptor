package org.openphc.cce.emitter.service.resolver;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.service.FhirClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shared helper that fetches a FHIR resource by type and ID from the configured
 * FHIR server and returns the response as a parsed Jackson {@link JsonNode}.
 *
 * <p>Encapsulates the repeated pattern across all resolvers:
 * <pre>
 *   client.read().resource(type).withId(id).elementsSubset(fields).execute()
 *   → encode to JSON string → parse to JsonNode
 * </pre>
 *
 * <p>Each call fetches fresh from the FHIR server — no caching.
 */
@Service
public class FhirResourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(FhirResourceFetcher.class);

    private final EmitterProperties properties;
    private final FhirContext fhirContext;
    private final FhirClientFactory fhirClientFactory;
    private final ObjectMapper objectMapper;

    public FhirResourceFetcher(EmitterProperties properties,
                               FhirContext fhirContext,
                               FhirClientFactory fhirClientFactory,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.fhirContext = fhirContext;
        this.fhirClientFactory = fhirClientFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches a FHIR resource and returns the response as a parsed JsonNode.
     *
     * @param resourceType  FHIR resource type (e.g. "Practitioner", "Organization", "RelatedPerson")
     * @param resourceId    resource ID on the FHIR server
     * @param elementsSubset fields to request via {@code _elements} (e.g. "name", "identifier")
     * @return parsed JSON response, or {@code null} if fetch fails
     */
    public JsonNode fetchResource(String resourceType, String resourceId, String... elementsSubset) {
        log.debug("Fetching {}/{} (_elements={})", resourceType, resourceId, elementsSubset);
        try {
            IGenericClient client = fhirClientFactory.createClient(properties.getFhirServer());
            IBaseResource resource = client.read()
                    .resource(resourceType)
                    .withId(resourceId)
                    .elementsSubset(elementsSubset)
                    .execute();

            String responseJson = fhirContext.newJsonParser().encodeResourceToString(resource);
            return objectMapper.readTree(responseJson);

        } catch (Exception e) {
            log.warn("Failed to fetch {}/{}: {}", resourceType, resourceId, e.getMessage());
            return null;
        }
    }
}
