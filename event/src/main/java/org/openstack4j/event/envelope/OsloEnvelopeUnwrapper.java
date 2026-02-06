package org.openstack4j.event.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unwraps oslo.messaging messagingv2 envelope format.
 *
 * <p>messagingv2 wraps the actual notification in an envelope:</p>
 * <pre>
 * {
 *   "oslo.version": "2.0",
 *   "oslo.message": "{\"event_type\":\"compute.instance.create.end\",...}"
 * }
 * </pre>
 *
 * <p>The inner oslo.message is a JSON string that needs a second parse.</p>
 *
 * <p>messagingv1 (legacy) sends the notification directly without envelope.</p>
 */
public class OsloEnvelopeUnwrapper {

    private static final Logger log = LoggerFactory.getLogger(OsloEnvelopeUnwrapper.class);

    private static final String OSLO_VERSION = "oslo.version";
    private static final String OSLO_MESSAGE = "oslo.message";

    private final ObjectMapper objectMapper;

    public OsloEnvelopeUnwrapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Unwrap the oslo.messaging envelope and return the inner notification JSON.
     *
     * @param rawBytes raw AMQP message body
     * @return parsed notification JsonNode
     * @throws OsloEnvelopeException if parsing fails
     */
    public JsonNode unwrap(byte[] rawBytes) {
        try {
            JsonNode root = objectMapper.readTree(rawBytes);

            // Check if this is a messagingv2 envelope
            if (root.has(OSLO_VERSION) && root.has(OSLO_MESSAGE)) {
                String version = root.get(OSLO_VERSION).asText();
                if (!"2.0".equals(version)) {
                    log.warn("Unexpected oslo.version: {}, attempting to parse anyway", version);
                }
                // oslo.message is a JSON-encoded string, needs second parse
                String innerJson = root.get(OSLO_MESSAGE).asText();
                return objectMapper.readTree(innerJson);
            }

            // No envelope (messagingv1 or direct format) - return as-is
            log.debug("No oslo.messaging envelope detected, treating as raw notification");
            return root;

        } catch (Exception e) {
            throw new OsloEnvelopeException("Failed to unwrap oslo.messaging envelope", e);
        }
    }

    public static class OsloEnvelopeException extends RuntimeException {
        public OsloEnvelopeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
