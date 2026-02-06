package org.openstack4j.event.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for OpenStack event library.
 *
 * <p>Can be loaded from YAML via {@link EventConfigLoader} or built programmatically.</p>
 *
 * <h3>RabbitMQ YAML (default, compatible with Spring Boot version):</h3>
 * <pre>
 * openstack:
 *   event:
 *     topic: notifications
 *     clusters:
 *       cluster-a:
 *         transport: rabbitmq          # default, can be omitted
 *         rabbitmq:
 *           host: 10.224.18.6
 *           port: 5672
 *         vhosts:
 *           nova:
 *             username: nova
 *             password: password
 * </pre>
 *
 * <h3>Kafka YAML (oslo.messaging kafka driver):</h3>
 * <pre>
 * openstack:
 *   event:
 *     topic: notifications
 *     clusters:
 *       cluster-a:
 *         transport: kafka
 *         kafka:
 *           bootstrap-servers: 10.224.18.6:9092
 *           group-id: openstack-event-consumer
 *         services:
 *           nova:
 *             username: ""
 *             password: ""
 *           cinder:
 *             username: ""
 *             password: ""
 * </pre>
 */
public class EventConfig {

    private boolean enabled = true;

    /**
     * Notification topic name used by oslo.messaging.
     * Default is "notifications" which produces routing keys like "notifications.info".
     */
    private String topic = "notifications";

    /**
     * Consumer prefetch count per channel.
     */
    private int prefetchCount = 10;

    /**
     * Interval between reconnection attempts on failure.
     */
    private Duration reconnectInterval = Duration.ofSeconds(5);

    /**
     * Cluster configurations keyed by cluster ID.
     */
    private Map<String, ClusterConfig> clusters = new LinkedHashMap<>();

    // --- Getters / Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public int getPrefetchCount() { return prefetchCount; }
    public void setPrefetchCount(int prefetchCount) { this.prefetchCount = prefetchCount; }

    public Duration getReconnectInterval() { return reconnectInterval; }
    public void setReconnectInterval(Duration reconnectInterval) { this.reconnectInterval = reconnectInterval; }

    public Map<String, ClusterConfig> getClusters() { return clusters; }
    public void setClusters(Map<String, ClusterConfig> clusters) { this.clusters = clusters; }

    // ========== Nested classes ==========

    public enum TransportType { rabbitmq, kafka }

    public static class ClusterConfig {

        /**
         * Transport backend: "rabbitmq" (default) or "kafka".
         */
        private TransportType transport = TransportType.rabbitmq;

        private RabbitmqConfig rabbitmq = new RabbitmqConfig();

        private KafkaClusterConfig kafka = new KafkaClusterConfig();

        /**
         * Service configurations keyed by OpenStack service name (nova, cinder, neutron, etc.).
         * For RabbitMQ these map to vhosts; for Kafka these map to topic subscriptions.
         * "vhosts" is an alias for backward compatibility.
         */
        private Map<String, ServiceConfig> services = new LinkedHashMap<>();

        public TransportType getTransport() { return transport; }
        public void setTransport(TransportType transport) { this.transport = transport; }

        public RabbitmqConfig getRabbitmq() { return rabbitmq; }
        public void setRabbitmq(RabbitmqConfig rabbitmq) { this.rabbitmq = rabbitmq; }

        public KafkaClusterConfig getKafka() { return kafka; }
        public void setKafka(KafkaClusterConfig kafka) { this.kafka = kafka; }

        public Map<String, ServiceConfig> getServices() { return services; }
        public void setServices(Map<String, ServiceConfig> services) { this.services = services; }

        /** @deprecated use {@link #getServices()} */
        @Deprecated
        public Map<String, ServiceConfig> getVhosts() { return services; }
        /** @deprecated use {@link #setServices(Map)} */
        @Deprecated
        public void setVhosts(Map<String, ServiceConfig> vhosts) { this.services = vhosts; }
    }

    public static class RabbitmqConfig {
        private String host = "localhost";
        private int port = 5672;
        private boolean ssl = false;

        /**
         * Connection timeout in milliseconds.
         */
        private int connectionTimeout = 10_000;

        /**
         * Heartbeat interval in seconds (AMQP heartbeat, not SSE).
         */
        private int heartbeat = 30;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public boolean isSsl() { return ssl; }
        public void setSsl(boolean ssl) { this.ssl = ssl; }

        public int getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }

        public int getHeartbeat() { return heartbeat; }
        public void setHeartbeat(int heartbeat) { this.heartbeat = heartbeat; }
    }

    public static class KafkaClusterConfig {
        private String bootstrapServers = "localhost:9092";
        private String groupId = "openstack-event-consumer";
        private String autoOffsetReset = "earliest";
        private boolean enableAutoCommit = true;
        private int maxPollRecords = 100;
        private long pollTimeoutMs = 1000;
        private String securityProtocol;
        private String saslMechanism;
        private String saslJaasConfig;

        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }

        public String getAutoOffsetReset() { return autoOffsetReset; }
        public void setAutoOffsetReset(String autoOffsetReset) { this.autoOffsetReset = autoOffsetReset; }

        public boolean isEnableAutoCommit() { return enableAutoCommit; }
        public void setEnableAutoCommit(boolean enableAutoCommit) { this.enableAutoCommit = enableAutoCommit; }

        public int getMaxPollRecords() { return maxPollRecords; }
        public void setMaxPollRecords(int maxPollRecords) { this.maxPollRecords = maxPollRecords; }

        public long getPollTimeoutMs() { return pollTimeoutMs; }
        public void setPollTimeoutMs(long pollTimeoutMs) { this.pollTimeoutMs = pollTimeoutMs; }

        public String getSecurityProtocol() { return securityProtocol; }
        public void setSecurityProtocol(String securityProtocol) { this.securityProtocol = securityProtocol; }

        public String getSaslMechanism() { return saslMechanism; }
        public void setSaslMechanism(String saslMechanism) { this.saslMechanism = saslMechanism; }

        public String getSaslJaasConfig() { return saslJaasConfig; }
        public void setSaslJaasConfig(String saslJaasConfig) { this.saslJaasConfig = saslJaasConfig; }
    }

    /**
     * Per-service configuration.
     * For RabbitMQ: maps to a vhost connection (username/password/vhost/exchange).
     * For Kafka: maps to a topic subscription (topic-override, group-id-override).
     */
    public static class ServiceConfig {
        private String username;
        private String password;

        /**
         * RabbitMQ: override the vhost name. Defaults to "/" + map key (service name).
         */
        private String vhost;

        /**
         * RabbitMQ: override the exchange name. Defaults to the service name.
         * Kafka: also used as the exchange part of topic name ({exchange}.notifications).
         */
        private String exchange;

        /**
         * Kafka: override the full topic name (bypasses exchange-based naming).
         */
        private String topicOverride;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getVhost() { return vhost; }
        public void setVhost(String vhost) { this.vhost = vhost; }

        public String getExchange() { return exchange; }
        public void setExchange(String exchange) { this.exchange = exchange; }

        public String getTopicOverride() { return topicOverride; }
        public void setTopicOverride(String topicOverride) { this.topicOverride = topicOverride; }
    }
}
