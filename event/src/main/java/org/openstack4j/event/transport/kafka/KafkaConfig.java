package org.openstack4j.event.transport.kafka;

import java.util.Map;
import java.util.Properties;

/**
 * Configuration for Kafka/Redpanda transport.
 *
 * @param bootstrapServers   Kafka bootstrap servers (e.g. "10.224.18.6:9092")
 * @param groupId            consumer group ID
 * @param autoOffsetReset    offset reset policy: "earliest", "latest"
 * @param enableAutoCommit   enable auto-commit (default true)
 * @param maxPollRecords     max records per poll (default 100)
 * @param pollTimeoutMs      poll timeout in milliseconds (default 1000)
 * @param securityProtocol   security protocol (null for plaintext): "SASL_PLAINTEXT", "SASL_SSL", etc.
 * @param saslMechanism      SASL mechanism: "PLAIN", "SCRAM-SHA-256", etc.
 * @param saslJaasConfig     SASL JAAS config string
 * @param extraProps         additional Kafka consumer properties
 */
public record KafkaConfig(
        String bootstrapServers,
        String groupId,
        String autoOffsetReset,
        boolean enableAutoCommit,
        int maxPollRecords,
        long pollTimeoutMs,
        String securityProtocol,
        String saslMechanism,
        String saslJaasConfig,
        Properties extraProps
) {
    /**
     * Simple constructor with just bootstrap servers and group ID.
     */
    public KafkaConfig(String bootstrapServers, String groupId) {
        this(bootstrapServers, groupId, "earliest", true, 100, 1000,
                null, null, null, new Properties());
    }
}
