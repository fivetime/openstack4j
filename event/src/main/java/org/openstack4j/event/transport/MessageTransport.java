package org.openstack4j.event.transport;

import java.io.Closeable;

/**
 * SPI interface for message transport backends.
 *
 * <p>A transport is responsible for connecting to a message broker,
 * subscribing to oslo.messaging notification topics, and delivering
 * raw message bytes to the provided callback. The envelope unwrapping
 * and notification parsing are handled by the caller.</p>
 *
 * <p>Implementations must be thread-safe. A single transport instance
 * manages all connections for one cluster (potentially multiple services).</p>
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>{@link #subscribe(String, ServiceEndpoint, MessageCallback)} — register services</li>
 *   <li>{@link #start()} — open connections and begin consuming</li>
 *   <li>{@link #unsubscribe(String)} — dynamically remove a service (optional)</li>
 *   <li>{@link #close()} — stop all consumers and release resources</li>
 * </ol>
 *
 * <h3>Implementations:</h3>
 * <ul>
 *   <li>{@code RabbitMQTransport} — one Connection per vhost, topic exchange binding</li>
 *   <li>{@code KafkaTransport} — one KafkaConsumer, subscribe to {@code <service>.notifications} topics</li>
 * </ul>
 */
public interface MessageTransport extends Closeable {

    /**
     * Register a service subscription before or after {@link #start()}.
     *
     * <p>For RabbitMQ: creates a connection to the service's vhost,
     * declares exchange/queue/bindings, and registers a consumer.</p>
     *
     * <p>For Kafka: adds the service's notification topic to the
     * consumer's subscription list.</p>
     *
     * @param serviceName  OpenStack service name (nova, cinder, neutron, etc.)
     * @param endpoint     service-specific connection parameters
     * @param callback     receives (serviceName, raw message bytes) for each message
     * @throws TransportException if subscription fails
     */
    void subscribe(String serviceName, ServiceEndpoint endpoint, MessageCallback callback);

    /**
     * Remove a service subscription and release its resources.
     *
     * @param serviceName service to unsubscribe
     */
    void unsubscribe(String serviceName);

    /**
     * Start consuming messages from all subscribed services.
     *
     * @throws TransportException if startup fails
     */
    void start();

    /**
     * Stop all consumers and release resources.
     * Idempotent — safe to call multiple times.
     */
    @Override
    void close();

    /**
     * @return number of active service consumers
     */
    int getActiveCount();

    /**
     * @return true if transport is started and at least partially operational
     */
    boolean isRunning();
}
