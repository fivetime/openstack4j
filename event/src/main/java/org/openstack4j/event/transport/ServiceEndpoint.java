package org.openstack4j.event.transport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transport-agnostic per-service connection parameters.
 *
 * <p>Contains common fields used by all transports, plus an extras map
 * for transport-specific settings. Each transport implementation
 * reads the fields it needs and ignores the rest.</p>
 *
 * <h3>RabbitMQ-specific fields:</h3>
 * <ul>
 *   <li>{@code vhost} — RabbitMQ virtual host name (default: "/" + serviceName)</li>
 *   <li>{@code exchange} — exchange name (default: serviceName)</li>
 * </ul>
 *
 * <h3>Kafka-specific fields:</h3>
 * <ul>
 *   <li>{@code topic-override} — override the kafka topic name (default: serviceName + ".notifications")</li>
 *   <li>{@code group-id-override} — override consumer group ID</li>
 * </ul>
 */
public class ServiceEndpoint {

    private String username;
    private String password;

    /**
     * Transport-specific extra parameters.
     * RabbitMQ: "vhost", "exchange"
     * Kafka: "topic-override", "group-id-override"
     */
    private Map<String, String> extras = new LinkedHashMap<>();

    public ServiceEndpoint() {}

    public ServiceEndpoint(String username, String password) {
        this.username = username;
        this.password = password;
    }

    // --- Getters / Setters ---

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Map<String, String> getExtras() { return extras; }
    public void setExtras(Map<String, String> extras) { this.extras = extras; }

    /**
     * Get an extra parameter, or return the default value.
     */
    public String getExtra(String key, String defaultValue) {
        return extras.getOrDefault(key, defaultValue);
    }

    /**
     * Set an extra parameter.
     */
    public ServiceEndpoint withExtra(String key, String value) {
        extras.put(key, value);
        return this;
    }
}
