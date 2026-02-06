package org.openstack4j.event.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import org.openstack4j.event.model.OpenStackEvent;
import org.openstack4j.event.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Parses oslo.messaging notification JSON into {@link OpenStackEvent}.
 *
 * <p>Notification structure (after envelope unwrap):</p>
 * <pre>
 * {
 *   "event_type": "compute.instance.create.end",
 *   "publisher_id": "nova-compute:host1",
 *   "timestamp": "2026-02-06 12:00:00.000000",
 *   "priority": "INFO",
 *   "message_id": "uuid-string",
 *   "payload": {
 *     "instance_id": "...",
 *     "state": "active",
 *     "old_state": "building",
 *     ...
 *   }
 * }
 * </pre>
 *
 * <p>Nova versioned notifications have a nested structure:</p>
 * <pre>
 * "payload": {
 *   "nova_object.data": {
 *     "uuid": "...",
 *     "state": "active"
 *   }
 * }
 * </pre>
 */
public class NotificationParser {

    private static final Logger log = LoggerFactory.getLogger(NotificationParser.class);

    // Terminal states per resource type
    private static final Set<String> SERVER_TERMINAL = Set.of(
            "active", "error", "deleted", "shutoff", "shelved_offloaded", "suspended", "paused", "stopped"
    );
    private static final Set<String> VOLUME_TERMINAL = Set.of(
            "available", "in-use", "error", "deleted", "error_deleting", "error_restoring"
    );
    private static final Set<String> IMAGE_TERMINAL = Set.of(
            "active", "killed", "deleted", "deactivated"
    );
    private static final Set<String> STACK_TERMINAL = Set.of(
            "create_complete", "create_failed", "update_complete", "update_failed",
            "delete_complete", "delete_failed", "rollback_complete", "rollback_failed"
    );
    private static final Set<String> GENERIC_TERMINAL = Set.of(
            "active", "error", "deleted", "available", "down"
    );

    /**
     * Parse a notification JSON node into an OpenStackEvent.
     *
     * @param clusterId cluster identifier
     * @param service   OpenStack service name (nova, cinder, etc.)
     * @param notification parsed notification JSON
     * @return OpenStackEvent or null if event cannot be parsed
     */
    public OpenStackEvent parse(String clusterId, String service, JsonNode notification) {
        try {
            String eventType = getText(notification, "event_type");
            if (eventType == null) {
                log.warn("Notification missing event_type, skipping");
                return null;
            }

            ResourceType resourceType = ResourceType.fromEventType(eventType);
            JsonNode payload = notification.get("payload");

            // Parse action and phase from event_type
            // e.g. "compute.instance.create.end" → action="create", phase="end"
            ActionPhase ap = parseActionPhase(eventType, resourceType);

            // Extract resource ID and status from payload
            String resourceId = extractResourceId(payload, resourceType);
            String status = extractStatus(payload, resourceType);
            String oldStatus = extractOldStatus(payload, resourceType);

            // Parse timestamp
            Instant timestamp = parseTimestamp(getText(notification, "timestamp"));

            // Determine if terminal
            boolean terminal = isTerminalStatus(status, resourceType);

            return OpenStackEvent.builder()
                    .clusterId(clusterId)
                    .service(service)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .eventType(eventType)
                    .action(ap.action)
                    .phase(ap.phase)
                    .priority(getText(notification, "priority"))
                    .publisherId(getText(notification, "publisher_id"))
                    .messageId(getText(notification, "message_id"))
                    .timestamp(timestamp)
                    .status(status)
                    .oldStatus(oldStatus)
                    .terminal(terminal)
                    .payload(payload)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse notification: {}", e.getMessage(), e);
            return null;
        }
    }

    // ========== Resource ID extraction ==========

    private String extractResourceId(JsonNode payload, ResourceType resourceType) {
        if (payload == null) return null;

        // Try Nova versioned notification format first
        JsonNode novaData = payload.path("nova_object.data");
        if (!novaData.isMissingNode()) {
            String uuid = getText(novaData, "uuid");
            if (uuid != null) return uuid;
        }

        // Common ID field names in order of specificity
        String[] idFields = switch (resourceType) {
            case SERVER -> new String[]{"instance_id", "uuid", "id"};
            case VOLUME -> new String[]{"volume_id", "id"};
            case SNAPSHOT -> new String[]{"snapshot_id", "id"};
            case BACKUP -> new String[]{"backup_id", "id"};
            case IMAGE -> new String[]{"id", "image_id"};
            case NETWORK -> new String[]{"network_id", "id"};
            case SUBNET -> new String[]{"subnet_id", "id"};
            case PORT -> new String[]{"port_id", "id"};
            case ROUTER -> new String[]{"router_id", "id"};
            case FLOATINGIP -> new String[]{"floatingip_id", "id"};
            case LOADBALANCER -> new String[]{"loadbalancer_id", "id"};
            case STACK -> new String[]{"stack_identity", "id"};
            default -> new String[]{"id", "resource_id", "uuid"};
        };

        for (String field : idFields) {
            String val = getText(payload, field);
            if (val != null) return val;
        }

        // Last resort: try nested resource object
        return getNestedText(payload, "resource_info", "id");
    }

    // ========== Status extraction ==========

    private String extractStatus(JsonNode payload, ResourceType resourceType) {
        if (payload == null) return null;

        // Nova versioned notification
        JsonNode novaData = payload.path("nova_object.data");
        if (!novaData.isMissingNode()) {
            String state = getText(novaData, "state");
            if (state != null) return state;
        }

        // Common status field names
        String[] statusFields = switch (resourceType) {
            case SERVER -> new String[]{"state", "status", "vm_state"};
            case VOLUME, SNAPSHOT, BACKUP -> new String[]{"status"};
            case IMAGE -> new String[]{"status"};
            case STACK -> new String[]{"state", "stack_status"};
            case LOADBALANCER -> new String[]{"operating_status", "provisioning_status", "status"};
            default -> new String[]{"status", "state"};
        };

        for (String field : statusFields) {
            String val = getText(payload, field);
            if (val != null) return val.toLowerCase();
        }
        return null;
    }

    private String extractOldStatus(JsonNode payload, ResourceType resourceType) {
        if (payload == null) return null;

        // Nova versioned notification
        JsonNode novaData = payload.path("nova_object.data");
        if (!novaData.isMissingNode()) {
            String oldState = getText(novaData, "old_state");
            if (oldState != null) return oldState;
        }

        // Common old status field names
        String[] fields = {"old_state", "old_status", "previous_state"};
        for (String field : fields) {
            String val = getText(payload, field);
            if (val != null) return val.toLowerCase();
        }
        return null;
    }

    // ========== Terminal state detection ==========

    private boolean isTerminalStatus(String status, ResourceType resourceType) {
        if (status == null) return false;
        String lower = status.toLowerCase();
        return switch (resourceType) {
            case SERVER -> SERVER_TERMINAL.contains(lower);
            case VOLUME, SNAPSHOT, BACKUP -> VOLUME_TERMINAL.contains(lower);
            case IMAGE -> IMAGE_TERMINAL.contains(lower);
            case STACK -> STACK_TERMINAL.contains(lower);
            default -> GENERIC_TERMINAL.contains(lower);
        };
    }

    // ========== Action/Phase parsing ==========

    private record ActionPhase(String action, String phase) {}

    /**
     * Parse action and phase from event_type.
     * <p>
     * Examples:
     * "compute.instance.create.end" → action="create", phase="end"
     * "volume.delete.start" → action="delete", phase="start"
     * "image.update" → action="update", phase=null
     */
    private ActionPhase parseActionPhase(String eventType, ResourceType resourceType) {
        String prefix = resourceType.getEventTypePrefix();
        String suffix = eventType.startsWith(prefix) ? eventType.substring(prefix.length()) : eventType;

        // Remove leading dot
        if (suffix.startsWith(".")) {
            suffix = suffix.substring(1);
        }

        // Split remaining parts
        String[] parts = suffix.split("\\.");
        if (parts.length == 0) {
            return new ActionPhase(null, null);
        }

        String action = parts[0];
        String phase = parts.length > 1 ? parts[parts.length - 1] : null;

        // "start", "end", "error" are phases, not actions
        if (phase != null && Set.of("start", "end", "error").contains(phase)) {
            return new ActionPhase(action, phase);
        }
        return new ActionPhase(action, null);
    }

    // ========== Timestamp parsing ==========

    /**
     * Parse oslo.messaging timestamp format: "2026-02-06 12:00:00.000000"
     */
    private Instant parseTimestamp(String timestampStr) {
        if (timestampStr == null) return Instant.now();
        try {
            // oslo.messaging uses space-separated format
            String normalized = timestampStr.replace(" ", "T");
            if (!normalized.endsWith("Z") && !normalized.contains("+")) {
                normalized += "Z";
            }
            return DateTimeFormatter.ISO_INSTANT.parse(normalized, Instant::from);
        } catch (DateTimeParseException e) {
            log.debug("Cannot parse timestamp '{}', using current time", timestampStr);
            return Instant.now();
        }
    }

    // ========== Utility ==========

    private String getText(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode val = node.get(field);
        return val.isNull() ? null : val.asText();
    }

    private String getNestedText(JsonNode node, String... path) {
        JsonNode current = node;
        for (String field : path) {
            if (current == null || !current.has(field)) return null;
            current = current.get(field);
        }
        return current != null && !current.isNull() ? current.asText() : null;
    }
}
