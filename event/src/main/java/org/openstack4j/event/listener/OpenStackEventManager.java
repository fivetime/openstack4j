package org.openstack4j.event.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openstack4j.event.config.EventConfig;
import org.openstack4j.event.config.EventConfig.ClusterConfig;
import org.openstack4j.event.config.EventConfig.ServiceConfig;
import org.openstack4j.event.config.EventConfigLoader;
import org.openstack4j.event.envelope.NotificationParser;
import org.openstack4j.event.envelope.OsloEnvelopeUnwrapper;
import org.openstack4j.event.model.OpenStackEvent;
import org.openstack4j.event.transport.*;
import org.openstack4j.event.transport.kafka.KafkaConfig;
import org.openstack4j.event.transport.kafka.KafkaTransport;
import org.openstack4j.event.transport.rabbitmq.RabbitMQConfig;
import org.openstack4j.event.transport.rabbitmq.RabbitMQTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Main entry point for consuming OpenStack oslo.messaging notifications.
 *
 * <p>Manages transport backends (RabbitMQ or Kafka) per cluster, parses
 * oslo.messaging envelopes and notifications, and dispatches parsed
 * events to registered {@link OpenStackEventListener} instances.</p>
 *
 * <h3>Usage with YAML config:</h3>
 * <pre>
 * var manager = OpenStackEventManager.fromYaml(Path.of("config.yml"));
 * manager.addListener(event -> System.out.println(event));
 * manager.start();
 * // ...
 * manager.close();
 * </pre>
 *
 * <h3>Dynamic service management:</h3>
 * <pre>
 * var svcConfig = new EventConfig.ServiceConfig();
 * svcConfig.setUsername("heat");
 * svcConfig.setPassword("password");
 * manager.addService("cluster-a", "heat", svcConfig);
 * manager.removeService("cluster-a", "heat");
 * </pre>
 *
 * <h3>Custom transport:</h3>
 * <pre>
 * manager.setTransport("cluster-a", myCustomTransport);
 * manager.start();
 * </pre>
 */
public class OpenStackEventManager implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(OpenStackEventManager.class);

    private final EventConfig config;
    private final ObjectMapper objectMapper;
    private final OsloEnvelopeUnwrapper unwrapper;
    private final NotificationParser parser;

    private final List<OpenStackEventListener> listeners = new CopyOnWriteArrayList<>();

    /** clusterId → transport instance */
    private final Map<String, MessageTransport> transports = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    // ========== Factory methods ==========

    public static OpenStackEventManager fromYaml(Path path) {
        try {
            return new OpenStackEventManager(EventConfigLoader.fromYaml(path));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config from " + path, e);
        }
    }

    public static OpenStackEventManager fromClasspath(String resource) {
        return new OpenStackEventManager(EventConfigLoader.fromClasspath(resource));
    }

    // ========== Constructor ==========

    public OpenStackEventManager(EventConfig config) {
        this(config, new ObjectMapper());
    }

    public OpenStackEventManager(EventConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.unwrapper = new OsloEnvelopeUnwrapper(objectMapper);
        this.parser = new NotificationParser();
    }

    // ========== Listener management ==========

    public void addListener(OpenStackEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(OpenStackEventListener listener) {
        listeners.remove(listener);
    }

    // ========== Transport management ==========

    /**
     * Override the transport for a specific cluster.
     * Must be called before {@link #start()}, or the cluster must be stopped first.
     */
    public void setTransport(String clusterId, MessageTransport transport) {
        MessageTransport old = transports.put(clusterId, transport);
        if (old != null) {
            old.close();
        }
    }

    // ========== Lifecycle ==========

    public synchronized void start() {
        if (running) {
            log.warn("OpenStackEventManager is already running");
            return;
        }
        if (!config.isEnabled()) {
            log.info("OpenStack event is disabled by configuration");
            return;
        }

        log.info("Starting OpenStack notification consumers...");

        for (Map.Entry<String, ClusterConfig> entry : config.getClusters().entrySet()) {
            String clusterId = entry.getKey();
            ClusterConfig cluster = entry.getValue();

            try {
                startCluster(clusterId, cluster);
            } catch (Exception e) {
                log.error("Failed to start cluster {}: {}", clusterId, e.getMessage(), e);
            }
        }

        running = true;
        log.info("OpenStack notification consumers started (active clusters: {})", transports.size());
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;

        for (Map.Entry<String, MessageTransport> entry : transports.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("Error stopping transport for cluster {}: {}",
                        entry.getKey(), e.getMessage());
            }
        }
        transports.clear();
        log.info("All OpenStack notification consumers stopped");
    }

    @Override
    public void close() {
        stop();
    }

    // ========== Dynamic service management ==========

    /**
     * Add and start a new service consumer at runtime.
     */
    public synchronized void addService(String clusterId, String serviceName, ServiceConfig svcConfig) {
        MessageTransport transport = transports.get(clusterId);
        if (transport == null) {
            throw new IllegalArgumentException("Cluster not found or not started: " + clusterId);
        }

        ServiceEndpoint endpoint = toEndpoint(svcConfig);
        MessageCallback callback = (svc, body) -> processMessage(clusterId, svc, body);
        transport.subscribe(serviceName, endpoint, callback);

        ClusterConfig cluster = config.getClusters().get(clusterId);
        if (cluster != null) {
            cluster.getServices().put(serviceName, svcConfig);
        }
        log.info("Dynamically added service: cluster={}, service={}", clusterId, serviceName);
    }

    /**
     * Stop and remove a service consumer at runtime.
     */
    public synchronized void removeService(String clusterId, String serviceName) {
        MessageTransport transport = transports.get(clusterId);
        if (transport != null) {
            transport.unsubscribe(serviceName);
        }
        log.info("Removed service: cluster={}, service={}", clusterId, serviceName);
    }

    // ========== Internal ==========

    private void startCluster(String clusterId, ClusterConfig cluster) {
        MessageTransport transport = transports.get(clusterId);
        if (transport == null) {
            transport = createTransport(clusterId, cluster);
            transports.put(clusterId, transport);
        }

        MessageCallback callback = (serviceName, body) -> processMessage(clusterId, serviceName, body);
        for (Map.Entry<String, ServiceConfig> svcEntry : cluster.getServices().entrySet()) {
            String serviceName = svcEntry.getKey();
            ServiceConfig svc = svcEntry.getValue();

            try {
                transport.subscribe(serviceName, toEndpoint(svc), callback);
            } catch (Exception e) {
                log.error("Failed to subscribe cluster={}, service={}: {}",
                        clusterId, serviceName, e.getMessage(), e);
            }
        }

        transport.start();
    }

    private MessageTransport createTransport(String clusterId, ClusterConfig cluster) {
        return switch (cluster.getTransport()) {
            case rabbitmq -> {
                var rmq = cluster.getRabbitmq();
                var brokerConfig = new RabbitMQConfig(
                        rmq.getHost(), rmq.getPort(), rmq.isSsl(),
                        rmq.getConnectionTimeout(), rmq.getHeartbeat(),
                        config.getReconnectInterval().toMillis()
                );
                yield new RabbitMQTransport(clusterId, brokerConfig,
                        config.getTopic(), config.getPrefetchCount());
            }
            case kafka -> {
                var kc = cluster.getKafka();
                var kafkaConfig = new KafkaConfig(
                        kc.getBootstrapServers(), kc.getGroupId(),
                        kc.getAutoOffsetReset(), kc.isEnableAutoCommit(),
                        kc.getMaxPollRecords(), kc.getPollTimeoutMs(),
                        kc.getSecurityProtocol(), kc.getSaslMechanism(),
                        kc.getSaslJaasConfig(), new Properties()
                );
                yield new KafkaTransport(clusterId, kafkaConfig, config.getTopic());
            }
        };
    }

    private ServiceEndpoint toEndpoint(ServiceConfig svc) {
        var endpoint = new ServiceEndpoint(svc.getUsername(), svc.getPassword());
        if (svc.getVhost() != null) endpoint.withExtra("vhost", svc.getVhost());
        if (svc.getExchange() != null) endpoint.withExtra("exchange", svc.getExchange());
        if (svc.getTopicOverride() != null) endpoint.withExtra("topic-override", svc.getTopicOverride());
        return endpoint;
    }

    /**
     * Process a raw message: unwrap envelope → parse notification → dispatch to listeners.
     */
    private void processMessage(String clusterId, String serviceName, byte[] body) {
        try {
            JsonNode notification = unwrapper.unwrap(body);

            if (log.isDebugEnabled()) {
                String eventType = notification.has("event_type") ?
                        notification.get("event_type").asText() : "unknown";
                log.debug("Received notification: cluster={}, service={}, event_type={}",
                        clusterId, serviceName, eventType);
            }

            OpenStackEvent event = parser.parse(clusterId, serviceName, notification);
            if (event == null) {
                log.debug("Notification could not be parsed, skipping");
                return;
            }

            for (OpenStackEventListener listener : listeners) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    log.error("Error in event listener: {}", e.getMessage(), e);
                }
            }

        } catch (OsloEnvelopeUnwrapper.OsloEnvelopeException e) {
            log.error("Failed to unwrap oslo.messaging envelope: {}", e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("Raw message body: {}", new String(body));
            }
        } catch (Exception e) {
            log.error("Error processing notification from cluster={}, service={}: {}",
                    clusterId, serviceName, e.getMessage(), e);
        }
    }

    // ========== Status ==========

    public int getActiveConsumerCount() {
        return transports.values().stream().mapToInt(MessageTransport::getActiveCount).sum();
    }

    public boolean isRunning() {
        return running;
    }

    public EventConfig getConfig() {
        return config;
    }
}
