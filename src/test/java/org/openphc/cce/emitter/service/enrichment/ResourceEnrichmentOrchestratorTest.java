package org.openphc.cce.emitter.service.enrichment;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.service.resolver.LocationResolver;
import org.openphc.cce.emitter.service.resolver.NationalIdResolver;
import org.openphc.cce.emitter.service.resolver.OrganizationResolver;
import org.openphc.cce.emitter.service.resolver.PractitionerResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ResourceEnrichmentOrchestrator} — tests the full enrichment orchestrator
 * with real strategies and mocked resolvers.
 */
@ExtendWith(MockitoExtension.class)
class ResourceEnrichmentOrchestratorTest {

    @Mock
    private NationalIdResolver nationalIdResolver;

    @Mock
    private PractitionerResolver practitionerResolver;

    @Mock
    private OrganizationResolver organizationResolver;

    @Mock
    private LocationResolver locationResolver;

    private ObjectMapper objectMapper;
    private FhirContext fhirContext;
    private ResourceEnrichmentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fhirContext = FhirContext.forR4();

        EmitterProperties properties = new EmitterProperties();
        properties.setReferenceResolution(new EmitterProperties.ReferenceResolutionConfig());

        // Allow PractitionerResolver's extractPractitionerRefNodeAtPath to use real implementation
        lenient().when(practitionerResolver.extractPractitionerRefNodeAtPath(any(), any())).thenCallRealMethod();

        // Create real strategy instances with mocked resolvers
        NationalIdEnrichmentStrategy nationalIdStrategy = new NationalIdEnrichmentStrategy(
                nationalIdResolver, objectMapper, fhirContext, properties);
        PractitionerDisplayEnrichmentStrategy practitionerStrategy = new PractitionerDisplayEnrichmentStrategy(
                practitionerResolver, properties);
        LocationEnrichmentStrategy locationStrategy = new LocationEnrichmentStrategy(
                organizationResolver, locationResolver, objectMapper, fhirContext, properties);

        orchestrator = new ResourceEnrichmentOrchestrator(objectMapper,
                List.of(nationalIdStrategy, practitionerStrategy, locationStrategy));
    }

    // ── Successful enrichment — subject reference ───────────────────

    @Nested
    @DisplayName("Subject enrichment — sets Patient subject from resolved national-id")
    class SubjectEnrichment {

        @Test
        @DisplayName("Encounter with existing subject — overwrites with resolved national-id")
        void overwritesExistingSubject() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "499084",
                      "subject": {"reference": "Patient/616"},
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499063"}}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("Encounter with Group subject — overwrites with Patient subject")
        void overwritesNonPatientSubject() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "499066",
                      "subject": {"reference": "Group/498166"},
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499063"}}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("Encounter without existing subject — creates subject with resolved national-id")
        void createsSubjectWhenMissing() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-no-subject",
                      "status": "finished",
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499063"}}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("ServiceRequest — sets Patient subject, leaves other references unchanged")
        void serviceRequestPreservesOtherReferences() throws Exception {
            String json = """
                    {
                      "resourceType": "ServiceRequest",
                      "id": "499114",
                      "subject": {"reference": "Patient/616"},
                      "encounter": {"reference": "Encounter/499084"},
                      "performer": [
                        {"reference": "RelatedPerson/499063"}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
            assertEquals("Encounter/499084", root.path("encounter").path("reference").asText());
        }
    }

    // ── Enrichment — patient.reference or identifier[] ────────────────

    @Nested
    @DisplayName("Enrichment — patient.reference for resources with 'patient' field, identifier[] for resources without subject/patient")
    class IdentifierEnrichment {

        @Test
        @DisplayName("RelatedPerson — sets patient.reference (has 'patient' field in FHIR R4)")
        void relatedPersonSetsPatientReference() throws Exception {
            String json = """
                    {
                      "resourceType": "RelatedPerson",
                      "id": "499063",
                      "identifier": [
                        {"system": "http://mdtlabs.com/national-id", "value": "1212121212"}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("patient").path("reference").asText());
            assertTrue(root.path("identifier").isArray());
            assertEquals(1, root.path("identifier").size());
        }

        @Test
        @DisplayName("AllergyIntolerance — sets patient.reference (has 'patient' field in FHIR R4)")
        void allergyIntoleranceSetsPatientReference() throws Exception {
            String json = """
                    {
                      "resourceType": "AllergyIntolerance",
                      "id": "allergy-101",
                      "asserter": {"reference": "RelatedPerson/499063"}
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("patient").path("reference").asText());
            assertTrue(root.path("identifier").isMissingNode());
        }

        @Test
        @DisplayName("Patient — adds national-id to identifier[] (no subject field in FHIR R4)")
        void patientAddsNationalIdIdentifier() throws Exception {
            String json = """
                    {
                      "resourceType": "Patient",
                      "id": "500861",
                      "identifier": [
                        {"system": "http://mdtlabs.com/some-id", "value": "existing-id"}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("9999999999");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("identifier").isArray());
            assertEquals(2, root.path("identifier").size());
            JsonNode enrichedIdentifier = root.path("identifier").get(1);
            assertEquals("http://openphc.org/identifier/upid", enrichedIdentifier.path("system").asText());
            assertEquals("9999999999", enrichedIdentifier.path("value").asText());
        }

        @Test
        @DisplayName("Resource with no existing identifier[] — creates array with national-id entry")
        void createsIdentifierArrayWhenMissing() throws Exception {
            String json = """
                    {
                      "resourceType": "Location",
                      "id": "loc-no-ids"
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("5555555555");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("identifier").isArray());
            assertEquals(1, root.path("identifier").size());
            JsonNode enrichedIdentifier = root.path("identifier").get(0);
            assertEquals("http://openphc.org/identifier/upid", enrichedIdentifier.path("system").asText());
            assertEquals("5555555555", enrichedIdentifier.path("value").asText());
        }
    }

    // ── Structural validation / forward as-is ────────────────────────

    @Nested
    @DisplayName("Structural validation — returns null for invalid payloads, as-is for resolution failures")
    class StructuralValidation {

        @Test
        @DisplayName("returns null for non-object JSON")
        void returnsNullForNonObjectJson() {
            String result = orchestrator.enrichReferences("[1, 2, 3]");
            assertNull(result);
        }

        @Test
        @DisplayName("returns null for payload with blank resourceType")
        void returnsNullForBlankResourceType() {
            String json = """
                    {
                      "resourceType": "",
                      "id": "123"
                    }
                    """;

            String result = orchestrator.enrichReferences(json);
            assertNull(result);
        }

        @Test
        @DisplayName("returns null for invalid JSON")
        void returnsNullForInvalidJson() {
            String result = orchestrator.enrichReferences("not valid json {{{");
            assertNull(result);
        }

        @Test
        @DisplayName("national-id resolver returns null — still returns enriched (empty) JSON (pipeline continues)")
        void continuesWhenNationalIdReturnsNull() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "499084",
                      "participant": [
                        {"individual": {"reference": "Practitioner/497436"}}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn(null);

            String result = orchestrator.enrichReferences(json);

            // Pipeline doesn't return null for resolution failures — strategies handle gracefully
            assertNotNull(result);
        }
    }

    // ── Practitioner display enrichment ─────────────────────────────

    @Nested
    @DisplayName("Practitioner display enrichment — fetches and sets display name on Practitioner references")
    class PractitionerDisplayEnrichment {

        @Test
        @DisplayName("Encounter with Practitioner participant — enriches display field")
        void enrichesPractitionerDisplayOnEncounter() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "502969",
                      "subject": {"reference": "Patient/499304"},
                      "participant": [
                        {"individual": {"reference": "Practitioner/12345"}}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(practitionerResolver.fetchPractitionerDisplayName("12345"))
                    .thenReturn("Dr. Aziz Muhammed");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Dr. Aziz Muhammed",
                    root.path("participant").get(0).path("individual").path("display").asText());
        }

        @Test
        @DisplayName("Encounter with RelatedPerson participant — no display enrichment (not Practitioner)")
        void skipsNonPractitionerReference() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "502969",
                      "subject": {"reference": "Patient/499304"},
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499291"}}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("participant").get(0).path("individual").path("display").isMissingNode());
        }

        @Test
        @DisplayName("Encounter with existing display — does not overwrite")
        void doesNotOverwriteExistingDisplay() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "502969",
                      "subject": {"reference": "Patient/499304"},
                      "participant": [
                        {"individual": {"reference": "Practitioner/12345", "display": "Existing Name"}}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Existing Name",
                    root.path("participant").get(0).path("individual").path("display").asText());
            verify(practitionerResolver, never()).fetchPractitionerDisplayName(anyString());
        }

        @Test
        @DisplayName("Observation with Practitioner performer — enriches display")
        void enrichesPractitionerDisplayOnObservation() throws Exception {
            String json = """
                    {
                      "resourceType": "Observation",
                      "id": "obs-123",
                      "subject": {"reference": "Patient/499304"},
                      "performer": [
                        {"reference": "Practitioner/67890"}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(practitionerResolver.fetchPractitionerDisplayName("67890"))
                    .thenReturn("Dr. Jean");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Dr. Jean", root.path("performer").get(0).path("display").asText());
        }

        @Test
        @DisplayName("Practitioner fetch fails — forwards without display (graceful degradation)")
        void forwardsWithoutDisplayOnFetchFailure() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "502969",
                      "subject": {"reference": "Patient/499304"},
                      "participant": [
                        {"individual": {"reference": "Practitioner/12345"}}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(practitionerResolver.fetchPractitionerDisplayName("12345"))
                    .thenReturn(null);

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("participant").get(0).path("individual").path("display").isMissingNode());
        }

        @Test
        @DisplayName("Resource type not in practitioner-display-paths — no enrichment attempt")
        void skipsResourceTypeWithoutPractitionerPath() throws Exception {
            String json = """
                    {
                      "resourceType": "MedicationRequest",
                      "id": "mr-789",
                      "subject": {"reference": "Patient/499304"},
                      "performer": [
                        {"reference": "Practitioner/12345"}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            verify(practitionerResolver, never()).fetchPractitionerDisplayName(anyString());
        }
    }

    // ── Location enrichment ─────────────────────────────────────────

    @Nested
    @DisplayName("Location enrichment — Organization-based location via configured paths")
    class LocationEnrichment {

        @Test
        @DisplayName("ServiceRequest with Organization in performer — adds locationReference[] with org reference + display")
        void addsLocationReferenceFromOrganizationForServiceRequest() throws Exception {
            String json = """
                    {
                      "resourceType": "ServiceRequest",
                      "id": "sr-1",
                      "performer": [
                        {"reference": "Practitioner/12345"},
                        {"reference": "Organization/1302"}
                      ]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(organizationResolver.extractOrganizationIdAtPath(any(JsonNode.class), eq("performer")))
                    .thenReturn("1302");
            when(organizationResolver.fetchOrganizationDisplayName("1302"))
                    .thenReturn("City Hospital");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("locationReference").isArray());
            assertEquals(1, root.path("locationReference").size());
            assertEquals("Organization/1302",
                    root.path("locationReference").get(0).path("reference").asText());
            assertEquals("City Hospital",
                    root.path("locationReference").get(0).path("display").asText());
        }

        @Test
        @DisplayName("Encounter with serviceProvider Organization — adds location[] with org reference + display")
        void addsLocationFromOrganizationForEncounter() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-1",
                      "serviceProvider": {"reference": "Organization/1302"}
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(organizationResolver.extractOrganizationIdAtPath(any(JsonNode.class), eq("serviceProvider")))
                    .thenReturn("1302");
            when(organizationResolver.fetchOrganizationDisplayName("1302"))
                    .thenReturn("ICU Room 3");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("location").isArray());
            assertEquals(1, root.path("location").size());
            assertEquals("Organization/1302",
                    root.path("location").get(0).path("location").path("reference").asText());
            assertEquals("ICU Room 3",
                    root.path("location").get(0).path("location").path("display").asText());
        }

        @Test
        @DisplayName("ServiceRequest with no Organization in performer — skips location enrichment")
        void skipsWhenNoOrganizationInPerformer() throws Exception {
            String json = """
                    {
                      "resourceType": "ServiceRequest",
                      "id": "sr-2",
                      "performer": [{"reference": "Practitioner/12345"}]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("locationReference").isMissingNode());
            verify(organizationResolver, never()).fetchOrganizationDisplayName(anyString());
        }

        @Test
        @DisplayName("Organization display fetch returns null — sets reference without display")
        void setsReferenceWithoutDisplayWhenFetchFails() throws Exception {
            String json = """
                    {
                      "resourceType": "ServiceRequest",
                      "id": "sr-3",
                      "performer": [{"reference": "Organization/999"}]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(organizationResolver.extractOrganizationIdAtPath(any(JsonNode.class), eq("performer")))
                    .thenReturn("999");
            when(organizationResolver.fetchOrganizationDisplayName("999"))
                    .thenReturn(null);

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("locationReference").isArray());
            assertEquals("Organization/999",
                    root.path("locationReference").get(0).path("reference").asText());
            assertTrue(root.path("locationReference").get(0).path("display").isMissingNode());
        }

        @Test
        @DisplayName("Observation (no location field in FHIR R4) — skips location enrichment entirely")
        void skipsForResourceWithNoLocationField() throws Exception {
            String json = """
                    {
                      "resourceType": "Observation",
                      "id": "obs-1",
                      "encounter": {"reference": "Encounter/456"},
                      "performer": [{"reference": "Practitioner/12345"}]
                    }
                    """;

            when(nationalIdResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = orchestrator.enrichReferences(json);

            assertNotNull(result);
            verify(organizationResolver, never()).fetchOrganizationDisplayName(anyString());
        }
    }
}
