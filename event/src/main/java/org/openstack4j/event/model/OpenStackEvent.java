package org.openstack4j.event.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Unified OpenStack event model.
 *
 * <p>Represents a single notification consumed from oslo.messaging.</p>
 */
public class OpenStackEvent {

    /** Cluster identifier (from configuration key) */
    private final String clusterId;

    /** OpenStack service that produced the event (nova, cinder, neutron, etc.) */
    private final String service;

    /** Resource type inferred from event_type */
    private final ResourceType resourceType;

    /** Resource ID extracted from payload (instance_id, volume_id, etc.) */
    private final String resourceId;

    /** Full oslo.messaging event_type, e.g. "compute.instance.create.end" */
    private final String eventType;

    /** Action extracted from event_type (e.g. "create", "delete", "resize") */
    private final String action;

    /** Phase extracted from event_type (e.g. "start", "end", "error") */
    private final String phase;

    /** Notification priority (INFO, WARN, ERROR, etc.) */
    private final String priority;

    /** Publisher ID from oslo.messaging (e.g. "nova-compute:host1") */
    private final String publisherId;

    /** oslo.messaging message_id */
    private final String messageId;

    /** Event timestamp */
    private final Instant timestamp;

    /** Current resource status/state extracted from payload (if available) */
    private final String status;

    /** Previous resource status/state extracted from payload (if available) */
    private final String oldStatus;

    /** Whether the status is a terminal state */
    private final boolean terminal;

    /** Full notification payload as raw JSON for custom processing */
    private final JsonNode payload;

    private OpenStackEvent(Builder builder) {
        this.clusterId = builder.clusterId;
        this.service = builder.service;
        this.resourceType = builder.resourceType;
        this.resourceId = builder.resourceId;
        this.eventType = builder.eventType;
        this.action = builder.action;
        this.phase = builder.phase;
        this.priority = builder.priority;
        this.publisherId = builder.publisherId;
        this.messageId = builder.messageId;
        this.timestamp = builder.timestamp;
        this.status = builder.status;
        this.oldStatus = builder.oldStatus;
        this.terminal = builder.terminal;
        this.payload = builder.payload;
    }

    public static Builder builder() {
        return new Builder();
    }

    // --- Getters ---

    public String getClusterId() { return clusterId; }
    public String getService() { return service; }
    public ResourceType getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getEventType() { return eventType; }
    public String getAction() { return action; }
    public String getPhase() { return phase; }
    public String getPriority() { return priority; }
    public String getPublisherId() { return publisherId; }
    public String getMessageId() { return messageId; }
    public Instant getTimestamp() { return timestamp; }
    public String getStatus() { return status; }
    public String getOldStatus() { return oldStatus; }
    public boolean isTerminal() { return terminal; }
    public JsonNode getPayload() { return payload; }

    /**
     * Unique key for subscription matching: clusterId/resourceType/resourceId
     */
    public String getSubscriptionKey() {
        return clusterId + "/" + resourceType.name().toLowerCase() + "/" + resourceId;
    }

    @Override
    public String toString() {
        return "OpenStackEvent{" +
                "clusterId='" + clusterId + '\'' +
                ", service='" + service + '\'' +
                ", resourceType=" + resourceType +
                ", resourceId='" + resourceId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", status='" + status + '\'' +
                ", oldStatus='" + oldStatus + '\'' +
                ", terminal=" + terminal +
                '}';
    }

    // ========== Builder ==========

    public static class Builder {
        private String clusterId;
        private String service;
        private ResourceType resourceType;
        private String resourceId;
        private String eventType;
        private String action;
        private String phase;
        private String priority;
        private String publisherId;
        private String messageId;
        private Instant timestamp;
        private String status;
        private String oldStatus;
        private boolean terminal;
        private JsonNode payload;

        public Builder clusterId(String clusterId) { this.clusterId = clusterId; return this; }
        public Builder service(String service) { this.service = service; return this; }
        public Builder resourceType(ResourceType resourceType) { this.resourceType = resourceType; return this; }
        public Builder resourceId(String resourceId) { this.resourceId = resourceId; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder action(String action) { this.action = action; return this; }
        public Builder phase(String phase) { this.phase = phase; return this; }
        public Builder priority(String priority) { this.priority = priority; return this; }
        public Builder publisherId(String publisherId) { this.publisherId = publisherId; return this; }
        public Builder messageId(String messageId) { this.messageId = messageId; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder oldStatus(String oldStatus) { this.oldStatus = oldStatus; return this; }
        public Builder terminal(boolean terminal) { this.terminal = terminal; return this; }
        public Builder payload(JsonNode payload) { this.payload = payload; return this; }

        public OpenStackEvent build() {
            Objects.requireNonNull(clusterId, "clusterId is required");
            Objects.requireNonNull(eventType, "eventType is required");
            if (resourceType == null) {
                resourceType = ResourceType.fromEventType(eventType);
            }
            if (timestamp == null) {
                timestamp = Instant.now();
            }
            return new OpenStackEvent(this);
        }
    }
}
