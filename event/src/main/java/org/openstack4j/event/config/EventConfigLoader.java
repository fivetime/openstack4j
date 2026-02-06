package org.openstack4j.event.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads {@link EventConfig} from a YAML file.
 *
 * <p>Supports both RabbitMQ and Kafka transport configurations.
 * "vhosts" and "services" keys are interchangeable for backward compatibility.</p>
 */
public class EventConfigLoader {

    /**
     * Load config from a YAML file path.
     */
    public static EventConfig fromYaml(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return fromYaml(is);
        }
    }

    /**
     * Load config from a classpath resource.
     */
    public static EventConfig fromClasspath(String resource) {
        try (InputStream is = EventConfigLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found: " + resource);
            }
            return fromYaml(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config from classpath: " + resource, e);
        }
    }

    /**
     * Load config from an InputStream.
     */
    @SuppressWarnings("unchecked")
    public static EventConfig fromYaml(InputStream is) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(is);

        // Navigate to openstack.event
        Map<String, Object> openstack = getMap(root, "openstack");
        Map<String, Object> event = getMap(openstack, "event");

        EventConfig config = new EventConfig();

        if (event.containsKey("enabled")) {
            config.setEnabled(Boolean.parseBoolean(String.valueOf(event.get("enabled"))));
        }
        if (event.containsKey("topic")) {
            config.setTopic(String.valueOf(event.get("topic")));
        }
        if (event.containsKey("prefetch-count")) {
            config.setPrefetchCount(toInt(event.get("prefetch-count"), 10));
        }
        if (event.containsKey("reconnect-interval")) {
            config.setReconnectInterval(parseDuration(String.valueOf(event.get("reconnect-interval"))));
        }

        // Parse clusters
        Map<String, Object> clusters = getMapOrEmpty(event, "clusters");
        for (Map.Entry<String, Object> entry : clusters.entrySet()) {
            String clusterId = entry.getKey();
            Map<String, Object> clusterMap = (Map<String, Object>) entry.getValue();
            config.getClusters().put(clusterId, parseCluster(clusterMap));
        }

        return config;
    }

    @SuppressWarnings("unchecked")
    private static EventConfig.ClusterConfig parseCluster(Map<String, Object> map) {
        EventConfig.ClusterConfig cluster = new EventConfig.ClusterConfig();

        // Transport type
        if (map.containsKey("transport")) {
            cluster.setTransport(EventConfig.TransportType.valueOf(
                    String.valueOf(map.get("transport")).toLowerCase()));
        }

        // RabbitMQ broker config
        Map<String, Object> rabbitmqMap = getMapOrEmpty(map, "rabbitmq");
        EventConfig.RabbitmqConfig rmq = cluster.getRabbitmq();
        if (rabbitmqMap.containsKey("host")) rmq.setHost(String.valueOf(rabbitmqMap.get("host")));
        if (rabbitmqMap.containsKey("port")) rmq.setPort(toInt(rabbitmqMap.get("port"), 5672));
        if (rabbitmqMap.containsKey("ssl")) rmq.setSsl(Boolean.parseBoolean(String.valueOf(rabbitmqMap.get("ssl"))));
        if (rabbitmqMap.containsKey("connection-timeout"))
            rmq.setConnectionTimeout(toInt(rabbitmqMap.get("connection-timeout"), 10_000));
        if (rabbitmqMap.containsKey("heartbeat"))
            rmq.setHeartbeat(toInt(rabbitmqMap.get("heartbeat"), 30));

        // Kafka broker config
        Map<String, Object> kafkaMap = getMapOrEmpty(map, "kafka");
        if (!kafkaMap.isEmpty()) {
            EventConfig.KafkaClusterConfig kc = cluster.getKafka();
            if (kafkaMap.containsKey("bootstrap-servers"))
                kc.setBootstrapServers(String.valueOf(kafkaMap.get("bootstrap-servers")));
            if (kafkaMap.containsKey("group-id"))
                kc.setGroupId(String.valueOf(kafkaMap.get("group-id")));
            if (kafkaMap.containsKey("auto-offset-reset"))
                kc.setAutoOffsetReset(String.valueOf(kafkaMap.get("auto-offset-reset")));
            if (kafkaMap.containsKey("enable-auto-commit"))
                kc.setEnableAutoCommit(Boolean.parseBoolean(String.valueOf(kafkaMap.get("enable-auto-commit"))));
            if (kafkaMap.containsKey("max-poll-records"))
                kc.setMaxPollRecords(toInt(kafkaMap.get("max-poll-records"), 100));
            if (kafkaMap.containsKey("poll-timeout-ms"))
                kc.setPollTimeoutMs(toLong(kafkaMap.get("poll-timeout-ms"), 1000));
            if (kafkaMap.containsKey("security-protocol"))
                kc.setSecurityProtocol(String.valueOf(kafkaMap.get("security-protocol")));
            if (kafkaMap.containsKey("sasl-mechanism"))
                kc.setSaslMechanism(String.valueOf(kafkaMap.get("sasl-mechanism")));
            if (kafkaMap.containsKey("sasl-jaas-config"))
                kc.setSaslJaasConfig(String.valueOf(kafkaMap.get("sasl-jaas-config")));
        }

        // Services: accept both "services" and "vhosts" keys for backward compatibility
        Map<String, Object> services = getMapOrEmpty(map, "services");
        if (services.isEmpty()) {
            services = getMapOrEmpty(map, "vhosts");
        }
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            String serviceName = entry.getKey();
            Map<String, Object> svcMap = (Map<String, Object>) entry.getValue();
            cluster.getServices().put(serviceName, parseService(svcMap));
        }

        return cluster;
    }

    private static EventConfig.ServiceConfig parseService(Map<String, Object> map) {
        EventConfig.ServiceConfig svc = new EventConfig.ServiceConfig();
        if (map.containsKey("username")) svc.setUsername(String.valueOf(map.get("username")));
        if (map.containsKey("password")) svc.setPassword(String.valueOf(map.get("password")));
        if (map.containsKey("vhost")) svc.setVhost(String.valueOf(map.get("vhost")));
        if (map.containsKey("exchange")) svc.setExchange(String.valueOf(map.get("exchange")));
        if (map.containsKey("topic-override")) svc.setTopicOverride(String.valueOf(map.get("topic-override")));
        return svc;
    }

    // ========== Utility ==========

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> parent, String key) {
        Object val = parent.get(key);
        if (val instanceof Map) return (Map<String, Object>) val;
        throw new IllegalArgumentException("Missing or invalid key: " + key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMapOrEmpty(Map<String, Object> parent, String key) {
        Object val = parent.get(key);
        if (val instanceof Map) return (Map<String, Object>) val;
        return new LinkedHashMap<>();
    }

    private static int toInt(Object val, int defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static long toLong(Object val, long defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /**
     * Parse simple duration strings: "5s", "30m", "1h", "500ms".
     * Falls back to seconds if no unit specified.
     */
    static Duration parseDuration(String str) {
        if (str == null || str.isBlank()) return Duration.ofSeconds(5);
        str = str.trim().toLowerCase();
        if (str.endsWith("ms")) return Duration.ofMillis(Long.parseLong(str.replace("ms", "").trim()));
        if (str.endsWith("s")) return Duration.ofSeconds(Long.parseLong(str.replace("s", "").trim()));
        if (str.endsWith("m")) return Duration.ofMinutes(Long.parseLong(str.replace("m", "").trim()));
        if (str.endsWith("h")) return Duration.ofHours(Long.parseLong(str.replace("h", "").trim()));
        return Duration.ofSeconds(Long.parseLong(str));
    }
}
