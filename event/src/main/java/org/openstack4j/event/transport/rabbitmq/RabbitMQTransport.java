package org.openstack4j.event.transport.rabbitmq;

import com.rabbitmq.client.*;
import org.openstack4j.event.transport.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ implementation of {@link MessageTransport}.
 *
 * <p>Creates one {@link Connection} + {@link Channel} per OpenStack service (vhost).
 * Each service maps to a separate RabbitMQ virtual host as per OpenStack convention.</p>
 *
 * <p>Topology declared per vhost:</p>
 * <ul>
 *   <li>Topic exchange: service name (e.g. "nova")</li>
 *   <li>Durable queue: "openstack-event-{clusterId}-{serviceName}"</li>
 *   <li>Bindings: {topic}.info, {topic}.error, {topic}.warn</li>
 * </ul>
 *
 * <p>Uses RabbitMQ client's built-in automatic recovery for reconnection.</p>
 */
public class RabbitMQTransport implements MessageTransport {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQTransport.class);

    private final String clusterId;
    private final RabbitMQConfig brokerConfig;
    private final String topic;
    private final int prefetchCount;

    /** serviceName → active consumer session */
    private final Map<String, ConsumerSession> sessions = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    public RabbitMQTransport(String clusterId, RabbitMQConfig brokerConfig,
                             String topic, int prefetchCount) {
        this.clusterId = clusterId;
        this.brokerConfig = brokerConfig;
        this.topic = topic;
        this.prefetchCount = prefetchCount;
    }

    @Override
    public void subscribe(String serviceName, ServiceEndpoint endpoint, MessageCallback callback) {
        if (sessions.containsKey(serviceName)) {
            log.warn("Service {} already subscribed in cluster {}, replacing", serviceName, clusterId);
            unsubscribe(serviceName);
        }

        ConsumerSession session = new ConsumerSession(serviceName, endpoint, callback);
        sessions.put(serviceName, session);

        // If already running, start this consumer immediately
        if (running) {
            try {
                session.start();
            } catch (Exception e) {
                sessions.remove(serviceName);
                throw new TransportException(
                        "Failed to start consumer for " + clusterId + "/" + serviceName, e);
            }
        }
    }

    @Override
    public void unsubscribe(String serviceName) {
        ConsumerSession session = sessions.remove(serviceName);
        if (session != null) {
            session.stop();
            log.info("Unsubscribed: cluster={}, service={}", clusterId, serviceName);
        }
    }

    @Override
    public void start() {
        if (running) {
            log.warn("RabbitMQTransport for cluster {} is already running", clusterId);
            return;
        }

        for (Map.Entry<String, ConsumerSession> entry : sessions.entrySet()) {
            try {
                entry.getValue().start();
            } catch (Exception e) {
                log.error("Failed to start consumer for cluster={}, service={}: {}",
                        clusterId, entry.getKey(), e.getMessage(), e);
            }
        }
        running = true;
    }

    @Override
    public void close() {
        if (!running) return;
        running = false;

        for (ConsumerSession session : sessions.values()) {
            try {
                session.stop();
            } catch (Exception e) {
                log.warn("Error stopping consumer {}/{}: {}",
                        clusterId, session.serviceName, e.getMessage());
            }
        }
        sessions.clear();
        log.info("RabbitMQTransport closed for cluster {}", clusterId);
    }

    @Override
    public int getActiveCount() {
        return (int) sessions.values().stream().filter(ConsumerSession::isRunning).count();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ========== Inner: per-vhost consumer session ==========

    private class ConsumerSession {
        final String serviceName;
        final ServiceEndpoint endpoint;
        final MessageCallback callback;

        Connection connection;
        Channel channel;
        String consumerTag;

        ConsumerSession(String serviceName, ServiceEndpoint endpoint, MessageCallback callback) {
            this.serviceName = serviceName;
            this.endpoint = endpoint;
            this.callback = callback;
        }

        void start() throws IOException, TimeoutException {
            ConnectionFactory factory = createConnectionFactory();
            String connName = "openstack-event-" + clusterId + "-" + serviceName;
            connection = factory.newConnection(connName);
            channel = connection.createChannel();
            channel.basicQos(prefetchCount);

            // Resolve exchange and queue names
            String exchangeName = endpoint.getExtra("exchange", serviceName);
            String queueName = "openstack-event-" + clusterId + "-" + serviceName;

            // Declare topology
            channel.exchangeDeclare(exchangeName, BuiltinExchangeType.TOPIC, true);
            channel.queueDeclare(queueName, true, false, false, null);

            for (String priority : new String[]{"info", "error", "warn"}) {
                channel.queueBind(queueName, exchangeName, topic + "." + priority);
            }

            // Start consuming (auto-ack)
            consumerTag = channel.basicConsume(queueName, true, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String ct, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) {
                    try {
                        callback.onMessage(serviceName, body);
                    } catch (Exception e) {
                        log.error("Error in message callback for {}/{}: {}",
                                clusterId, serviceName, e.getMessage(), e);
                    }
                }
            });

            log.info("Started RabbitMQ consumer: cluster={}, service={}, vhost={}, exchange={}",
                    clusterId, serviceName, resolveVhost(), exchangeName);
        }

        void stop() {
            try { if (channel != null && channel.isOpen() && consumerTag != null) channel.basicCancel(consumerTag); }
            catch (Exception e) { log.debug("Error cancelling consumer: {}", e.getMessage()); }
            try { if (channel != null && channel.isOpen()) channel.close(); }
            catch (Exception e) { log.debug("Error closing channel: {}", e.getMessage()); }
            try { if (connection != null && connection.isOpen()) connection.close(); }
            catch (Exception e) { log.debug("Error closing connection: {}", e.getMessage()); }
        }

        boolean isRunning() {
            return connection != null && connection.isOpen()
                    && channel != null && channel.isOpen();
        }

        private String resolveVhost() {
            return endpoint.getExtra("vhost", "/" + serviceName);
        }

        private ConnectionFactory createConnectionFactory() {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(brokerConfig.host());
            factory.setPort(brokerConfig.port());
            factory.setUsername(endpoint.getUsername());
            factory.setPassword(endpoint.getPassword());
            factory.setVirtualHost(resolveVhost());
            factory.setConnectionTimeout(brokerConfig.connectionTimeout());
            factory.setRequestedHeartbeat(brokerConfig.heartbeat());

            // Automatic recovery
            factory.setAutomaticRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(brokerConfig.recoveryIntervalMs());
            factory.setTopologyRecoveryEnabled(true);

            if (brokerConfig.ssl()) {
                try {
                    factory.useSslProtocol();
                } catch (NoSuchAlgorithmException e) {
                    throw new TransportException("SSL not available", e);
                } catch (Exception e) {
                    throw new TransportException("Failed to configure SSL", e);
                }
            }

            return factory;
        }
    }
}
