package org.openphc.cce.emitter.service.enrichment;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.service.resolver.NationalIdResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enrichment strategy that resolves the national-id and places it on the
 * appropriate FHIR R4 field ({@code subject}, {@code patient}, or {@code identifier[]}).
 *
 * <p>Execution order: 100 (runs first — other strategies may depend on national-id being set).
 */
@Component
@Order(100)
public class NationalIdEnrichmentStrategy implements EnrichmentStrategy {

    private static final Logger log = LoggerFactory.getLogger(NationalIdEnrichmentStrategy.class);
    private static final String PATIENT_PREFIX = "Patient/";
    private static final List<String> FHIR_R4_PATIENT_REFERENCE_FIELDS = List.of("subject", "patient");

    private final NationalIdResolver nationalIdResolver;
    private final ObjectMapper objectMapper;
    private final FhirContext fhirContext;
    private final String nationalIdIdentifierSystem;

    public NationalIdEnrichmentStrategy(NationalIdResolver nationalIdResolver,
                                        ObjectMapper objectMapper,
                                        FhirContext fhirContext,
                                        EmitterProperties properties) {
        this.nationalIdResolver = nationalIdResolver;
        this.objectMapper = objectMapper;
        this.fhirContext = fhirContext;
        this.nationalIdIdentifierSystem = properties.getReferenceResolution().getNationalIdIdentifierSystem();
    }

    @Override
    public void enrich(EnrichmentContext context) {
        try {
            // Step 1: Resolve the national-id from the payload using configured strategies
            // (use-official → type-code → system-suffix) applied to the identity resource's identifiers
            String nationalId = nationalIdResolver.resolveNationalIdFromPayload(context.payload());
            if (nationalId == null) {
                log.warn("National-id resolution failed for {} — forwarding as-is without enrichment",
                        context.resourceType());
                return;
            }

            ObjectNode payload = context.payload();
            String resourceType = context.resourceType();

            // Step 2: Determine the appropriate field to place the Patient reference based on FHIR R4 spec.
            // Resources with "subject" field (Encounter, Observation, ServiceRequest, Condition, etc.)
            //   → set subject.reference = "Patient/<national-id>"
            // Resources with "patient" field (RelatedPerson, AllergyIntolerance, Claim, etc.)
            //   → set patient.reference = "Patient/<national-id>"
            // Resources with neither (Patient, Location, Organization, Practitioner, etc.)
            //   → add identifier entry with national-id value
            String patientReferenceField = getFhirR4PatientReferenceField(resourceType);
            if (patientReferenceField != null) {
                // Example: Encounter → subject.reference = "Patient/1212121212"
                //          RelatedPerson → patient.reference = "Patient/1212121212"
                String patientReference = PATIENT_PREFIX + nationalId;
                setPatientReference(payload, patientReferenceField, patientReference);
                log.debug("Enriched {} — set {}.reference = {}", resourceType, patientReferenceField, patientReference);
            } else {
                // Example: Patient → identifier[]: [{"system": "http://openphc.org/identifier/upid", "value": "1212121212"}]
                addNationalIdIdentifier(payload, nationalId);
                log.debug("Enriched {} — added national-id identifier: {}", resourceType, nationalId);
            }
        } catch (Exception e) {
            log.error("National-id enrichment failed for {} — forwarding as-is: {}",
                    context.resourceType(), e.getMessage());
        }
    }

    /**
     * Determines which FHIR R4 field to use for the Patient reference on the given resource type.
     *
     * <p>Checks in order: {@code subject}, then {@code patient}. Uses HAPI FHIR's
     * {@link RuntimeResourceDefinition} to introspect the resource structure at runtime.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code Encounter} → returns {@code "subject"}</li>
     *   <li>{@code RelatedPerson} → returns {@code "patient"}</li>
     *   <li>{@code Patient} → returns {@code null} (has neither field)</li>
     * </ul>
     *
     * @param resourceType the FHIR resource type name (e.g. "Encounter", "RelatedPerson")
     * @return the field name ({@code "subject"} or {@code "patient"}), or {@code null} if neither exists
     */
    private String getFhirR4PatientReferenceField(String resourceType) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        for (String patientReferenceField : FHIR_R4_PATIENT_REFERENCE_FIELDS) {
            if (resourceDef.getChildByName(patientReferenceField) != null) {
                return patientReferenceField;
            }
        }
        return null;
    }

    /**
     * Sets the Patient reference on the payload at the given field name.
     *
     * <p>If the field already exists as an object, updates the {@code reference} value in place.
     * Otherwise, creates a new object node with the reference.
     *
     * <p>Example — existing subject:
     * <pre>{@code
     * Before: {"subject": {"reference": "Group/498166"}}
     * After:  {"subject": {"reference": "Patient/1212121212"}}
     * }</pre>
     *
     * <p>Example — missing subject:
     * <pre>{@code
     * Before: {"resourceType": "Encounter", "id": "499084"}
     * After:  {"resourceType": "Encounter", "id": "499084", "subject": {"reference": "Patient/1212121212"}}
     * }</pre>
     *
     * @param payload the FHIR resource JSON payload to modify
     * @param patientReferenceField the field name ({@code "subject"} or {@code "patient"})
     * @param patientReference the full Patient reference string (e.g. {@code "Patient/1212121212"})
     */
    private void setPatientReference(ObjectNode payload, String patientReferenceField, String patientReference) {
        if (payload.has(patientReferenceField) && payload.get(patientReferenceField).isObject()) {
            // Field exists as object — update reference in place, preserving other fields (e.g. display)
            ((ObjectNode) payload.get(patientReferenceField)).put("reference", patientReference);
        } else {
            // Field missing or not an object — create fresh reference object
            payload.putObject(patientReferenceField).put("reference", patientReference);
        }
    }

    /**
     * Adds a national-id identifier entry to the resource's {@code identifier[]} array.
     *
     * <p>Used for resource types that have no {@code subject} or {@code patient} field
     * in the FHIR R4 spec (e.g. Patient, Location, Organization, Practitioner).
     *
     * <p>Example — existing identifiers:
     * <pre>{@code
     * Before: {"identifier": [{"system": "http://mdtlabs.com/some-id", "value": "existing"}]}
     * After:  {"identifier": [
     *            {"system": "http://mdtlabs.com/some-id", "value": "existing"},
     *            {"system": "http://openphc.org/identifier/upid", "value": "1212121212"}
     *          ]}
     * }</pre>
     *
     * <p>Example — no existing identifiers:
     * <pre>{@code
     * Before: {"resourceType": "Location", "id": "loc-1"}
     * After:  {"resourceType": "Location", "id": "loc-1",
     *          "identifier": [{"system": "http://openphc.org/identifier/upid", "value": "1212121212"}]}
     * }</pre>
     *
     * @param payload the FHIR resource JSON payload to modify
     * @param nationalId the resolved national-id value to add
     */
    private void addNationalIdIdentifier(ObjectNode payload, String nationalId) {
        ObjectNode identifierEntry = objectMapper.createObjectNode();
        identifierEntry.put("system", nationalIdIdentifierSystem);
        identifierEntry.put("value", nationalId);

        if (payload.has("identifier") && payload.get("identifier").isArray()) {
            // Append to existing identifier array
            ((ArrayNode) payload.get("identifier")).add(identifierEntry);
        } else {
            // Create new identifier array with the national-id entry
            payload.putArray("identifier").add(identifierEntry);
        }
    }
}
