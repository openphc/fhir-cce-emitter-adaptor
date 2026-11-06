package org.openphc.cce.emitter.service.enrichment;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared context passed through the enrichment orchestrator.
 *
 * <p>Contains the mutable FHIR resource payload (Jackson ObjectNode) and
 * resource metadata extracted during parsing. Strategies mutate the payload
 * in-place — no copies are made between strategy invocations.
 *
 * @param payload      mutable JSON tree of the incoming FHIR resource
 * @param resourceType FHIR resource type (e.g. "Encounter", "RelatedPerson")
 * @param resourceId   FHIR resource ID (e.g. "499063")
 */
public record EnrichmentContext(ObjectNode payload, String resourceType, String resourceId) {
}
