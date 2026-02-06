package org.openstack4j.event.transport.kafka;

import org.openstack4j.event.transport.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka/Redpanda implementation of {@link MessageTransport}.
 *
 * <p>Consumes oslo.messaging notifications from Kafka topics.
 * When OpenStack is configured with {@code transport_url = kafka://...},
 * notifications are published to topics named {@code <service>.notifications}
 * (e.g. "nova.notifications", "cinder.notifications").</p>
 *
 * <h3>oslo.messaging kafka driver behavior:</h3>
 * <ul>
 *   <li>No vhost concept — all services share the same Kafka cluster</li>
 *   <li>Topic naming: {@code <exchange>.<topic>} where exchange = service name,
 *       topic = "notifications" → e.g. "nova.notifications"</li>
 *   <li>Message format: same oslo.messaging envelope (v1 or v2)</li>
 *   <li>No AMQP routing keys — priorities are embedded in the message</li>
 * </ul>
 *
 * <h3>Dependencies:</h3>
 * <p>Requires {@code org.apache.kafka:kafka-clients} on the classpath.
 * Uses reflection to avoid compile-time dependency — if kafka-clients
 * is not present, instantiation will fail with a clear error.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * var config = new KafkaConfig("10.224.18.6:9092", "openstack-event-consumer");
 * var transport = new KafkaTransport("cluster-a", config);
 * transport.subscribe("nova", new ServiceEndpoint(), callback);
 * transport.subscribe("cinder", new ServiceEndpoint(), callback);
 * transport.start();
 * </pre>
 */
public class KafkaTransport implements MessageTransport {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransport.class);

    private final String clusterId;
    private final KafkaConfig kafkaConfig;
    private final String notificationTopic;

    /** serviceName → (endpoint, callback, topicName) */
    private final Map<String, ServiceSubscription> subscriptions = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollerThread;

    // Kafka consumer (raw Object to avoid compile-time dependency)
    private Object kafkaConsumer;

    public KafkaTransport(String clusterId, KafkaConfig kafkaConfig, String notificationTopic) {
        this.clusterId = clusterId;
        this.kafkaConfig = kafkaConfig;
        this.notificationTopic = notificationTopic;
        verifyKafkaClientAvailable();
    }

    public KafkaTransport(String clusterId, KafkaConfig kafkaConfig) {
        this(clusterId, kafkaConfig, "notifications");
    }

    @Override
    public void subscribe(String serviceName, ServiceEndpoint endpoint, MessageCallback callback) {
        String topicName = resolveTopicName(serviceName, endpoint);
        subscriptions.put(serviceName, new ServiceSubscription(endpoint, callback, topicName));
        log.debug("Registered subscription: cluster={}, service={}, topic={}",
                clusterId, serviceName, topicName);

        // If already running, update topic subscription
        if (running.get()) {
            updateTopicSubscription();
        }
    }

    @Override
    public void unsubscribe(String serviceName) {
        subscriptions.remove(serviceName);
        if (running.get()) {
            updateTopicSubscription();
        }
        log.info("Unsubscribed: cluster={}, service={}", clusterId, serviceName);
    }

    @Override
    public void start() {
        if (running.getAndSet(true)) {
            log.warn("KafkaTransport for cluster {} is already running", clusterId);
            return;
        }

        if (subscriptions.isEmpty()) {
            log.warn("No subscriptions registered for cluster {}", clusterId);
            return;
        }

        try {
            kafkaConsumer = createKafkaConsumer();
            updateTopicSubscription();
        } catch (Exception e) {
            running.set(false);
            throw new TransportException("Failed to create Kafka consumer for cluster " + clusterId, e);
        }

        // Start poll loop in a dedicated thread
        pollerThread = new Thread(this::pollLoop, "kafka-poller-" + clusterId);
        pollerThread.setDaemon(true);
        pollerThread.start();

        log.info("KafkaTransport started: cluster={}, topics={}", clusterId, getTopicNames());
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) return;

        // Interrupt poll loop
        if (pollerThread != null) {
            pollerThread.interrupt();
            try {
                pollerThread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Close Kafka consumer
        if (kafkaConsumer != null) {
            try {
                kafkaConsumer.getClass().getMethod("close").invoke(kafkaConsumer);
            } catch (Exception e) {
                log.debug("Error closing Kafka consumer: {}", e.getMessage());
            }
        }

        subscriptions.clear();
        log.info("KafkaTransport closed for cluster {}", clusterId);
    }

    @Override
    public int getActiveCount() {
        return running.get() ? subscriptions.size() : 0;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ========== Poll loop ==========

    @SuppressWarnings("unchecked")
    private void pollLoop() {
        log.info("Kafka poll loop started for cluster {}", clusterId);

        try {
            var pollMethod = kafkaConsumer.getClass().getMethod("poll", Duration.class);
            Duration pollTimeout = Duration.ofMillis(kafkaConfig.pollTimeoutMs());

            while (running.get()) {
                try {
                    // ConsumerRecords<String, byte[]> records = consumer.poll(timeout)
                    Object records = pollMethod.invoke(kafkaConsumer, pollTimeout);

                    // Iterate records
                    var iteratorMethod = records.getClass().getMethod("iterator");
                    var iterator = (Iterator<?>) iteratorMethod.invoke(records);

                    while (iterator.hasNext()) {
                        Object record = iterator.next();
                        processRecord(record);
                    }
                } catch (Exception e) {
                    if (running.get()) {
                        log.error("Error in Kafka poll loop: {}", e.getMessage(), e);
                        Thread.sleep(1_000); // back off
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Fatal error in Kafka poll loop: {}", e.getMessage(), e);
        }

        log.info("Kafka poll loop stopped for cluster {}", clusterId);
    }

    private void processRecord(Object record) {
        try {
            var topicMethod = record.getClass().getMethod("topic");
            var valueMethod = record.getClass().getMethod("value");

            String topic = (String) topicMethod.invoke(record);
            byte[] value = (byte[]) valueMethod.invoke(record);

            // Resolve service from topic: "nova.notifications" → "nova"
            String serviceName = resolveServiceFromTopic(topic);

            ServiceSubscription sub = subscriptions.get(serviceName);
            if (sub != null) {
                sub.callback.onMessage(serviceName, value);
            } else {
                log.debug("No subscription for topic {}, skipping", topic);
            }
        } catch (Exception e) {
            log.error("Error processing Kafka record: {}", e.getMessage(), e);
        }
    }

    // ========== Topic management ==========

    private String resolveTopicName(String serviceName, ServiceEndpoint endpoint) {
        String override = endpoint.getExtra("topic-override", null);
        if (override != null) return override;
        // oslo.messaging kafka driver: topic = "<exchange>.<topic>"
        // exchange defaults to service name, topic defaults to "notifications"
        String exchange = endpoint.getExtra("exchange", serviceName);
        return exchange + "." + notificationTopic;
    }

    private String resolveServiceFromTopic(String topicName) {
        // Reverse lookup: find which service owns this topic
        for (Map.Entry<String, ServiceSubscription> entry : subscriptions.entrySet()) {
            if (entry.getValue().topicName.equals(topicName)) {
                return entry.getKey();
            }
        }
        // Fallback: strip ".notifications" suffix
        int dotIdx = topicName.indexOf('.');
        return dotIdx > 0 ? topicName.substring(0, dotIdx) : topicName;
    }

    @SuppressWarnings("unchecked")
    private void updateTopicSubscription() {
        if (kafkaConsumer == null) return;

        Set<String> topics = getTopicNames();
        try {
            var subscribeMethod = kafkaConsumer.getClass().getMethod("subscribe", Collection.class);
            subscribeMethod.invoke(kafkaConsumer, topics);
            log.debug("Updated Kafka subscription: {}", topics);
        } catch (Exception e) {
            throw new TransportException("Failed to update Kafka topic subscription", e);
        }
    }

    private Set<String> getTopicNames() {
        Set<String> topics = new LinkedHashSet<>();
        for (ServiceSubscription sub : subscriptions.values()) {
            topics.add(sub.topicName);
        }
        return topics;
    }

    // ========== Kafka consumer creation via reflection ==========

    private Object createKafkaConsumer() {
        try {
            // Build properties
            Properties props = new Properties();
            props.put("bootstrap.servers", kafkaConfig.bootstrapServers());
            props.put("group.id", kafkaConfig.groupId());
            props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            props.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
            props.put("auto.offset.reset", kafkaConfig.autoOffsetReset());
            props.put("enable.auto.commit", String.valueOf(kafkaConfig.enableAutoCommit()));
            props.put("max.poll.records", String.valueOf(kafkaConfig.maxPollRecords()));

            // SASL/security if configured
            if (kafkaConfig.securityProtocol() != null) {
                props.put("security.protocol", kafkaConfig.securityProtocol());
            }
            if (kafkaConfig.saslMechanism() != null) {
                props.put("sasl.mechanism", kafkaConfig.saslMechanism());
            }
            if (kafkaConfig.saslJaasConfig() != null) {
                props.put("sasl.jaas.config", kafkaConfig.saslJaasConfig());
            }

            // Add any extra properties
            props.putAll(kafkaConfig.extraProps());

            // new KafkaConsumer<>(props)
            Class<?> consumerClass = Class.forName("org.apache.kafka.clients.consumer.KafkaConsumer");
            return consumerClass.getConstructor(Properties.class).newInstance(props);

        } catch (ClassNotFoundException e) {
            throw new TransportException(
                    "kafka-clients library not found on classpath. " +
                    "Add org.apache.kafka:kafka-clients dependency to use KafkaTransport.", e);
        } catch (Exception e) {
            throw new TransportException("Failed to create KafkaConsumer", e);
        }
    }

    private void verifyKafkaClientAvailable() {
        try {
            Class.forName("org.apache.kafka.clients.consumer.KafkaConsumer");
        } catch (ClassNotFoundException e) {
            throw new TransportException(
                    "kafka-clients library not found. Add dependency: " +
                    "org.apache.kafka:kafka-clients:3.9.0 (or compatible version)");
        }
    }

    // ========== Inner record ==========

    private record ServiceSubscription(ServiceEndpoint endpoint, MessageCallback callback, String topicName) {}
}
