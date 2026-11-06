package org.openphc.cce.emitter.service.enrichment;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Strategy interface for FHIR resource enrichment.
 *
 * <p>Each implementation handles a single enrichment concern (e.g., national-id resolution,
 * practitioner display name, organization-based location). Strategies are executed in order
 * by the {@link ResourceEnrichmentOrchestrator} and operate on the same mutable {@link ObjectNode}
 * payload — parse once, serialize once.
 *
 * <p>Implementations must be self-contained and handle their own failures gracefully
 * (log and skip — never throw).
 */
public interface EnrichmentStrategy {

    /**
     * Enriches the payload in-place.
     *
     * <p>Must not throw — log and skip on failure.
     *
     * @param context shared enrichment context containing the mutable payload and resource metadata
     */
    void enrich(EnrichmentContext context);
}
