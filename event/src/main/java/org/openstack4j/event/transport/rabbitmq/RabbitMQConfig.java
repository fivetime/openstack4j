package org.openstack4j.event.transport.rabbitmq;

/**
 * Broker-level configuration for a RabbitMQ cluster.
 *
 * @param host               RabbitMQ host
 * @param port               RabbitMQ port (default 5672)
 * @param ssl                enable SSL/TLS
 * @param connectionTimeout  connection timeout in milliseconds
 * @param heartbeat          AMQP heartbeat interval in seconds
 * @param recoveryIntervalMs interval between automatic recovery attempts in milliseconds
 */
public record RabbitMQConfig(
        String host,
        int port,
        boolean ssl,
        int connectionTimeout,
        int heartbeat,
        long recoveryIntervalMs
) {
    public RabbitMQConfig(String host, int port) {
        this(host, port, false, 10_000, 30, 5_000);
    }
}
