package org.openphc.cce.emitter.service.resolver;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IRead;
import ca.uhn.fhir.rest.gclient.IReadExecutable;
import ca.uhn.fhir.rest.gclient.IReadTyped;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerConfig;
import org.openphc.cce.emitter.config.EmitterProperties.ReferenceResolutionConfig;
import org.openphc.cce.emitter.service.FhirClientFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NationalIdResolver}.
 */
@ExtendWith(MockitoExtension.class)
class NationalIdResolverTest {

    private static FhirContext fhirContext;
    private static ObjectMapper objectMapper;

    @Mock private FhirClientFactory fhirClientFactory;
    @Mock private IGenericClient fhirClient;
    @Mock private IRead readBuilder;
    @Mock private IReadTyped<IBaseResource> readTyped;
    @Mock private IReadExecutable<IBaseResource> readExecutable;

    @BeforeAll
    static void initContext() {
        fhirContext = FhirContext.forR4();
        objectMapper = new ObjectMapper();
    }

    @BeforeEach
    void setUp() {
        lenient().when(fhirClientFactory.createClient(any(FhirServerConfig.class))).thenReturn(fhirClient);
        lenient().when(fhirClient.read()).thenReturn(readBuilder);
        lenient().when(readBuilder.resource(anyString())).thenReturn(readTyped);
        lenient().when(readTyped.withId(anyString())).thenReturn(readExecutable);
        lenient().when(readExecutable.elementsSubset(any(String[].class))).thenReturn(readExecutable);
    }

    private EmitterProperties props(List<String> strategies, String suffix, String typeCode) {
        EmitterProperties p = new EmitterProperties();
        p.setFhirServer(new FhirServerConfig());
        ReferenceResolutionConfig rr = new ReferenceResolutionConfig();
        rr.setNationalIdMatchStrategies(strategies);
        rr.setNationalIdSystemSuffix(suffix);
        rr.setNationalIdTypeCode(typeCode);
        rr.setPersonIdentityResourceType("RelatedPerson");
        rr.setPersonIdentityReferencePaths(List.of(
                "Encounter:participant.individual",
                "ServiceRequest:performer",
                "Observation:performer",
                "Patient:link.other"
        ));
        p.setReferenceResolution(rr);
        return p;
    }

    private void stubReturn(IBaseResource resource) {
        when(readExecutable.execute()).thenReturn(resource);
    }

    private NationalIdResolver newResolver(EmitterProperties p) {
        FhirResourceFetcher fetcher = new FhirResourceFetcher(p, fhirContext, fhirClientFactory, objectMapper);
        return new NationalIdResolver(p, fetcher);
    }

    @Nested
    @DisplayName("Strategy: use-official (FHIR R4 standard)")
    class UseOfficialStrategy {

        @Test
        @DisplayName("returns identifier value where use=official")
        void resolvesByUseOfficial() {
            Patient patient = new Patient();
            patient.setId("123");
            patient.addIdentifier()
                    .setUse(Identifier.IdentifierUse.SECONDARY)
                    .setSystem("https://example.org/id/local")
                    .setValue("L-55");
            patient.addIdentifier()
                    .setUse(Identifier.IdentifierUse.OFFICIAL)
                    .setSystem("https://example.org/id/national")
                    .setValue("NID-10001");
            stubReturn(patient);

            NationalIdResolver resolver = newResolver(
                    props(List.of("use-official"), "/national-id", "NI"));

            assertEquals("NID-10001", resolver.fetchAndExtractNationalId("Patient", "123"));
        }

        @Test
        @DisplayName("returns null when no identifier has use=official")
        void noOfficialIdentifier() {
            Patient patient = new Patient();
            patient.setId("123");
            patient.addIdentifier().setSystem("X").setValue("Y");
            stubReturn(patient);

            NationalIdResolver resolver = newResolver(
                    props(List.of("use-official"), "/national-id", "NI"));

            assertNull(resolver.fetchAndExtractNationalId("Patient", "123"));
        }
    }

    @Nested
    @DisplayName("Strategy: type-code (HL7 v2-0203)")
    class TypeCodeStrategy {

        @Test
        @DisplayName("returns identifier value where type.coding[].code matches configured code")
        void resolvesByTypeCode() {
            Patient patient = new Patient();
            patient.setId("124");
            Identifier id = patient.addIdentifier();
            id.setSystem("https://country.gov/id").setValue("NID-20002");
            id.getType().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v2-0203")
                    .setCode("NI")
                    .setDisplay("National unique individual identifier");
            stubReturn(patient);

            NationalIdResolver resolver = newResolver(
                    props(List.of("type-code"), "/national-id", "NI"));

            assertEquals("NID-20002", resolver.fetchAndExtractNationalId("Patient", "124"));
        }

        @Test
        @DisplayName("respects configured type code (e.g. PPN for passport)")
        void resolvesByCustomTypeCode() {
            Patient patient = new Patient();
            patient.setId("124");
            Identifier id = patient.addIdentifier();
            id.setSystem("https://country.gov/passport").setValue("P-99999");
            id.getType().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v2-0203")
                    .setCode("PPN");
            stubReturn(patient);

            NationalIdResolver resolver = newResolver(
                    props(List.of("type-code"), "/national-id", "PPN"));

            assertEquals("P-99999", resolver.fetchAndExtractNationalId("Patient", "124"));
        }
    }

    @Nested
    @DisplayName("Strategy: system-suffix (SPICE / custom servers)")
    class SystemSuffixStrategy {

        @Test
        @DisplayName("matches SPICE-style identifier with /national-id suffix")
        void resolvesSpiceStyle() {
            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier()
                    .setSystem("http://spice-20-server-hapi-fhir-1:8080/fhir/identity-type")
                    .setValue("National ID");
            patient.addIdentifier()
                    .setSystem("http://spice-20-server-hapi-fhir-1:8080/fhir/national-id")
                    .setValue("NID-1774256338");
            patient.addIdentifier()
                    .setSystem("http://spice-20-server-hapi-fhir-1:8080/fhir/village-id")
                    .setValue("34");
            stubReturn(patient);

            NationalIdResolver resolver = newResolver(
                    props(List.of("system-suffix"), "/national-id", "NI"));

            assertEquals("NID-1774256338", resolver.fetchAndExtractNationalId("Patient", "616"));
        }

        @Test
        @DisplayName("matches flat identifier system (system=\"NID\")")
        void resolvesFlatSystemStyle() {
            Patient patient = new Patient();
            patient.setId("251119-0001-4106");
            patient.addIdentifier().setSystem("NID").setValue("1192880005226000");
            patient.addIdentifier().setSystem("UPI").setValue("251119-0001-4106");
            stubReturn(patient);

            NationalIdResolver resolver = newResolver(
                    props(List.of("system-suffix"), "NID", "NI"));

            assertEquals("1192880005226000",
                    resolver.fetchAndExtractNationalId("Patient", "251119-0001-4106"));
        }

        @Test
        @DisplayName("returns null when no identifier system ends with the configured suffix")
        void noMatchingSuffix() {
            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier().setSystem("http://spice/fhir/village-id").setValue("34");
            stubReturn(patient);

            NationalIdResolver resolver = newResolver(
                    props(List.of("system-suffix"), "/national-id", "NI"));

            assertNull(resolver.fetchAndExtractNationalId("Patient", "616"));
        }
    }

    @Nested
    @DisplayName("Multi-strategy ordering (defaults: use-official → type-code → system-suffix)")
    class MultiStrategyOrdering {

        private final List<String> defaults = List.of("use-official", "type-code", "system-suffix");

        @Test
        @DisplayName("falls through to system-suffix for SPICE Patient (no use/type fields)")
        void spiceFallsThroughToSuffix() {
            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier().setSystem("http://spice/fhir/national-id").setValue("NID-SPICE");
            stubReturn(patient);

            NationalIdResolver resolver = newResolver(props(defaults, "/national-id", "NI"));

            assertEquals("NID-SPICE", resolver.fetchAndExtractNationalId("Patient", "616"));
        }

        @Test
        @DisplayName("use-official wins over later strategies when present")
        void useOfficialWinsFirst() {
            Patient patient = new Patient();
            patient.setId("123");
            patient.addIdentifier()
                    .setUse(Identifier.IdentifierUse.OFFICIAL)
                    .setSystem("https://example.org/national")
                    .setValue("OFFICIAL-WINS");
            patient.addIdentifier()
                    .setSystem("http://spice/fhir/national-id")
                    .setValue("SUFFIX-LOSES");
            stubReturn(patient);

            NationalIdResolver resolver = newResolver(props(defaults, "/national-id", "NI"));

            assertEquals("OFFICIAL-WINS", resolver.fetchAndExtractNationalId("Patient", "123"));
        }
    }

    @Nested
    @DisplayName("resolveNationalIdFromPayload — full resource JSON resolution")
    class ResolveFromPayload {

        private final List<String> defaults = List.of("use-official", "type-code", "system-suffix");

        @Test
        @DisplayName("identity-source resource (RelatedPerson) — extracts from own identifiers")
        void identitySourceExtractsFromOwnIdentifiers() throws Exception {
            NationalIdResolver resolver = newResolver(props(defaults, "/national-id", "NI"));

            String json = """
                    {
                      "resourceType": "RelatedPerson",
                      "id": "499063",
                      "identifier": [
                        {"use": "official", "system": "http://example.org/nid", "value": "NID-12345"}
                      ]
                    }
                    """;

            assertEquals("NID-12345", resolver.resolveNationalIdFromPayload(objectMapper.readTree(json)));
        }

        @Test
        @DisplayName("identity-source resource with no matching identifier — returns null")
        void identitySourceNoMatchingIdentifier() throws Exception {
            NationalIdResolver resolver = newResolver(props(defaults, "/national-id", "NI"));

            String json = """
                    {
                      "resourceType": "RelatedPerson",
                      "id": "499063",
                      "identifier": [
                        {"system": "http://example.org/village-id", "value": "312"}
                      ]
                    }
                    """;

            assertNull(resolver.resolveNationalIdFromPayload(objectMapper.readTree(json)));
        }

        @Test
        @DisplayName("other resource — finds identity-source at path and fetches from FHIR server")
        void otherResourceResolvesViaPath() throws Exception {
            RelatedPerson rp = new RelatedPerson();
            rp.setId("499063");
            rp.addIdentifier()
                    .setUse(Identifier.IdentifierUse.OFFICIAL)
                    .setSystem("http://example.org/nid")
                    .setValue("NID-RESOLVED");
            stubReturn(rp);

            NationalIdResolver resolver = newResolver(props(defaults, "/national-id", "NI"));

            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-1",
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499063"}}
                      ]
                    }
                    """;

            assertEquals("NID-RESOLVED", resolver.resolveNationalIdFromPayload(objectMapper.readTree(json)));
        }

        @Test
        @DisplayName("other resource — no configured path → returns null")
        void noConfiguredPathReturnsNull() throws Exception {
            NationalIdResolver resolver = newResolver(props(defaults, "/national-id", "NI"));

            String json = """
                    {
                      "resourceType": "Organization",
                      "id": "org-1",
                      "name": "Test Hospital"
                    }
                    """;

            assertNull(resolver.resolveNationalIdFromPayload(objectMapper.readTree(json)));
        }

        @Test
        @DisplayName("other resource — no identity-source reference at configured path → returns null")
        void noIdentitySourceAtPathReturnsNull() throws Exception {
            NationalIdResolver resolver = newResolver(props(defaults, "/national-id", "NI"));

            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-2",
                      "participant": [
                        {"individual": {"reference": "Practitioner/123"}}
                      ]
                    }
                    """;

            assertNull(resolver.resolveNationalIdFromPayload(objectMapper.readTree(json)));
        }

        @Test
        @DisplayName("configurable identity-source type — uses Patient instead of RelatedPerson")
        void configurableIdentitySourceType() throws Exception {
            EmitterProperties p = new EmitterProperties();
            p.setFhirServer(new FhirServerConfig());
            ReferenceResolutionConfig rr = new ReferenceResolutionConfig();
            rr.setNationalIdMatchStrategies(defaults);
            rr.setNationalIdSystemSuffix("/national-id");
            rr.setNationalIdTypeCode("NI");
            rr.setPersonIdentityResourceType("Patient");
            rr.setPersonIdentityReferencePaths(List.of("Encounter:subject"));
            p.setReferenceResolution(rr);

            NationalIdResolver resolver = newResolver(p);

            String json = """
                    {
                      "resourceType": "Patient",
                      "id": "pat-1",
                      "identifier": [
                        {"use": "official", "system": "http://gov/nid", "value": "PAT-NID-999"}
                      ]
                    }
                    """;

            assertEquals("PAT-NID-999", resolver.resolveNationalIdFromPayload(objectMapper.readTree(json)));
        }

        @Test
        @DisplayName("configurable identity-source type — Encounter resolves Patient at subject path")
        void configurableIdentitySourceEncounterResolvesPatient() throws Exception {
            EmitterProperties p = new EmitterProperties();
            p.setFhirServer(new FhirServerConfig());
            ReferenceResolutionConfig rr = new ReferenceResolutionConfig();
            rr.setNationalIdMatchStrategies(defaults);
            rr.setNationalIdSystemSuffix("/national-id");
            rr.setNationalIdTypeCode("NI");
            rr.setPersonIdentityResourceType("Patient");
            rr.setPersonIdentityReferencePaths(List.of("Encounter:subject"));
            p.setReferenceResolution(rr);

            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier()
                    .setUse(Identifier.IdentifierUse.OFFICIAL)
                    .setSystem("http://gov/nid")
                    .setValue("PAT-NID-616");
            stubReturn(patient);

            NationalIdResolver resolver = newResolver(p);

            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-1",
                      "subject": {"reference": "Patient/616"}
                    }
                    """;

            assertEquals("PAT-NID-616", resolver.resolveNationalIdFromPayload(objectMapper.readTree(json)));
        }
    }

    @Nested
    @DisplayName("parsePathConfig utility")
    class ParsePathConfig {

        @Test
        @DisplayName("parses standard path entries")
        void parsesStandardEntries() {
            var result = ResolverPathHelper.parsePathConfig(List.of(
                    "Encounter:participant.individual",
                    "ServiceRequest:performer"
            ));
            assertEquals(2, result.size());
            assertEquals("participant.individual", result.get("Encounter"));
            assertEquals("performer", result.get("ServiceRequest"));
        }

        @Test
        @DisplayName("returns empty map for null input")
        void returnsEmptyForNull() {
            assertEquals(0, ResolverPathHelper.parsePathConfig(null).size());
        }
    }
}
