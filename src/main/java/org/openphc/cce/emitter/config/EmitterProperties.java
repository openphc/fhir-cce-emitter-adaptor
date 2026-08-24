package org.openphc.cce.emitter.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Type-safe configuration properties for the FHIR CCE Emitter Adaptor.
 * Bound from {@code emitter.*} in application.yml.
 */
@Data
@ConfigurationProperties(prefix = "emitter")
public class EmitterProperties {

    /** Callback URL base — must be reachable by the FHIR server. */
    @NotBlank
    private String selfBaseUrl;

    /** FHIR R4 server configuration. */
    @Valid
    @NotNull
    private FhirServerConfig fhirServer = new FhirServerConfig();

    /** OpenHIM connection configuration. */
    @Valid
    @NotNull
    private OpenhimConfig openhim = new OpenhimConfig();

    /** Startup auto-subscription configuration. */
    @Valid
    private StartupSubscriptionConfig startupSubscriptions = new StartupSubscriptionConfig();

    /** Reference resolution configuration. */
    @Valid
    private ReferenceResolutionConfig referenceResolution = new ReferenceResolutionConfig();

    // ── Inner config classes ────────────────────────────────────────────

    @Data
    public static class FhirServerConfig {
        @NotBlank
        private String name = "default-fhir";

        @NotBlank
        private String url = "http://localhost:8090/fhir";

        @Valid
        private FhirServerAuthConfig auth = new FhirServerAuthConfig();
    }

    @Data
    public static class FhirServerAuthConfig {
        /** Auth type: none | basic | bearer | token-endpoint | oauth2 */
        private String type = "none";

        /** Username for basic / token-endpoint auth. */
        private String username;

        /** Password for basic / token-endpoint auth. */
        private String password;

        /** Static Bearer token for bearer auth. */
        private String token;

        /** Token endpoint URL for token-endpoint / oauth2 auth. */
        private String tokenUrl;

        /** Client type header sent to token endpoint. */
        private String client = "web";

        /** Cookie name to extract JWT from Set-Cookie header. */
        private String tokenCookieName = "AuthCookie";

        /** Whether the cookie value is base64-encoded. */
        private boolean tokenCookieBase64 = true;

        /** JSON field name for token in response body. */
        private String tokenBodyField;

        /** OAuth2 client ID (for oauth2 auth type). */
        private String clientId;

        /** OAuth2 client secret (for oauth2 auth type). */
        private String clientSecret;

        /** OAuth2 scope, space-separated (for oauth2 auth type). */
        private String scope;

        /** Token cache TTL in seconds. Overridden by expires_in for oauth2. */
        private long tokenTtlSeconds = 3600;
    }

    @Data
    public static class OpenhimConfig {
        @NotBlank
        private String name = "openhim";

        @NotBlank
        private String baseUrl = "http://localhost:5001/fhir";

        @Valid
        private OpenhimAuthConfig auth = new OpenhimAuthConfig();

        /** Trust all SSL certificates for OpenHIM connections. */
        private boolean sslTrustAll = false;

        /** Append FHIR resource type to OpenHIM URL. */
        private boolean appendResourceType = true;
    }

    @Data
    public static class OpenhimAuthConfig {
        /** Auth type: none | basic | jwt | custom-token */
        private String type = "basic";

        /** Username for basic auth. */
        private String username;

        /** Password for basic auth. */
        private String password;

        /** Token string for jwt / custom-token auth. */
        private String token;
    }

    @Data
    public static class StartupSubscriptionConfig {
        /** Enable automatic subscription on startup. */
        private boolean enabled = false;

        /** Delay in seconds before subscribing (allows FHIR server to become ready). */
        private int delaySeconds = 10;

        /** Maximum number of existing subscriptions to fetch in a single query. */
        private int fetchPageSize = 500;

        /** Max retry attempts when fetching existing subscriptions fails (FHIR server not ready). */
        private int fetchRetryMaxAttempts = 3;

        /** Backoff delay in milliseconds between fetch retry attempts. */
        private long fetchRetryBackoffMs = 5000;

        /** FHIR R4 resource types to subscribe to on startup. */
        private List<String> resourceTypes = List.of(
                "Patient", "RelatedPerson", "Encounter", "Observation",
                "Condition", "MedicationRequest", "MedicationDispense",
                "MedicationStatement", "DiagnosticReport", "QuestionnaireResponse",
                "ServiceRequest", "CarePlan", "Appointment", "Group",
                "Location", "Organization", "Practitioner", "Coverage",
                "PaymentNotice", "Device", "Provenance"
        );
    }

    @Data
    public static class ReferenceResolutionConfig {

        /**
         * The FHIR resource type used as the identity source for national-id extraction.
         * When an incoming callback is for this resource type, the national-id is extracted
         * from its own {@code identifier[]}. For all other resource types, the configured
         * path is walked to find a reference to this type, which is then fetched from the
         * FHIR server to extract the national-id.
         *
         * <p>Default: {@code RelatedPerson}. Can also be {@code Patient} or any resource
         * type that carries a national-id identifier.
         *
         * <p>Configurable via {@code EMITTER_PERSON_IDENTITY_RESOURCE_TYPE}.
         */
        private String personIdentityResourceType = "RelatedPerson";

        /**
         * Ordered list of strategies used to locate the national-id in {@code identifier[]}.
         * Strategies are tried in order; the first match wins.
         *
         * <p>Supported values:
         * <ul>
         *   <li>{@code system-suffix} — match {@code identifier.system.endsWith(nationalIdSystemSuffix)}.
         *       Used by SPICE and custom FHIR servers.</li>
         *   <li>{@code use-official} — match {@code identifier.use == "official"} (FHIR R4 standard).</li>
         *   <li>{@code type-code} — match {@code identifier.type.coding[].code == nationalIdTypeCode}
         *       (HL7 v2-0203 code {@code NI} = National unique individual identifier).</li>
         * </ul>
         * Configurable via {@code EMITTER_NATIONAL_ID_MATCH_STRATEGIES} (comma-separated).
         */
        private List<String> nationalIdMatchStrategies = List.of("use-official", "type-code", "system-suffix");

        /**
         * Suffix (or full URI) matched against {@code identifier.system} when strategy is
         * {@code system-suffix}. Configurable via {@code EMITTER_NATIONAL_ID_SYSTEM_SUFFIX}.
         */
        private String nationalIdSystemSuffix = "/national-id";

        /**
         * HL7 identifier type code matched against {@code identifier.type.coding[].code}
         * when strategy is {@code type-code}. Default {@code NI} = National unique individual
         * identifier (HL7 v2-0203). Configurable via {@code EMITTER_NATIONAL_ID_TYPE_CODE}.
         */
        private String nationalIdTypeCode = "NI";

        /**
         * The {@code identifier.system} URI used when enriching payloads with a national-id
         * identifier entry. This is the system value written into the outgoing payload's
         * {@code identifier[]} array for resource types without a {@code subject} field.
         *
         * <p>Default: {@code http://openphc.org/identifier/upid}.
         * Configurable via {@code EMITTER_NATIONAL_ID_IDENTIFIER_SYSTEM}.
         */
        private String nationalIdIdentifierSystem = "http://openphc.org/identifier/upid";

        /**
         * Configurable JSON paths per resource type for locating the identity-source
         * reference in the payload. Each entry is {@code ResourceType:dot.separated.path},
         * e.g. {@code Encounter:participant.individual.reference}.
         *
         * <p>The path is walked segment by segment from the root object. When a segment
         * points to an array, all elements are traversed. The final segment should be
         * {@code reference} — the resolver looks for a value starting with
         * {@code <identityResourceType>/}.
         *
         * <p>If a resource type has no configured path, forwarding is skipped with an
         * error log. Resources matching the {@code identityResourceType} are always handled
         * as a special case (national-id extracted from own identifiers) regardless of
         * this config.
         *
         * <p>Configurable via {@code EMITTER_PERSON_IDENTITY_REFERENCE_PATHS} (comma-separated).
         */
        private List<String> personIdentityReferencePaths = List.of(
                "Encounter:participant.individual",
                "ServiceRequest:performer",
                "Observation:performer",
                "Patient:link.other"

        );

        /**
         * Configurable JSON paths per resource type for locating the Practitioner
         * reference object whose {@code display} field should be enriched.
         * Each entry is {@code ResourceType:dot.separated.path}, where the path
         * points to the reference object (containing both {@code reference} and
         * {@code display} fields).
         *
         * <p>Example: {@code Encounter:participant.individual} — the resolver walks
         * to each {@code participant[].individual} object, checks if
         * {@code reference} starts with {@code "Practitioner/"}, and if
         * {@code display} is absent, fetches the Practitioner from the FHIR server
         * and populates the display name.
         *
         * <p>Configurable via {@code EMITTER_PRACTITIONER_DISPLAY_PATHS} (comma-separated).
         */
        private List<String> practitionerDisplayPaths = List.of(
                "Encounter:participant.individual",
                "Observation:performer",
                "ServiceRequest:performer",
                "Condition:asserter"
        );

        /**
         * Whether to derive location from Organization references at configured paths.
         *
         * <p>When enabled, the
         * Organization-path approach is used: walks the configured
         * {@code location-from-organization-paths} to find an Organization reference, fetches
         * its display name, and sets the location field.
         *
         * <p>When disabled, the generic location enrichment flow is used instead:
         * <ol>
         *   <li>Location exists with display → skip</li>
         *   <li>Location reference exists without display → fetch display from Location resource</li>
         *   <li>No location → find Encounter reference at configured path, fetch Encounter,
         *       extract its location data, and set on the payload</li>
         * </ol>
         *
         * <p>Configurable via {@code EMITTER_LOCATION_ENRICHMENT_BASED_ON_ORGANIZATION_PATH}.
         */
        private boolean locationEnrichmentBasedOnOrganizationPath = true;

        /**
         * Configurable JSON paths per resource type for locating the Encounter reference
         * in the payload. Used by the generic location enrichment flow (when
         * {@code location-enrichment-based-on-organization-path=false}) to find the Encounter
         * whose location data should be copied to the incoming resource.
         *
         * <p>Each entry is {@code ResourceType:dot.separated.path}, e.g.
         * {@code Observation:encounter} — the resolver walks to the {@code encounter}
         * field and looks for a reference starting with {@code "Encounter/"}.
         *
         * <p>Configurable via {@code EMITTER_LOCATION_FROM_ENCOUNTER_PATHS} (comma-separated).
         */
        private List<String> locationFromEncounterPaths = List.of(
                "Observation:encounter",
                "ServiceRequest:encounter",
                "Condition:encounter",
                "MedicationRequest:encounter"
        );

        /**
         * Configurable JSON paths per resource type for locating the Organization
         * reference in the payload. The Organization reference and its display name
         * (fetched from the FHIR server) are used to populate the location field.
         *
         * <p>Each entry is {@code ResourceType:dot.separated.path}, where the path
         * points to the field containing the Organization reference string.
         *
         * <p>Example: {@code Encounter:serviceProvider.reference} — the resolver walks
         * to {@code serviceProvider.reference} and extracts the Organization ID from
         * a value like {@code "Organization/1302"}.
         *
         * <p>Configurable via {@code EMITTER_LOCATION_FROM_ORGANIZATION_PATHS} (comma-separated).
         */
        private List<String> locationFromOrganizationPaths = List.of(
                "Encounter:serviceProvider",
                "ServiceRequest:performer"
        );
    }
}
