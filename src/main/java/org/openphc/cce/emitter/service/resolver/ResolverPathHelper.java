package org.openphc.cce.emitter.service.resolver;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared utility for walking FHIR JSON trees along dot-separated paths
 * with array fan-out and prefix-based matching at leaf nodes.
 *
 * <p>Used by all resolvers ({@link NationalIdResolver}, {@link PractitionerResolver},
 * {@link OrganizationResolver}) that need to locate a FHIR reference at a configurable
 * dot-path within an incoming resource payload.
 *
 * <h3>Core Concepts</h3>
 * <ul>
 *   <li><b>Dot-path</b>: A string like {@code "participant.individual"} that describes
 *       how to navigate nested JSON fields — each segment separated by a dot represents one level
 *       of nesting. The path always points to the <em>container</em> of the reference object
 *       (NOT the {@code .reference} field itself).</li>
 *   <li><b>Array fan-out</b>: When the walker encounters a JSON array at any point during traversal,
 *       it iterates over each element and continues the path walk within each element. This allows
 *       matching inside arrays without requiring explicit array indexing in the path config.</li>
 *   <li><b>Prefix matching</b>: At the leaf (final segment), the walker checks whether the resolved
 *       node is a FHIR reference object — i.e. it has a {@code "reference"} field whose value
 *       starts with the given prefix (e.g. {@code "RelatedPerson/"}, {@code "Practitioner/"}).</li>
 *   <li><b>Returns the reference ObjectNode</b>: The single walker always returns the full reference
 *       object node (e.g. {@code {"reference": "RelatedPerson/499063"}}). Callers decide what to
 *       extract — the ID string, or mutate the node to add a {@code "display"} field.</li>
 * </ul>
 *
 * <h3>Design — Single Walker, Multiple Consumers</h3>
 * <p>All three resolvers use the same traversal. What differs is what they do with the returned node:</p>
 * <ul>
 *   <li>{@link NationalIdResolver} → extracts the ID from {@code node.reference} by stripping the prefix</li>
 *   <li>{@link OrganizationResolver} → extracts the ID from {@code node.reference} by stripping the prefix</li>
 *   <li>{@link PractitionerResolver} → uses the returned node directly to add a {@code display} field</li>
 * </ul>
 *
 * <h3>Example — Walking an Encounter Payload</h3>
 * <pre>{@code
 * // Given this FHIR Encounter JSON:
 * {
 *   "resourceType": "Encounter",
 *   "participant": [
 *     {
 *       "individual": {
 *         "reference": "RelatedPerson/499063"
 *       }
 *     }
 *   ]
 * }
 *
 * // Calling:
 * JsonNode refNode = JsonPathWalker.findReferenceNodeAtPath(
 *     payload, "participant.individual", "RelatedPerson/");
 *
 * // Traversal steps:
 * //   1. Start at root, look for "participant" → finds an array
 * //   2. Array fan-out: enter first element { "individual": {...} }
 * //   3. Final segment "individual" → resolves to {"reference": "RelatedPerson/499063"}
 * //   4. Check: is it an object with "reference" starting with "RelatedPerson/"? → YES
 * //   5. Return the node: {"reference": "RelatedPerson/499063"}
 * //
 * // Caller extracts what it needs:
 * String id = JsonPathWalker.extractIdFromReferenceNode(refNode, "RelatedPerson/");
 * // → "499063"
 * }</pre>
 */
public final class ResolverPathHelper {

    /**
     * Private constructor — this is a stateless utility class with only static methods.
     * No instances should be created.
     */
    private ResolverPathHelper() {
        // Utility class — no instantiation
    }

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Walks a dot-separated path through a JSON tree to find the first FHIR reference
     * object node whose {@code "reference"} field starts with the given prefix.
     *
     * <p>The path points to the <b>container</b> of the reference object — NOT to the
     * {@code "reference"} field itself. The walker resolves the path, then checks if the
     * resulting node is an object with a matching {@code "reference"} field.
     *
     * <p>Handles arrays at any depth by fan-out — iterates all array elements and
     * returns the first match found (depth-first, left-to-right).
     *
     * <h4>Example 1 — Finding a RelatedPerson reference in an Encounter</h4>
     * <pre>{@code
     * // Payload:
     * // {
     * //   "participant": [
     * //     { "individual": { "reference": "RelatedPerson/499063" } }
     * //   ]
     * // }
     * //
     * // Call:
     * JsonNode refNode = JsonPathWalker.findReferenceNodeAtPath(payload,
     *     "participant.individual",   // dot-path to the container
     *     "RelatedPerson/");          // prefix to match on "reference" field
     * // Returns: ObjectNode {"reference": "RelatedPerson/499063"}
     * //
     * // Extract the ID:
     * String id = JsonPathWalker.extractIdFromReferenceNode(refNode, "RelatedPerson/");
     * // Returns: "499063"
     * }</pre>
     *
     * <h4>Example 2 — Finding an Organization reference in a ServiceRequest</h4>
     * <pre>{@code
     * // Payload:
     * // { "performer": [{"reference": "Organization/1302"}] }
     * //
     * // Call:
     * JsonNode refNode = JsonPathWalker.findReferenceNodeAtPath(payload,
     *     "performer",           // dot-path (single segment)
     *     "Organization/");      // prefix
     * // Returns: ObjectNode {"reference": "Organization/1302"}
     * }</pre>
     *
     * <h4>Example 3 — Finding a Practitioner reference for display enrichment</h4>
     * <pre>{@code
     * // Payload:
     * // {
     * //   "participant": [
     * //     { "individual": { "reference": "Practitioner/12345" } }
     * //   ]
     * // }
     * //
     * // Call:
     * JsonNode refNode = JsonPathWalker.findReferenceNodeAtPath(payload,
     *     "participant.individual",   // dot-path
     *     "Practitioner/");           // prefix
     * // Returns: ObjectNode {"reference": "Practitioner/12345"}
     * //
     * // Mutate it to add display:
     * ((ObjectNode) refNode).put("display", "Dr. Jane Smith");
     * // Result: {"reference": "Practitioner/12345", "display": "Dr. Jane Smith"}
     * }</pre>
     *
     * <h4>Example 4 — No match returns null</h4>
     * <pre>{@code
     * // Payload has Practitioner, but we look for Organization:
     * // { "performer": [{"reference": "Practitioner/456"}] }
     * //
     * JsonNode refNode = JsonPathWalker.findReferenceNodeAtPath(payload,
     *     "performer", "Organization/");
     * // Returns: null (no "reference" field starts with "Organization/")
     * }</pre>
     *
     * @param rootPayloadNode the root JSON node of the FHIR resource to start traversal from
     * @param dotSeparatedPath dot-separated path to the <b>container</b> of the reference object.
     *                         Example: {@code "participant.individual"} (NOT {@code "participant.individual.reference"}).
     *                         Each dot-separated segment represents a level of JSON nesting.
     * @param referencePrefix  the FHIR reference prefix to match on the {@code "reference"} field
     *                         of the resolved node (e.g. {@code "RelatedPerson/"}, {@code "Organization/"}).
     * @return the first matching reference ObjectNode (has a {@code "reference"} field starting
     *         with the prefix), or {@code null} if no match found at the given path
     */
    public static JsonNode findReferenceNodeAtPath(JsonNode rootPayloadNode, String dotSeparatedPath, String referencePrefix) {
        // Split "participant.individual" into ["participant", "individual"]
        String[] pathSegments = dotSeparatedPath.split("\\.");

        // Start recursive traversal from the root node at segment index 0 (first segment).
        // The index 0 means "start processing from the first path segment".
        // As we descend into the tree, the index increments to track which segment we're at.
        int startingSegmentIndex = 0;
        return walkToReferenceNode(rootPayloadNode, pathSegments, startingSegmentIndex, referencePrefix);
    }

    /**
     * Extracts the resource ID from a reference node by reading its {@code "reference"} field
     * and stripping the prefix.
     *
     * <p>This is a convenience method for resolvers that need the ID string from the node
     * returned by {@link #findReferenceNodeAtPath}.
     *
     * <h4>Example</h4>
     * <pre>{@code
     * // Given refNode = {"reference": "RelatedPerson/499063"}
     * String id = JsonPathWalker.extractIdFromReferenceNode(refNode, "RelatedPerson/");
     * // Returns: "499063"
     *
     * // Given refNode = {"reference": "Organization/1302", "display": "Main Hospital"}
     * String id = JsonPathWalker.extractIdFromReferenceNode(refNode, "Organization/");
     * // Returns: "1302"
     * }</pre>
     *
     * @param referenceNode   the reference ObjectNode (as returned by {@link #findReferenceNodeAtPath})
     * @param referencePrefix the prefix to strip (e.g. {@code "RelatedPerson/"}, {@code "Organization/"})
     * @return the ID portion after the prefix, or {@code null} if the node is null or
     *         its {@code "reference"} field doesn't start with the prefix
     */
    public static String extractIdFromReferenceNode(JsonNode referenceNode, String referencePrefix) {
        if (referenceNode == null) {
            return null;
        }
        // Read the "reference" field value, e.g. "RelatedPerson/499063"
        String referenceValue = referenceNode.path("reference").asText(null);

        // Validate and strip the prefix to extract the ID
        if (referenceValue != null && referenceValue.startsWith(referencePrefix)) {
            // "RelatedPerson/499063".substring("RelatedPerson/".length()) → "499063"
            return referenceValue.substring(referencePrefix.length());
        }
        return null;
    }

    /**
     * Parses a list of {@code "ResourceType:dot.path"} configuration entries into a lookup map.
     *
     * <p>This is used to parse YAML/env-var configuration like:
     * <pre>{@code
     * emitter:
     *   reference-resolution:
     *     person-identity-reference-paths:
     *       - "Encounter:participant.individual"
     *       - "ServiceRequest:performer"
     *       - "Observation:performer"
     * }</pre>
     *
     * <h4>Example</h4>
     * <pre>{@code
     * List<String> configEntries = List.of(
     *     "Encounter:participant.individual",
     *     "ServiceRequest:performer"
     * );
     *
     * Map<String, String> pathMap = JsonPathWalker.parsePathConfig(configEntries);
     * // Result: { "Encounter" → "participant.individual",
     * //           "ServiceRequest" → "performer" }
     *
     * // Usage: look up the dot-path for a given resource type
     * String path = pathMap.get("Encounter");
     * // path = "participant.individual"
     * }</pre>
     *
     * <p>Entries without a colon separator, or with colon at the very start/end, are silently skipped.
     *
     * @param pathConfigEntries list of configuration strings in {@code "ResourceType:dot.path"} format.
     *                          May be {@code null} (returns empty map).
     * @return map where key = FHIR resource type (e.g. {@code "Encounter"}),
     *         value = dot-separated path (e.g. {@code "participant.individual"})
     */
    public static Map<String, String> parsePathConfig(List<String> pathConfigEntries) {
        Map<String, String> resourceTypeToPathMap = new HashMap<>();
        if (pathConfigEntries == null) return resourceTypeToPathMap;

        for (String configEntry : pathConfigEntries) {
            // Find the first colon which separates "ResourceType" from "dot.path"
            // Example: "Encounter:participant.individual"
            //           ^^^^^^^^^  ← resource type (before colon)
            //                      ^^^^^^^^^^^^^^^^^^^^^^ ← dot-path (after colon)
            int colonSeparatorIndex = configEntry.indexOf(':');

            // Validate: colon must exist, must not be at position 0 (empty type),
            // and must not be at the last position (empty path)
            boolean hasValidResourceType = colonSeparatorIndex > 0;
            boolean hasValidPath = colonSeparatorIndex < configEntry.length() - 1;

            if (hasValidResourceType && hasValidPath) {
                String resourceType = configEntry.substring(0, colonSeparatorIndex).trim();
                String dotPath = configEntry.substring(colonSeparatorIndex + 1).trim();
                resourceTypeToPathMap.put(resourceType, dotPath);
            }
        }
        return resourceTypeToPathMap;
    }

    // ═══════════════════════════════════════════════════════════════════
    // PRIVATE RECURSIVE WALKER
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Recursively walks the JSON tree following the path segments to find the first
     * FHIR reference object node at the leaf whose {@code "reference"} field starts
     * with the given prefix.
     *
     * <h4>Traversal Logic</h4>
     * <ol>
     *   <li><b>Null/Missing guard</b>: If the current node is null or missing, stop — return null.</li>
     *   <li><b>Array fan-out</b>: If the current node is a JSON array, iterate each element and
     *       recursively continue the walk from the same segment index (since the array itself is not
     *       a named path segment — it's a container of objects we need to search within).</li>
     *   <li><b>Intermediate segment</b>: If not at the last segment, descend into the child node
     *       named by the current segment and advance the index by 1.</li>
     *   <li><b>Leaf segment</b>: If we've reached the last segment, resolve the child node at that
     *       field name, then check if it (or its array elements) is a reference object matching
     *       the prefix.</li>
     * </ol>
     *
     * <h4>Walk-through Example</h4>
     * <pre>{@code
     * // Path segments: ["participant", "individual"]
     * // Prefix: "RelatedPerson/"
     * // Payload:
     * // {
     * //   "participant": [                              ← segment[0] = "participant"
     * //     {                                           ← array fan-out (iterate elements)
     * //       "individual": {                           ← segment[1] = "individual" (LEAF)
     * //         "reference": "RelatedPerson/499063"     ← check: has "reference" with prefix? YES
     * //       }
     * //     }
     * //   ]
     * // }
     * //
     * // Call: walkToReferenceNode(root, ["participant","individual"], 0, "RelatedPerson/")
     * //
     * // Step 1: index=0, root is object → not array, not leaf (index 0 != 1)
     * //         → descend into root.path("participant") → this is an ARRAY
     * // Step 2: index=1, array → fan-out into element {"individual":{...}}
     * // Step 3: index=1, element is object → IS leaf (index 1 == 1)
     * //         → get child at "individual" → {"reference": "RelatedPerson/499063"}
     * //         → isReferenceObjectWithPrefix? → YES → return this node
     * }</pre>
     *
     * @param currentNode         the JSON node currently being examined at this recursion level
     * @param pathSegments        the full array of path segments (e.g. ["participant", "individual"])
     * @param currentSegmentIndex the zero-based index into {@code pathSegments} indicating which segment
     *                            we are currently processing. Starts at 0 for the first segment and
     *                            increments as we descend deeper into the JSON tree. When it equals
     *                            {@code pathSegments.length - 1}, we've reached the leaf segment.
     * @param referencePrefix     the prefix to match on the {@code "reference"} field
     *                            (e.g. "RelatedPerson/", "Practitioner/")
     * @return the first matching reference ObjectNode, or {@code null} if not found
     */
    private static JsonNode walkToReferenceNode(JsonNode currentNode, String[] pathSegments, int currentSegmentIndex, String referencePrefix) {
        // Guard: stop recursion if the node is null or represents a missing JSON field
        if (currentNode == null || currentNode.isMissingNode()) {
            return null;
        }

        // ── Array fan-out ────────────────────────────────────────────────
        // FHIR resources commonly use arrays (e.g. "participant" is an array of BackboneElements).
        // When we encounter an array, we don't advance the segment index — instead we iterate
        // each array element and continue the walk from the SAME segment index.
        // This is because the array itself is not a named field in the path; it's just a container.
        //
        // Example: path = "participant.individual", and "participant" resolves to an array:
        //   participant: [ {individual: {...}}, {individual: {...}} ]
        //   We try each element at the same segment index (still looking for "individual" inside each).
        if (currentNode.isArray()) {
            for (JsonNode arrayElement : currentNode) {
                JsonNode result = walkToReferenceNode(arrayElement, pathSegments, currentSegmentIndex, referencePrefix);
                if (result != null) return result; // First match wins (depth-first)
            }
            return null; // No element in the array matched
        }

        // Navigate to the child node at the current path segment
        // Example: pathSegments[0] = "participant" → get the "participant" field
        String currentFieldName = pathSegments[currentSegmentIndex];
        JsonNode childNode = currentNode.path(currentFieldName);

        // ── Intermediate segment — descend deeper ────────────────────────
        int lastSegmentIndex = pathSegments.length - 1;
        boolean isIntermediateSegment = (currentSegmentIndex < lastSegmentIndex);

        if (isIntermediateSegment) {
            // Not at the leaf yet — descend into child and advance segment index
            int nextSegmentIndex = currentSegmentIndex + 1;
            return walkToReferenceNode(childNode, pathSegments, nextSegmentIndex, referencePrefix);
        }

        // ── Leaf segment — check if the resolved child is a matching reference node ──
        // At the leaf, the child node should be either:
        //   (a) A single reference object: {"reference": "Practitioner/12345"}
        //   (b) An array of reference objects: [{"reference": "Practitioner/12345"}, ...]
        //
        // We check each candidate to see if it's an object with a "reference" field
        // whose value starts with our prefix.

        if (childNode.isArray()) {
            // Case (b): Array of candidate reference objects — check each
            // Example: "performer": [{"reference": "Practitioner/12345"}, {"reference": "Organization/1"}]
            for (JsonNode candidateRefNode : childNode) {
                if (isReferenceObjectWithPrefix(candidateRefNode, referencePrefix)) {
                    return candidateRefNode;
                }
            }
            return null;
        }

        // Case (a): Single candidate reference object
        return isReferenceObjectWithPrefix(childNode, referencePrefix) ? childNode : null;
    }

    /**
     * Checks whether a JSON node is a FHIR reference object whose {@code "reference"}
     * field value starts with the specified prefix.
     *
     * <p>A FHIR reference object typically looks like:
     * <pre>{@code
     * {
     *   "reference": "Practitioner/12345",
     *   "display": "Dr. Smith"           // optional
     * }
     * }</pre>
     *
     * <h4>Examples</h4>
     * <pre>{@code
     * // Match — object with reference starting with "Practitioner/":
     * // {"reference": "Practitioner/12345"}  → returns true
     *
     * // No match — wrong prefix:
     * // {"reference": "Organization/1302"}   → returns false (for prefix "Practitioner/")
     *
     * // No match — not an object (e.g. a string or number):
     * // "some-string"                        → returns false
     *
     * // No match — object without "reference" field:
     * // {"display": "Dr. Smith"}             → returns false
     * }</pre>
     *
     * @param candidateNode   the JSON node to check
     * @param referencePrefix the expected prefix on the {@code "reference"} field value
     *                        (e.g. {@code "Practitioner/"}, {@code "Organization/"})
     * @return {@code true} if the node is an object with a {@code "reference"} field
     *         starting with the given prefix; {@code false} otherwise
     */
    private static boolean isReferenceObjectWithPrefix(JsonNode candidateNode, String referencePrefix) {
        // Must be a JSON object — primitive values or arrays can't be FHIR reference objects
        if (!candidateNode.isObject()) return false;

        // Read the "reference" field value (e.g. "Practitioner/12345")
        String referenceFieldValue = candidateNode.path("reference").asText(null);

        // Check if it starts with our target prefix
        return referenceFieldValue != null && referenceFieldValue.startsWith(referencePrefix);
    }
}
