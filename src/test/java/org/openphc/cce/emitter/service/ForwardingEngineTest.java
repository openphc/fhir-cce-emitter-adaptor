package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.EmitterProperties;import org.openphc.cce.emitter.config.EmitterProperties.*;
import org.openphc.cce.emitter.service.enrichment.ResourceEnrichmentOrchestrator;
import org.springframework.http.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ForwardingEngine}.
 * Covers forwarding flow, URL construction, auth headers, retry logic,
 * SSL trust selection, and metrics.
 */
@ExtendWith(MockitoExtension.class)
class ForwardingEngineTest {

    @Mock
    private FhirContext fhirContext;

    @Mock
    private RestTemplate standardRestTemplate;

    @Mock
    private RestTemplate trustAllRestTemplate;

    @Mock
    private IParser jsonParser;

    @Mock
    private IBaseResource parsedResource;

    @Mock
    private IIdType idType;

    @Mock
    private ResourceEnrichmentOrchestrator enrichmentOrchestrator;

    private MeterRegistry meterRegistry;
    private EmitterProperties properties;
    private ForwardingEngine engine;

    private static final String SAMPLE_JSON = """
            {
                "resourceType": "Patient",
                "id": "123"
            }
            """;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        properties = buildDefaultProperties();
        // ResourceEnrichmentOrchestrator returns input unchanged by default (pass-through)
        lenient().when(enrichmentOrchestrator.enrichReferences(anyString())).thenAnswer(i -> i.getArgument(0));
        engine = new ForwardingEngine(fhirContext, properties,
                standardRestTemplate, trustAllRestTemplate, enrichmentOrchestrator, meterRegistry);
    }

    private EmitterProperties buildDefaultProperties() {
        EmitterProperties props = new EmitterProperties();
        props.setSelfBaseUrl("http://localhost:9090");

        OpenhimConfig openhim = new OpenhimConfig();
        openhim.setBaseUrl("http://openhim:5001/fhir");
        openhim.setAppendResourceType(true);
        openhim.setSslTrustAll(false);

        OpenhimAuthConfig auth = new OpenhimAuthConfig();
        auth.setType("none");
        openhim.setAuth(auth);

        props.setOpenhim(openhim);
        return props;
    }

    private void stubFhirParse(String type, String id) {
        lenient().when(fhirContext.newJsonParser()).thenReturn(jsonParser);
        lenient().when(jsonParser.parseResource(anyString())).thenReturn(parsedResource);
        lenient().when(parsedResource.fhirType()).thenReturn(type);
        lenient().when(parsedResource.getIdElement()).thenReturn(idType);
        lenient().when(idType.toUnqualifiedVersionless()).thenReturn(idType);
        lenient().when(idType.getValue()).thenReturn(type + "/" + id);
    }

    private void stubFhirParseFailure() {
        when(fhirContext.newJsonParser()).thenReturn(jsonParser);
        when(jsonParser.parseResource(anyString())).thenThrow(new RuntimeException("Invalid JSON"));
    }

    private ResponseEntity<String> successResponse() {
        return new ResponseEntity<>("{\"status\":\"ok\"}", HttpStatus.OK);
    }

    private ResponseEntity<String> errorResponse(HttpStatus status, String body) {
        return new ResponseEntity<>(body, status);
    }

    // ── a. Forwarding flow ──────────────────────────────────────────────

    @Nested
    class ForwardingFlow {

        @Test
        void validResource_forwardsToOpenhim() {
            stubFhirParse("Patient", "123");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(successResponse());

            ForwardResult result = engine.forward("test-key", SAMPLE_JSON);

            assertTrue(result.isSuccess());
            verify(standardRestTemplate).exchange(
                    eq("http://openhim:5001/fhir/Patient"),
                    eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @Test
        void parseFailure_stillForwardsWithUnknownType() {
            stubFhirParseFailure();
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(successResponse());

            ForwardResult result = engine.forward("test-key", "not-valid-fhir");

            assertTrue(result.isSuccess());
            verify(standardRestTemplate).exchange(
                    eq("http://openhim:5001/fhir/Unknown"),
                    eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @Test
        void validResource_correctUrlConstructed() {
            stubFhirParse("Observation", "obs-456");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(successResponse());

            engine.forward("key1", SAMPLE_JSON);

            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            verify(standardRestTemplate).exchange(urlCaptor.capture(),
                    eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
            assertEquals("http://openhim:5001/fhir/Observation", urlCaptor.getValue());
        }
    }

    // ── b. URL construction ─────────────────────────────────────────────

    @Nested
    class UrlConstruction {

        @Test
        void appendResourceTypeTrue_urlHasResourceType() {
            properties.getOpenhim().setAppendResourceType(true);
            stubFhirParse("Encounter", "enc-1");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(successResponse());

            engine.forward("key", SAMPLE_JSON);

            verify(standardRestTemplate).exchange(
                    eq("http://openhim:5001/fhir/Encounter"),
                    eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @Test
        void appendResourceTypeFalse_urlIsBaseOnly() {
            properties.getOpenhim().setAppendResourceType(false);
            stubFhirParse("Encounter", "enc-1");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(successResponse());

            engine.forward("key", SAMPLE_JSON);

            verify(standardRestTemplate).exchange(
                    eq("http://openhim:5001/fhir"),
                    eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }
    }

    // ── c. Authentication headers ───────────────────────────────────────

    @Nested
    class AuthHeaders {

        @Test
        void authTypeBasic_authorizationBasicHeader() {
            OpenhimAuthConfig auth = new OpenhimAuthConfig();
            auth.setType("basic");
            auth.setUsername("user");
            auth.setPassword("pass");

            HttpHeaders headers = engine.buildHeaders(auth);

            String expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes());
            assertEquals(expected, headers.getFirst(HttpHeaders.AUTHORIZATION));
            assertEquals(MediaType.APPLICATION_JSON, headers.getContentType());
        }

        @Test
        void authTypeJwt_authorizationBearerHeader() {
            OpenhimAuthConfig auth = new OpenhimAuthConfig();
            auth.setType("jwt");
            auth.setToken("my-jwt-token");

            HttpHeaders headers = engine.buildHeaders(auth);

            assertEquals("Bearer my-jwt-token", headers.getFirst(HttpHeaders.AUTHORIZATION));
        }

        @Test
        void authTypeCustomToken_authorizationCustomHeader() {
            OpenhimAuthConfig auth = new OpenhimAuthConfig();
            auth.setType("custom-token");
            auth.setToken("my-custom-token");

            HttpHeaders headers = engine.buildHeaders(auth);

            assertEquals("Custom my-custom-token", headers.getFirst(HttpHeaders.AUTHORIZATION));
        }

        @Test
        void authTypeNone_noAuthorizationHeader() {
            OpenhimAuthConfig auth = new OpenhimAuthConfig();
            auth.setType("none");

            HttpHeaders headers = engine.buildHeaders(auth);

            assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION));
            assertEquals(MediaType.APPLICATION_JSON, headers.getContentType());
        }
    }

    // ── d. No retry — single attempt per callback ────────────────────────

    @Nested
    class RetryLogic {

        @Test
        void firstAttemptSucceeds_noRetry() {
            stubFhirParse("Patient", "1");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(successResponse());

            ForwardResult result = engine.forward("key", SAMPLE_JSON);

            assertTrue(result.isSuccess());
            verify(standardRestTemplate, times(1)).exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @Test
        void connectionRefused_failsImmediately_noRetry() {
            stubFhirParse("Patient", "1");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            ForwardResult result = engine.forward("key", SAMPLE_JSON);

            assertFalse(result.isSuccess());
            assertEquals("unreachable", result.status());
            // Single attempt only — no retry loop
            verify(standardRestTemplate, times(1)).exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
            assertEquals(1.0, meterRegistry.counter("fhir.emitter.forward.failure").count());
        }

        @Test
        void allAttemptsFail_failureCounterIncremented() {
            stubFhirParse("Patient", "1");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            ForwardResult result = engine.forward("key", SAMPLE_JSON);

            assertFalse(result.isSuccess());
            assertEquals("unreachable", result.status());
            // Single attempt only — retry loop removed to prevent HAPI timeout loop
            verify(standardRestTemplate, times(1)).exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
            assertEquals(1.0, meterRegistry.counter("fhir.emitter.forward.failure").count());
        }

        @Test
        void connectionRefused_doesNotDelay_sleepNotCalled() {
            stubFhirParse("Patient", "1");
            ForwardingEngine spyEngine = spy(engine);

            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            spyEngine.forward("key", SAMPLE_JSON);

            // sleep must NOT be called — no backoff delay that could trigger HAPI timeout loop
            verify(spyEngine, never()).sleep(anyLong());
        }
    }

    // ── e. SSL trust ────────────────────────────────────────────────────

    @Nested
    class SslTrust {

        @Test
        void sslTrustAllTrue_usesTrustAllRestTemplate() {
            properties.getOpenhim().setSslTrustAll(true);
            stubFhirParse("Patient", "1");
            when(trustAllRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(successResponse());

            engine.forward("key", SAMPLE_JSON);

            verify(trustAllRestTemplate).exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class));
            verifyNoInteractions(standardRestTemplate);
        }

        @Test
        void sslTrustAllFalse_usesStandardRestTemplate() {
            properties.getOpenhim().setSslTrustAll(false);
            stubFhirParse("Patient", "1");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(successResponse());

            engine.forward("key", SAMPLE_JSON);

            verify(standardRestTemplate).exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class));
            verifyNoInteractions(trustAllRestTemplate);
        }
    }

    // ── f. Metrics ──────────────────────────────────────────────────────

    @Nested
    class Metrics {

        @Test
        void forward_incrementsCallbacksReceivedCounter() {
            stubFhirParse("Patient", "1");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(successResponse());

            engine.forward("key", SAMPLE_JSON);

            assertEquals(1.0, meterRegistry.counter("fhir.emitter.callbacks.received").count());
        }

        @Test
        void successfulForward_successCounterAndDurationRecorded() {
            stubFhirParse("Patient", "1");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(successResponse());

            engine.forward("key", SAMPLE_JSON);

            assertEquals(1.0, meterRegistry.counter("fhir.emitter.forward.success").count());
            // Duration timer should exist
            assertNotNull(meterRegistry.find("fhir.emitter.forward.duration")
                    .tag("outcome", "success").timer());
        }

        @Test
        void failedForward_failureCounterIncremented() {
            stubFhirParse("Patient", "1");
            when(standardRestTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            engine.forward("key", SAMPLE_JSON);

            assertEquals(1.0, meterRegistry.counter("fhir.emitter.forward.failure").count());
            assertEquals(0.0, meterRegistry.counter("fhir.emitter.forward.success").count());
        }
    }

    // ── ForwardResult record ────────────────────────────────────────────

    @Nested
    class ForwardResultTests {

        @Test
        void success_isSuccessTrue() {
            ForwardResult result = ForwardResult.success();
            assertTrue(result.isSuccess());
            assertEquals("success", result.status());
        }

        @Test
        void failure_isSuccessFalse() {
            ForwardResult result = ForwardResult.failure(500, "Internal error");
            assertFalse(result.isSuccess());
            assertEquals(500, result.statusCode());
            assertEquals("Internal error", result.body());
        }

        @Test
        void unreachable_isSuccessFalse() {
            ForwardResult result = ForwardResult.unreachable(3);
            assertFalse(result.isSuccess());
            assertEquals("unreachable", result.status());
            assertEquals(3, result.attempts());
        }

        @Test
        void skipped_isSuccessFalse() {
            ForwardResult result = ForwardResult.skipped();
            assertFalse(result.isSuccess());
            assertEquals("skipped", result.status());
            assertEquals(0, result.attempts());
        }
    }

    // ── g. Skip forwarding ──────────────────────────────────────────────

    @Nested
    class SkipForwarding {

        @Test
        void enricherReturnsNull_forwardSkipped_noOpenhimCall() {
            stubFhirParse("Encounter", "enc-1");
            when(enrichmentOrchestrator.enrichReferences(anyString())).thenReturn(null);

            ForwardResult result = engine.forward("key", SAMPLE_JSON);

            assertFalse(result.isSuccess());
            assertEquals("skipped", result.status());
            verifyNoInteractions(standardRestTemplate);
            verifyNoInteractions(trustAllRestTemplate);
            assertEquals(1.0, meterRegistry.counter("fhir.emitter.callbacks.received").count());
            assertEquals(1.0, meterRegistry.counter("fhir.emitter.forward.skipped").count());
            assertEquals(0.0, meterRegistry.counter("fhir.emitter.forward.success").count());
        }
    }
}
