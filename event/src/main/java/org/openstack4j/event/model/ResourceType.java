package org.openstack4j.event.model;

/**
 * OpenStack resource types that emit notifications.
 */
public enum ResourceType {

    // Compute (Nova)
    SERVER("compute.instance"),
    KEYPAIR("keypair"),

    // Block Storage (Cinder)
    VOLUME("volume"),
    SNAPSHOT("snapshot"),
    BACKUP("backup"),

    // Image (Glance)
    IMAGE("image"),

    // Network (Neutron)
    NETWORK("network"),
    SUBNET("subnet"),
    PORT("port"),
    ROUTER("router"),
    FLOATINGIP("floatingip"),
    SECURITY_GROUP("security_group"),
    LOADBALANCER("loadbalancer"),
    LISTENER("listener"),
    POOL("pool"),

    // Identity (Keystone)
    PROJECT("identity.project"),
    USER("identity.user"),
    ROLE("identity.role"),

    // Orchestration (Heat)
    STACK("orchestration.stack"),

    // DNS (Designate)
    DNS_ZONE("dns.zone"),
    DNS_RECORDSET("dns.recordset"),

    // Unknown / catch-all
    UNKNOWN("unknown");

    /**
     * The prefix used in oslo.messaging event_type.
     * e.g. "compute.instance" matches "compute.instance.create.end"
     */
    private final String eventTypePrefix;

    ResourceType(String eventTypePrefix) {
        this.eventTypePrefix = eventTypePrefix;
    }

    public String getEventTypePrefix() {
        return eventTypePrefix;
    }

    /**
     * Resolve ResourceType from an oslo.messaging event_type string.
     * e.g. "compute.instance.create.end" → SERVER
     *      "volume.create.end" → VOLUME
     *      "image.update" → IMAGE
     */
    public static ResourceType fromEventType(String eventType) {
        if (eventType == null) {
            return UNKNOWN;
        }
        // Longest prefix match first
        ResourceType best = UNKNOWN;
        int bestLen = 0;
        for (ResourceType rt : values()) {
            if (rt == UNKNOWN) continue;
            if (eventType.startsWith(rt.eventTypePrefix) && rt.eventTypePrefix.length() > bestLen) {
                best = rt;
                bestLen = rt.eventTypePrefix.length();
            }
        }
        return best;
    }
}
